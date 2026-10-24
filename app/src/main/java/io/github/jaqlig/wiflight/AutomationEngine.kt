package io.github.jaqlig.wiflight

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Wi-Fi callback rule engine hosted by the foreground service. */
class AutomationEngine(
    private val context: Context,
    private val onStatus: (String) -> Unit = {},
    private val onFinished: () -> Unit,
    private val onFailure: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = context.applicationContext as FlightApplication
    private val store get() = app.store
    private val controller get() = app.controller
    private val wifi get() = app.wifi
    private var wifiLease: AutoCloseable? = null
    private var timer: Job? = null
    private var transition: Job? = null
    private var previousAirplane = false
    private var lastConnection: Boolean? = null
    private var lastValidation: Boolean? = null
    private var started = false
    private var restored = false
    private var observing = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            val actual = controller.isEnabled()
            val changed = actual != previousAirplane
            previousAirplane = actual
            if (!actual) store.owned(false)
            if (changed && !controller.busy.value && store.state.value.enabled) {
                // Ownership is relinquished on explicit user intervention.
                store.owned(false)
                if (store.state.value.pauseOnManualChange) {
                    store.enabled(false)
                    store.log("Manual airplane-mode change detected; automation paused")
                    store.status("Paused after manual change")
                } else {
                    store.manualOverride(wifi.state.value.connected)
                    store.log("Manual airplane-mode change respected; automation enabled, waiting for Wi-Fi change")
                }
                schedule()
            }
        }
    }

    fun start() {
        check(!started) { "Automation engine already started" }
        started = true
        previousAirplane = controller.isEnabled()
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), false, observer,
        )
        observing = true
        try {
            wifiLease = wifi.acquire()
        } catch (e: Exception) {
            stop()
            throw e
        }
        scope.launch {
            try {
                controller.recover()
                if (!controller.isEnabled()) store.owned(false)
                val actual = controller.isEnabled()
                previousAirplane = actual
                restored = true
                schedule()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.enabled(false)
                report("Assistant recovery failed · open app")
                store.log("Assistant recovery failed: ${e.message}")
                onFailure()
            }
        }
        scope.launch {
            wifi.state.collect { network ->
                val connected = network.connected
                val connectionChanged = lastConnection != connected
                val validationChanged = lastValidation != network.validated
                val state = store.state.value
                if (state.manualOverride && state.overrideWifiConnected != connected) {
                    store.clearManualOverride()
                    store.log("Wi-Fi changed; applying automation again")
                }
                if (connectionChanged) {
                    store.log(if (connected) "Wi-Fi connected" else "Wi-Fi disconnected")
                    lastConnection = connected
                }
                if (connected && validationChanged && lastValidation != null) {
                    store.log(if (network.validated) "Wi-Fi internet validated" else "Wi-Fi internet validation lost")
                }
                lastValidation = network.validated
                if (connectionChanged || (state.requireValidatedInternet && validationChanged)) schedule()
            }
        }
        scope.launch {
            store.state.distinctUntilChanged { previous, current -> previous.hasSameAutomationSettings(current) }
                .collect { schedule() }
        }
    }

    fun recheck() { schedule() }

    private fun schedule() {
        if (!started || !restored || transition?.isActive == true) return
        timer?.cancel()
        timer = null
        val state = store.state.value
        val decision = decide(policyInput(state))
        if (decision == Decision.NONE) {
            if (!state.enabled) {
                finishPaused()
            } else {
                report(if (state.manualOverride && state.overrideWifiConnected == wifi.state.value.connected) {
                    "Active · respecting manual mode until Wi-Fi changes"
                } else if (wifi.state.value.connected && state.requireValidatedInternet && !wifi.state.value.validated) {
                    if (controller.isEnabled()) "Wi-Fi has no validated internet · keeping manual airplane mode"
                    else "Wi-Fi has no validated internet · cellular available"
                } else if (wifi.state.value.connected) {
                    if (state.owned) "On Wi-Fi · airplane mode on" else "On Wi-Fi · keeping existing airplane mode"
                } else if (controller.isEnabled()) "Waiting for Wi-Fi · keeping manual airplane mode"
                else "Waiting for Wi-Fi · cellular available")
            }
            return
        }
        val seconds = if (!state.enabled) 0 else if (decision == Decision.ENABLE) state.connectDelay else state.disconnectDelay
        report(if (decision == Decision.ENABLE) "${if (state.requireValidatedInternet) "Wi-Fi internet validated" else "Wi-Fi connected"} · switching in ${seconds}s"
            else "Restoring cellular in ${seconds}s")
        timer = scope.launch {
            delay(seconds * 1_000L)
            // Re-evaluate against current facts rather than running a stale timer's command.
            val current = store.state.value
            val now = decide(policyInput(current))
            if (now != decision) { schedule(); return@launch }
            transition = scope.launch {
                try {
                    report("Switching airplane mode…")
                    controller.setEnabled(now == Decision.ENABLE)
                    previousAirplane = controller.isEnabled()
                    // If the device dropped Wi-Fi on enable, stop rather than enter a radio-toggle loop.
                    if (now == Decision.ENABLE) {
                        delay(1_000)
                        if (!wifi.state.value.connected) {
                            store.enabled(false)
                            store.log("Wi-Fi dropped during enable; stopping automation and restoring cellular")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    store.enabled(false)
                    store.status("Paused · action failed")
                    store.log("Action failed: ${e.message ?: e.javaClass.simpleName}")
                    // Do not retry a failing privileged operation indefinitely.
                    onFailure()
                    return@launch
                }
                transition = null
                schedule()
            }
        }
    }

    private fun policyInput(state: FlightState) = PolicyInput(
        state.enabled, wifi.state.value.connected, controller.isEnabled(), state.owned,
        manualOverrideWifi = if (state.manualOverride) state.overrideWifiConnected else null,
        requireValidatedInternet = state.requireValidatedInternet,
        wifiValidated = wifi.state.value.validated,
    )

    private fun finishPaused() {
        if (controller.needsRecovery()) {
            report("Paused · open app to recover assistant")
        } else if (!store.state.value.status.startsWith("Paused after")) {
            store.status("Paused")
        }
        onFinished()
    }

    private fun report(message: String) {
        store.status(message)
        onStatus(message)
    }

    fun stop() {
        if (observing) {
            context.contentResolver.unregisterContentObserver(observer)
            observing = false
        }
        scope.cancel()
        timer = null
        transition = null
        wifiLease?.close()
        wifiLease = null
    }
}

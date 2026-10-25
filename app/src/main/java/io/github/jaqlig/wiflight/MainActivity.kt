package io.github.jaqlig.wiflight

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        val app = application as FlightApplication
        // Reopening after force-stop must actually resume the saved enabled rule.
        if (app.store.state.value.enabled) {
            runCatching { AutomationRuntime.update(this) }.onFailure {
                app.store.enabled(false)
                app.store.status("Unable to resume monitoring")
                app.store.log("Service restart failed: ${it.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FlightApplication
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(
                primary = Color(0xFF83D5BF), secondary = Color(0xFFACCCDF),
            ) else lightColorScheme(
                primary = Color(0xFF146B59), secondary = Color(0xFF38657C),
            )) {
                Surface(Modifier.fillMaxSize()) { FlightScreen(app, this) }
            }
        }
    }
}

@Composable
private fun FlightScreen(app: FlightApplication, activity: MainActivity) {
    val state by app.store.state.collectAsStateWithLifecycle()
    val busy by app.controller.busy.collectAsStateWithLifecycle()
    var permission by remember { mutableStateOf(app.controller.hasPermission()) }
    val permissionSetup = remember(permission) { if (permission) null else app.shizuku }
    val idleSetupState = remember { MutableStateFlow(ShizukuGrantState()) }
    val shizuku by (permissionSetup?.state ?: idleSetupState).collectAsStateWithLifecycle()
    val wifiMonitor = app.wifi
    val wifi by wifiMonitor.state.collectAsStateWithLifecycle()
    var airplane by remember { mutableStateOf(app.controller.isEnabled()) }
    var recovery by remember { mutableStateOf(app.controller.needsRecovery()) }
    var batteryUnrestricted by remember { mutableStateOf(BatterySettings.unrestricted(activity)) }
    val statusBarIcons = remember(activity) { StatusBarIconSettings(activity) }
    var airplaneIconHidden by remember { mutableStateOf<Boolean?>(null) }
    var iconSettingError by remember { mutableStateOf<String?>(null) }
    var iconActionError by remember { mutableStateOf<String?>(null) }
    var iconChangeGeneration by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var logsExpanded by rememberSaveable { mutableStateOf(false) }
    // Closed logs do not subscribe the whole screen to log updates.
    val visibleLogs = if (logsExpanded) app.store.logs.collectAsStateWithLifecycle().value else emptyList()
    var connectSeconds by remember(state.connectDelay) { mutableFloatStateOf(state.connectDelay.toFloat()) }
    var disconnectSeconds by remember(state.disconnectDelay) { mutableFloatStateOf(state.disconnectDelay.toFloat()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()

    fun refreshIconSetting() {
        runCatching { statusBarIcons.isAirplaneIconHidden() }
            .onSuccess { airplaneIconHidden = it; iconSettingError = null }
            .onFailure {
                airplaneIconHidden = null
                iconSettingError = it.message ?: "Unable to read the status-bar icon setting."
            }
    }

    fun refresh() {
        permission = app.controller.hasPermission()
        airplane = app.controller.isEnabled()
        recovery = app.controller.needsRecovery()
        batteryUnrestricted = BatterySettings.unrestricted(activity)
        permissionSetup?.refresh()
        refreshIconSetting()
    }

    DisposableEffect(lifecycle, permissionSetup, statusBarIcons) {
        var wifiLease: AutoCloseable? = null
        var setupObservation: AutoCloseable? = null
        var observingAirplane = false
        var observingIcon = false
        val airplaneObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                airplane = app.controller.isEnabled()
            }
        }
        val iconObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { refreshIconSetting() }
        }
        fun startObserving() {
            if (wifiLease == null) wifiLease = wifiMonitor.acquire()
            if (setupObservation == null) setupObservation = permissionSetup?.observeAvailability()
            if (!observingAirplane) {
                activity.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), false, airplaneObserver,
                )
                observingAirplane = true
            }
            if (!observingIcon) {
                runCatching {
                    activity.contentResolver.registerContentObserver(statusBarIcons.settingUri, false, iconObserver)
                }.onSuccess { observingIcon = true }.onFailure {
                    app.store.log("Status-bar icon observation unavailable: ${it.message}")
                }
            }
            refresh()
        }
        fun stopObserving() {
            setupObservation?.close()
            setupObservation = null
            wifiLease?.close()
            wifiLease = null
            if (observingAirplane) {
                activity.contentResolver.unregisterContentObserver(airplaneObserver)
                observingAirplane = false
            }
            if (observingIcon) {
                activity.contentResolver.unregisterContentObserver(iconObserver)
                observingIcon = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) startObserving()
            if (event == Lifecycle.Event.ON_PAUSE) stopObserving()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startObserving()
        onDispose { lifecycle.removeObserver(observer); stopObserving() }
    }
    LaunchedEffect(busy, shizuku.granting, state.owned) { refresh() }

    fun command(enabled: Boolean) {
        error = null
        // Application scope lets the recovery finally block finish when the activity is recreated.
        app.scope.launch {
            runCatching { app.controller.setEnabled(enabled) }
                .onFailure {
                    error = it.message ?: "Airplane-mode command failed"
                    app.store.log("Manual test failed: $error")
                }
            refresh()
        }
    }

    fun applySettings() {
        if (!app.store.state.value.enabled) return
        runCatching { AutomationRuntime.update(activity) }.onFailure {
            error = it.message
            app.store.log("Monitoring update failed: ${it.message}")
        }
    }

    Scaffold { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item("header") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("WiFlight", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Wi-Fi in. Cellular out.", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            item("automation") {
                Section {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Automation", style = MaterialTheme.typography.titleLarge)
                            Text(if (busy) "Applying change…" else state.status, style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(checked = state.enabled, enabled = !busy && !shizuku.granting && (state.enabled ||
                            (permission && !recovery)), onCheckedChange = { enabled ->
                            error = null
                            app.store.enabled(enabled)
                            app.store.log(if (enabled) "Automation enabled" else "Automation paused")
                            runCatching { AutomationRuntime.update(activity) }.onFailure {
                                app.store.enabled(false)
                                app.store.status("Unable to start monitoring")
                                app.store.log("Monitoring start failed: ${it.message}")
                                error = it.message
                            }
                        })
                    }
                    HorizontalDivider()
                    Text("Wi-Fi: ${if (wifi.connected) "connected" else "disconnected"}")
                    if (wifi.connected) Text("Internet: ${if (wifi.validated) "validated" else "not validated"}")
                    Text("Airplane mode: ${if (airplane) "on" else "off"}")
                    Text(if (state.owned && state.enabled && state.requireValidatedInternet) "App will restore cellular if Wi-Fi disconnects or loses internet validation."
                        else if (state.owned && state.enabled) "App will restore cellular when Wi-Fi disconnects."
                        else if (state.owned) "Airplane mode was enabled by this app. Use Disable to restore cellular."
                        else "Manual airplane mode is left under your control.", style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let { message -> item("error") { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (recovery) item("recovery") {
                Section {
                    Text("Restore your assistant", style = MaterialTheme.typography.titleLarge)
                    Text("An interrupted operation left a recovery record. Restore the saved assistant before resuming.")
                    Button(enabled = permission && !busy, onClick = {
                        scope.launch {
                            runCatching { app.controller.recover() }.onFailure { error = it.message }
                            refresh()
                        }
                    }) { Text("Recover assistant") }
                }
            }
            item("permission") {
                Section {
                    Text("1 · Allow airplane-mode control", style = MaterialTheme.typography.titleLarge)
                    if (permission) {
                        Text("Android permission granted. No computer or running Shizuku service is needed for automation.")
                    } else {
                        Text("Use Shizuku to grant the permission on this device, without a computer or console.")
                        Text(if (shizuku.available) "Shizuku is running." else "Install and start Shizuku first. Then return here.",
                            style = MaterialTheme.typography.bodyMedium)
                        Button(enabled = !busy && !shizuku.granting, onClick = { permissionSetup?.request() }) {
                            Text(if (shizuku.granting) "Granting…" else "Grant with Shizuku")
                        }
                        TextButton(enabled = !shizuku.granting, onClick = {
                            val intent = activity.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))
                            runCatching { activity.startActivity(intent) }.onFailure { error = "No app can open Shizuku or its setup guide." }
                        }) { Text("Open Shizuku / setup guide") }
                        Text("Alternatively, connect to a computer with ADB and run:", style = MaterialTheme.typography.bodySmall)
                        SelectionContainer {
                            Text(ADB_COMMAND, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                activity.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("ADB permission command", ADB_COMMAND))
                            }) { Text("Copy") }
                            TextButton(onClick = { refresh() }) { Text("Check permission") }
                        }
                    }
                    shizuku.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("Each airplane-mode change briefly switches the default assistant, then restores it. The app never records audio.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item("test") {
                Section {
                    Text("2 · Test on your device", style = MaterialTheme.typography.titleLarge)
                    Text("Test airplane-mode switching here.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = permission && !busy && !shizuku.granting && !state.enabled && !recovery && !airplane,
                            onClick = { command(true) }) { Text("Enable") }
                        OutlinedButton(enabled = permission && !busy && !shizuku.granting && !state.enabled && !recovery && airplane,
                            onClick = { command(false) }) { Text("Disable") }
                    }
                }
            }
            if (!batteryUnrestricted) item("battery") {
                Section {
                    Text("Allow background operation", style = MaterialTheme.typography.titleLarge)
                    Text("Remove battery restrictions so WiFlight can keep monitoring Wi-Fi when the screen is off.")
                    Text("Approve Android’s prompt. If this opens app settings instead, choose App battery usage → Unrestricted.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        runCatching { BatterySettings.request(activity) }.onFailure {
                            error = "Unable to open battery settings. Open this app’s settings and allow unrestricted battery use."
                        }
                    }) { Text("Remove battery restrictions") }
                }
            }
            item("internet") {
                Section {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Require internet access", style = MaterialTheme.typography.titleLarge)
                            Text("Before enabling airplane mode", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = state.requireValidatedInternet, enabled = !busy,
                            onCheckedChange = { app.store.requireValidatedInternet(it); applySettings() })
                    }
                    Text(if (state.requireValidatedInternet) "Use Wi-Fi only when Android validates internet access. Restore cellular if validation is lost."
                        else "Use any Wi-Fi connection, including networks without internet.", style = MaterialTheme.typography.bodyMedium)
                    Text("Internet validation does not guarantee Wi-Fi calling is available.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item("manual-changes") {
                Section {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Pause after manual changes", style = MaterialTheme.typography.titleLarge)
                            Text("When you change airplane mode yourself", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = state.pauseOnManualChange, enabled = !busy,
                            onCheckedChange = { app.store.pauseOnManualChange(it); applySettings() })
                    }
                    Text(if (state.pauseOnManualChange) "Automation pauses until you turn it back on."
                        else "Automation stays enabled and respects your manual choice until Wi-Fi next connects or disconnects.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            item("status-bar-icon") {
                Section {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Hide airplane-mode icon", style = MaterialTheme.typography.titleLarge)
                            Text("In the status bar", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = airplaneIconHidden == true,
                            enabled = permission && !busy && !shizuku.granting && airplaneIconHidden != null,
                            onCheckedChange = { hidden ->
                                iconActionError = null
                                val generation = ++iconChangeGeneration
                                runCatching { statusBarIcons.setAirplaneIconHidden(hidden) }
                                    .onSuccess { diagnostic ->
                                        app.store.log(diagnostic)
                                        scope.launch {
                                            // A single delayed read catches a tuner restoring its persistent value.
                                            delay(1_000)
                                            if (generation == iconChangeGeneration) {
                                                refreshIconSetting()
                                                if (airplaneIconHidden != null && airplaneIconHidden != hidden) {
                                                    iconActionError = "The icon setting changed back. Check SystemUI Tuner's Persistent Options."
                                                    app.store.log(iconActionError!!)
                                                }
                                            }
                                        }
                                    }
                                    .onFailure {
                                        iconActionError = it.message ?: "Unable to change the airplane icon setting."
                                        app.store.log("Status-bar icon change failed: $iconActionError")
                                    }
                                refreshIconSetting()
                            })
                    }
                    Text("Airplane mode still works normally. This system-wide setting remains after closing or uninstalling WiFlight.",
                        style = MaterialTheme.typography.bodyMedium)
                    if (!permission) Text("Grant the airplane-mode control permission above to change this setting.",
                        style = MaterialTheme.typography.bodySmall)
                    iconSettingError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                    iconActionError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                }
            }
            item("timing") {
                Section {
                    Text("Timing", style = MaterialTheme.typography.titleLarge)
                    Text("Enable after ${connectSeconds.roundToInt()}s of Wi-Fi")
                    Slider(value = connectSeconds, valueRange = Timing.MIN_SECONDS.toFloat()..Timing.MAX_SECONDS.toFloat(),
                        steps = Timing.SLIDER_STEPS, enabled = !busy,
                        onValueChange = { connectSeconds = it },
                        onValueChangeFinished = { app.store.delays(connectSeconds.roundToInt(), disconnectSeconds.roundToInt()); applySettings() })
                    Text("Restore cellular after ${disconnectSeconds.roundToInt()}s without Wi-Fi")
                    Slider(value = disconnectSeconds, valueRange = Timing.MIN_SECONDS.toFloat()..Timing.MAX_SECONDS.toFloat(),
                        steps = Timing.SLIDER_STEPS, enabled = !busy,
                        onValueChange = { disconnectSeconds = it },
                        onValueChangeFinished = { app.store.delays(connectSeconds.roundToInt(), disconnectSeconds.roundToInt()); applySettings() })
                    Text(if (state.requireValidatedInternet) "Both delays range from 1 to 30 seconds. Loss of internet validation also starts the cellular restore delay."
                        else "Both delays range from 1 to 30 seconds.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item("activity") {
                ActivityLogHeader(app.store, logsExpanded, onExpandedChange = { logsExpanded = it })
            }
            if (logsExpanded) items(visibleLogs, contentType = { "log" }) { log ->
                SelectionContainer {
                    Text(log, modifier = Modifier.padding(horizontal = 8.dp), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item("settings") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        activity.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open airplane-mode settings") }
                    OutlinedButton(onClick = {
                        runCatching { BatterySettings.openAppSettings(activity) }.onFailure {
                            error = "Unable to open this app’s settings."
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("App and battery settings") }
                    OutlinedButton(onClick = {
                        runCatching {
                            activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
                        }.onFailure {
                            error = "Unable to open notification settings."
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Status notification (optional)") }
                    Text("Enable the optional status notification to see automation status and a Pause shortcut. Notification permission is not required for the background service to run.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item("bottom") { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
internal fun Section(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

private val ADB_COMMAND = "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.WRITE_SECURE_SETTINGS"

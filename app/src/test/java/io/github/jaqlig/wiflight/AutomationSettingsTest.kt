package io.github.jaqlig.wiflight

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationSettingsTest {
    @Test fun `status and ownership updates do not restart a pending wifi timer`() = runTest {
        val initial = FlightState(enabled = true)
        val state = MutableStateFlow(initial)
        val decisions = mutableListOf<FlightState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.distinctUntilChanged { previous, current -> previous.hasSameAutomationSettings(current) }
                .collect { decisions.add(it) }
        }
        state.value = state.value.copy(status = "Restoring cellular in 3s")
        state.value = state.value.copy(owned = true)
        state.value = state.value.copy(pauseOnManualChange = true)
        assertEquals(listOf(initial), decisions)
    }

    @Test fun `changes that affect radio decisions still reach the engine`() = runTest {
        val state = MutableStateFlow(FlightState())
        val decisions = mutableListOf<FlightState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            state.distinctUntilChanged { previous, current -> previous.hasSameAutomationSettings(current) }
                .collect { decisions.add(it) }
        }
        val changes = listOf(
            state.value.copy(enabled = true),
            state.value.copy(connectDelay = 10),
            state.value.copy(disconnectDelay = 10),
            state.value.copy(manualOverride = true),
            state.value.copy(overrideWifiConnected = true),
            state.value.copy(requireValidatedInternet = false),
        )
        changes.forEach { state.value = it }
        assertEquals(listOf(FlightState()) + changes, decisions)
    }
}

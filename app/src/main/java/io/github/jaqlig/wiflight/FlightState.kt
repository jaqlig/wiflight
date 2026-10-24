package io.github.jaqlig.wiflight

data class FlightState(
    val enabled: Boolean = false,
    val owned: Boolean = false,
    val connectDelay: Int = 3,
    val disconnectDelay: Int = 3,
    val status: String = "Paused",
    val pauseOnManualChange: Boolean = false,
    val manualOverride: Boolean = false,
    val overrideWifiConnected: Boolean = false,
    val requireValidatedInternet: Boolean = true,
)

/** Compare only values that can change a scheduled radio decision, without allocating tuples. */
internal fun FlightState.hasSameAutomationSettings(other: FlightState): Boolean =
    enabled == other.enabled && connectDelay == other.connectDelay && disconnectDelay == other.disconnectDelay &&
        manualOverride == other.manualOverride && overrideWifiConnected == other.overrideWifiConnected &&
        requireValidatedInternet == other.requireValidatedInternet

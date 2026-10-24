package io.github.jaqlig.wiflight

/** Pure policy: Android callbacks and timers are handled by the service. */
data class PolicyInput(
    val enabled: Boolean,
    val wifiConnected: Boolean,
    val airplaneEnabled: Boolean,
    val owned: Boolean,
    // A manual choice is respected only while Wi-Fi still matches this snapshot.
    val manualOverrideWifi: Boolean? = null,
    val requireValidatedInternet: Boolean = false,
    val wifiValidated: Boolean = false,
)

enum class Decision { ENABLE, DISABLE, NONE }

fun decide(input: PolicyInput): Decision = with(input) {
    val usableWifi = wifiConnected && (!requireValidatedInternet || wifiValidated)
    when {
        !enabled -> if (owned && airplaneEnabled) Decision.DISABLE else Decision.NONE
        manualOverrideWifi == wifiConnected -> Decision.NONE
        usableWifi && !airplaneEnabled -> Decision.ENABLE
        !usableWifi && owned && airplaneEnabled -> Decision.DISABLE
        else -> Decision.NONE
    }
}

object Timing {
    const val MIN_SECONDS = 1
    const val MAX_SECONDS = 30
    const val SLIDER_STEPS = MAX_SECONDS - MIN_SECONDS - 1
}

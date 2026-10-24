package io.github.jaqlig.wiflight

import android.content.Context
import android.content.Intent

/** All entry points use the foreground service for monitoring and radio cleanup. */
object AutomationRuntime {
    fun update(context: Context) {
        val app = context.applicationContext as FlightApplication
        val state = app.store.state.value
        when (monitoringMode(state.enabled, state.owned, app.controller.needsRecovery())) {
            MonitoringMode.FOREGROUND -> AutomationService.start(context)
            MonitoringMode.OFF -> {
                context.stopService(Intent(context, AutomationService::class.java))
                app.store.status("Paused")
            }
        }
    }
}

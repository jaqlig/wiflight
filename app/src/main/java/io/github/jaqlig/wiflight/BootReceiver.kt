package io.github.jaqlig.wiflight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val app = context.applicationContext as FlightApplication
        val state = app.store.state.value
        if (state.enabled || state.owned || app.controller.needsRecovery()) {
            runCatching { AutomationRuntime.update(context) }.onFailure {
                app.store.log("Automatic restart unavailable: ${it.message}. Open the app to resume.")
                app.store.status("Open app to resume")
            }
        }
    }
}

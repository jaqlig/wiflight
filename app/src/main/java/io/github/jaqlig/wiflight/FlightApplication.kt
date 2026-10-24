package io.github.jaqlig.wiflight

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FlightApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var store: FlightStore
        private set
    lateinit var controller: AirplaneModeController
        private set
    val wifi by lazy { WifiMonitor(this) }
    // Background starts never need permission-setup objects or Shizuku listeners.
    val shizuku by lazy { ShizukuPermissionGrant(this, scope, controller, store) }

    override fun onCreate() {
        super.onCreate()
        store = FlightStore(this)
        controller = AssistantAirplaneController(this, store)
        scope.launch {
            runCatching { controller.recover() }.onFailure {
                store.enabled(false)
                store.status("Assistant recovery needs attention")
                store.log("Recovery failed: ${it.message}")
            }
        }
    }
}

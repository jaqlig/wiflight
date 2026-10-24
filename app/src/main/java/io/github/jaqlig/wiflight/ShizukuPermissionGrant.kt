package io.github.jaqlig.wiflight

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

data class ShizukuGrantState(
    val available: Boolean = false,
    val granting: Boolean = false,
    val message: String? = null,
)

/** Setup-only integration. No Shizuku dependency remains once Android confirms the grant. */
class ShizukuPermissionGrant(
    private val context: Context,
    private val scope: CoroutineScope,
    private val controller: AirplaneModeController,
    private val store: FlightStore,
) {
    private val mutable = MutableStateFlow(ShizukuGrantState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    /** Visible setup UI owns these listeners; normal automation never installs them. */
    fun observeAvailability(): AutoCloseable {
        val received = Shizuku.OnBinderReceivedListener { refresh() }
        val dead = Shizuku.OnBinderDeadListener {
            mutable.value = mutable.value.copy(available = false)
        }
        Shizuku.addBinderReceivedListenerSticky(received, handler)
        Shizuku.addBinderDeadListener(dead, handler)
        return AutoCloseable {
            Shizuku.removeBinderReceivedListener(received)
            Shizuku.removeBinderDeadListener(dead)
        }
    }

    fun refresh() {
        mutable.value = mutable.value.copy(available = Shizuku.pingBinder())
    }

    fun request() {
        if (job?.isActive == true) return
        if (controller.hasPermission()) {
            mutable.value = mutable.value.copy(message = "Permission already granted.")
            return
        }
        if (!Shizuku.pingBinder()) {
            mutable.value = mutable.value.copy(available = false,
                message = "Install and start Shizuku, then return here and try again.")
            return
        }
        job = scope.launch {
            mutable.value = mutable.value.copy(granting = true, message = "Waiting for Shizuku…")
            val dead = Shizuku.OnBinderDeadListener {
                job?.cancel(CancellationException("Shizuku stopped. Start it and try again."))
            }
            Shizuku.addBinderDeadListener(dead, handler)
            try {
                check(Shizuku.getVersion() >= 13) { "Update Shizuku to version 13 or newer." }
                withTimeout(60_000) { authorize() }
                mutable.value = mutable.value.copy(message = "Granting Android permission…")
                grantThroughUserService()
                check(controller.hasPermission()) { "Android did not grant the permission. Try the ADB alternative below." }
                mutable.value = mutable.value.copy(message = "Permission granted. Shizuku is no longer needed for automation.")
                store.log("WRITE_SECURE_SETTINGS granted through Shizuku")
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(message = e.message ?: "Shizuku permission setup failed.")
                store.log("Shizuku setup: ${mutable.value.message}")
                if (e is CancellationException) throw e
            } finally {
                Shizuku.removeBinderDeadListener(dead)
                mutable.value = mutable.value.copy(granting = false, available = Shizuku.pingBinder())
            }
        }
    }

    private suspend fun authorize() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
        check(!Shizuku.shouldShowRequestPermissionRationale()) {
            "Access was denied. Allow WiFlight in Shizuku's authorized applications, then retry."
        }
        val result = CompletableDeferred<Boolean>()
        val listener = Shizuku.OnRequestPermissionResultListener { code, grant ->
            if (code == REQUEST_CODE) result.complete(grant == PackageManager.PERMISSION_GRANTED)
        }
        Shizuku.addRequestPermissionResultListener(listener, handler)
        try {
            Shizuku.requestPermission(REQUEST_CODE)
            check(result.await()) { "Shizuku access was denied. You can retry or use ADB." }
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    private suspend fun grantThroughUserService() {
        val args = Shizuku.UserServiceArgs(ComponentName(context, PermissionGrantService::class.java))
            .tag("grant-secure-settings").version(BuildConfig.VERSION_CODE)
            .daemon(false).processNameSuffix("permission_grant")
        val binder = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service?.pingBinder() == true) binder.complete(service)
                else binder.completeExceptionally(IllegalStateException("Shizuku returned an unavailable service."))
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                binder.completeExceptionally(IllegalStateException("Shizuku permission service disconnected."))
            }
        }
        try {
            Shizuku.bindUserService(args, connection)
            val remote = IGrantPermissionService.Stub.asInterface(withTimeout(15_000) { binder.await() })
            val error = withContext(Dispatchers.IO) { remote.grantWriteSecureSettings(Process.myUid() / 100_000) }
            check(error.isNullOrEmpty()) { error }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { Shizuku.unbindUserService(args, connection, true) }
            }
        }
    }

    companion object { private const val REQUEST_CODE = 71 }
}

package io.github.jaqlig.wiflight

import android.Manifest
import android.os.Binder
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Short-lived Shizuku UserService, running as shell/root. No arbitrary command or package input. */
class PermissionGrantService : IGrantPermissionService.Stub() {
    override fun grantWriteSecureSettings(userId: Int): String {
        require(userId >= 0 && userId == Binder.getCallingUid() / 100_000) { "Invalid calling user" }
        val process = ProcessBuilder(
            "/system/bin/pm", "grant", "--user", userId.toString(),
            BuildConfig.APPLICATION_ID, Manifest.permission.WRITE_SECURE_SETTINGS,
        ).redirectErrorStream(true).start()
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                return "Android's permission command timed out. Check the permission status before retrying."
            }
            val output = process.inputStream.bufferedReader().use { it.readText().take(2_000).trim() }
            return if (process.exitValue() == 0) "" else output.ifBlank { "Android rejected the permission grant." }
        } finally {
            process.destroy()
        }
    }

    // Transaction ID required by Shizuku to clean up its UserService process on unbind.
    override fun destroy() { exitProcess(0) }
}

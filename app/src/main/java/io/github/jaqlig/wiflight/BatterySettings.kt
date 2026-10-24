package io.github.jaqlig.wiflight

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object BatterySettings {
    fun unrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) &&
            !context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    fun openAppSettings(activity: Activity) {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${activity.packageName}")))
    }

    fun request(activity: Activity) {
        val power = activity.getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(activity.packageName)) {
            openAppSettings(activity)
            return
        }
        // Continuous, user-enabled automation is the app's core function. Android displays its
        // own approval prompt; the app cannot silently change this battery exemption.
        val prompt = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}"))
        runCatching { activity.startActivity(prompt) }.getOrElse { openAppSettings(activity) }
    }
}

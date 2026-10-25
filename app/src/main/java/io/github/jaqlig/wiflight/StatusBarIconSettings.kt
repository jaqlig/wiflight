package io.github.jaqlig.wiflight

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** Edits SystemUI's shared setting only when requested; automation never reapplies it. */
internal class StatusBarIconSettings(private val context: Context) {
    private val resolver = context.contentResolver
    private val defaultHiddenIcons by lazy { loadDefaultHiddenIcons() }
    val settingUri get() = Settings.Secure.getUriFor(SETTING)

    fun isAirplaneIconHidden(): Boolean {
        val value = Settings.Secure.getString(resolver, SETTING)
        return StatusBarIconList.icons(value, if (value == null) defaultHiddenIcons else null)
            .contains(StatusBarIconList.AIRPLANE)
    }

    fun setAirplaneIconHidden(hidden: Boolean): String {
        check(context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED) { "Grant the airplane-mode control permission first." }
        // Read again at the time of the tap to preserve edits made by other icon-tuning apps.
        val previous = Settings.Secure.getString(resolver, SETTING)
        val updated = StatusBarIconList.withAirplaneHidden(previous, defaultHiddenIcons, hidden)
        check(Settings.Secure.putString(resolver, SETTING, updated)) {
            "Android could not change the airplane icon setting."
        }
        val readBack = Settings.Secure.getString(resolver, SETTING)
        check(StatusBarIconList.icons(readBack, defaultHiddenIcons).contains(StatusBarIconList.AIRPLANE) == hidden) {
            "The icon setting changed back. Check SystemUI Tuner's Persistent Options."
        }
        // Settings readback confirms storage, not whether the device's SystemUI rendered the change.
        return "Status-bar icons: before=${previous ?: "<default>"}; written=$updated; readback=${readBack ?: "<default>"}"
    }

    @SuppressLint("DiscouragedApi")
    private fun loadDefaultHiddenIcons(): Set<String>? = runCatching {
        // A missing setting means OEM defaults, not an empty list. Preserve those defaults too.
        val resources = context.packageManager.getResourcesForApplication(SYSTEM_UI)
        val resourceId = listOf("config_statusBarIconsToExclude", "config_statusBarIconBlackList")
            .firstNotNullOfOrNull { name ->
                resources.getIdentifier(name, "array", SYSTEM_UI).takeIf { it != 0 }
            } ?: return@runCatching null
        resources.getStringArray(resourceId).filter { it.isNotEmpty() }.toSet()
    }.getOrNull()

    private companion object {
        const val SETTING = "icon_blacklist"
        const val SYSTEM_UI = "com.android.systemui"
    }
}

internal object StatusBarIconList {
    const val AIRPLANE = "airplane"

    fun icons(value: String?, defaults: Set<String>?): Set<String> =
        value?.split(',')?.filter { it.isNotEmpty() }?.toSet()
            ?: checkNotNull(defaults) { "This device does not expose its default status-bar icon settings." }

    fun withAirplaneHidden(value: String?, defaults: Set<String>?, hidden: Boolean): String {
        val icons = icons(value, defaults)
        if (value != null && (AIRPLANE in icons) == hidden) return value
        val updated = icons.toMutableSet()
        if (hidden) updated.add(AIRPLANE) else updated.remove(AIRPLANE)
        // Match SystemUI Tuner: write an explicit list, including an empty string for no hidden icons.
        // A user-requested visibility change must not be turned into a reset of the shared setting.
        return updated.joinToString(",")
    }
}

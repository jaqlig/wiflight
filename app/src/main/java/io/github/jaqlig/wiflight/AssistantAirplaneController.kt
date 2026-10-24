package io.github.jaqlig.wiflight

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

interface AirplaneModeController {
    val busy: StateFlow<Boolean>
    fun hasPermission(): Boolean
    fun isEnabled(): Boolean
    fun needsRecovery(): Boolean
    suspend fun setEnabled(enabled: Boolean)
    suspend fun recover()
}

/** Uses the documented voice-control Settings intent; never writes airplane_mode_on directly. */
class AssistantAirplaneController(
    private val context: Context,
    private val store: FlightStore,
) : AirplaneModeController {
    private val mutex = Mutex()
    private val active = MutableStateFlow(false)
    override val busy: StateFlow<Boolean> = active
    private val resolver = context.contentResolver
    private val voiceComponent = ComponentName(context, FlightVoiceService::class.java).flattenToString()
    private val recognitionComponent = ComponentName(context, SilentRecognitionService::class.java).flattenToString()

    override fun hasPermission() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
        PackageManager.PERMISSION_GRANTED
    override fun isEnabled() = Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    override fun needsRecovery() = store.prefs.contains(JOURNAL)

    override suspend fun recover() = mutex.withLock {
        active.value = true
        try { restoreAssistant() } finally { active.value = false }
    }

    override suspend fun setEnabled(enabled: Boolean) = mutex.withLock {
        check(hasPermission()) { "Grant WRITE_SECURE_SETTINGS using the setup command first." }
        active.value = true
        try {
            restoreAssistant()
            if (isEnabled() == enabled) {
                if (!enabled) store.owned(false)
                return@withLock
            }
            check(context.packageManager.resolveActivity(
                Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE), PackageManager.MATCH_DEFAULT_ONLY,
            ) != null) { "This Android build has no voice-control airplane-mode activity." }

            val snapshot = JSONObject()
            ASSISTANT_KEYS.forEach { snapshot.put(it, Settings.Secure.getString(resolver, it) ?: JSONObject.NULL) }
            check(store.prefs.edit().putString(JOURNAL, snapshot.toString()).commit()) {
                "Could not save assistant recovery record."
            }
            // Claim ownership before requesting enable: a process death must not strand cellular off.
            if (enabled) store.owned(true)
            store.log("Requesting airplane mode ${if (enabled) "on" else "off"}")
            putSecure("assistant", voiceComponent)
            putSecure("voice_interaction_service", voiceComponent)

            val voice = withTimeout(8_000) { VoiceBridge.service.first { it != null }!! }
            val token = UUID.randomUUID().toString()
            VoiceBridge.begin(token)
            voice.showSession(Bundle().apply {
                putString("token", token)
                putBoolean("enabled", enabled)
            }, 0)
            withTimeout(8_000) {
                while (true) {
                    VoiceBridge.failure.value?.takeIf { it.first == token }?.let {
                        error(it.second)
                    }
                    if (isEnabled() == enabled) break
                    delay(100)
                }
            }
            if (!enabled) store.owned(false)
            store.log("Android reports airplane mode ${if (enabled) "on" else "off"}")
        } finally {
            withContext(NonCancellable) {
                VoiceBridge.finishSession()
                try {
                    restoreAssistant()
                } finally {
                    if (!isEnabled()) store.owned(false)
                    active.value = false
                }
            }
        }
    }

    private fun putSecure(key: String, value: String?) {
        check(Settings.Secure.putString(resolver, key, value)) { "Android rejected writing $key." }
    }

    private fun restoreAssistant() {
        val raw = store.prefs.getString(JOURNAL, null) ?: return
        check(hasPermission()) { "Restore the ADB permission to recover your previous assistant." }
        val original = JSONObject(raw)
        fun saved(key: String): String? = if (original.isNull(key)) null else original.getString(key)
        // A failed bind can clear the temporary service setting. Restore that too, while preserving
        // a genuinely different assistant selected by the user during the operation.
        val externallyChanged = listOf("assistant", "voice_interaction_service").any { key ->
            val current = Settings.Secure.getString(resolver, key)
            !current.isNullOrEmpty() && !sameComponent(current, voiceComponent) && current != saved(key)
        }
        if (!externallyChanged) {
            listOf("voice_interaction_service", "assistant", "voice_recognition_service").forEach { key ->
                val current = Settings.Secure.getString(resolver, key)
                // Preserve an independently selected recognizer, too.
                if (key != "voice_recognition_service" || current.isNullOrEmpty() ||
                    sameComponent(current, recognitionComponent) || current == saved(key)) {
                    putSecure(key, saved(key))
                }
            }
        }
        check(store.prefs.edit().remove(JOURNAL).commit())
        store.log(if (externallyChanged) "Assistant changed independently; kept new selection"
            else "Previous assistant restored")
    }

    private fun sameComponent(a: String?, b: String): Boolean = a != null &&
        ComponentName.unflattenFromString(a) == ComponentName.unflattenFromString(b)

    companion object {
        private const val JOURNAL = "assistant_recovery"
        private val ASSISTANT_KEYS = listOf("assistant", "voice_interaction_service", "voice_recognition_service")
    }
}

/** In-process bridge. Tokens prevent a delayed old session from changing the radio. */
internal object VoiceBridge {
    val service = MutableStateFlow<FlightVoiceService?>(null)
    val failure = MutableStateFlow<Pair<String, String>?>(null)
    var token: String? = null
        private set
    var session: VoiceInteractionSession? = null
    fun begin(value: String) { token = value; failure.value = null }
    fun finishSession() {
        token = null
        runCatching { session?.finish() }
        session = null
    }
}

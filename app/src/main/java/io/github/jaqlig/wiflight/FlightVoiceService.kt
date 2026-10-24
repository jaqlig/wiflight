package io.github.jaqlig.wiflight

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

class FlightVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        VoiceBridge.service.value = this
    }
    override fun onShutdown() {
        if (VoiceBridge.service.value === this) VoiceBridge.service.value = null
        super.onShutdown()
    }
    override fun onDestroy() {
        if (VoiceBridge.service.value === this) VoiceBridge.service.value = null
        super.onDestroy()
    }
}

class FlightSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = FlightSession(this)
}

private class FlightSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
        setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
    }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val arguments = args ?: run { finish(); return }
        val token = arguments.getString("token")
        if (token == null || token != VoiceBridge.token) { finish(); return }
        VoiceBridge.session = this
        try {
            startVoiceActivity(Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE)
                .putExtra(Settings.EXTRA_AIRPLANE_MODE_ENABLED, arguments.getBoolean("enabled")))
        } catch (e: Exception) {
            VoiceBridge.failure.value = token to (e.message ?: e.javaClass.simpleName)
            finish()
        }
    }
}

/** Required by voice-service metadata. This app never records or recognizes audio. */
class SilentRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onStopListening(listener: Callback) { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    override fun onCancel(listener: Callback) = Unit
}

package io.github.jaqlig.wiflight

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class FlightStore(context: Context) {
    // commit() is intentional: ownership and assistant recovery must reach disk before a radio change.
    internal val prefs = context.getSharedPreferences("flight", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private val mutableLogs = MutableStateFlow(readLogs())
    val logs = mutableLogs.asStateFlow()
    private val logTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

    private fun readLogs(): List<String> = runCatching {
        val array = JSONArray(prefs.getString("logs", "[]"))
        List(minOf(array.length(), LOG_LIMIT)) { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun read(): FlightState {
        return FlightState(
            enabled = prefs.getBoolean("enabled", false), owned = prefs.getBoolean("owned", false),
            connectDelay = prefs.getInt("connectDelay", 3).coerceIn(Timing.MIN_SECONDS, Timing.MAX_SECONDS),
            disconnectDelay = prefs.getInt("disconnectDelay", 3).coerceIn(Timing.MIN_SECONDS, Timing.MAX_SECONDS),
            status = prefs.getString("status", "Paused") ?: "Paused",
            pauseOnManualChange = prefs.getBoolean("pauseOnManualChange", false),
            manualOverride = prefs.getBoolean("manualOverride", false),
            overrideWifiConnected = prefs.getBoolean("overrideWifiConnected", false),
            requireValidatedInternet = prefs.getBoolean("requireValidatedInternet", true),
        )
    }

    fun enabled(value: Boolean) {
        if (mutable.value.enabled == value) return
        check(prefs.edit().putBoolean("enabled", value).putBoolean("manualOverride", false).commit())
        mutable.value = mutable.value.copy(enabled = value, manualOverride = false)
    }
    fun owned(value: Boolean) {
        if (mutable.value.owned == value) return
        check(prefs.edit().putBoolean("owned", value).commit())
        mutable.value = mutable.value.copy(owned = value)
    }
    fun requireValidatedInternet(value: Boolean) {
        if (mutable.value.requireValidatedInternet == value) return
        prefs.edit().putBoolean("requireValidatedInternet", value).apply()
        mutable.value = mutable.value.copy(requireValidatedInternet = value)
    }
    fun delays(connect: Int, disconnect: Int) {
        val connectSeconds = connect.coerceIn(Timing.MIN_SECONDS, Timing.MAX_SECONDS)
        val disconnectSeconds = disconnect.coerceIn(Timing.MIN_SECONDS, Timing.MAX_SECONDS)
        if (mutable.value.connectDelay == connectSeconds && mutable.value.disconnectDelay == disconnectSeconds) return
        prefs.edit().putInt("connectDelay", connectSeconds).putInt("disconnectDelay", disconnectSeconds).apply()
        mutable.value = mutable.value.copy(connectDelay = connectSeconds, disconnectDelay = disconnectSeconds)
    }
    fun status(value: String) {
        if (mutable.value.status == value) return
        prefs.edit().putString("status", value).apply()
        mutable.value = mutable.value.copy(status = value)
    }
    fun log(message: String) {
        val time = LocalTime.now().format(logTimeFormat)
        val previous = mutableLogs.value
        val logs = buildList(minOf(previous.size + 1, LOG_LIMIT)) {
            add("$time  $message")
            for (index in 0 until minOf(previous.size, LOG_LIMIT - 1)) add(previous[index])
        }
        prefs.edit().putString("logs", JSONArray(logs).toString()).apply()
        mutableLogs.value = logs
    }
    fun clearLog() {
        prefs.edit().remove("logs").apply()
        mutableLogs.value = emptyList()
    }
    fun pauseOnManualChange(value: Boolean) {
        if (mutable.value.pauseOnManualChange == value) return
        prefs.edit().putBoolean("pauseOnManualChange", value).apply()
        mutable.value = mutable.value.copy(pauseOnManualChange = value)
    }
    fun manualOverride(wifiConnected: Boolean) {
        prefs.edit().putBoolean("manualOverride", true).putBoolean("overrideWifiConnected", wifiConnected).apply()
        mutable.value = mutable.value.copy(manualOverride = true, overrideWifiConnected = wifiConnected)
    }
    fun clearManualOverride() {
        if (!mutable.value.manualOverride) return
        prefs.edit().putBoolean("manualOverride", false).apply()
        mutable.value = mutable.value.copy(manualOverride = false)
    }
    companion object { private const val LOG_LIMIT = 80 }
}

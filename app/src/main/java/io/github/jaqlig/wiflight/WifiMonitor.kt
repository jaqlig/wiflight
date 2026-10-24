package io.github.jaqlig.wiflight

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WifiState(val connected: Boolean = false, val validated: Boolean = false)

/** One physical Wi-Fi callback shared by the foreground service and visible screen. */
class WifiMonitor(context: Context) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val networks = mutableMapOf<Network, Boolean>()
    private val mutable = MutableStateFlow(WifiState())
    val state = mutable.asStateFlow()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val registration = SharedRegistration(::start, ::stop)

    fun acquire(): AutoCloseable {
        check(Looper.myLooper() == Looper.getMainLooper())
        val lease = registration.acquire()
        return AutoCloseable {
            check(Looper.myLooper() == Looper.getMainLooper())
            lease.close()
        }
    }

    private fun publish() {
        val connected = networks.isNotEmpty()
        val validated = networks.values.any { it }
        if (mutable.value.connected != connected || mutable.value.validated != validated) {
            mutable.value = WifiState(connected, validated)
        }
    }

    private fun start() {
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (callback !== this) return
                networks.putIfAbsent(network, false)
                publish()
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (callback !== this) return
                networks[network] = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                publish()
            }
            override fun onLost(network: Network) {
                if (callback !== this) return
                networks.remove(network)
                publish()
            }
        }
        callback = listener
        try {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), listener, Handler(Looper.getMainLooper()))
            // Synchronous initial state prevents a false disconnect while waiting for callbacks.
            connectivity.allNetworks.forEach { network ->
                val caps = connectivity.getNetworkCapabilities(network) ?: return@forEach
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    networks[network] = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            }
            publish()
        } catch (e: Exception) {
            callback = null
            runCatching { connectivity.unregisterNetworkCallback(listener) }
            networks.clear()
            throw e
        }
    }

    private fun stop() {
        val listener = callback ?: return
        callback = null // Ignore callbacks already queued by the old registration.
        try { connectivity.unregisterNetworkCallback(listener) }
        finally { networks.clear(); publish() }
    }
}

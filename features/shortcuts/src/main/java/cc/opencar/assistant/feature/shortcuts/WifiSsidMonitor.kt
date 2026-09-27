package cc.opencar.assistant.feature.shortcuts

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Invokes [onChanged] on connected Wi‑Fi SSID transitions. Listens for Wi‑Fi network
 * callbacks only while an enabled shortcut has a [ShortcutTrigger.WifiSsid] trigger.
 */
class WifiSsidMonitor(
    context: Context,
    private val store: ShortcutStore,
    private val onChanged: (ssid: String?) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var job: Job? = null
    @Volatile private var lastSsid: String? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            store.shortcuts
                .map { list -> list.any { s -> s.enabled && s.triggers.any { it is ShortcutTrigger.WifiSsid } } }
                .distinctUntilChanged()
                .collect { needed -> if (needed) register() else unregister() }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        unregister()
    }

    fun currentSsid(): String? {
        val info = runCatching { wifi?.connectionInfo }.getOrNull() ?: return null
        val raw = info.ssid ?: return null
        val cleaned = raw.trim().removePrefix("\"").removeSuffix("\"")
        if (cleaned.isEmpty() || cleaned.equals("<unknown ssid>", true) || cleaned == "0x") {
            return null
        }
        return cleaned
    }

    private fun check() {
        val ssid = currentSsid()
        if (ssid != lastSsid) {
            lastSsid = ssid
            Log.i(TAG, "wifi ssid -> ${ssid ?: "(none)"}")
            onChanged(ssid)
        }
    }

    @Synchronized
    private fun register() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = check()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = check()
            override fun onLost(network: Network) = check()
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { connectivity?.registerNetworkCallback(request, cb) }
            .onSuccess { callback = cb }
            .onFailure { Log.w(TAG, "network callback unavailable: ${it.message}") }
        check()
    }

    @Synchronized
    private fun unregister() {
        val cb = callback ?: return
        callback = null
        runCatching { connectivity?.unregisterNetworkCallback(cb) }
    }

    companion object {
        private const val TAG = "WifiSsidMonitor"
    }
}

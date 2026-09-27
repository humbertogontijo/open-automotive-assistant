package cc.opencar.assistant.feature.web

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import cc.opencar.assistant.protocol.OaaBuild
import cc.opencar.assistant.protocol.OaaMdns

/** Announces `_oaa-car._tcp` so hubs on the LAN can offer to pair; only while unpaired. */
class CarMdnsAdvertiser(context: Context, private val port: Int) {
    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun start(nodeId: String, name: String, integration: String) {
        val mgr = nsd ?: return
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "OAA $name".take(63)
            serviceType = OaaMdns.CAR_SERVICE_TYPE.removeSuffix(".local.")
            setPort(this@CarMdnsAdvertiser.port)
            setAttribute(OaaMdns.TXT_ID, nodeId)
            setAttribute(OaaMdns.TXT_NAME, name.take(64))
            setAttribute(OaaMdns.TXT_INTEGRATION, integration)
            setAttribute(OaaMdns.TXT_VERSION, OaaBuild.VERSION)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "advertising ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed ($errorCode)")
                synchronized(this@CarMdnsAdvertiser) { if (listener === this) listener = null }
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        listener = l
        runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { listener = null; Log.w(TAG, "mDNS unavailable", it) }
    }

    @Synchronized
    fun stop() {
        val l = listener ?: return
        listener = null
        runCatching { nsd?.unregisterService(l) }
    }

    private companion object {
        const val TAG = "OaaCarMdns"
    }
}

package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaBuild
import cc.opencar.assistant.protocol.OaaMdns
import cc.opencar.assistant.protocol.OaaPorts
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

class MdnsPublisher(
    private val humanPort: Int = OaaPorts.HUMAN_DEFAULT,
    private val nodePort: Int = OaaPorts.NODE_DEFAULT,
) {
    private var jmdns: JmDNS? = null

    fun start() {
        runCatching {
            val mdns = JmDNS.create(InetAddress.getLocalHost())
            val info = ServiceInfo.create(
                OaaMdns.SERVICE_TYPE,
                OaaMdns.SERVICE_NAME,
                humanPort,
                0,
                0,
                mapOf(
                    "human_port" to humanPort.toString(),
                    "node_port" to nodePort.toString(),
                    "version" to OaaBuild.VERSION,
                    "path" to "/",
                ),
            )
            mdns.registerService(info)
            jmdns = mdns
            println("OAA mDNS published ${OaaMdns.SERVICE_TYPE} human=$humanPort node=$nodePort")
        }.onFailure {
            System.err.println("OAA mDNS unavailable: ${it.message}")
        }
    }

    fun stop() {
        runCatching { jmdns?.unregisterAllServices() }
        runCatching { jmdns?.close() }
        jmdns = null
    }
}

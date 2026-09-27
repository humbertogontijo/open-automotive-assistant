package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaBuild
import cc.opencar.assistant.protocol.OaaMdns
import cc.opencar.assistant.protocol.OaaPorts
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/** An unpaired car announcing `_oaa-car._tcp` on the LAN. */
data class DiscoveredCar(
    val id: String,
    val name: String,
    val integration: String?,
    val version: String?,
    val host: String,
    val port: Int,
    val seenAtMs: Long,
)

/** Cars seen by mDNS, keyed by node id. */
class DiscoveredCars {
    private val cars = ConcurrentHashMap<String, DiscoveredCar>()
    private val byService = ConcurrentHashMap<String, String>()

    fun put(car: DiscoveredCar, serviceName: String = car.id) {
        cars[car.id] = car
        byService[serviceName] = car.id
    }

    fun removeService(serviceName: String) {
        byService.remove(serviceName)?.let { cars.remove(it) }
    }

    fun get(id: String): DiscoveredCar? = cars[id]

    fun all(): List<DiscoveredCar> = cars.values.sortedBy { it.name.lowercase() }
}

/** Publishes the hub (`_oaa-hub._tcp`) and browses for cars waiting to pair. */
class HubMdns(
    private val identity: HubIdentity,
    private val discovered: DiscoveredCars,
    private val humanPort: Int = OaaPorts.HUMAN_DEFAULT,
    private val nodePort: Int = OaaPorts.NODE_DEFAULT,
) {
    private var jmdns: JmDNS? = null

    private val listener = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            event.dns.requestServiceInfo(event.type, event.name, RESOLVE_TIMEOUT_MS)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            discovered.removeService(event.name)
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info ?: return
            val id = info.getPropertyString(OaaMdns.TXT_ID)?.takeIf { it.isNotBlank() } ?: return
            val host = (info.inet4Addresses.firstOrNull() ?: info.inetAddresses.firstOrNull())?.hostAddress ?: return
            discovered.put(
                DiscoveredCar(
                    id = id,
                    name = info.getPropertyString(OaaMdns.TXT_NAME)?.takeIf { it.isNotBlank() } ?: info.name,
                    integration = info.getPropertyString(OaaMdns.TXT_INTEGRATION),
                    version = info.getPropertyString(OaaMdns.TXT_VERSION),
                    host = host,
                    port = info.port,
                    seenAtMs = System.currentTimeMillis(),
                ),
                serviceName = event.name,
            )
        }
    }

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
                    OaaMdns.TXT_HUB_ID to identity.id,
                ),
            )
            mdns.registerService(info)
            mdns.addServiceListener(OaaMdns.CAR_SERVICE_TYPE, listener)
            jmdns = mdns
            println("OAA mDNS published ${OaaMdns.SERVICE_TYPE} human=$humanPort node=$nodePort; browsing ${OaaMdns.CAR_SERVICE_TYPE}")
        }.onFailure {
            System.err.println("OAA mDNS unavailable: ${it.message}")
        }
    }

    fun stop() {
        runCatching { jmdns?.removeServiceListener(OaaMdns.CAR_SERVICE_TYPE, listener) }
        runCatching { jmdns?.unregisterAllServices() }
        runCatching { jmdns?.close() }
        jmdns = null
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 3_000L
    }
}

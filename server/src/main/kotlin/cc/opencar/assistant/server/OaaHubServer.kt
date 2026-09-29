package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.protocol.OaaPorts
import io.ktor.serialization.gson.gson
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import java.io.File
import java.time.Duration
import java.util.logging.Logger

/**
 * Self-hosted hub with two faces (ADR-0003): the human face (SPA, auth, fleet,
 * proxy, signaling, OTA admin) and the node face (pairing, car sessions, OTA downloads).
 */
class OaaHubServer(
    dataDir: File,
    private val humanPort: Int = OaaPorts.HUMAN_DEFAULT,
    private val nodePort: Int = OaaPorts.NODE_DEFAULT,
    private val demoNode: ((EventBus) -> DemoNodeTransport)? =
        if (HubConfig.demoNode) { bus -> DemoNodeTransport("demo", bus) } else null,
    private val mdnsEnabled: Boolean = HubConfig.mdnsEnabled,
    publicNode: PublicNode = PublicNode(),
    private val fetchCarRelease: Boolean = true,
) {
    internal val hub = HubContext(dataDir, humanPort, nodePort, publicNode = publicNode)
    private var humanEngine: ApplicationEngine? = null
    private var nodeEngine: ApplicationEngine? = null
    private var mdns: HubMdns? = null

    fun start(wait: Boolean = false) {
        demoNode?.let { create ->
            val demo = create(hub.eventBus)
            hub.registry.registerDemo(demo)
            demo.start()
            log.info("demo node registered")
        }
        nodeEngine = embeddedServer(CIO, port = nodePort, host = "0.0.0.0") {
            install(WebSockets) {
                // A car that drops off the network without closing (Wi-Fi gone) is detached within ~30 s.
                pingPeriod = Duration.ofSeconds(20)
                timeout = Duration.ofSeconds(30)
            }
            install(ContentNegotiation) { gson() }
            install(PartialContent)
            routing { nodeFaceRoutes(hub) }
        }.also { it.start(wait = false) }
        log.info("node face listening on :$nodePort")
        if (mdnsEnabled) {
            mdns = HubMdns(hub.identity, hub.discovered, humanPort, nodePort).also { it.start() }
        }
        humanEngine = embeddedServer(CIO, port = humanPort, host = "0.0.0.0") {
            install(ContentNegotiation) { gson() }
            install(WebSockets)
            routing {
                get(OaaPaths.HEALTH) { call.respond(mapOf("ok" to true, "face" to "human")) }
                staticRoutes(isNode = { hub.registry.get(it) != null })
                authRoutes(hub)
                webRtcRoutes(hub)
                otaRoutes(hub)
                inviteRoutes(hub)
                proxyRoutes(hub)
            }
        }
        log.info("human face listening on :$humanPort (data=${hub.dataDir.absolutePath})")
        log.info("public node URL: ${hub.publicNode.dialUrl ?: "none"}")
        hub.publicCheck.refresh()
        if (fetchCarRelease) hub.carRelease.start()
        humanEngine?.start(wait = wait)
    }

    fun stop() {
        mdns?.stop()
        humanEngine?.stop(1000, 2000)
        nodeEngine?.stop(1000, 2000)
        hub.registry.flush()
        hub.auth.flush()
    }

    private companion object {
        val log: Logger = Logger.getLogger("oaa.hub")
    }
}

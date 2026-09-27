package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaBuild
import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaHeaders
import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.protocol.OaaRoles
import cc.opencar.assistant.protocol.OaaRpc
import cc.opencar.assistant.protocol.OaaUiEvents
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.method
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject

/** Fleet, the per-car event stream, and the `/api` + `/debug` proxy to the selected car. */
internal fun Routing.proxyRoutes(hub: HubContext) = with(hub) {
    get(OaaPaths.STATUS) {
        val user = call.requireUser() ?: return@get
        if (call.nodeIdOrNull() != null) return@get proxy(call)
        call.respond(
            mapOf(
                "role" to OaaRoles.HUB,
                "version" to OaaBuild.VERSION,
                "fleet" to fleet(),
                "setup" to mapOf("complete" to !auth.needsSetup()),
                "user" to userPublic(user),
                "webrtc" to mapOf("v" to OaaFrames.VERSION, "turn" to ice.turnEnabled),
                "hub" to mapOf(
                    "id" to identity.id,
                    "name" to identity.name,
                    "publicNodeUrl" to publicNode.url,
                    "publicDialUrl" to publicNode.dialUrl,
                    "publicHumanUrl" to HubConfig.publicHumanUrl,
                    "nodePort" to nodePort,
                ),
            ),
        )
    }

    get(OaaPaths.I18N) {
        call.requireUser() ?: return@get
        if (call.nodeIdOrNull() != null) return@get proxy(call)
        call.respond(HubI18n.bundle(call.request.queryParameters["locale"] ?: call.request.header(HttpHeaders.AcceptLanguage)))
    }

    post("/api/locale") {
        call.requireUser() ?: return@post
        if (call.nodeIdOrNull() != null) return@post proxy(call)
        val locale = call.receiveParameters()["locale"] ?: call.request.queryParameters["locale"]
        call.respond(HubI18n.bundle(locale) + ("ok" to true))
    }

    get(OaaPaths.NODES) {
        call.requireUser() ?: return@get
        call.respond(fleet())
    }

    post(OaaPaths.NODES_PAIRING) {
        call.requireAdmin() ?: return@post
        val offer = registry.createPairingCode()
        call.respond(
            mapOf(
                "code" to offer.code,
                "expiresAtMs" to offer.expiresAtMs,
                "publicNodeUrl" to publicNode.url,
                "publicDialUrl" to publicNode.dialUrl,
            ),
        )
    }

    delete("${OaaPaths.NODES}/{nodeId}") {
        call.requireAdmin() ?: return@delete
        call.respond(mapOf("ok" to registry.remove(call.parameters["nodeId"].orEmpty())))
    }

    webSocket(OaaPaths.EVENTS) {
        if (call.currentUser() == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "auth required"))
            return@webSocket
        }
        val client = EventBus.UiClient(call.nodeIdOrNull(), this)
        eventBus.attach(client)
        try {
            client.offer(JSONObject().put("t", OaaUiEvents.HELLO).put("role", OaaRoles.HUB).toString())
            for (frame in incoming) {
                if (frame is Frame.Text) answerPing(frame.readText())
            }
        } finally {
            eventBus.detach(client)
        }
    }

    webSocket(OaaPaths.DEBUG_LOGS_STREAM) {
        val user = call.currentUser()
        val nodeId = call.nodeIdOrNull()
        if (user == null || !user.isAdmin || nodeId == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "admin and node required"))
            return@webSocket
        }
        val peer = SignalPeer { line -> send(Frame.Text(line)) }
        try {
            if (!logs.subscribe(nodeId, peer, call.request.queryParameters["token"])) {
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "node offline"))
                return@webSocket
            }
            for (frame in incoming) Unit
        } finally {
            logs.unsubscribe(nodeId, peer)
        }
    }

    route("/api/{path...}") { proxyMethods(hub, adminOnly = false) }
    route(OaaPaths.DEBUG) { proxyMethods(hub, adminOnly = true) }
    route("${OaaPaths.DEBUG}/{path...}") { proxyMethods(hub, adminOnly = true) }
}

private val PROXIED_METHODS = listOf(HttpMethod.Get, HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete)

private fun Route.proxyMethods(hub: HubContext, adminOnly: Boolean) {
    for (m in PROXIED_METHODS) {
        method(m) {
            handle {
                with(hub) {
                    val user = if (adminOnly) call.requireAdmin() else call.requireUser()
                    if (user != null) proxy(call)
                }
            }
        }
    }
}

/** Forward the request to the selected car as an rpc frame and relay its response. */
private suspend fun HubContext.proxy(call: ApplicationCall) {
    val nodeId = call.nodeIdOrNull()
        ?: return call.respondError(HttpStatusCode.BadRequest, "select a car (${OaaHeaders.NODE})")
    val session = registry.session(nodeId)
        ?: return call.respondError(HttpStatusCode.ServiceUnavailable, "node offline")
    val method = call.request.httpMethod
    val body = if (method == HttpMethod.Post || method == HttpMethod.Put) {
        val bytes = call.receiveChannel().readRemaining(OaaRpc.MAX_BODY_BYTES + 1L).readBytes()
        if (bytes.size > OaaRpc.MAX_BODY_BYTES) {
            return call.respondError(HttpStatusCode.PayloadTooLarge, "request body over ${OaaRpc.MAX_BODY_BYTES} bytes")
        }
        bytes
    } else {
        null
    }
    val result = try {
        session.rpc(
            method = method.value,
            path = call.request.path(),
            query = call.request.queryString().takeIf { it.isNotEmpty() },
            contentType = call.request.header(HttpHeaders.ContentType),
            body = body,
        )
    } catch (_: TimeoutCancellationException) {
        return call.respondError(HttpStatusCode.GatewayTimeout, "node did not answer")
    } catch (e: Exception) {
        return call.respondError(HttpStatusCode.BadGateway, e.message ?: "rpc failed")
    }
    if (result.body.size > OaaRpc.MAX_BODY_BYTES) {
        return call.respondError(HttpStatusCode.PayloadTooLarge, "response over ${OaaRpc.MAX_BODY_BYTES} bytes")
    }
    result.contentDisposition?.let { call.response.header(HttpHeaders.ContentDisposition, it) }
    call.respondBytes(
        result.body,
        result.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() } ?: ContentType.Application.Json,
        HttpStatusCode.fromValue(result.status),
    )
}

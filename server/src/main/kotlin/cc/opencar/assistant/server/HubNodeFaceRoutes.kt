package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaOta
import cc.opencar.assistant.protocol.OaaPaths
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import org.json.JSONObject

/** Node face (8788): pairing, the car session, and OTA downloads. Nothing else. */
internal fun Routing.nodeFaceRoutes(hub: HubContext) = with(hub) {
    get(OaaPaths.HEALTH) {
        call.respond(mapOf("ok" to true, "face" to "node"))
    }

    post(OaaPaths.NODES_PAIR) {
        handlePair(call)
    }

    webSocket(OaaPaths.NODES_SESSION) {
        val node = call.bearerNode(hub)
        if (node == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid token"))
            return@webSocket
        }
        val session = NodeSession(node.id, this, eventBus, nodeListener)
        registry.attachSession(node.id, session)
        try {
            for (frame in incoming) {
                if (frame is Frame.Text) session.handleFrame(frame.readText())
            }
        } finally {
            if (registry.detachSession(node.id, session) && registry.session(node.id) == null) {
                signalRelay.onNodeDetached(node.id)
            }
        }
    }

    get("${OaaPaths.NODES_ARTIFACTS}/{sha}") {
        if (call.bearerNode(hub) == null) {
            call.respondError(HttpStatusCode.Unauthorized, "node token required")
            return@get
        }
        val file = artifacts.file(call.parameters["sha"].orEmpty())
        if (file == null) {
            call.respondError(HttpStatusCode.NotFound, "unknown artifact")
            return@get
        }
        call.respond(LocalFileContent(file, ContentType.parse(OaaOta.APK_MIME)))
    }

    get("/{path...}") {
        call.respondError(HttpStatusCode.NotFound, "node face: pair, session and artifacts only")
    }
    post("/{path...}") {
        call.respondError(HttpStatusCode.NotFound, "node face: pair, session and artifacts only")
    }
}

private fun ApplicationCall.bearerNode(hub: HubContext): NodeRecord? {
    val token = request.queryParameters["token"]
        ?: request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
    if (token.isNullOrBlank()) return null
    return hub.registry.resolveToken(token)
}

private suspend fun HubContext.handlePair(call: ApplicationCall) {
    val obj = runCatching { JSONObject(call.receiveText()) }.getOrNull()
    if (obj == null) {
        call.respondError(HttpStatusCode.BadRequest, "invalid json")
        return
    }
    val code = obj.optString("code").trim()
    val nodeId = obj.optString("nodeId").trim()
    val name = obj.optString("name").ifBlank { nodeId }
    val integration = obj.optString("integration").takeIf { it.isNotBlank() }
    if (code.isEmpty() || nodeId.isEmpty()) {
        call.respondError(HttpStatusCode.BadRequest, "code and nodeId required")
        return
    }
    val paired = registry.pair(code, nodeId, name, integration)
    if (paired == null) {
        call.respondError(HttpStatusCode.BadRequest, "invalid or expired code")
        return
    }
    call.respond(
        mapOf(
            "ok" to true,
            "nodeId" to paired.first.id,
            "token" to paired.second,
            "publicNodeUrl" to HubConfig.publicNodeUrl,
            "sessionPath" to HubConfig.sessionPath,
        ),
    )
}

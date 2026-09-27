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
        call.respond(mapOf("ok" to true, "face" to "node", "hubId" to identity.id))
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
        session.send(publicNode.frame())
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
        serveArtifact(call, call.parameters["sha"].orEmpty())
    }

    get("/{path...}") {
        // Offers carry the public bridge's artifacts path; cars on the LAN download it from here.
        val prefix = publicNode.artifactsPath + "/"
        val path = "/" + call.parameters.getAll("path").orEmpty().joinToString("/")
        if (prefix != OaaPaths.NODES_ARTIFACTS + "/" && path.startsWith(prefix)) {
            return@get serveArtifact(call, path.removePrefix(prefix))
        }
        call.respondError(HttpStatusCode.NotFound, "node face: pair, session and artifacts only")
    }
    post("/{path...}") {
        call.respondError(HttpStatusCode.NotFound, "node face: pair, session and artifacts only")
    }
}

private suspend fun HubContext.serveArtifact(call: ApplicationCall, sha: String) {
    if (call.bearerNode(this) == null) {
        call.respondError(HttpStatusCode.Unauthorized, "node token required")
        return
    }
    val file = artifacts.file(sha)
    if (file == null) {
        call.respondError(HttpStatusCode.NotFound, "unknown artifact")
        return
    }
    val mime = if (artifacts.isDelta(sha)) OaaOta.DELTA_MIME else OaaOta.APK_MIME
    call.respond(LocalFileContent(file, ContentType.parse(mime)))
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
        // hubId lets PublicNodeCheck tell that the public URL reached this hub.
        call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "code and nodeId required", "hubId" to identity.id))
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
            "publicNodeUrl" to publicNode.url,
            "sessionPath" to publicNode.sessionPath,
            "hubId" to identity.id,
            "hubName" to identity.name,
        ),
    )
}

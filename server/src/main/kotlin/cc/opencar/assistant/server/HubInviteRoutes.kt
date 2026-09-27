package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.protocol.OaaPorts
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.json.JSONObject

/** Nearby unpaired cars and hub-initiated pairing (admin only). */
internal fun Routing.inviteRoutes(hub: HubContext) = with(hub) {
    get(OaaPaths.NODES_DISCOVERED) {
        call.requireAdmin() ?: return@get
        val cars = discovered.all().filter { registry.get(it.id) == null }.map {
            mapOf(
                "id" to it.id,
                "name" to it.name,
                "integration" to it.integration,
                "version" to it.version,
                "host" to it.host,
                "port" to it.port,
                "seenAtMs" to it.seenAtMs,
            )
        }
        call.respond(mapOf("cars" to cars))
    }

    post(OaaPaths.NODES_INVITE) {
        call.requireAdmin() ?: return@post
        val obj = runCatching { JSONObject(call.receiveText()) }.getOrNull()
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid json")
        val nodeId = obj.optString("nodeId").trim()
        val known = nodeId.takeIf { it.isNotEmpty() }?.let { discovered.get(it) }
        val host = known?.host ?: obj.optString("host").trim().removePrefix("http://").substringBefore('/')
        if (host.isEmpty()) return@post call.respondError(HttpStatusCode.BadRequest, "nodeId or host required")
        val (h, p) = splitHostPort(host, known?.port ?: obj.optInt("port", OaaPorts.HUMAN_DEFAULT))
        when (val r = invites.invite(h, p)) {
            is CarInvites.Result.Failed -> call.respondError(HttpStatusCode.fromValue(r.status), r.error)
            is CarInvites.Result.Ok -> call.respond(
                mapOf(
                    "ok" to true,
                    "inviteId" to r.value.id,
                    "nodeId" to r.value.nodeId,
                    "name" to r.value.name,
                    "expiresAtMs" to r.value.expiresAtMs,
                ),
            )
        }
    }

    post("${OaaPaths.NODES_INVITE}/{id}/confirm") {
        call.requireAdmin() ?: return@post
        val obj = runCatching { JSONObject(call.receiveText()) }.getOrNull()
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid json")
        val code = obj.optString("code").filter { it.isDigit() }
        if (code.isEmpty()) return@post call.respondError(HttpStatusCode.BadRequest, "code required")
        when (val r = invites.confirm(call.parameters["id"].orEmpty(), code)) {
            is CarInvites.Result.Failed -> call.respondError(HttpStatusCode.fromValue(r.status), r.error)
            is CarInvites.Result.Ok -> call.respond(mapOf("ok" to true, "node" to nodeSummary(r.value)))
        }
    }

    delete("${OaaPaths.NODES_INVITE}/{id}") {
        call.requireAdmin() ?: return@delete
        invites.cancel(call.parameters["id"].orEmpty())
        call.respond(mapOf("ok" to true))
    }
}

/** `host`, `host:port`, `[v6]` or `[v6]:port`; [defaultPort] when none is given. */
internal fun splitHostPort(input: String, defaultPort: Int): Pair<String, Int> {
    if (input.startsWith("[")) {
        val end = input.indexOf(']')
        if (end > 0) {
            val port = input.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return input.substring(1, end) to port
        }
    }
    val colon = input.lastIndexOf(':')
    if (colon > 0 && input.indexOf(':') == colon) {
        input.substring(colon + 1).toIntOrNull()?.let { return input.substring(0, colon) to it }
    }
    return input to defaultPort
}

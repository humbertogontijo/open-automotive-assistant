package cc.opencar.assistant.feature.web

import cc.opencar.assistant.protocol.OaaCarAuth
import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.protocol.OaaRoles
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.json.JSONObject

private const val MAX_OPEN_REQUESTS = 5
private const val BROWSER_COOKIE_MAX_AGE = 400 * 24 * 3600
private val REQUEST_KINDS = setOf(OaaCarAuth.KIND_BROWSER, OaaCarAuth.KIND_HUB, OaaCarAuth.KIND_TOOL)

/** Pairing by a code shown on the head unit, and the trusted-client list (ADR-0004). */
internal fun Routing.registerAuthRoutes(deps: OaaWebDeps) {
    val auth = deps.auth
    val store = auth.store

    get(OaaPaths.AUTH_STATUS) {
        val c = call.caller
        call.respond(
            mapOf(
                "role" to OaaRoles.LOCAL,
                "authenticated" to (c != null),
                "kind" to c?.kind,
                "headUnit" to (c?.isHeadUnit == true),
                "loopback" to call.peerIsLoopback,
                "setupRequired" to false,
            ),
        )
    }

    post(OaaPaths.AUTH_LOCAL) {
        if (call.caller?.isHeadUnit == true) {
            call.respond(mapOf("ok" to true))
        } else {
            call.respond(HttpStatusCode.Unauthorized, mapOf("ok" to false, "error" to "head unit only"))
        }
    }

    post(OaaPaths.AUTH_PAIR_REQUEST) {
        if (call.caller?.kind == OaaCarAuth.KIND_INTERNAL) return@post call.fail(HttpStatusCode.Forbidden, "not via hub")
        val obj = call.jsonBody() ?: return@post call.fail(HttpStatusCode.BadRequest, "invalid json")
        val kind = obj.optString("kind", OaaCarAuth.KIND_BROWSER)
        if (kind !in REQUEST_KINDS) return@post call.fail(HttpStatusCode.BadRequest, "unknown kind")
        if (store.pending().size >= MAX_OPEN_REQUESTS) return@post call.fail(HttpStatusCode.TooManyRequests, "too many open requests")
        val req = store.request(
            kind = kind,
            name = obj.optString("name"),
            source = call.peerIp,
            hubId = obj.optString("hubId").takeIf { kind == OaaCarAuth.KIND_HUB && it.isNotBlank() },
        ) ?: return@post call.fail(HttpStatusCode.TooManyRequests, "a request from this address is already open")
        call.respond(
            mapOf(
                "ok" to true,
                "requestId" to req.id,
                "expiresAtMs" to req.expiresAtMs,
                "nodeId" to deps.hub?.nodeId,
                "name" to deps.hub?.displayName(),
                "integration" to deps.session.integrationId,
            ),
        )
    }

    post(OaaPaths.AUTH_PAIR_CONFIRM) {
        val obj = call.jsonBody() ?: return@post call.fail(HttpStatusCode.BadRequest, "invalid json")
        val requestId = obj.optString("requestId")
        val source = call.peerIp
        if (store.pending().any { it.id == requestId && it.source != source }) {
            return@post call.fail(HttpStatusCode.Forbidden, "confirm from the address that asked")
        }
        when (val r = store.confirm(requestId, obj.optString("code"))) {
            is CarAuthStore.Confirm.Failed -> call.fail(HttpStatusCode.fromValue(r.status), r.error)
            is CarAuthStore.Confirm.Ok -> when (r.client.kind) {
                OaaCarAuth.KIND_BROWSER -> {
                    call.setCarCookie(r.token, BROWSER_COOKIE_MAX_AGE)
                    call.respond(mapOf("ok" to true, "clientId" to r.client.id))
                }
                OaaCarAuth.KIND_HUB -> {
                    val link = obj.optJSONObject("hub")
                    val hub = deps.hub
                    val linked = if (link != null && hub != null) hub.linkFromHub(link, source, r.client.name) else null
                    if (linked == null || linked["ok"] != true) {
                        store.revoke(r.client.id)
                        return@post call.fail(HttpStatusCode.BadRequest, (linked?.get("error") as? String) ?: "hub link missing")
                    }
                    call.respond(
                        mapOf(
                            "ok" to true,
                            "token" to r.token,
                            "nodeId" to hub?.nodeId,
                            "name" to hub?.displayName(),
                            "integration" to deps.session.integrationId,
                        ),
                    )
                }
                else -> call.respond(mapOf("ok" to true, "clientId" to r.client.id, "token" to r.token))
            }
        }
    }

    get(OaaPaths.AUTH_PAIR_PENDING) {
        if (!call.requireHeadUnit()) return@get
        call.respond(mapOf("pending" to store.pending().map { it.wire() }))
    }

    delete("${OaaPaths.AUTH_PAIR_PENDING}/{id}") {
        if (!call.requireHeadUnit()) return@delete
        store.cancel(call.parameters["id"].orEmpty())
        call.respond(mapOf("ok" to true))
    }

    get(OaaPaths.AUTH_CLIENTS) {
        if (!call.requireHeadUnit()) return@get
        call.respond(
            mapOf(
                "clients" to store.clients().map {
                    mapOf(
                        "id" to it.id,
                        "kind" to it.kind,
                        "name" to it.name,
                        "createdAtMs" to it.createdAtMs,
                        "lastSeenMs" to it.lastSeenMs,
                        "hubId" to it.hubId,
                    )
                },
            ),
        )
    }

    delete("${OaaPaths.AUTH_CLIENTS}/{id}") {
        if (!call.requireHeadUnit()) return@delete
        val gone = store.revoke(call.parameters["id"].orEmpty())
            ?: return@delete call.fail(HttpStatusCode.NotFound, "unknown client")
        if (gone.kind == OaaCarAuth.KIND_HUB) deps.hub?.onHubRevoked(gone.hubId)
        call.respond(mapOf("ok" to true))
    }
}

/** Head unit view of a pairing request (the code never leaves the head unit). */
internal fun CarAuthStore.PairRequest.wire(): Map<String, Any?> = mapOf(
    "id" to id,
    "kind" to kind,
    "name" to name,
    "code" to code,
    "source" to source,
    "expiresAtMs" to expiresAtMs,
)

private suspend fun ApplicationCall.requireHeadUnit(): Boolean {
    if (caller?.isHeadUnit == true) return true
    fail(HttpStatusCode.Forbidden, "head unit only")
    return false
}

private suspend fun ApplicationCall.fail(status: HttpStatusCode, message: String) {
    respond(status, mapOf("ok" to false, "error" to message))
}

private suspend fun ApplicationCall.jsonBody(): JSONObject? = runCatching { JSONObject(receiveText()) }.getOrNull()

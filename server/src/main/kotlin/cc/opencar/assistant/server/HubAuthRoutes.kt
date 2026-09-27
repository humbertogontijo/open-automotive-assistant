package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaHeaders
import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.protocol.OaaRoles
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val OAUTH_STATE_TTL_MS = 10 * 60_000L

internal fun Routing.authRoutes(hub: HubContext) = with(hub) {
    val oauthStates = ConcurrentHashMap<String, Long>()

    get(OaaPaths.AUTH_STATUS) {
        val user = call.currentUser()
        call.respond(
            mapOf(
                "role" to OaaRoles.HUB,
                "setupRequired" to auth.needsSetup(),
                "authenticated" to (user != null),
                "user" to user?.let { userPublic(it) },
                "providers" to mapOf(
                    "local" to true,
                    "homeassistant" to HubConfig.haOAuthEnabled,
                    "ingress" to HubConfig.isAddon,
                ),
                "addon" to HubConfig.isAddon,
            ),
        )
    }

    post(OaaPaths.AUTH_SETUP) {
        if (!auth.needsSetup()) {
            call.respondError(HttpStatusCode.Conflict, "setup already complete")
            return@post
        }
        val obj = call.jsonBody() ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid json")
        val username = obj.optString("username").trim()
        try {
            val user = auth.createAdmin(username, obj.optString("password"), obj.optString("displayName").ifBlank { username })
            val session = auth.issueSession(user.id)
            call.setSessionCookie(session.token)
            call.respond(mapOf("ok" to true, "token" to session.token, "user" to userPublic(user)))
        } catch (e: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, e.message ?: "setup failed")
        }
    }

    post(OaaPaths.AUTH_LOGIN) {
        val obj = call.jsonBody() ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid json")
        val session = auth.loginLocal(obj.optString("username"), obj.optString("password"))
            ?: return@post call.respondError(HttpStatusCode.Unauthorized, "invalid credentials")
        call.setSessionCookie(session.token)
        call.respond(mapOf("ok" to true, "token" to session.token, "user" to auth.get(session.userId)?.let { userPublic(it) }))
    }

    post(OaaPaths.AUTH_LOGOUT) {
        auth.logout(call.sessionToken())
        call.clearSessionCookie()
        call.respond(mapOf("ok" to true))
    }

    get(OaaPaths.AUTH_ME) {
        val user = call.requireUser() ?: return@get
        call.respond(mapOf("user" to userPublic(user)))
    }

    get(OaaPaths.AUTH_SYSTEM_TOKEN) {
        call.requireAdmin() ?: return@get
        call.respond(mapOf("token" to auth.systemToken(), "role" to HubRole.SYSTEM.wire))
    }

    get(OaaPaths.AUTH_HA_AUTHORIZE) {
        val oauth = haOAuth ?: return@get call.respondError(HttpStatusCode.BadRequest, "HA OAuth not configured")
        val now = System.currentTimeMillis()
        oauthStates.values.removeIf { it < now }
        val state = UUID.randomUUID().toString()
        oauthStates[state] = now + OAUTH_STATE_TTL_MS
        call.respondRedirect(oauth.authorizeUrl(call.oauthRedirectUri(), state))
    }

    get(OaaPaths.AUTH_HA_CALLBACK) {
        val oauth = haOAuth ?: return@get call.respondError(HttpStatusCode.BadRequest, "HA OAuth not configured")
        val code = call.request.queryParameters["code"]
        val expiry = call.request.queryParameters["state"]?.let { oauthStates.remove(it) }
        if (code.isNullOrBlank() || expiry == null || expiry < System.currentTimeMillis()) {
            call.respondError(HttpStatusCode.BadRequest, "OAuth state invalid or expired")
            return@get
        }
        val identity = try {
            withContext(Dispatchers.IO) { oauth.exchange(code) }
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.BadGateway, "Home Assistant sign-in failed: ${e.message}")
            return@get
        }
        val session = auth.loginHa(identity.id, identity.name, identity.name, identity.isAdmin)
        call.setSessionCookie(session.token)
        call.respondRedirect(call.ingressPrefix() + "/")
    }
}

private fun ApplicationCall.ingressPrefix(): String = request.header(OaaHeaders.INGRESS_PATH)?.trimEnd('/') ?: ""

private suspend fun ApplicationCall.jsonBody(): JSONObject? = runCatching { JSONObject(receiveText()) }.getOrNull()

private fun ApplicationCall.oauthRedirectUri(): String {
    val proto = request.header(HttpHeaders.XForwardedProto) ?: "http"
    val host = request.header(HttpHeaders.XForwardedHost) ?: request.header(HttpHeaders.Host) ?: request.local.serverHost
    return "$proto://$host${ingressPrefix()}${OaaPaths.AUTH_HA_CALLBACK}"
}

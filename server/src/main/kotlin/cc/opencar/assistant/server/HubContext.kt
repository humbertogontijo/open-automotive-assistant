package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaCookies
import cc.opencar.assistant.protocol.OaaHeaders
import cc.opencar.assistant.protocol.OaaPorts
import cc.opencar.assistant.server.ota.ArtifactStore
import cc.opencar.assistant.server.ota.OtaRollouts
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import org.json.JSONObject
import java.io.File

/** Hub components plus the per-call helpers every route file shares. */
class HubContext(
    val dataDir: File,
    val humanPort: Int = OaaPorts.HUMAN_DEFAULT,
    val nodePort: Int = OaaPorts.NODE_DEFAULT,
    val ice: IceConfig = IceConfig.fromEnv(),
    val publicNode: PublicNode = PublicNode(),
) {
    val eventBus = EventBus()
    val identity = HubIdentity(dataDir)
    val registry = NodeRegistry(dataDir)
    val discovered = DiscoveredCars()
    val invites = CarInvites(identity, registry, nodePort, publicNode)
    val auth = AuthStore(dataDir)
    val signalRelay = WebRtcSignalRelay(registry, ice)
    val logs = LogRelay(registry)
    val artifacts = ArtifactStore(dataDir)
    val rollouts = OtaRollouts(dataDir, registry, artifacts) { publicNode.artifactsPath }
    val haOAuth: HaOAuth? = HubConfig.homeAssistantUrl?.let { url -> HubConfig.haClientId?.let { HaOAuth(url, it) } }

    val nodeListener = object : NodeListener {
        override suspend fun onSignal(nodeId: String, text: String) = signalRelay.onNodeFrame(nodeId, text)

        override suspend fun onHello(nodeId: String, payload: JSONObject) {
            val app = payload.optJSONObject("app")?.let {
                AppInfo(
                    packageName = it.optString("package").takeIf { s -> s.isNotEmpty() },
                    versionName = it.optString("versionName").takeIf { s -> s.isNotEmpty() },
                    versionCode = if (it.has("versionCode")) it.optLong("versionCode") else null,
                    apkSha256 = it.optString("apkSha256").takeIf { s -> s.isNotEmpty() },
                )
            }
            registry.updateHello(nodeId, payload.optString("name"), payload.optString("integration"), app)
            registry.setVia(nodeId, payload.optString("via").takeIf { it == "local" || it == "public" })
            rollouts.onHello(nodeId, app?.apkSha256)
            logs.onHello(nodeId)
        }

        override suspend fun onOtaStatus(nodeId: String, payload: JSONObject) = rollouts.onStatus(nodeId, payload)

        override suspend fun onLog(nodeId: String, line: String) = logs.onLog(nodeId, line)
    }

    fun nodeSummary(n: NodeRecord): Map<String, Any?> = mapOf(
        "id" to n.id,
        "name" to n.name,
        "online" to registry.isOnline(n.id),
        "integration" to n.integration,
        "lastSeenMs" to n.lastSeenMs,
        "app" to n.app?.let {
            mapOf(
                "package" to it.packageName,
                "versionName" to it.versionName,
                "versionCode" to it.versionCode,
                "apkSha256" to it.apkSha256,
            )
        },
        "ota" to rollouts.stateFor(n.id),
        "via" to registry.via(n.id),
    )

    fun fleet(): Map<String, Any?> = mapOf(
        "nodes" to registry.all().map { nodeSummary(it) },
        "publicNode" to mapOf(
            "url" to publicNode.url,
            "dialUrl" to publicNode.dialUrl,
        ),
    )

    // --- per-call helpers ---

    fun ApplicationCall.sessionToken(): String? {
        request.cookies[OaaCookies.SESSION]?.takeIf { it.isNotBlank() }?.let { return it }
        val authz = request.header(HttpHeaders.Authorization) ?: return null
        return authz.removePrefix("Bearer ").trim().takeIf { it.isNotEmpty() }
    }

    fun ApplicationCall.currentUser(): HubUser? {
        auth.resolveSession(sessionToken())?.let { return it }
        return ingressUser()
    }

    /** HA Ingress identity, trusted only from the Supervisor ingress proxy. */
    private fun ApplicationCall.ingressUser(): HubUser? {
        if (!HubConfig.isAddon || request.local.remoteAddress !in HubConfig.ingressProxies) return null
        val haId = request.header(OaaHeaders.INGRESS_USER_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val name = request.header(OaaHeaders.INGRESS_USER_NAME)?.trim()?.ifBlank { haId } ?: haId
        val display = request.header(OaaHeaders.INGRESS_DISPLAY_NAME)?.trim()?.ifBlank { name } ?: name
        return auth.get(auth.loginHa(haId, name, display).userId)
    }

    suspend fun ApplicationCall.requireUser(): HubUser? {
        currentUser()?.let { return it }
        respondError(HttpStatusCode.Unauthorized, "authentication required")
        return null
    }

    suspend fun ApplicationCall.requireAdmin(): HubUser? {
        val user = requireUser() ?: return null
        if (user.isAdmin) return user
        respondError(HttpStatusCode.Forbidden, "admin required")
        return null
    }

    suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
        respond(status, mapOf("ok" to false, "error" to message))
    }

    /**
     * Selected car: header, then `?node=`, then the `oaa_node` cookie. A present-but-blank
     * header addresses the hub itself, overriding the cookie.
     */
    fun ApplicationCall.nodeIdOrNull(): String? {
        request.header(OaaHeaders.NODE)?.let { return it.trim().takeIf { id -> id.isNotEmpty() } }
        return request.queryParameters["node"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.cookies[OaaCookies.NODE]?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun ApplicationCall.setSessionCookie(token: String) {
        response.cookies.append(
            Cookie(
                name = OaaCookies.SESSION,
                value = token,
                maxAge = 60 * 60 * 24 * 30,
                path = "/",
                httpOnly = true,
                extensions = mapOf("SameSite" to "Lax"),
                encoding = CookieEncoding.RAW,
            ),
        )
    }

    fun ApplicationCall.clearSessionCookie() {
        response.cookies.append(Cookie(name = OaaCookies.SESSION, value = "", maxAge = 0, path = "/", httpOnly = true))
    }

    fun userPublic(u: HubUser): Map<String, Any?> = mapOf(
        "id" to u.id,
        "username" to u.username,
        "displayName" to u.displayName,
        "role" to u.role.wire,
    )
}

/** Answer `{"type":"ping"}` from a viewer socket; true when [text] was a ping. */
suspend fun WebSocketSession.answerPing(text: String): Boolean {
    if (text.length > 256) return false
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return false
    if (json.optString("type") != "ping" && json.optString("t") != "ping") return false
    send(Frame.Text("""{"type":"pong","t":"pong"}"""))
    return true
}

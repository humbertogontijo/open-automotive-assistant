package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaCarAuth
import cc.opencar.assistant.protocol.OaaPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Hub-initiated pairing (ADR-0004): ask a car for a pairing code, then send the code the
 * admin read off the car's screen together with a pre-registered node token.
 */
class CarInvites(
    private val identity: HubIdentity,
    private val registry: NodeRegistry,
    private val nodePort: Int,
) {
    data class Invite(
        val id: String,
        val carUrl: String,
        val requestId: String,
        val nodeId: String,
        val name: String,
        val integration: String?,
        val expiresAtMs: Long,
    )

    /** Outcome of a call to the car: `ok` or an HTTP status plus message for the admin. */
    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Failed(val status: Int, val error: String) : Result<Nothing>()
    }

    private val invites = ConcurrentHashMap<String, Invite>()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun get(id: String): Invite? = invites[id]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }

    fun cancel(id: String) {
        invites.remove(id)
    }

    suspend fun invite(host: String, port: Int): Result<Invite> {
        invites.values.removeIf { it.expiresAtMs < System.currentTimeMillis() }
        val carUrl = "http://${if (':' in host && !host.startsWith("[")) "[$host]" else host}:$port"
        val body = JSONObject()
            .put("kind", OaaCarAuth.KIND_HUB)
            .put("name", identity.name)
            .put("hubId", identity.id)
        val res = when (val r = post(carUrl + OaaPaths.AUTH_PAIR_REQUEST, body)) {
            is Result.Failed -> return r
            is Result.Ok -> r.value
        }
        val nodeId = res.optString("nodeId").takeIf { it.isNotBlank() }
            ?: return Result.Failed(502, "car did not report a node id")
        val invite = Invite(
            id = randomToken().take(16),
            carUrl = carUrl,
            requestId = res.optString("requestId"),
            nodeId = nodeId,
            name = res.optString("name").ifBlank { nodeId },
            integration = res.optString("integration").takeIf { it.isNotBlank() },
            expiresAtMs = res.optLong("expiresAtMs", System.currentTimeMillis() + OaaCarAuth.CODE_TTL_MS),
        )
        invites[invite.id] = invite
        return Result.Ok(invite)
    }

    suspend fun confirm(inviteId: String, code: String): Result<NodeRecord> {
        val invite = get(inviteId) ?: return Result.Failed(404, "invite expired; ask the car again")
        val staged = registry.stage(invite.nodeId, invite.name, invite.integration)
        val hub = JSONObject()
            .put("hubId", identity.id)
            .put("hubName", identity.name)
            .put("nodeToken", staged.token)
            .put("nodePort", nodePort)
            .put("nodeUrls", JSONArray(listOfNotNull(HubConfig.publicNodeUrl)))
            .put("sessionPath", HubConfig.sessionPath)
        val body = JSONObject()
            .put("requestId", invite.requestId)
            .put("code", code.trim())
            .put("hub", hub)
        return when (val r = post(invite.carUrl + OaaPaths.AUTH_PAIR_CONFIRM, body)) {
            is Result.Failed -> {
                registry.unstage(staged)
                // The car drops the request after a 404 or too many wrong codes.
                if (r.status == 404) invites.remove(inviteId)
                r
            }
            is Result.Ok -> {
                invites.remove(inviteId)
                registry.setCarLink(invite.nodeId, r.value.optString("token").takeIf { it.isNotBlank() }, invite.carUrl)
                Result.Ok(registry.get(invite.nodeId)!!)
            }
        }
    }

    private suspend fun post(url: String, body: JSONObject): Result<JSONObject> = withContext(Dispatchers.IO) {
        val req = runCatching {
            HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
        }.getOrElse { return@withContext Result.Failed(400, "invalid car address") }
        val resp = runCatching { http.send(req, HttpResponse.BodyHandlers.ofString()) }
            .getOrElse { return@withContext Result.Failed(502, "cannot reach car: ${it.message ?: it.javaClass.simpleName}") }
        val json = runCatching { JSONObject(resp.body()) }.getOrNull()
        if (resp.statusCode() in 200..299 && json != null && json.optBoolean("ok", false)) {
            Result.Ok(json)
        } else {
            val err = json?.optString("error")?.takeIf { it.isNotBlank() } ?: "car answered ${resp.statusCode()}"
            Result.Failed(if (resp.statusCode() in 400..599) resp.statusCode() else 502, err)
        }
    }
}

package cc.opencar.assistant.feature.web

import cc.opencar.assistant.protocol.OaaCarAuth
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Car-issued access (ADR-0004). Trusted clients (browsers, hubs, tools) hold a bearer
 * token stored here only as a SHA-256 hash. New clients get one by typing the code the
 * head unit shows for their pairing request. Head unit sessions are per launch and
 * never persisted.
 */
class CarAuthStore(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    data class Client(
        val id: String,
        val kind: String,
        var name: String,
        val tokenHash: String,
        val createdAtMs: Long,
        var lastSeenMs: Long? = null,
        val hubId: String? = null,
    )

    data class PairRequest(
        val id: String,
        val kind: String,
        val name: String,
        val source: String,
        val code: String,
        val expiresAtMs: Long,
        val hubId: String? = null,
        var attempts: Int = 0,
    )

    sealed class Confirm {
        data class Ok(val client: Client, val token: String, val request: PairRequest) : Confirm()
        data class Failed(val status: Int, val error: String) : Confirm()
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val clients = ConcurrentHashMap<String, Client>()
    private val byHash = ConcurrentHashMap<String, String>()
    private val requests = ConcurrentHashMap<String, PairRequest>()
    private val huSessions = ConcurrentHashMap.newKeySet<String>()
    private var lastTouchPersistMs = 0L

    /** Called whenever the set of open pairing requests changes (new, confirmed, expired, cancelled). */
    @Volatile var onPendingChanged: (List<PairRequest>) -> Unit = {}

    init {
        load()
    }

    /** Open a pairing request; null when [source] already has one open. */
    fun request(kind: String, name: String, source: String, hubId: String? = null): PairRequest? {
        val req = synchronized(this) {
            sweepLocked()
            if (requests.values.any { it.source == source }) return null
            PairRequest(
                id = randomHex(12),
                kind = kind,
                name = name.trim().take(64).ifEmpty { kind },
                source = source,
                code = (100_000 + random.nextInt(900_000)).toString(),
                expiresAtMs = now() + OaaCarAuth.CODE_TTL_MS,
                hubId = hubId?.take(64),
            ).also { requests[it.id] = it }
        }
        notifyPending()
        return req
    }

    fun pending(): List<PairRequest> = synchronized(this) {
        sweepLocked()
        requests.values.sortedBy { it.expiresAtMs }
    }

    fun confirm(requestId: String, code: String): Confirm {
        val result = synchronized(this) {
            val req = requests[requestId]
            if (req == null || req.expiresAtMs < now()) {
                requests.remove(requestId)
                return@synchronized Confirm.Failed(404, "unknown or expired request")
            }
            if (!MessageDigest.isEqual(req.code.toByteArray(), code.trim().toByteArray())) {
                req.attempts++
                if (req.attempts >= OaaCarAuth.MAX_ATTEMPTS) requests.remove(requestId)
                return@synchronized Confirm.Failed(403, "wrong code")
            }
            requests.remove(requestId)
            val (client, token) = addClientLocked(req.kind, req.name, req.hubId)
            Confirm.Ok(client, token, req)
        }
        notifyPending()
        return result
    }

    fun cancel(requestId: String) {
        if (requests.remove(requestId) != null) notifyPending()
    }

    /** Drop expired requests; notifies when any were dropped. */
    fun sweep() {
        val dropped = synchronized(this) { sweepLocked() }
        if (dropped) notifyPending()
    }

    /** Trust a client without a code (adb tools, a hub paired from the car). */
    fun addClient(kind: String, name: String, hubId: String? = null): Pair<Client, String> =
        synchronized(this) { addClientLocked(kind, name, hubId) }

    fun resolve(token: String?): Client? {
        if (token.isNullOrBlank()) return null
        val client = byHash[hash(token)]?.let { clients[it] } ?: return null
        val t = now()
        client.lastSeenMs = t
        if (t - lastTouchPersistMs > 60_000L) {
            lastTouchPersistMs = t
            persist()
        }
        return client
    }

    fun clients(): List<Client> = clients.values.sortedBy { it.createdAtMs }

    fun revoke(id: String): Client? = synchronized(this) {
        val c = clients.remove(id) ?: return null
        byHash.remove(c.tokenHash)
        persist()
        c
    }

    /** Remove the hub client for [hubId], or every hub when null (the hub link was dropped). */
    fun revokeHub(hubId: String?) = synchronized(this) {
        val gone = clients.values.filter { it.kind == OaaCarAuth.KIND_HUB && (hubId == null || it.hubId == hubId) }
        gone.forEach { clients.remove(it.id); byHash.remove(it.tokenHash) }
        if (gone.isNotEmpty()) persist()
    }

    fun newHuSession(): String = randomHex(32).also { huSessions.add(it) }

    fun isHuSession(token: String?): Boolean = token != null && token in huSessions

    private fun addClientLocked(kind: String, name: String, hubId: String?): Pair<Client, String> {
        if (hubId != null) {
            clients.values.filter { it.kind == kind && it.hubId == hubId }.forEach {
                clients.remove(it.id)
                byHash.remove(it.tokenHash)
            }
        }
        val token = randomHex(32)
        val client = Client(
            id = randomHex(8),
            kind = kind,
            name = name.trim().take(64).ifEmpty { kind },
            tokenHash = hash(token),
            createdAtMs = now(),
            hubId = hubId,
        )
        clients[client.id] = client
        byHash[client.tokenHash] = client.id
        persist()
        return client to token
    }

    private fun sweepLocked(): Boolean {
        val t = now()
        return requests.values.removeIf { it.expiresAtMs < t }
    }

    private fun notifyPending() {
        runCatching { onPendingChanged(pending()) }
    }

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun load() {
        if (!file.exists()) return
        val type = object : TypeToken<List<Client>>() {}.type
        val list: List<Client> = runCatching { gson.fromJson<List<Client>>(file.readText(), type) }.getOrNull() ?: return
        for (c in list) {
            clients[c.id] = c
            byHash[c.tokenHash] = c.id
        }
    }

    @Synchronized
    private fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(gson.toJson(clients.values.toList()))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}

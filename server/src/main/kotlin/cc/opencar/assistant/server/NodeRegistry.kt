package cc.opencar.assistant.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** App build a node reported in its `hello` frame. */
data class AppInfo(
    val packageName: String? = null,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val apkSha256: String? = null,
)

data class NodeRecord(
    val id: String,
    var name: String,
    var integration: String? = null,
    var token: String,
    var lastSeenMs: Long? = null,
    var app: AppInfo? = null,
    /** Car-issued token (ADR-0004) from a hub-initiated pairing; the car's LAN API accepts it. */
    var carToken: String? = null,
    /** Car LAN origin the hub paired through, e.g. `http://192.168.1.236:8787`. */
    var lanUrl: String? = null,
)

/** A node token issued ahead of the car's confirm; [previous] restores a re-pair that fails. */
data class StagedNode(val nodeId: String, val token: String, val previous: NodeRecord?)

data class PairingCode(
    val code: String,
    val expiresAtMs: Long,
)

class NodeRegistry(dataDir: File) {
    private val gson = Gson()
    private val storeFile = File(dataDir, "nodes.json")
    private val nodes = ConcurrentHashMap<String, NodeRecord>()
    /** In-process demo nodes: served like any node, never written to [storeFile]. */
    private val demoIds = ConcurrentHashMap.newKeySet<String>()
    private val writer = JsonFileWriter(storeFile) {
        gson.toJson(nodes.values.filter { it.id !in demoIds })
    }
    private val tokenIndex = ConcurrentHashMap<String, String>()
    private val pairing = ConcurrentHashMap<String, PairingCode>()
    private val sessions = ConcurrentHashMap<String, NodeTransport>()
    private val random = SecureRandom()

    init {
        load()
    }

    fun all(): List<NodeRecord> = nodes.values.sortedBy { it.name.lowercase() }

    fun isOnline(nodeId: String): Boolean = sessions.containsKey(nodeId)

    fun createPairingCode(ttlMs: Long = 10 * 60_000L): PairingCode {
        val now = System.currentTimeMillis()
        pairing.values.removeIf { it.expiresAtMs < now }
        val code = (100000 + random.nextInt(900000)).toString()
        return PairingCode(code, now + ttlMs).also { pairing[code] = it }
    }

    @Synchronized
    fun pair(code: String, nodeId: String, name: String, integration: String?): Pair<NodeRecord, String>? {
        val offer = pairing.remove(code) ?: return null
        if (offer.expiresAtMs < System.currentTimeMillis()) return null
        val token = randomToken()
        val record = nodes[nodeId]?.also {
            tokenIndex.remove(it.token)
            it.name = name
            it.integration = integration
            it.token = token
        } ?: NodeRecord(id = nodeId, name = name, integration = integration, token = token).also {
            nodes[nodeId] = it
        }
        tokenIndex[token] = nodeId
        // A re-pair revokes the old token: drop any session that used it.
        sessions.remove(nodeId)?.close()
        persist()
        return record to token
    }

    /**
     * Hub-initiated pairing: register [nodeId] with a fresh token before the car confirms, so
     * the car's first dial-in already works. Undo with [unstage] if the confirm fails.
     */
    @Synchronized
    fun stage(nodeId: String, name: String, integration: String?): StagedNode {
        val previous = nodes[nodeId]?.copy()
        val token = randomToken()
        previous?.let { tokenIndex.remove(it.token) }
        nodes[nodeId] = (previous?.copy() ?: NodeRecord(id = nodeId, name = name, token = token)).also {
            it.name = name
            it.integration = integration ?: it.integration
            it.token = token
        }
        tokenIndex[token] = nodeId
        sessions.remove(nodeId)?.close()
        persist()
        return StagedNode(nodeId, token, previous)
    }

    @Synchronized
    fun unstage(staged: StagedNode) {
        val current = nodes[staged.nodeId] ?: return
        if (current.token != staged.token) return
        tokenIndex.remove(staged.token)
        val prev = staged.previous
        if (prev == null) {
            nodes.remove(staged.nodeId)
        } else {
            nodes[prev.id] = prev
            tokenIndex[prev.token] = prev.id
        }
        persist()
    }

    @Synchronized
    fun setCarLink(nodeId: String, carToken: String?, lanUrl: String?) {
        val n = nodes[nodeId] ?: return
        n.carToken = carToken
        n.lanUrl = lanUrl
        persist()
    }

    /** Register an in-process demo node (CI / OAA_DEMO_NODE). */
    @Synchronized
    fun registerDemo(transport: NodeTransport, name: String = "Demo node") {
        val id = transport.nodeId
        val token = "demo-" + randomToken()
        nodes[id]?.let { tokenIndex.remove(it.token) }
        nodes[id] = NodeRecord(id = id, name = name, integration = "demo", token = token)
        demoIds += id
        tokenIndex[token] = id
        sessions.put(id, transport)?.close()
    }

    fun resolveToken(token: String): NodeRecord? = tokenIndex[token]?.let { nodes[it] }

    fun get(nodeId: String): NodeRecord? = nodes[nodeId]

    @Synchronized
    fun remove(nodeId: String): Boolean {
        val n = nodes.remove(nodeId) ?: return false
        tokenIndex.remove(n.token)
        sessions.remove(nodeId)?.close()
        persist()
        return true
    }

    fun attachSession(nodeId: String, session: NodeTransport) {
        sessions.put(nodeId, session)?.takeIf { it !== session }?.close()
        nodes[nodeId]?.lastSeenMs = System.currentTimeMillis()
    }

    /** True when [session] was the node's live session (and is now gone). */
    fun detachSession(nodeId: String, session: NodeTransport): Boolean {
        val removed = sessions.remove(nodeId, session)
        if (removed) {
            session.close()
            nodes[nodeId]?.lastSeenMs = System.currentTimeMillis()
        }
        return removed
    }

    @Synchronized
    fun updateHello(nodeId: String, name: String?, integration: String?, app: AppInfo?) {
        val n = nodes[nodeId] ?: return
        val before = n.copy()
        name?.takeIf { it.isNotBlank() }?.let { n.name = it }
        integration?.takeIf { it.isNotBlank() }?.let { n.integration = it }
        if (app != null) n.app = app
        if (n != before) persist()
    }

    fun session(nodeId: String): NodeTransport? = sessions[nodeId]

    @Synchronized
    private fun load() {
        if (!storeFile.exists()) return
        val type = object : TypeToken<List<NodeRecord>>() {}.type
        val list: List<NodeRecord> = runCatching {
            gson.fromJson<List<NodeRecord>>(storeFile.readText(), type)
        }.getOrNull() ?: return
        nodes.clear()
        tokenIndex.clear()
        // Older hubs persisted the demo node; it is re-registered on start when enabled.
        for (n in list.filterNot { it.integration == "demo" && it.token.startsWith("demo-") }) {
            nodes[n.id] = n
            tokenIndex[n.token] = n.id
        }
    }

    private fun persist() = writer.schedule()

    fun flush() = writer.flush()
}

package cc.opencar.assistant.server

import com.google.gson.Gson
import com.google.gson.GsonBuilder
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
)

data class PairingCode(
    val code: String,
    val expiresAtMs: Long,
)

class NodeRegistry(private val dataDir: File) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val storeFile = File(dataDir, "nodes.json")
    private val nodes = ConcurrentHashMap<String, NodeRecord>()
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

    /** Register an in-process demo node (CI / OAA_DEMO_NODE). */
    @Synchronized
    fun registerDemo(transport: NodeTransport, name: String = "Demo node") {
        val id = transport.nodeId
        val token = "demo-" + randomToken()
        nodes[id]?.let { tokenIndex.remove(it.token) }
        nodes[id] = NodeRecord(id = id, name = name, integration = "demo", token = token)
        tokenIndex[token] = id
        sessions.put(id, transport)?.close()
        persist()
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
        name?.takeIf { it.isNotBlank() }?.let { n.name = it }
        integration?.takeIf { it.isNotBlank() }?.let { n.integration = it }
        if (app != null) n.app = app
        persist()
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
        for (n in list) {
            nodes[n.id] = n
            tokenIndex[n.token] = n.id
        }
    }

    @Synchronized
    private fun persist() {
        dataDir.mkdirs()
        storeFile.writeText(gson.toJson(nodes.values.toList()))
    }
}

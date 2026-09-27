package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaPaths
import com.google.gson.Gson
import java.io.File

/**
 * The node-face endpoint cars dial when away from the hub's LAN. `OAA_PUBLIC_NODE_URL` wins
 * (compose, the HA app's `public_node_url` option); otherwise the URL the HA component reports
 * (its Nabu Casa or external URL), persisted in `data/public-node.json`.
 */
class PublicNode(dataDir: File) {
    data class Endpoint(val url: String, val sessionPath: String)

    private val file = File(dataDir, "public-node.json")
    private val gson = Gson()
    @Volatile private var reported: Endpoint? = runCatching { gson.fromJson(file.readText(), Endpoint::class.java) }
        .getOrNull()
        ?.takeIf { !it.url.isNullOrBlank() && !it.sessionPath.isNullOrBlank() }
    private val writer = JsonFileWriter(file) { gson.toJson(reported) }

    fun current(): Endpoint? =
        HubConfig.publicNodeUrl?.let { Endpoint(it.trimEnd('/'), HubConfig.sessionPath) } ?: reported

    val url: String? get() = current()?.url

    val sessionPath: String get() = current()?.sessionPath ?: OaaPaths.NODES_SESSION

    /** What to type on the car for manual pairing: the URL, plus the bridge prefix (`/api/oaa_node`) if any. */
    val dialUrl: String?
        get() = current()?.let { e ->
            if (e.sessionPath == OaaPaths.NODES_SESSION) e.url else e.url + e.sessionPath.removeSuffix("/session")
        }

    /** OTA path cars get in offers: `OAA_ARTIFACTS_PATH`, else the session path's `artifacts` sibling. */
    val artifactsPath: String
        get() = HubConfig.artifactsPathOverride
            ?: sessionPath.takeIf { it.endsWith("/session") }?.let { it.removeSuffix("session") + "artifacts" }
            ?: OaaPaths.NODES_ARTIFACTS

    /** Where the current endpoint comes from: `env`, `reported`, or null when there is none. */
    val source: String?
        get() = when {
            HubConfig.publicNodeUrl != null -> "env"
            reported != null -> "reported"
            else -> null
        }

    /** Record what the HA component resolved ([url] null clears it); true when [current] changed. */
    fun report(url: String?, sessionPath: String?): Boolean {
        val before = current()
        val next = url?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?.let { Endpoint(it, sessionPath?.trim()?.takeIf { p -> p.startsWith("/") } ?: OaaPaths.NODES_SESSION) }
        if (next == reported) return false
        reported = next
        if (next == null) file.delete() else writer.schedule()
        return current() != before
    }

    fun frame(): String {
        val payload = OaaFrames.versioned().put("sessionPath", sessionPath)
        url?.let { payload.put("publicNodeUrl", it) }
        return OaaFrames.frame(OaaFrames.PUBLIC_NODE, payload)
    }

    companion object {
        fun isHttpUrl(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")
    }
}

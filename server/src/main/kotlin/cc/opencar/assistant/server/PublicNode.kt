package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaPaths
import java.net.URI

/**
 * Where cars dial the node face when away from the hub's LAN: `OAA_PUBLIC_NODE_URL`, the same URL
 * typed on a car for manual pairing. A plain origin (`https://oaa.example.com`) uses the node
 * face's own paths; a path prefix is a bridge (`https://x.ui.nabu.casa/api/oaa_node` → session at
 * `<prefix>/session`, updates at `<prefix>/artifacts`).
 */
class PublicNode(configured: String? = HubConfig.publicNodeUrl) {
    /** The configured URL as cars type it, or null when unset. */
    val dialUrl: String? = configured?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private val prefix: String = dialUrl?.let { runCatching { URI(it).rawPath }.getOrNull() }.orEmpty().trimEnd('/')

    /** Origin cars open the session on (the `public_node` frame's `publicNodeUrl`). */
    val url: String? = dialUrl?.removeSuffix(prefix)

    val sessionPath: String = if (prefix.isEmpty()) OaaPaths.NODES_SESSION else "$prefix/session"

    val pairPath: String = if (prefix.isEmpty()) OaaPaths.NODES_PAIR else "$prefix/pair"

    /** OTA path cars get in offers; the node face serves it too, so LAN cars download the same offer. */
    val artifactsPath: String = if (prefix.isEmpty()) OaaPaths.NODES_ARTIFACTS else "$prefix/artifacts"

    fun frame(): String {
        val payload = OaaFrames.versioned().put("sessionPath", sessionPath)
        url?.let { payload.put("publicNodeUrl", it) }
        return OaaFrames.frame(OaaFrames.PUBLIC_NODE, payload)
    }
}

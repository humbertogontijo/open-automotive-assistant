package cc.opencar.assistant.feature.web

import cc.opencar.assistant.protocol.OaaPaths
import org.json.JSONObject

/** A node-face endpoint: base URL plus the session path on it (`/api/oaa_node/session` behind the HA bridge). */
internal data class NodeUrl(val url: String, val sessionPath: String = OaaPaths.NODES_SESSION) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("sessionPath", sessionPath)

    /** Through a bridge prefix or over TLS: an address that works away from the hub's LAN. */
    val looksPublic: Boolean get() = sessionPath != OaaPaths.NODES_SESSION || url.startsWith("https://")

    companion object {
        fun parse(o: JSONObject?): NodeUrl? {
            val url = o?.optString("url")?.trim()?.trimEnd('/').orEmpty()
            if (!url.startsWith("http")) return null
            return NodeUrl(url, o!!.optString("sessionPath").ifBlank { OaaPaths.NODES_SESSION })
        }

        fun parse(raw: String?): NodeUrl? = raw?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }

        /** The hub's `public_node` frame payload (`{v, publicNodeUrl?, sessionPath}`). */
        fun fromPublicNode(payload: JSONObject): NodeUrl? {
            val url = payload.optString("publicNodeUrl").trim().trimEnd('/')
            if (!url.startsWith("http")) return null
            return NodeUrl(url, payload.optString("sessionPath").ifBlank { OaaPaths.NODES_SESSION })
        }
    }
}

/**
 * The two ways a car reaches its hub, like the Home Assistant app's internal and external URLs:
 * [local] on the hub's LAN (learned at pairing) and [public] from anywhere (Nabu Casa or a
 * tunnel, published by the hub). Local wins whenever the hub answers on it.
 */
internal data class HubEndpoints(val local: NodeUrl?, val public: NodeUrl?) {
    enum class Via(val wire: String) { LOCAL("local"), PUBLIC("public") }

    val isEmpty: Boolean get() = local == null && public == null

    operator fun get(via: Via): NodeUrl? = if (via == Via.LOCAL) local else public

    /**
     * Endpoint to dial next. [localReachable]: the hub just answered a health probe on [local].
     * [lastFailed]: the endpoint whose socket last failed to open; when the hub answers locally
     * it is tried last this round. A local URL whose probe failed is not dialed while there is
     * a public one: it would only burn a connect timeout.
     */
    fun choose(localReachable: Boolean, lastFailed: Via? = null): Via? {
        if (local == null) return Via.PUBLIC.takeIf { public != null }
        if (!localReachable) return if (public != null) Via.PUBLIC else Via.LOCAL
        if (public == null || lastFailed != Via.LOCAL) return Via.LOCAL
        return Via.PUBLIC
    }
}

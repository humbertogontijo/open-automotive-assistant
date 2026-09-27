package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaPaths
import java.security.SecureRandom

object HubConfig {
    private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

    val publicNodeUrl: String? get() = env("OAA_PUBLIC_NODE_URL")

    val publicHumanUrl: String? get() = env("OAA_PUBLIC_HUMAN_URL")

    /** Path on publicNodeUrl for the node WebSocket (HA Cloud uses /api/oaa_node/session). */
    val sessionPath: String get() = env("OAA_SESSION_PATH") ?: OaaPaths.NODES_SESSION

    /**
     * Path on publicNodeUrl for OTA downloads. Defaults to the session path's sibling,
     * so `OAA_SESSION_PATH=/api/oaa_node/session` (HA Cloud) yields `/api/oaa_node/artifacts`.
     */
    val artifactsPath: String
        get() = env("OAA_ARTIFACTS_PATH")?.trimEnd('/')
            ?: sessionPath.takeIf { it.endsWith("/session") }?.let { it.removeSuffix("session") + "artifacts" }
            ?: OaaPaths.NODES_ARTIFACTS

    val homeAssistantUrl: String? get() = env("OAA_HA_URL")

    val haClientId: String? get() = env("OAA_HA_CLIENT_ID")

    val haOAuthEnabled: Boolean get() = homeAssistantUrl != null && haClientId != null

    val isAddon: Boolean
        get() = System.getenv("OAA_HOMEASSISTANT_ADDON")?.equals("true", ignoreCase = true) == true ||
            System.getenv("SUPERVISOR_TOKEN") != null

    /** Peers allowed to assert Ingress identity headers (Supervisor ingress proxy). */
    val ingressProxies: Set<String>
        get() = (env("OAA_INGRESS_PROXIES") ?: "172.30.32.2").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    val demoNode: Boolean
        get() = System.getenv("OAA_DEMO_NODE") == "1" || System.getenv("OAA_DEMO_NODE") == "true"

    val mdnsEnabled: Boolean get() = System.getenv("OAA_MDNS") != "0"
}

private val random = SecureRandom()

/** 256-bit opaque token (sessions, node bearer tokens). */
fun randomToken(): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

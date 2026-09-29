package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaOta
import cc.opencar.assistant.server.ota.CarRelease
import java.security.SecureRandom

object HubConfig {
    private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

    /** URL away cars dial the node face on; see [PublicNode]. */
    val publicNodeUrl: String? get() = env("OAA_PUBLIC_NODE_URL")

    val publicHumanUrl: String? get() = env("OAA_PUBLIC_HUMAN_URL")

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

    /** Car update policy, one of [OaaOta.MODES]; see [CarRelease]. */
    val carUpdates: String
        get() = env("OAA_CAR_UPDATES")?.lowercase()?.takeIf { it in OaaOta.MODES } ?: OaaOta.MODE_ASK

    /** Release folder holding [OaaOta.RELEASE_MANIFEST] and the car APK; `{version}` is this hub's version. */
    val carReleaseUrl: String get() = env("OAA_CAR_RELEASE_URL") ?: CarRelease.DEFAULT_URL
}

private val random = SecureRandom()

/** 256-bit opaque token (sessions, node bearer tokens). */
fun randomToken(): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

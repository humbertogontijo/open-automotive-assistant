package cc.opencar.assistant.server

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ICE servers for the media plane. TURN credentials follow the coturn REST
 * pattern (`use-auth-secret`): username = `<expiry>:<user>`, credential =
 * base64(HMAC-SHA1(secret, username)). The shared secret never leaves the hub.
 */
class IceConfig(
    private val stunUrls: List<String>,
    private val turnUrls: List<String>,
    private val turnSecret: String?,
    val ttlSec: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val turnEnabled: Boolean get() = turnUrls.isNotEmpty() && !turnSecret.isNullOrEmpty()

    fun iceServers(userId: String): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        if (stunUrls.isNotEmpty()) out += mapOf("urls" to stunUrls)
        if (turnEnabled) {
            val expiry = clock() / 1000 + ttlSec
            val username = "$expiry:${userId.replace(":", "_")}"
            out += mapOf(
                "urls" to turnUrls,
                "username" to username,
                "credential" to hmacSha1(turnSecret!!, username),
            )
        }
        return out
    }

    companion object {
        private const val DEFAULT_STUN = "stun:stun.cloudflare.com:3478"

        fun fromEnv(): IceConfig = IceConfig(
            stunUrls = csv(System.getenv("OAA_STUN_URLS"), default = listOf(DEFAULT_STUN)),
            turnUrls = csv(System.getenv("OAA_TURN_URLS"), default = emptyList()),
            turnSecret = System.getenv("OAA_TURN_SECRET")?.trim()?.takeIf { it.isNotEmpty() },
            ttlSec = System.getenv("OAA_TURN_TTL")?.toLongOrNull()?.coerceIn(60, 86_400) ?: 3_600,
        )

        fun hmacSha1(secret: String, message: String): String {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            return Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
        }

        private fun csv(raw: String?, default: List<String>): List<String> {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty()) return default
            if (trimmed == "none") return emptyList()
            return trimmed.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}

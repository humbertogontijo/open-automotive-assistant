package cc.opencar.assistant.feature.web

import android.content.Context
import cc.opencar.assistant.protocol.OaaCarAuth
import cc.opencar.assistant.protocol.OaaCookies
import cc.opencar.assistant.protocol.OaaHeaders
import cc.opencar.assistant.protocol.OaaPaths
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingApplicationRequest
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.lang.reflect.Field
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Access control for the car's HTTP server (ADR-0004). Every `/api/…` and `/debug…` call
 * needs a caller: the head unit UI (per-launch [localKey] swapped for a session cookie),
 * a paired browser, hub or tool (car-issued token), or a hub `rpc` replayed in-process
 * ([internalSecret]).
 */
class CarAuth(context: Context) {
    val store = CarAuthStore(File(context.filesDir, "oaa/auth-clients.json"))
    val localKey: String = randomHex()
    internal val internalSecret: String = randomHex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _pending = MutableStateFlow<List<CarAuthStore.PairRequest>>(emptyList())

    /** Open pairing requests, newest state; drives the head unit dialog and notification. */
    val pending: StateFlow<List<CarAuthStore.PairRequest>> = _pending.asStateFlow()

    init {
        store.onPendingChanged = { list ->
            _pending.value = list
            list.minOfOrNull { it.expiresAtMs }?.let { at ->
                scope.launch {
                    delay((at - System.currentTimeMillis()).coerceAtLeast(0) + 500)
                    store.sweep()
                }
            }
        }
    }

    internal fun resolve(call: ApplicationCall): CarCaller? {
        val loopback = call.peerIsLoopback
        if (loopback && secretEquals(call.request.header(OaaHeaders.INTERNAL_RPC), internalSecret)) {
            return CarCaller(OaaCarAuth.KIND_INTERNAL)
        }
        val cookie = call.request.cookies[OaaCookies.CAR_SESSION]
        if (store.isHuSession(cookie)) return CarCaller(OaaCarAuth.KIND_HU)
        if (loopback && secretEquals(call.request.header(OaaHeaders.LOCAL_KEY), localKey)) {
            call.setCarCookie(store.newHuSession(), maxAgeSeconds = null)
            return CarCaller(OaaCarAuth.KIND_HU)
        }
        val token = cookie?.takeIf { it.isNotBlank() }
            ?: call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()
        val client = store.resolve(token) ?: return null
        return CarCaller(client.kind, client.id, client.name)
    }

    private fun secretEquals(provided: String?, expected: String): Boolean =
        provided != null && MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())

    companion object {
        private fun randomHex(): String {
            val b = ByteArray(32)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Who is calling; absent on unauthenticated calls to the open routes. */
internal data class CarCaller(val kind: String, val clientId: String? = null, val name: String? = null) {
    val isHeadUnit: Boolean get() = kind == OaaCarAuth.KIND_HU
    val remote: Boolean get() = !isHeadUnit
}

private val CallerKey = AttributeKey<CarCaller>("oaa.caller")

internal val ApplicationCall.caller: CarCaller? get() = attributes.getOrNull(CallerKey)

private val IP_LITERAL = Regex("""^[0-9.]+$|^[0-9a-fA-F:.%]*:[0-9a-fA-F:.%]*$""")

private val cioRemoteAddress: Field? = runCatching {
    Class.forName("io.ktor.server.cio.CIOApplicationRequest").getDeclaredField("remoteAddress").apply { isAccessible = true }
}.getOrNull()

/**
 * The peer's address as seen by the socket. On Android Ktor's `remoteHost` and `remoteAddress`
 * can both return a reverse-DNS name, which a LAN device controls through its PTR record.
 */
private val ApplicationCall.peerAddress: InetAddress?
    get() {
        val engineRequest = (request as? RoutingApplicationRequest)?.engineRequest ?: request
        val socket = cioRemoteAddress?.takeIf { it.declaringClass.isInstance(engineRequest) }
            ?.let { runCatching { it.get(engineRequest) }.getOrNull() }
        (socket as? InetSocketAddress)?.address?.let { return it }
        val text = request.local.remoteAddress
        return if (IP_LITERAL.matches(text)) runCatching { InetAddress.getByName(text) }.getOrNull() else null
    }

internal val ApplicationCall.peerIp: String get() = peerAddress?.hostAddress ?: "unknown"

internal val ApplicationCall.peerIsLoopback: Boolean get() = peerAddress?.isLoopbackAddress == true

private val OPEN_PATHS = setOf(
    OaaPaths.HEALTH,
    OaaPaths.I18N,
    OaaPaths.AUTH_STATUS,
    OaaPaths.AUTH_PAIR_REQUEST,
    OaaPaths.AUTH_PAIR_CONFIRM,
    OaaPaths.AUTH_LOCAL,
)

private fun requiresAuth(path: String): Boolean {
    if (path in OPEN_PATHS) return false
    return path.startsWith("/api/") || path == OaaPaths.DEBUG || path.startsWith(OaaPaths.DEBUG + "/")
}

/** Resolve the caller on every call; `/api/…` and `/debug…` answer 401 without one. */
internal fun Application.installCarAuth(auth: CarAuth) {
    intercept(ApplicationCallPipeline.Plugins) {
        val caller = auth.resolve(call)
        if (caller != null) call.attributes.put(CallerKey, caller)
        if (caller == null && requiresAuth(call.request.path())) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("ok" to false, "error" to "pairing required"))
            finish()
        }
    }
}

internal fun ApplicationCall.setCarCookie(token: String, maxAgeSeconds: Int?) {
    response.cookies.append(
        Cookie(
            name = OaaCookies.CAR_SESSION,
            value = token,
            // Ktor 2 omits Max-Age when it is 0, which makes a session cookie. Ktor 3 takes
            // Int? and would send Max-Age=0 (delete), so pass null there instead.
            maxAge = maxAgeSeconds ?: 0,
            path = "/",
            httpOnly = true,
            extensions = mapOf("SameSite" to "Strict"),
            encoding = CookieEncoding.RAW,
        ),
    )
}

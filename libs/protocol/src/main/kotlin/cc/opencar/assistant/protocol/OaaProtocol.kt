package cc.opencar.assistant.protocol

import org.json.JSONObject
import java.util.Base64

/** Shared wire contract for head unit and hub (docs/openapi, ADR-0003). */
object OaaHeaders {
    const val NODE = "X-Oaa-Node"
    const val INGRESS_USER_ID = "X-Remote-User-ID"
    const val INGRESS_USER_NAME = "X-Remote-User-Name"
    const val INGRESS_DISPLAY_NAME = "X-Remote-User-Display-Name"
    const val INGRESS_PATH = "X-Ingress-Path"
    const val OTA_PACKAGE = "X-Oaa-Package"
    const val OTA_VERSION_NAME = "X-Oaa-Version-Name"
    const val OTA_VERSION_CODE = "X-Oaa-Version-Code"
    /** Per-launch secret the head unit's own UI presents on its first page load. */
    const val LOCAL_KEY = "X-Oaa-Local-Key"
    /** In-process secret on requests the car replays for the hub (`rpc` frames). */
    const val INTERNAL_RPC = "X-Oaa-Internal"
}

object OaaCookies {
    const val SESSION = "oaa_session"
    /** Selected car for requests that cannot set [OaaHeaders.NODE] (links, `/debug` HTML). */
    const val NODE = "oaa_node"
    /** Car-issued session (head unit UI or a paired browser). */
    const val CAR_SESSION = "oaa_car"
}

object OaaRoles {
    const val LOCAL = "local"
    const val HUB = "hub"
}

object OaaPorts {
    const val HUMAN_DEFAULT = 8787
    const val NODE_DEFAULT = 8788
}

object OaaPaths {
    const val HEALTH = "/api/health"
    const val STATUS = "/api/status"
    const val EVENTS = "/api/events"
    const val I18N = "/api/i18n"

    const val NODES = "/api/nodes"
    const val NODES_PAIRING = "/api/nodes/pairing"
    const val NODES_PAIR = "/api/nodes/pair"
    const val NODES_SESSION = "/api/nodes/session"
    /** Node face: `GET /api/nodes/artifacts/{sha256}` (node bearer token). */
    const val NODES_ARTIFACTS = "/api/nodes/artifacts"
    /** Hub: unpaired cars seen over mDNS. */
    const val NODES_DISCOVERED = "/api/nodes/discovered"
    /** Hub: start pairing a car (`POST`), then `POST {id}/confirm` with the car's code. */
    const val NODES_INVITE = "/api/nodes/invite"
    const val AUTH_STATUS = "/api/auth/status"
    /** Car: a device or hub asks for access; the head unit shows a code. */
    const val AUTH_PAIR_REQUEST = "/api/auth/pair/request"
    const val AUTH_PAIR_CONFIRM = "/api/auth/pair/confirm"
    /** Car, head unit only: the open pairing request and its code. */
    const val AUTH_PAIR_PENDING = "/api/auth/pair/pending"
    /** Car, head unit only: trusted clients (`GET`, `DELETE /{id}`). */
    const val AUTH_CLIENTS = "/api/auth/clients"
    /** Car: swap [OaaHeaders.LOCAL_KEY] for a head unit session cookie. */
    const val AUTH_LOCAL = "/api/auth/local"
    const val AUTH_SETUP = "/api/auth/setup"
    const val AUTH_LOGIN = "/api/auth/login"
    const val AUTH_LOGOUT = "/api/auth/logout"
    const val AUTH_ME = "/api/auth/me"
    const val AUTH_SYSTEM_TOKEN = "/api/auth/system-token"
    const val AUTH_HA_AUTHORIZE = "/api/auth/authorize"
    const val AUTH_HA_CALLBACK = "/api/auth/callback"

    const val WEBRTC_SIGNAL = "/api/webrtc/signal"
    const val WEBRTC_ICE = "/api/webrtc/ice"

    const val OTA_ARTIFACTS = "/api/ota/artifacts"
    const val OTA_ROLLOUTS = "/api/ota/rollouts"

    /** Car-local hub join config (`role=local` only). */
    const val HUB_JOIN = "/api/hub"
    /** Car: app update announced by the hub (`GET`); `POST` asks the hub to install it (head unit only). */
    const val UPDATE = "/api/update"

    const val DEBUG = "/debug"
    const val DEBUG_LOGS_STREAM = "/debug/logs/stream"
}

/** `t` values on the `/api/events` UI stream (car and hub). */
object OaaUiEvents {
    const val HELLO = "hello"
    const val PING = "ping"
    const val TELEMETRY = "telemetry"
    const val ENTITY = "entity"
    const val CATALOG = "catalog"
    /** DVR summary (`dvr`), sent only when it changes. */
    const val DVR = "dvr"
    /** Car, head unit sockets only: a pairing request opened or closed (`pending` or null). */
    const val PAIR_REQUEST = "pair_request"
}

/** Car-issued access (ADR-0004): pairing by a code shown on the head unit. */
object OaaCarAuth {
    const val KIND_HU = "hu"
    const val KIND_BROWSER = "browser"
    const val KIND_HUB = "hub"
    const val KIND_INTERNAL = "internal"
    const val KIND_TOOL = "tool"

    const val CODE_TTL_MS = 3 * 60_000L
    const val MAX_ATTEMPTS = 5
}

/**
 * Hub ↔ node WebSocket frames: `{type, payload}`. Frames that negotiate behaviour
 * (signaling, data channel, OTA) carry `payload.v` = [VERSION].
 */
object OaaFrames {
    const val VERSION = 1

    const val HELLO = "hello"
    const val PING = "ping"
    const val PONG = "pong"
    const val RPC = "rpc"
    const val RPC_RESULT = "rpc_result"
    /** Car → hub: one `/api/events` UI message (`payload` is the `t`-shaped object). */
    const val EVENT = "event"
    const val OTA_OFFER = "ota_offer"
    const val OTA_STATUS = "ota_status"
    /**
     * Hub → car after `hello`: the hub's car update policy (`{v, mode}`) plus, when the hub holds a
     * newer build for this car, `{sha256, size, versionName, versionCode}`.
     */
    const val OTA_AVAILABLE = "ota_available"
    /** Car → hub: install the build announced in [OTA_AVAILABLE] (`{v, sha256}`); the hub answers with [OTA_OFFER]. */
    const val OTA_REQUEST = "ota_request"
    const val LOG_SUBSCRIBE = "log_subscribe"
    const val LOG_UNSUBSCRIBE = "log_unsubscribe"
    const val LOG = "log"
    /** Hub → car: the public node endpoint to dial when away (`{v, publicNodeUrl?, sessionPath}`). */
    const val PUBLIC_NODE = "public_node"

    fun frame(type: String, payload: JSONObject? = null): String {
        val o = JSONObject().put("type", type)
        if (payload != null) o.put("payload", payload)
        return o.toString()
    }

    fun parse(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()

    /** [EVENT] frames are always `EVENT_PREFIX + payloadJson + "}"`, so the hub can forward the payload unparsed. */
    const val EVENT_PREFIX = """{"type":"$EVENT","payload":"""

    fun eventFrame(payloadJson: String): String = "$EVENT_PREFIX$payloadJson}"

    /** Payload text of an [EVENT] frame, or null for any other frame. */
    fun eventPayload(text: String): String? =
        if (text.startsWith(EVENT_PREFIX) && text.endsWith("}")) {
            text.substring(EVENT_PREFIX.length, text.length - 1)
        } else {
            null
        }

    fun versioned(): JSONObject = JSONObject().put("v", VERSION)

    fun isCurrentVersion(payload: JSONObject?): Boolean = payload?.optInt("v", -1) == VERSION

    fun hangup(sessionId: String?, reason: String): String {
        val p = versioned().put("reason", reason)
        if (sessionId != null) p.put("sessionId", sessionId)
        return frame(OaaWebRtc.HANGUP, p)
    }
}

/**
 * HTTP-shaped request proxied hub → car over the node WebSocket. Textual bodies
 * travel as `body`, anything else as `bodyB64`. Bodies are capped at [MAX_BODY_BYTES]
 * so the base64 frame stays under the Home Assistant Cloud bridge message limit.
 */
object OaaRpc {
    const val MAX_BODY_BYTES = 2_621_440
    const val TIMEOUT_MS = 20_000L

    class Request(
        val id: String,
        val method: String,
        val path: String,
        val query: String? = null,
        val contentType: String? = null,
        val body: ByteArray? = null,
    ) {
        /** Path plus query string, as sent to the car's local server. */
        val target: String get() = if (query.isNullOrEmpty()) path else "$path?$query"
    }

    class Response(
        val status: Int,
        val contentType: String? = null,
        val contentDisposition: String? = null,
        val body: ByteArray = ByteArray(0),
    ) {
        fun text(): String = body.toString(Charsets.UTF_8)
    }

    fun isTextual(contentType: String?): Boolean {
        val ct = contentType?.lowercase() ?: return true
        return ct.startsWith("text/") || "json" in ct || "xml" in ct ||
            "javascript" in ct || "x-www-form-urlencoded" in ct
    }

    fun encodeRequest(r: Request): String {
        val p = JSONObject()
            .put("id", r.id)
            .put("method", r.method)
            .put("path", r.path)
        if (!r.query.isNullOrEmpty()) p.put("query", r.query)
        if (r.contentType != null) p.put("contentType", r.contentType)
        putBody(p, r.body, r.contentType)
        return OaaFrames.frame(OaaFrames.RPC, p)
    }

    fun decodeRequest(p: JSONObject): Request = Request(
        id = p.optString("id"),
        method = p.optString("method", "GET").uppercase(),
        path = p.optString("path", "/"),
        query = p.optString("query").takeIf { it.isNotEmpty() },
        contentType = p.optString("contentType").takeIf { it.isNotEmpty() },
        body = readBody(p),
    )

    fun encodeResponse(id: String, r: Response): String {
        val p = JSONObject().put("id", id).put("status", r.status)
        if (r.contentType != null) p.put("contentType", r.contentType)
        if (r.contentDisposition != null) p.put("contentDisposition", r.contentDisposition)
        putBody(p, r.body, r.contentType)
        return OaaFrames.frame(OaaFrames.RPC_RESULT, p)
    }

    fun decodeResponse(p: JSONObject): Response = Response(
        status = p.optInt("status", 500),
        contentType = p.optString("contentType").takeIf { it.isNotEmpty() },
        contentDisposition = p.optString("contentDisposition").takeIf { it.isNotEmpty() },
        body = readBody(p) ?: ByteArray(0),
    )

    fun error(status: Int, message: String): Response = Response(
        status = status,
        contentType = "application/json",
        body = JSONObject().put("ok", false).put("error", message).toString().toByteArray(),
    )

    private fun putBody(p: JSONObject, body: ByteArray?, contentType: String?) {
        if (body == null) return
        if (isTextual(contentType)) {
            p.put("body", body.toString(Charsets.UTF_8))
        } else {
            p.put("bodyB64", Base64.getEncoder().encodeToString(body))
        }
    }

    private fun readBody(p: JSONObject): ByteArray? = when {
        p.has("bodyB64") && !p.isNull("bodyB64") -> Base64.getDecoder().decode(p.getString("bodyB64"))
        p.has("body") && !p.isNull("body") -> p.getString("body").toByteArray(Charsets.UTF_8)
        else -> null
    }
}

/** Hub-delivered app updates (`ota_offer` → `ota_status`). */
object OaaOta {
    const val STATE_PENDING = "pending"
    const val STATE_OFFERED = "offered"
    const val STATE_DOWNLOADING = "downloading"
    const val STATE_VERIFYING = "verifying"
    const val STATE_INSTALLING = "installing"
    const val STATE_PENDING_USER = "pending_user"
    const val STATE_INSTALLED = "installed"
    const val STATE_FAILED = "failed"

    val TERMINAL = setOf(STATE_INSTALLED, STATE_FAILED)

    const val APK_MIME = "application/vnd.android.package-archive"
    const val MAX_ARTIFACT_BYTES = 256L * 1024 * 1024

    /** `ota_offer.delta`: an OADP patch (see :apk-delta) from the car's installed APK. */
    const val DELTA_MIME = "application/vnd.oaa.apk-delta"

    /** Offer the full APK instead when a delta exceeds this share of it. */
    const val MAX_DELTA_PERCENT = 70

    /** Hub car update policy ([OaaFrames.OTA_AVAILABLE] `mode`): the car shows an Install button. */
    const val MODE_ASK = "ask"
    /** The hub installs its release on cars as soon as they connect. */
    const val MODE_AUTO = "auto"
    /** The hub announces no release. */
    const val MODE_OFF = "off"
    val MODES = setOf(MODE_ASK, MODE_AUTO, MODE_OFF)

    /** Release asset describing the car APK: `{package, versionName, versionCode, sha256, size, file}`. */
    const val RELEASE_MANIFEST = "car-apk.json"

    private val SHA256 = Regex("^[0-9a-f]{64}$")

    fun isSha256(s: String?): Boolean = s != null && SHA256.matches(s)
}

/**
 * Media plane (ADR-0003): WebRTC signaling relayed by the hub, media car↔viewer only.
 * Signaling frames and data-channel JSON carry `v` = [OaaFrames.VERSION].
 */
object OaaWebRtc {
    const val OFFER = "webrtc_offer"
    const val ANSWER = "webrtc_answer"
    const val ICE = "webrtc_ice"
    const val HANGUP = "webrtc_hangup"
    val SIGNAL_TYPES = setOf(OFFER, ANSWER, ICE, HANGUP)

    const val REASON_VERSION = "version"
    const val REASON_BUSY = "busy"
    const val REASON_REPLACED = "replaced"
    const val REASON_OFFLINE = "offline"
    const val REASON_UNSUPPORTED = "unsupported"
    const val REASON_BYE = "bye"
    const val REASON_INVALID = "invalid"
    const val REASON_ERROR = "error"

    const val DC_LABEL = "oaa-media"
    const val CHUNK_MAX_BYTES = 262_144
    /** A recording download and a cut. */
    const val MAX_TRANSFERS = 2
    /** One live track per camera; the viewer offers this many recvonly video m-lines. */
    const val MAX_VIDEO_TRACKS = 4
    /** MediaStream id of a camera's live track is this prefix plus its role. */
    const val STREAM_PREFIX = "oaa-cam-"
    /** Per-session cap on relayed ICE candidates (each direction). */
    const val MAX_ICE_PER_SESSION = 128

    const val FEATURE_LIVE = "live"
    const val FEATURE_REPLAY = "replay"
    const val FEATURE_DOWNLOAD = "download"
    const val FEATURE_CUT = "cut"

    const val DC_HELLO = "dc_hello"
    /**
     * SPA→car: replay recordings on the camera tracks instead of live.
     * `replay_start {atMs, speed?, paused?}`, `replay_seek {atMs}`, `replay_pause`,
     * `replay_resume`, `replay_speed {speed}`, `replay_stop` (back to live).
     * The cameras replayed are the ones chosen with [LIVE_SELECT].
     */
    const val REPLAY_START = "replay_start"
    const val REPLAY_SEEK = "replay_seek"
    const val REPLAY_PAUSE = "replay_pause"
    const val REPLAY_RESUME = "replay_resume"
    const val REPLAY_SPEED = "replay_speed"
    const val REPLAY_STOP = "replay_stop"
    /**
     * Car→SPA `{state, atMs, speed, group?, roles, anchor}`; state is playing / paused /
     * ended / live / error. A frame with RTP time r ≥ `anchor.fromRtpMs` was captured at
     * `anchor.wallMs + (r - anchor.rtpMs) × anchor.speed` (`anchor.live`: at r itself).
     */
    const val REPLAY_STATE = "replay_state"
    const val DOWNLOAD_OPEN = "download_open"
    const val CUT_REQUEST = "cut_request"
    /** Car→SPA text companion to binary chunks: size / mime / name / codec for a reqId. */
    const val MEDIA_META = "media_meta"
    const val MEDIA_ERROR = "media_error"
    const val TRANSFER_CANCEL = "transfer_cancel"
    /** Viewer hidden / visible again: car stops or resumes feeding the live RTP tracks. */
    const val LIVE_PAUSE = "live_pause"
    const val LIVE_RESUME = "live_resume"
    /** SPA→car `{roles}`: cameras to send live (grid vs one camera); the rest stop. */
    const val LIVE_SELECT = "live_select"
    /** Car→SPA `{roles, streaming}`: tracks in this session and the cameras sending now. */
    const val LIVE_TRACKS = "live_tracks"
    /** SPA→car `{t0}`; the car answers [CLOCK] `{t0, carUtcMs}` for the viewer's clock offset. */
    const val CLOCK_SYNC = "clock_sync"
    const val CLOCK = "clock"
    /** Car→SPA `{reqId, doneMs, totalMs}` while an export is re-encoding. */
    const val MEDIA_PROGRESS = "media_progress"

    private val SESSION_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

    fun isValidSessionId(id: String?): Boolean = id != null && SESSION_ID.matches(id)
}

/**
 * Binary data-channel frame: `u8 type | u32 reqId | u32 seq | u8 flags | payload` (big-endian).
 */
object OaaMediaChunk {
    const val TYPE_CHUNK: Int = 1
    const val FLAG_EOF: Int = 0x01
    const val HEADER_BYTES = 10

    class Frame(
        val type: Int,
        val reqId: Long,
        val seq: Long,
        val flags: Int,
        val payload: ByteArray,
    ) {
        val isEof: Boolean get() = flags and FLAG_EOF != 0
    }

    fun encode(
        reqId: Long,
        seq: Long,
        flags: Int,
        payload: ByteArray,
        offset: Int = 0,
        length: Int = payload.size - offset,
        type: Int = TYPE_CHUNK,
    ): ByteArray {
        require(length in 0..OaaWebRtc.CHUNK_MAX_BYTES) { "chunk too large: $length" }
        val out = ByteArray(HEADER_BYTES + length)
        out[0] = type.toByte()
        putU32(out, 1, reqId)
        putU32(out, 5, seq)
        out[9] = flags.toByte()
        System.arraycopy(payload, offset, out, HEADER_BYTES, length)
        return out
    }

    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER_BYTES) return null
        return Frame(
            type = bytes[0].toInt() and 0xff,
            reqId = getU32(bytes, 1),
            seq = getU32(bytes, 5),
            flags = bytes[9].toInt() and 0xff,
            payload = bytes.copyOfRange(HEADER_BYTES, bytes.size),
        )
    }

    private fun putU32(out: ByteArray, at: Int, v: Long) {
        out[at] = ((v ushr 24) and 0xff).toByte()
        out[at + 1] = ((v ushr 16) and 0xff).toByte()
        out[at + 2] = ((v ushr 8) and 0xff).toByte()
        out[at + 3] = (v and 0xff).toByte()
    }

    private fun getU32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xff) shl 24) or
            ((b[at + 1].toLong() and 0xff) shl 16) or
            ((b[at + 2].toLong() and 0xff) shl 8) or
            (b[at + 3].toLong() and 0xff)
}

/** Data-channel path ACL: recordings are addressed by basename only. */
object OaaMediaNames {
    private val SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    fun isSafeBasename(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return false
        return SAFE.matches(name)
    }
}

object OaaMdns {
    const val SERVICE_TYPE = "_oaa-hub._tcp.local."
    const val SERVICE_NAME = "Open Automotive Assistant Hub"
    /** Advertised by cars that are not paired to a hub. */
    const val CAR_SERVICE_TYPE = "_oaa-car._tcp.local."
    const val TXT_ID = "id"
    const val TXT_NAME = "name"
    const val TXT_INTEGRATION = "integration"
    const val TXT_VERSION = "version"
    const val TXT_HUB_ID = "hub_id"
}

/**
 * Path segments that serve the SPA shell (keep in sync with features/web/ui/src/pages/ids.js).
 * Cars report them as `pages` in `/api/status`; a hub's newer UI hides pages the car lacks.
 */
object OaaSpa {
    val PAGES = setOf(
        "home", "cars", "history", "controls", "drive", "energy", "lights", "adas",
        "assistant", "display", "sound", "connect", "vehicle", "cameras", "store",
        "shortcuts", "plugins", "settings", "lab", "about", "login", "setup",
    )
}

/** Content types shared by the car and hub static routes. */
object OaaMime {
    fun forPath(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "html" -> "text/html; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "webmanifest" -> "application/manifest+json"
        "m3u8" -> "application/vnd.apple.mpegurl"
        else -> "application/octet-stream"
    }
}

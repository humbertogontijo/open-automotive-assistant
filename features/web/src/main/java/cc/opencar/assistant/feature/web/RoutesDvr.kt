package cc.opencar.assistant.feature.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * DVR HTTP surface: mode / timeline / play / cut / clear / storage / policy / live HLS.
 */
internal fun Routing.registerDvrRoutes(deps: OaaWebDeps) {
    val dvr = deps.dvr

    post("/api/dvr/mode") {
        val params = call.params()
        val mode = params["mode"]
        val storage = params["storage"]
        if (storage != null) dvr.setStorage(storage)
        val res = withContext(Dispatchers.IO) { dvr.setMode(mode) }
        call.respond(res)
    }
    post("/api/dvr/policy") {
        val params = call.params()
        val maxTotalMb = (params["maxTotalMb"])
            ?.toIntOrNull()
        val maxAgeDays = (params["maxAgeDays"])
            ?.toIntOrNull()
        val status = withContext(Dispatchers.IO) { dvr.setPolicy(maxTotalMb, maxAgeDays) }
        call.respond(mapOf("ok" to true, "status" to status))
    }
    post("/api/dvr/storage") {
        val params = call.params()
        val id = params["id"]
        val ok = withContext(Dispatchers.IO) { dvr.setStorage(id) }
        call.respond(mapOf("ok" to ok, "status" to dvr.status()))
    }
    get("/api/dvr/timeline") {
        call.respond(withContext(Dispatchers.IO) { dvr.timeline() })
    }
    get("/api/dvr/play") {
        val atMs = call.request.queryParameters["atMs"]?.toLongOrNull()
        if (atMs == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "atMs required"))
            return@get
        }
        call.respond(withContext(Dispatchers.IO) { dvr.resolvePlayAt(atMs) })
    }
    get("/api/dvr/cut") {
        val fromMs = call.request.queryParameters["fromMs"]?.toLongOrNull()
        val toMs = call.request.queryParameters["toMs"]?.toLongOrNull()
        if (fromMs == null || toMs == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("ok" to false, "error" to "fromMs and toMs required"),
            )
            return@get
        }
        val cut = withContext(Dispatchers.IO) {
            runCatching { dvr.cutWallClockToTemp(fromMs, toMs) }
        }.getOrElse { t ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("ok" to false, "error" to (t.message ?: "cut failed")),
            )
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"${cut.downloadName}\"",
        )
        try {
            withContext(Dispatchers.IO) {
                call.respondOutputStream(ContentType.parse("video/mp4")) {
                    cut.file.inputStream().use { input -> input.copyTo(this) }
                }
            }
        } finally {
            cut.file.delete()
        }
    }
    get("/api/dvr/recordings/{name}") {
        val name = call.parameters["name"] ?: return@get
        val file = dvr.recordingFile(name)
        if (file == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("ok" to false, "error" to "not found"))
            return@get
        }
        val inline = call.request.queryParameters["inline"] == "1"
        if (!inline) {
            call.response.header(
                HttpHeaders.ContentDisposition,
                "attachment; filename=\"${file.name}\"",
            )
        }
        call.response.header(HttpHeaders.ContentType, "video/mp4")
        call.respondFile(file)
    }
    post("/api/dvr/clear") {
        val includeLocked = parseBool(call.params()["includeLocked"]) ?: true
        val res = withContext(Dispatchers.IO) { dvr.clearRecordings(includeLocked) }
        call.respond(res)
    }
    post("/api/dvr/preview/start") {
        val ok = withContext(Dispatchers.IO) { dvr.startPreview(null) }
        call.respond(mapOf("ok" to ok, "status" to dvr.status()))
    }
    post("/api/dvr/preview/stop") {
        dvr.stopPreview()
        call.respond(mapOf("ok" to true, "status" to dvr.status()))
    }
    /**
     * Live HLS (CMAF/fMP4). One <video> + hls.js — no clip reloads.
     * Supports LL-HLS blocking reload via `_HLS_msn` (holds until that SN exists).
     */
    get("/api/dvr/live.m3u8") {
        val started = withContext(Dispatchers.IO) { dvr.startPreview(null) }
        if (!started) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to (dvr.lastError ?: "preview failed")),
            )
            return@get
        }
        val msn = call.request.queryParameters["_HLS_msn"]?.toLongOrNull()
        // `_HLS_part` ignored — we publish whole segments only.
        val body = withContext(Dispatchers.IO) {
            if (msn != null) {
                dvr.hlsPlaylistBlocking(msn)
            } else {
                var ready: String? = null
                var waits = 0
                while (ready == null && waits < 80) {
                    ready = dvr.hlsPlaylist()
                    if (ready == null) {
                        delay(100)
                        waits++
                    }
                }
                ready
            }
        }
        if (body == null) {
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf("error" to "hls not ready", "detail" to dvr.lastError),
            )
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(body, ContentType.parse("application/vnd.apple.mpegurl"))
    }
    get("/api/dvr/live/init.mp4") {
        val init = dvr.fmp4InitSegment()
        if (init == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no init"))
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondBytes(init, ContentType.parse("video/mp4"))
    }
    get("/api/dvr/live/seg/{seq}") {
        val seq = call.parameters["seq"]?.removeSuffix(".m4s")?.toLongOrNull()
        if (seq == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "bad seq"))
            return@get
        }
        val frag = dvr.fmp4Fragment(seq)
        if (frag == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "seg gone"))
            return@get
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondBytes(frag, ContentType.parse("video/iso.segment"))
    }
}

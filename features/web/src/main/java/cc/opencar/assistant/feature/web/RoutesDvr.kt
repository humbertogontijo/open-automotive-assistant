package cc.opencar.assistant.feature.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DVR HTTP surface: mode / timeline / per-camera play / per-camera cut / clear /
 * storage / policy. Live video is WebRTC only (see [registerWebRtcRoutes]).
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
        val role = call.request.queryParameters["role"]?.takeIf { it.isNotEmpty() }
        call.respond(withContext(Dispatchers.IO) { dvr.resolvePlayAt(atMs, role) })
    }
    get("/api/dvr/cut") {
        val q = call.request.queryParameters
        val role = q["role"]
        val fromMs = q["fromMs"]?.toLongOrNull()
        val toMs = q["toMs"]?.toLongOrNull()
        if (role.isNullOrEmpty() || fromMs == null || toMs == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("ok" to false, "error" to "role, fromMs and toMs required"),
            )
            return@get
        }
        if (role !in dvr.cameraRoles()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "unknown camera"))
            return@get
        }
        val cut = withContext(Dispatchers.IO) {
            runCatching { dvr.cutWallClockToTemp(role, fromMs, toMs) }
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
        // Range requests (seeking in a <video>) are answered by the PartialContent plugin.
        call.respondFile(file)
    }
    post("/api/dvr/clear") {
        val includeLocked = parseBool(call.params()["includeLocked"]) ?: true
        val res = withContext(Dispatchers.IO) { dvr.clearRecordings(includeLocked) }
        call.respond(res)
    }
    post("/api/dvr/preview/start") {
        val ok = withContext(Dispatchers.IO) { dvr.startPreview() }
        call.respond(mapOf("ok" to ok, "status" to dvr.status()))
    }
    post("/api/dvr/preview/stop") {
        withContext(Dispatchers.IO) { dvr.stopPreview() }
        call.respond(mapOf("ok" to true, "status" to dvr.status()))
    }
}

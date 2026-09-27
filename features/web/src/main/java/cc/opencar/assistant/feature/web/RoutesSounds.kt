package cc.opencar.assistant.feature.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MAX_SOUND_BYTES = 16L shl 20

internal fun Routing.registerSoundRoutes(deps: OaaWebDeps) {
    val sounds = deps.sounds ?: return

    get("/api/sounds") {
        call.respond(sounds.snapshot())
    }
    get("/api/sounds/{kind}") {
        val kind = SoundsController.Kind.from(call.parameters["kind"])
        if (kind == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "kind=avas|lock"))
            return@get
        }
        call.respond(
            mapOf(
                "ok" to true,
                "kind" to kind.id,
                "active" to sounds.activeName(kind),
                "files" to sounds.list(kind),
            ),
        )
    }
    post("/api/sounds/upload") {
        val kind = SoundsController.Kind.from(
            call.request.queryParameters["kind"] ?: call.request.headers["X-Sound-Kind"],
        )
        if (kind == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "kind=avas|lock"))
            return@post
        }
        val name = call.request.queryParameters["name"]
            ?: call.request.headers["X-Filename"]
            ?: "sound-${System.currentTimeMillis()}.wav"
        val out = ByteArrayOutputStream()
        if (!call.receiveCapped(out, MAX_SOUND_BYTES)) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("ok" to false, "error" to "sound too large"))
            return@post
        }
        call.respond(withContext(Dispatchers.IO) { sounds.saveUpload(kind, name, out.toByteArray()) })
    }
    post("/api/sounds/apply") {
        val params = call.params()
        val kind = SoundsController.Kind.from(params["kind"])
        if (kind == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "kind=avas|lock"))
            return@post
        }
        val name = params["name"]
        call.respond(sounds.setActive(kind, name))
    }
    post("/api/sounds/preview") {
        val params = call.params()
        val kind = SoundsController.Kind.from(params["kind"])
        if (kind == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "kind=avas|lock"))
            return@post
        }
        val name = params["name"]
        call.respond(sounds.preview(kind, name))
    }
    post("/api/sounds/preview/stop") {
        sounds.stopPreview()
        call.respond(mapOf("ok" to true))
    }
    delete("/api/sounds/{kind}/{name}") {
        val kind = SoundsController.Kind.from(call.parameters["kind"])
        val name = call.parameters["name"]
        if (kind == null || name.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "bad path"))
            return@delete
        }
        call.respond(sounds.delete(kind, name))
    }
}

package cc.opencar.assistant.feature.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_APK_BYTES = 1L shl 30

internal fun Routing.registerStoreRoutes(deps: OaaWebDeps) {
    post("/api/install/binary") {
        val expected = call.request.headers["X-Sha256"]
        val file = File(deps.installer.installDir(), "upload-${System.currentTimeMillis()}.apk")
        if (!file.outputStream().use { call.receiveCapped(it, MAX_APK_BYTES) }) {
            file.delete()
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("ok" to false, "message" to "APK too large"))
            return@post
        }
        val result = withContext(Dispatchers.IO) { deps.installer.install(file, expected) }
        call.respond(mapOf("ok" to result.ok, "message" to result.message, "sha256" to result.sha256))
    }
    get("/api/store/search") {
        val q = call.request.queryParameters["q"].orEmpty()
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 30
        val includeFdroid = call.request.queryParameters["fdroid"] != "0"
        val hits = withContext(Dispatchers.IO) { deps.store.search(q, limit, includeFdroid) }
        call.respond(
            mapOf(
                "ok" to true,
                "query" to q,
                "apps" to hits.map {
                    mapOf(
                        "packageName" to it.packageName,
                        "name" to it.name,
                        "summary" to it.summary,
                        "iconUrl" to it.iconUrl,
                        "source" to it.source,
                        "installReady" to it.installReady,
                    )
                },
            ),
        )
    }
    get("/api/store/package/{packageName}") {
        val pkg = call.parameters["packageName"].orEmpty()
        val detail = withContext(Dispatchers.IO) { deps.store.detail(pkg) }
        if (detail == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("ok" to false, "message" to "not found"))
            return@get
        }
        call.respond(detail)
    }
    post("/api/store/install") {
        val params = call.params()
        val pkg = params["packageName"]
            ?: call.request.queryParameters["packageName"]
            ?: ""
        val vc = (params["versionCode"])
            ?.toLongOrNull()
        if (pkg.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "message" to "packageName required"))
            return@post
        }
        val outcome = withContext(Dispatchers.IO) { deps.store.install(pkg, vc) }
        call.respond(
            mapOf(
                "ok" to outcome.ok,
                "message" to outcome.message,
                "packageName" to outcome.packageName,
                "sha256" to outcome.sha256,
            ),
        )
    }
}

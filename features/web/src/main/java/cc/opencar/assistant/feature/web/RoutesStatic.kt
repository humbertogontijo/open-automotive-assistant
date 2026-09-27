package cc.opencar.assistant.feature.web

import cc.opencar.assistant.protocol.OaaMime
import cc.opencar.assistant.protocol.OaaSpa
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

internal fun Routing.registerStaticRoutes(deps: OaaWebDeps) {
    get("/") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondText(deps.assetText("web/index.html"), ContentType.Text.Html)
    }
    get("/static/{path...}") {
        val rel = call.parameters.getAll("path")?.joinToString("/") ?: return@get
        if (rel.contains("..") || rel.startsWith("/")) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        val bytes = try {
            deps.assetBytes("web/$rel")
        } catch (_: Exception) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondBytes(bytes, ContentType.parse(OaaMime.forPath(rel)))
    }
}

/**
 * SPA shell for known section paths. Register **after** API/debug routes so
 * `/{section}` cannot shadow `/api`, `/debug`, etc.
 */
internal fun Routing.registerSpaFallbackRoutes(deps: OaaWebDeps) {
    get("/{section}") {
        val section = call.parameters["section"] ?: return@get
        if (section !in OaaSpa.PAGES) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondText(deps.assetText("web/index.html"), ContentType.Text.Html)
    }
}

internal fun OaaWebDeps.assetText(path: String): String =
    context.assets.open(path).bufferedReader().use { it.readText() }

internal fun OaaWebDeps.assetBytes(path: String): ByteArray =
    context.assets.open(path).use { it.readBytes() }

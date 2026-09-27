package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaMime
import cc.opencar.assistant.protocol.OaaSpa
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/** SPA shell + bundled `web/` assets (same files the car serves). */
internal fun Routing.staticRoutes() {
    get("/") { serveIndex(call) }
    get("/static/{path...}") {
        val rel = call.parameters.getAll("path")?.joinToString("/") ?: return@get
        if (rel.contains("..") || rel.startsWith("/")) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        val bytes = resourceBytes("web/$rel") ?: return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondBytes(bytes, ContentType.parse(OaaMime.forPath(rel)))
    }
    get("/{section}") {
        if (call.parameters["section"] !in OaaSpa.PAGES) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        serveIndex(call)
    }
}

private suspend fun serveIndex(call: ApplicationCall) {
    call.response.header(HttpHeaders.CacheControl, "no-store")
    val html = resourceBytes("web/index.html")?.toString(Charsets.UTF_8) ?: MISSING_HTML
    call.respondText(html, ContentType.Text.Html)
}

internal fun resourceBytes(path: String): ByteArray? =
    HubContext::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }

private val MISSING_HTML = """
    <!DOCTYPE html><html><body>
    <h1>OAA Hub</h1>
    <p>Web assets missing. Rebuild with Gradle so <code>features/web</code> assets are copied into the server jar.</p>
    </body></html>
""".trimIndent()

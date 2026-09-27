package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaSpa
import cc.opencar.assistant.protocol.OaaStatic
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import java.io.File

/**
 * SPA shell + `web/` assets (the same bundle the car serves). `OAA_WEB_DIR` serves
 * them from disk instead, e.g. `features/web/ui/build/dist/web` with `npm run dev`.
 */
internal fun Routing.staticRoutes(webDir: File? = System.getenv("OAA_WEB_DIR")?.takeIf { it.isNotBlank() }?.let(::File)) {
    val read = OaaStatic.cachingImmutable(
        if (webDir != null) {
            { rel -> File(webDir, rel).takeIf { it.isFile }?.readBytes() }
        } else {
            { rel -> resourceBytes("web/$rel") }
        },
    )
    get("/") { serveIndex(call, read) }
    get("/static/{path...}") {
        val rel = call.parameters.getAll("path")?.joinToString("/") ?: return@get
        if (!OaaStatic.isSafePath(rel)) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        val asset = OaaStatic.resolve(rel, call.request.header(HttpHeaders.AcceptEncoding), read = read)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondAsset(asset)
    }
    get("/{section}") {
        if (call.parameters["section"] !in OaaSpa.PAGES) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        serveIndex(call, read)
    }
}

private suspend fun serveIndex(call: ApplicationCall, read: (String) -> ByteArray?) {
    val asset = OaaStatic.resolve(OaaStatic.INDEX, call.request.header(HttpHeaders.AcceptEncoding), read = read)
    if (asset == null) {
        call.response.header(HttpHeaders.CacheControl, OaaStatic.CACHE_NO_STORE)
        call.respondText(MISSING_HTML, ContentType.Text.Html)
        return
    }
    call.respondAsset(asset)
}

private suspend fun ApplicationCall.respondAsset(asset: OaaStatic.Asset) {
    response.header(HttpHeaders.CacheControl, asset.cacheControl)
    response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
    asset.encoding?.let { response.header(HttpHeaders.ContentEncoding, it) }
    respondBytes(asset.bytes, ContentType.parse(asset.contentType))
}

internal fun resourceBytes(path: String): ByteArray? =
    HubContext::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes() }

private val MISSING_HTML = """
    <!DOCTYPE html><html><body>
    <h1>OAA Hub</h1>
    <p>Web assets missing. Rebuild with Gradle so the <code>:webui</code> bundle is copied into the server jar.</p>
    </body></html>
""".trimIndent()

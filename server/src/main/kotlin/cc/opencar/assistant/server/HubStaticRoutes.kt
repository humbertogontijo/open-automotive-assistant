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
internal fun Routing.staticRoutes(
    isNode: (String) -> Boolean,
    webDir: File? = System.getenv("OAA_WEB_DIR")?.takeIf { it.isNotBlank() }?.let(::File),
) {
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
    // Hub pages (`/settings`) and a car's home without the slash (`/<node>`).
    get("/{section}") {
        val section = call.parameters["section"].orEmpty()
        if (section !in OaaSpa.PAGES && !isNode(section)) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        serveIndex(call, read)
    }
    // A car's pages: `/<node>/`, `/<node>/cameras`. Unknown cars still get the SPA, which returns to the fleet.
    get("/{node}/") { serveNestedIndex(call, read) }
    get("/{node}/{section}") {
        if (call.parameters["section"] !in OaaSpa.PAGES) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        serveNestedIndex(call, read)
    }
}

/**
 * The shell one directory below the web root. Its asset URLs are relative (so Ingress prefixes
 * work), hence a `<base>` pointing back up to the root.
 */
private suspend fun serveNestedIndex(call: ApplicationCall, read: (String) -> ByteArray?) {
    val html = read(OaaStatic.INDEX)?.toString(Charsets.UTF_8) ?: return serveIndex(call, read)
    call.response.header(HttpHeaders.CacheControl, OaaStatic.CACHE_NO_STORE)
    call.respondText(html.replaceFirst("<head>", "<head><base href=\"../\">"), ContentType.Text.Html)
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

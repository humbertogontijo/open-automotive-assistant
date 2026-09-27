package cc.opencar.assistant.feature.web

import android.content.Context
import cc.opencar.assistant.protocol.OaaSpa
import cc.opencar.assistant.protocol.OaaStatic
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/** Android's asset merger strips `.gz`, so the APK stores gzip variants as `.gzip`. */
private val APK_SUFFIXES = linkedMapOf("br" to ".br", "gzip" to ".gzip")

private typealias AssetReader = (String) -> ByteArray?

private fun assetReader(context: Context): AssetReader = OaaStatic.cachingImmutable { path ->
    runCatching { context.assets.open("web/$path").use { it.readBytes() } }.getOrNull()
}

internal fun Routing.registerStaticRoutes(deps: OaaWebDeps) {
    val read = assetReader(deps.context)
    get("/") { serveIndex(call, read) }
    get("/static/{path...}") {
        val rel = call.parameters.getAll("path")?.joinToString("/") ?: return@get
        if (!OaaStatic.isSafePath(rel)) {
            call.respond(HttpStatusCode.BadRequest)
            return@get
        }
        val asset = OaaStatic.resolve(rel, call.request.header(HttpHeaders.AcceptEncoding), APK_SUFFIXES, read)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respondAsset(asset)
    }
}

/**
 * SPA shell for known section paths. Register **after** API/debug routes so
 * `/{section}` cannot shadow `/api`, `/debug`, etc.
 */
internal fun Routing.registerSpaFallbackRoutes(deps: OaaWebDeps) {
    val read = assetReader(deps.context)
    get("/{section}") {
        val section = call.parameters["section"] ?: return@get
        if (section !in OaaSpa.PAGES) {
            call.respond(HttpStatusCode.NotFound)
            return@get
        }
        serveIndex(call, read)
    }
}

private suspend fun serveIndex(call: ApplicationCall, read: AssetReader) {
    val asset = OaaStatic.resolve(OaaStatic.INDEX, call.request.header(HttpHeaders.AcceptEncoding), APK_SUFFIXES, read)
        ?: return call.respond(HttpStatusCode.NotFound)
    call.respondAsset(asset)
}

private suspend fun ApplicationCall.respondAsset(asset: OaaStatic.Asset) {
    response.headers.append(HttpHeaders.CacheControl, asset.cacheControl)
    response.headers.append(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
    asset.encoding?.let { response.headers.append(HttpHeaders.ContentEncoding, it) }
    respondBytes(asset.bytes, ContentType.parse(asset.contentType))
}

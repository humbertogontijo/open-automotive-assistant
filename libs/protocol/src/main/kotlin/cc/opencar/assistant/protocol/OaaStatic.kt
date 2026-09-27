package cc.opencar.assistant.protocol

import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * SPA static files for the car and hub routes: precompressed variants written by
 * `features/web/ui/build.mjs` and the cache policy for hashed bundles.
 */
object OaaStatic {
    /** Hashed bundle directory under the web root (`/static/assets/...`). */
    const val HASHED_DIR = "assets/"
    const val CACHE_IMMUTABLE = "public, max-age=31536000, immutable"
    const val CACHE_NO_STORE = "no-store"
    const val INDEX = "index.html"

    /** Suffix of each precompressed variant, by content coding, in server preference order. */
    val DEFAULT_SUFFIXES: Map<String, String> = linkedMapOf("br" to ".br", "gzip" to ".gz")

    class Asset(
        val bytes: ByteArray,
        val contentType: String,
        /** `Content-Encoding` value, or null for the raw file. */
        val encoding: String?,
        val cacheControl: String,
    )

    /** Relative web path without traversal or absolute components. */
    fun isSafePath(rel: String): Boolean =
        rel.isNotEmpty() && !rel.startsWith("/") && !rel.contains("..") && !rel.contains('\\')

    fun cacheControl(rel: String): String =
        if (rel.startsWith(HASHED_DIR)) CACHE_IMMUTABLE else CACHE_NO_STORE

    /** Codings from an `Accept-Encoding` header with a non-zero q-value (`*` is ignored). */
    fun acceptedCodings(acceptEncoding: String?): Set<String> {
        if (acceptEncoding.isNullOrBlank()) return emptySet()
        val out = LinkedHashSet<String>()
        for (part in acceptEncoding.split(',')) {
            val fields = part.split(';').map { it.trim() }
            val coding = fields.first().lowercase()
            if (coding.isEmpty() || coding == "*") continue
            val q = fields.drop(1)
                .firstOrNull { it.startsWith("q=", ignoreCase = true) }
                ?.substring(2)?.toDoubleOrNull() ?: 1.0
            if (q > 0.0) out += coding
        }
        return out
    }

    /**
     * Picks the best variant of [rel] the client accepts, falling back to the raw file.
     * [read] returns the bytes of a path under the web root, or null when absent.
     */
    fun resolve(
        rel: String,
        acceptEncoding: String?,
        suffixes: Map<String, String> = DEFAULT_SUFFIXES,
        read: (String) -> ByteArray?,
    ): Asset? {
        if (!isSafePath(rel)) return null
        val type = OaaMime.forPath(rel)
        val cache = cacheControl(rel)
        val accepted = acceptedCodings(acceptEncoding)
        for ((coding, suffix) in suffixes) {
            if (coding !in accepted) continue
            val bytes = read(rel + suffix) ?: continue
            return Asset(bytes, type, coding, cache)
        }
        return read(rel)?.let { Asset(it, type, null, cache) }
    }

    /** Wraps [read] so files under [HASHED_DIR] (content-addressed, never changing) are read once, misses included. */
    fun cachingImmutable(read: (String) -> ByteArray?): (String) -> ByteArray? {
        val cache = ConcurrentHashMap<String, Optional<ByteArray>>()
        return { rel ->
            if (rel.startsWith(HASHED_DIR)) cache.getOrPut(rel) { Optional.ofNullable(read(rel)) }.orElse(null) else read(rel)
        }
    }
}

package cc.opencar.assistant.feature.install

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Blocking HTTP and hashing shared by the app stores; call from an IO thread. */
internal object StoreHttp {
    private const val USER_AGENT = "OpenAutomotiveAssistant/0.1"
    const val TIMEOUT_MS = 20_000
    private const val DOWNLOAD_TIMEOUT_MS = 180_000

    private fun open(url: String, readTimeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = readTimeoutMs
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            instanceFollowRedirects = true
        }

    fun get(url: String): String {
        val conn = open(url, TIMEOUT_MS)
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code for $url: ${body.take(200)}")
            }
            body
        } finally {
            conn.disconnect()
        }
    }

    fun download(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        val conn = open(url, DOWNLOAD_TIMEOUT_MS)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code downloading $url")
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Lowercase hex digest of [file] (`SHA-256`, `MD5`, …). */
    fun digestHex(file: File, algo: String = "SHA-256"): String {
        val digest = MessageDigest.getInstance(algo)
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

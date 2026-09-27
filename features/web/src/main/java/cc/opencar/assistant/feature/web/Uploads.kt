package cc.opencar.assistant.feature.web

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Streams the request body into [out] on the IO dispatcher.
 * @return false (with [out] partially written) once the body exceeds [maxBytes].
 */
internal suspend fun ApplicationCall.receiveCapped(out: OutputStream, maxBytes: Long): Boolean {
    if ((request.contentLength() ?: 0L) > maxBytes) return false
    val channel = receiveChannel()
    return withContext(Dispatchers.IO) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = channel.readAvailable(buf, 0, buf.size)
            if (n < 0) break
            total += n
            if (total > maxBytes) return@withContext false
            out.write(buf, 0, n)
        }
        true
    }
}

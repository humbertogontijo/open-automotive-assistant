package cc.opencar.assistant.server

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether [PublicNode.dialUrl] leads back to this hub: an empty pair request through it must
 * reach our node face, which rejects it naming its `hubId`. Checked in the background and
 * cached, so the Fleet page can say why away cars cannot connect.
 */
class PublicNodeCheck(
    private val publicNode: PublicNode,
    private val hubId: () -> String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    class Result(val ok: Boolean, val detail: String?, val checkedAtMs: Long)

    @Volatile private var last: Result? = null
    private val running = AtomicBoolean(false)

    /** Latest result (null until the first check finishes); starts a new check when stale. */
    fun latest(): Result? {
        val r = last
        val maxAge = if (r?.ok == true) OK_TTL_MS else FAILED_TTL_MS
        if (r == null || System.currentTimeMillis() - r.checkedAtMs > maxAge) refresh()
        return r
    }

    fun refresh() {
        val url = publicNode.url ?: return
        if (!running.compareAndSet(false, true)) return
        val req = HttpRequest.newBuilder(URI(url + publicNode.pairPath))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build()
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete { resp, err ->
            last = Result(err == null && answeredByUs(resp), err?.let { describe(it) } ?: resp?.let { describe(it) }, System.currentTimeMillis())
            running.set(false)
        }
    }

    private fun answeredByUs(resp: HttpResponse<String>?): Boolean =
        resp != null && runCatching { JSONObject(resp.body()).optString("hubId") }.getOrNull() == hubId()

    private fun describe(resp: HttpResponse<String>): String? {
        if (answeredByUs(resp)) return null
        val error = runCatching { JSONObject(resp.body()).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
        return "HTTP ${resp.statusCode()}" + (error?.let { ": $it" } ?: "")
    }

    private fun describe(err: Throwable): String {
        val cause = generateSequence(err) { it.cause }.last()
        return cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
    }

    fun toMap(): Map<String, Any?>? = latest()?.let { mapOf("ok" to it.ok, "detail" to it.detail, "checkedAtMs" to it.checkedAtMs) }

    private companion object {
        const val OK_TTL_MS = 5 * 60_000L
        const val FAILED_TTL_MS = 30_000L
    }
}

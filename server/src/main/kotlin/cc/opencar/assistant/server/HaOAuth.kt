package cc.opencar.assistant.server

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * "Sign in with Home Assistant": authorization-code exchange against HA's IndieAuth
 * endpoints, then `auth/current_user` over the HA WebSocket API for the identity.
 */
class HaOAuth(
    private val haUrl: String,
    private val clientId: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    data class Identity(val id: String, val name: String, val isAdmin: Boolean)

    fun authorizeUrl(redirectUri: String, state: String): String =
        haUrl.trimEnd('/') + "/auth/authorize?response_type=code" +
            "&client_id=" + enc(clientId) +
            "&redirect_uri=" + enc(redirectUri) +
            "&state=" + enc(state)

    fun exchange(code: String): Identity {
        val form = "grant_type=authorization_code&code=${enc(code)}&client_id=${enc(clientId)}"
        val resp = http.send(
            HttpRequest.newBuilder(URI(haUrl.trimEnd('/') + "/auth/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(resp.statusCode() == 200) { "token exchange failed (${resp.statusCode()})" }
        val accessToken = JSONObject(resp.body()).getString("access_token")
        return currentUser(accessToken)
    }

    private fun currentUser(accessToken: String): Identity {
        val result = CompletableFuture<Identity>()
        val wsUrl = haUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/api/websocket"
        val listener = object : WebSocket.Listener {
            private val buf = StringBuilder()

            override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buf.append(data)
                if (last) {
                    val msg = JSONObject(buf.toString())
                    buf.setLength(0)
                    when (msg.optString("type")) {
                        "auth_required" -> ws.sendText(
                            JSONObject().put("type", "auth").put("access_token", accessToken).toString(),
                            true,
                        )
                        "auth_ok" -> ws.sendText(
                            JSONObject().put("id", 1).put("type", "auth/current_user").toString(),
                            true,
                        )
                        "auth_invalid" -> result.completeExceptionally(IllegalStateException("HA rejected token"))
                        "result" -> {
                            val user = msg.optJSONObject("result")
                            if (msg.optBoolean("success") && user != null) {
                                result.complete(
                                    Identity(
                                        id = user.getString("id"),
                                        name = user.optString("name"),
                                        isAdmin = user.optBoolean("is_admin"),
                                    ),
                                )
                            } else {
                                result.completeExceptionally(IllegalStateException("auth/current_user failed"))
                            }
                            ws.sendClose(WebSocket.NORMAL_CLOSURE, "done")
                        }
                    }
                }
                ws.request(1)
                return null
            }

            override fun onError(ws: WebSocket, error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI(wsUrl), listener)
            .exceptionally { result.completeExceptionally(it); null }
        return result.get(15, TimeUnit.SECONDS)
    }

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
}

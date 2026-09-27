package cc.opencar.assistant.feature.web

import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.protocol.OaaRoles
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.json.JSONObject

internal fun Routing.registerHubRoutes(deps: OaaWebDeps) {
    // The car UI has no login; hub-auth.js reads this to skip the hub sign-in overlay.
    get(OaaPaths.AUTH_STATUS) {
        call.respond(mapOf("role" to OaaRoles.LOCAL, "authenticated" to true, "setupRequired" to false))
    }

    val hub = deps.hub ?: return

    get(OaaPaths.HUB_JOIN) {
        call.respond(hub.status())
    }

    post(OaaPaths.HUB_JOIN) {
        val obj = runCatching { JSONObject(call.receiveText()) }.getOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "invalid json"))
        val hubUrl = obj.optString("hubUrl").trim()
        val code = obj.optString("code").trim()
        if (hubUrl.isEmpty() || code.isEmpty()) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("ok" to false, "error" to "hubUrl and code required"))
        }
        val result = hub.join(hubUrl, code, obj.optString("name").takeIf { it.isNotBlank() })
        call.respond(if (result["ok"] == true) HttpStatusCode.OK else HttpStatusCode.BadRequest, result)
    }

    delete(OaaPaths.HUB_JOIN) {
        call.respond(hub.leave())
    }
}

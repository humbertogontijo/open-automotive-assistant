package cc.opencar.assistant.feature.web

import cc.opencar.assistant.feature.debug.ContributorDebugState
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond

/**
 * Form fields with the query string as fallback (form wins). Reads the body, so call it
 * once per request; a missing or non-form body leaves only the query parameters.
 */
internal suspend fun ApplicationCall.params(): Parameters {
    val form = runCatching { receiveParameters() }.getOrDefault(Parameters.Empty)
    return Parameters.build {
        appendAll(form)
        appendAll(request.queryParameters)
    }
}

/** "1"/"true"/"on" → true, "0"/"false"/"off" → false, anything else → null. */
internal fun parseBool(raw: String?): Boolean? = when (raw?.lowercase()) {
    "1", "true", "on" -> true
    "0", "false", "off" -> false
    else -> null
}

/** Responds 401 unless the `token` query parameter matches the contributor token. */
internal suspend fun ApplicationCall.requireToken(debug: ContributorDebugState): Boolean {
    if (debug.checkToken(request.queryParameters["token"])) return true
    respond(HttpStatusCode.Unauthorized, mapOf("error" to "token required"))
    return false
}

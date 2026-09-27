package cc.opencar.assistant.server

import cc.opencar.assistant.protocol.OaaHeaders
import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.server.ota.ArtifactStore
import cc.opencar.assistant.server.ota.Rollout
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Hub-delivered app updates: admins upload an APK, then roll it out to cars. */
internal fun Routing.otaRoutes(hub: HubContext) = with(hub) {
    post(OaaPaths.OTA_ARTIFACTS) {
        call.requireAdmin() ?: return@post
        val pkg = call.request.header(OaaHeaders.OTA_PACKAGE)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "${OaaHeaders.OTA_PACKAGE} required")
        val versionName = call.request.header(OaaHeaders.OTA_VERSION_NAME)?.trim()?.takeIf { it.isNotEmpty() }
        val versionCode = call.request.header(OaaHeaders.OTA_VERSION_CODE)?.trim()?.toLongOrNull()
        val artifact = try {
            val input = call.receiveChannel().toInputStream()
            withContext(Dispatchers.IO) { artifacts.put(input, pkg, versionName, versionCode) }
        } catch (e: ArtifactStore.TooLarge) {
            return@post call.respondError(HttpStatusCode.PayloadTooLarge, e.message ?: "too large")
        } catch (e: IllegalArgumentException) {
            return@post call.respondError(HttpStatusCode.BadRequest, e.message ?: "bad upload")
        }
        call.respond(mapOf("ok" to true, "artifact" to artifact))
    }

    get(OaaPaths.OTA_ARTIFACTS) {
        call.requireAdmin() ?: return@get
        call.respond(mapOf("artifacts" to artifacts.list()))
    }

    post(OaaPaths.OTA_ROLLOUTS) {
        call.requireAdmin() ?: return@post
        val obj = runCatching { JSONObject(call.receiveText()) }.getOrNull()
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid json")
        val artifact = artifacts.get(obj.optString("artifact"))
            ?: return@post call.respondError(HttpStatusCode.NotFound, "unknown artifact")
        val requested = obj.optJSONArray("nodes")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
        val nodeIds = when {
            obj.optBoolean("all") -> registry.all().map { it.id }
            requested != null -> requested
            else -> return@post call.respondError(HttpStatusCode.BadRequest, "nodes or all required")
        }
        val unknown = nodeIds.filter { registry.get(it) == null }
        if (nodeIds.isEmpty() || unknown.isNotEmpty()) {
            return@post call.respondError(HttpStatusCode.BadRequest, "unknown nodes: $unknown")
        }
        call.respond(mapOf("ok" to true, "rollout" to rolloutJson(rollouts.create(artifact, nodeIds))))
    }

    get(OaaPaths.OTA_ROLLOUTS) {
        call.requireAdmin() ?: return@get
        call.respond(mapOf("rollouts" to rollouts.list().map { rolloutJson(it) }))
    }

    get("${OaaPaths.OTA_ROLLOUTS}/{id}") {
        call.requireAdmin() ?: return@get
        val r = rollouts.get(call.parameters["id"].orEmpty())
            ?: return@get call.respondError(HttpStatusCode.NotFound, "unknown rollout")
        call.respond(rolloutJson(r))
    }
}

private fun rolloutJson(r: Rollout): Map<String, Any?> = mapOf(
    "id" to r.id,
    "sha256" to r.sha256,
    "createdAtMs" to r.createdAtMs,
    "done" to r.done,
    "targets" to r.targets,
)

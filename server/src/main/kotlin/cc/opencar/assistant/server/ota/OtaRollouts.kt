package cc.opencar.assistant.server.ota

import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaOta
import cc.opencar.assistant.protocol.OaaPaths
import cc.opencar.assistant.server.NodeRegistry
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class TargetState(
    var state: String = OaaOta.STATE_PENDING,
    var progress: Int? = null,
    var error: String? = null,
    var updatedAtMs: Long = System.currentTimeMillis(),
)

data class Rollout(
    val id: String,
    val sha256: String,
    val createdAtMs: Long,
    val targets: MutableMap<String, TargetState>,
) {
    val done: Boolean get() = targets.values.all { it.state in OaaOta.TERMINAL }
}

/**
 * Pushes an [Artifact] to cars: `ota_offer` to online targets now and to offline
 * ones when they reconnect, until their `hello` reports the artifact's hash.
 */
class OtaRollouts(
    dataDir: File,
    private val registry: NodeRegistry,
    private val artifacts: ArtifactStore,
    /** Path prefix cars download artifacts from (the public bridge's when one is published). */
    private val artifactsPath: () -> String = { OaaPaths.NODES_ARTIFACTS },
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val storeFile = File(dataDir, "ota.json")
    private val rollouts = LinkedHashMap<String, Rollout>()

    init {
        load()
    }

    suspend fun create(artifact: Artifact, nodeIds: Collection<String>): Rollout {
        val rollout = synchronized(this) {
            // A newer rollout supersedes earlier ones for the same cars.
            rollouts.values.forEach { r ->
                nodeIds.forEach { id ->
                    r.targets[id]?.takeIf { it.state !in OaaOta.TERMINAL }?.apply {
                        state = OaaOta.STATE_FAILED
                        error = "superseded"
                        updatedAtMs = System.currentTimeMillis()
                    }
                }
            }
            Rollout(
                id = UUID.randomUUID().toString(),
                sha256 = artifact.sha256,
                createdAtMs = System.currentTimeMillis(),
                targets = nodeIds.associateWith { TargetState() }.toMutableMap(),
            ).also {
                rollouts[it.id] = it
                trim()
                persist()
            }
        }
        nodeIds.forEach { offer(rollout, it) }
        return rollout
    }

    fun get(id: String): Rollout? = synchronized(this) { rollouts[id] }

    fun list(): List<Rollout> = synchronized(this) { rollouts.values.reversed() }

    /** Latest rollout state for [nodeId] (Fleet display). */
    fun stateFor(nodeId: String): Map<String, Any?>? = synchronized(this) {
        rollouts.values.reversed().firstNotNullOfOrNull { r ->
            r.targets[nodeId]?.let {
                mapOf(
                    "rolloutId" to r.id,
                    "sha256" to r.sha256,
                    "state" to it.state,
                    "progress" to it.progress,
                    "error" to it.error,
                    "updatedAtMs" to it.updatedAtMs,
                )
            }
        }
    }

    /** Car (re)connected: mark installed when running the target build, else re-offer. */
    suspend fun onHello(nodeId: String, apkSha256: String?) {
        val active = synchronized(this) {
            rollouts.values.filter { r -> r.targets[nodeId]?.state?.let { it !in OaaOta.TERMINAL } == true }
        }
        for (r in active) {
            if (apkSha256 != null && apkSha256.equals(r.sha256, ignoreCase = true)) {
                update(r, nodeId, OaaOta.STATE_INSTALLED, progress = 100, error = null)
            } else {
                offer(r, nodeId)
            }
        }
    }

    fun onStatus(nodeId: String, payload: JSONObject) {
        val r = get(payload.optString("rolloutId")) ?: return
        val state = payload.optString("state").takeIf { it.isNotEmpty() } ?: return
        // "installed" is only trusted from the hello hash after restart.
        if (state == OaaOta.STATE_INSTALLED) return
        update(
            r,
            nodeId,
            state,
            progress = if (payload.has("progress")) payload.optInt("progress") else null,
            error = payload.optString("error").takeIf { it.isNotEmpty() },
        )
    }

    private suspend fun offer(rollout: Rollout, nodeId: String) {
        val artifact = artifacts.get(rollout.sha256)
        if (artifact == null) {
            update(rollout, nodeId, OaaOta.STATE_FAILED, error = "artifact missing on hub")
            return
        }
        val node = registry.session(nodeId) ?: return
        val payload = OaaFrames.versioned()
            .put("rolloutId", rollout.id)
            .put("sha256", artifact.sha256)
            .put("size", artifact.size)
            .put("package", artifact.packageName)
            .put("versionName", artifact.versionName)
            .put("versionCode", artifact.versionCode)
            .put("path", "${artifactsPath()}/${artifact.sha256}")
        val installed = registry.get(nodeId)?.app?.apkSha256?.lowercase()
        val delta = installed?.let { withContext(Dispatchers.IO) { artifacts.delta(it, artifact.sha256) } }
        if (delta != null) {
            payload.put(
                "delta",
                JSONObject()
                    .put("from", delta.from)
                    .put("sha256", delta.sha256)
                    .put("size", delta.size)
                    .put("path", "${artifactsPath()}/${delta.sha256}"),
            )
        }
        if (node.send(OaaFrames.frame(OaaFrames.OTA_OFFER, payload))) {
            val current = synchronized(this) { rollout.targets[nodeId]?.state }
            if (current == OaaOta.STATE_PENDING) update(rollout, nodeId, OaaOta.STATE_OFFERED)
        }
    }

    private fun update(r: Rollout, nodeId: String, state: String, progress: Int? = null, error: String? = null) {
        synchronized(this) {
            val t = r.targets[nodeId] ?: return
            if (t.state in OaaOta.TERMINAL) return
            t.state = state
            t.progress = progress
            t.error = error
            t.updatedAtMs = System.currentTimeMillis()
            persist()
        }
    }

    private fun trim() {
        while (rollouts.size > MAX_ROLLOUTS) rollouts.remove(rollouts.keys.first())
    }

    private fun load() {
        if (!storeFile.exists()) return
        val type = object : TypeToken<List<Rollout>>() {}.type
        val list: List<Rollout> = runCatching { gson.fromJson<List<Rollout>>(storeFile.readText(), type) }.getOrNull() ?: return
        list.forEach { rollouts[it.id] = it }
    }

    private fun persist() {
        storeFile.parentFile?.mkdirs()
        storeFile.writeText(gson.toJson(rollouts.values.toList()))
    }

    private companion object {
        const val MAX_ROLLOUTS = 50
    }
}

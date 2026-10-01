package cc.opencar.assistant.feature.dvr

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-camera reference counts around [SharedH264Pipeline]. The recorder holds
 * every camera; a live viewer holds only the cameras it shows. A camera starts
 * on its first seat and stops when the last one is released.
 */
class SharedCameraHub(
    private val pipeline: SharedH264Pipeline,
    private val cameras: () -> List<SharedH264Pipeline.Camera>,
    /** Held while cameras open, so the startup probe never competes for them. */
    private val cameraLock: Any,
) {
    private val refs = ConcurrentHashMap<String, Int>()
    @Volatile private var lastError: String? = null

    fun roles(): List<String> = cameras().map { it.role }
    fun lastError(): String? = lastError ?: pipeline.lastError
    fun refCount(role: String): Int = refs[role] ?: 0
    fun refCounts(): Map<String, Int> = HashMap(refs)
    fun isRunning(role: String): Boolean = pipeline.isRunning(role)
    fun anyRunning(): Boolean = pipeline.runningRoles().isNotEmpty()

    /**
     * Take a seat on each of [roles] (unknown roles are ignored). Returns the roles
     * now streaming; seats on cameras that failed to start are not kept.
     */
    fun acquire(roles: Collection<String>): Set<String> {
        val byRole = cameras().associateBy { it.role }
        val ok = LinkedHashSet<String>()
        synchronized(cameraLock) {
            for (role in roles) {
                val cam = byRole[role] ?: continue
                if (pipeline.isRunning(role) || pipeline.start(cam)) {
                    refs.merge(role, 1, Int::plus)
                    ok += role
                } else {
                    lastError = pipeline.lastError
                    if ((refs[role] ?: 0) == 0) pipeline.stop(role)
                }
            }
        }
        return ok
    }

    fun release(roles: Collection<String>) {
        synchronized(cameraLock) {
            for (role in roles) {
                val left = refs.compute(role) { _, n -> if (n == null || n <= 1) null else n - 1 }
                if (left == null) pipeline.stop(role)
            }
        }
    }

    /** Restart cameras that died while still held (HAL error, disconnect). */
    fun reviveHeld(): List<String> {
        val byRole = cameras().associateBy { it.role }
        val revived = ArrayList<String>()
        synchronized(cameraLock) {
            for ((role, n) in refs) {
                if (n <= 0 || pipeline.isRunning(role)) continue
                val cam = byRole[role] ?: continue
                if (pipeline.start(cam)) revived += role else lastError = pipeline.lastError
            }
        }
        return revived
    }

    fun status(): Map<String, Any?> = pipeline.status() + mapOf(
        "refs" to refCounts(),
        "hubError" to lastError,
    )
}

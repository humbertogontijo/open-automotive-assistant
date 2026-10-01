package cc.opencar.assistant.feature.dvr

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-camera H.264 streams keyed by role. Each camera has its own Camera2 →
 * MediaCodec session and drain thread; there is no compositing or GL step.
 * Recording opens one MP4 per camera for a segment group.
 */
class SharedH264Pipeline(private val context: Context) {
    data class Camera(val role: String, val cameraId: String)

    private val sessions = ConcurrentHashMap<String, CameraEncoderSession>()
    private val taps = ConcurrentHashMap<String, MutableSet<CameraEncoderSession.SampleTap>>()
    @Volatile var lastError: String? = null
        private set

    fun session(role: String): CameraEncoderSession? = sessions[role]
    fun isRunning(role: String): Boolean = sessions[role]?.isRunning() == true
    fun runningRoles(): List<String> = sessions.values.filter { it.isRunning() }.map { it.role }

    /** Start [camera] if it is not already streaming. */
    @Synchronized
    fun start(camera: Camera): Boolean {
        val existing = sessions[camera.role]
        if (existing != null && existing.isRunning()) return true
        existing?.stop()
        val s = existing?.takeIf { it.cameraId == camera.cameraId }
            ?: CameraEncoderSession(context, camera.role, camera.cameraId)
        taps[camera.role]?.forEach(s::addTap)
        if (!s.start()) {
            lastError = s.lastError ?: "camera ${camera.role} failed"
            Log.w(TAG, "start ${camera.role}: $lastError")
            sessions[camera.role] = s
            return false
        }
        sessions[camera.role] = s
        lastError = null
        return true
    }

    @Synchronized
    fun stop(role: String) {
        sessions[role]?.stop()
    }

    @Synchronized
    fun stopAll() {
        sessions.values.forEach { it.stop() }
    }

    fun addTap(role: String, tap: CameraEncoderSession.SampleTap) {
        taps.getOrPut(role) { java.util.concurrent.CopyOnWriteArraySet() }.add(tap)
        sessions[role]?.addTap(tap)
    }

    fun removeTap(role: String, tap: CameraEncoderSession.SampleTap) {
        taps[role]?.remove(tap)
        sessions[role]?.removeTap(tap)
    }

    fun requestKeyFrame(role: String) {
        sessions[role]?.requestKeyFrame()
    }

    fun parameterSetsAnnexB(role: String): ByteArray? = sessions[role]?.parameterSetsAnnexB()

    fun fps(role: String): Int = sessions[role]?.fps() ?: CameraEncoderSession.DEFAULT_FPS

    /** Open `<dir>/<base>_<role>.mp4` for each running camera; returns the opened files by role. */
    fun openGroup(dir: File, base: String): Map<String, File> {
        val out = LinkedHashMap<String, File>()
        for (s in sessions.values) {
            if (!s.isRunning()) continue
            val file = File(dir, "${base}_${s.role}.mp4")
            if (s.openMp4(file)) out[s.role] = file
        }
        return out
    }

    /** Close every open recording file. */
    fun closeGroup(): List<CameraEncoderSession.RecordedFile> =
        sessions.values.mapNotNull { it.closeMp4() }

    fun groupBytes(): Long = sessions.values.sumOf { if (it.activeFile() != null) it.bytesWritten() else 0L }

    fun hasOpenFiles(): Boolean = sessions.values.any { it.activeFile() != null }

    fun status(): Map<String, Any?> = mapOf(
        "mode" to if (runningRoles().isEmpty()) "off" else "per-camera",
        "cameras" to sessions.values.sortedBy { it.role }.map { it.status() },
        "lastError" to lastError,
    )

    companion object {
        private const val TAG = "OaaH264Pipe"
    }
}

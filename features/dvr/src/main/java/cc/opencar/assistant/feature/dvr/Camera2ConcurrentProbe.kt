package cc.opencar.assistant.feature.dvr

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log

/**
 * On-device check that every camera can stream into its own hardware H.264
 * encoder at the same time. Opens all cameras through [CameraEncoderSession]
 * (Camera2 → encoder Surface, no GL), lets them run briefly and reports the
 * codec, size and measured fps per camera.
 *
 * When fewer hardware encoders exist than cameras, [CameraEncoderSession]
 * falls back to a smaller size and then to a software encoder; the report
 * shows which cameras ended up where.
 */
class Camera2ConcurrentProbe(private val context: Context) {
    data class CamResult(
        val role: String,
        val cameraId: String,
        val started: Boolean,
        val encoder: String? = null,
        val hardwareEncoder: Boolean = false,
        val size: String? = null,
        val fps: Int = 0,
        val measuredFps: Int = 0,
        val error: String? = null,
        val hardwareLevel: String? = null,
    )

    data class Report(
        val cameraIds: List<String>,
        val results: List<CamResult>,
        val allStarted: Boolean,
        val hardwareEncoders: Int,
        val logicalMultiCameras: List<String>,
        val notes: String,
    )

    fun probe(cameras: List<SharedH264Pipeline.Camera>, runMs: Long = RUN_MS): Report {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        val logical = ids.filter { id ->
            runCatching {
                val caps = cm.getCameraCharacteristics(id).get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                caps != null && CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
            }.getOrDefault(false)
        }
        if (cameras.isEmpty()) {
            return Report(ids, emptyList(), false, 0, logical, "No cameras")
        }
        val sessions = cameras.map { CameraEncoderSession(context, it.role, it.cameraId) }
        val started = BooleanArray(sessions.size)
        try {
            sessions.forEachIndexed { i, s -> started[i] = s.start() }
            if (started.any { it }) Thread.sleep(runMs)
            val results = sessions.mapIndexed { i, s ->
                CamResult(
                    role = s.role,
                    cameraId = s.cameraId,
                    started = started[i] && s.isRunning(),
                    encoder = s.codecName(),
                    hardwareEncoder = s.isHardwareEncoder(),
                    size = if (started[i]) "${s.width()}x${s.height()}" else null,
                    fps = s.fps(),
                    measuredFps = s.measuredFps(),
                    error = s.lastError,
                    hardwareLevel = hardwareLevel(cm, s.cameraId),
                )
            }
            val hw = results.count { it.started && it.hardwareEncoder }
            val notes = buildString {
                append("cameras=${results.size} started=${results.count { it.started }} hwEncoders=$hw")
                val sw = results.filter { it.started && !it.hardwareEncoder }.map { it.role }
                if (sw.isNotEmpty()) append(" softwareFallback=${sw.joinToString(",")}")
                if (logical.isNotEmpty()) append(" logical=${logical.joinToString(",")}")
            }
            Log.i(TAG, "encoder probe: $notes results=$results")
            return Report(
                cameraIds = ids,
                results = results,
                allStarted = results.all { it.started },
                hardwareEncoders = hw,
                logicalMultiCameras = logical,
                notes = notes,
            )
        } finally {
            sessions.forEach { runCatching { it.stop() } }
        }
    }

    fun statusMap(report: Report): Map<String, Any?> = mapOf(
        "cameraIds" to report.cameraIds,
        "allStarted" to report.allStarted,
        "hardwareEncoders" to report.hardwareEncoders,
        "logicalMultiCameras" to report.logicalMultiCameras,
        "notes" to report.notes,
        "results" to report.results.map {
            mapOf(
                "role" to it.role,
                "id" to it.cameraId,
                "started" to it.started,
                "encoder" to it.encoder,
                "hardwareEncoder" to it.hardwareEncoder,
                "size" to it.size,
                "fps" to it.fps,
                "measuredFps" to it.measuredFps,
                "error" to it.error,
                "hardwareLevel" to it.hardwareLevel,
            )
        },
    )

    private fun hardwareLevel(cm: CameraManager, id: String): String? = runCatching {
        when (cm.getCameraCharacteristics(id).get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level3"
            else -> "other"
        }
    }.getOrNull()

    companion object {
        private const val TAG = "OaaCam2Probe"
        private const val RUN_MS = 2_500L
    }
}

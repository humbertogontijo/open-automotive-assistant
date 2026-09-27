package cc.opencar.assistant.feature.dvr

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device Camera2 concurrent / Surface capability probe (Phase 2).
 * Opens cameras with dummy [SurfaceTexture] targets; does not replace the
 * product mosaic path by itself.
 */
class Camera2ConcurrentProbe(private val context: Context) {
    data class CamResult(
        val id: String,
        val opened: Boolean,
        val previewSize: String? = null,
        val error: String? = null,
        val hardwareLevel: String? = null,
        val isLogical: Boolean = false,
    )

    data class Report(
        val cameraIds: List<String>,
        val concurrentTried: List<String>,
        val results: List<CamResult>,
        val allOpened: Boolean,
        val logicalMultiCameras: List<String>,
        val notes: String,
    )

    fun probe(maxCams: Int = 4): Report {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = cm.cameraIdList.toList()
        val logical = mutableListOf<String>()
        for (id in ids) {
            runCatching {
                val chars = cm.getCameraCharacteristics(id)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                if (caps != null &&
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
                ) {
                    logical += id
                }
            }
        }
        val tryIds = ids.take(maxCams)
        if (tryIds.isEmpty()) {
            return Report(
                cameraIds = ids,
                concurrentTried = emptyList(),
                results = emptyList(),
                allOpened = false,
                logicalMultiCameras = logical,
                notes = "No camera ids",
            )
        }

        val thread = HandlerThread("oaa-cam2-probe").also { it.start() }
        val handler = Handler(thread.looper)
        val results = mutableListOf<CamResult>()
        val devices = mutableListOf<CameraDevice>()
        val textures = mutableListOf<SurfaceTexture>()
        val surfaces = mutableListOf<Surface>()

        try {
            for ((index, id) in tryIds.withIndex()) {
                val latch = CountDownLatch(1)
                val opened = AtomicReference<CameraDevice?>(null)
                val err = AtomicReference<String?>(null)
                var hwLevel: String? = null
                var preview: String? = null
                runCatching {
                    val chars = cm.getCameraCharacteristics(id)
                    hwLevel = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
                        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level3"
                        else -> "other"
                    }
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val size = choosePreview(map)
                    preview = size?.let { "${it.width}x${it.height}" }
                }
                try {
                    cm.openCamera(
                        id,
                        object : CameraDevice.StateCallback() {
                            override fun onOpened(camera: CameraDevice) {
                                opened.set(camera)
                                latch.countDown()
                            }

                            override fun onDisconnected(camera: CameraDevice) {
                                camera.close()
                                err.set("disconnected")
                                latch.countDown()
                            }

                            override fun onError(camera: CameraDevice, error: Int) {
                                camera.close()
                                err.set("error=$error")
                                latch.countDown()
                            }
                        },
                        handler,
                    )
                } catch (t: SecurityException) {
                    err.set("permission: ${t.message}")
                    latch.countDown()
                } catch (t: Throwable) {
                    err.set(t.message)
                    latch.countDown()
                }
                val ok = latch.await(3, TimeUnit.SECONDS)
                val cam = opened.get()
                if (!ok || cam == null) {
                    results += CamResult(
                        id = id,
                        opened = false,
                        previewSize = preview,
                        error = err.get() ?: "timeout",
                        hardwareLevel = hwLevel,
                        isLogical = id in logical,
                    )
                    // Stop trying further if a mid-list open failed under concurrent load.
                    if (index > 0) break
                    continue
                }
                devices += cam
                val st = SurfaceTexture(200 + index).also {
                    val wh = preview?.split("x")
                    val w = wh?.getOrNull(0)?.toIntOrNull() ?: 640
                    val h = wh?.getOrNull(1)?.toIntOrNull() ?: 360
                    it.setDefaultBufferSize(w, h)
                    textures += it
                }
                val surface = Surface(st).also { surfaces += it }
                try {
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    }.build()
                    val sessionLatch = CountDownLatch(1)
                    val sessionErr = AtomicReference<String?>(null)
                    cam.createCaptureSession(
                        listOf(surface),
                        object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                runCatching {
                                    session.setRepeatingRequest(req, null, handler)
                                }.onFailure { sessionErr.set(it.message) }
                                sessionLatch.countDown()
                            }

                            override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                sessionErr.set("configureFailed")
                                sessionLatch.countDown()
                            }
                        },
                        handler,
                    )
                    sessionLatch.await(3, TimeUnit.SECONDS)
                    results += CamResult(
                        id = id,
                        opened = sessionErr.get() == null,
                        previewSize = preview,
                        error = sessionErr.get(),
                        hardwareLevel = hwLevel,
                        isLogical = id in logical,
                    )
                    if (sessionErr.get() != null) break
                } catch (t: Throwable) {
                    results += CamResult(
                        id = id,
                        opened = false,
                        previewSize = preview,
                        error = t.message,
                        hardwareLevel = hwLevel,
                        isLogical = id in logical,
                    )
                    break
                }
            }
        } finally {
            devices.forEach { runCatching { it.close() } }
            surfaces.forEach { runCatching { it.release() } }
            textures.forEach { runCatching { it.release() } }
            thread.quitSafely()
        }

        val allOpened = results.isNotEmpty() && results.all { it.opened }
        val notes = buildString {
            append("tried=${tryIds.size} opened=${results.count { it.opened }}")
            if (logical.isNotEmpty()) append(" logical=${logical.joinToString(",")}")
        }
        Log.i(TAG, "Camera2 probe: $notes results=$results")
        return Report(
            cameraIds = ids,
            concurrentTried = tryIds,
            results = results,
            allOpened = allOpened,
            logicalMultiCameras = logical,
            notes = notes,
        )
    }

    fun statusMap(report: Report): Map<String, Any?> = mapOf(
        "cameraIds" to report.cameraIds,
        "concurrentTried" to report.concurrentTried,
        "allOpened" to report.allOpened,
        "logicalMultiCameras" to report.logicalMultiCameras,
        "notes" to report.notes,
        "results" to report.results.map {
            mapOf(
                "id" to it.id,
                "opened" to it.opened,
                "previewSize" to it.previewSize,
                "error" to it.error,
                "hardwareLevel" to it.hardwareLevel,
                "isLogical" to it.isLogical,
            )
        },
    )

    private fun choosePreview(map: StreamConfigurationMap?): Size? {
        if (map == null) return Size(640, 360)
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return Size(640, 360)
        return sizes
            .filter { it.width <= 1280 && it.height <= 720 }
            .minByOrNull { kotlin.math.abs(it.width * it.height - 640 * 360) }
            ?: sizes.minByOrNull { it.width * it.height }
    }

    companion object {
        private const val TAG = "OaaCam2Probe"
    }
}

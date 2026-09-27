package cc.opencar.assistant.feature.dvr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.camera2.CameraManager
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera1 preview capture for the GPU mosaic path.
 *
 * Opens cameras concurrently onto [SurfaceTexture]s that [SharedH264Pipeline]
 * later rebinds to GLES OES textures. No NV21 / JPEG path.
 */
class CameraPreviewSession(private val context: Context) : AutoCloseable {
    private data class Slot(
        val id: String,
        val index: Int,
        var camera: Camera? = null,
        var surfaceTexture: SurfaceTexture? = null,
        var width: Int = 0,
        var height: Int = 0,
        var fps: Int = 0,
    )

    private val slots = ConcurrentHashMap<String, Slot>()
    private val running = AtomicBoolean(false)
    @Volatile private var mode: String = MODE_IDLE
    var lastError: String? = null
        private set

    fun isRunning(): Boolean = running.get()
    fun captureMode(): String = mode
    fun openCameraIds(): List<String> = slots.keys.toList()
    fun surfaceTexture(cameraId: String): SurfaceTexture? = slots[cameraId]?.surfaceTexture

    fun previewSize(cameraId: String): Pair<Int, Int>? {
        val slot = slots[cameraId] ?: return null
        if (slot.width <= 0 || slot.height <= 0) return null
        return slot.width to slot.height
    }

    fun previewSizes(orderedIds: List<String>): List<Pair<Int, Int>> =
        orderedIds.map { id -> previewSize(id) ?: (640 to 480) }

    /** Max preview fps across open cameras (from Camera1 fps ranges). */
    fun previewFps(orderedIds: List<String>): Int {
        var maxFps = 0
        for (id in orderedIds) {
            val slot = slots[id] ?: continue
            maxFps = maxOf(maxFps, slot.fps)
        }
        return if (maxFps > 0) maxFps else MosaicPreviewSession.DEFAULT_FPS
    }

    /**
     * Open every id concurrently. Returns false if any open fails
     * (all hardware released on failure).
     */
    @Synchronized
    fun startAll(ids: List<String>): Boolean {
        if (ids.isEmpty()) {
            lastError = "No cameras"
            return false
        }
        if (running.get() && mode == MODE_CONCURRENT && slots.keys.toSet() == ids.toSet()) {
            return true
        }
        stopHardware()
        val pmState = context.checkSelfPermission(Manifest.permission.CAMERA)
        Log.i(TAG, "CAMERA checkSelfPermission=$pmState (granted=${PackageManager.PERMISSION_GRANTED})")
        for (id in ids) {
            if (!openSlot(id)) {
                val err = lastError ?: "open failed"
                Log.w(TAG, "concurrent open failed at cam=$id ($err); releasing")
                stopHardware()
                lastError = "Concurrent open failed at $id: $err"
                mode = MODE_IDLE
                return false
            }
        }
        running.set(true)
        mode = MODE_CONCURRENT
        lastError = null
        Log.i(TAG, "concurrent cameras opened=${ids.size} ids=$ids")
        return true
    }

    @Synchronized
    fun stop() = stopHardware()

    /**
     * Swap each open camera's preview [SurfaceTexture] for ones already bound to
     * GL OES texture ids (created on the GL thread).
     */
    @Synchronized
    fun rebindPreviewTextures(orderedIds: List<String>, glTextures: List<SurfaceTexture>): Boolean {
        if (glTextures.isEmpty() || orderedIds.isEmpty()) return false
        return try {
            for ((i, id) in orderedIds.withIndex()) {
                if (i >= glTextures.size) break
                val slot = slots[id] ?: continue
                val cam = slot.camera ?: continue
                val st = glTextures[i]
                runCatching { cam.stopPreview() }
                val old = slot.surfaceTexture
                st.setDefaultBufferSize(slot.width.coerceAtLeast(1), slot.height.coerceAtLeast(1))
                cam.setPreviewTexture(st)
                slot.surfaceTexture = st
                runCatching { old?.release() }
                cam.startPreview()
            }
            Log.i(TAG, "rebound ${glTextures.size} previews to GL OES textures")
            true
        } catch (t: Throwable) {
            lastError = t.message
            Log.e(TAG, "rebindPreviewTextures failed", t)
            false
        }
    }

    fun status(): Map<String, Any?> = mapOf(
        "previewRunning" to running.get(),
        "captureMode" to mode,
        "openCameras" to slots.keys.toList(),
        "previews" to slots.map { (id, s) ->
            mapOf("id" to id, "width" to s.width, "height" to s.height, "fps" to s.fps)
        },
        "lastError" to lastError,
    )

    override fun close() = stopHardware()

    private fun openSlot(id: String): Boolean {
        val camIndex = resolveCamera1Index(id)
        return try {
            @Suppress("DEPRECATION")
            val cam = Camera.open(camIndex)
            val params = cam.parameters
            val (pw, ph) = choosePreviewSize(params.supportedPreviewSizes)
            params.setPreviewSize(pw, ph)
            val fps = choosePreviewFps(params)
            runCatching { cam.parameters = params }
            val st = SurfaceTexture(/* singleBuffered = */ false)
            st.setDefaultBufferSize(pw, ph)
            cam.setPreviewTexture(st)
            slots[id] = Slot(id, camIndex, cam, st, pw, ph, fps)
            cam.startPreview()
            Log.i(TAG, "Camera1 open cam=$id idx=$camIndex ${pw}x${ph}@${fps}fps")
            true
        } catch (t: Throwable) {
            lastError = t.message
            Log.e(TAG, "Camera1 open failed cam=$id idx=$camIndex", t)
            false
        }
    }

    private fun stopHardware() {
        running.set(false)
        mode = MODE_IDLE
        for ((_, slot) in slots) {
            runCatching {
                slot.camera?.stopPreview()
                slot.camera?.release()
            }
            runCatching { slot.surfaceTexture?.release() }
            slot.camera = null
            slot.surfaceTexture = null
        }
        slots.clear()
    }

    /** Prefer the largest supported preview size (native, no artificial downscale). */
    @Suppress("DEPRECATION")
    private fun choosePreviewSize(sizes: List<Camera.Size>?): Pair<Int, Int> {
        if (sizes.isNullOrEmpty()) return 640 to 480
        val best = sizes.maxByOrNull { it.width.toLong() * it.height } ?: sizes.first()
        return best.width to best.height
    }

    @Suppress("DEPRECATION")
    private fun choosePreviewFps(params: Camera.Parameters): Int {
        val ranges = params.supportedPreviewFpsRange
        if (!ranges.isNullOrEmpty()) {
            val best = ranges.maxByOrNull { it[Camera.Parameters.PREVIEW_FPS_MAX_INDEX] }
            if (best != null) {
                params.setPreviewFpsRange(
                    best[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                    best[Camera.Parameters.PREVIEW_FPS_MAX_INDEX],
                )
                // Camera1 fps ranges are in thousandths.
                return (best[Camera.Parameters.PREVIEW_FPS_MAX_INDEX] / 1000)
                    .coerceIn(MosaicPreviewSession.MIN_FPS, MosaicPreviewSession.MAX_FPS)
            }
        }
        return MosaicPreviewSession.DEFAULT_FPS
    }

    @Suppress("DEPRECATION")
    private fun resolveCamera1Index(id: String): Int {
        val n = Camera.getNumberOfCameras().coerceAtLeast(1)
        id.toIntOrNull()?.takeIf { it in 0 until n }?.let { return it }
        val listed = runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList
        }.getOrNull()
        val byList = listed?.indexOf(id)?.takeIf { it >= 0 }
        if (byList != null && byList < n) return byList
        return 0
    }

    companion object {
        private const val TAG = "OaaCamPreview"
        private const val MODE_IDLE = "idle"
        private const val MODE_CONCURRENT = "concurrent"
    }
}

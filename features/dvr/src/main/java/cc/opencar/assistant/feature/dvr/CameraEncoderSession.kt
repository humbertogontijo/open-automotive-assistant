package cc.opencar.assistant.feature.dvr

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaMuxer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * One camera straight into its own H.264 encoder: Camera2 renders into the
 * encoder's input Surface, so frames never touch the CPU or GL. Encoded
 * AccessUnits go to the DVR muxer (when a file is open) and to live taps.
 */
class CameraEncoderSession(
    private val context: Context,
    val role: String,
    val cameraId: String,
) {
    fun interface SampleTap {
        fun onSample(unit: H264Encoder.AccessUnit)
    }

    /** A closed recording file and the wall-clock span of the samples written to it. */
    data class RecordedFile(
        val file: File,
        val firstCaptureUtcMs: Long,
        val lastCaptureUtcMs: Long,
        val durationMs: Long,
        val bytes: Long,
    )

    private val running = AtomicBoolean(false)
    private val taps = CopyOnWriteArraySet<SampleTap>()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var capture: CameraCaptureSession? = null
    private var encoder: H264Encoder? = null
    private var drainThread: Thread? = null

    @Volatile private var width = 0
    @Volatile private var height = 0
    @Volatile private var fps = DEFAULT_FPS
    @Volatile private var wallOffsetUs = Long.MIN_VALUE
    private var realtimeTimestamps = false

    private val frames = AtomicLong(0)
    private val windowStartNs = AtomicLong(0)
    @Volatile private var measuredFps = 0
    @Volatile var lastError: String? = null
        private set

    private val muxerLock = Any()
    private var muxer: MediaMuxer? = null
    private var muxerTrack = -1
    private var muxerStarted = false
    private var mp4: File? = null
    private var firstPtsUs = -1L
    private var lastPtsUs = -1L
    private var firstWallMs = 0L
    private var lastWallMs = 0L
    private val bytesWritten = AtomicLong(0)

    fun isRunning(): Boolean = running.get()
    fun width(): Int = width
    fun height(): Int = height
    fun fps(): Int = fps
    fun measuredFps(): Int = measuredFps
    fun codecName(): String? = encoder?.codecName()
    fun isHardwareEncoder(): Boolean = encoder?.isHardware() == true
    fun parameterSetsAnnexB(): ByteArray? = encoder?.parameterSetsAnnexB()
    fun requestKeyFrame() = encoder?.requestKeyFrame()
    fun addTap(tap: SampleTap) = taps.add(tap)
    fun removeTap(tap: SampleTap) = taps.remove(tap)
    fun activeFile(): File? = synchronized(muxerLock) { mp4 }
    fun bytesWritten(): Long = bytesWritten.get()

    /** Opens the camera and encoder; false (and everything released) on failure. */
    @Synchronized
    fun start(): Boolean {
        if (running.get()) return true
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = runCatching { cm.getCameraCharacteristics(cameraId) }.getOrElse {
            lastError = "characteristics: ${it.message}"
            return false
        }
        realtimeTimestamps = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val fpsRange = chooseFpsRange(chars)
        fps = fpsRange?.upper?.coerceIn(MIN_FPS, MAX_FPS) ?: DEFAULT_FPS

        val enc = startEncoder(candidateSizes(chars)) ?: return false
        encoder = enc
        val surface = enc.inputSurface() ?: run {
            lastError = "encoder has no input surface"
            stopInternal()
            return false
        }
        val ht = HandlerThread("oaa-cam-$role").also { it.start() }
        thread = ht
        val h = Handler(ht.looper)
        handler = h

        val cam = openCamera(cm, h) ?: run {
            stopInternal()
            return false
        }
        device = cam
        val session = createSession(cam, surface, h) ?: run {
            stopInternal()
            return false
        }
        capture = session
        try {
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                if (fpsRange != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            }.build()
            session.setRepeatingRequest(req, null, h)
        } catch (t: Throwable) {
            lastError = "repeating request: ${t.message}"
            stopInternal()
            return false
        }

        wallOffsetUs = Long.MIN_VALUE
        enc.wallClockOf = ::wallClockMs
        enc.listener = object : H264Encoder.Listener {
            override fun onAccessUnit(unit: H264Encoder.AccessUnit) = onUnit(unit)
            override fun onError(message: String) {
                lastError = message
            }
        }
        frames.set(0)
        windowStartNs.set(System.nanoTime())
        running.set(true)
        drainThread = Thread({ drainLoop(enc) }, "oaa-enc-$role").apply {
            isDaemon = true
            start()
        }
        lastError = null
        Log.i(TAG, "camera $role (id=$cameraId) ${width}x$height@$fps encoder=${enc.codecName()} hw=${enc.isHardware()}")
        return true
    }

    @Synchronized
    fun stop() = stopInternal()

    private fun startEncoder(sizes: List<Size>): H264Encoder? {
        for (size in sizes) {
            val enc = H264Encoder(size.width, size.height, bitrateFor(size), fps)
            if (enc.start()) {
                width = size.width
                height = size.height
                return enc
            }
            lastError = "encoder ${size.width}x${size.height}: ${enc.lastError}"
            Log.w(TAG, "camera $role: $lastError")
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(cm: CameraManager, h: Handler): CameraDevice? {
        val latch = CountDownLatch(1)
        val opened = AtomicReference<CameraDevice?>(null)
        val err = AtomicReference<String?>(null)
        try {
            cm.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opened.set(camera)
                        latch.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        err.set("disconnected")
                        onCameraLost("disconnected")
                        latch.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        err.set("error=$error")
                        onCameraLost("error=$error")
                        latch.countDown()
                    }
                },
                h,
            )
        } catch (t: Throwable) {
            err.set(t.message)
            latch.countDown()
        }
        if (!latch.await(OPEN_TIMEOUT_SEC, TimeUnit.SECONDS)) err.compareAndSet(null, "open timeout")
        val cam = opened.get()
        if (cam == null) lastError = "open camera $cameraId: ${err.get() ?: "failed"}"
        return cam
    }

    private fun createSession(cam: CameraDevice, surface: android.view.Surface, h: Handler): CameraCaptureSession? {
        val latch = CountDownLatch(1)
        val out = AtomicReference<CameraCaptureSession?>(null)
        try {
            @Suppress("DEPRECATION")
            cam.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        out.set(session)
                        latch.countDown()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        latch.countDown()
                    }
                },
                h,
            )
        } catch (t: Throwable) {
            lastError = "capture session: ${t.message}"
            return null
        }
        latch.await(OPEN_TIMEOUT_SEC, TimeUnit.SECONDS)
        return out.get().also { if (it == null) lastError = lastError ?: "capture session configure failed" }
    }

    private fun onCameraLost(reason: String) {
        if (!running.get()) return
        lastError = "camera $cameraId $reason"
        Log.w(TAG, "camera $role lost: $reason")
        running.set(false)
    }

    private fun drainLoop(enc: H264Encoder) {
        while (running.get()) {
            try {
                enc.drain(timeoutUs = DRAIN_TIMEOUT_US)
            } catch (t: Throwable) {
                lastError = t.message
                Log.w(TAG, "drain $role: ${t.message}")
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /**
     * Camera buffer timestamps are on the sensor clock (boot or monotonic);
     * anchor them to the wall clock once and re-anchor if they drift apart.
     */
    private fun wallClockMs(ptsUs: Long): Long {
        val nowMs = System.currentTimeMillis()
        if (wallOffsetUs == Long.MIN_VALUE) {
            val clockUs = if (realtimeTimestamps) SystemClock.elapsedRealtimeNanos() / 1000L else System.nanoTime() / 1000L
            wallOffsetUs = nowMs * 1000L - clockUs
        }
        var wall = (ptsUs + wallOffsetUs) / 1000L
        if (kotlin.math.abs(wall - nowMs) > MAX_CLOCK_SKEW_MS) {
            wallOffsetUs = nowMs * 1000L - ptsUs
            wall = nowMs
        }
        return wall
    }

    private fun onUnit(unit: H264Encoder.AccessUnit) {
        if (unit.isConfig) return
        countFrame()
        synchronized(muxerLock) { writeMuxerLocked(unit) }
        for (tap in taps) {
            runCatching { tap.onSample(unit) }.onFailure { Log.w(TAG, "tap $role: ${it.message}") }
        }
    }

    private fun countFrame() {
        val total = frames.incrementAndGet()
        val elapsed = System.nanoTime() - windowStartNs.get()
        if (elapsed >= 1_000_000_000L) {
            measuredFps = ((total * 1_000_000_000L) / elapsed).toInt().coerceIn(0, 120)
            frames.set(0)
            windowStartNs.set(System.nanoTime())
        }
    }

    // --- recording -----------------------------------------------------------

    fun openMp4(file: File): Boolean = synchronized(muxerLock) {
        closeMuxerLocked()
        try {
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mp4 = file
            muxerTrack = -1
            muxerStarted = false
            firstPtsUs = -1L
            lastPtsUs = -1L
            firstWallMs = 0L
            lastWallMs = 0L
            bytesWritten.set(0)
            true
        } catch (t: Throwable) {
            lastError = "mp4 open: ${t.message}"
            Log.e(TAG, "openMp4 $role failed", t)
            false
        }
    }.also { if (it) requestKeyFrame() }

    /** Finalize the open file; null when nothing was open. */
    fun closeMp4(): RecordedFile? = synchronized(muxerLock) { closeMuxerLocked() }

    private fun writeMuxerLocked(unit: H264Encoder.AccessUnit) {
        val m = muxer ?: return
        // Each file must open on a keyframe to be playable from its start.
        if (!muxerStarted && !unit.isKeyFrame) return
        try {
            if (!muxerStarted) {
                val format = encoder?.outputFormat() ?: return
                muxerTrack = m.addTrack(format)
                m.start()
                muxerStarted = true
                firstPtsUs = unit.ptsUs
                firstWallMs = unit.captureUtcMs
            }
            if (unit.ptsUs <= lastPtsUs) return
            val info = MediaCodec.BufferInfo()
            info.offset = 0
            info.size = unit.data.size
            info.presentationTimeUs = unit.ptsUs
            info.flags = if (unit.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            m.writeSampleData(muxerTrack, ByteBuffer.wrap(unit.data), info)
            lastPtsUs = unit.ptsUs
            lastWallMs = unit.captureUtcMs
            bytesWritten.addAndGet(unit.data.size.toLong())
        } catch (t: Throwable) {
            lastError = "muxer: ${t.message}"
            Log.w(TAG, "muxer $role: ${t.message}")
        }
    }

    private fun closeMuxerLocked(): RecordedFile? {
        val file = mp4 ?: return null
        val started = muxerStarted
        runCatching {
            if (started) muxer?.stop()
        }.onFailure { Log.w(TAG, "muxer stop $role: ${it.message}") }
        runCatching { muxer?.release() }
        muxer = null
        mp4 = null
        muxerTrack = -1
        muxerStarted = false
        if (!started) {
            runCatching { file.delete() }
            return null
        }
        val frameMs = 1000L / fps.coerceAtLeast(1)
        val durationMs = ((lastPtsUs - firstPtsUs) / 1000L + frameMs).coerceAtLeast(frameMs)
        return RecordedFile(file, firstWallMs, lastWallMs + frameMs, durationMs, bytesWritten.get())
    }

    private fun stopInternal() {
        val wasRunning = running.getAndSet(false)
        runCatching { capture?.stopRepeating() }
        runCatching { capture?.close() }
        capture = null
        runCatching { device?.close() }
        device = null
        drainThread?.let { t ->
            runCatching { t.join(1_000) }
        }
        drainThread = null
        synchronized(muxerLock) { closeMuxerLocked() }
        encoder?.listener = null
        encoder?.stop()
        encoder = null
        thread?.quitSafely()
        thread = null
        handler = null
        measuredFps = 0
        if (wasRunning) Log.i(TAG, "camera $role stopped")
    }

    fun status(): Map<String, Any?> = mapOf(
        "role" to role,
        "cameraId" to cameraId,
        "running" to running.get(),
        "width" to width,
        "height" to height,
        "size" to "${width}x$height",
        "fps" to fps,
        "measuredFps" to measuredFps,
        "encoder" to encoder?.codecName(),
        "hardware" to (encoder?.isHardware() == true),
        "activeMp4" to activeFile()?.name,
        "bytesWritten" to bytesWritten.get(),
        "lastError" to lastError,
    )

    private fun candidateSizes(chars: CameraCharacteristics): List<Size> {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = runCatching { map?.getOutputSizes(MediaCodec::class.java) }.getOrNull()
            ?.filter { it.width % 2 == 0 && it.height % 2 == 0 }
            .orEmpty()
        val fitting = sizes
            .filter { it.width <= MAX_WIDTH && it.height <= MAX_HEIGHT }
            .sortedByDescending { it.width.toLong() * it.height }
            .distinct()
        val out = fitting.take(MAX_SIZE_ATTEMPTS).ifEmpty {
            listOfNotNull(sizes.minByOrNull { it.width.toLong() * it.height })
        }
        return out.ifEmpty { listOf(Size(640, 480)) }
    }

    private fun chooseFpsRange(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        val usable = ranges.filter { it.upper in MIN_FPS..MAX_FPS }
        val best = usable.maxOfOrNull { it.upper } ?: return null
        return usable.filter { it.upper == best }.maxByOrNull { it.lower }
    }

    companion object {
        private const val TAG = "OaaCamEnc"
        const val DEFAULT_FPS = 15
        const val MIN_FPS = 5
        const val MAX_FPS = 30
        private const val MAX_WIDTH = 1280
        private const val MAX_HEIGHT = 720
        private const val MAX_SIZE_ATTEMPTS = 3
        private const val OPEN_TIMEOUT_SEC = 4L
        private const val DRAIN_TIMEOUT_US = 20_000L
        private const val MAX_CLOCK_SKEW_MS = 5_000L

        /** ~1.2 Mbps at 640x480, scaled by pixel count. */
        fun bitrateFor(size: Size): Int {
            val area = size.width.toLong() * size.height
            return (area * H264Encoder.DEFAULT_BITRATE / (640L * 480L)).toInt().coerceIn(600_000, 3_000_000)
        }
    }
}

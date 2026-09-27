package cc.opencar.assistant.feature.dvr

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared H.264 pipeline: camera OES → GLES mosaic → HW [MediaCodec] Surface.
 * Live: minimal fMP4 / HLS. DVR: [MediaMuxer] MP4.
 */
class SharedH264Pipeline(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
) {
    fun interface SampleTap {
        fun onSample(unit: MosaicH264Encoder.AccessUnit)
    }

    /** Extra consumers of encoded AccessUnits (e.g. WebRTC pass-through). Config units are not delivered. */
    private val taps = CopyOnWriteArraySet<SampleTap>()
    private val encoder = MosaicH264Encoder(width, height, fps = fps)
    private val fmp4 = Fmp4LiveMuxer(width, height).also { it.setFps(fps) }
    private val gl = MosaicGlComposer(width, height)
    private val running = AtomicBoolean(false)

    private val muxerLock = Any()
    private var dvrMuxer: MediaMuxer? = null
    private var dvrTrack = -1
    private var dvrStarted = false
    private var activeMp4: File? = null
    private val bytesWritten = AtomicLong(0)
    private var liveParamsSet = false

    private var glJob: Future<*>? = null
    private val glExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "oaa-h264-gl").apply { isDaemon = true }
    }
    @Volatile private var cameraTextures: List<SurfaceTexture> = emptyList()
    @Volatile private var measuredFps: Int = fps
    private val frameCount = AtomicLong(0)
    private val measureWindowStartNs = AtomicLong(0)
    var lastError: String? = null
        private set

    fun isRunning(): Boolean = running.get()
    fun measuredFps(): Int = measuredFps
    fun fmp4Init(): ByteArray? = fmp4.initSegment().also { touchLive() }
    fun fmp4Fragment(seq: Long): ByteArray? = fmp4.fragment(seq).also { touchLive() }
    fun hlsPlaylist(): String? = fmp4.hlsPlaylist().also { touchLive() }
    fun hlsPlaylistBlocking(msn: Long?, timeoutMs: Long = 3_000L): String? {
        touchLive()
        return fmp4.hlsPlaylistBlocking(msn, timeoutMs)
    }

    /** fMP4 muxing only runs while an HLS client has read within [LIVE_IDLE_MS]. */
    @Volatile private var liveReadAtMs = 0L
    private var liveIdle = true

    private fun touchLive() {
        val now = SystemClock.elapsedRealtime()
        if (now - liveReadAtMs > LIVE_IDLE_MS) encoder.requestKeyFrame()
        liveReadAtMs = now
    }
    fun activeFile(): File? = activeMp4
    fun bytesWritten(): Long = bytesWritten.get()
    fun videoWidth(): Int = width
    fun videoHeight(): Int = height
    fun fps(): Int = fps
    fun requestKeyFrame() = encoder.requestKeyFrame()
    fun addTap(tap: SampleTap) = taps.add(tap)
    fun removeTap(tap: SampleTap) = taps.remove(tap)

    /** SPS + PPS as Annex-B (start-code prefixed), or null before the codec reports them. */
    fun parameterSetsAnnexB(): ByteArray? {
        val s0 = encoder.csd0()
        val s1 = encoder.csd1()
        if (s0 != null && s1 != null) return AnnexB.withStartCode(s0) + AnnexB.withStartCode(s1)
        val merged = encoder.spsPps() ?: return null
        return if (AnnexB.hasStartCode(merged)) merged else null
    }

    fun start(
        tileCount: Int,
        rebind: (List<SurfaceTexture>) -> Boolean,
    ): Boolean {
        if (running.get()) return true
        if (tileCount <= 0) {
            lastError = "no cameras"
            return false
        }
        if (!encoder.start()) {
            lastError = encoder.lastError ?: "encoder failed"
            return false
        }
        val surface = encoder.inputSurface()
        if (surface == null || !gl.init(surface)) {
            lastError = gl.lastError ?: "GL init failed"
            encoder.stop()
            return false
        }
        encoder.listener = object : MosaicH264Encoder.Listener {
            override fun onAccessUnit(unit: MosaicH264Encoder.AccessUnit) {
                writeSamples(unit)
            }

            override fun onError(message: String) {
                lastError = message
            }
        }
        running.set(true)

        val intervalMs = (1000L / fps.coerceIn(1, 30)).coerceAtLeast(33L)
        val attached = AtomicBoolean(false)
        val attachError = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        frameCount.set(0)
        measureWindowStartNs.set(System.nanoTime())
        glJob = glExec.submit {
            var frames = 0
            try {
                val textures = gl.createBoundTextures(tileCount)
                if (!rebind(textures)) {
                    attachError.set("camera rebind failed")
                    textures.forEach { runCatching { it.release() } }
                    latch.countDown()
                    return@submit
                }
                cameraTextures = textures
                attached.set(true)
                latch.countDown()
                Thread.sleep(120)
                while (running.get()) {
                    if (gl.drawFrame(textures, tileCount)) {
                        encoder.onGlFramePresented()
                        encoder.drain(timeoutUs = 20_000L)
                        frames++
                        val total = frameCount.incrementAndGet()
                        val started = measureWindowStartNs.get()
                        val elapsedNs = System.nanoTime() - started
                        if (elapsedNs >= 1_000_000_000L) {
                            measuredFps = ((total * 1_000_000_000L) / elapsedNs).toInt()
                                .coerceIn(1, 60)
                            frameCount.set(0)
                            measureWindowStartNs.set(System.nanoTime())
                        }
                        if (frames == 1 || frames == 15) {
                            Log.i(TAG, "GL frame=$frames hasInit=${fmp4.initSegment() != null}")
                        }
                    } else {
                        lastError = gl.lastError
                        Log.w(TAG, "drawFrame failed: $lastError")
                    }
                    Thread.sleep(intervalMs)
                }
            } catch (_: InterruptedException) {
                latch.countDown()
            } catch (t: Throwable) {
                attachError.set(t.message)
                lastError = t.message
                Log.e(TAG, "GL loop failed", t)
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        if (!attached.get()) {
            lastError = attachError.get() ?: gl.lastError ?: "GL bind failed"
            Log.e(TAG, "start failed: $lastError")
            stop()
            return false
        }
        lastError = null
        Log.i(TAG, "H264 GL pipeline active ${width}x${height}@${fps} encoder=${encoder.codecName()}")
        return true
    }

    fun openMp4(file: File): Boolean {
        synchronized(muxerLock) {
            closeDvrLocked()
            return try {
                dvrMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                activeMp4 = file
                dvrTrack = -1
                dvrStarted = false
                bytesWritten.set(0)
                true
            } catch (t: Throwable) {
                lastError = t.message
                Log.e(TAG, "openMp4 failed", t)
                false
            }
        }
    }

    fun closeMp4(): File? = synchronized(muxerLock) { closeDvrLocked() }

    private fun closeDvrLocked(): File? {
        val file = activeMp4
        runCatching {
            if (dvrStarted) dvrMuxer?.stop()
            dvrMuxer?.release()
        }
        dvrMuxer = null
        dvrTrack = -1
        dvrStarted = false
        activeMp4 = null
        return file
    }

    private fun writeSamples(unit: MosaicH264Encoder.AccessUnit) {
        ensureLiveParams()
        if (unit.isConfig) return
        synchronized(muxerLock) {
            writeDvrLocked(unit)
            if (SystemClock.elapsedRealtime() - liveReadAtMs < LIVE_IDLE_MS) {
                if (liveIdle) {
                    liveIdle = false
                    fmp4.resetMedia()
                }
                fmp4.onSample(unit.data, unit.isKeyFrame)
            } else {
                liveIdle = true
            }
        }
        for (tap in taps) {
            runCatching { tap.onSample(unit) }.onFailure { Log.w(TAG, "tap: ${it.message}") }
        }
    }

    private fun ensureLiveParams() {
        if (liveParamsSet) return
        val s0 = encoder.csd0()
        val s1 = encoder.csd1()
        if (s0 != null && s1 != null) {
            fmp4.setParameterSets(s0, s1)
            liveParamsSet = fmp4.initSegment() != null
        }
    }

    private fun writeDvrLocked(unit: MosaicH264Encoder.AccessUnit) {
        val m = dvrMuxer ?: return
        if (encoder.csd0() == null && encoder.spsPps() == null) return
        // Each file must open on a keyframe to be playable from its start.
        if (!dvrStarted && !unit.isKeyFrame) return
        try {
            if (!dvrStarted) {
                dvrTrack = m.addTrack(videoFormat())
                m.start()
                dvrStarted = true
            }
            val info = MediaCodec.BufferInfo()
            info.offset = 0
            info.size = unit.data.size
            info.presentationTimeUs = unit.ptsUs
            info.flags = if (unit.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            m.writeSampleData(dvrTrack, ByteBuffer.wrap(unit.data), info)
            bytesWritten.addAndGet(unit.data.size.toLong())
        } catch (t: Throwable) {
            lastError = t.message
            Log.w(TAG, "dvr muxer: ${t.message}")
        }
    }

    private fun videoFormat(): MediaFormat {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        val s0 = encoder.csd0()
        val s1 = encoder.csd1()
        if (s0 != null) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(s0))
            if (s1 != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(s1))
        } else {
            val csd = encoder.spsPps()
            if (csd != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        }
        return format
    }

    fun stop() {
        running.set(false)
        glJob?.cancel(true)
        try {
            glExec.submit {
                cameraTextures = emptyList()
                gl.release()
            }.get(1, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            runCatching { gl.release() }
            cameraTextures = emptyList()
        }
        glJob = null
        synchronized(muxerLock) { closeDvrLocked() }
        encoder.listener = null
        encoder.stop()
        fmp4.clear()
        liveParamsSet = false
    }

    fun status(): Map<String, Any?> = mapOf(
        "running" to running.get(),
        "mode" to if (running.get()) "h264-gl" else "off",
        "gpu" to running.get(),
        "width" to width,
        "height" to height,
        "fps" to fps,
        "measuredFps" to measuredFps,
        "size" to "${width}x${height}",
        "encoder" to encoder.codecName(),
        "hasInit" to (fmp4.initSegment() != null),
        "activeMp4" to activeMp4?.name,
        "bytesWritten" to bytesWritten.get(),
        "lastError" to (lastError ?: encoder.lastError ?: gl.lastError),
    )

    companion object {
        private const val TAG = "OaaH264Pipe"
        private const val LIVE_IDLE_MS = 15_000L
    }
}

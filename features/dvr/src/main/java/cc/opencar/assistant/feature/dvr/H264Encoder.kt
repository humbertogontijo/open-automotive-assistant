package cc.opencar.assistant.feature.dvr

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * H.264 encoder with Surface input (a camera stream or an EGL surface).
 * Tries hardware codecs first, then software ones that accept Surface input.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int = DEFAULT_BITRATE,
    private val fps: Int = 15,
    private val keyFrameIntervalSec: Int = 2,
) {
    data class AccessUnit(
        val data: ByteArray,
        /** Encoder presentation time (source clock). */
        val ptsUs: Long,
        val isKeyFrame: Boolean,
        val isConfig: Boolean,
        /** Wall-clock capture time; 0 for config units. */
        val captureUtcMs: Long = 0L,
    )

    interface Listener {
        fun onAccessUnit(unit: AccessUnit)
        fun onError(message: String)
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val running = AtomicBoolean(false)
    private val spsPps = AtomicReference<ByteArray?>(null)
    private val csd0 = AtomicReference<ByteArray?>(null)
    private val csd1 = AtomicReference<ByteArray?>(null)
    @Volatile private var selectedCodec: String? = null
    @Volatile private var hardware: Boolean = false
    @Volatile private var lastKeyPtsUs = -1L
    var lastError: String? = null
        private set

    @Volatile var listener: Listener? = null

    /** Maps an output presentation time (µs) to wall-clock ms. */
    @Volatile var wallClockOf: (Long) -> Long = { System.currentTimeMillis() }

    fun spsPps(): ByteArray? = spsPps.get()
    fun csd0(): ByteArray? = csd0.get()
    fun csd1(): ByteArray? = csd1.get()
    fun inputSurface(): Surface? = inputSurface
    fun isRunning(): Boolean = running.get()
    fun codecName(): String? = selectedCodec
    fun isHardware(): Boolean = hardware
    fun width(): Int = width
    fun height(): Int = height
    fun fps(): Int = fps

    /** SPS + PPS as Annex-B (start-code prefixed), or null before the codec reports them. */
    fun parameterSetsAnnexB(): ByteArray? {
        val s0 = csd0()
        val s1 = csd1()
        if (s0 != null && s1 != null) return AnnexB.withStartCode(s0) + AnnexB.withStartCode(s1)
        val merged = spsPps() ?: return null
        return if (AnnexB.hasStartCode(merged)) merged else null
    }

    /** Track format for [android.media.MediaMuxer], or null before the codec config is known. */
    fun outputFormat(): MediaFormat? {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        val s0 = csd0()
        val s1 = csd1()
        when {
            s0 != null -> {
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(s0))
                if (s1 != null) format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(s1))
            }
            spsPps() != null -> format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(spsPps()!!))
            else -> return null
        }
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(1, 60))
        return format
    }

    fun start(): Boolean {
        if (running.get()) return true
        val format = buildFormat()
        var lastFail: Throwable? = null
        for ((name, hw) in encoderCandidates(format)) {
            var c: MediaCodec? = null
            try {
                c = if (name != null) {
                    MediaCodec.createByCodecName(name)
                } else {
                    MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                }
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = c.createInputSurface()
                c.start()
                codec = c
                inputSurface = surface
                selectedCodec = c.name
                hardware = hw ?: runCatching { c.codecInfo.isHardwareAccelerated }.getOrDefault(false)
                lastKeyPtsUs = -1L
                running.set(true)
                lastError = null
                Log.i(TAG, "H264 encoder ${c.name} hw=$hardware ${width}x$height @${fps}fps ${bitrate / 1000}kbps")
                return true
            } catch (t: Throwable) {
                lastFail = t
                Log.w(TAG, "encoder candidate ${name ?: "byType"} failed: ${t.message}")
                runCatching { c?.release() }
            }
        }
        lastError = lastFail?.message ?: "encoder failed"
        Log.e(TAG, "H264 encoder start failed ${width}x$height", lastFail)
        stop()
        return false
    }

    /** Ask the codec for an IDR on the next frame (recording rotation, viewer join / PLI). */
    fun requestKeyFrame() {
        val b = Bundle()
        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        runCatching { codec?.setParameters(b) }
    }

    /** Signal end of stream for Surface input (offline transcodes). */
    fun signalEndOfStream() {
        runCatching { codec?.signalEndOfInputStream() }
    }

    /**
     * Pull every ready output buffer. Returns true once the end-of-stream buffer
     * has been seen.
     */
    fun drain(timeoutUs: Long = 0L): Boolean {
        val c = codec ?: return false
        val info = MediaCodec.BufferInfo()
        var spins = 0
        while (spins++ < 16) {
            val outIndex = try {
                c.dequeueOutputBuffer(info, if (spins == 1) timeoutUs else 0L)
            } catch (t: Throwable) {
                lastError = t.message
                listener?.onError(t.message ?: "dequeue failed")
                return false
            }
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = c.outputFormat
                    val s0 = copyCsd(fmt, "csd-0")
                    val s1 = copyCsd(fmt, "csd-1")
                    if (s0 != null) csd0.set(s0)
                    if (s1 != null) csd1.set(s1)
                    val merged = mergeCsd(fmt)
                    if (merged != null) {
                        spsPps.set(merged)
                        listener?.onAccessUnit(AccessUnit(merged, 0L, isKeyFrame = true, isConfig = true))
                    }
                }
                outIndex >= 0 -> {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val buf = c.getOutputBuffer(outIndex)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        if (isConfig) {
                            spsPps.set(bytes)
                            listener?.onAccessUnit(AccessUnit(bytes, 0L, isKeyFrame = true, isConfig = true))
                        } else {
                            val pts = info.presentationTimeUs
                            enforceGop(pts, isKey)
                            listener?.onAccessUnit(
                                AccessUnit(
                                    data = bytes,
                                    ptsUs = pts,
                                    isKeyFrame = isKey,
                                    isConfig = false,
                                    captureUtcMs = wallClockOf(pts),
                                ),
                            )
                        }
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (eos) return true
                }
                else -> return false
            }
        }
        return false
    }

    /** Some encoders ignore KEY_I_FRAME_INTERVAL with Surface input; nudge them. */
    private fun enforceGop(ptsUs: Long, isKey: Boolean) {
        if (isKey) {
            lastKeyPtsUs = ptsUs
            return
        }
        val last = lastKeyPtsUs
        val limitUs = keyFrameIntervalSec * 1_500_000L
        if (last < 0 || ptsUs - last > limitUs) {
            lastKeyPtsUs = ptsUs
            requestKeyFrame()
        }
    }

    private fun copyCsd(fmt: MediaFormat, key: String): ByteArray? {
        val buf = fmt.getByteBuffer(key) ?: return null
        val dup = buf.asReadOnlyBuffer()
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return out
    }

    private fun mergeCsd(fmt: MediaFormat): ByteArray? {
        val sps = copyCsd(fmt, "csd-0") ?: return null
        val pps = copyCsd(fmt, "csd-1")
        if (pps == null) return sps
        return if (AnnexB.hasStartCode(sps) || AnnexB.hasStartCode(pps)) sps + pps else AnnexB.toAvcc(listOf(sps, pps))
    }

    fun stop() {
        running.set(false)
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        codec = null
        inputSurface = null
        selectedCodec = null
        hardware = false
        spsPps.set(null)
        csd0.set(null)
        csd1.set(null)
    }

    private fun buildFormat(): MediaFormat {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(1, 30))
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)
        runCatching {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }
        return format
    }

    /** (codec name or null for createEncoderByType, hardware flag when known). */
    private fun encoderCandidates(format: MediaFormat): List<Pair<String?, Boolean?>> {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val hw = mutableListOf<String>()
        val sw = mutableListOf<String>()
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) continue
            val sizeOk = runCatching { caps.videoCapabilities.isSizeSupported(width, height) }.getOrDefault(true)
            if (!sizeOk) continue
            if (info.isHardwareAccelerated) hw.add(info.name) else sw.add(info.name)
        }
        val ordered = LinkedHashMap<String?, Boolean?>()
        hw.forEach { ordered[it] = true }
        list.findEncoderForFormat(format)?.let { if (it !in ordered) ordered[it] = null }
        sw.forEach { if (it !in ordered) ordered[it] = false }
        ordered[null] = null
        return ordered.entries.map { it.key to it.value }
    }

    companion object {
        private const val TAG = "OaaH264Enc"
        const val DEFAULT_BITRATE = 1_200_000
    }
}

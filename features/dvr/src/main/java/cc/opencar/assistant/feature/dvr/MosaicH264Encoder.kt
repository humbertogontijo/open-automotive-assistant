package cc.opencar.assistant.feature.dvr

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Hardware H.264 encoder with Surface input (fed by GLES).
 * Prefers a hardware codec; fails if none can be configured.
 */
class MosaicH264Encoder(
    private val width: Int,
    private val height: Int,
    private val bitrate: Int = 2_500_000,
    private val fps: Int = 5,
    private val keyFrameIntervalSec: Int = 2,
) {
    data class AccessUnit(
        val data: ByteArray,
        val ptsUs: Long,
        val isKeyFrame: Boolean,
        val isConfig: Boolean,
    )

    interface Listener {
        fun onAccessUnit(unit: AccessUnit)
        fun onError(message: String)
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val running = AtomicBoolean(false)
    private val frameIndex = AtomicLong(0)
    private val spsPps = AtomicReference<ByteArray?>(null)
    private val csd0 = AtomicReference<ByteArray?>(null)
    private val csd1 = AtomicReference<ByteArray?>(null)
    @Volatile private var selectedCodec: String? = null
    var lastError: String? = null
        private set

    @Volatile var listener: Listener? = null

    fun spsPps(): ByteArray? = spsPps.get()
    fun csd0(): ByteArray? = csd0.get()
    fun csd1(): ByteArray? = csd1.get()
    fun inputSurface(): Surface? = inputSurface
    fun isRunning(): Boolean = running.get()
    fun codecName(): String? = selectedCodec

    fun start(): Boolean {
        if (running.get()) return true
        val format = buildFormat()
        val candidates = encoderCandidates(format)
        var lastFail: Throwable? = null
        for (name in candidates) {
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
                running.set(true)
                lastError = null
                Log.i(TAG, "H264 encoder ${c.name} ${width}x${height} @${fps}fps")
                return true
            } catch (t: Throwable) {
                lastFail = t
                Log.w(TAG, "encoder candidate ${name ?: "byType"} failed: ${t.message}")
                runCatching { c?.release() }
            }
        }
        lastError = lastFail?.message ?: "encoder failed"
        Log.e(TAG, "H264 encoder start failed", lastFail)
        stop()
        return false
    }

    fun onGlFramePresented() {
        requestKeyFrameIfNeeded()
    }

    /** Ask the codec for an IDR on the next frame (remote viewer join / PLI). */
    fun requestKeyFrame() {
        val b = Bundle()
        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        runCatching { codec?.setParameters(b) }
    }

    fun drain(timeoutUs: Long = 0L) {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        var spins = 0
        while (spins++ < 8) {
            val outIndex = try {
                c.dequeueOutputBuffer(info, if (spins == 1) timeoutUs else 0L)
            } catch (t: Throwable) {
                lastError = t.message
                break
            }
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val fmt = c.outputFormat
                    Log.i(TAG, "encoder output format: $fmt")
                    val s0 = copyCsd(fmt, "csd-0")
                    val s1 = copyCsd(fmt, "csd-1")
                    if (s0 != null) csd0.set(s0)
                    if (s1 != null) csd1.set(s1)
                    val merged = mergeCsd(fmt)
                    if (merged != null) {
                        spsPps.set(merged)
                        Log.i(TAG, "codec config from format csd0=${s0?.size} csd1=${s1?.size}")
                        listener?.onAccessUnit(
                            AccessUnit(
                                data = merged,
                                ptsUs = 0L,
                                isKeyFrame = true,
                                isConfig = true,
                            ),
                        )
                    }
                }
                outIndex >= 0 -> {
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
                            Log.i(TAG, "codec config ${bytes.size}B")
                        }
                        val pts = if (info.presentationTimeUs > 0) {
                            info.presentationTimeUs
                        } else {
                            frameIndex.get() * (1_000_000L / fps.coerceAtLeast(1))
                        }
                        listener?.onAccessUnit(
                            AccessUnit(
                                data = bytes,
                                ptsUs = pts,
                                isKeyFrame = isKey || isConfig,
                                isConfig = isConfig,
                            ),
                        )
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
                else -> break
            }
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

    private fun requestKeyFrameIfNeeded() {
        val n = frameIndex.incrementAndGet()
        val interval = (fps * keyFrameIntervalSec).coerceAtLeast(1).toLong()
        if (n == 1L || n % interval == 0L) requestKeyFrame()
    }

    fun stop() {
        running.set(false)
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        codec = null
        inputSurface = null
        frameIndex.set(0)
        selectedCodec = null
        spsPps.set(null)
        csd0.set(null)
        csd1.set(null)
    }

    private fun buildFormat(): MediaFormat {
        val w = (width + 15) / 16 * 16
        val h = (height + 15) / 16 * 16
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(1, 30))
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSec)
        runCatching {
            format.setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            )
            format.setInteger(
                MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel31,
            )
        }
        return format
    }

    private fun encoderCandidates(format: MediaFormat): List<String?> {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val hw = mutableListOf<String>()
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) {
                continue
            }
            if (info.isHardwareAccelerated) hw.add(info.name)
        }
        val byFormat = list.findEncoderForFormat(format)
        val ordered = LinkedHashSet<String?>()
        hw.forEach { ordered.add(it) }
        if (byFormat != null) ordered.add(byFormat)
        ordered.add(null)
        return ordered.toList()
    }

    companion object {
        private const val TAG = "OaaH264Enc"
    }
}

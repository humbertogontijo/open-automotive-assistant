package cc.opencar.assistant.feature.web.webrtc

import cc.opencar.assistant.feature.dvr.AnnexB
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.feature.dvr.MosaicH264Encoder
import cc.opencar.assistant.feature.dvr.SharedH264Pipeline
import org.webrtc.CapturerObserver
import org.webrtc.EncodedImage
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame
import java.nio.ByteBuffer

/**
 * Feeds mosaic AccessUnits into libwebrtc without re-encoding.
 *
 * libwebrtc only calls an encoder when a raw frame enters the track, so every
 * AccessUnit pushes a tiny placeholder frame; [PassThroughH264Encoder.encode]
 * then emits the queued AccessUnit stamped with that frame's capture time.
 */
class PassThroughH264Source(private val dvr: DvrController) : SharedH264Pipeline.SampleTap {
    private val queue = ArrayDeque<MosaicH264Encoder.AccessUnit>()
    private var waitingForKey = true
    @Volatile var observer: CapturerObserver? = null
    @Volatile var paused: Boolean = false
        set(value) {
            field = value
            if (!value) requestKeyFrame()
        }

    private var placeholder: JavaI420Buffer? = null

    override fun onSample(unit: MosaicH264Encoder.AccessUnit) {
        if (paused) return
        val obs = observer ?: return
        val (w, h) = dvr.liveVideoSize() ?: return
        synchronized(queue) {
            queue.addLast(unit)
            while (queue.size > MAX_QUEUE) {
                queue.removeFirst()
                waitingForKey = true
            }
        }
        val frame = VideoFrame(placeholderFor(w, h), 0, System.nanoTime())
        try {
            obs.onFrameCaptured(frame)
        } finally {
            frame.release()
        }
    }

    /** Next AccessUnit to send; keeps the reference chain intact (drops to next IDR on backlog). */
    fun take(): MosaicH264Encoder.AccessUnit? {
        var needKey = false
        val out = synchronized(queue) {
            if (queue.size > MAX_BACKLOG) {
                val lastKey = queue.indexOfLast { it.isKeyFrame }
                if (lastKey >= 0) {
                    repeat(lastKey) { queue.removeFirst() }
                } else {
                    queue.clear()
                    waitingForKey = true
                    needKey = true
                }
            }
            var picked: MosaicH264Encoder.AccessUnit? = null
            while (queue.isNotEmpty()) {
                val au = queue.removeFirst()
                if (waitingForKey && !au.isKeyFrame) continue
                waitingForKey = false
                picked = au
                break
            }
            if (picked == null && waitingForKey) needKey = true
            picked
        }
        if (needKey) requestKeyFrame()
        return out
    }

    /** Annex-B payload; IDRs get SPS/PPS prepended so late joiners can decode. */
    fun annexB(unit: MosaicH264Encoder.AccessUnit): ByteArray {
        if (!unit.isKeyFrame || AnnexB.containsNalType(unit.data, AnnexB.NAL_SPS)) return unit.data
        val ps = dvr.liveParameterSets() ?: return unit.data
        return ps + unit.data
    }

    fun videoSize(): Pair<Int, Int>? = dvr.liveVideoSize()

    fun requestKeyFrame() = dvr.requestLiveKeyFrame()

    fun reset() {
        synchronized(queue) {
            queue.clear()
            waitingForKey = true
        }
    }

    private fun placeholderFor(w: Int, h: Int): VideoFrame.Buffer {
        val cur = placeholder
        val buf = if (cur != null && cur.width == w && cur.height == h) {
            cur
        } else {
            cur?.release()
            JavaI420Buffer.allocate(w, h).also { placeholder = it }
        }
        buf.retain()
        return buf
    }

    fun dispose() {
        observer = null
        reset()
        placeholder?.release()
        placeholder = null
    }

    companion object {
        private const val MAX_QUEUE = 30
        private const val MAX_BACKLOG = 2
    }
}

class PassThroughH264Encoder(private val source: PassThroughH264Source) : VideoEncoder {
    private var callback: VideoEncoder.Callback? = null

    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        this.callback = callback
        source.reset()
        source.requestKeyFrame()
        return VideoCodecStatus.OK
    }

    override fun release(): VideoCodecStatus {
        callback = null
        return VideoCodecStatus.OK
    }

    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
        val cb = callback ?: return VideoCodecStatus.UNINITIALIZED
        if (info.frameTypes.any { it == EncodedImage.FrameType.VideoFrameKey }) source.requestKeyFrame()
        val unit = source.take() ?: return VideoCodecStatus.OK
        val payload = source.annexB(unit)
        val buffer = ByteBuffer.allocateDirect(payload.size)
        buffer.put(payload)
        buffer.rewind()
        val (w, h) = source.videoSize() ?: (frame.buffer.width to frame.buffer.height)
        val image = EncodedImage.builder()
            .setBuffer(buffer) {}
            .setEncodedWidth(w)
            .setEncodedHeight(h)
            .setCaptureTimeNs(frame.timestampNs)
            .setFrameType(if (unit.isKeyFrame) EncodedImage.FrameType.VideoFrameKey else EncodedImage.FrameType.VideoFrameDelta)
            .setRotation(0)
            .createEncodedImage()
        cb.onEncodedFrame(image, VideoEncoder.CodecSpecificInfo())
        return VideoCodecStatus.OK
    }

    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, framerate: Int): VideoCodecStatus =
        VideoCodecStatus.OK

    override fun getScalingSettings(): VideoEncoder.ScalingSettings = VideoEncoder.ScalingSettings.OFF

    override fun getImplementationName(): String = "OaaPassThroughH264"

    override fun isHardwareEncoder(): Boolean = true
}

/** Advertises only H.264 constrained baseline — the mosaic encoder's profile. */
class PassThroughEncoderFactory : VideoEncoderFactory {
    @Volatile var source: PassThroughH264Source? = null

    private val h264 = VideoCodecInfo(
        "H264",
        mapOf(
            VideoCodecInfo.H264_FMTP_PROFILE_LEVEL_ID to VideoCodecInfo.H264_CONSTRAINED_BASELINE_3_1,
            VideoCodecInfo.H264_FMTP_LEVEL_ASYMMETRY_ALLOWED to "1",
            VideoCodecInfo.H264_FMTP_PACKETIZATION_MODE to "1",
        ),
        emptyList(),
    )

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        if (!info.name.equals("H264", ignoreCase = true)) return null
        val src = source ?: return null
        return PassThroughH264Encoder(src)
    }

    override fun getSupportedCodecs(): Array<VideoCodecInfo> = arrayOf(h264)
}

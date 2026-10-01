package cc.opencar.assistant.feature.dvr

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.util.Size
import java.io.File
import java.nio.ByteBuffer
import java.util.TimeZone

/**
 * Export one camera's wall-clock range as a single MP4 with the capture time
 * burned into the picture: hardware decode → [TimestampGlRenderer] → hardware
 * encode. Recordings carry no burned-in time; this runs only on export.
 *
 * Blocking; run it on a worker thread. The calling thread owns a GL context
 * for the duration of the call.
 */
object TimestampBurnTranscoder {
    private const val TAG = "OaaBurnCut"
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val EOS_DRAIN_MS = 5_000L

    /** Media range inside [src] (ms from its first sample); [startUtcMs] is that sample's capture time. */
    data class Range(
        val src: File,
        val fromMs: Long,
        val toMs: Long,
        val startUtcMs: Long,
    )

    fun interface Progress {
        fun onProgress(doneMs: Long, totalMs: Long)
    }

    class Cancelled : RuntimeException("cut cancelled")

    /** Returns the output duration in ms. */
    fun transcode(
        ranges: List<Range>,
        dst: File,
        timeZone: TimeZone = TimeZone.getDefault(),
        progress: Progress? = null,
        isCancelled: () -> Boolean = { false },
    ): Long {
        require(ranges.isNotEmpty()) { "no ranges" }
        val probe = trackFormat(ranges.first().src)
        val width = probe.getInteger(MediaFormat.KEY_WIDTH)
        val height = probe.getInteger(MediaFormat.KEY_HEIGHT)
        val fps = runCatching { probe.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull()?.takeIf { it in 1..60 }
            ?: CameraEncoderSession.DEFAULT_FPS
        val frameUs = 1_000_000L / fps
        val totalMs = ranges.sumOf { (it.toMs - it.fromMs).coerceAtLeast(0L) }.coerceAtLeast(1L)

        val bitrate = (CameraEncoderSession.bitrateFor(Size(width, height)) * 3 / 2).coerceAtMost(4_000_000)
        val encoder = H264Encoder(width, height, bitrate, fps, keyFrameIntervalSec = 1)
        check(encoder.start()) { "export encoder: ${encoder.lastError}" }
        encoder.wallClockOf = { 0L }
        val sink = MuxSink(dst, encoder)
        encoder.listener = sink
        val gl = TimestampGlRenderer(width, height, timeZone)
        var doneMs = 0L
        var outOffsetUs = 0L
        var lastOutUs = -1L
        try {
            gl.init(checkNotNull(encoder.inputSurface()) { "encoder surface" })
            val decodeTarget = checkNotNull(gl.decoderSurface()) { "decoder surface" }
            for (range in ranges) {
                var rangeFirstUs = -1L
                decodeRange(range, decodeTarget, isCancelled) { srcUs, baseUs, render ->
                    if (!render) return@decodeRange
                    if (!gl.awaitFrame()) {
                        Log.w(TAG, "frame timeout at ${srcUs}us in ${range.src.name}")
                        return@decodeRange
                    }
                    if (rangeFirstUs < 0) rangeFirstUs = srcUs
                    var outUs = outOffsetUs + (srcUs - rangeFirstUs)
                    if (outUs <= lastOutUs) outUs = lastOutUs + 1
                    lastOutUs = outUs
                    val wallMs = range.startUtcMs + (srcUs - baseUs) / 1000L
                    gl.drawFrame(wallMs, outUs * 1000L)
                    encoder.drain(0L)
                    val rangeDone = ((srcUs - baseUs) / 1000L - range.fromMs).coerceIn(0L, range.toMs - range.fromMs)
                    progress?.onProgress((doneMs + rangeDone).coerceAtMost(totalMs), totalMs)
                }
                doneMs += (range.toMs - range.fromMs).coerceAtLeast(0L)
                if (lastOutUs >= 0) outOffsetUs = lastOutUs + frameUs
            }
            encoder.signalEndOfStream()
            val deadline = System.currentTimeMillis() + EOS_DRAIN_MS
            while (System.currentTimeMillis() < deadline) {
                if (encoder.drain(CODEC_TIMEOUT_US)) break
            }
            progress?.onProgress(totalMs, totalMs)
            check(sink.samples > 0) { "no frames in range" }
            return ((sink.lastPtsUs - sink.firstPtsUs) / 1000L + frameUs / 1000L).coerceAtLeast(1L)
        } finally {
            encoder.listener = null
            sink.close()
            gl.release()
            encoder.stop()
        }
    }

    private fun trackFormat(file: File): MediaFormat {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            val i = videoTrack(ex) ?: error("no video track in ${file.name}")
            return ex.getTrackFormat(i)
        } finally {
            runCatching { ex.release() }
        }
    }

    private fun videoTrack(ex: MediaExtractor): Int? = (0 until ex.trackCount).firstOrNull { i ->
        ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
    }

    /**
     * Decode [range] into [target]. [onFrame] gets each output frame's source time, the file's
     * first-sample time and whether it falls inside the range (only those are rendered).
     */
    private fun decodeRange(
        range: Range,
        target: android.view.Surface,
        isCancelled: () -> Boolean,
        onFrame: (srcUs: Long, baseUs: Long, render: Boolean) -> Unit,
    ) {
        val ex = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            ex.setDataSource(range.src.absolutePath)
            val track = videoTrack(ex) ?: error("no video track in ${range.src.name}")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            ex.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val baseUs = ex.sampleTime.coerceAtLeast(0L)
            val fromUs = baseUs + range.fromMs * 1000L
            val toUs = baseUs + range.toMs * 1000L
            ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(format, target, null, 0)
            dec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (isCancelled()) throw Cancelled()
                if (!inputDone) {
                    val inIdx = dec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf: ByteBuffer = dec.getInputBuffer(inIdx) ?: error("decoder input")
                        val size = ex.readSampleData(buf, 0)
                        val pts = ex.sampleTime
                        if (size < 0 || pts < 0 || pts > toUs) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, size, pts, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (outIdx >= 0) {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val inRange = info.size > 0 && info.presentationTimeUs in fromUs..toUs
                    dec.releaseOutputBuffer(outIdx, inRange)
                    onFrame(info.presentationTimeUs, baseUs, inRange)
                    if (eos) break
                }
            }
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { ex.release() }
        }
    }

    /** Writes encoder output to an MP4 once the codec config is known, starting on a keyframe. */
    private class MuxSink(dst: File, private val encoder: H264Encoder) : H264Encoder.Listener {
        private val muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var track = -1
        var samples = 0
            private set
        var firstPtsUs = 0L
            private set
        var lastPtsUs = 0L
            private set

        override fun onAccessUnit(unit: H264Encoder.AccessUnit) {
            if (unit.isConfig) return
            if (track < 0) {
                if (!unit.isKeyFrame) return
                track = muxer.addTrack(encoder.outputFormat() ?: return)
                muxer.start()
                firstPtsUs = unit.ptsUs
            }
            val info = MediaCodec.BufferInfo()
            info.set(0, unit.data.size, unit.ptsUs, if (unit.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(track, ByteBuffer.wrap(unit.data), info)
            lastPtsUs = unit.ptsUs
            samples++
        }

        override fun onError(message: String) {
            Log.w(TAG, "export encoder: $message")
        }

        fun close() {
            runCatching { if (track >= 0) muxer.stop() }.onFailure { Log.w(TAG, "muxer stop: ${it.message}") }
            runCatching { muxer.release() }
        }
    }
}

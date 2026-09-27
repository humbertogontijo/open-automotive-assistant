package cc.opencar.assistant.feature.dvr

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Keyframe-aligned MP4 remux without re-encoding.
 * [fromMs]/[toMs] are media-relative within each source file.
 */
object Mp4ClipRemuxer {
    private const val TAG = "OaaMp4Cut"

    data class Range(
        val src: File,
        val fromMs: Long,
        val toMs: Long,
    )

    fun remux(src: File, dst: File, fromMs: Long, toMs: Long): Long =
        remuxRanges(listOf(Range(src, fromMs, toMs)), dst)

    /** Concatenate one or more media-relative ranges into [dst]; continuous PTS. */
    fun remuxRanges(ranges: List<Range>, dst: File): Long {
        require(ranges.isNotEmpty()) { "no ranges" }
        var muxer: MediaMuxer? = null
        var outTrack = -1
        var ptsOffsetUs = 0L
        var lastOutPtsUs = 0L
        var wrote = 0
        try {
            for (range in ranges) {
                val fromUs = range.fromMs.coerceAtLeast(0L) * 1000L
                val toUs = range.toMs.coerceAtLeast(range.fromMs + 1L) * 1000L
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(range.src.absolutePath)
                    val videoTrack = (0 until extractor.trackCount).firstOrNull { i ->
                        val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                        mime.startsWith("video/")
                    } ?: error("no video track in ${range.src.name}")
                    extractor.selectTrack(videoTrack)
                    extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    val startPts = extractor.sampleTime.coerceAtLeast(0L)
                    val format = extractor.getTrackFormat(videoTrack)
                    if (muxer == null) {
                        muxer = MediaMuxer(
                            dst.absolutePath,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                        )
                        outTrack = muxer.addTrack(format)
                        muxer.start()
                    }
                    val maxSize = runCatching {
                        format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    }.getOrNull()?.takeIf { it > 0 } ?: (2 * 1024 * 1024)
                    val buffer = ByteBuffer.allocateDirect(maxSize.coerceAtLeast(256 * 1024))
                    val info = MediaCodec.BufferInfo()
                    var rangeLast = startPts
                    while (true) {
                        val pts = extractor.sampleTime
                        if (pts < 0) break
                        if (pts > toUs) break
                        if (pts < startPts) {
                            if (!extractor.advance()) break
                            continue
                        }
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        info.offset = 0
                        info.size = size
                        info.presentationTimeUs = ptsOffsetUs + (pts - startPts)
                        info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        }
                        muxer!!.writeSampleData(outTrack, buffer, info)
                        rangeLast = pts
                        lastOutPtsUs = info.presentationTimeUs
                        wrote++
                        if (!extractor.advance()) break
                    }
                    val rangeDurUs = (rangeLast - startPts).coerceAtLeast(0L)
                    ptsOffsetUs += rangeDurUs + 1_000L // 1ms gap between segments
                } finally {
                    runCatching { extractor.release() }
                }
            }
            check(wrote > 0) { "no samples in range" }
            return (lastOutPtsUs / 1000L).coerceAtLeast(1L)
        } finally {
            runCatching {
                muxer?.stop()
                muxer?.release()
            }.onFailure { Log.w(TAG, "muxer release: ${it.message}") }
        }
    }
}

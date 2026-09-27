package cc.opencar.assistant.feature.dvr

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Re-wraps a progressive DVR MP4 as fragmented MP4 (init + moof/mdat) for MSE,
 * without re-encoding. Media time 0 of the output is the sync sample at or
 * before the requested offset ([Info.startMs] in source-file time).
 */
class Fmp4FileStreamer(private val file: File) : Closeable {
    data class Info(
        val width: Int,
        val height: Int,
        /** RFC 6381 codec string, e.g. `avc1.42c01f`. */
        val codec: String,
        val durationMs: Long,
        /** Source-file time of the first emitted sample (sync point ≤ requested offset). */
        val startMs: Long,
        val init: ByteArray,
    )

    data class Fragment(val data: ByteArray, val endMs: Long)

    private val extractor = MediaExtractor()
    private var muxer: Fmp4LiveMuxer? = null
    private var buffer: ByteBuffer = ByteBuffer.allocateDirect(512 * 1024)
    private var lastSeq = 0L
    private var held: ByteArray? = null
    private var heldKey = false
    private var heldPtsUs = 0L
    private var startUs = 0L
    private var frameUs = 200_000L
    private var eof = false

    fun open(offsetMs: Long): Info {
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/avc") == true
        } ?: error("no H.264 track in ${file.name}")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val sps = format.getByteBuffer("csd-0")?.let { bytesOf(it) } ?: error("missing SPS")
        val pps = format.getByteBuffer("csd-1")?.let { bytesOf(it) } ?: error("missing PPS")
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
        runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE) }.getOrNull()
            ?.takeIf { it in 1..60 }?.let { frameUs = 1_000_000L / it }
        runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrNull()
            ?.takeIf { it > buffer.capacity() }?.let { buffer = ByteBuffer.allocateDirect(it) }

        val m = Fmp4LiveMuxer(width, height, timescale = 1000)
        m.setParameterSets(sps, pps)
        val init = m.initSegment() ?: error("invalid SPS/PPS")
        muxer = m

        extractor.seekTo(offsetMs.coerceAtLeast(0L) * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        startUs = extractor.sampleTime.coerceAtLeast(0L)
        return Info(width, height, codecString(sps), durationUs / 1000L, startUs / 1000L, init)
    }

    /** Next fMP4 fragment (~1 s or one GOP), or null at end of file. */
    fun nextFragment(): Fragment? {
        val m = muxer ?: return null
        while (true) {
            if (m.latestSeq() > lastSeq) {
                lastSeq++
                val data = m.fragment(lastSeq) ?: continue
                return Fragment(data, (heldPtsUs - startUs) / 1000L)
            }
            if (eof) return null
            readOne(m)
        }
    }

    private fun readOne(m: Fmp4LiveMuxer) {
        val pts = extractor.sampleTime
        if (pts < 0) {
            held?.let { m.onSample(it, heldKey, frameUs / 1000L) }
            held = null
            m.flush()
            eof = true
            return
        }
        buffer.clear()
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            extractor.advance()
            return
        }
        val bytes = ByteArray(size)
        buffer.position(0)
        buffer.get(bytes, 0, size)
        val key = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
        held?.let { prev ->
            val durMs = ((pts - heldPtsUs) / 1000L).takeIf { it > 0 } ?: (frameUs / 1000L)
            m.onSample(prev, heldKey, durMs)
        }
        held = bytes
        heldKey = key
        heldPtsUs = pts
        extractor.advance()
    }

    override fun close() {
        runCatching { extractor.release() }
        muxer?.clear()
        muxer = null
    }

    private fun bytesOf(buf: ByteBuffer): ByteArray {
        val dup = buf.duplicate()
        dup.position(0)
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return out
    }

    companion object {
        /** `avc1.PPCCLL` from the SPS (profile_idc, constraint flags, level_idc). */
        fun codecString(spsIn: ByteArray): String {
            val sps = when {
                spsIn.size >= 4 && spsIn[0] == 0.toByte() && spsIn[1] == 0.toByte() &&
                    spsIn[2] == 0.toByte() && spsIn[3] == 1.toByte() -> spsIn.copyOfRange(4, spsIn.size)
                spsIn.size >= 3 && spsIn[0] == 0.toByte() && spsIn[1] == 0.toByte() &&
                    spsIn[2] == 1.toByte() -> spsIn.copyOfRange(3, spsIn.size)
                else -> spsIn
            }
            if (sps.size < 4) return "avc1.42e01f"
            return String.format(
                Locale.US,
                "avc1.%02x%02x%02x",
                sps[1].toInt() and 0xff,
                sps[2].toInt() and 0xff,
                sps[3].toInt() and 0xff,
            )
        }
    }
}

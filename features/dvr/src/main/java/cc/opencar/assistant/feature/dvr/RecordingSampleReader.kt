package cc.opencar.assistant.feature.dvr

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

/**
 * Reads one camera's DVR recording as Annex-B AccessUnits stamped with their
 * capture wall clock, for replay over the camera's WebRTC track. Key frames
 * carry SPS/PPS so a viewer can start decoding at any of them.
 */
class RecordingSampleReader(
    private val file: File,
    /** Capture time of the file's first frame (media time 0). */
    private val fileStartUtcMs: Long,
) : Closeable {
    class Sample(val annexB: ByteArray, val wallMs: Long, val isKey: Boolean)

    private val extractor = MediaExtractor()
    private var buffer: ByteBuffer = ByteBuffer.allocateDirect(512 * 1024)
    private var parameterSets = ByteArray(0)

    /** Position on the key frame at or before media [offsetMs]. */
    fun open(offsetMs: Long) {
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/avc") == true
        } ?: error("no H.264 track in ${file.name}")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val sps = format.getByteBuffer("csd-0")?.let(::bytesOf) ?: error("missing SPS")
        val pps = format.getByteBuffer("csd-1")?.let(::bytesOf) ?: error("missing PPS")
        parameterSets = AnnexB.withStartCode(sps) + AnnexB.withStartCode(pps)
        runCatching { format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }.getOrNull()
            ?.takeIf { it > buffer.capacity() }?.let { buffer = ByteBuffer.allocateDirect(it) }
        extractor.seekTo(offsetMs.coerceAtLeast(0L) * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    /** Next AccessUnit, or null at the end of the file. */
    fun next(): Sample? {
        while (true) {
            val ptsUs = extractor.sampleTime
            if (ptsUs < 0) return null
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            val key = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
            extractor.advance()
            if (size <= 0) continue
            val raw = ByteArray(size)
            buffer.position(0)
            buffer.get(raw, 0, size)
            val annexB = toAnnexB(raw) ?: continue
            val data = if (key && !AnnexB.containsNalType(annexB, AnnexB.NAL_SPS)) parameterSets + annexB else annexB
            return Sample(data, fileStartUtcMs + ptsUs / 1000L, key)
        }
    }

    override fun close() {
        runCatching { extractor.release() }
    }

    private fun toAnnexB(raw: ByteArray): ByteArray? {
        if (AnnexB.hasStartCode(raw)) return raw
        val nals = AnnexB.splitAvcc(raw) ?: return null
        return nals.fold(ByteArray(0)) { acc, nal -> acc + AnnexB.withStartCode(nal) }
    }

    private fun bytesOf(buf: ByteBuffer): ByteArray {
        val dup = buf.duplicate()
        dup.position(0)
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return out
    }
}

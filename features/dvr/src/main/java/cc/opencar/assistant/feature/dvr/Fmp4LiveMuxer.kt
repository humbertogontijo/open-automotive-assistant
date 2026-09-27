package cc.opencar.assistant.feature.dvr

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Minimal CMAF / fMP4 for live HLS: one init segment + moof/mdat fragments.
 * DVR recordings still use [MediaMuxer].
 */
class Fmp4LiveMuxer(
    private val width: Int,
    private val height: Int,
    private val timescale: Int = 1000,
) {
    data class Fragment(
        val seq: Long,
        val data: ByteArray,
        val durationTicks: Long,
        val isKey: Boolean,
        val timescale: Int,
    ) {
        fun durationSec(): Double = durationTicks.toDouble() / timescale.coerceAtLeast(1)
    }

    private var initSegment: ByteArray? = null
    private val fragments = CopyOnWriteArrayList<Fragment>()
    private val seq = AtomicLong(1)
    private var decodeTime = 0L
    private val pending = ArrayList<PendingSample>()
    private var pendingDur = 0L
    /** Wakes LL-HLS blocking playlist waiters when a new segment is published. */
    private val segmentLock = Object()

    private data class PendingSample(val avcc: ByteArray, val durationTicks: Long, val isKey: Boolean)

    @Volatile private var fps: Int = 5

    /** Target media segment length (~1s) — fewer HTTP fetches than 1-frame segments. */
    private val segmentTicks: Int
        get() = timescale // 1 second

    fun setFps(value: Int) {
        fps = value.coerceIn(1, 30)
    }

    fun initSegment(): ByteArray? = initSegment
    fun fragment(seq: Long): ByteArray? = fragments.firstOrNull { it.seq == seq }?.data
    /** Highest published media sequence number, or 0 if none yet. */
    fun latestSeq(): Long = fragments.lastOrNull()?.seq ?: 0L

    /**
     * LL-HLS blocking reload: hold until a segment with [msn] (or newer) exists,
     * then return the playlist. On timeout, return whatever is available.
     * Spec: server MUST NOT respond until playlist contains SN >= _HLS_msn.
     */
    fun hlsPlaylistBlocking(msn: Long?, timeoutMs: Long = BLOCK_TIMEOUT_MS): String? {
        if (msn != null && msn > 0L) {
            val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
            synchronized(segmentLock) {
                while (latestSeq() < msn) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0L) break
                    try {
                        segmentLock.wait(left)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        return hlsPlaylist()
    }

    fun setParameterSets(spsIn: ByteArray, ppsIn: ByteArray) {
        val sps = AnnexB.stripStartCode(spsIn)
        val pps = AnnexB.stripStartCode(ppsIn)
        if (sps.size < 4 || pps.size < 2) return
        if (AnnexB.nalType(sps) != AnnexB.NAL_SPS || AnnexB.nalType(pps) != AnnexB.NAL_PPS) return
        initSegment = buildInit(sps, pps)
        Log.i(TAG, "init ready sps=${sps.size} pps=${pps.size}")
    }

    /** Append a sample; [durationTicks] (in [timescale]) defaults to one frame at the current fps. */
    fun onSample(
        data: ByteArray,
        isKey: Boolean,
        durationTicks: Long = (timescale / fps.coerceAtLeast(1)).toLong(),
    ) {
        if (initSegment == null) return
        val avcc = toAvcc(data) ?: return
        val dur = durationTicks.coerceAtLeast(1L)
        // Start a new segment on keyframe if we already have media buffered.
        if (isKey && pending.isNotEmpty()) {
            flushPending()
        }
        pending += PendingSample(avcc, dur, isKey)
        pendingDur += dur
        if (pendingDur >= segmentTicks) {
            flushPending()
        }
    }

    /** Publish buffered samples as a final fragment (end of a restreamed file). */
    fun flush() = flushPending()

    private fun flushPending() {
        if (pending.isEmpty()) return
        val samples = ArrayList(pending)
        val dur = pendingDur
        pending.clear()
        pendingDur = 0
        val s = seq.getAndIncrement()
        val startsWithKey = samples.firstOrNull()?.isKey == true
        val frag = buildFragment(samples, decodeTime, startsWithKey, s)
        decodeTime += dur
        fragments.add(Fragment(s, frag, dur, startsWithKey, timescale))
        while (fragments.size > 30) fragments.removeAt(0)
        synchronized(segmentLock) { segmentLock.notifyAll() }
    }

    fun hlsPlaylist(): String? {
        if (initSegment == null) return null
        val all = fragments.toList()
        if (all.isEmpty()) return null
        // Sliding window of ~6s, but always begin on a keyframe *at or before*
        // the window start — never snap to the latest key (that left only 1–2
        // segments and made hls.js thrash between live edge and older buffer).
        val from = (all.size - WINDOW_SEGMENTS).coerceAtLeast(0)
        val start = (0..from).lastOrNull { all[it].isKey } ?: from
        val window = all.drop(start)
        if (window.isEmpty()) return null
        val target = window.maxOf { it.durationSec() }.let { kotlin.math.ceil(it).toInt().coerceAtLeast(1) }
        // ~2 segments behind edge — lowLatencyMode uses HOLD-BACK when the
        // client does not override with liveSyncDurationCount.
        val holdBack = "%.3f".format(Locale.US, (target * 2).toDouble().coerceAtLeast(2.0))
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        sb.append("#EXT-X-TARGETDURATION:").append(target).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(window.first().seq).append('\n')
        sb.append("#EXT-X-INDEPENDENT-SEGMENTS\n")
        sb.append("#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES,HOLD-BACK=").append(holdBack).append('\n')
        sb.append("#EXT-X-MAP:URI=\"/api/dvr/live/init.mp4\"\n")
        for (f in window) {
            sb.append("#EXTINF:").append("%.3f".format(Locale.US, f.durationSec())).append(",\n")
            sb.append("/api/dvr/live/seg/").append(f.seq).append(".m4s\n")
        }
        return sb.toString()
    }

    /** Drop buffered media but keep the init segment and sequence numbering (live viewer returned). */
    fun resetMedia() {
        fragments.clear()
        pending.clear()
        pendingDur = 0
    }

    fun clear() {
        initSegment = null
        fragments.clear()
        decodeTime = 0
        seq.set(1)
        pending.clear()
        pendingDur = 0
        synchronized(segmentLock) { segmentLock.notifyAll() }
    }

    /** Media NAL units as AVCC; parameter sets and AUDs are dropped (they live in the init segment). */
    private fun toAvcc(data: ByteArray): ByteArray? {
        if (data.size < 5) return null
        val nals = (if (AnnexB.hasStartCode(data)) AnnexB.split(data) else AnnexB.splitAvcc(data) ?: return null)
            .filter { AnnexB.nalType(it) !in AnnexB.NAL_SPS..AnnexB.NAL_AUD }
        return if (nals.isEmpty()) null else AnnexB.toAvcc(nals)
    }

    private fun buildInit(sps: ByteArray, pps: ByteArray): ByteArray {
        val ftyp = box(
            "ftyp",
            byteArrayOf(
                0x69, 0x73, 0x6f, 0x6d, 0, 0, 0, 1,
                0x69, 0x73, 0x6f, 0x6d, 0x69, 0x73, 0x6f, 0x35, 0x61, 0x76, 0x63, 0x31,
            ),
        )
        val avcC = buildAvcC(sps, pps)
        val avc1 = box(
            "avc1",
            concat(
                ByteArray(6), u16(1), ByteArray(16),
                u16(width), u16(height),
                u32(0x00480000), u32(0x00480000), u32(0),
                u16(1), ByteArray(32), u16(0x0018), u16(0xffff),
                avcC,
            ),
        )
        val stsd = box("stsd", concat(u32(0), u32(1), avc1))
        val stbl = box(
            "stbl",
            concat(
                stsd,
                box("stts", concat(u32(0), u32(0))),
                box("stsc", concat(u32(0), u32(0))),
                box("stsz", concat(u32(0), u32(0), u32(0))),
                box("stco", concat(u32(0), u32(0))),
            ),
        )
        val url = box("url ", u32(1))
        val dref = box("dref", concat(u32(0), u32(1), url))
        val minf = box("minf", concat(box("vmhd", concat(u32(1), u16(0), u16(0), u16(0), u16(0))), box("dinf", dref), stbl))
        val hdlr = box(
            "hdlr",
            concat(
                u32(0), u32(0),
                byteArrayOf(0x76, 0x69, 0x64, 0x65),
                ByteArray(12),
                byteArrayOf(0x56, 0x69, 0x64, 0x65, 0x6f, 0x48, 0x61, 0x6e, 0x64, 0x6c, 0x65, 0x72, 0),
            ),
        )
        val mdhd = box("mdhd", concat(u32(0), u32(0), u32(0), u32(timescale), u32(0), u16(0x55c4), u16(0)))
        val mdia = box("mdia", concat(mdhd, hdlr, minf))
        val tkhd = box(
            "tkhd",
            concat(
                u32(0x0007), u32(0), u32(0), u32(1), u32(0), u32(0),
                u32(0), u32(0), // reserved[2]
                u16(0), u16(0), u16(0), u16(0),
                u32(0x00010000), u32(0), u32(0),
                u32(0), u32(0x00010000), u32(0),
                u32(0), u32(0), u32(0x40000000),
                u32(width shl 16), u32(height shl 16),
            ),
        )
        val trak = box("trak", concat(tkhd, mdia))
        val mvhd = box(
            "mvhd",
            concat(
                u32(0), u32(0), u32(0), u32(timescale), u32(0),
                u32(0x00010000), u16(0x0100), u16(0),
                u32(0), u32(0),
                u32(0x00010000), u32(0), u32(0),
                u32(0), u32(0x00010000), u32(0),
                u32(0), u32(0), u32(0x40000000),
                u32(0), u32(0), u32(0), u32(0), u32(0), u32(0),
                u32(2),
            ),
        )
        val trex = box("trex", concat(u32(0), u32(1), u32(1), u32(0), u32(0), u32(0)))
        val moov = box("moov", concat(mvhd, trak, box("mvex", trex)))
        return concat(ftyp, moov)
    }

    private fun buildAvcC(sps: ByteArray, pps: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(1)
        body.write(sps[1].toInt() and 0xff)
        body.write(sps[2].toInt() and 0xff)
        body.write(sps[3].toInt() and 0xff)
        body.write(0xff)
        body.write(0xe1)
        body.write((sps.size ushr 8) and 0xff)
        body.write(sps.size and 0xff)
        body.write(sps)
        body.write(1)
        body.write((pps.size ushr 8) and 0xff)
        body.write(pps.size and 0xff)
        body.write(pps)
        return box("avcC", body.toByteArray())
    }

    private fun buildFragment(
        samples: List<PendingSample>,
        baseDecodeTime: Long,
        startsWithKey: Boolean,
        sequence: Long,
    ): ByteArray {
        val tfhd = box("tfhd", concat(u32(0x020000), u32(1)))
        val tfdt = box("tfdt", concat(u32(0x01000000), u64(baseDecodeTime)))
        // data-offset | sample-duration | sample-size | sample-flags
        val trunFlags = 0x000701
        fun sampleEntries(): ByteArray {
            val out = ByteArrayOutputStream()
            for ((i, s) in samples.withIndex()) {
                val flags = when {
                    i == 0 && startsWithKey -> 0x02000000
                    s.isKey -> 0x02000000
                    else -> 0x01010000
                }
                out.write(u32(s.durationTicks.toInt()))
                out.write(u32(s.avcc.size))
                out.write(u32(flags))
            }
            return out.toByteArray()
        }
        fun trun(dataOffset: Int) = box(
            "trun",
            concat(
                u32(trunFlags),
                u32(samples.size),
                u32(dataOffset),
                sampleEntries(),
            ),
        )
        val probe = box(
            "moof",
            concat(
                box("mfhd", concat(u32(0), u32(sequence.toInt()))),
                box("traf", concat(tfhd, tfdt, trun(0))),
            ),
        )
        val dataOffset = probe.size + 8
        val moof = box(
            "moof",
            concat(
                box("mfhd", concat(u32(0), u32(sequence.toInt()))),
                box("traf", concat(tfhd, tfdt, trun(dataOffset))),
            ),
        )
        val mdatBody = ByteArrayOutputStream()
        for (s in samples) mdatBody.write(s.avcc)
        return concat(moof, box("mdat", mdatBody.toByteArray()))
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val out = ByteArray(size)
        out[0] = ((size ushr 24) and 0xff).toByte()
        out[1] = ((size ushr 16) and 0xff).toByte()
        out[2] = ((size ushr 8) and 0xff).toByte()
        out[3] = (size and 0xff).toByte()
        val t = type.toByteArray(Charsets.US_ASCII)
        System.arraycopy(t, 0, out, 4, 4)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    private fun u16(v: Int): ByteArray = byteArrayOf(((v ushr 8) and 0xff).toByte(), (v and 0xff).toByte())
    private fun u32(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xff).toByte(),
        ((v ushr 16) and 0xff).toByte(),
        ((v ushr 8) and 0xff).toByte(),
        (v and 0xff).toByte(),
    )
    private fun u32(v: Long): ByteArray = u32(v.toInt())
    private fun u64(v: Long): ByteArray = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array()

    private fun concat(vararg parts: ByteArray): ByteArray {
        val n = parts.sumOf { it.size }
        val out = ByteArray(n)
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, o, p.size)
            o += p.size
        }
        return out
    }

    companion object {
        private const val TAG = "OaaFmp4"
        private const val WINDOW_SEGMENTS = 6
        /** Apple LL-HLS: hold blocking reloads up to ~3× target duration. */
        private const val BLOCK_TIMEOUT_MS = 3_000L
    }
}

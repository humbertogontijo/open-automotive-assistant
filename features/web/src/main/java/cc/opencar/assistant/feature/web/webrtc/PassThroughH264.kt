package cc.opencar.assistant.feature.web.webrtc

import cc.opencar.assistant.feature.dvr.AnnexB
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.feature.dvr.MosaicH264Encoder
import cc.opencar.assistant.feature.dvr.SharedH264Pipeline

/**
 * Feeds mosaic AccessUnits to the live track without re-encoding.
 *
 * Sending starts at the next IDR (after start, resume or a viewer PLI) so the
 * browser never gets a broken reference chain; IDRs carry SPS/PPS for late joiners.
 */
class PassThroughH264Source(
    private val dvr: DvrController,
    /** Writes one Annex-B AccessUnit lasting `durationUs`; false once the session is gone. */
    private val write: (ByteArray, Long) -> Boolean,
) : SharedH264Pipeline.SampleTap {
    private val lock = Any()
    private var waitingForKey = true
    private var lastPtsUs = -1L
    @Volatile private var disposed = false

    @Volatile var paused: Boolean = false
        set(value) {
            field = value
            if (!value) restart()
        }

    override fun onSample(unit: MosaicH264Encoder.AccessUnit) {
        if (paused || disposed) return
        val payload: ByteArray
        val durationUs: Long
        synchronized(lock) {
            if (waitingForKey) {
                if (!unit.isKeyFrame) return
                waitingForKey = false
            }
            durationUs = if (lastPtsUs in 0 until unit.ptsUs) unit.ptsUs - lastPtsUs else frameUs()
            lastPtsUs = unit.ptsUs
            payload = annexB(unit)
        }
        if (!write(payload, durationUs)) reset()
    }

    /** IDRs get SPS/PPS prepended so late joiners can decode. */
    private fun annexB(unit: MosaicH264Encoder.AccessUnit): ByteArray {
        if (!unit.isKeyFrame || AnnexB.containsNalType(unit.data, AnnexB.NAL_SPS)) return unit.data
        val ps = dvr.liveParameterSets() ?: return unit.data
        return ps + unit.data
    }

    private fun frameUs(): Long = 1_000_000L / dvr.liveFps().coerceIn(1, 60)

    /** Viewer PLI/FIR: keep sending deltas, ask the encoder for an IDR. */
    fun requestKeyFrame() = dvr.requestLiveKeyFrame()

    /** Drop to the next IDR and ask the encoder for one (start, resume, ICE connected). */
    fun restart() {
        reset()
        dvr.requestLiveKeyFrame()
    }

    private fun reset() {
        synchronized(lock) {
            waitingForKey = true
            lastPtsUs = -1L
        }
    }

    fun dispose() {
        disposed = true
        reset()
    }
}

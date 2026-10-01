package cc.opencar.assistant.feature.web.webrtc

import cc.opencar.assistant.feature.dvr.AnnexB
import cc.opencar.assistant.feature.dvr.CameraEncoderSession
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.feature.dvr.H264Encoder

/**
 * Feeds one camera's AccessUnits to its live track without re-encoding.
 *
 * Sending starts at the next IDR (after start, resume or a viewer PLI) so the
 * browser never gets a broken reference chain; IDRs carry SPS/PPS for late joiners.
 */
class PassThroughH264Source(
    private val dvr: DvrController,
    val role: String,
    /** Writes one Annex-B AccessUnit captured at `captureUtcMs`; false once the session is gone. */
    private val write: (ByteArray, Long) -> Boolean,
) : CameraEncoderSession.SampleTap {
    private val lock = Any()
    private var waitingForKey = true
    @Volatile private var disposed = false

    @Volatile var paused: Boolean = false
        set(value) {
            field = value
            if (!value) restart()
        }

    /** Frames captured before this are dropped: a replay already used RTP times up to it on this track. */
    @Volatile var minCaptureUtcMs: Long = 0L

    override fun onSample(unit: H264Encoder.AccessUnit) {
        if (paused || disposed || unit.captureUtcMs < minCaptureUtcMs) return
        synchronized(lock) {
            if (waitingForKey) {
                if (!unit.isKeyFrame) return
                waitingForKey = false
            }
        }
        if (!write(annexB(unit), unit.captureUtcMs)) reset()
    }

    /** IDRs get SPS/PPS prepended so late joiners can decode. */
    private fun annexB(unit: H264Encoder.AccessUnit): ByteArray {
        if (!unit.isKeyFrame || AnnexB.containsNalType(unit.data, AnnexB.NAL_SPS)) return unit.data
        val ps = dvr.liveParameterSets(role) ?: return unit.data
        return ps + unit.data
    }

    /** Viewer PLI/FIR: keep sending deltas, ask the encoder for an IDR. */
    fun requestKeyFrame() = dvr.requestLiveKeyFrame(role)

    /** Drop to the next IDR and ask the encoder for one (start, resume, ICE connected). */
    fun restart() {
        reset()
        dvr.requestLiveKeyFrame(role)
    }

    private fun reset() {
        synchronized(lock) { waitingForKey = true }
    }

    fun dispose() {
        disposed = true
        reset()
    }
}

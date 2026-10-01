package cc.opencar.assistant.feature.web.webrtc

/**
 * Timing of a recording replay over a session's camera tracks; one clock for
 * every camera so they stay in step.
 *
 * A frame captured at wall time W is due at car time `t0 + (W - w0) / speed`,
 * and that car time is also its RTP timestamp. RTP time therefore keeps
 * following the car clock through seeks, pauses and speed changes, which is
 * what the viewer's jitter buffer expects from a live stream. Each re-anchor
 * starts an epoch whose frames all have RTP times at or after
 * [Anchor.fromRtpMs]; the viewer maps them back to capture time with the anchor.
 *
 * Not thread-safe; owned by the replay thread.
 */
class ReplayClock {
    data class Anchor(val fromRtpMs: Long, val rtpMs: Long, val wallMs: Long, val speed: Double) {
        /** Capture time of a frame of this epoch sent with RTP time [rtpMs]. */
        fun wallAt(rtpMs: Long): Long = wallMs + ((rtpMs - this.rtpMs) * speed).toLong()
    }

    private val lastRtp = HashMap<String, Long>()

    var anchor: Anchor = Anchor(0L, 0L, 0L, 1.0)
        private set

    /** Wall position at car time [nowMs] while playing. */
    fun positionAt(nowMs: Long): Long = anchor.wallAt(nowMs)

    /**
     * Start an epoch that plays from wall [wallMs] at [speed]. Frames sent so far all precede it,
     * and so do live frames (RTP = capture time) older than its pre-roll.
     */
    fun reanchor(nowMs: Long, wallMs: Long, speed: Double): Anchor {
        val from = maxOf((lastRtp.values.maxOrNull() ?: 0L) + 1L, nowMs - PREROLL_SLOTS_MS)
        anchor = Anchor(from, maxOf(nowMs, from), wallMs, speed)
        return anchor
    }

    /** Car time at which the frame captured at [wallMs] is due. */
    fun dueAt(wallMs: Long): Long = anchor.rtpMs + ((wallMs - anchor.wallMs) / anchor.speed).toLong()

    /**
     * [role] is about to send the frames between its key frame and the target at once;
     * they get RTP times just below [nowMs] instead of their (past) due times.
     */
    fun startPreroll(role: String, nowMs: Long) {
        lastRtp[role] = maxOf(lastRtp[role] ?: 0L, nowMs - PREROLL_SLOTS_MS)
    }

    /** RTP time (ms) of [role]'s next frame, captured at [wallMs]: increasing per role, inside the epoch. */
    fun rtpFor(role: String, wallMs: Long): Long {
        val rtp = maxOf(dueAt(wallMs), (lastRtp[role] ?: 0L) + 1L, anchor.fromRtpMs)
        lastRtp[role] = rtp
        return rtp
    }

    /** Live frames captured before this would land inside the replay's RTP range. */
    fun liveFloor(): Long = (lastRtp.values.maxOrNull() ?: 0L) + 1L

    companion object {
        /** Room below "now" for one GOP of pre-roll frames, 1 ms apart. */
        const val PREROLL_SLOTS_MS = 120L
    }
}

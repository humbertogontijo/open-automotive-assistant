package cc.opencar.assistant.feature.web.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayClockTest {
    private val now = 1_790_000_000_000L
    private val wall = 1_789_000_000_000L

    @Test
    fun framesAreDueOnTheReplayTimeline() {
        val c = ReplayClock()
        c.reanchor(now, wall, 1.0)
        assertEquals(now, c.dueAt(wall))
        assertEquals(now + 40, c.dueAt(wall + 40))
        assertEquals(now + 1_000, c.rtpFor("front", wall + 1_000))
        assertEquals(wall + 500, c.positionAt(now + 500))
    }

    @Test
    fun speedScalesDueTimesAndAnchorMapsBack() {
        val c = ReplayClock()
        val a = c.reanchor(now, wall, 2.0)
        assertEquals(now + 500, c.dueAt(wall + 1_000))
        val rtp = c.rtpFor("rear", wall + 1_000)
        assertEquals(wall + 1_000, a.wallAt(rtp))
    }

    @Test
    fun rtpIncreasesPerRoleAcrossSeeks() {
        val c = ReplayClock()
        c.reanchor(now, wall, 1.0)
        val before = c.rtpFor("front", wall + 2_000)
        // Seek back an hour 10 ms later: RTP time must not go backwards.
        val a = c.reanchor(now + 10, wall - 3_600_000, 1.0)
        c.startPreroll("front", now + 10)
        val preroll = c.rtpFor("front", wall - 3_601_000)
        assertTrue(a.fromRtpMs > before)
        assertTrue(preroll >= a.fromRtpMs)
        assertTrue(c.rtpFor("front", wall - 3_600_000) > preroll)
    }

    @Test
    fun prerollSitsJustBelowNowAfterALongPause() {
        val c = ReplayClock()
        c.reanchor(now, wall, 1.0)
        c.rtpFor("left", wall)
        val later = now + 600_000
        val a = c.reanchor(later, wall + 30_000, 1.0)
        c.startPreroll("left", later)
        val first = c.rtpFor("left", wall + 28_500)
        assertTrue(first >= a.fromRtpMs)
        assertTrue(first in (later - ReplayClock.PREROLL_SLOTS_MS)..later)
        assertEquals(later, a.rtpMs)
    }

    @Test
    fun firstEpochLeavesEarlierLiveFramesOutside() {
        val c = ReplayClock()
        val a = c.reanchor(now, wall, 1.0)
        c.startPreroll("front", now)
        assertEquals(now - ReplayClock.PREROLL_SLOTS_MS, a.fromRtpMs)
        assertTrue(c.rtpFor("front", wall - 1_000) >= a.fromRtpMs)
    }

    @Test
    fun liveFloorIsAfterEveryReplayFrame() {
        val c = ReplayClock()
        c.reanchor(now, wall, 1.0)
        val a = c.rtpFor("front", wall + 100)
        val b = c.rtpFor("right", wall + 140)
        assertEquals(maxOf(a, b) + 1, c.liveFloor())
    }
}

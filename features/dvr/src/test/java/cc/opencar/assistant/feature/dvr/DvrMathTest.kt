package cc.opencar.assistant.feature.dvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DvrTimelineMathTest {
    @Test
    fun mediaRangeClampsToSegment() {
        val (from, to) = DvrTimelineMath.mediaRangeForCut(
            segStartUtcMs = 1_000L,
            segDurationMs = 10_000L,
            fromUtcMs = 500L,
            toUtcMs = 20_000L,
        )
        assertEquals(0L, from)
        assertEquals(10_000L, to)
    }

    @Test
    fun mediaRangePartialOverlap() {
        val (from, to) = DvrTimelineMath.mediaRangeForCut(
            segStartUtcMs = 1_000L,
            segDurationMs = 10_000L,
            fromUtcMs = 3_000L,
            toUtcMs = 6_000L,
        )
        assertEquals(2_000L, from)
        assertEquals(5_000L, to)
    }

    @Test
    fun resolvePlayAtInsideSegment() {
        val segs = listOf(
            DvrTimelineMath.Segment(0, 5_000, 5_000),
            DvrTimelineMath.Segment(10_000, 20_000, 10_000),
        )
        val snap = DvrTimelineMath.resolvePlayAt(segs, 12_500)!!
        assertEquals(1, snap.index)
        assertEquals(2_500L, snap.offsetMs)
        assertEquals(12_500L, snap.wallUtcMs)
    }

    @Test
    fun resolvePlayAtSnapsGapToNearestEdge() {
        val segs = listOf(
            DvrTimelineMath.Segment(0, 5_000, 5_000),
            DvrTimelineMath.Segment(10_000, 20_000, 10_000),
        )
        val snap = DvrTimelineMath.resolvePlayAt(segs, 7_000)!!
        // Closer to end of first (4999) than start of second (10000)
        assertEquals(0, snap.index)
        assertEquals(4_999L, snap.wallUtcMs)
    }

    @Test
    fun resolvePlayAtEmpty() {
        assertNull(DvrTimelineMath.resolvePlayAt(emptyList(), 1L))
    }
}

class DvrStorageMathTest {
    @Test
    fun dvrDirAppendsOnce() {
        val root = File("/tmp/OpenAutomotiveAssistant")
        assertEquals(File(root, "dvr"), DvrStorageMath.dvrDirUnder(root))
    }

    @Test
    fun isUnderDirectoryRejectsSiblingPrefix() {
        val dir = File("/data/app/files")
        assertTrue(DvrStorageMath.isUnderDirectory(File("/data/app/files/dvr/a.mp4"), dir))
        assertTrue(DvrStorageMath.isUnderDirectory(File("/data/app/files"), dir))
        assertFalse(DvrStorageMath.isUnderDirectory(File("/data/app/files2/a.mp4"), dir))
        assertFalse(DvrStorageMath.isUnderDirectory(File("/data/app/files_backup/a.mp4"), dir))
    }

    @Test
    fun isDvrRecordingName() {
        assertTrue(DvrStorageMath.isDvrRecordingName("oaa_dvr_20260101_120000.mp4"))
        assertFalse(DvrStorageMath.isDvrRecordingName("oaa_dvr_x.mjpeg"))
        assertFalse(DvrStorageMath.isDvrRecordingName("../oaa_dvr_x.mp4"))
        assertFalse(DvrStorageMath.isDvrRecordingName("oaa_dvr_x.mp4.meta"))
    }

    @Test
    fun pruneByAgeAndSize() {
        val now = 1_000_000_000L
        val files = listOf(
            DvrStorageMath.PruneFile("a.mp4", now - 10 * 86_400_000L, 50L * 1024 * 1024, locked = false, active = false),
            DvrStorageMath.PruneFile("b.mp4", now - 1_000L, 50L * 1024 * 1024, locked = false, active = false),
            DvrStorageMath.PruneFile("c.mp4", now - 2_000L, 50L * 1024 * 1024, locked = true, active = false),
            DvrStorageMath.PruneFile("active.mp4", now, 10L * 1024 * 1024, locked = false, active = true),
        )
        // maxAgeDays=7 deletes a; maxTotalMb=80 keeps b+c (~100MB) so also drops oldest unlocked among remaining → b
        val deleted = DvrStorageMath.pruneDeleteNames(files, now, maxAgeDays = 7, maxTotalMb = 80)
        assertTrue(deleted.contains("a.mp4"))
        assertTrue(deleted.contains("b.mp4"))
        assertFalse(deleted.contains("c.mp4"))
        assertFalse(deleted.contains("active.mp4"))
    }
}

class DvrGroupTimelineTest {
    private fun file(role: String, stamp: String, start: Long, dur: Long) =
        DvrStorageMath.groupOf(DvrStorageMath.recordingName(stamp, role)) to
            DvrTimelineMath.CameraFile(role, DvrStorageMath.recordingName(stamp, role), start, dur)

    private val groups = DvrTimelineMath.groups(
        listOf(
            file("front", "20260101_120500", 300_000L, 300_000L),
            file("front", "20260101_120000", 0L, 300_000L),
            file("rear", "20260101_120000", 40L, 299_900L),
            file("rear", "20260101_120500", 300_030L, 299_000L),
        ),
    )

    @Test
    fun groupsByStampSortedWithUnionSpan() {
        assertEquals(2, groups.size)
        assertEquals("oaa_dvr_20260101_120000", groups[0].id)
        assertEquals(0L, groups[0].startUtcMs)
        assertEquals(300_000L, groups[0].endUtcMs)
        assertEquals(listOf("front", "rear"), groups[0].files.map { it.role })
        assertEquals(600_000L, groups[1].endUtcMs)
    }

    @Test
    fun cutRangesForRoleSpanGroups() {
        val parts = DvrTimelineMath.cutRangesForRole(groups, "rear", 290_000L, 310_000L)
        assertEquals(2, parts.size)
        assertEquals("oaa_dvr_20260101_120000_rear.mp4", parts[0].first.name)
        assertEquals(289_960L, parts[0].second)
        assertEquals(299_900L, parts[0].third)
        assertEquals(0L, parts[1].second)
        assertEquals(9_970L, parts[1].third)
    }

    @Test
    fun cutRangesForMissingRoleIsEmpty() {
        assertTrue(DvrTimelineMath.cutRangesForRole(groups, "left", 0L, 10_000L).isEmpty())
    }

    @Test
    fun perRoleOffsetUsesThatCamerasStart() {
        val g = groups[0]
        val snap = DvrTimelineMath.resolvePlayAt(groups.map { it.asSegment() }, 10_000L)!!
        assertEquals(0, snap.index)
        assertEquals(10_000L, DvrTimelineMath.offsetInFile(g.file("front")!!, snap.wallUtcMs))
        assertEquals(9_960L, DvrTimelineMath.offsetInFile(g.file("rear")!!, snap.wallUtcMs))
        // Before the rear file's first frame clamps to its start.
        assertEquals(0L, DvrTimelineMath.offsetInFile(g.file("rear")!!, 10L))
    }
}

class DvrGroupStorageTest {
    @Test
    fun namesRoundTrip() {
        val name = DvrStorageMath.recordingName("20260101_120000", "front")
        assertEquals("oaa_dvr_20260101_120000_front.mp4", name)
        assertEquals("oaa_dvr_20260101_120000", DvrStorageMath.groupOf(name))
        assertEquals("front", DvrStorageMath.roleOf(name))
        assertNull(DvrStorageMath.roleOf("oaa_dvr_20260101_120000.mp4"))
    }

    @Test
    fun sanitizeRoleFallsBackToIndex() {
        assertEquals("front", DvrStorageMath.sanitizeRole("Front", 0))
        assertEquals("cam2", DvrStorageMath.sanitizeRole(null, 2))
        assertEquals("cam3", DvrStorageMath.sanitizeRole("--", 3))
    }

    @Test
    fun pruneDeletesWholeGroupsAndHonorsGroupLocks() {
        val now = 1_000_000_000L
        val mb = 1024L * 1024L
        fun f(stamp: String, role: String, age: Long, locked: Boolean = false, active: Boolean = false) =
            DvrStorageMath.PruneFile(DvrStorageMath.recordingName(stamp, role), now - age, 30 * mb, locked, active)
        val files = listOf(
            f("20260101_100000", "front", 3_000L),
            f("20260101_100000", "rear", 2_900L),
            f("20260101_110000", "front", 2_000L, locked = true),
            f("20260101_110000", "rear", 1_900L),
            f("20260101_120000", "front", 0L, active = true),
            f("20260101_120000", "rear", 0L),
        )
        val deleted = DvrStorageMath.pruneDeleteNames(files, now, maxAgeDays = 0, maxTotalMb = 70).toSet()
        assertEquals(
            setOf("oaa_dvr_20260101_100000_front.mp4", "oaa_dvr_20260101_100000_rear.mp4"),
            deleted,
        )
    }
}

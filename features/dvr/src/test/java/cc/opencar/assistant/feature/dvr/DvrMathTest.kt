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

class MosaicLayoutTest {
    @Test
    fun fourEqualTilesMake2x2Canvas() {
        val tiles = listOf(
            640 to 480,
            640 to 480,
            640 to 480,
            640 to 480,
        )
        assertEquals(1280 to 960, MosaicLayout.canvasSize(tiles))
        assertEquals(2, MosaicLayout.colsFor(4))
    }

    @Test
    fun mixedSizesUseMaxPerColumnAndRow() {
        val tiles = listOf(
            640 to 480,
            800 to 480,
            640 to 360,
            640 to 480,
        )
        // cols=2: col0 max(640,640)=640, col1 max(800,640)=800 → 1440
        // rows=2: row0 max(480,480)=480, row1 max(360,480)=480 → 960
        assertEquals(1440 to 960, MosaicLayout.canvasSize(tiles))
    }

    @Test
    fun emptyFallsBack() {
        assertEquals(640 to 480, MosaicLayout.canvasSize(emptyList()))
    }
}

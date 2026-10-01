package cc.opencar.assistant.feature.dvr

/**
 * Pure wall-clock / media-offset helpers for DVR play + cut (unit-tested).
 */
object DvrTimelineMath {

    data class Segment(
        val startUtcMs: Long,
        val endUtcMs: Long,
        val durationMs: Long,
    )

    data class PlaySnap(
        val startUtcMs: Long,
        val endUtcMs: Long,
        val durationMs: Long,
        val wallUtcMs: Long,
        val offsetMs: Long,
        val index: Int,
    )

    /** One camera's file inside a segment group; `startUtcMs` is its first frame's capture time. */
    data class CameraFile(
        val role: String,
        val name: String,
        val startUtcMs: Long,
        val durationMs: Long,
    ) {
        val endUtcMs: Long get() = startUtcMs + durationMs
    }

    /** Per-camera files recorded together; spans the union of its files. */
    data class Group(
        val id: String,
        val files: List<CameraFile>,
    ) {
        val startUtcMs: Long get() = files.minOf { it.startUtcMs }
        val endUtcMs: Long get() = files.maxOf { it.endUtcMs }
        val durationMs: Long get() = endUtcMs - startUtcMs
        fun file(role: String): CameraFile? = files.firstOrNull { it.role == role }
        fun asSegment(): Segment = Segment(startUtcMs, endUtcMs, durationMs)
    }

    /** Group per-camera files by group id; empty groups are dropped, result sorted by start. */
    fun groups(files: List<Pair<String, CameraFile>>): List<Group> =
        files.groupBy({ it.first }, { it.second })
            .filterValues { it.isNotEmpty() }
            .map { (id, list) -> Group(id, list.sortedBy { it.role }) }
            .sortedBy { it.startUtcMs }

    /** Media [from,to) inside a segment for a wall-clock cut range. */
    fun mediaRangeForCut(
        segStartUtcMs: Long,
        segDurationMs: Long,
        fromUtcMs: Long,
        toUtcMs: Long,
    ): Pair<Long, Long> {
        val mediaFrom = (fromUtcMs - segStartUtcMs).coerceAtLeast(0L)
        val mediaTo = (toUtcMs - segStartUtcMs)
            .coerceAtMost(segDurationMs)
            .coerceAtLeast(mediaFrom + 1L)
        return mediaFrom to mediaTo
    }

    /** One camera's files overlapping [fromUtcMs, toUtcMs), with media ranges, in time order. */
    fun cutRangesForRole(
        groups: List<Group>,
        role: String,
        fromUtcMs: Long,
        toUtcMs: Long,
    ): List<Triple<CameraFile, Long, Long>> =
        groups.mapNotNull { it.file(role) }
            .filter { it.startUtcMs < toUtcMs && it.endUtcMs > fromUtcMs }
            .sortedBy { it.startUtcMs }
            .map { f ->
                val (a, b) = mediaRangeForCut(f.startUtcMs, f.durationMs, fromUtcMs, toUtcMs)
                Triple(f, a, b)
            }

    /** Media offset of wall time [wallUtcMs] inside [file], clamped to the file. */
    fun offsetInFile(file: CameraFile, wallUtcMs: Long): Long =
        (wallUtcMs - file.startUtcMs).coerceIn(0L, (file.durationMs - 1).coerceAtLeast(0L))

    /**
     * Snap [atUtcMs] into [segs] (sorted by start). Returns null if empty.
     */
    fun resolvePlayAt(segs: List<Segment>, atUtcMs: Long): PlaySnap? {
        if (segs.isEmpty()) return null
        val containing = segs.indexOfFirst { atUtcMs >= it.startUtcMs && atUtcMs < it.endUtcMs }
        if (containing >= 0) {
            val s = segs[containing]
            val wall = atUtcMs.coerceIn(s.startUtcMs, (s.endUtcMs - 1).coerceAtLeast(s.startUtcMs))
            val offset = (wall - s.startUtcMs).coerceIn(0L, (s.durationMs - 1).coerceAtLeast(0L))
            return PlaySnap(s.startUtcMs, s.endUtcMs, s.durationMs, wall, offset, containing)
        }
        var bestIdx = 0
        var bestDist = Long.MAX_VALUE
        var bestWall = segs[0].startUtcMs
        for (i in segs.indices) {
            val s = segs[i]
            val dStart = kotlin.math.abs(atUtcMs - s.startUtcMs)
            val dEnd = kotlin.math.abs(atUtcMs - (s.endUtcMs - 1))
            if (dStart < bestDist) {
                bestDist = dStart
                bestIdx = i
                bestWall = s.startUtcMs
            }
            if (dEnd < bestDist) {
                bestDist = dEnd
                bestIdx = i
                bestWall = s.endUtcMs - 1
            }
        }
        val s = segs[bestIdx]
        val offset = (bestWall - s.startUtcMs).coerceIn(0L, (s.durationMs - 1).coerceAtLeast(0L))
        return PlaySnap(s.startUtcMs, s.endUtcMs, s.durationMs, bestWall, offset, bestIdx)
    }
}

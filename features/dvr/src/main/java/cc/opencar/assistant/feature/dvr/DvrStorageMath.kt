package cc.opencar.assistant.feature.dvr

import java.io.File
import java.util.Locale

/**
 * Pure path / naming / prune selection helpers (unit-tested).
 *
 * A recording segment is a group of per-camera files sharing one stamp:
 * `oaa_dvr_<yyyyMMdd_HHmmss>_<role>.mp4`. Retention and locks act on whole groups.
 */
object DvrStorageMath {

    const val SUBDIR_DVR = "dvr"
    const val PREFIX = "oaa_dvr_"

    private val GROUPED = Regex("^oaa_dvr_(\\d{8}_\\d{6})_([a-z0-9]{1,16})\\.mp4$")
    private val ROLE = Regex("^[a-z0-9]{1,16}$")

    /**
     * Build the DVR directory under [storageRoot]. Roots must not already end in `/dvr`
     * (caller strips); this always appends exactly one [SUBDIR_DVR] segment.
     */
    fun dvrDirUnder(storageRoot: File): File = File(storageRoot, SUBDIR_DVR)

    /** True when [file] is [dir] or a descendant (canonical paths, separator-safe). */
    fun isUnderDirectory(file: File, dir: File): Boolean {
        val canon = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        val prefix = root.path
        return canon.path == prefix || canon.path.startsWith(prefix + File.separator)
    }

    /** True when [name] is a continuous DVR media file we still recognize. */
    fun isDvrRecordingName(name: String): Boolean {
        if (name.isBlank() || name.contains("..") || name.contains('/') || name.contains('\\')) {
            return false
        }
        if (name.endsWith(".lock") || name.endsWith(".meta")) return false
        return name.startsWith(PREFIX) && name.endsWith(".mp4")
    }

    /** Camera role safe for file names: lowercase `[a-z0-9]`, or `cam<index>` when unusable. */
    fun sanitizeRole(raw: String?, index: Int): String {
        val r = raw?.lowercase(Locale.US)?.filter { it in 'a'..'z' || it in '0'..'9' }?.take(16)
        return if (r != null && ROLE.matches(r)) r else "cam$index"
    }

    fun groupBase(stamp: String): String = PREFIX + stamp

    fun recordingName(stamp: String, role: String): String = "${groupBase(stamp)}_$role.mp4"

    /** Group id (`oaa_dvr_<stamp>`) of a per-camera file, or the name itself for anything else. */
    fun groupOf(name: String): String =
        GROUPED.matchEntire(name)?.let { PREFIX + it.groupValues[1] } ?: name

    /** Camera role of a per-camera file, or null. */
    fun roleOf(name: String): String? = GROUPED.matchEntire(name)?.groupValues?.get(2)

    data class PruneFile(
        val name: String,
        val lastModified: Long,
        val length: Long,
        val locked: Boolean,
        val active: Boolean,
    )

    /**
     * Returns names to delete for age then size policy, oldest unlocked group first.
     * A group is locked / active when any of its files is. [nowMs] / [maxAgeDays] /
     * [maxTotalMb] mirror [DvrController] policy.
     */
    fun pruneDeleteNames(
        files: List<PruneFile>,
        nowMs: Long,
        maxAgeDays: Int,
        maxTotalMb: Int,
    ): List<String> {
        val groups = files.groupBy { groupOf(it.name) }.values.map { members ->
            PruneGroup(
                names = members.map { it.name },
                lastModified = members.maxOf { it.lastModified },
                length = members.sumOf { it.length },
                locked = members.any { it.locked },
                active = members.any { it.active },
            )
        }
        val toDelete = linkedSetOf<String>()
        val deletedGroups = HashSet<PruneGroup>()
        val cutoff = if (maxAgeDays > 0) nowMs - maxAgeDays * 86_400_000L else 0L
        if (cutoff > 0) {
            groups.filter { !it.active && !it.locked && it.lastModified < cutoff }.forEach {
                toDelete += it.names
                deletedGroups += it
            }
        }
        val remaining = groups.filter { !it.active && it !in deletedGroups }
        var total = remaining.sumOf { it.length }
        val cap = maxTotalMb.toLong() * 1024L * 1024L
        for (g in remaining.filter { !it.locked }.sortedBy { it.lastModified }) {
            if (total <= cap) break
            toDelete += g.names
            total -= g.length
        }
        return toDelete.toList()
    }

    private data class PruneGroup(
        val names: List<String>,
        val lastModified: Long,
        val length: Long,
        val locked: Boolean,
        val active: Boolean,
    )
}

package cc.opencar.assistant.feature.dvr

import java.io.File

/**
 * Pure path / prune selection helpers (unit-tested).
 */
object DvrStorageMath {

    const val SUBDIR_DVR = "dvr"

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
        return name.startsWith("oaa_dvr_") && name.endsWith(".mp4")
    }

    data class PruneFile(
        val name: String,
        val lastModified: Long,
        val length: Long,
        val locked: Boolean,
        val active: Boolean,
    )

    /**
     * Returns names to delete for age then size policy (oldest unlocked first).
     * [nowMs] / [maxAgeDays] / [maxTotalMb] mirror [DvrController] policy.
     */
    fun pruneDeleteNames(
        files: List<PruneFile>,
        nowMs: Long,
        maxAgeDays: Int,
        maxTotalMb: Int,
    ): List<String> {
        val toDelete = linkedSetOf<String>()
        val cutoff = if (maxAgeDays > 0) nowMs - maxAgeDays * 86_400_000L else 0L
        if (cutoff > 0) {
            files.filter { !it.active && !it.locked && it.lastModified < cutoff }
                .forEach { toDelete += it.name }
        }
        val remaining = files.filter { !it.active && it.name !in toDelete }
        var total = remaining.sumOf { it.length }
        val cap = maxTotalMb.toLong() * 1024L * 1024L
        val unlocked = remaining.filter { !it.locked }.sortedBy { it.lastModified }
        for (f in unlocked) {
            if (total <= cap) break
            toDelete += f.name
            total -= f.length
        }
        return toDelete.toList()
    }
}

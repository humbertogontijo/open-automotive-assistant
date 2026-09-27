package cc.opencar.assistant.server.ota

import cc.opencar.assistant.apkdelta.ApkDelta
import cc.opencar.assistant.protocol.OaaOta
import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class Artifact(
    val sha256: String,
    val size: Long,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long?,
    val uploadedAtMs: Long,
)

/** OADP patch turning artifact [from] into [to]; served by its own [sha256] like an artifact. */
data class Delta(val from: String, val to: String, val sha256: String, val size: Long)

/**
 * Uploaded head-unit APKs under `<data>/artifacts`, addressed by SHA-256, plus deltas
 * between them under `artifacts/deltas` (built on first request, pruned with their APKs).
 */
class ArtifactStore(dataDir: File, private val keep: Int = 5) {
    private val dir = File(dataDir, "artifacts").also { it.mkdirs() }
    private val deltaDir = File(dir, "deltas").also { it.mkdirs() }
    private val gson = Gson()
    private val deltaLock = Any()

    /** Index entry for a `from`→`to` pair; [sha256] is null when no delta is worth sending. */
    private data class DeltaIndex(val sha256: String?, val size: Long)

    class TooLarge : Exception("artifact exceeds ${OaaOta.MAX_ARTIFACT_BYTES} bytes")

    /** Stream [input] to disk while hashing; enforces [OaaOta.MAX_ARTIFACT_BYTES]. */
    @Synchronized
    fun put(input: InputStream, packageName: String, versionName: String?, versionCode: Long?): Artifact {
        val tmp = File.createTempFile("upload-", ".part", dir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    if (size > OaaOta.MAX_ARTIFACT_BYTES) throw TooLarge()
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            require(size > 0) { "empty upload" }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val apk = apkFile(sha)
            if (!apk.exists()) check(tmp.renameTo(apk)) { "could not store artifact" }
            val meta = Artifact(sha, size, packageName, versionName, versionCode, System.currentTimeMillis())
            metaFile(sha).writeText(gson.toJson(meta))
            prune()
            return meta
        } finally {
            tmp.delete()
        }
    }

    fun get(sha: String): Artifact? {
        if (!OaaOta.isSha256(sha)) return null
        val meta = metaFile(sha)
        if (!meta.exists() || !apkFile(sha).exists()) return null
        return runCatching { gson.fromJson(meta.readText(), Artifact::class.java) }.getOrNull()
    }

    /** Download body for [sha]: an artifact APK or a delta patch. */
    fun file(sha: String): File? =
        get(sha)?.let { apkFile(it.sha256) } ?: sha.takeIf { OaaOta.isSha256(it) }?.let { patchFile(it) }?.takeIf { it.exists() }

    fun isDelta(sha: String): Boolean = get(sha) == null && file(sha) != null

    fun list(): List<Artifact> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { get(it.name.removeSuffix(".json")) }
            .sortedByDescending { it.uploadedAtMs }

    /**
     * Patch from stored artifact [from] to [to], built once and cached. Null when either APK
     * is gone, they cannot be diffed, or the patch exceeds [OaaOta.MAX_DELTA_PERCENT] of [to].
     * Blocking: call off the event loop.
     */
    fun delta(from: String, to: String): Delta? = synchronized(deltaLock) {
        if (from == to) return null
        val target = get(to) ?: return null
        val base = get(from)?.let { apkFile(it.sha256) } ?: return null
        val index = indexFile(from, to)
        val cached = runCatching { gson.fromJson(index.readText(), DeltaIndex::class.java) }.getOrNull()
        if (cached != null) {
            val sha = cached.sha256 ?: return null
            if (patchFile(sha).exists()) return Delta(from, to, sha, cached.size)
        }
        val tmp = File.createTempFile("delta-", ".part", deltaDir)
        try {
            val entry = runCatching { ApkDelta.diff(base, apkFile(to), tmp) }.getOrNull()
                ?.takeIf { tmp.length() * 100 <= target.size * OaaOta.MAX_DELTA_PERCENT }
                ?.let {
                    val sha = ApkDelta.sha256(tmp)
                    val patch = patchFile(sha)
                    if (!patch.exists()) check(tmp.renameTo(patch)) { "could not store delta" }
                    DeltaIndex(sha, patch.length())
                }
            index.writeText(gson.toJson(entry ?: DeltaIndex(null, 0)))
            entry?.sha256?.let { Delta(from, to, it, entry.size) }
        } finally {
            tmp.delete()
        }
    }

    private fun prune() {
        list().drop(keep).forEach {
            apkFile(it.sha256).delete()
            metaFile(it.sha256).delete()
        }
        synchronized(deltaLock) {
            deltaDir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().forEach { index ->
                val (from, to) = index.name.removeSuffix(".json").split("-").takeIf { it.size == 2 } ?: return@forEach
                if (apkFile(from).exists() && apkFile(to).exists()) return@forEach
                runCatching { gson.fromJson(index.readText(), DeltaIndex::class.java) }.getOrNull()
                    ?.sha256?.let { patchFile(it).delete() }
                index.delete()
            }
        }
    }

    private fun apkFile(sha: String) = File(dir, "$sha.apk")
    private fun metaFile(sha: String) = File(dir, "$sha.json")
    private fun patchFile(sha: String) = File(deltaDir, "$sha.oadp")
    private fun indexFile(from: String, to: String) = File(deltaDir, "$from-$to.json")
}

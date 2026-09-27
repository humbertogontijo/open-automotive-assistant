package cc.opencar.assistant.server.ota

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

/** Uploaded head-unit APKs under `<data>/artifacts`, addressed by SHA-256. */
class ArtifactStore(dataDir: File, private val keep: Int = 5) {
    private val dir = File(dataDir, "artifacts").also { it.mkdirs() }
    private val gson = Gson()

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

    fun file(sha: String): File? = get(sha)?.let { apkFile(it.sha256) }

    fun list(): List<Artifact> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { get(it.name.removeSuffix(".json")) }
            .sortedByDescending { it.uploadedAtMs }

    private fun prune() {
        list().drop(keep).forEach {
            apkFile(it.sha256).delete()
            metaFile(it.sha256).delete()
        }
    }

    private fun apkFile(sha: String) = File(dir, "$sha.apk")
    private fun metaFile(sha: String) = File(dir, "$sha.json")
}

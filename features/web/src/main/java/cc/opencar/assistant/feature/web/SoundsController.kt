package cc.opencar.assistant.feature.web

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Custom AVAS / lock sound files stored under app files.
 * OEM VHAL only exposes [esm_sound]/[esm_volume] ints — custom playback is app-side
 * until a vendor drop-in path is confirmed.
 */
class SoundsController(
    private val context: Context,
    private val prefs: SharedPreferences =
        context.getSharedPreferences("oaa_sounds", Context.MODE_PRIVATE),
) {
    enum class Kind(val id: String) {
        AVAS("avas"),
        LOCK("lock"),
        ;

        companion object {
            fun from(raw: String?): Kind? =
                entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }
        }
    }

    private var player: MediaPlayer? = null

    fun dir(kind: Kind): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "sounds/${kind.id}")
            .also { it.mkdirs() }

    fun list(kind: Kind): List<Map<String, Any?>> {
        val active = activeName(kind)
        val files = dir(kind).listFiles { f ->
            f.isFile && ALLOWED_EXT.any { f.name.endsWith(it, ignoreCase = true) }
        } ?: emptyArray()
        return files.sortedBy { it.name.lowercase() }.map { f ->
            mapOf(
                "name" to f.name,
                "size" to f.length(),
                "mtime" to f.lastModified(),
                "active" to (f.name == active),
            )
        }
    }

    fun activeName(kind: Kind): String? =
        prefs.getString(prefKey(kind), null)?.takeIf { it.isNotBlank() }

    fun activeFile(kind: Kind): File? {
        val name = activeName(kind) ?: return null
        return resolve(kind, name)
    }

    fun saveUpload(kind: Kind, originalName: String, bytes: ByteArray): Map<String, Any?> {
        val safe = sanitizeName(originalName) ?: return mapOf("ok" to false, "error" to "bad name")
        if (ALLOWED_EXT.none { safe.endsWith(it, ignoreCase = true) }) {
            return mapOf("ok" to false, "error" to "unsupported type (wav/mp3/ogg)")
        }
        if (bytes.size > MAX_BYTES) {
            return mapOf("ok" to false, "error" to "file too large")
        }
        val dest = File(dir(kind), safe)
        dest.writeBytes(bytes)
        if (activeName(kind) == null) setActive(kind, safe)
        return mapOf("ok" to true, "name" to safe, "size" to dest.length())
    }

    fun setActive(kind: Kind, name: String?): Map<String, Any?> {
        if (name.isNullOrBlank()) {
            prefs.edit().remove(prefKey(kind)).apply()
            return mapOf("ok" to true, "active" to null)
        }
        val file = resolve(kind, name) ?: return mapOf("ok" to false, "error" to "not found")
        prefs.edit().putString(prefKey(kind), file.name).apply()
        return mapOf("ok" to true, "active" to file.name)
    }

    fun delete(kind: Kind, name: String): Map<String, Any?> {
        val file = resolve(kind, name) ?: return mapOf("ok" to false, "error" to "not found")
        val ok = file.delete()
        if (ok && activeName(kind) == name) {
            prefs.edit().remove(prefKey(kind)).apply()
        }
        return mapOf("ok" to ok)
    }

    fun preview(kind: Kind, name: String? = null): Map<String, Any?> {
        val file = when {
            !name.isNullOrBlank() -> resolve(kind, name)
            else -> activeFile(kind)
        } ?: return mapOf("ok" to false, "error" to "no file")
        return try {
            stopPreview()
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { stopPreview() }
            mp.prepare()
            mp.start()
            player = mp
            mapOf("ok" to true, "name" to file.name)
        } catch (t: Throwable) {
            Log.w(TAG, "preview failed: ${t.message}")
            stopPreview()
            mapOf("ok" to false, "error" to (t.message ?: "play failed"))
        }
    }

    fun stopPreview() {
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "avas" to mapOf(
            "active" to activeName(Kind.AVAS),
            "files" to list(Kind.AVAS),
        ),
        "lock" to mapOf(
            "active" to activeName(Kind.LOCK),
            "files" to list(Kind.LOCK),
        ),
        "note" to "Custom files play via app MediaPlayer; OEM AVAS still uses esm_sound / esm_volume.",
    )

    private fun resolve(kind: Kind, name: String): File? {
        val safe = sanitizeName(name) ?: return null
        val f = File(dir(kind), safe)
        if (!f.isFile) return null
        if (!f.canonicalPath.startsWith(dir(kind).canonicalPath)) return null
        return f
    }

    private fun sanitizeName(raw: String): String? {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isBlank() || base.contains("..")) return null
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun prefKey(kind: Kind) = "active_${kind.id}"

    companion object {
        private const val TAG = "OaaSounds"
        private const val MAX_BYTES = 8 * 1024 * 1024
        private val ALLOWED_EXT = listOf(".wav", ".mp3", ".ogg", ".m4a")
    }
}

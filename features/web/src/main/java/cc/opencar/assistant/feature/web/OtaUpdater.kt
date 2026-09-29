package cc.opencar.assistant.feature.web

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.util.Log
import cc.opencar.assistant.apkdelta.ApkDelta
import cc.opencar.assistant.feature.debug.LogRingBuffer
import cc.opencar.assistant.feature.install.ApkInstaller
import cc.opencar.assistant.feature.install.InstallEvents
import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaOta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Applies a hub `ota_offer`: download the artifact with the node token (resuming
 * with `Range`), verify hash, package and signing certificate, then self-install.
 * When the offer carries a `delta` from the installed APK, the artifact is rebuilt
 * from that patch instead, falling back to the full download on any failure.
 * Success is not reported here — the hub confirms it from the next `hello`.
 */
internal class OtaUpdater(
    private val context: Context,
    private val installer: ApkInstaller,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val report: (JSONObject) -> Unit,
) {
    private val busy = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<Int, String>()

    /** An offer is downloading, verifying or installing. */
    val isBusy: Boolean get() = busy.get()

    init {
        scope.launch {
            InstallEvents.events.collect { e ->
                val rolloutId = sessions[e.sessionId] ?: return@collect
                when {
                    e.pendingUser -> status(rolloutId, OaaOta.STATE_PENDING_USER)
                    e.success -> sessions.remove(e.sessionId)
                    else -> {
                        sessions.remove(e.sessionId)
                        status(rolloutId, OaaOta.STATE_FAILED, error = e.message ?: "install failed (${e.status})")
                    }
                }
            }
        }
    }

    fun offer(payload: JSONObject, nodeBaseUrl: String, token: String) {
        val rolloutId = payload.optString("rolloutId")
        val sha = payload.optString("sha256").lowercase()
        val path = payload.optString("path")
        if (!OaaFrames.isCurrentVersion(payload) || !OaaOta.isSha256(sha) || !path.startsWith("/")) {
            status(rolloutId, OaaOta.STATE_FAILED, error = "invalid offer")
            return
        }
        if (payload.optString("package") != context.packageName) {
            status(rolloutId, OaaOta.STATE_FAILED, error = "package mismatch (${payload.optString("package")})")
            return
        }
        val base = nodeBaseUrl.trimEnd('/')
        val delta = payload.optJSONObject("delta")?.let { d ->
            val from = d.optString("from").lowercase()
            val patchSha = d.optString("sha256").lowercase()
            val patchPath = d.optString("path")
            if (OaaOta.isSha256(from) && OaaOta.isSha256(patchSha) && patchPath.startsWith("/")) {
                DeltaOffer(from, patchSha, d.optLong("size", -1), base + patchPath)
            } else {
                null
            }
        }
        if (!busy.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                apply(rolloutId, sha, payload.optLong("size", -1), base + path, token, delta)
            } catch (t: Throwable) {
                Log.w(TAG, "ota failed", t)
                status(rolloutId, OaaOta.STATE_FAILED, error = t.message ?: t.javaClass.simpleName)
            } finally {
                busy.set(false)
            }
        }
    }

    private class DeltaOffer(val from: String, val sha256: String, val size: Long, val url: String)

    private fun apply(rolloutId: String, sha: String, size: Long, url: String, token: String, delta: DeltaOffer?) {
        val dir = installer.installDir()
        val apk = File(dir, "ota-$sha.apk")
        if (!apk.exists() && delta != null) {
            try {
                applyDelta(rolloutId, sha, delta, apk, token)
            } catch (e: Exception) {
                Log.w(TAG, "ota delta failed, downloading the full APK", e)
                LogRingBuffer.append("OTA $rolloutId: delta failed (${e.message}), downloading the full APK")
            }
        }
        if (!apk.exists()) {
            LogRingBuffer.append("OTA $rolloutId: downloading $sha")
            val part = File(dir, "ota-$sha.apk.part")
            download(rolloutId, url, token, part, size)
            if (!part.renameTo(apk)) throw IOException("rename failed")
        }

        status(rolloutId, OaaOta.STATE_VERIFYING)
        val actual = installer.sha256(apk)
        if (actual != sha) {
            apk.delete()
            throw IOException("sha256 mismatch")
        }
        verifyPackage(apk)

        status(rolloutId, OaaOta.STATE_INSTALLING)
        val result = installer.install(apk, sha, selfUpdate = true)
        val sessionId = result.sessionId
        if (!result.ok || sessionId == null) throw IOException(result.message)
        sessions[sessionId] = rolloutId
        LogRingBuffer.append("OTA $rolloutId: install session $sessionId committed")
        dir.listFiles()?.filter { it.name.startsWith("ota-") && it != apk }?.forEach { it.delete() }
    }

    /** Rebuild [apk] (target [sha]) from the installed APK and the offered patch. */
    private fun applyDelta(rolloutId: String, sha: String, delta: DeltaOffer, apk: File, token: String) {
        val base = File(context.applicationInfo.sourceDir)
        if (installer.sha256(base) != delta.from) throw IOException("installed APK is not the delta base")
        val dir = apk.parentFile ?: throw IOException("no install dir")
        val patch = File(dir, "ota-${delta.sha256}.oadp")
        val rebuilt = File(dir, "ota-$sha.apk.delta")
        try {
            if (!patch.exists()) {
                LogRingBuffer.append("OTA $rolloutId: downloading delta ${delta.sha256} (${delta.size} bytes)")
                val part = File(dir, "ota-${delta.sha256}.oadp.part")
                download(rolloutId, delta.url, token, part, delta.size)
                if (!part.renameTo(patch)) throw IOException("rename failed")
            }
            if (installer.sha256(patch) != delta.sha256) throw IOException("delta sha256 mismatch")
            status(rolloutId, OaaOta.STATE_VERIFYING)
            ApkDelta.apply(base, patch, rebuilt)
            if (installer.sha256(rebuilt) != sha) throw IOException("rebuilt APK sha256 mismatch")
            if (!rebuilt.renameTo(apk)) throw IOException("rename failed")
            LogRingBuffer.append("OTA $rolloutId: rebuilt $sha from delta")
        } finally {
            patch.delete()
            rebuilt.delete()
        }
    }

    private fun download(rolloutId: String, url: String, token: String, part: File, size: Long) {
        var have = if (part.exists()) part.length() else 0L
        if (size > 0 && have >= size) {
            if (have == size) return
            part.delete()
            have = 0L
        }
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        http.newCall(req).execute().use { resp ->
            val append = resp.code == 206
            if (!resp.isSuccessful) throw IOException("download failed (${resp.code})")
            val body = resp.body ?: throw IOException("empty body")
            var done = if (append) have else 0L
            var lastPct = -1
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (size > 0) {
                            val pct = (done * 100 / size).toInt().coerceIn(0, 100)
                            if (pct / 10 != lastPct / 10) {
                                lastPct = pct
                                status(rolloutId, OaaOta.STATE_DOWNLOADING, progress = pct)
                            }
                        }
                    }
                }
            }
            if (size > 0 && done != size) throw IOException("short download ($done/$size)")
        }
    }

    /** The archive must be our own package, signed with our current certificate. */
    private fun verifyPackage(apk: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: throw IOException("not an APK")
        if (archive.packageName != context.packageName) throw IOException("package mismatch (${archive.packageName})")
        val ours = pm.getPackageInfo(context.packageName, flags)
        val theirs = archive.signingInfo?.apkContentsSigners.orEmpty().toSet()
        val mine = ours.signingInfo?.apkContentsSigners.orEmpty().toSet<Signature>()
        if (theirs.isEmpty() || theirs != mine) throw IOException("signing certificate mismatch")
    }

    private fun status(rolloutId: String, state: String, progress: Int? = null, error: String? = null) {
        val p = OaaFrames.versioned().put("rolloutId", rolloutId).put("state", state)
        if (progress != null) p.put("progress", progress)
        if (error != null) p.put("error", error)
        if (state == OaaOta.STATE_FAILED) LogRingBuffer.append("OTA $rolloutId failed: $error")
        report(p)
    }

    private companion object {
        const val TAG = "OaaOta"
    }
}

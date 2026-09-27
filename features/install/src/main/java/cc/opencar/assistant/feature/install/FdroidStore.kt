package cc.opencar.assistant.feature.install

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * F-Droid catalog client: search, package detail, download + hash verify, then community re-sign.
 */
class FdroidStore(
    private val context: Context,
    private val keys: CommunityKeyStore = CommunityKeyStore(context),
    private val installer: ApkInstaller = ApkInstaller(context),
) {
    data class SearchHit(
        val packageName: String,
        val name: String,
        val summary: String,
        val iconUrl: String?,
    )

    data class PackageDetail(
        val packageName: String,
        val name: String,
        val summary: String,
        val description: String,
        val iconUrl: String?,
        val suggestedVersionCode: Long?,
        val versions: List<Version>,
    ) {
        data class Version(
            val versionName: String,
            val versionCode: Long,
            val size: Long?,
            val apkName: String?,
            val hash: String?,
            val hashType: String?,
        )
    }

    data class InstallOutcome(
        val ok: Boolean,
        val message: String,
        val packageName: String? = null,
        val sha256: String? = null,
        val signedPath: String? = null,
    )

    fun search(query: String, limit: Int = 30): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return browse(limit)
        return searchApi(q, limit)
    }

    /**
     * Default browse when the store opens with an empty query.
     * Uses the public search API in parallel — never downloads index-v1.jar here.
     */
    fun browse(limit: Int = 30): List<SearchHit> {
        val queries = BROWSE_QUERIES
        val futures = queries.map { q ->
            CompletableFuture.supplyAsync {
                runCatching { searchApi(q, limit) }.getOrElse {
                    Log.w(TAG, "browse query '$q' failed: ${it.message}")
                    emptyList()
                }
            }
        }
        val seen = LinkedHashSet<String>()
        val out = ArrayList<SearchHit>(limit)
        for (future in futures) {
            val hits = runCatching { future.get(StoreHttp.TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS) }
                .getOrElse {
                    Log.w(TAG, "browse future failed: ${it.message}")
                    emptyList()
                }
            for (h in hits) {
                if (!seen.add(h.packageName)) continue
                out += h
                if (out.size >= limit) return out
            }
        }
        return out
    }

    private fun searchApi(query: String, limit: Int): List<SearchHit> {
        val url = "$SEARCH_API?q=${encode(query)}"
        val root = JSONObject(StoreHttp.get(url))
        val apps = root.optJSONArray("apps") ?: JSONArray()
        val out = ArrayList<SearchHit>(minOf(apps.length(), limit))
        for (i in 0 until apps.length()) {
            if (out.size >= limit) break
            val a = apps.optJSONObject(i) ?: continue
            val pkg = a.optString("packageName").ifBlank { a.optString("package_name") }
                .ifBlank { packageFromUrl(a.optString("url")) }
            if (pkg.isBlank()) continue
            val name = a.optString("name").ifBlank { pkg }
            val summary = a.optString("summary")
            val icon = a.optString("icon").takeIf { it.isNotBlank() }
            val iconUrl = when {
                icon == null -> null
                icon.startsWith("http") -> icon
                else -> "$REPO_BASE/icons-640/$icon"
            }
            out += SearchHit(pkg, name, summary, iconUrl)
        }
        return out
    }

    private fun packageFromUrl(url: String): String {
        // https://f-droid.org/en/packages/com.example.app  or …/packages/com.example.app/
        val marker = "/packages/"
        val idx = url.indexOf(marker)
        if (idx < 0) return ""
        return url.substring(idx + marker.length).trim('/').substringBefore('/')
    }

    fun detail(packageName: String): PackageDetail? {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return null
        val api = JSONObject(StoreHttp.get("$PACKAGES_API/$pkg"))
        val meta = loadIndexApp(pkg)
        val suggested = api.optLong("suggestedVersionCode", -1).takeIf { it >= 0 }
            ?: api.optJSONObject("package")?.optLong("suggestedVersionCode", -1)?.takeIf { it >= 0 }

        val versions = ArrayList<PackageDetail.Version>()
        val packages = api.optJSONArray("packages") ?: JSONArray()
        for (i in 0 until packages.length()) {
            val p = packages.optJSONObject(i) ?: continue
            val vc = p.optLong("versionCode", p.optLong("version_code", -1))
            if (vc < 0) continue
            versions += PackageDetail.Version(
                versionName = p.optString("versionName").ifBlank { p.optString("version_name") },
                versionCode = vc,
                size = p.optLong("size", -1).takeIf { it >= 0 },
                apkName = p.optString("apkName").ifBlank { p.optString("apk_name") }.takeIf { it.isNotBlank() },
                hash = p.optString("hash").takeIf { it.isNotBlank() },
                hashType = p.optString("hashType").ifBlank { p.optString("hash_type") }.takeIf { it.isNotBlank() },
            )
        }

        // Prefer index-v1 for apkName/hash when packages API is sparse
        if (meta != null) {
            val byCode = HashMap<Long, JSONObject>()
            val apks = meta.optJSONArray("_apks") ?: JSONArray()
            for (i in 0 until apks.length()) {
                val apk = apks.optJSONObject(i) ?: continue
                val vc = apk.optLong("versionCode", -1)
                if (vc >= 0) byCode[vc] = apk
            }
            if (versions.isEmpty()) {
                for ((vc, apk) in byCode.entries.sortedByDescending { it.key }) {
                    versions += PackageDetail.Version(
                        versionName = apk.optString("versionName"),
                        versionCode = vc,
                        size = apk.optLong("size", -1).takeIf { it >= 0 },
                        apkName = apk.optString("apkName").takeIf { it.isNotBlank() },
                        hash = apk.optString("hash").takeIf { it.isNotBlank() },
                        hashType = apk.optString("hashType").ifBlank { "sha256" },
                    )
                }
            } else {
                for (i in versions.indices) {
                    val v = versions[i]
                    val apk = byCode[v.versionCode] ?: continue
                    if (v.apkName.isNullOrBlank()) {
                        versions[i] = v.copy(
                            apkName = apk.optString("apkName").takeIf { it.isNotBlank() } ?: v.apkName,
                            hash = v.hash ?: apk.optString("hash").takeIf { it.isNotBlank() },
                            hashType = v.hashType
                                ?: apk.optString("hashType").ifBlank { "sha256" },
                            size = v.size ?: apk.optLong("size", -1).takeIf { it >= 0 },
                        )
                    }
                }
            }
        }

        val name = meta?.optString("name")?.takeIf { it.isNotBlank() }
            ?: api.optString("name").takeIf { it.isNotBlank() }
            ?: pkg
        val summary = meta?.optString("summary").orEmpty()
        val description = meta?.optString("description").orEmpty()
        val iconName = meta?.optString("icon")?.takeIf { it.isNotBlank() }
        val iconUrl = iconName?.let {
            if (it.startsWith("http")) it else "$REPO_BASE/icons-640/$it"
        }

        return PackageDetail(
            packageName = pkg,
            name = name,
            summary = summary,
            description = description,
            iconUrl = iconUrl,
            suggestedVersionCode = suggested,
            versions = versions.sortedByDescending { it.versionCode },
        )
    }

    fun install(packageName: String, versionCode: Long? = null): InstallOutcome {
        val detail = detail(packageName)
            ?: return InstallOutcome(false, "Package not found: $packageName", packageName)
        val version = when {
            versionCode != null -> detail.versions.firstOrNull { it.versionCode == versionCode }
            detail.suggestedVersionCode != null ->
                detail.versions.firstOrNull { it.versionCode == detail.suggestedVersionCode }
                    ?: detail.versions.firstOrNull()
            else -> detail.versions.firstOrNull()
        } ?: return InstallOutcome(false, "No versions available", packageName)

        val apkName = version.apkName
            ?: "${packageName}_${version.versionCode}.apk"
        val url = "$REPO_BASE/$apkName"
        val dir = installer.installDir()
        val raw = File(dir, "fdroid-$packageName-${version.versionCode}.apk")
        return try {
            StoreHttp.download(url, raw)
            val expected = version.hash
            if (!expected.isNullOrBlank() &&
                (version.hashType.isNullOrBlank() || version.hashType.equals("sha256", true))
            ) {
                val actual = StoreHttp.digestHex(raw)
                if (!actual.equals(expected, ignoreCase = true)) {
                    raw.delete()
                    return InstallOutcome(false, "SHA-256 mismatch", packageName, actual)
                }
            }
            val signed = File(dir, "fdroid-$packageName-${version.versionCode}_signed.apk")
            val sign = keys.resign(raw, signed)
            if (!sign.ok) {
                return InstallOutcome(false, "Re-sign failed: ${sign.message}", packageName)
            }
            val result = installer.install(signed)
            InstallOutcome(
                ok = result.ok,
                message = result.message,
                packageName = packageName,
                sha256 = result.sha256,
                signedPath = signed.absolutePath,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "install $packageName failed", t)
            InstallOutcome(false, t.message ?: "install failed", packageName)
        }
    }

    /** Cached index-v1.json extracted from index-v1.jar (unsigned read; JAR verify optional). */
    @Volatile private var indexCache: JSONObject? = null
    @Volatile private var indexCachedAt: Long = 0L

    private fun loadIndexApp(packageName: String): JSONObject? {
        val index = ensureIndex() ?: return null
        val apps = index.optJSONArray("apps") ?: return null
        var found: JSONObject? = null
        for (i in 0 until apps.length()) {
            val a = apps.optJSONObject(i) ?: continue
            if (a.optString("packageName") == packageName) {
                found = a
                break
            }
        }
        val packages = index.optJSONObject("packages") ?: return found
        val apks = packages.optJSONArray(packageName) ?: return found
        val copy = found ?: JSONObject().put("packageName", packageName)
        copy.put("_apks", apks)
        return copy
    }

    private fun ensureIndex(): JSONObject? {
        val now = System.currentTimeMillis()
        val cached = indexCache
        if (cached != null && now - indexCachedAt < INDEX_TTL_MS) return cached
        synchronized(this) {
            if (indexCache != null && now - indexCachedAt < INDEX_TTL_MS) return indexCache
            return try {
                val jarFile = File(context.cacheDir, "fdroid-index-v1.jar")
                StoreHttp.download("$REPO_BASE/index-v1.jar", jarFile)
                val json = ZipFile(jarFile).use { zip ->
                    val entry = zip.getEntry("index-v1.json")
                        ?: throw IllegalStateException("index-v1.json missing in jar")
                    zip.getInputStream(entry).bufferedReader().use { it.readText() }
                }
                val obj = JSONObject(json)
                indexCache = obj
                indexCachedAt = System.currentTimeMillis()
                obj
            } catch (t: Throwable) {
                Log.w(TAG, "index-v1 load failed", t)
                indexCache
            }
        }
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8.name())

    companion object {
        private const val TAG = "FdroidStore"
        const val REPO_BASE = "https://f-droid.org/repo"
        const val SEARCH_API = "https://search.f-droid.org/api/search_apps"
        const val PACKAGES_API = "https://f-droid.org/api/v1/packages"
        private val INDEX_TTL_MS = TimeUnit.HOURS.toMillis(6)

        /** Thematic queries for the empty-store landing page (search API, not full index). */
        private val BROWSE_QUERIES = listOf(
            "maps",
            "navigation",
            "browser",
            "music",
            "offline",
        )
    }
}

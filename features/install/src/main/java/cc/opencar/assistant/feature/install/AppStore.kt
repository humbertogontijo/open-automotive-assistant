package cc.opencar.assistant.feature.install

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Unified store: curated extras (featured / proprietary) + F-Droid browse/search.
 */
class AppStore(
    context: Context,
    private val keys: CommunityKeyStore = CommunityKeyStore(context),
    private val installer: ApkInstaller = ApkInstaller(context),
    private val fdroid: FdroidStore = FdroidStore(context, keys, installer),
    private val extras: ExtrasCatalog = ExtrasCatalog(context),
) {
    data class SearchHit(
        val packageName: String,
        val name: String,
        val summary: String,
        val iconUrl: String?,
        val source: String,
        val installReady: Boolean = true,
    )

    fun search(query: String, limit: Int = 30, includeFdroid: Boolean = true): List<SearchHit> {
        val q = query.trim()
        val out = ArrayList<SearchHit>()
        val seen = HashSet<String>()

        // Curated extras always lead the list (featured when browsing, match when searching).
        val extraHits = if (q.isEmpty()) extras.featured() else extras.search(q)
        for (e in extraHits) {
            if (!seen.add(e.packageName)) continue
            out += SearchHit(
                packageName = e.packageName,
                name = e.name,
                summary = e.summary,
                iconUrl = extras.iconUrl(e),
                source = "extras",
                installReady = e.installReady(),
            )
            if (out.size >= limit) return out
        }

        if (!includeFdroid) return out

        // F-Droid: browse via search API when empty; search when typing.
        // Never block extras on F-Droid failures / timeouts.
        val fdroidHits = runCatching {
            if (q.isEmpty()) fdroid.browse(limit) else fdroid.search(q, limit)
        }.getOrElse {
            Log.w(TAG, "fdroid search failed: ${it.message}")
            emptyList()
        }
        for (h in fdroidHits) {
            if (!seen.add(h.packageName)) continue
            out += SearchHit(
                packageName = h.packageName,
                name = h.name,
                summary = h.summary,
                iconUrl = h.iconUrl,
                source = "fdroid",
                installReady = true,
            )
            if (out.size >= limit) break
        }
        return out
    }

    fun detail(packageName: String): Map<String, Any?>? {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return null
        val extra = extras.findByPackage(pkg)
        if (extra != null) {
            val resolved = runCatching { extras.resolveLatest(extra) }.getOrNull()
            val versions = if (resolved != null) {
                listOf(
                    mapOf(
                        "versionName" to resolved.versionName,
                        "versionCode" to resolved.versionCode,
                        "size" to null,
                        "apkName" to resolved.apkName,
                    ),
                )
            } else {
                emptyList()
            }
            return mapOf(
                "ok" to true,
                "packageName" to extra.packageName,
                "name" to extra.name,
                "summary" to extra.summary,
                "description" to extra.summary,
                "iconUrl" to extras.iconUrl(extra),
                "suggestedVersionCode" to (resolved?.versionCode),
                "versions" to versions,
                "source" to "extras",
                "extrasId" to extra.id,
                "installReady" to extra.installReady(),
                "sourceDetail" to extra.source,
            )
        }
        val d = fdroid.detail(pkg) ?: return null
        return mapOf(
            "ok" to true,
            "packageName" to d.packageName,
            "name" to d.name,
            "summary" to d.summary,
            "description" to d.description,
            "iconUrl" to d.iconUrl,
            "suggestedVersionCode" to d.suggestedVersionCode,
            "versions" to d.versions.map {
                mapOf(
                    "versionName" to it.versionName,
                    "versionCode" to it.versionCode,
                    "size" to it.size,
                    "apkName" to it.apkName,
                )
            },
            "source" to "fdroid",
            "installReady" to true,
        )
    }

    fun install(packageName: String, versionCode: Long? = null): FdroidStore.InstallOutcome {
        val pkg = packageName.trim()
        val extra = extras.findByPackage(pkg)
        if (extra != null) {
            return installExtra(extra)
        }
        return fdroid.install(pkg, versionCode)
    }

    private fun installExtra(app: ExtrasCatalog.ExtraApp): FdroidStore.InstallOutcome {
        return try {
            val resolved = extras.resolveLatest(app)
            val dir = installer.installDir()
            val raw = File(dir, "extra-${app.id}-${resolved.versionCode}.apk")
            StoreHttp.download(resolved.url, raw)
            if (!resolved.hash.isNullOrBlank()) {
                val type = resolved.hashType?.lowercase().orEmpty()
                val actual = when {
                    type.isBlank() || type == "sha256" -> StoreHttp.digestHex(raw)
                    type == "md5" -> StoreHttp.digestHex(raw, "MD5")
                    else -> null
                }
                if (actual != null && !actual.equals(resolved.hash, ignoreCase = true)) {
                    raw.delete()
                    return FdroidStore.InstallOutcome(
                        false,
                        "${type.ifBlank { "sha256" }.uppercase()} mismatch",
                        app.packageName,
                        actual,
                    )
                }
            }
            val signed = File(dir, "extra-${app.id}-${resolved.versionCode}_signed.apk")
            val sign = keys.resign(raw, signed)
            if (!sign.ok) {
                return FdroidStore.InstallOutcome(false, "Re-sign failed: ${sign.message}", app.packageName)
            }
            val result = installer.install(signed)
            FdroidStore.InstallOutcome(
                ok = result.ok,
                message = result.message,
                packageName = app.packageName,
                sha256 = result.sha256,
                signedPath = signed.absolutePath,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "install extra ${app.id}", t)
            FdroidStore.InstallOutcome(false, t.message ?: "install failed", app.packageName)
        }
    }

    companion object {
        private const val TAG = "AppStore"
    }
}

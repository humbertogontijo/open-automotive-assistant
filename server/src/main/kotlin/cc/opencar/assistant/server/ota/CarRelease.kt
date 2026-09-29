package cc.opencar.assistant.server.ota

import cc.opencar.assistant.apkdelta.ApkDelta
import cc.opencar.assistant.protocol.OaaBuild
import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaOta
import cc.opencar.assistant.server.HubConfig
import cc.opencar.assistant.server.NodeRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * The car app built for this hub's release. Its [OaaOta.RELEASE_MANIFEST] and APK are fetched
 * from the release folder (GitHub Releases unless `OAA_CAR_RELEASE_URL` says otherwise) into the
 * [ArtifactStore]. Cars running an older build hear about it in `ota_available` after `hello`, then
 * install it with `ota_request` ([OaaOta.MODE_ASK]) or get the rollout straight away ([OaaOta.MODE_AUTO]).
 *
 * Offers carry a delta whenever the store holds the car's installed APK, so before offering the hub
 * also fetches the release the car runs (matched by hash): the hub downloads full APKs, the car only
 * the patch.
 */
class CarRelease(
    dataDir: File,
    private val artifacts: ArtifactStore,
    private val rollouts: OtaRollouts,
    private val registry: NodeRegistry,
    val mode: String = HubConfig.carUpdates,
    /** Release folder; `{version}` is replaced by a version name. */
    private val releaseUrl: String = HubConfig.carReleaseUrl,
    private val open: (String) -> InputStream = ::httpGet,
) {
    val url: String = urlFor(OaaBuild.VERSION)
    private val tmpDir = dataDir
    private val stateFile = File(dataDir, "car-release.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Installed-APK hash → its release fetched as a delta base (null when the car runs no published build). */
    private val bases = ConcurrentHashMap<String, Deferred<Artifact?>>()
    @Volatile private var sha256: String? = null
    @Volatile private var error: String? = null
    @Volatile private var fetching = false
    private var job: Job? = null

    /** The fetched release, while its APK is still in the store. */
    val artifact: Artifact? get() = sha256?.let { artifacts.get(it) }

    init {
        restore()
    }

    /** Fetch the release in the background until it lands, then announce it to connected cars. */
    fun start() {
        if (mode == OaaOta.MODE_OFF || job != null) return
        job = scope.launch {
            var wait = RETRY_MIN_MS
            while (isActive && artifact == null) {
                val fetched = runCatching { fetch() }
                    .onFailure {
                        error = it.message ?: it.javaClass.simpleName
                        log.warning("car release $url: $error (retry in ${wait / 60_000} min)")
                    }
                    .getOrNull()
                if (fetched != null) {
                    log.info("car release ${fetched.versionName} (${fetched.versionCode}) ready from $url")
                    registry.all().filter { registry.isOnline(it.id) }.forEach { onHello(it.id) }
                    break
                }
                delay(wait)
                wait = (wait * 3).coerceAtMost(RETRY_MAX_MS)
            }
        }
    }

    /** This hub's release into the store. */
    suspend fun fetch(): Artifact {
        fetching = true
        try {
            val stored = fetchRelease(url)
            sha256 = stored.sha256
            error = null
            persist()
            return stored
        } finally {
            fetching = false
        }
    }

    /**
     * Manifest plus APK from release folder [folder] into the store (skipped when already there).
     * [expectSha] rejects a release whose APK is not that build before downloading it.
     */
    private suspend fun fetchRelease(folder: String, expectSha: String? = null): Artifact = withContext(Dispatchers.IO) {
        val manifest = JSONObject(open("$folder/${OaaOta.RELEASE_MANIFEST}").use { it.readBytes().toString(Charsets.UTF_8) })
        val sha = manifest.getString("sha256").lowercase()
        val file = manifest.getString("file")
        val pkg = manifest.getString("package")
        if (!OaaOta.isSha256(sha)) throw IOException("manifest sha256 invalid")
        if (!APK_NAME.matches(file)) throw IOException("manifest file name invalid")
        if (expectSha != null && sha != expectSha) throw IOException("release $folder is another build")
        artifacts.get(sha) ?: download("$folder/$file", sha).let { apk ->
            try {
                apk.inputStream().use {
                    artifacts.put(
                        it,
                        pkg,
                        manifest.optString("versionName").takeIf { v -> v.isNotEmpty() },
                        manifest.optLong("versionCode", -1).takeIf { v -> v >= 0 },
                    )
                }
            } finally {
                apk.delete()
            }
        }
    }

    private fun download(url: String, sha: String): File {
        val tmp = File.createTempFile("car-release-", ".part", tmpDir)
        try {
            open(url).use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        size += n
                        if (size > OaaOta.MAX_ARTIFACT_BYTES) throw IOException("APK exceeds ${OaaOta.MAX_ARTIFACT_BYTES} bytes")
                        out.write(buf, 0, n)
                    }
                }
            }
            if (ApkDelta.sha256(tmp) != sha) throw IOException("APK sha256 does not match the manifest")
            return tmp
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Car connected: tell it the policy and any newer build, then prepare the delta in the background
     * (announced again with `downloadSize`) and, in auto mode, start its rollout once.
     */
    suspend fun onHello(nodeId: String): Job? {
        val update = artifact?.takeIf { isUpdateFor(nodeId, it) }
        announce(nodeId, update, null)
        if (update == null) return null
        return scope.launch {
            prepareDelta(nodeId, update)?.let { announce(nodeId, update, it.size) }
            if (mode == OaaOta.MODE_AUTO && !rollouts.targets(nodeId, update.sha256)) {
                rollouts.create(update, listOf(nodeId))
            }
        }
    }

    /** Car asked to install the announced build. Anything stale gets a fresh `ota_available` instead. */
    fun request(nodeId: String, payload: JSONObject): Job = scope.launch {
        val update = artifact
        val ok = mode != OaaOta.MODE_OFF && update != null && OaaFrames.isCurrentVersion(payload) &&
            payload.optString("sha256").equals(update.sha256, ignoreCase = true) && isUpdateFor(nodeId, update)
        if (!ok) {
            onHello(nodeId)?.join()
            return@launch
        }
        prepareDelta(nodeId, update!!)
        if (rollouts.targets(nodeId, update.sha256, activeOnly = true)) {
            rollouts.reoffer(nodeId)
        } else {
            rollouts.create(update, listOf(nodeId))
        }
    }

    private suspend fun announce(nodeId: String, update: Artifact?, downloadSize: Long?) {
        val node = registry.session(nodeId) ?: return
        val payload = OaaFrames.versioned().put("mode", mode)
        if (update != null) {
            payload
                .put("sha256", update.sha256)
                .put("size", update.size)
                .put("versionName", update.versionName)
                .put("versionCode", update.versionCode)
            if (downloadSize != null) payload.put("downloadSize", downloadSize)
        }
        node.send(OaaFrames.frame(OaaFrames.OTA_AVAILABLE, payload))
    }

    /** Patch from the car's installed APK to [update], fetching that APK's release first when the store lacks it. */
    private suspend fun prepareDelta(nodeId: String, update: Artifact): Delta? {
        val app = registry.get(nodeId)?.app ?: return null
        val installed = app.apkSha256?.lowercase()?.takeIf { OaaOta.isSha256(it) } ?: return null
        if (artifacts.get(installed) == null) {
            val version = app.versionName ?: return null
            val base = bases.computeIfAbsent(installed) {
                scope.async {
                    runCatching { fetchRelease(urlFor(version), expectSha = installed) }
                        .onFailure { log.info("no delta base for $version ($installed): ${it.message}") }
                        .getOrNull()
                }
            }
            base.await() ?: return null
        }
        return withContext(Dispatchers.IO) { artifacts.delta(installed, update.sha256) }
    }

    /** Same package as the car runs and a higher version code. */
    private fun isUpdateFor(nodeId: String, a: Artifact): Boolean {
        val app = registry.get(nodeId)?.app ?: return false
        val target = a.versionCode ?: return false
        return app.packageName == a.packageName &&
            !a.sha256.equals(app.apkSha256, ignoreCase = true) &&
            target > (app.versionCode ?: 0L)
    }

    fun toMap(): Map<String, Any?> {
        val a = artifact
        val state = when {
            mode == OaaOta.MODE_OFF -> "off"
            a != null -> "ready"
            fetching -> "fetching"
            else -> "unavailable"
        }
        return mapOf(
            "mode" to mode,
            "state" to state,
            "versionName" to a?.versionName,
            "versionCode" to a?.versionCode,
            "url" to url,
            "error" to error.takeIf { a == null },
        )
    }

    private fun urlFor(version: String) = releaseUrl.replace("{version}", version).trimEnd('/')

    private fun restore() {
        val saved = runCatching { JSONObject(stateFile.readText()) }.getOrNull() ?: return
        if (saved.optString("url") == url) sha256 = saved.optString("sha256").takeIf { OaaOta.isSha256(it) }
    }

    private fun persist() {
        stateFile.writeText(JSONObject().put("url", url).put("sha256", sha256).toString())
    }

    companion object {
        const val DEFAULT_URL = "https://github.com/humbertogontijo/open-automotive-assistant/releases/download/v{version}"
        private const val RETRY_MIN_MS = 5 * 60_000L
        private const val RETRY_MAX_MS = 6 * 60 * 60_000L
        private val APK_NAME = Regex("^[A-Za-z0-9._-]+\\.apk$")
        private val log: Logger = Logger.getLogger("oaa.hub")

        private val http: HttpClient by lazy {
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build()
        }

        private fun httpGet(url: String): InputStream {
            val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(10)).GET().build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if (res.statusCode() !in 200..299) {
                res.body().close()
                throw IOException("HTTP ${res.statusCode()} for $url")
            }
            return res.body()
        }
    }
}

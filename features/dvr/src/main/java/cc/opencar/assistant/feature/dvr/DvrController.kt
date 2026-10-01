package cc.opencar.assistant.feature.dvr

import android.content.Context
import android.content.SharedPreferences
import android.hardware.camera2.CameraManager
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import cc.opencar.assistant.api.CameraSource
import cc.opencar.assistant.api.VehicleSession
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-camera DVR: every camera encodes on its own (Camera2 → hardware H.264),
 * recordings are groups of per-camera MP4s, live viewers hold only the cameras
 * they show, and exports burn the capture time in on demand.
 */
class DvrController(
    private val context: Context,
    private val session: VehicleSession,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val recording = AtomicBoolean(false)
    /** Wall-clock start of the current DVR session. */
    private var recordingStartedAt: Long = 0L
    /** Wall-clock start of the open segment group (rotates with each group). */
    @Volatile private var segmentJobStartedAt: Long = 0L
    @Volatile private var mode: String = MODE_OFF
    @Volatile private var storageId: String = STORAGE_APP
    @Volatile private var maxTotalMb: Int = DEFAULT_MAX_TOTAL_MB
    @Volatile private var maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS
    @Volatile private var storageNote: String? = null

    @Volatile private var cachedTargets: List<Map<String, Any?>>? = null
    @Volatile private var cachedTargetsAt: Long = 0L

    /** Held while cameras open or the probe runs; Camera2 cannot serve both at once. */
    private val cameraLock = Any()
    private val pipeline = SharedH264Pipeline(context)
    private val hub = SharedCameraHub(pipeline, ::hubCameras, cameraLock)

    /** Cameras the recorder holds a hub seat on. */
    @Volatile private var recordingRoles: Set<String> = emptySet()
    /** Cameras held by the local preview endpoint. */
    @Volatile private var previewRoles: Set<String> = emptySet()
    /** Hub seats held by live (WebRTC) viewers, per role. */
    private val liveSeats = ConcurrentHashMap<String, AtomicInteger>()

    /** Open segment group (`oaa_dvr_<stamp>`) and its per-camera files. */
    @Volatile private var activeGroup: String? = null
    @Volatile private var activeFiles: Map<String, File> = emptyMap()

    private var writer: Future<*>? = null
    private val writerExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "oaa-dvr-writer").apply { isDaemon = true }
    }
    /** Exports share the hardware codecs with four live encoders; run one at a time. */
    private val exportLock = Any()
    @Volatile private var camera2ProbeReport: Map<String, Any?>? = null
    var lastError: String? = null
        private set

    /** Debounce wake/sleep flaps (aligned with shortcut screen debounce). */
    @Volatile private var lastWakeSleepAtMs: Long = 0L
    @Volatile private var lastWakeSleepWasWake: Boolean? = null
    @Volatile private var wakeSleepDebounceSkips: Long = 0L
    @Volatile private var lastCutRole: String? = null
    @Volatile private var lastCutSegments: Int = 0
    @Volatile private var lastCutBytes: Long = 0L

    init {
        storageId = prefs.getString(KEY_STORAGE, STORAGE_APP) ?: STORAGE_APP
        maxTotalMb = prefs.getInt(KEY_MAX_TOTAL_MB, DEFAULT_MAX_TOTAL_MB).coerceIn(256, 65536)
        maxAgeDays = prefs.getInt(KEY_MAX_AGE_DAYS, DEFAULT_MAX_AGE_DAYS).coerceAtLeast(0)
        val savedMode = prefs.getString(KEY_MODE, MODE_OFF) ?: MODE_OFF
        // Segment is transient; only DVR persists across restarts.
        mode = if (savedMode == MODE_DVR) MODE_DVR else MODE_OFF
        ensureStorageMounted()
        writerExec.execute { runCamera2Probe() }
    }

    private fun runCamera2Probe() {
        synchronized(cameraLock) {
            if (hub.anyRunning()) {
                camera2ProbeReport = mapOf("skipped" to "cameras already streaming")
                return
            }
            runCatching {
                val probe = Camera2ConcurrentProbe(context)
                camera2ProbeReport = probe.statusMap(probe.probe(hubCameras()))
            }.onFailure {
                camera2ProbeReport = mapOf("error" to it.message)
                Log.w(TAG, "Camera2 probe failed: ${it.message}")
            }
        }
    }

    fun cameras(): List<CameraSource> = session.cameras().ifEmpty {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.mapIndexed { i, id -> CameraSource(id, "Cam $i", id) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Cameras keyed by file-safe role, in grid order (front, right, rear, left, then the rest). */
    private fun hubCameras(): List<SharedH264Pipeline.Camera> {
        val used = HashSet<String>()
        return cameras().mapIndexed { i, src ->
            var role = DvrStorageMath.sanitizeRole(src.role, i)
            if (!used.add(role)) role = "cam$i".also { used.add(it) }
            SharedH264Pipeline.Camera(role, src.cameraId)
        }.sortedBy { ROLE_ORDER.indexOf(it.role).let { i -> if (i < 0) ROLE_ORDER.size else i } }
    }

    /** Camera roles in grid order. */
    fun cameraRoles(): List<String> = hubCameras().map { it.role }

    /** Camera2 ids currently streaming. */
    fun runningCameraIds(): List<String> {
        val running = pipeline.runningRoles().toSet()
        return hubCameras().filter { it.role in running }.map { it.cameraId }
    }

    fun isRecording(): Boolean = recording.get()

    fun mode(): String = mode

    fun storageTargets(forceRefresh: Boolean = false): List<Map<String, Any?>> {
        val now = System.currentTimeMillis()
        val cached = cachedTargets
        if (!forceRefresh && cached != null && now - cachedTargetsAt < STORAGE_CACHE_MS) {
            return cached
        }
        val out = mutableListOf<Map<String, Any?>>()
        // Roots without trailing dvr/ — [dvrDir] appends DvrStorageMath.SUBDIR_DVR once.
        val app = context.getExternalFilesDir(null)?.also { it.mkdirs() }
            ?: File(context.filesDir, "external-dvr").also { it.mkdirs() }
        out += targetMap(
            id = STORAGE_APP,
            labelKey = "cameras.storage.app",
            label = "App storage",
            dir = app,
            kind = "app",
        )
        val primary = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        runCatching {
            val dir = File(primary, "OpenAutomotiveAssistant").also { it.mkdirs() }
            out += targetMap(
                id = STORAGE_PRIMARY,
                labelKey = "cameras.storage.primary",
                label = "Internal shared",
                dir = dir,
                kind = "primary",
            )
        }
        try {
            val sm = context.getSystemService(StorageManager::class.java)
            sm.storageVolumes.forEachIndexed { i, vol ->
                if (vol.isPrimary) return@forEachIndexed
                val desc = vol.getDescription(context) ?: "vol$i"
                val state = vol.state
                val path = volumePath(vol)
                val usb = looksLikeUsb(desc, vol)
                if (path != null && state == Environment.MEDIA_MOUNTED) {
                    val dir = File(path, "OpenAutomotiveAssistant")
                    val created = runCatching { dir.mkdirs(); true }.getOrDefault(false)
                    val writable = created && dir.canWrite()
                    out += targetMap(
                        id = "vol_$i",
                        labelKey = if (usb) "cameras.storage.usb" else "cameras.storage.sd",
                        label = desc,
                        dir = dir,
                        kind = if (usb) "usb" else "sd",
                        writableOverride = writable,
                        available = writable,
                    )
                } else if (usb || isRemovableNonPrimary(vol)) {
                    // Known removable / USB volume that is not mounted — show disabled.
                    out += mapOf(
                        "id" to "vol_$i",
                        "labelKey" to if (usb) "cameras.storage.usb" else "cameras.storage.sd",
                        "label" to desc,
                        "path" to (path?.absolutePath ?: ""),
                        "kind" to if (usb) "usb" else "sd",
                        "writable" to false,
                        "available" to false,
                        "totalBytes" to 0L,
                        "freeBytes" to 0L,
                        "usableBytes" to 0L,
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "storageVolumes: ${t.message}")
        }
        // Always expose a USB slot so users know flash drives are supported.
        if (out.none { it["kind"] == "usb" }) {
            out += mapOf(
                "id" to STORAGE_USB_PLACEHOLDER,
                "labelKey" to "cameras.storage.usb.none",
                "label" to "USB / Flash",
                "path" to "",
                "kind" to "usb",
                "writable" to false,
                "available" to false,
                "totalBytes" to 0L,
                "freeBytes" to 0L,
                "usableBytes" to 0L,
            )
        }
        cachedTargets = out
        cachedTargetsAt = now
        return out
    }

    /** Flat volume list for Android section (same StatFs fields). */
    fun volumeStats(forceRefresh: Boolean = false): List<Map<String, Any?>> =
        storageTargets(forceRefresh)

    fun setStorage(id: String?): Boolean {
        val targets = storageTargets(forceRefresh = true)
        val match = targets.firstOrNull { it["id"] == id } ?: targets.firstOrNull() ?: return false
        storageId = match["id"] as String
        prefs.edit().putString(KEY_STORAGE, storageId).apply()
        storageNote = null
        if (match["writable"] != true) {
            storageNote = "Storage not writable"
            return false
        }
        writerExec.execute { prune() }
        return true
    }

    fun setPolicy(maxTotalMb: Int?, maxAgeDays: Int?): Map<String, Any?> {
        if (maxTotalMb != null) {
            this.maxTotalMb = maxTotalMb.coerceIn(256, 65536)
            prefs.edit().putInt(KEY_MAX_TOTAL_MB, this.maxTotalMb).apply()
        }
        if (maxAgeDays != null) {
            this.maxAgeDays = maxAgeDays.coerceAtLeast(0)
            prefs.edit().putInt(KEY_MAX_AGE_DAYS, this.maxAgeDays).apply()
        }
        writerExec.execute { prune() }
        return status()
    }

    /**
     * Set recording mode.
     * - [MODE_OFF]: stop writing
     * - [MODE_DVR]: enable continuous DVR (persisted; wake/sleep lifecycle)
     */
    fun setMode(next: String?): Map<String, Any?> {
        val m = when (next?.lowercase(Locale.US)) {
            MODE_DVR -> MODE_DVR
            else -> MODE_OFF
        }
        when (m) {
            MODE_OFF -> {
                mode = MODE_OFF
                prefs.edit().putString(KEY_MODE, MODE_OFF).apply()
                stop()
            }
            MODE_DVR -> {
                mode = MODE_DVR
                prefs.edit().putString(KEY_MODE, MODE_DVR).apply()
                if (!recording.get()) {
                    startInternal()
                }
            }
        }
        return mapOf("ok" to true, "mode" to mode, "recording" to recording.get(), "status" to status())
    }

    /** ACC/boot wake: start only when DVR mode is enabled. */
    fun onVehicleWake(source: String = "wake") {
        if (mode != MODE_DVR && prefs.getString(KEY_MODE, MODE_OFF) != MODE_DVR) return
        if (!allowWakeSleepTransition(wake = true, source = source)) return
        mode = MODE_DVR
        if (!recording.get()) {
            Log.i(TAG, "DVR auto-start source=$source")
            startInternal()
        }
    }

    /** Screen-off / sleep: stop only when in DVR mode. */
    fun onVehicleSleep(source: String = "sleep") {
        if (mode != MODE_DVR) return
        if (!allowWakeSleepTransition(wake = false, source = source)) return
        if (recording.get()) {
            Log.i(TAG, "DVR auto-stop source=$source")
            stop()
        }
    }

    private fun allowWakeSleepTransition(wake: Boolean, source: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = lastWakeSleepWasWake
        val elapsed = now - lastWakeSleepAtMs
        if (prev != null && prev != wake && elapsed < WAKE_SLEEP_DEBOUNCE_MS) {
            wakeSleepDebounceSkips++
            Log.i(
                TAG,
                "DVR wake/sleep debounced source=$source wake=$wake elapsedMs=$elapsed skips=$wakeSleepDebounceSkips",
            )
            return false
        }
        lastWakeSleepAtMs = now
        lastWakeSleepWasWake = wake
        return true
    }

    /** Storage root (app / primary / USB). Continuous files live under [dvrDir]. */
    fun outputDir(): File {
        ensureStorageMounted()
        val path = storageTargets().firstOrNull { it["id"] == storageId }?.get("path") as? String
        val dir = if (path != null) {
            File(path)
        } else {
            context.getExternalFilesDir(null) ?: File(context.filesDir, "external-dvr")
        }
        dir.mkdirs()
        return dir
    }

    fun dvrDir(): File = DvrStorageMath.dvrDirUnder(outputDir()).also { it.mkdirs() }

    // --- live ----------------------------------------------------------------

    /** Keep every camera warm for local viewers; idempotent. */
    @Synchronized
    fun startPreview(): Boolean {
        if (previewRoles.isNotEmpty()) return previewRoles.any { hub.isRunning(it) }
        val got = hub.acquire(cameraRoles())
        if (got.isEmpty()) {
            lastError = hub.lastError() ?: lastError ?: "Cameras failed"
            return false
        }
        previewRoles = got
        return true
    }

    @Synchronized
    fun stopPreview() {
        val held = previewRoles
        previewRoles = emptySet()
        if (held.isNotEmpty()) hub.release(held)
    }

    /**
     * Seats for a live viewer on [roles]. Returns the roles now streaming; pair them
     * with [releaseLive].
     */
    fun acquireLive(roles: Collection<String>): Set<String> {
        val got = hub.acquire(roles.distinct())
        got.forEach { liveSeats.getOrPut(it) { AtomicInteger() }.incrementAndGet() }
        if (got.size < roles.distinct().size) lastError = hub.lastError() ?: lastError
        return got
    }

    fun releaseLive(roles: Collection<String>) {
        val drop = roles.filter { role ->
            val n = liveSeats[role] ?: return@filter false
            n.getAndUpdate { if (it > 0) it - 1 else 0 } > 0
        }
        if (drop.isNotEmpty()) hub.release(drop)
    }

    /** Release every live seat (WebRTC shutdown). */
    fun releaseAllLive() {
        val drop = ArrayList<String>()
        liveSeats.forEach { (role, n) -> repeat(n.getAndSet(0)) { drop += role } }
        if (drop.isNotEmpty()) hub.release(drop)
    }

    fun addLiveTap(role: String, tap: CameraEncoderSession.SampleTap) = pipeline.addTap(role, tap)

    fun removeLiveTap(role: String, tap: CameraEncoderSession.SampleTap) = pipeline.removeTap(role, tap)

    fun requestLiveKeyFrame(role: String) = pipeline.requestKeyFrame(role)

    fun liveParameterSets(role: String): ByteArray? = pipeline.parameterSetsAnnexB(role)

    fun liveFps(role: String): Int = pipeline.fps(role)

    /** Encoded size of [role], or null while that camera is down. */
    fun liveVideoSize(role: String): Pair<Int, Int>? =
        pipeline.session(role)?.takeIf { it.isRunning() }?.let { it.width() to it.height() }

    fun previewStatus(): Map<String, Any?> = mapOf(
        "previewRunning" to hub.anyRunning(),
        "previewRoles" to previewRoles.toList(),
        "liveSeats" to liveSeats.mapValues { it.value.get() }.filterValues { it > 0 },
        "runningRoles" to pipeline.runningRoles(),
    )

    // --- recording -----------------------------------------------------------

    private fun startInternal(): Boolean {
        if (recording.get()) return true
        writer?.let { prev ->
            recording.set(false)
            runCatching { prev.get(WRITER_STOP_WAIT_MS, TimeUnit.MILLISECONDS) }
            if (!prev.isDone) {
                // A new writeLoop would queue behind the stuck one on the single-thread executor.
                lastError = "previous recording still finalizing"
                Log.w(TAG, "start: previous writer still running")
                return false
            }
            writer = null
        }
        ensureStorageMounted()
        val roles = cameraRoles()
        if (roles.isEmpty()) {
            lastError = "No cameras"
            return false
        }
        val held = hub.acquire(roles)
        if (held.isEmpty()) {
            lastError = hub.lastError() ?: lastError ?: "Cameras failed"
            return false
        }
        recordingRoles = held
        recording.set(true)
        recordingStartedAt = System.currentTimeMillis()
        lastError = if (held.size < roles.size) "cameras not recording: ${(roles - held).joinToString()}" else null
        writer = writerExec.submit { writeLoop() }
        return true
    }

    private fun writeLoop() {
        var lastReviveAt = System.currentTimeMillis()
        var lastRetryAt = lastReviveAt
        try {
            while (recording.get()) {
                val now = System.currentTimeMillis()
                var regroup = false
                if (now - lastReviveAt >= REVIVE_CHECK_MS) {
                    lastReviveAt = now
                    sealDeadFiles()
                    if (hub.reviveHeld().isNotEmpty()) regroup = true
                    if (now - lastRetryAt >= RETRY_MISSING_MS) {
                        lastRetryAt = now
                        val missing = cameraRoles() - recordingRoles
                        if (missing.isNotEmpty()) {
                            val got = hub.acquire(missing)
                            if (got.isNotEmpty()) {
                                recordingRoles = recordingRoles + got
                                regroup = true
                            }
                        }
                    }
                }
                rotateIfNeeded(now, regroup)
                try {
                    Thread.sleep(ROTATE_CHECK_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } catch (t: Throwable) {
            lastError = t.message
            Log.w(TAG, "writeLoop: ${t.message}")
        } finally {
            recording.set(false)
            closeActiveGroup("stop")
            prune()
            val held = recordingRoles
            recordingRoles = emptySet()
            if (held.isNotEmpty()) hub.release(held)
        }
    }

    /** Opens the first group, or rotates on time / size / camera set changes. */
    @Synchronized
    private fun rotateIfNeeded(now: Long, regroup: Boolean) {
        if (activeGroup != null) {
            val maxBytes = activeFiles.keys.maxOfOrNull { pipeline.session(it)?.bytesWritten() ?: 0L } ?: 0L
            val hitLimit = regroup || maxBytes >= SEGMENT_MAX_BYTES || now - segmentJobStartedAt >= SEGMENT_MAX_MS
            if (!hitLimit) return
            closeActiveGroup(if (regroup) "cameras" else "rotate")
            prune()
        }
        openGroup()
    }

    @Synchronized
    private fun openGroup(): Boolean {
        val dir = dvrDir()
        val base = DvrStorageMath.groupBase(uniqueStamp(dir))
        val files = pipeline.openGroup(dir, base)
        if (files.isEmpty()) {
            lastError = pipeline.lastError ?: "no camera to record"
            return false
        }
        activeGroup = base
        activeFiles = files
        segmentJobStartedAt = System.currentTimeMillis()
        return true
    }

    /** Stamp not used by any file in [dir] yet (bumped by a second on clashes). */
    private fun uniqueStamp(dir: File): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val existing = dir.list()?.toSet().orEmpty()
        var t = System.currentTimeMillis()
        repeat(60) {
            val stamp = fmt.format(Date(t))
            val prefix = DvrStorageMath.groupBase(stamp) + "_"
            if (existing.none { it.startsWith(prefix) }) return stamp
            t += 1_000L
        }
        return fmt.format(Date(t))
    }

    /** Finalize files of cameras that died mid-group so they keep their meta. */
    @Synchronized
    private fun sealDeadFiles() {
        val group = activeGroup ?: return
        val dead = activeFiles.keys.filter { !pipeline.isRunning(it) }
        if (dead.isEmpty()) return
        for (role in dead) {
            pipeline.session(role)?.closeMp4()?.let { writeSegmentMeta(it, group) }
        }
        activeFiles = activeFiles - dead.toSet()
    }

    /** Finalize every open file of the group and write each one's meta. No-op when none is open. */
    @Synchronized
    private fun closeActiveGroup(reason: String) {
        val group = activeGroup ?: return
        val closed = pipeline.closeGroup()
        activeGroup = null
        activeFiles = emptyMap()
        segmentJobStartedAt = 0L
        closed.forEach { writeSegmentMeta(it, group) }
        Log.i(TAG, "group closed reason=$reason group=$group files=${closed.size}")
    }

    private fun writeSegmentMeta(rec: CameraEncoderSession.RecordedFile, group: String) {
        runCatching {
            val role = DvrStorageMath.roleOf(rec.file.name) ?: ""
            val start = rec.firstCaptureUtcMs.takeIf { it > 0L }
                ?: (rec.file.lastModified() - rec.durationMs)
            val end = start + rec.durationMs
            metaFileFor(rec.file).writeText(
                "{\"role\":\"$role\",\"group\":\"$group\",\"durationMs\":${rec.durationMs}," +
                    "\"startUtcMs\":$start,\"endUtcMs\":$end,\"format\":\"mp4\"}\n",
            )
        }.onFailure {
            Log.w(TAG, "meta write failed for ${rec.file.name}: ${it.message}")
            lastError = "meta write: ${it.message}"
        }
    }

    fun stop() {
        recording.set(false)
        val w = writer
        // Wait for writeLoop finally (close + meta) before returning.
        if (w != null) {
            runCatching { w.get(WRITER_STOP_WAIT_MS, TimeUnit.MILLISECONDS) }
                .onFailure { Log.w(TAG, "stop wait: ${it.message}") }
            // Kept while still running so the next start waits for it instead of overlapping.
            if (w.isDone) writer = null
        }
        // Fallback if the writer never finalized (cancel / crash).
        closeActiveGroup("stop-fallback")
        recordingStartedAt = 0L
    }

    // --- timeline ------------------------------------------------------------

    /** Closed per-camera files with startUtcMs meta, grouped (open group excluded). */
    fun timelineGroups(): List<DvrTimelineMath.Group> {
        val active = activeFilePaths()
        val files = recordingFiles().mapNotNull { f ->
            if (f.canonicalPath in active) return@mapNotNull null
            val role = DvrStorageMath.roleOf(f.name) ?: return@mapNotNull null
            val start = readMetaLong(f, "startUtcMs") ?: return@mapNotNull null
            val dur = readMetaLong(f, "durationMs")?.takeIf { it > 0 } ?: durationMsFor(f)
            if (dur <= 0L) return@mapNotNull null
            DvrStorageMath.groupOf(f.name) to DvrTimelineMath.CameraFile(role, f.name, start, dur)
        }
        return DvrTimelineMath.groups(files)
    }

    fun timeline(): Map<String, Any?> {
        val closed = timelineGroups()
        val now = System.currentTimeMillis()
        val group = activeGroup
        val activeStart = segmentJobStartedAt.takeIf { recording.get() && group != null && it > 0L }
        val locked = lockedGroups()
        val segs = ArrayList<Map<String, Any?>>(closed.size + 1)
        closed.forEach { g ->
            segs += mapOf(
                "id" to g.id,
                "startUtcMs" to g.startUtcMs,
                "endUtcMs" to g.endUtcMs,
                "durationMs" to g.durationMs,
                "active" to false,
                "locked" to (g.id in locked),
                "cameras" to g.files.associate { f ->
                    f.role to mapOf(
                        "name" to f.name,
                        "startUtcMs" to f.startUtcMs,
                        "endUtcMs" to f.endUtcMs,
                        "durationMs" to f.durationMs,
                    )
                },
            )
        }
        if (activeStart != null && group != null) {
            val dur = (now - activeStart).coerceAtLeast(0L)
            segs += mapOf(
                "id" to group,
                "startUtcMs" to activeStart,
                "endUtcMs" to now,
                "durationMs" to dur,
                "active" to true,
                "locked" to (group in locked),
                "cameras" to activeFiles.mapValues { (_, f) ->
                    mapOf("name" to f.name, "startUtcMs" to activeStart, "endUtcMs" to now, "durationMs" to dur)
                },
            )
        }
        val rangeStart = listOfNotNull(closed.firstOrNull()?.startUtcMs, activeStart).minOrNull()
        val rangeEnd = if (activeStart != null) now else closed.maxOfOrNull { it.endUtcMs }
        return mapOf(
            "ok" to true,
            "roles" to cameraRoles(),
            "segments" to segs,
            "rangeStartUtcMs" to rangeStart,
            "rangeEndUtcMs" to rangeEnd,
            "recording" to recording.get(),
            "mode" to mode,
        )
    }

    /**
     * Resolve wall-clock [atUtcMs] to a segment group and each camera's file + offset.
     * With [role], snaps among groups that have that camera and also puts its
     * `name` / `offsetMs` at the top level. Seeking into the open group seals it
     * first so it becomes playable; gaps snap to the nearest recorded edge.
     */
    fun resolvePlayAt(atUtcMs: Long, role: String? = null): Map<String, Any?> {
        maybeSealActiveForSeek(atUtcMs)
        val groups = timelineGroups().let { all -> if (role == null) all else all.filter { it.file(role) != null } }
        if (groups.isEmpty()) {
            // Still recording but nothing sealed yet (too short) → stay live.
            if (recording.get()) {
                return mapOf("ok" to true, "live" to true, "atUtcMs" to atUtcMs)
            }
            return mapOf("ok" to false, "error" to "no recordings")
        }
        val snap = DvrTimelineMath.resolvePlayAt(groups.map { it.asSegment() }, atUtcMs)
            ?: return mapOf("ok" to false, "error" to "no recordings")
        val g = groups[snap.index]
        val wall = snap.wallUtcMs
        val cameras = g.files.associate { f ->
            f.role to mapOf(
                "name" to f.name,
                "offsetMs" to DvrTimelineMath.offsetInFile(f, wall),
                "startUtcMs" to f.startUtcMs,
                "endUtcMs" to f.endUtcMs,
                "durationMs" to f.durationMs,
            )
        }
        val out = linkedMapOf<String, Any?>(
            "ok" to true,
            "group" to g.id,
            "atUtcMs" to wall,
            "startUtcMs" to g.startUtcMs,
            "endUtcMs" to g.endUtcMs,
            "durationMs" to g.durationMs,
            "cameras" to cameras,
        )
        if (role != null) {
            cameras[role]?.let { cam ->
                out["role"] = role
                out["name"] = cam["name"]
                out["offsetMs"] = cam["offsetMs"]
                out["fileStartUtcMs"] = cam["startUtcMs"]
                out["fileDurationMs"] = cam["durationMs"]
            }
        }
        return out
    }

    /**
     * Finalize the open group when the user seeks into its wall-clock range, so
     * the writer loop opens the next one and the sealed MP4s become playable.
     */
    @Synchronized
    private fun maybeSealActiveForSeek(atUtcMs: Long) {
        if (!recording.get()) return
        val started = segmentJobStartedAt
        if (activeGroup == null || started <= 0L) return
        val now = System.currentTimeMillis()
        if (atUtcMs < started || atUtcMs > now + 1_000L) return
        if (now - started < 1_000L) return
        closeActiveGroup("seek-seal")
        if (recording.get()) openGroup()
    }

    fun recordingFile(name: String): File? {
        if (!DvrStorageMath.isDvrRecordingName(name)) return null
        val root = outputDir().canonicalFile
        val f = File(dvrDir(), name)
        if (!f.isFile) return null
        val canon = runCatching { f.canonicalFile }.getOrNull() ?: return null
        if (!canon.path.startsWith(root.path)) return null
        return canon
    }

    /**
     * Delete closed DVR files (and their meta/locks). Skips the open group.
     * @return count deleted
     */
    fun clearRecordings(includeLocked: Boolean = true): Map<String, Any?> {
        val active = activeFilePaths()
        val locked = lockedGroups()
        var deleted = 0
        var skippedLocked = 0
        var skippedActive = 0
        recordingFiles().forEach { f ->
            if (f.canonicalPath in active) {
                skippedActive++
                return@forEach
            }
            if (!includeLocked && DvrStorageMath.groupOf(f.name) in locked) {
                skippedLocked++
                return@forEach
            }
            lockFileFor(f).delete()
            metaFileFor(f).delete()
            if (f.delete()) deleted++
        }
        // Orphan meta/locks for missing files
        metaDir().listFiles()?.forEach { m ->
            val n = m.name
            if (n.endsWith(".meta") || n.endsWith(".lock")) {
                val base = n.removeSuffix(".meta").removeSuffix(".lock")
                if (recordingFile(base) == null) m.delete()
            }
        }
        return mapOf(
            "ok" to true,
            "deleted" to deleted,
            "skippedLocked" to skippedLocked,
            "skippedActive" to skippedActive,
            "status" to status(),
        )
    }

    /** Lock or unlock a whole segment group; [name] is a group id or any member file name. */
    fun setLocked(name: String, locked: Boolean): Boolean {
        val group = DvrStorageMath.groupOf(name)
        val members = recordingFiles().filter { DvrStorageMath.groupOf(it.name) == group }
        if (members.isEmpty()) return false
        return members.all { f ->
            val lock = lockFileFor(f)
            if (locked) {
                runCatching { lock.writeText("locked\n"); true }.getOrDefault(false)
            } else {
                !lock.exists() || lock.delete()
            }
        }
    }

    // --- export --------------------------------------------------------------

    data class CutResult(
        val file: File,
        val downloadName: String,
        val durationMs: Long,
    )

    /**
     * Export [role]'s wall-clock [fromUtcMs, toUtcMs] into a temp MP4 with the capture
     * time burned in (re-encoded on demand). Seals the open group first when the range
     * overlaps it. At most [MAX_CUT_MS] long. Caller deletes [CutResult.file].
     */
    fun cutWallClockToTemp(
        role: String,
        fromUtcMs: Long,
        toUtcMs: Long,
        onProgress: ((doneMs: Long, totalMs: Long) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): CutResult {
        if (toUtcMs <= fromUtcMs) error("invalid range")
        if (toUtcMs - fromUtcMs > MAX_CUT_MS) error("clip longer than ${MAX_CUT_MS / 60_000} min")
        maybeSealActiveForSeek(toUtcMs)
        val parts = DvrTimelineMath.cutRangesForRole(timelineGroups(), role, fromUtcMs, toUtcMs)
        if (parts.isEmpty()) error("no recordings in range for $role")
        val dir = dvrDir()
        val ranges = parts.map { (f, mediaFrom, mediaTo) ->
            TimestampBurnTranscoder.Range(File(dir, f.name), mediaFrom, mediaTo, f.startUtcMs)
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(fromUtcMs))
        val downloadName = "oaa_clip_${stamp}_$role.mp4"
        val out = File(context.cacheDir, "oaa_cut_${stamp}_${role}_${Thread.currentThread().id}.mp4")
        try {
            val durationMs = synchronized(exportLock) {
                TimestampBurnTranscoder.transcode(
                    ranges,
                    out,
                    TimeZone.getDefault(),
                    onProgress?.let { cb -> TimestampBurnTranscoder.Progress { done, total -> cb(done, total) } },
                    isCancelled,
                )
            }
            if (durationMs <= 0L || !out.isFile || out.length() < 32) {
                out.delete()
                error("cut produced empty file")
            }
            lastCutRole = role
            lastCutSegments = parts.size
            lastCutBytes = out.length()
            Log.i(TAG, "cut ok role=$role files=${parts.size} bytes=${out.length()} durMs=$durationMs")
            return CutResult(out, downloadName, durationMs)
        } catch (t: Throwable) {
            runCatching { out.delete() }
            throw t
        }
    }

    fun prune() {
        val active = activeFilePaths()
        val all = recordingFiles()
        if (all.isEmpty()) return
        val inputs = all.map { f ->
            DvrStorageMath.PruneFile(
                name = f.name,
                lastModified = f.lastModified(),
                length = f.length(),
                locked = isLocked(f),
                active = f.canonicalPath in active,
            )
        }
        val names = DvrStorageMath.pruneDeleteNames(
            inputs,
            System.currentTimeMillis(),
            maxAgeDays,
            maxTotalMb,
        )
        for (name in names) {
            val f = all.firstOrNull { it.name == name } ?: continue
            Log.i(TAG, "prune ${f.name}")
            lockFileFor(f).delete()
            metaFileFor(f).delete()
            f.delete()
        }
    }

    /** The fields the UI shows (mode, storage, usage, errors); cheap enough to poll every few seconds. */
    fun summary(): Map<String, Any?> {
        val targets = storageTargets()
        val selected = targets.firstOrNull { it["id"] == storageId }
        val usage = usageOnCurrentStorage()
        return mapOf(
            "recording" to recording.get(),
            "mode" to mode,
            "storageId" to storageId,
            "storages" to targets,
            "storageNote" to storageNote,
            "policy" to mapOf("maxTotalMb" to maxTotalMb, "maxAgeDays" to maxAgeDays),
            "usageBytes" to usage.first,
            "usageCount" to usage.second,
            "selectedFreeBytes" to selected?.get("freeBytes"),
            "selectedTotalBytes" to selected?.get("totalBytes"),
            "selectedUsableBytes" to selected?.get("usableBytes"),
            "lastError" to lastError,
        )
    }

    fun status(): Map<String, Any?> {
        val byRole = hubCameras().associateBy { it.cameraId }
        val rec = recording.get()
        return summary() + mapOf(
            "format" to "h264",
            "roles" to cameraRoles(),
            "stream" to hub.status() + mapOf(
                "camera2Probe" to camera2ProbeReport,
                "source" to "cameras",
            ),
            "cameras" to cameras().map { src ->
                val role = byRole[src.cameraId]?.role
                val s = role?.let { pipeline.session(it) }
                mapOf(
                    "id" to src.cameraId,
                    "entityId" to src.id,
                    "label" to src.label,
                    "role" to role,
                    "running" to (s?.isRunning() == true),
                    "width" to s?.width()?.takeIf { it > 0 },
                    "height" to s?.height()?.takeIf { it > 0 },
                    "fps" to s?.measuredFps(),
                    "encoder" to s?.codecName(),
                    "recording" to (rec && role != null && role in activeFiles),
                )
            },
            "outputDir" to outputDir().absolutePath,
            "dvrDir" to dvrDir().absolutePath,
            "activeGroup" to activeGroup,
            "activeFiles" to activeFiles.mapValues { it.value.name },
            "recordingRoles" to recordingRoles.toList(),
            "wakeSleepDebounceSkips" to wakeSleepDebounceSkips,
            "lastCutRole" to lastCutRole,
            "lastCutSegments" to lastCutSegments,
            "lastCutBytes" to lastCutBytes,
            "maxCutMs" to MAX_CUT_MS,
            "segmentBytes" to if (rec) pipeline.groupBytes() else 0L,
            "segmentElapsedMs" to if (rec && segmentJobStartedAt > 0) {
                System.currentTimeMillis() - segmentJobStartedAt
            } else {
                0L
            },
            // Session wall-clock (does not reset when DVR rotates files).
            "elapsedMs" to if (rec && recordingStartedAt > 0) {
                System.currentTimeMillis() - recordingStartedAt
            } else {
                null
            },
            "recordingStartedAt" to recordingStartedAt.takeIf { rec && it > 0 },
            "segmentMaxBytes" to SEGMENT_MAX_BYTES,
            "segmentMaxMs" to SEGMENT_MAX_MS,
            "preview" to previewStatus(),
        )
    }

    private fun activeFilePaths(): Set<String> =
        activeFiles.values.mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.toSet()

    private fun lockedGroups(): Set<String> =
        recordingFiles().filter { isLocked(it) }.map { DvrStorageMath.groupOf(it.name) }.toSet()

    private fun usageOnCurrentStorage(): Pair<Long, Int> {
        val files = recordingFiles()
        return files.sumOf { it.length() } to files.size
    }

    /** DVR files under dvr/ only (requires startUtcMs for timeline). */
    private fun recordingFiles(): List<File> {
        val out = ArrayList<File>()
        dvrDir().listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            if (DvrStorageMath.isDvrRecordingName(f.name)) out += f
        }
        return out
    }

    private fun durationMsFor(file: File): Long {
        readMetaLong(file, "durationMs")?.let { if (it > 0) return it }
        return extractMp4DurationMs(file) ?: 0L
    }

    private fun extractMp4DurationMs(file: File): Long? {
        if (!file.name.endsWith(".mp4") || !file.isFile) return null
        return runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(file.absolutePath)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
            } finally {
                runCatching { r.release() }
            }
        }.getOrNull()
    }

    private fun readMetaLong(file: File, key: String): Long? {
        val meta = metaFileFor(file)
        if (!meta.isFile) return null
        val text = runCatching { meta.readText() }.getOrNull() ?: return null
        val re = Regex("\"$key\"\\s*:\\s*(\\d+)")
        return re.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    /** App-private meta — Movies/ volumes reject non-media sidecars (EPERM). */
    private fun metaFileFor(f: File): File = File(metaDir(), f.name + ".meta")

    private fun metaDir(): File =
        File(context.filesDir, "dvr-meta").also { it.mkdirs() }

    private fun lockFileFor(f: File): File {
        return File(metaDir(), f.name + ".lock")
    }

    private fun isLocked(f: File): Boolean = lockFileFor(f).isFile

    private fun ensureStorageMounted() {
        val targets = storageTargets(forceRefresh = true)
        val match = targets.firstOrNull { it["id"] == storageId }
        if (match == null || match["writable"] != true) {
            storageNote = if (match == null) "Storage unavailable; using app" else "Storage not writable; using app"
            storageId = STORAGE_APP
            prefs.edit().putString(KEY_STORAGE, STORAGE_APP).apply()
        }
    }

    private fun targetMap(
        id: String,
        labelKey: String,
        label: String,
        dir: File,
        kind: String,
        writableOverride: Boolean? = null,
        available: Boolean? = null,
    ): Map<String, Any?> {
        val space = statFs(dir)
        val writable = writableOverride ?: dir.canWrite()
        return mapOf(
            "id" to id,
            "labelKey" to labelKey,
            "label" to label,
            "path" to dir.absolutePath,
            "kind" to kind,
            "writable" to writable,
            "available" to (available ?: writable),
            "totalBytes" to space[0],
            "freeBytes" to space[1],
            "usableBytes" to space[2],
        )
    }

    private fun isRemovableNonPrimary(vol: StorageVolume): Boolean {
        return try {
            !vol.isPrimary && vol.isRemovable
        } catch (_: Throwable) {
            false
        }
    }

    private fun statFs(dir: File): LongArray {
        return try {
            val s = StatFs(dir.absolutePath)
            longArrayOf(s.totalBytes, s.freeBytes, s.availableBytes)
        } catch (_: Throwable) {
            longArrayOf(0L, 0L, 0L)
        }
    }

    private fun volumePath(vol: StorageVolume): File? = vol.directory

    private fun looksLikeUsb(desc: String, vol: StorageVolume): Boolean {
        val d = desc.lowercase(Locale.US)
        if (d.contains("usb") || d.contains("flash") || d.contains("otg") || d.contains("pendrive")) {
            return true
        }
        // Removable non-emulated volumes are often USB on HUs.
        return try {
            !vol.isEmulated && vol.isRemovable
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val TAG = "OaaDvr"
        private const val PREFS = "oaa_dvr"
        private const val KEY_STORAGE = "dvr_storage_id"
        private const val KEY_MODE = "dvr_mode"
        private const val KEY_MAX_TOTAL_MB = "dvr_max_total_mb"
        private const val KEY_MAX_AGE_DAYS = "dvr_max_age_days"

        const val STORAGE_APP = "app"
        const val STORAGE_PRIMARY = "primary"
        const val STORAGE_USB_PLACEHOLDER = "usb"
        const val MODE_OFF = "off"
        const val MODE_DVR = "dvr"
        const val KIND_DVR = "dvr"
        /** Longest export; each one is a full decode + re-encode on the head unit. */
        const val MAX_CUT_MS = 10L * 60L * 1000L

        private val ROLE_ORDER = listOf("front", "right", "rear", "left")
        private const val STORAGE_CACHE_MS = 30_000L
        private const val DEFAULT_MAX_TOTAL_MB = 2048
        private const val DEFAULT_MAX_AGE_DAYS = 0
        /** Per camera file. */
        private const val SEGMENT_MAX_BYTES = 100L * 1024L * 1024L
        private const val SEGMENT_MAX_MS = 5L * 60L * 1000L
        private const val ROTATE_CHECK_MS = 1_000L
        private const val WRITER_STOP_WAIT_MS = 20_000L
        private const val REVIVE_CHECK_MS = 5_000L
        private const val RETRY_MISSING_MS = 30_000L
        private const val WAKE_SLEEP_DEBOUNCE_MS = 5_000L
    }
}

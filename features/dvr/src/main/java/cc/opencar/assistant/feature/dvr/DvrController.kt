package cc.opencar.assistant.feature.dvr

import android.content.Context
import android.content.SharedPreferences
import android.hardware.camera2.CameraManager
import android.os.Build
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
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class DvrController(
    private val context: Context,
    private val session: VehicleSession,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val recording = AtomicBoolean(false)
    private var activeCameraId: String? = null
    private var activeFile: File? = null
    /** Wall-clock start of the current DVR session. */
    private var recordingStartedAt: Long = 0L
    /** Wall-clock start of the current output file (rotates with each file). */
    private var segmentJobStartedAt: Long = 0L
    private val segmentBytes = AtomicLong(0L)
    @Volatile private var mode: String = MODE_OFF
    @Volatile private var storageId: String = STORAGE_APP
    @Volatile private var maxTotalMb: Int = DEFAULT_MAX_TOTAL_MB
    @Volatile private var maxAgeDays: Int = DEFAULT_MAX_AGE_DAYS
    @Volatile private var storageNote: String? = null

    @Volatile private var cachedTargets: List<Map<String, Any?>>? = null
    @Volatile private var cachedTargetsAt: Long = 0L

    private val singlePreview = CameraPreviewSession(context)
    private val mosaic = MosaicPreviewSession(singlePreview)
    private val hub = SharedMosaicHub(mosaic) {
        ensureMosaicStarted()
    }
    /** Extra hub seat held while the DVR writer is running. */
    private var recordingHubHeld = false
    /** Extra hub seat held while a live preview / HLS client is active. */
    private var previewHubHeld = false
    /** Hub seats held by remote (WebRTC) live viewers. */
    private val remoteLiveSeats = java.util.concurrent.atomic.AtomicInteger(0)
    private val liveTaps = java.util.concurrent.CopyOnWriteArraySet<SharedH264Pipeline.SampleTap>()
    private var mosaicWriter: Future<*>? = null
    private val writerExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "oaa-dvr-writer").apply { isDaemon = true }
    }
    private var h264: SharedH264Pipeline? = null
    @Volatile private var streamFormat: String = "off"
    @Volatile private var camera2ProbeReport: Map<String, Any?>? = null
    var lastError: String? = null
        private set

    /** Debounce wake/sleep flaps (aligned with shortcut screen debounce). */
    @Volatile private var lastWakeSleepAtMs: Long = 0L
    @Volatile private var lastWakeSleepWasWake: Boolean? = null
    @Volatile private var wakeSleepDebounceSkips: Long = 0L
    @Volatile private var lastCutSegments: Int = 0
    @Volatile private var lastCutBytes: Long = 0L

    init {
        storageId = prefs.getString(KEY_STORAGE, STORAGE_APP) ?: STORAGE_APP
        maxTotalMb = prefs.getInt(KEY_MAX_TOTAL_MB, DEFAULT_MAX_TOTAL_MB).coerceIn(256, 65536)
        maxAgeDays = prefs.getInt(KEY_MAX_AGE_DAYS, DEFAULT_MAX_AGE_DAYS).coerceAtLeast(0)
        mosaic.onStopped = {
            h264?.stop()
            h264 = null
            streamFormat = "off"
        }
        val savedMode = prefs.getString(KEY_MODE, MODE_OFF) ?: MODE_OFF
        // Segment is transient; only DVR persists across restarts.
        mode = if (savedMode == MODE_DVR) MODE_DVR else MODE_OFF
        ensureStorageMounted()
        writerExec.execute { runCamera2Probe() }
    }

    private fun ensureMosaicStarted(): Boolean {
        val ids = cameras().map { it.cameraId }
        if (ids.isEmpty()) {
            lastError = "No cameras"
            return false
        }
        if (!mosaic.start(ids)) {
            lastError = mosaic.lastError ?: "Mosaic start failed"
            return false
        }
        if (!startH264()) {
            mosaic.stop()
            return false
        }
        return true
    }

    private fun startH264(): Boolean {
        if (h264?.isRunning() == true) return true
        val pipe = SharedH264Pipeline(
            mosaic.mosaicWidth(),
            mosaic.mosaicHeight(),
            mosaic.sourceFps(),
        )
        liveTaps.forEach(pipe::addTap)
        val ids = mosaic.cameraIds()
        val ok = pipe.start(ids.size) { textures ->
            singlePreview.rebindPreviewTextures(ids, textures)
        }
        if (!ok) {
            lastError = pipe.lastError ?: "H264 GL pipeline failed"
            Log.e(TAG, "H264 start failed: $lastError")
            pipe.stop()
            return false
        }
        h264 = pipe
        streamFormat = "h264"
        lastError = null
        return true
    }

    private fun runCamera2Probe() {
        runCatching {
            val report = Camera2ConcurrentProbe(context).probe()
            camera2ProbeReport = Camera2ConcurrentProbe(context).statusMap(report)
        }.onFailure {
            camera2ProbeReport = mapOf("error" to it.message)
            Log.w(TAG, "Camera2 probe failed: ${it.message}")
        }
    }

    fun fmp4InitSegment(): ByteArray? = h264?.fmp4Init()
    fun fmp4Fragment(seq: Long): ByteArray? = h264?.fmp4Fragment(seq)
    fun hlsPlaylist(): String? = h264?.hlsPlaylist()
    fun hlsPlaylistBlocking(msn: Long?, timeoutMs: Long = 3_000L): String? =
        h264?.hlsPlaylistBlocking(msn, timeoutMs)

    fun cameras(): List<CameraSource> = session.cameras().ifEmpty {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.mapIndexed { i, id -> CameraSource(id, "Cam $i", id) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Camera2 ids currently open in the mosaic hub. */
    fun openCameraIds(): List<String> =
        if (mosaic.isRunning()) mosaic.cameraIds() else emptyList()

    fun isMosaicRunning(): Boolean = mosaic.isRunning()

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

    fun frameIntervalMs(): Long = mosaic.frameIntervalMs()

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

    fun startPreview(cameraId: String? = null): Boolean {
        // Merged H.264 mosaic only — single-camera JPEG preview is gone.
        if (cameraId != null) {
            Log.i(TAG, "startPreview(cam=$cameraId): using merged mosaic")
        }
        if (previewHubHeld) return hub.isRunning()
        if (!hub.acquire()) {
            lastError = hub.lastError() ?: lastError ?: "Mosaic failed"
            return false
        }
        previewHubHeld = true
        return true
    }

    fun stopPreview() {
        if (previewHubHeld) {
            previewHubHeld = false
            hub.release()
        }
    }

    /** Seat for a remote WebRTC live viewer; pair every true result with [releaseRemoteLive]. */
    fun acquireRemoteLive(): Boolean {
        if (!hub.acquire()) {
            lastError = hub.lastError() ?: lastError ?: "Mosaic failed"
            return false
        }
        remoteLiveSeats.incrementAndGet()
        return true
    }

    fun releaseRemoteLive() {
        if (remoteLiveSeats.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) hub.release()
    }

    /** Release every remote viewer seat and tap (WebRTC shutdown). */
    fun releaseAllRemoteLive() {
        repeat(remoteLiveSeats.getAndSet(0)) { hub.release() }
        liveTaps.forEach { h264?.removeTap(it) }
        liveTaps.clear()
    }

    fun addLiveTap(tap: SharedH264Pipeline.SampleTap) {
        liveTaps.add(tap)
        h264?.addTap(tap)
    }

    fun removeLiveTap(tap: SharedH264Pipeline.SampleTap) {
        liveTaps.remove(tap)
        h264?.removeTap(tap)
    }

    fun requestLiveKeyFrame() {
        h264?.requestKeyFrame()
    }

    fun liveParameterSets(): ByteArray? = h264?.parameterSetsAnnexB()

    /** Encoded mosaic size, or null while the pipeline is down. */
    fun liveVideoSize(): Pair<Int, Int>? = h264?.let { it.videoWidth() to it.videoHeight() }

    fun liveFps(): Int = h264?.fps() ?: mosaic.sourceFps()

    fun previewStatus(): Map<String, Any?> {
        if (hub.isRunning()) {
            return hub.status() + mapOf(
                "merged" to true,
                "previewRunning" to true,
                "previewHeld" to previewHubHeld,
                "format" to streamFormat,
                "h264" to h264?.status(),
            )
        }
        return mapOf(
            "previewRunning" to false,
            "previewHeld" to previewHubHeld,
            "merged" to true,
            "format" to streamFormat,
            "h264" to h264?.status(),
        )
    }

    private fun startInternal(): Boolean {
        if (recording.get()) return true
        mosaicWriter?.let { prev ->
            recording.set(false)
            runCatching { prev.get() }
            mosaicWriter = null
        }
        ensureStorageMounted()
        if (!recordingHubHeld) {
            if (!hub.acquire()) {
                lastError = hub.lastError() ?: lastError ?: "Mosaic failed"
                return false
            }
            recordingHubHeld = true
        }
        if (h264?.isRunning() != true) {
            lastError = lastError ?: "H264 pipeline not running"
            if (recordingHubHeld) {
                hub.release()
                recordingHubHeld = false
            }
            return false
        }
        recording.set(true)
        recordingStartedAt = System.currentTimeMillis()
        activeCameraId = "merged"
        lastError = null
        mosaicWriter = writerExec.submit { writeLoopH264() }
        return true
    }

    private fun writeLoopH264() {
        val pipe = h264
        if (pipe == null || !pipe.isRunning()) {
            lastError = "H264 pipeline not running"
            recording.set(false)
            if (recordingHubHeld) {
                hub.release()
                recordingHubHeld = false
            }
            return
        }
        try {
            while (recording.get()) {
                if (pipe.activeFile() == null) {
                    val file = newRecordingFile("mp4")
                    if (!pipe.openMp4(file)) {
                        lastError = pipe.lastError ?: "mp4 open failed"
                        break
                    }
                    activeFile = file
                    segmentBytes.set(0L)
                    segmentJobStartedAt = System.currentTimeMillis()
                    pipe.requestKeyFrame()
                }
                segmentBytes.set(pipe.bytesWritten())
                val elapsed = System.currentTimeMillis() - segmentJobStartedAt
                val hitLimit =
                    pipe.bytesWritten() >= SEGMENT_MAX_BYTES || elapsed >= SEGMENT_MAX_MS
                if (hitLimit) {
                    closeActiveSegment("rotate")
                    prune()
                    // Continuous DVR: open the next file on the next loop iteration.
                }
                try {
                    Thread.sleep(ROTATE_CHECK_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        } catch (t: Throwable) {
            lastError = t.message
            Log.w(TAG, "writeLoopH264: ${t.message}")
        } finally {
            closeActiveSegment("stop")
            prune()
            recording.set(false)
            if (recordingHubHeld) {
                hub.release()
                recordingHubHeld = false
            }
        }
    }

    /**
     * Finalize the open MP4 (stop muxer + write startUtcMs meta).
     * Safe to call twice — second call is a no-op.
     */
    @Synchronized
    private fun closeActiveSegment(reason: String) {
        val file = activeFile ?: return
        val startedAt = segmentJobStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val closed = runCatching { h264?.closeMp4() }.getOrNull() ?: file
        activeFile = null
        segmentJobStartedAt = 0L
        segmentBytes.set(0L)
        val wallElapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(frameIntervalMs())
        val ptsDur = extractMp4DurationMs(closed)
        val durationMs = ptsDur?.takeIf { it > 0L } ?: wallElapsed
        writeSegmentMetaMp4(closed, durationMs, startUtcMs = startedAt, ptsDurationMs = ptsDur)
        Log.i(
            TAG,
            "segment closed reason=$reason file=${closed.name} durMs=$durationMs ptsMs=$ptsDur wallMs=$wallElapsed",
        )
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

    private fun writeSegmentMetaMp4(
        file: File,
        durationMs: Long,
        startUtcMs: Long,
        ptsDurationMs: Long? = null,
    ) {
        runCatching {
            val end = startUtcMs + durationMs
            val pts = ptsDurationMs?.takeIf { it > 0L }
            val ptsField = if (pts != null) ",\"ptsDurationMs\":$pts" else ""
            val meta = metaFileFor(file)
            meta.writeText(
                "{\"durationMs\":$durationMs,\"startUtcMs\":$startUtcMs," +
                    "\"endUtcMs\":$end,\"format\":\"mp4\"$ptsField}\n",
            )
            Log.i(TAG, "wrote meta ${meta.name} start=$startUtcMs dur=$durationMs pts=$pts")
        }.onFailure {
            Log.w(TAG, "meta write failed for ${file.name}: ${it.message}")
            lastError = "meta write: ${it.message}"
        }
    }

    private fun newRecordingFile(ext: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dvrDir(), "oaa_dvr_$stamp.$ext")
    }

    fun stop() {
        recording.set(false)
        val w = mosaicWriter
        mosaicWriter = null
        // Wait for writeLoop finally (close + meta) before returning.
        if (w != null) {
            runCatching { w.get(20, java.util.concurrent.TimeUnit.SECONDS) }
                .onFailure { Log.w(TAG, "stop wait: ${it.message}") }
        }
        // Fallback if the writer never finalized (cancel / crash).
        closeActiveSegment("stop-fallback")
        recordingStartedAt = 0L
        activeCameraId = null
    }

    data class TimelineSegment(
        val name: String,
        val startUtcMs: Long,
        val endUtcMs: Long,
        val durationMs: Long,
        val file: File,
    )

    /** Closed DVR segments with startUtcMs meta only (active file excluded). */
    fun timelineSegments(): List<TimelineSegment> {
        val active = activeFile?.canonicalPath
        return recordingFiles()
            .mapNotNull { f ->
                if (active != null && f.canonicalPath == active) return@mapNotNull null
                if (!f.name.endsWith(".mp4")) return@mapNotNull null
                val start = readMetaLong(f, "startUtcMs") ?: return@mapNotNull null
                val dur = readMetaLong(f, "durationMs")?.takeIf { it > 0 } ?: durationMsFor(f)
                if (dur <= 0L) return@mapNotNull null
                val end = readMetaLong(f, "endUtcMs") ?: (start + dur)
                TimelineSegment(f.name, start, end, dur, f)
            }
            .sortedBy { it.startUtcMs }
    }

    fun timeline(): Map<String, Any?> {
        val closed = timelineSegments()
        val now = System.currentTimeMillis()
        val activeStart = segmentJobStartedAt.takeIf { recording.get() && activeFile != null && it > 0L }
        val segs = ArrayList<Map<String, Any?>>(closed.size + 1)
        closed.forEach { s ->
            segs += mapOf(
                "name" to s.name,
                "startUtcMs" to s.startUtcMs,
                "endUtcMs" to s.endUtcMs,
                "durationMs" to s.durationMs,
                "active" to false,
            )
        }
        if (activeStart != null) {
            val af = activeFile!!
            val dur = (now - activeStart).coerceAtLeast(0L)
            segs += mapOf(
                "name" to af.name,
                "startUtcMs" to activeStart,
                "endUtcMs" to now,
                "durationMs" to dur,
                "active" to true,
            )
        }
        val rangeStart = when {
            closed.isNotEmpty() && activeStart != null ->
                minOf(closed.first().startUtcMs, activeStart)
            closed.isNotEmpty() -> closed.first().startUtcMs
            activeStart != null -> activeStart
            else -> null
        }
        val rangeEnd = when {
            activeStart != null -> now
            closed.isNotEmpty() -> closed.last().endUtcMs
            else -> null
        }
        return mapOf(
            "ok" to true,
            "segments" to segs,
            "rangeStartUtcMs" to rangeStart,
            "rangeEndUtcMs" to rangeEnd,
            "recording" to recording.get(),
            "mode" to mode,
        )
    }

    /**
     * Resolve wall-clock [atUtcMs] to a closed segment + media offset.
     * Seeking into the still-open file seals it first so it becomes playable.
     * Snaps into gaps to the nearest recorded edge.
     */
    fun resolvePlayAt(atUtcMs: Long): Map<String, Any?> {
        maybeSealActiveForSeek(atUtcMs)
        val segs = timelineSegments()
        if (segs.isEmpty()) {
            // Still recording but nothing sealed yet (too short) → stay live.
            if (recording.get()) {
                return mapOf("ok" to true, "live" to true, "atUtcMs" to atUtcMs)
            }
            return mapOf("ok" to false, "error" to "no recordings")
        }
        val mathSegs = segs.map {
            DvrTimelineMath.Segment(it.startUtcMs, it.endUtcMs, it.durationMs)
        }
        val snap = DvrTimelineMath.resolvePlayAt(mathSegs, atUtcMs)
            ?: return mapOf("ok" to false, "error" to "no recordings")
        val snapped = segs[snap.index]
        return mapOf(
            "ok" to true,
            "name" to snapped.name,
            "offsetMs" to snap.offsetMs,
            "atUtcMs" to snap.wallUtcMs,
            "startUtcMs" to snapped.startUtcMs,
            "endUtcMs" to snapped.endUtcMs,
            "durationMs" to snapped.durationMs,
        )
    }

    /**
     * Finalize the open muxer file when the user seeks into its wall-clock range,
     * so the writer loop opens the next file and the sealed MP4 becomes playable.
     */
    private fun maybeSealActiveForSeek(atUtcMs: Long) {
        if (!recording.get()) return
        val started = segmentJobStartedAt
        if (activeFile == null || started <= 0L) return
        val now = System.currentTimeMillis()
        if (atUtcMs < started || atUtcMs > now + 1_000L) return
        if (now - started < 1_000L) return
        closeActiveSegment("seek-seal")
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
     * Delete closed DVR files (and their meta/locks). Skips the active open file.
     * @return count deleted
     */
    fun clearRecordings(includeLocked: Boolean = true): Map<String, Any?> {
        val active = activeFile?.canonicalPath
        var deleted = 0
        var skippedLocked = 0
        var skippedActive = 0
        recordingFiles().forEach { f ->
            if (active != null && f.canonicalPath == active) {
                skippedActive++
                return@forEach
            }
            if (!includeLocked && isLocked(f)) {
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

    fun setLocked(name: String, locked: Boolean): Boolean {
        val f = recordingFile(name) ?: return false
        val lock = lockFileFor(f)
        return if (locked) {
            runCatching { lock.writeText("locked\n"); true }.getOrDefault(false)
        } else {
            !lock.exists() || lock.delete()
        }
    }

    data class CutResult(
        val file: File,
        val downloadName: String,
        val durationMs: Long,
    )

    /**
     * Remux wall-clock [fromUtcMs, toUtcMs] across segments into a temp MP4.
     * Seals the active file first when the range overlaps it (same as play).
     * Caller deletes [CutResult.file] when done.
     */
    fun cutWallClockToTemp(fromUtcMs: Long, toUtcMs: Long): CutResult {
        val from = fromUtcMs
        val to = toUtcMs
        if (to <= from) error("invalid range")
        maybeSealActiveForSeek(to)
        val segs = timelineSegments().filter { it.startUtcMs < to && it.endUtcMs > from }
        if (segs.isEmpty()) error("no recordings in range")
        val ranges = segs.map { s ->
            val (mediaFrom, mediaTo) = DvrTimelineMath.mediaRangeForCut(
                s.startUtcMs,
                s.durationMs,
                from,
                to,
            )
            Mp4ClipRemuxer.Range(s.file, mediaFrom, mediaTo)
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val downloadName = "oaa_clip_${stamp}.mp4"
        val out = File(context.cacheDir, "oaa_cut_${stamp}_${Thread.currentThread().id}.mp4")
        try {
            val durationMs = Mp4ClipRemuxer.remuxRanges(ranges, out)
            if (durationMs <= 0L || !out.isFile || out.length() < 32) {
                out.delete()
                error("cut produced empty file")
            }
            lastCutSegments = segs.size
            lastCutBytes = out.length()
            Log.i(
                TAG,
                "cut ok segs=${segs.size} bytes=${out.length()} durMs=$durationMs name=$downloadName",
            )
            return CutResult(out, downloadName, durationMs)
        } catch (t: Throwable) {
            runCatching { out.delete() }
            throw t
        }
    }

    fun prune() {
        val active = activeFile?.canonicalPath
        val all = recordingFiles()
        if (all.isEmpty()) return
        val inputs = all.map { f ->
            DvrStorageMath.PruneFile(
                name = f.name,
                lastModified = f.lastModified(),
                length = f.length(),
                locked = isLocked(f),
                active = active != null && f.canonicalPath == active,
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
        return summary() + mapOf(
            "format" to streamFormat,
            "cameraId" to (activeCameraId ?: "merged"),
            "merged" to true,
            "stream" to hub.status() + mapOf(
                "format" to streamFormat,
                "h264" to h264?.status(),
                "camera2Probe" to camera2ProbeReport,
                "size" to "${mosaic.mosaicWidth()}x${mosaic.mosaicHeight()}",
                "width" to mosaic.mosaicWidth(),
                "height" to mosaic.mosaicHeight(),
                "fps" to (h264?.measuredFps() ?: mosaic.sourceFps()),
                "sourceFps" to mosaic.sourceFps(),
                "frameIntervalMs" to frameIntervalMs(),
                "source" to "cameras",
            ),
            "cameras" to cameras().map { src ->
                val size = singlePreview.previewSize(src.cameraId)
                mapOf(
                    "id" to src.cameraId,
                    "entityId" to src.id,
                    "label" to src.label,
                    "role" to src.role,
                    "width" to size?.first,
                    "height" to size?.second,
                )
            },
            "outputDir" to outputDir().absolutePath,
            "dvrDir" to dvrDir().absolutePath,
            "activeFile" to activeFile?.absolutePath,
            "previewHeld" to previewHubHeld,
            "hubRefs" to hub.refCount(),
            "wakeSleepDebounceSkips" to wakeSleepDebounceSkips,
            "lastCutSegments" to lastCutSegments,
            "lastCutBytes" to lastCutBytes,
            "segmentBytes" to if (recording.get()) segmentBytes.get() else 0L,
            "segmentElapsedMs" to if (recording.get() && segmentJobStartedAt > 0) {
                System.currentTimeMillis() - segmentJobStartedAt
            } else {
                0L
            },
            // Session wall-clock (does not reset when DVR rotates files).
            "elapsedMs" to if (recording.get() && recordingStartedAt > 0) {
                System.currentTimeMillis() - recordingStartedAt
            } else {
                null
            },
            "recordingStartedAt" to recordingStartedAt.takeIf { recording.get() && it > 0 },
            "segmentMaxBytes" to SEGMENT_MAX_BYTES,
            "segmentMaxMs" to SEGMENT_MAX_MS,
            "preview" to previewStatus(),
        )
    }

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

    private fun volumePath(vol: StorageVolume): File? {
        return if (Build.VERSION.SDK_INT >= 30) {
            vol.directory
        } else {
            @Suppress("DEPRECATION")
            vol.javaClass.methods
                .firstOrNull { it.name == "getPathFile" }
                ?.invoke(vol) as? File
        }
    }

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

        private const val STORAGE_CACHE_MS = 30_000L
        private const val DEFAULT_MAX_TOTAL_MB = 2048
        private const val DEFAULT_MAX_AGE_DAYS = 0
        private const val SEGMENT_MAX_BYTES = 100L * 1024L * 1024L
        private const val SEGMENT_MAX_MS = 5L * 60L * 1000L
        private const val ROTATE_CHECK_MS = 1_000L
        private const val WAKE_SLEEP_DEBOUNCE_MS = 5_000L
    }
}

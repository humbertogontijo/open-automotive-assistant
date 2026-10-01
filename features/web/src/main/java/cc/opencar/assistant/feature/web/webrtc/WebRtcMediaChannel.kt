package cc.opencar.assistant.feature.web.webrtc

import android.util.Log
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaMediaChunk
import cc.opencar.assistant.protocol.OaaMediaNames
import cc.opencar.assistant.protocol.OaaWebRtc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Car side of the `oaa-media` data channel (ADR-0003): camera selection, the
 * car clock, replay control (recordings play on the camera tracks, see
 * [ReplaySession]), recording downloads and per-camera cuts. Recordings are
 * addressed by basename only and resolved inside the DVR directory.
 */
class WebRtcMediaChannel(
    private val dc: DataLink,
    private val sessionId: String,
    private val dvr: DvrController,
    private val live: LiveControl,
) {
    /** The open `oaa-media` channel; the owning session delivers its events. */
    interface DataLink {
        val isOpen: Boolean
        val bufferedAmount: Long
        fun sendText(text: String): Boolean
        fun sendBinary(data: ByteArray): Boolean
    }

    /** The session's camera tracks: live, or replaying recordings. */
    interface LiveControl {
        /** Camera roles with a track in this session. */
        val roles: List<String>
        fun setPaused(paused: Boolean)
        fun select(roles: Collection<String>)
        /** One of the `replay_*` requests. */
        fun replay(type: String, msg: JSONObject)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transfers = ConcurrentHashMap<Long, Job>()
    @Volatile private var closed = false

    /** Call once the channel is open. */
    fun start() {
        if (dc.isOpen) sendHello()
    }

    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        transfers.clear()
    }

    fun onClosed() {
        scope.cancel()
    }

    fun onMessage(bytes: ByteArray, binary: Boolean) {
        if (binary || closed) return
        val msg = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
        val reqId = msg.optLong("reqId", -1L)
        if (!OaaFrames.isCurrentVersion(msg)) {
            sendError(reqId, OaaWebRtc.REASON_VERSION)
            return
        }
        when (val type = msg.optString("type")) {
            OaaWebRtc.TRANSFER_CANCEL -> cancel(reqId)
            OaaWebRtc.REPLAY_START, OaaWebRtc.REPLAY_SEEK, OaaWebRtc.REPLAY_PAUSE,
            OaaWebRtc.REPLAY_RESUME, OaaWebRtc.REPLAY_SPEED, OaaWebRtc.REPLAY_STOP,
            -> live.replay(type, msg)
            OaaWebRtc.DOWNLOAD_OPEN -> openDownload(reqId, msg.optString("name"))
            OaaWebRtc.CUT_REQUEST -> openCut(
                reqId,
                msg.optString("role"),
                msg.optLong("fromMs", -1L),
                msg.optLong("toMs", -1L),
            )
            OaaWebRtc.LIVE_PAUSE -> live.setPaused(true)
            OaaWebRtc.LIVE_RESUME -> live.setPaused(false)
            OaaWebRtc.LIVE_SELECT -> live.select(
                msg.optJSONArray("roles")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: live.roles,
            )
            OaaWebRtc.CLOCK_SYNC -> sendJson(
                OaaFrames.versioned()
                    .put("type", OaaWebRtc.CLOCK)
                    .put("t0", msg.optDouble("t0", 0.0))
                    .put("carUtcMs", System.currentTimeMillis()),
            )
            else -> sendError(reqId, "unknown message")
        }
    }

    private fun sendHello() {
        val features = JSONArray()
        if (live.roles.isNotEmpty()) {
            features.put(OaaWebRtc.FEATURE_LIVE)
            features.put(OaaWebRtc.FEATURE_REPLAY)
        }
        features.put(OaaWebRtc.FEATURE_DOWNLOAD)
        features.put(OaaWebRtc.FEATURE_CUT)
        sendJson(
            OaaFrames.versioned()
                .put("type", OaaWebRtc.DC_HELLO)
                .put("sessionId", sessionId)
                .put("features", features)
                .put("cameras", JSONArray(dvr.cameraRoles()))
                .put("tracks", JSONArray(live.roles))
                .put("carUtcMs", System.currentTimeMillis())
                .put("maxCutMs", DvrController.MAX_CUT_MS)
                .put("chunkMaxBytes", OaaWebRtc.CHUNK_MAX_BYTES)
                .put("maxTransfers", OaaWebRtc.MAX_TRANSFERS),
        )
    }

    /** Which tracks exist and which cameras are sending now. */
    fun sendLiveTracks(roles: List<String>, streaming: List<String>) {
        if (!dc.isOpen) return
        sendJson(
            OaaFrames.versioned()
                .put("type", OaaWebRtc.LIVE_TRACKS)
                .put("roles", JSONArray(roles))
                .put("streaming", JSONArray(streaming)),
        )
    }

    /** A car→SPA event that is not tied to a transfer (e.g. [OaaWebRtc.REPLAY_STATE]). */
    fun sendEvent(obj: JSONObject) {
        if (dc.isOpen) sendJson(obj)
    }

    // --- download / cut -----------------------------------------------------

    private fun openDownload(reqId: Long, name: String) {
        if (!OaaMediaNames.isSafeBasename(name)) {
            sendError(reqId, "invalid name")
            return
        }
        val file = dvr.recordingFile(name)
        if (file == null) {
            sendError(reqId, "not found")
            return
        }
        if (!admit(reqId)) return
        launchTransfer(reqId) { streamFile(reqId, file, name, "download") }
    }

    private fun openCut(reqId: Long, role: String, fromMs: Long, toMs: Long) {
        if (role !in dvr.cameraRoles()) {
            sendError(reqId, "unknown camera")
            return
        }
        if (fromMs <= 0 || toMs <= fromMs || toMs - fromMs > DvrController.MAX_CUT_MS) {
            sendError(reqId, "invalid range")
            return
        }
        if (!admit(reqId)) return
        launchTransfer(reqId) {
            var lastSentAt = 0L
            val cut = dvr.cutWallClockToTemp(
                role,
                fromMs,
                toMs,
                onProgress = { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastSentAt >= PROGRESS_EVERY_MS || done >= total) {
                        lastSentAt = now
                        sendJson(
                            OaaFrames.versioned()
                                .put("type", OaaWebRtc.MEDIA_PROGRESS)
                                .put("reqId", reqId)
                                .put("doneMs", done)
                                .put("totalMs", total),
                        )
                    }
                },
                isCancelled = { !isActive },
            )
            try {
                streamFile(reqId, cut.file, cut.downloadName, "cut", cut.durationMs)
            } finally {
                runCatching { cut.file.delete() }
            }
        }
    }

    /** Run [block] as the transfer for [reqId]. */
    private fun launchTransfer(reqId: Long, block: suspend CoroutineScope.() -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "transfer $reqId: ${t.message}")
                sendError(reqId, t.message ?: "transfer failed")
            } finally {
                transfers.remove(reqId, coroutineContext[Job])
            }
        }
        transfers[reqId] = job
        job.start()
    }

    private suspend fun CoroutineScope.streamFile(
        reqId: Long,
        file: File,
        name: String,
        kind: String,
        durationMs: Long? = null,
    ) {
        val size = file.length()
        val meta = OaaFrames.versioned()
            .put("type", OaaWebRtc.MEDIA_META)
            .put("reqId", reqId)
            .put("kind", kind)
            .put("name", name)
            .put("mime", "video/mp4")
            .put("size", size)
        if (durationMs != null) meta.put("durationMs", durationMs)
        sendJson(meta)
        var seq = 0L
        val buf = ByteArray(SEND_CHUNK)
        var sent = 0L
        file.inputStream().use { input ->
            while (true) {
                ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                sent += n
                sendChunk(reqId, seq++, buf, 0, n, if (sent >= size) OaaMediaChunk.FLAG_EOF else 0)
                if (sent >= size) return
            }
        }
        sendChunk(reqId, seq, ByteArray(0), 0, 0, OaaMediaChunk.FLAG_EOF)
    }

    // --- plumbing -----------------------------------------------------------

    private fun admit(reqId: Long): Boolean {
        if (reqId < 0 || reqId > 0xFFFF_FFFFL) {
            sendError(reqId, "reqId required")
            return false
        }
        if (transfers.containsKey(reqId)) {
            sendError(reqId, "reqId in use")
            return false
        }
        if (transfers.size >= OaaWebRtc.MAX_TRANSFERS) {
            sendError(reqId, OaaWebRtc.REASON_BUSY)
            return false
        }
        return true
    }

    private fun cancel(reqId: Long) {
        transfers.remove(reqId)?.cancel()
    }

    private suspend fun sendChunk(reqId: Long, seq: Long, data: ByteArray, off: Int, len: Int, flags: Int) {
        while (dc.bufferedAmount > HIGH_WATER_BYTES) {
            if (closed || !dc.isOpen) throw IOException("channel closed")
            delay(15)
        }
        val frame = OaaMediaChunk.encode(reqId, seq, flags, data, off, len)
        if (!dc.sendBinary(frame)) throw IOException("send failed")
    }

    private fun sendError(reqId: Long, error: String) {
        val msg = OaaFrames.versioned()
            .put("type", OaaWebRtc.MEDIA_ERROR)
            .put("error", error)
        if (reqId >= 0) msg.put("reqId", reqId)
        sendJson(msg)
    }

    private fun sendJson(obj: JSONObject) {
        if (closed) return
        dc.sendText(obj.toString())
    }

    companion object {
        private const val TAG = "OaaWebRtcDc"
        /** Well under Chrome/Firefox SCTP max-message-size; keeps two transfers interleaved. */
        private const val SEND_CHUNK = 64 * 1024
        private const val HIGH_WATER_BYTES = 1L * 1024 * 1024
        private const val PROGRESS_EVERY_MS = 500L
    }
}

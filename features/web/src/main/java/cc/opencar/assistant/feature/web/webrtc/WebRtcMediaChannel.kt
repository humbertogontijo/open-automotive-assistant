package cc.opencar.assistant.feature.web.webrtc

import android.util.Log
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.feature.dvr.Fmp4FileStreamer
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Car side of the `oaa-media` data channel (ADR-0003): fMP4 playback of DVR
 * recordings, recording downloads and wall-clock cuts. Recordings are
 * addressed by basename only and resolved inside the DVR directory.
 */
class WebRtcMediaChannel(
    private val dc: DataLink,
    private val sessionId: String,
    private val dvr: DvrController,
    private val liveAvailable: Boolean,
    private val onLivePaused: (Boolean) -> Unit,
) {
    /** The open `oaa-media` channel; the owning session delivers its events. */
    interface DataLink {
        val isOpen: Boolean
        val bufferedAmount: Long
        fun sendText(text: String): Boolean
        fun sendBinary(data: ByteArray): Boolean
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transfers = ConcurrentHashMap<Long, Job>()
    private val playbackFiles = ConcurrentHashMap<Long, File>()
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
        playbackFiles.clear()
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
        when (msg.optString("type")) {
            OaaWebRtc.PLAYBACK_OPEN -> openPlayback(reqId, msg)
            OaaWebRtc.PLAYBACK_SEEK -> seekPlayback(reqId, msg)
            OaaWebRtc.PLAYBACK_CLOSE, OaaWebRtc.TRANSFER_CANCEL -> cancel(reqId)
            OaaWebRtc.DOWNLOAD_OPEN -> openDownload(reqId, msg.optString("name"))
            OaaWebRtc.CUT_REQUEST -> openCut(reqId, msg.optLong("fromMs", -1L), msg.optLong("toMs", -1L))
            OaaWebRtc.LIVE_PAUSE -> onLivePaused(true)
            OaaWebRtc.LIVE_RESUME -> onLivePaused(false)
            else -> sendError(reqId, "unknown message")
        }
    }

    private fun sendHello() {
        val features = JSONArray()
        if (liveAvailable) features.put(OaaWebRtc.FEATURE_LIVE)
        features.put(OaaWebRtc.FEATURE_PLAYBACK)
        features.put(OaaWebRtc.FEATURE_DOWNLOAD)
        features.put(OaaWebRtc.FEATURE_CUT)
        sendJson(
            OaaFrames.versioned()
                .put("type", OaaWebRtc.DC_HELLO)
                .put("sessionId", sessionId)
                .put("features", features)
                .put("playbackContainer", OaaWebRtc.PLAYBACK_CONTAINER)
                .put("chunkMaxBytes", OaaWebRtc.CHUNK_MAX_BYTES)
                .put("maxTransfers", OaaWebRtc.MAX_TRANSFERS),
        )
    }

    // --- playback -----------------------------------------------------------

    private data class PlayTarget(val file: File, val offsetMs: Long, val extra: JSONObject)

    private fun resolveTarget(msg: JSONObject, fallbackFile: File?): Result<PlayTarget> {
        if (msg.has("atMs")) {
            val res = dvr.resolvePlayAt(msg.optLong("atMs"))
            if (res["ok"] != true) return Result.failure(IOException(res["error"]?.toString() ?: "no recordings"))
            if (res["live"] == true) return Result.failure(IOException("live"))
            val name = res["name"]?.toString() ?: return Result.failure(IOException("no recordings"))
            val file = dvr.recordingFile(name) ?: return Result.failure(IOException("not found"))
            val extra = JSONObject()
                .put("atMs", res["atUtcMs"])
                .put("segStartUtcMs", res["startUtcMs"])
                .put("segEndUtcMs", res["endUtcMs"])
            return Result.success(PlayTarget(file, (res["offsetMs"] as? Number)?.toLong() ?: 0L, extra))
        }
        val file = if (msg.has("name")) {
            val name = msg.optString("name")
            if (!OaaMediaNames.isSafeBasename(name)) return Result.failure(IOException("invalid name"))
            dvr.recordingFile(name) ?: return Result.failure(IOException("not found"))
        } else {
            fallbackFile ?: return Result.failure(IOException("name or atMs required"))
        }
        return Result.success(PlayTarget(file, msg.optLong("offsetMs", 0L).coerceAtLeast(0L), JSONObject()))
    }

    private fun openPlayback(reqId: Long, msg: JSONObject) {
        if (!admit(reqId)) return
        val target = resolveTarget(msg, null).getOrElse {
            sendError(reqId, it.message ?: "playback failed")
            return
        }
        startPlayback(reqId, target, previous = null)
    }

    private fun seekPlayback(reqId: Long, msg: JSONObject) {
        val previous = transfers[reqId]
        if (previous == null && !admit(reqId)) return
        val target = resolveTarget(msg, playbackFiles[reqId]).getOrElse {
            sendError(reqId, it.message ?: "seek failed")
            return
        }
        startPlayback(reqId, target, previous)
    }

    private fun startPlayback(reqId: Long, target: PlayTarget, previous: Job?) {
        playbackFiles[reqId] = target.file
        launchTransfer(reqId, previous) { streamPlayback(reqId, target) }
    }

    private suspend fun CoroutineScope.streamPlayback(reqId: Long, target: PlayTarget) {
        Fmp4FileStreamer(target.file).use { streamer ->
            val info = streamer.open(target.offsetMs)
            val meta = JSONObject(target.extra.toString())
                .put("type", OaaWebRtc.MEDIA_META)
                .put("v", OaaFrames.VERSION)
                .put("reqId", reqId)
                .put("kind", "playback")
                .put("name", target.file.name)
                .put("mime", "video/mp4; codecs=\"${info.codec}\"")
                .put("codec", info.codec)
                .put("width", info.width)
                .put("height", info.height)
                .put("durationMs", info.durationMs)
                .put("startMs", info.startMs)
                .put("offsetMs", target.offsetMs)
            sendJson(meta)
            var seq = 0L
            seq = sendBytes(reqId, seq, info.init, OaaMediaChunk.FLAG_INIT)
            val startedAt = System.currentTimeMillis()
            while (true) {
                ensureActive()
                val frag = streamer.nextFragment() ?: break
                seq = sendBytes(reqId, seq, frag.data, 0)
                // Pace to ~2× realtime after a 10 s head start; avoids pulling a whole file over cellular.
                while ((frag.endMs - info.startMs) > (System.currentTimeMillis() - startedAt) * 2 + PLAYBACK_LEAD_MS) {
                    delay(100)
                }
            }
            sendChunk(reqId, seq, ByteArray(0), 0, 0, OaaMediaChunk.FLAG_EOF)
        }
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

    private fun openCut(reqId: Long, fromMs: Long, toMs: Long) {
        if (fromMs <= 0 || toMs <= fromMs || toMs - fromMs > MAX_CUT_MS) {
            sendError(reqId, "invalid range")
            return
        }
        if (!admit(reqId)) return
        launchTransfer(reqId) {
            val cut = dvr.cutWallClockToTemp(fromMs, toMs)
            try {
                streamFile(reqId, cut.file, cut.downloadName, "cut", cut.durationMs)
            } finally {
                runCatching { cut.file.delete() }
            }
        }
    }

    /** Run [block] as the transfer for [reqId], after [previous] (a replaced seek) has stopped. */
    private fun launchTransfer(reqId: Long, previous: Job? = null, block: suspend CoroutineScope.() -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            previous?.cancelAndJoin()
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
        playbackFiles.remove(reqId)
    }

    /** Split [data] into ≤ [SEND_CHUNK] frames; returns the next seq. */
    private suspend fun sendBytes(reqId: Long, startSeq: Long, data: ByteArray, flags: Int): Long {
        var seq = startSeq
        var off = 0
        do {
            val n = minOf(SEND_CHUNK, data.size - off)
            sendChunk(reqId, seq++, data, off, n, if (seq - 1 == startSeq) flags else 0)
            off += n
        } while (off < data.size)
        return seq
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
        private const val PLAYBACK_LEAD_MS = 10_000L
        private const val MAX_CUT_MS = 30 * 60_000L
    }
}

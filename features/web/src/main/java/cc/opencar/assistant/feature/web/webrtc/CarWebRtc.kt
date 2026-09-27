package cc.opencar.assistant.feature.web.webrtc

import android.util.Log
import cc.opencar.assistant.feature.debug.LogRingBuffer
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaWebRtc
import cc.opencar.oaartc.oaartc.Listener
import cc.opencar.oaartc.oaartc.Oaartc
import cc.opencar.oaartc.oaartc.Session as RtcSession
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Car end of the WebRTC media plane (ADR-0003). Receives `webrtc_*` frames relayed by
 * the hub over the node WebSocket, answers the viewer's offer, and publishes
 * the live mosaic (pass-through H.264) plus the `oaa-media` data channel.
 * One session at a time; a new offer replaces the current one.
 */
class CarWebRtc(
    private val dvr: DvrController,
    /** Sends a signaling frame to the hub; false when the node socket is down. */
    private val sendSignal: (String) -> Boolean,
) {
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "oaa-webrtc").apply { isDaemon = true }
    }
    private var current: Session? = null

    private inner class Session(val id: String) {
        var rtc: RtcSession? = null
        var source: PassThroughH264Source? = null
        var seatHeld = false
        var media: WebRtcMediaChannel? = null
        var remoteSet = false
        val pendingIce = ArrayList<Triple<String, String, Long>>()
        var iceState = ""
        var closed = false
    }

    fun onSignal(frame: JSONObject) {
        exec.execute {
            runCatching { handle(frame) }.onFailure { Log.w(TAG, "signal failed", it) }
        }
    }

    fun closeAll(reason: String = OaaWebRtc.REASON_BYE) {
        exec.execute { current?.let { close(it, reason, notify = true) } }
    }

    /** Close the session, then drop any seat or tap still held; the instance is unusable afterwards. */
    fun shutdown() {
        runCatching {
            exec.submit { current?.let { close(it, OaaWebRtc.REASON_BYE, notify = true) } }.get(2, TimeUnit.SECONDS)
        }
        exec.shutdownNow()
        dvr.releaseAllRemoteLive()
    }

    private fun handle(frame: JSONObject) {
        val type = frame.optString("type")
        val p = frame.optJSONObject("payload") ?: return
        val sid = p.optString("sessionId")
        if (!OaaWebRtc.isValidSessionId(sid)) return
        if (!OaaFrames.isCurrentVersion(p)) {
            send(OaaFrames.hangup(sid, OaaWebRtc.REASON_VERSION))
            current?.takeIf { it.id == sid }?.let { close(it, OaaWebRtc.REASON_VERSION, notify = false) }
            return
        }
        when (type) {
            OaaWebRtc.OFFER -> handleOffer(sid, p)
            OaaWebRtc.ICE -> {
                val s = current?.takeIf { it.id == sid } ?: return
                val cand = Triple(
                    p.optString("candidate"),
                    p.optString("sdpMid"),
                    p.optLong("sdpMLineIndex", 0),
                )
                if (cand.first.isEmpty()) return
                if (s.remoteSet) addCandidate(s, cand) else s.pendingIce += cand
            }
            OaaWebRtc.HANGUP -> current?.takeIf { it.id == sid }?.let {
                close(it, p.optString("reason", OaaWebRtc.REASON_BYE), notify = false)
            }
        }
    }

    private fun handleOffer(sid: String, p: JSONObject) {
        current?.let { close(it, OaaWebRtc.REASON_REPLACED, notify = it.id != sid) }
        val s = Session(sid)
        current = s

        val rtc = try {
            Oaartc.newSession(p.optJSONArray("iceServers")?.toString() ?: "[]", OaaWebRtc.DC_LABEL, listenerFor(s))
        } catch (t: Throwable) {
            Log.w(TAG, "session create failed", t)
            close(s, OaaWebRtc.REASON_ERROR, notify = true)
            return
        }
        s.rtc = rtc

        val wantsLive = dvr.cameras().isNotEmpty() && p.optString("sdp").contains("m=video")
        if (wantsLive && dvr.acquireRemoteLive()) {
            s.seatHeld = true
            s.source = PassThroughH264Source(dvr) { data, durationUs ->
                runCatching { rtc.writeSample(data, durationUs) }.isSuccess
            }
        }

        val answer = try {
            rtc.answer(p.optString("sdp"), s.seatHeld)
        } catch (t: Throwable) {
            Log.w(TAG, "answer failed: ${t.message}")
            close(s, OaaWebRtc.REASON_ERROR, notify = true)
            return
        }
        s.remoteSet = true
        s.pendingIce.forEach { addCandidate(s, it) }
        s.pendingIce.clear()
        s.source?.let { dvr.addLiveTap(it) }

        val out = OaaFrames.versioned()
            .put("sessionId", sid)
            .put("sdp", answer)
            .put("codecs", JSONArray().put("H264"))
        send(OaaFrames.frame(OaaWebRtc.ANSWER, out))
        LogRingBuffer.append("WebRTC session $sid answered live=${s.seatHeld}")
    }

    private fun addCandidate(s: Session, c: Triple<String, String, Long>) {
        runCatching { s.rtc?.addRemoteCandidate(c.first, c.second, c.third) }
            .onFailure { Log.w(TAG, "remote candidate rejected: ${it.message}") }
    }

    /** Pion calls back on Go threads; everything hops onto [exec]. */
    private fun listenerFor(s: Session) = object : Listener {
        override fun onLocalCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Long) {
            val out = OaaFrames.versioned()
                .put("sessionId", s.id)
                .put("candidate", candidate)
                .put("sdpMid", sdpMid)
                .put("sdpMLineIndex", sdpMLineIndex)
            send(OaaFrames.frame(OaaWebRtc.ICE, out))
        }

        override fun onConnectionState(state: String) {
            exec.execute { onIceState(s, state) }
        }

        override fun onKeyFrameRequest() {
            s.source?.requestKeyFrame()
        }

        override fun onDataOpen() {
            exec.execute {
                if (current !== s || s.media != null) return@execute
                val rtc = s.rtc ?: return@execute
                s.media = WebRtcMediaChannel(RtcDataLink(rtc), s.id, dvr, liveAvailable = s.seatHeld) { paused ->
                    s.source?.paused = paused
                }.also { it.start() }
            }
        }

        override fun onDataMessage(data: ByteArray, binary: Boolean) {
            exec.execute { s.media?.onMessage(data, binary) }
        }

        override fun onDataClose() {
            exec.execute { s.media?.onClosed() }
        }
    }

    private fun onIceState(s: Session, state: String) {
        if (current !== s) return
        s.iceState = state
        when (state) {
            ICE_FAILED -> close(s, OaaWebRtc.REASON_ERROR, notify = true)
            ICE_DISCONNECTED -> exec.schedule({
                if (current === s && s.iceState == ICE_DISCONNECTED) {
                    close(s, OaaWebRtc.REASON_ERROR, notify = true)
                }
            }, DISCONNECT_GRACE_SEC, TimeUnit.SECONDS)
            ICE_CONNECTED -> s.source?.restart()
        }
    }

    private fun close(s: Session, reason: String, notify: Boolean) {
        if (s.closed) return
        s.closed = true
        if (current === s) current = null
        if (notify) send(OaaFrames.hangup(s.id, reason))
        s.media?.close()
        s.source?.let { src ->
            dvr.removeLiveTap(src)
            src.dispose()
        }
        runCatching { s.rtc?.close() }
        if (s.seatHeld) {
            s.seatHeld = false
            dvr.releaseRemoteLive()
        }
        LogRingBuffer.append("WebRTC session ${s.id} closed ($reason)")
    }

    private fun send(text: String) {
        if (!sendSignal(text)) Log.w(TAG, "signal dropped (hub offline)")
    }

    private class RtcDataLink(private val rtc: RtcSession) : WebRtcMediaChannel.DataLink {
        override val isOpen: Boolean get() = runCatching { rtc.dataOpen() }.getOrDefault(false)
        override val bufferedAmount: Long get() = runCatching { rtc.bufferedAmount() }.getOrDefault(0L)
        override fun sendText(text: String): Boolean = runCatching { rtc.sendText(text) }.isSuccess
        override fun sendBinary(data: ByteArray): Boolean = runCatching { rtc.sendBinary(data) }.isSuccess
    }

    companion object {
        private const val TAG = "OaaCarWebRtc"
        private const val DISCONNECT_GRACE_SEC = 15L
        private const val ICE_CONNECTED = "connected"
        private const val ICE_DISCONNECTED = "disconnected"
        private const val ICE_FAILED = "failed"
    }
}

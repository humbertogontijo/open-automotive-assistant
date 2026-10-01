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
 * Car end of the WebRTC media plane (ADR-0003). Answers viewer offers relayed by
 * the hub (node WebSocket) or sent on the car's own `/api/webrtc/signal`, sends
 * each camera on its own pass-through H.264 track and serves the `oaa-media`
 * data channel. The hub keeps one session per car; local viewers get a few more.
 */
class CarWebRtc(private val dvr: DvrController) {
    /** Where a session's signaling replies go; false when that socket is gone. */
    fun interface SignalSink {
        fun send(text: String): Boolean
    }

    enum class Origin { HUB, LOCAL }

    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "oaa-webrtc").apply { isDaemon = true }
    }
    /** Camera seats open cameras (seconds); kept off [exec] so signaling never waits on them. */
    private val liveExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "oaa-webrtc-live").apply { isDaemon = true }
    }
    /** Open sessions in arrival order; confined to [exec]. */
    private val sessions = LinkedHashMap<String, Session>()

    private inner class Session(val id: String, val origin: Origin, val sink: SignalSink) {
        var rtc: RtcSession? = null
        /** Track per camera role, in the order the tracks were added. */
        val sources = LinkedHashMap<String, PassThroughH264Source>()
        @Volatile var media: WebRtcMediaChannel? = null
        var remoteSet = false
        val pendingIce = ArrayList<Triple<String, String, Long>>()
        var iceState = ""
        @Volatile var closed = false
        @Volatile var paused = false
        /** Roles the viewer shows (live, or replayed while [replay] runs). */
        @Volatile var wanted: Set<String> = emptySet()
        /** Roles with a hub seat and tap; confined to [liveExec]. */
        val held = LinkedHashSet<String>()
        /** Recordings playing on the tracks instead of live; set on [exec]. */
        @Volatile var replay: ReplaySession? = null
    }

    fun onSignal(frame: JSONObject, origin: Origin, sink: SignalSink) {
        exec.execute {
            runCatching { handle(frame, origin, sink) }.onFailure { Log.w(TAG, "signal failed", it) }
        }
    }

    /** A signaling socket closed: its sessions go too. */
    fun onSinkClosed(sink: SignalSink) {
        exec.execute {
            sessions.values.filter { it.sink === sink }.forEach { close(it, OaaWebRtc.REASON_BYE, notify = false) }
        }
    }

    fun closeAll(reason: String = OaaWebRtc.REASON_BYE) {
        exec.execute { sessions.values.toList().forEach { close(it, reason, notify = true) } }
    }

    /** Close every session, then drop any seat still held; the instance is unusable afterwards. */
    fun shutdown() {
        runCatching {
            exec.submit { sessions.values.toList().forEach { close(it, OaaWebRtc.REASON_BYE, notify = true) } }
                .get(2, TimeUnit.SECONDS)
        }
        exec.shutdownNow()
        runCatching { liveExec.submit {}.get(5, TimeUnit.SECONDS) }
        liveExec.shutdownNow()
        dvr.releaseAllLive()
    }

    private fun handle(frame: JSONObject, origin: Origin, sink: SignalSink) {
        val type = frame.optString("type")
        val p = frame.optJSONObject("payload") ?: return
        val sid = p.optString("sessionId")
        if (!OaaWebRtc.isValidSessionId(sid)) return
        val existing = sessions[sid]?.takeIf { it.sink === sink }
        if (!OaaFrames.isCurrentVersion(p)) {
            sink.send(OaaFrames.hangup(sid, OaaWebRtc.REASON_VERSION))
            existing?.let { close(it, OaaWebRtc.REASON_VERSION, notify = false) }
            return
        }
        when (type) {
            OaaWebRtc.OFFER -> handleOffer(sid, origin, sink, p)
            OaaWebRtc.ICE -> {
                val s = existing ?: return
                val cand = Triple(
                    p.optString("candidate"),
                    p.optString("sdpMid"),
                    p.optLong("sdpMLineIndex", 0),
                )
                if (cand.first.isEmpty()) return
                if (s.remoteSet) addCandidate(s, cand) else s.pendingIce += cand
            }
            OaaWebRtc.HANGUP -> existing?.let {
                close(it, p.optString("reason", OaaWebRtc.REASON_BYE), notify = false)
            }
        }
    }

    private fun handleOffer(sid: String, origin: Origin, sink: SignalSink, p: JSONObject) {
        sessions[sid]?.let { close(it, OaaWebRtc.REASON_REPLACED, notify = it.sink !== sink) }
        val sameOrigin = sessions.values.filter { it.origin == origin }
        val cap = if (origin == Origin.HUB) 1 else MAX_LOCAL_SESSIONS
        sameOrigin.take((sameOrigin.size - cap + 1).coerceAtLeast(0)).forEach {
            close(it, OaaWebRtc.REASON_REPLACED, notify = true)
        }
        val s = Session(sid, origin, sink)
        sessions[sid] = s

        val iceServers = if (origin == Origin.HUB) p.optJSONArray("iceServers")?.toString() ?: "[]" else "[]"
        val rtc = try {
            Oaartc.newSession(iceServers, OaaWebRtc.DC_LABEL, origin == Origin.LOCAL, listenerFor(s))
        } catch (t: Throwable) {
            Log.w(TAG, "session create failed", t)
            close(s, OaaWebRtc.REASON_ERROR, notify = true)
            return
        }
        s.rtc = rtc

        val sdp = p.optString("sdp")
        val videoLines = VIDEO_MLINE.findAll(sdp).count().coerceAtMost(OaaWebRtc.MAX_VIDEO_TRACKS)
        for (role in dvr.cameraRoles().take(videoLines)) {
            val added = runCatching { rtc.addVideoTrack(role) }
                .onFailure { Log.w(TAG, "track $role: ${it.message}") }
                .isSuccess
            if (!added) continue
            s.sources[role] = PassThroughH264Source(dvr, role) { data, captureUtcMs ->
                runCatching { rtc.writeSample(role, data, captureUtcMs) }.isSuccess
            }
        }

        val answer = try {
            rtc.answer(sdp)
        } catch (t: Throwable) {
            Log.w(TAG, "answer failed: ${t.message}")
            close(s, OaaWebRtc.REASON_ERROR, notify = true)
            return
        }
        s.remoteSet = true
        s.pendingIce.forEach { addCandidate(s, it) }
        s.pendingIce.clear()
        // Every track starts live; the viewer narrows it with live_select.
        select(s, s.sources.keys)

        val out = OaaFrames.versioned()
            .put("sessionId", sid)
            .put("sdp", answer)
            .put("codecs", JSONArray().put("H264"))
            .put("tracks", JSONArray(s.sources.keys.toList()))
        sink.send(OaaFrames.frame(OaaWebRtc.ANSWER, out))
        LogRingBuffer.append("WebRTC ${origin.name.lowercase()} session $sid answered tracks=${s.sources.keys}")
    }

    private fun addCandidate(s: Session, c: Triple<String, String, Long>) {
        runCatching { s.rtc?.addRemoteCandidate(c.first, c.second, c.third) }
            .onFailure { Log.w(TAG, "remote candidate rejected: ${it.message}") }
    }

    private fun select(s: Session, roles: Collection<String>) {
        s.wanted = roles.filter { it in s.sources }.toSet()
        s.replay?.setRoles(s.wanted)
        liveExec.execute { reconcile(s) }
    }

    /** `replay_*` from the viewer; runs on [exec]. */
    private fun onReplay(s: Session, type: String, msg: JSONObject) {
        if (s.closed) return
        val speed = msg.optDouble("speed", Double.NaN).takeIf { it.isFinite() && it > 0 }
        when (type) {
            OaaWebRtc.REPLAY_START -> {
                val r = s.replay ?: startReplay(s) ?: return
                speed?.let { r.setSpeed(it) }
                r.seek(msg.optLong("atMs"), msg.optBoolean("paused", false))
            }
            OaaWebRtc.REPLAY_SEEK -> s.replay?.seek(msg.optLong("atMs"), paused = null)
            OaaWebRtc.REPLAY_PAUSE -> s.replay?.pause()
            OaaWebRtc.REPLAY_RESUME -> s.replay?.resume()
            OaaWebRtc.REPLAY_SPEED -> speed?.let { s.replay?.setSpeed(it) }
            OaaWebRtc.REPLAY_STOP -> stopReplay(s, notify = true)
        }
    }

    private fun startReplay(s: Session): ReplaySession? {
        val rtc = s.rtc ?: return null
        val r = ReplaySession(
            dvr,
            s.wanted.ifEmpty { s.sources.keys },
            write = { role, data, rtpMs -> runCatching { rtc.writeSample(role, data, rtpMs) }.isSuccess },
            onState = { msg -> s.media?.sendEvent(msg) },
        )
        s.replay = r
        r.start()
        liveExec.execute { reconcile(s) }
        LogRingBuffer.append("WebRTC session ${s.id} replay started")
        return r
    }

    /** Back to live: live frames resume only after the replay's last RTP time on every track. */
    private fun stopReplay(s: Session, notify: Boolean) {
        val r = s.replay ?: return
        s.replay = null
        val floor = r.stop()
        s.sources.values.forEach { it.minCaptureUtcMs = floor }
        liveExec.execute { reconcile(s) }
        if (notify) {
            s.media?.sendEvent(
                OaaFrames.versioned()
                    .put("type", OaaWebRtc.REPLAY_STATE)
                    .put("state", "live")
                    .put("anchor", JSONObject().put("fromRtpMs", floor).put("live", true)),
            )
        }
    }

    private fun setPaused(s: Session, paused: Boolean) {
        s.paused = paused
        s.sources.values.forEach { it.paused = paused }
    }

    /** Bring the session's seats and taps in line with what it wants; runs on [liveExec]. */
    private fun reconcile(s: Session) {
        val want = if (s.closed || s.replay != null) emptySet() else s.wanted
        val drop = s.held - want
        if (drop.isNotEmpty()) {
            drop.forEach { role -> s.sources[role]?.let { dvr.removeLiveTap(role, it) } }
            dvr.releaseLive(drop)
            s.held -= drop
        }
        val add = want - s.held
        if (add.isNotEmpty()) {
            val got = dvr.acquireLive(add)
            for (role in got) {
                val src = s.sources[role] ?: continue
                src.paused = s.paused
                dvr.addLiveTap(role, src)
                src.restart()
            }
            s.held += got
        }
        if (!s.closed) s.media?.sendLiveTracks(s.sources.keys.toList(), s.held.toList())
    }

    /** Pion calls back on Go threads; everything hops onto [exec]. */
    private fun listenerFor(s: Session) = object : Listener {
        override fun onLocalCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Long) {
            val out = OaaFrames.versioned()
                .put("sessionId", s.id)
                .put("candidate", candidate)
                .put("sdpMid", sdpMid)
                .put("sdpMLineIndex", sdpMLineIndex)
            s.sink.send(OaaFrames.frame(OaaWebRtc.ICE, out))
        }

        override fun onConnectionState(state: String) {
            exec.execute { onIceState(s, state) }
        }

        override fun onKeyFrameRequest(role: String) {
            if (s.replay == null) s.sources[role]?.requestKeyFrame()
        }

        override fun onDataOpen() {
            exec.execute {
                if (s.closed || s.media != null) return@execute
                val rtc = s.rtc ?: return@execute
                s.media = WebRtcMediaChannel(RtcDataLink(rtc), s.id, dvr, liveControl(s)).also { it.start() }
                liveExec.execute { if (!s.closed) s.media?.sendLiveTracks(s.sources.keys.toList(), s.held.toList()) }
            }
        }

        override fun onDataMessage(data: ByteArray, binary: Boolean) {
            exec.execute { s.media?.onMessage(data, binary) }
        }

        override fun onDataClose() {
            exec.execute { s.media?.onClosed() }
        }
    }

    private fun liveControl(s: Session) = object : WebRtcMediaChannel.LiveControl {
        override val roles: List<String> get() = s.sources.keys.toList()
        override fun setPaused(paused: Boolean) = exec.execute { setPaused(s, paused) }
        override fun select(roles: Collection<String>) = exec.execute { if (!s.closed) select(s, roles) }
        override fun replay(type: String, msg: JSONObject) = exec.execute { onReplay(s, type, msg) }
    }

    private fun onIceState(s: Session, state: String) {
        if (s.closed) return
        s.iceState = state
        when (state) {
            ICE_FAILED -> close(s, OaaWebRtc.REASON_ERROR, notify = true)
            ICE_DISCONNECTED -> exec.schedule({
                if (!s.closed && s.iceState == ICE_DISCONNECTED) {
                    close(s, OaaWebRtc.REASON_ERROR, notify = true)
                }
            }, DISCONNECT_GRACE_SEC, TimeUnit.SECONDS)
            ICE_CONNECTED -> s.sources.values.forEach { it.restart() }
        }
    }

    private fun close(s: Session, reason: String, notify: Boolean) {
        if (s.closed) return
        s.closed = true
        sessions.remove(s.id, s)
        if (notify) s.sink.send(OaaFrames.hangup(s.id, reason))
        stopReplay(s, notify = false)
        s.media?.close()
        s.sources.values.forEach { it.dispose() }
        runCatching { s.rtc?.close() }
        liveExec.execute { reconcile(s) }
        LogRingBuffer.append("WebRTC session ${s.id} closed ($reason)")
    }

    private class RtcDataLink(private val rtc: RtcSession) : WebRtcMediaChannel.DataLink {
        override val isOpen: Boolean get() = runCatching { rtc.dataOpen() }.getOrDefault(false)
        override val bufferedAmount: Long get() = runCatching { rtc.bufferedAmount() }.getOrDefault(0L)
        override fun sendText(text: String): Boolean = runCatching { rtc.sendText(text) }.isSuccess
        override fun sendBinary(data: ByteArray): Boolean = runCatching { rtc.sendBinary(data) }.isSuccess
    }

    companion object {
        private const val TAG = "OaaCarWebRtc"
        private const val MAX_LOCAL_SESSIONS = 3
        private const val DISCONNECT_GRACE_SEC = 15L
        private const val ICE_CONNECTED = "connected"
        private const val ICE_DISCONNECTED = "disconnected"
        private const val ICE_FAILED = "failed"
        private val VIDEO_MLINE = Regex("^m=video ", RegexOption.MULTILINE)
    }
}

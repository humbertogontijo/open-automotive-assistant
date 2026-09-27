package cc.opencar.assistant.feature.web.webrtc

import android.content.Context
import android.util.Log
import cc.opencar.assistant.feature.debug.LogRingBuffer
import cc.opencar.assistant.feature.dvr.DvrController
import cc.opencar.assistant.protocol.OaaFrames
import cc.opencar.assistant.protocol.OaaWebRtc
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Car end of the WebRTC media plane (ADR-0003). Receives `webrtc_*` frames relayed by
 * the hub over the node WebSocket, answers the viewer's offer, and publishes
 * the live mosaic (pass-through H.264) plus the `oaa-media` data channel.
 * One session at a time; a new offer replaces the current one.
 */
class CarWebRtc(
    context: Context,
    private val dvr: DvrController,
    /** Sends a signaling frame to the hub; false when the node socket is down. */
    private val sendSignal: (String) -> Boolean,
) {
    private val appContext = context.applicationContext
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "oaa-webrtc").apply { isDaemon = true }
    }
    private val encoderFactory = PassThroughEncoderFactory()
    private var factory: PeerConnectionFactory? = null
    private var current: Session? = null

    private inner class Session(val id: String) {
        var pc: PeerConnection? = null
        var source: PassThroughH264Source? = null
        var videoSource: VideoSource? = null
        var track: VideoTrack? = null
        var seatHeld = false
        var media: WebRtcMediaChannel? = null
        var remoteSet = false
        val pendingIce = ArrayList<IceCandidate>()
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
                val cand = IceCandidate(
                    p.optString("sdpMid").takeIf { it.isNotEmpty() },
                    p.optInt("sdpMLineIndex", 0),
                    p.optString("candidate"),
                )
                if (cand.sdp.isNullOrEmpty()) return
                if (s.remoteSet) s.pc?.addIceCandidate(cand) else s.pendingIce += cand
            }
            OaaWebRtc.HANGUP -> current?.takeIf { it.id == sid }?.let {
                close(it, p.optString("reason", OaaWebRtc.REASON_BYE), notify = false)
            }
        }
    }

    private fun handleOffer(sid: String, p: JSONObject) {
        current?.let { close(it, OaaWebRtc.REASON_REPLACED, notify = it.id != sid) }
        val f = ensureFactory()
        val s = Session(sid)
        current = s

        val wantsLive = dvr.cameras().isNotEmpty() && p.optString("sdp").contains("m=video")
        if (wantsLive && dvr.acquireRemoteLive()) {
            s.seatHeld = true
            val src = PassThroughH264Source(dvr)
            val vs = f.createVideoSource(false)
            src.observer = vs.capturerObserver
            vs.capturerObserver.onCapturerStarted(true)
            encoderFactory.source = src
            s.source = src
            s.videoSource = vs
            s.track = f.createVideoTrack("oaa-mosaic", vs)
            dvr.addLiveTap(src)
        }

        val config = PeerConnection.RTCConfiguration(parseIceServers(p.optJSONArray("iceServers"))).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = f.createPeerConnection(config, observerFor(s))
        if (pc == null) {
            close(s, OaaWebRtc.REASON_ERROR, notify = true)
            return
        }
        s.pc = pc

        pc.setRemoteDescription(
            sdpObserver(s, "setRemote") {
                s.remoteSet = true
                s.pendingIce.forEach { pc.addIceCandidate(it) }
                s.pendingIce.clear()
                attachLiveTrack(s, pc)
                pc.createAnswer(
                    sdpObserver(s, "createAnswer", onCreate = { answer ->
                        pc.setLocalDescription(
                            sdpObserver(s, "setLocal") {
                                val out = OaaFrames.versioned()
                                    .put("sessionId", sid)
                                    .put("sdp", answer.description)
                                    .put("codecs", JSONArray().put("H264"))
                                send(OaaFrames.frame(OaaWebRtc.ANSWER, out))
                                LogRingBuffer.append("WebRTC session $sid answered live=${s.seatHeld}")
                            },
                            answer,
                        )
                    }),
                    MediaConstraints(),
                )
            },
            SessionDescription(SessionDescription.Type.OFFER, p.optString("sdp")),
        )
    }

    private fun attachLiveTrack(s: Session, pc: PeerConnection) {
        val track = s.track ?: return
        val tx = pc.transceivers.firstOrNull {
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && !it.isStopped
        } ?: return
        tx.sender.setTrack(track, false)
        tx.sender.setStreams(listOf("oaa"))
        tx.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
        runCatching {
            val params = tx.sender.parameters
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            params.encodings.forEach { e ->
                e.maxBitrateBps = MAX_BITRATE_BPS
                e.maxFramerate = dvr.liveFps().coerceIn(1, 30)
            }
            tx.sender.setParameters(params)
        }
    }

    private fun observerFor(s: Session) = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            exec.execute {
                if (current !== s) return@execute
                when (state) {
                    PeerConnection.IceConnectionState.FAILED ->
                        close(s, OaaWebRtc.REASON_ERROR, notify = true)
                    PeerConnection.IceConnectionState.DISCONNECTED ->
                        exec.schedule({
                            if (current === s && s.pc?.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED) {
                                close(s, OaaWebRtc.REASON_ERROR, notify = true)
                            }
                        }, DISCONNECT_GRACE_SEC, TimeUnit.SECONDS)
                    PeerConnection.IceConnectionState.CONNECTED ->
                        s.source?.requestKeyFrame()
                    else -> Unit
                }
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidate(candidate: IceCandidate) {
            val out = OaaFrames.versioned()
                .put("sessionId", s.id)
                .put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex)
            send(OaaFrames.frame(OaaWebRtc.ICE, out))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onDataChannel(dc: DataChannel) {
            exec.execute {
                if (current !== s || dc.label() != OaaWebRtc.DC_LABEL || s.media != null) {
                    runCatching { dc.close(); dc.dispose() }
                    return@execute
                }
                s.media = WebRtcMediaChannel(dc, s.id, dvr, liveAvailable = s.seatHeld) { paused ->
                    s.source?.paused = paused
                    s.track?.setEnabled(!paused)
                }.also { it.start() }
            }
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
            src.observer?.onCapturerStopped()
            src.dispose()
            if (encoderFactory.source === src) encoderFactory.source = null
        }
        runCatching { s.pc?.close() }
        runCatching { s.pc?.dispose() }
        runCatching { s.track?.dispose() }
        runCatching { s.videoSource?.dispose() }
        if (s.seatHeld) {
            s.seatHeld = false
            dvr.releaseRemoteLive()
        }
        LogRingBuffer.append("WebRTC session ${s.id} closed ($reason)")
    }

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions(),
        )
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory())
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    private fun parseIceServers(arr: JSONArray?): List<PeerConnection.IceServer> {
        if (arr == null) return emptyList()
        val out = ArrayList<PeerConnection.IceServer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val urls = when (val u = o.opt("urls")) {
                is JSONArray -> (0 until u.length()).map { u.optString(it) }.filter { it.isNotEmpty() }
                is String -> listOf(u)
                else -> emptyList()
            }
            if (urls.isEmpty()) continue
            val b = PeerConnection.IceServer.builder(urls)
            o.optString("username").takeIf { it.isNotEmpty() }?.let { b.setUsername(it) }
            o.optString("credential").takeIf { it.isNotEmpty() }?.let { b.setPassword(it) }
            out += b.createIceServer()
        }
        return out
    }

    private fun sdpObserver(
        s: Session,
        step: String,
        onCreate: ((SessionDescription) -> Unit)? = null,
        onSet: (() -> Unit)? = null,
    ) = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {
            exec.execute { if (!s.closed) onCreate?.invoke(desc) }
        }
        override fun onSetSuccess() {
            exec.execute { if (!s.closed) onSet?.invoke() }
        }
        override fun onCreateFailure(error: String?) = fail(error)
        override fun onSetFailure(error: String?) = fail(error)
        private fun fail(error: String?) {
            Log.w(TAG, "$step failed: $error")
            exec.execute { close(s, OaaWebRtc.REASON_ERROR, notify = true) }
        }
    }

    private fun send(text: String) {
        if (!sendSignal(text)) Log.w(TAG, "signal dropped (hub offline)")
    }

    companion object {
        private const val TAG = "OaaCarWebRtc"
        private const val MAX_BITRATE_BPS = 3_000_000
        private const val DISCONNECT_GRACE_SEC = 15L
    }
}

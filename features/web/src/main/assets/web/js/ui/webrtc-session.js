/**
 * Hub media plane (ADR-0003): one RTCPeerConnection per selected car carrying
 * the live mosaic (H.264 RTP track) and the `oaa-media` data channel (fMP4
 * playback into MSE, downloads, cuts). The hub only relays signaling on
 * /api/webrtc/signal; media flows car ↔ browser.
 *
 * Local role (browser on the car's LAN) keeps HLS + /api/dvr/* HTTP.
 */
import { state } from "../store.js";
import { api } from "../api.js";
import { t } from "../i18n.js";
import { MsePlayback } from "./mse-playback.js";

const V = 1;
const DC_LABEL = "oaa-media";
const FLAG_EOF = 0x01;
const HEADER_BYTES = 10;
const CONNECT_TIMEOUT_MS = 20000;
const PING_MS = 25000;

const REASON_TEXT = {
  offline: "Car is offline",
  unsupported: "The car app does not support remote video — update it",
  replaced: "Another viewer opened this car's cameras",
  version: "Media protocol version mismatch — update the car app or hub",
  busy: "The car is busy with other transfers",
  invalid: "Invalid media request",
  error: "Remote video connection failed",
  timeout: "Remote video connection timed out",
  bye: "Remote video closed",
};

/** @type {MediaSession|null} */
let current = null;
/** @type {Promise<MediaSession>|null} */
let connecting = null;
let liveWanted = false;
/** Back off reconnects after an unexpected hangup so repaints don't hammer the car. */
const RETRY_BACKOFF_MS = 5000;
let lastFailAt = 0;
let lastFailReason = "";

function isCurrentVersion(p) {
  return !!p && p.v === V;
}

export function reasonText(reason) {
  const key = String(reason || "error");
  return t("media.reason." + key, REASON_TEXT[key] || key);
}

function randomSessionId() {
  const a = new Uint8Array(12);
  crypto.getRandomValues(a);
  return (
    "s_" +
    Array.prototype.map
      .call(a, function (b) {
        return b.toString(16).padStart(2, "0");
      })
      .join("")
  );
}

class MediaError extends Error {
  constructor(reason) {
    super(reasonText(reason));
    this.reason = reason;
  }
}

class MediaSession {
  constructor(nodeId) {
    this.nodeId = nodeId;
    this.sessionId = randomSessionId();
    /** @type {WebSocket|null} */
    this.ws = null;
    /** @type {RTCPeerConnection|null} */
    this.pc = null;
    /** @type {RTCDataChannel|null} */
    this.dc = null;
    this.hello = null;
    /** @type {MediaStream|null} */
    this.liveStream = null;
    this.liveWaiters = [];
    this.closed = false;
    this.closeReason = "";
    this.transfers = new Map();
    this.nextReq = 1;
    this.pendingIce = [];
    this.remoteSet = false;
    this.pingTimer = 0;
    this.livePaused = false;
    this._hello = null;
  }

  has(feature) {
    return !!(this.hello && Array.isArray(this.hello.features) && this.hello.features.indexOf(feature) >= 0);
  }

  async connect() {
    const self = this;
    const helloP = new Promise(function (resolve, reject) {
      self._hello = { resolve: resolve, reject: reject };
    });
    helloP.catch(function () {});
    // One deadline for the whole handshake: ICE config, signaling hello, answer, dc_hello.
    const timer = setTimeout(function () {
      self._failHello("timeout");
    }, CONNECT_TIMEOUT_MS);
    try {
      await this._handshake(helloP);
      this.hello = await helloP;
    } finally {
      clearTimeout(timer);
    }
    return this;
  }

  async _handshake(helloP) {
    const self = this;
    let iceServers = [];
    try {
      const ice = await Promise.race([api("/api/webrtc/ice"), helloP]);
      if (ice && Array.isArray(ice.iceServers)) iceServers = ice.iceServers;
    } catch (e) {
      if (e instanceof MediaError) throw e;
    }
    if (this.closed) throw new MediaError(this.closeReason || "bye");
    const pc = new RTCPeerConnection({ iceServers: iceServers, bundlePolicy: "max-bundle" });
    this.pc = pc;
    pc.addTransceiver("video", { direction: "recvonly" });
    const dc = pc.createDataChannel(DC_LABEL, { ordered: true });
    dc.binaryType = "arraybuffer";
    this.dc = dc;
    dc.onmessage = function (ev) {
      self._onDc(ev.data);
    };
    dc.onclose = function () {
      self.close("bye", false);
    };
    pc.ontrack = function (ev) {
      self.liveStream = ev.streams && ev.streams[0] ? ev.streams[0] : new MediaStream([ev.track]);
      const waiters = self.liveWaiters.splice(0);
      waiters.forEach(function (w) {
        w(self.liveStream);
      });
    };
    pc.onicecandidate = function (ev) {
      if (!ev.candidate || !ev.candidate.candidate) return;
      self._signal("webrtc_ice", {
        candidate: ev.candidate.candidate,
        sdpMid: ev.candidate.sdpMid,
        sdpMLineIndex: ev.candidate.sdpMLineIndex,
      });
    };
    pc.onconnectionstatechange = function () {
      if (pc.connectionState === "failed") self.close("error", true);
    };

    await Promise.race([this._openSignal(), helloP]);
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    this._signal("webrtc_offer", { sdp: pc.localDescription.sdp });
  }

  _openSignal() {
    const self = this;
    const loc = window.location;
    const proto = loc.protocol === "https:" ? "wss:" : "ws:";
    const url = proto + "//" + loc.host + "/api/webrtc/signal?node=" + encodeURIComponent(this.nodeId);
    return new Promise(function (resolve, reject) {
      let opened = false;
      const ws = new WebSocket(url);
      self.ws = ws;
      ws.onmessage = function (ev) {
        let msg;
        try {
          msg = JSON.parse(ev.data);
        } catch (e) {
          return;
        }
        if (!msg) return;
        if (msg.type === "hello") {
          const p = msg.payload || {};
          if (!isCurrentVersion(p)) {
            reject(new MediaError("version"));
            return;
          }
          if (p.online === false) {
            reject(new MediaError("offline"));
            return;
          }
          opened = true;
          resolve();
          return;
        }
        self._onSignal(msg);
      };
      ws.onclose = function () {
        if (!opened) reject(new MediaError("offline"));
        else self.close(self.closeReason || "bye", false);
      };
      ws.onerror = function () {
        try {
          ws.close();
        } catch (e) {}
      };
      self.pingTimer = setInterval(function () {
        if (ws.readyState === WebSocket.OPEN) ws.send('{"type":"ping"}');
      }, PING_MS);
    });
  }

  async _onSignal(msg) {
    const p = msg.payload || {};
    if (p.sessionId && p.sessionId !== this.sessionId) return;
    if (msg.type === "webrtc_hangup") {
      this.close(p.reason || "bye", false);
      return;
    }
    if (!isCurrentVersion(p)) {
      this.close("version", true);
      return;
    }
    const pc = this.pc;
    if (!pc) return;
    try {
      if (msg.type === "webrtc_answer") {
        await pc.setRemoteDescription({ type: "answer", sdp: p.sdp });
        this.remoteSet = true;
        const queued = this.pendingIce.splice(0);
        for (let i = 0; i < queued.length; i++) await pc.addIceCandidate(queued[i]);
      } else if (msg.type === "webrtc_ice") {
        const cand = { candidate: p.candidate, sdpMid: p.sdpMid, sdpMLineIndex: p.sdpMLineIndex };
        if (this.remoteSet) await pc.addIceCandidate(cand);
        else this.pendingIce.push(cand);
      }
    } catch (e) {
      console.warn("webrtc signal", e);
    }
  }

  _signal(type, payload) {
    const ws = this.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    ws.send(
      JSON.stringify({
        type: type,
        payload: Object.assign({ v: V, sessionId: this.sessionId }, payload || {}),
      }),
    );
  }

  _failHello(reason) {
    if (this._hello) {
      this._hello.reject(new MediaError(reason));
      this._hello = null;
    }
    this.close(reason, true);
  }

  _onDc(data) {
    if (typeof data === "string") {
      let m;
      try {
        m = JSON.parse(data);
      } catch (e) {
        return;
      }
      if (!m) return;
      if (m.type === "dc_hello") {
        if (!isCurrentVersion(m)) {
          this._failHello("version");
          return;
        }
        if (this._hello) {
          this._hello.resolve(m);
          this._hello = null;
        }
        return;
      }
      const t = this.transfers.get(m.reqId);
      if (!t) return;
      if (m.type === "media_meta") {
        if (t.onMeta) t.onMeta(m);
      } else if (m.type === "media_error") {
        this.transfers.delete(m.reqId);
        if (t.onError) t.onError(new Error(m.error || "media error"));
      }
      return;
    }
    if (!(data instanceof ArrayBuffer) || data.byteLength < HEADER_BYTES) return;
    const dv = new DataView(data);
    if (dv.getUint8(0) !== 1) return;
    const reqId = dv.getUint32(1);
    const eof = (dv.getUint8(9) & FLAG_EOF) !== 0;
    const t = this.transfers.get(reqId);
    if (!t) return;
    if (eof && t.kind === "file") this.transfers.delete(reqId);
    if (t.onChunk) t.onChunk(new Uint8Array(data, HEADER_BYTES), eof);
  }

  send(msg) {
    const dc = this.dc;
    if (!dc || dc.readyState !== "open") throw new MediaError("bye");
    dc.send(JSON.stringify(Object.assign({ v: V }, msg)));
  }

  allocReq() {
    const max = (this.hello && this.hello.maxTransfers) || 2;
    if (this.transfers.size >= max) throw new MediaError("busy");
    const id = this.nextReq;
    this.nextReq = (this.nextReq % 0xfffffff0) + 1;
    return id;
  }

  waitLive(timeoutMs) {
    if (this.liveStream) return Promise.resolve(this.liveStream);
    const self = this;
    return new Promise(function (resolve, reject) {
      const timer = setTimeout(function () {
        reject(new MediaError("timeout"));
      }, timeoutMs);
      self.liveWaiters.push(function (s) {
        clearTimeout(timer);
        resolve(s);
      });
    });
  }

  pauseLive() {
    if (this.livePaused || this.closed) return;
    this.livePaused = true;
    try {
      this.send({ type: "live_pause" });
    } catch (e) {}
  }

  resumeLive() {
    if (!this.livePaused || this.closed) return;
    this.livePaused = false;
    try {
      this.send({ type: "live_resume" });
    } catch (e) {}
  }

  /** Download a recording (basename) or a wall-clock cut as a Blob. */
  transferFile(msg, onProgress) {
    const self = this;
    const reqId = this.allocReq();
    return new Promise(function (resolve, reject) {
      const parts = [];
      let meta = null;
      let got = 0;
      self.transfers.set(reqId, {
        kind: "file",
        onMeta: function (m) {
          meta = m;
        },
        onChunk: function (payload, eof) {
          if (payload.byteLength) {
            parts.push(payload);
            got += payload.byteLength;
            if (onProgress) onProgress(got, meta && meta.size);
          }
          if (eof) {
            resolve({
              blob: new Blob(parts, { type: (meta && meta.mime) || "video/mp4" }),
              name: (meta && meta.name) || "clip.mp4",
            });
          }
        },
        onError: reject,
      });
      try {
        self.send(Object.assign({}, msg, { reqId: reqId }));
      } catch (e) {
        self.transfers.delete(reqId);
        reject(e);
      }
    });
  }

  close(reason, notify) {
    if (this.closed) return;
    this.closed = true;
    this.closeReason = reason || "bye";
    if (notify) this._signal("webrtc_hangup", { reason: this.closeReason });
    if (this._hello) {
      this._hello.reject(new MediaError(this.closeReason));
      this._hello = null;
    }
    clearInterval(this.pingTimer);
    const err = new MediaError(this.closeReason);
    this.transfers.forEach(function (t) {
      if (t.onError) t.onError(err);
    });
    this.transfers.clear();
    try {
      if (this.ws) this.ws.close();
    } catch (e) {}
    try {
      if (this.pc) this.pc.close();
    } catch (e) {}
    this.ws = null;
    this.pc = null;
    this.dc = null;
    if (current === this) current = null;
    if (this.closeReason !== "bye") {
      lastFailAt = Date.now();
      lastFailReason = this.closeReason;
      if (typeof onSessionClosed === "function") onSessionClosed(this.closeReason);
    }
  }
}

/** @type {((reason: string) => void)|null} */
let onSessionClosed = null;

/** UI hook for unexpected hangups (offline, replaced, failed ICE…). */
export function setMediaCloseHandler(fn) {
  onSessionClosed = fn;
}

/** Connected session for the selected car (single-flight). */
export async function getMediaSession() {
  const nodeId = state.selectedNodeId;
  if (current && !current.closed && current.nodeId === nodeId && current.hello) return current;
  if (connecting && current && current.nodeId === nodeId && !current.closed) return connecting;
  // Two tabs on one car would otherwise keep stealing the session from each other.
  const backoff = lastFailReason === "replaced" ? 60000 : RETRY_BACKOFF_MS;
  if (Date.now() - lastFailAt < backoff) throw new MediaError(lastFailReason);
  hangupMediaSession();
  const s = new MediaSession(nodeId);
  current = s;
  connecting = s.connect().then(
    function (x) {
      if (connecting && current === s) connecting = null;
      return x;
    },
    function (e) {
      if (current === s) {
        connecting = null;
        s.close(e && e.reason ? e.reason : "error", true);
      }
      throw e;
    },
  );
  return connecting;
}

export function hangupMediaSession() {
  liveWanted = false;
  const s = current;
  current = null;
  connecting = null;
  if (s) s.close("bye", true);
}

// --- live -------------------------------------------------------------------

/** @param {HTMLVideoElement} video */
export async function startWebRtcLive(video) {
  const s = await getMediaSession();
  if (!s.has("live")) throw new Error("Live video is not available on this car");
  liveWanted = true;
  s.resumeLive();
  const stream = await s.waitLive(10000);
  if (video.srcObject !== stream) {
    try {
      video.removeAttribute("src");
    } catch (e) {}
    video.srcObject = stream;
  }
  video.muted = true;
  video.playsInline = true;
  video.setAttribute("playsinline", "");
  await video.play().catch(function () {});
  return true;
}

/** Detach the live track; keeps the session for playback / downloads. */
export function stopWebRtcLive(video) {
  liveWanted = false;
  if (video && video.srcObject) {
    try {
      video.pause();
    } catch (e) {}
    video.srcObject = null;
  }
  if (current && !current.closed) current.pauseLive();
}

export function isWebRtcLivePlaying(video) {
  return !!(liveWanted && current && !current.closed && video && video.srcObject === current.liveStream);
}

document.addEventListener("visibilitychange", function () {
  const s = current;
  if (!s || s.closed || !liveWanted) return;
  if (document.visibilityState === "hidden") {
    s.pauseLive();
  } else {
    s.resumeLive();
  }
});

// --- downloads / cuts -------------------------------------------------------

export async function downloadRecordingRemote(name, onProgress) {
  const s = await getMediaSession();
  if (!s.has("download")) throw new Error("Downloads are not available on this car");
  return s.transferFile({ type: "download_open", name: name }, onProgress);
}

export async function cutRemote(fromMs, toMs, onProgress) {
  const s = await getMediaSession();
  if (!s.has("cut")) throw new Error("Clip export is not available on this car");
  return s.transferFile({ type: "cut_request", fromMs: fromMs, toMs: toMs }, onProgress);
}

// --- playback (fMP4 → MSE) ---------------------------------------------------

/** @type {MsePlayback|null} */
let activePlayback = null;

/**
 * @param {HTMLVideoElement} video
 * @param {string} name recording basename
 * @param {number} offsetMs media offset inside the recording
 * @param {(e: Error) => void} [onError]
 */
export async function playRecordingRemote(video, name, offsetMs, onError) {
  stopRemotePlayback();
  const s = await getMediaSession();
  if (!s.has("playback")) throw new Error("Playback is not available on this car");
  if (s.hello.playbackContainer && s.hello.playbackContainer !== "fmp4") {
    throw new Error("Unsupported playback container " + s.hello.playbackContainer);
  }
  const pb = new MsePlayback(s, video, s.allocReq(), onError);
  activePlayback = pb;
  pb.start(name, offsetMs);
  return pb;
}

export function stopRemotePlayback() {
  const pb = activePlayback;
  activePlayback = null;
  if (pb) pb.close();
}

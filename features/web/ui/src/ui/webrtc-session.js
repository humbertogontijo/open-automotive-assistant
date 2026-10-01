/**
 * Camera media plane (ADR-0003): one RTCPeerConnection per car carrying one
 * H.264 track per camera (MediaStream id `oaa-cam-<role>`) and the `oaa-media`
 * data channel (car clock, fMP4 playback into MSE, downloads, cuts).
 *
 * Signaling goes to the car's own /api/webrtc/signal when the UI is served by
 * the car (session.role "local") or to the hub's /api/webrtc/signal?node= when
 * it is served by the hub. Media never touches the hub.
 */
import { session } from "../store.js";
import { api } from "../api.js";
import { wsUrl } from "../base.js";
import { t } from "../i18n.js";
import { MsePlayback } from "./mse-playback.js";
import { unwrapRtpMs } from "./camera-stamp.js";

const V = 1;
const DC_LABEL = "oaa-media";
const STREAM_PREFIX = "oaa-cam-";
/** recvonly video m-lines offered; the car answers up to one track per camera. */
const VIDEO_LINES = 4;
const FLAG_EOF = 0x01;
const HEADER_BYTES = 10;
const CONNECT_TIMEOUT_MS = 20000;
const PING_MS = 25000;
const CLOCK_SAMPLES = 5;
const CLOCK_RESYNC_MS = 60000;
/** Car→viewer frames on /api/webrtc/signal; `pong` and anything else is unversioned. */
const SIGNAL_TYPES = ["webrtc_answer", "webrtc_ice", "webrtc_hangup"];

const REASON_TEXT = {
  offline: "Car is offline",
  unsupported: "The car app does not support remote video — update it",
  replaced: "Another viewer opened this car's cameras",
  version: "Media protocol version mismatch — update the car app or hub",
  busy: "The car is busy with other transfers",
  invalid: "Invalid media request",
  error: "Video connection failed",
  timeout: "Video connection timed out",
  bye: "Video connection closed",
};

/** @type {MediaSession|null} */
let current = null;
/** @type {Promise<MediaSession>|null} */
let connecting = null;
/** Back off reconnects after an unexpected hangup so repaints don't hammer the car. */
const RETRY_BACKOFF_MS = 5000;
let lastFailAt = 0;
let lastFailReason = "";
let lastFailKey = "";
/** Roles the UI wants streamed; replayed on every new session. */
let wantedRoles = null;
/** @type {Set<(info: {roles: string[], streaming: string[], cameras: string[]}) => void>} */
const liveListeners = new Set();

function isCurrentVersion(p) {
  return !!p && p.v === V;
}

export function reasonText(reason) {
  const key = String(reason || "error");
  return t("media.reason." + key, REASON_TEXT[key] || key);
}

/** "local" on the car's own UI, "hub:<nodeId>" on the hub, "" when no car is selected. */
function sessionKey() {
  if (session.role === "hub") return session.selectedNodeId ? "hub:" + session.selectedNodeId : "";
  return "local";
}

function signalUrl(key) {
  if (key === "local") return wsUrl("/api/webrtc/signal");
  return wsUrl("/api/webrtc/signal?node=" + encodeURIComponent(key.slice(4)));
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
  constructor(key) {
    this.key = key;
    this.sessionId = randomSessionId();
    /** @type {WebSocket|null} */
    this.ws = null;
    /** @type {RTCPeerConnection|null} */
    this.pc = null;
    /** @type {RTCDataChannel|null} */
    this.dc = null;
    this.hello = null;
    /** @type {Map<string, MediaStream>} */
    this.streams = new Map();
    /** @type {Map<string, RTCRtpReceiver>} */
    this.receivers = new Map();
    /** @type {Array<{role: string, resolve: (s: MediaStream) => void}>} */
    this.streamWaiters = [];
    this.tracks = { roles: [], streaming: [] };
    this.closed = false;
    this.closeReason = "";
    this.transfers = new Map();
    this.nextReq = 1;
    this.pendingIce = [];
    this.remoteSet = false;
    this.pingTimer = 0;
    this.clockTimer = 0;
    this.clockOffsetMs = 0;
    this.clockBestRtt = Infinity;
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
    this._startClockSync();
    return this;
  }

  async _handshake(helloP) {
    const self = this;
    if (typeof RTCPeerConnection !== "function") throw new Error("This browser does not support WebRTC video");
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
    for (let i = 0; i < VIDEO_LINES; i++) pc.addTransceiver("video", { direction: "recvonly" });
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
      const stream = ev.streams && ev.streams[0];
      // m-lines the car left without a track can still fire ontrack.
      if (!stream || String(stream.id).indexOf(STREAM_PREFIX) !== 0) return;
      const role = String(stream.id).slice(STREAM_PREFIX.length);
      self.streams.set(role, stream);
      self.receivers.set(role, ev.receiver);
      self.streamWaiters = self.streamWaiters.filter(function (w) {
        if (w.role !== role) return true;
        w.resolve(stream);
        return false;
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
    const url = signalUrl(this.key);
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
    if (SIGNAL_TYPES.indexOf(msg.type) < 0) return;
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
        if (Array.isArray(p.tracks)) this.tracks.roles = p.tracks.map(String);
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
        const carUtc = Number(m.carUtcMs);
        if (carUtc > 0) this.clockOffsetMs = carUtc - Date.now();
        if (Array.isArray(m.tracks)) this.tracks.roles = m.tracks.map(String);
        if (this._hello) {
          this._hello.resolve(m);
          this._hello = null;
        }
        return;
      }
      if (m.type === "clock") {
        this._onClock(m);
        return;
      }
      if (m.type === "live_tracks") {
        this.tracks = {
          roles: Array.isArray(m.roles) ? m.roles.map(String) : this.tracks.roles,
          streaming: Array.isArray(m.streaming) ? m.streaming.map(String) : [],
        };
        emitLiveTracks(this);
        return;
      }
      const tr = this.transfers.get(m.reqId);
      if (!tr) return;
      if (m.type === "media_meta") {
        if (tr.onMeta) tr.onMeta(m);
      } else if (m.type === "media_progress") {
        if (tr.onProgress) tr.onProgress(m);
      } else if (m.type === "media_error") {
        this.transfers.delete(m.reqId);
        if (tr.onError) tr.onError(new Error(m.error || "media error"));
      }
      return;
    }
    if (!(data instanceof ArrayBuffer) || data.byteLength < HEADER_BYTES) return;
    const dv = new DataView(data);
    if (dv.getUint8(0) !== 1) return;
    const reqId = dv.getUint32(1);
    const eof = (dv.getUint8(9) & FLAG_EOF) !== 0;
    const tr = this.transfers.get(reqId);
    if (!tr) return;
    if (eof && tr.kind === "file") this.transfers.delete(reqId);
    if (tr.onChunk) tr.onChunk(new Uint8Array(data, HEADER_BYTES), eof);
  }

  // --- car clock --------------------------------------------------------------

  _startClockSync() {
    const self = this;
    const round = function () {
      self.clockBestRtt = Infinity;
      for (let i = 0; i < CLOCK_SAMPLES; i++) {
        setTimeout(function () {
          if (self.closed) return;
          try {
            self.send({ type: "clock_sync", t0: Date.now() });
          } catch (e) {}
        }, i * 200);
      }
    };
    round();
    this.clockTimer = setInterval(round, CLOCK_RESYNC_MS);
  }

  /** Keep the lowest-RTT sample of each round. */
  _onClock(m) {
    const now = Date.now();
    const t0 = Number(m.t0);
    const car = Number(m.carUtcMs);
    if (!(t0 > 0) || !(car > 0) || now < t0) return;
    const rtt = now - t0;
    if (rtt > this.clockBestRtt) return;
    this.clockBestRtt = rtt;
    this.clockOffsetMs = car + rtt / 2 - now;
  }

  carNow() {
    return Date.now() + this.clockOffsetMs;
  }

  /** Wall-clock capture time of the frame on screen for `role`. */
  liveWallMs(role, rtpTimestamp) {
    let ts = rtpTimestamp;
    if (ts == null) {
      const rx = this.receivers.get(role);
      const srcs = rx && typeof rx.getSynchronizationSources === "function" ? rx.getSynchronizationSources() : [];
      if (srcs && srcs.length && srcs[0].rtpTimestamp != null) ts = srcs[0].rtpTimestamp;
    }
    const now = this.carNow();
    return ts == null ? now : unwrapRtpMs(ts, now);
  }

  // --- requests -----------------------------------------------------------------

  send(msg) {
    const dc = this.dc;
    if (!dc || dc.readyState !== "open") throw new MediaError("bye");
    dc.send(JSON.stringify(Object.assign({ v: V }, msg)));
  }

  allocReq() {
    const max = (this.hello && this.hello.maxTransfers) || 2;
    if (this.transfers.size >= max) throw new MediaError("busy");
    let id = this.nextReq;
    while (this.transfers.has(id)) id = (id % 0xfffffff0) + 1;
    this.nextReq = (id % 0xfffffff0) + 1;
    return id;
  }

  waitStream(role, timeoutMs) {
    const have = this.streams.get(role);
    if (have) return Promise.resolve(have);
    const self = this;
    return new Promise(function (resolve, reject) {
      const w = {
        role: role,
        resolve: function (s) {
          clearTimeout(timer);
          resolve(s);
        },
      };
      const timer = setTimeout(function () {
        self.streamWaiters = self.streamWaiters.filter(function (x) {
          return x !== w;
        });
        reject(new MediaError("timeout"));
      }, timeoutMs);
      self.streamWaiters.push(w);
    });
  }

  select(roles) {
    try {
      this.send({ type: "live_select", roles: roles });
    } catch (e) {}
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

  /**
   * Download a recording (basename) or a per-camera cut as a Blob.
   * `onProgress({phase, done, total})`: phase "encode" (car-side export, ms)
   * then "transfer" (bytes).
   */
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
          if (onProgress) onProgress({ phase: "transfer", done: 0, total: Number(m.size) || 0 });
        },
        onProgress: function (m) {
          if (onProgress) onProgress({ phase: "encode", done: Number(m.doneMs) || 0, total: Number(m.totalMs) || 0 });
        },
        onChunk: function (payload, eof) {
          if (payload.byteLength) {
            parts.push(payload);
            got += payload.byteLength;
            if (onProgress) onProgress({ phase: "transfer", done: got, total: (meta && Number(meta.size)) || 0 });
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
    clearInterval(this.clockTimer);
    const err = new MediaError(this.closeReason);
    this.transfers.forEach(function (tr) {
      if (tr.onError) tr.onError(err);
    });
    this.transfers.clear();
    this.streamWaiters = [];
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
      lastFailKey = this.key;
      if (typeof onSessionClosed === "function") onSessionClosed(this.closeReason);
    }
  }
}

function emitLiveTracks(s) {
  const info = {
    roles: s.tracks.roles.slice(),
    streaming: s.tracks.streaming.slice(),
    cameras: s.hello && Array.isArray(s.hello.cameras) ? s.hello.cameras.map(String) : [],
  };
  liveListeners.forEach(function (fn) {
    try {
      fn(info);
    } catch (e) {}
  });
}

/** @type {((reason: string) => void)|null} */
let onSessionClosed = null;

/** UI hook for unexpected hangups (offline, replaced, failed ICE…). */
export function setMediaCloseHandler(fn) {
  onSessionClosed = fn;
}

/** `live_tracks` updates (which cameras are sending); returns an unsubscribe. */
export function onLiveTracks(fn) {
  liveListeners.add(fn);
  return function () {
    liveListeners.delete(fn);
  };
}

/** Connected session for the current car (single-flight). */
export async function getMediaSession() {
  const key = sessionKey();
  if (!key) throw new MediaError("offline");
  if (current && !current.closed && current.key === key && current.hello) return current;
  if (connecting && current && current.key === key && !current.closed) return connecting;
  // Two tabs on one car would otherwise keep stealing the session from each other.
  const backoff = lastFailReason === "replaced" ? 60000 : RETRY_BACKOFF_MS;
  if (lastFailKey === key && Date.now() - lastFailAt < backoff) throw new MediaError(lastFailReason);
  closeCurrent();
  const s = new MediaSession(key);
  current = s;
  connecting = s.connect().then(
    function (x) {
      if (connecting && current === s) connecting = null;
      if (wantedRoles) x.select(wantedRoles);
      emitLiveTracks(x);
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

function closeCurrent() {
  const s = current;
  current = null;
  connecting = null;
  if (s) s.close("bye", true);
}

export function hangupMediaSession() {
  wantedRoles = null;
  playbacks.forEach(function (pb) {
    pb.close();
  });
  playbacks.clear();
  closeCurrent();
}

/** Car wall clock (ms), from the data-channel clock sync; local clock until connected. */
export function carNow() {
  return current && !current.closed ? current.carNow() : Date.now();
}

/** Cameras the car reported in dc_hello. */
export function carCameras() {
  return current && current.hello && Array.isArray(current.hello.cameras) ? current.hello.cameras.map(String) : [];
}

/** Longest per-camera cut the car accepts (ms). */
export function maxCutMs() {
  return (current && current.hello && Number(current.hello.maxCutMs)) || 10 * 60 * 1000;
}

// --- live -------------------------------------------------------------------

/** Tell the car which cameras to send (the rest stay idle). */
export function selectLive(roles) {
  wantedRoles = roles.slice();
  const s = current;
  if (s && !s.closed && s.hello) {
    s.select(wantedRoles);
    if (wantedRoles.length) s.resumeLive();
  }
}

/**
 * @param {string} role
 * @param {HTMLVideoElement} video
 */
export async function attachLive(role, video) {
  const s = await getMediaSession();
  if (!s.has("live")) throw new Error("Live video is not available on this car");
  if (s.tracks.roles.length && s.tracks.roles.indexOf(role) < 0) throw new Error("No live track for this camera");
  s.resumeLive();
  const stream = await s.waitStream(role, 10000);
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

/** Detach a live track from its <video>; keeps the session. */
export function detachLive(video) {
  if (video && video.srcObject) {
    try {
      video.pause();
    } catch (e) {}
    video.srcObject = null;
  }
}

/** Wall-clock capture time of the live frame (rVFC `metadata.rtpTimestamp` when available). */
export function liveFrameWallMs(role, rtpTimestamp) {
  const s = current;
  if (!s || s.closed) return Date.now();
  return s.liveWallMs(role, rtpTimestamp);
}

document.addEventListener("visibilitychange", function () {
  const s = current;
  if (!s || s.closed || !wantedRoles || !wantedRoles.length) return;
  if (document.visibilityState === "hidden") {
    s.pauseLive();
  } else {
    s.resumeLive();
  }
});

// --- downloads / cuts -------------------------------------------------------

/** Export one camera's wall-clock range (timestamp burned in by the car). */
export async function cutRemote(role, fromMs, toMs, onProgress) {
  const s = await getMediaSession();
  if (!s.has("cut")) throw new Error("Clip export is not available on this car");
  return s.transferFile({ type: "cut_request", role: role, fromMs: fromMs, toMs: toMs }, onProgress);
}

// --- playback (fMP4 → MSE) ---------------------------------------------------

/** @type {Map<HTMLVideoElement, MsePlayback>} */
const playbacks = new Map();

/**
 * @param {HTMLVideoElement} video
 * @param {string} name recording basename
 * @param {number} offsetMs media offset inside the recording
 * @param {(e: Error) => void} [onError]
 */
export async function playRecordingRemote(video, name, offsetMs, onError) {
  stopRemotePlayback(video);
  const s = await getMediaSession();
  if (!s.has("playback")) throw new Error("Playback is not available on this car");
  if (s.hello.playbackContainer && s.hello.playbackContainer !== "fmp4") {
    throw new Error("Unsupported playback container " + s.hello.playbackContainer);
  }
  const pb = new MsePlayback(s, video, s.allocReq(), onError);
  playbacks.set(video, pb);
  pb.start(name, offsetMs);
  return pb;
}

/** Stop playback on `video`, or on every video when omitted. */
export function stopRemotePlayback(video) {
  if (video) {
    const pb = playbacks.get(video);
    playbacks.delete(video);
    if (pb) pb.close();
    return;
  }
  playbacks.forEach(function (pb) {
    pb.close();
  });
  playbacks.clear();
}

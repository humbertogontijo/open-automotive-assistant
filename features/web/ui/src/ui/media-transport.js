/**
 * Where camera media comes from for the selected car:
 *   local  → the car's own HTTP (HLS live, progressive MP4, /api/dvr/cut)
 *   webrtc → the hub media plane (webrtc-session.js); nothing heavy goes through RPC
 * Camera modules call mediaTransport() instead of branching on the host role.
 */
import { session } from "../store.js";
import { api } from "../api.js";
import {
  startWebRtcLive,
  stopWebRtcLive,
  isWebRtcLivePlaying,
  playRecordingRemote,
  stopRemotePlayback,
  cutRemote,
  hangupMediaSession,
} from "./webrtc-session.js";

const HLS_SRC = "/api/dvr/live.m3u8";
const WEBRTC_SRC = "webrtc:live";

/** hls.js is only needed for local live, so it ships as its own chunk. */
/** @type {typeof import("./live-h264.js") | null} */
let hlsLive = null;
async function loadHlsLive() {
  if (!hlsLive) hlsLive = await import("./live-h264.js");
  return hlsLive;
}

function recordingUrl(name) {
  return "/api/dvr/recordings/" + encodeURIComponent(name) + "?inline=1&t=" + Date.now();
}

async function responseError(res, fallback) {
  try {
    const j = await res.json();
    if (j && j.error) return new Error(j.error);
  } catch (e) {}
  return new Error(fallback);
}

const local = {
  liveSrc: HLS_SRC,
  /** Local viewers hold the car's preview seat over HTTP. */
  async acquireLive() {
    const start = await api("/api/dvr/preview/start", { method: "POST" });
    if (!start || start.ok === false) {
      throw new Error((start && start.status && start.status.lastError) || "preview start failed");
    }
  },
  async releaseLive() {
    try {
      await api("/api/dvr/preview/stop", { method: "POST" });
    } catch (e) {}
  },
  close() {
    return local.releaseLive();
  },
  async startLive(video) {
    return (await loadHlsLive()).startH264Live(video);
  },
  stopLive(video) {
    if (hlsLive) hlsLive.stopH264Live(video);
  },
  isLivePlaying: function () {
    return !!hlsLive && hlsLive.isLivePlaying();
  },
  async playRecording(video, name) {
    video.src = recordingUrl(name);
    video.load();
  },
  stopPlayback() {},
  async cut(fromMs, toMs) {
    const url =
      "/api/dvr/cut?fromMs=" + encodeURIComponent(String(fromMs)) + "&toMs=" + encodeURIComponent(String(toMs));
    const res = await fetch(url, { credentials: "same-origin" });
    const ctype = (res.headers.get("content-type") || "").toLowerCase();
    if (!res.ok || ctype.indexOf("json") >= 0) throw await responseError(res, "cut failed");
    const m = /filename="?([^";]+)"?/i.exec(res.headers.get("content-disposition") || "");
    return { blob: await res.blob(), name: (m && m[1]) || "clip.mp4" };
  },
};

const webrtc = {
  liveSrc: WEBRTC_SRC,
  /** The WebRTC session holds its own seat on the car. */
  async acquireLive() {},
  /** Pausing the track (stopLive) is enough; the session stays up for playback. */
  async releaseLive() {},
  close() {
    hangupMediaSession();
  },
  startLive: startWebRtcLive,
  stopLive: stopWebRtcLive,
  isLivePlaying: isWebRtcLivePlaying,
  playRecording: playRecordingRemote,
  stopPlayback: stopRemotePlayback,
  cut: cutRemote,
};

function hubMedia() {
  return session.role === "hub" && !!session.selectedNodeId && typeof RTCPeerConnection === "function";
}

/**
 * Transport for the selected car. Pass the current live `src` to get the transport
 * that is actually playing it (it may differ after the selection changed).
 */
export function mediaTransport(src) {
  if (src === WEBRTC_SRC) return webrtc;
  if (src === HLS_SRC) return local;
  return hubMedia() ? webrtc : local;
}

/** True for the live source of either transport. */
export function isLiveSrc(src) {
  return src === HLS_SRC || src === WEBRTC_SRC;
}

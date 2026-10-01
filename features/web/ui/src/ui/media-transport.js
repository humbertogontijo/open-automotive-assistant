/**
 * Where recorded camera media comes from for the current car. Live video is
 * always WebRTC (webrtc-session.js); recordings and cuts depend on the host:
 *   local → the car's own HTTP (progressive MP4 with Range); cuts over the data
 *           channel for progress, HTTP /api/dvr/cut as fallback
 *   hub   → the WebRTC data channel (fMP4 into MSE, cuts with progress)
 * Camera modules call mediaTransport() instead of branching on the host role.
 */
import { session } from "../store.js";
import { appUrl } from "../base.js";
import { playRecordingRemote, stopRemotePlayback, cutRemote } from "./webrtc-session.js";

function recordingUrl(name) {
  return appUrl("/api/dvr/recordings/" + encodeURIComponent(name) + "?inline=1");
}

async function responseError(res, fallback) {
  try {
    const j = await res.json();
    if (j && j.error) return new Error(j.error);
  } catch (e) {}
  return new Error(fallback);
}

async function httpCut(role, fromMs, toMs, onProgress) {
  if (onProgress) onProgress({ phase: "encode", done: 0, total: 0 });
  const url = appUrl(
    "/api/dvr/cut?role=" +
      encodeURIComponent(role) +
      "&fromMs=" +
      encodeURIComponent(String(fromMs)) +
      "&toMs=" +
      encodeURIComponent(String(toMs)),
  );
  const res = await fetch(url, { credentials: "same-origin" });
  const ctype = (res.headers.get("content-type") || "").toLowerCase();
  if (!res.ok || ctype.indexOf("json") >= 0) throw await responseError(res, "cut failed");
  const m = /filename="?([^";]+)"?/i.exec(res.headers.get("content-disposition") || "");
  return { blob: await res.blob(), name: (m && m[1]) || "clip_" + role + ".mp4" };
}

const local = {
  kind: "local",
  /** @param {HTMLVideoElement} video */
  async playRecording(video, name) {
    try {
      video.srcObject = null;
    } catch (e) {}
    video.src = recordingUrl(name);
    video.load();
  },
  /** @param {HTMLVideoElement} [video] */
  stopPlayback(video) {
    if (!video || !video.getAttribute("src")) return;
    try {
      video.pause();
      video.removeAttribute("src");
      video.load();
    } catch (e) {}
  },
  async cut(role, fromMs, toMs, onProgress) {
    try {
      return await cutRemote(role, fromMs, toMs, onProgress);
    } catch (e) {
      // `reason` marks a session failure (offline, busy, timeout…); car errors for the request itself don't retry.
      if (!e || !e.reason) throw e;
      return httpCut(role, fromMs, toMs, onProgress);
    }
  },
};

const hub = {
  kind: "hub",
  playRecording: playRecordingRemote,
  stopPlayback: stopRemotePlayback,
  cut: cutRemote,
};

export function mediaTransport() {
  return session.role === "hub" ? hub : local;
}

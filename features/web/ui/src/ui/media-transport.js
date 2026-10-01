/**
 * Clip export for the current car. Live video and recordings both play over
 * WebRTC (webrtc-session.js); cuts go over the data channel for progress, and
 * on the car's own page fall back to HTTP /api/dvr/cut when the session fails.
 * Camera modules call mediaTransport() instead of branching on the host role.
 */
import { session } from "../store.js";
import { appUrl } from "../base.js";
import { cutRemote } from "./webrtc-session.js";

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
  cut: cutRemote,
};

export function mediaTransport() {
  return session.role === "hub" ? hub : local;
}

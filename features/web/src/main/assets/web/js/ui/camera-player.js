/**
 * Camera player core: live on <video> and wall-clock DVR playback over closed
 * oaa_dvr_*.mp4 segments. Media comes through media-transport.js (car HTTP or
 * hub WebRTC). The scrubber lives in camera-timeline-view.js and the clip
 * range in camera-cut.js; both repaint through [onPlayerEvent].
 */
import { loadCss } from "./load-css.js";
loadCss("/static/js/ui/camera-player.css");

import { html, nothing } from "../lit.js";
import { state, patch } from "../store.js";
import { t } from "../i18n.js";
import { api, errText } from "../api.js";
import { mediaTransport, isLiveSrc } from "./media-transport.js";
import { LIVE_EDGE_TIP_MS, todayKey, timelineRangeForDay } from "./dvr-timeline.js";

export { isLiveSrc };

const FRAME_MS = 100;
export const SPEED_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2];

let liveActive = false;
let liveBusy = false;
/** @type {{ name: string, startUtcMs: number, endUtcMs: number, durationMs: number, positionMs: number, timer: number|null }|null} */
let playback = null;
let seeking = false;
let playAtBusy = false;
/** @type {((() => Promise<void>)|null)} */
let liveStarter = null;
/** @type {{ video: HTMLVideoElement, handlers: Array<[string, EventListener]> }|null} */
let streamListeners = null;

const hooks = { sync: [], live: [] };

/**
 * "sync": repaint transport DOM (playhead, cut range…) without a lit render.
 * "live": the player went back to today's live edge.
 */
export function onPlayerEvent(name, fn) {
  hooks[name].push(fn);
}

function emit(name) {
  hooks[name].forEach(function (fn) {
    fn();
  });
}

export function setLiveStarter(fn) {
  if (typeof fn === "function") liveStarter = fn;
}

export function getLiveStarter() {
  return liveStarter;
}

export function isRecordingPlayback() {
  return state.cameraPlayerMode === "dvr";
}

export function canPlayRecording(name) {
  return typeof name === "string" && /\.mp4$/i.test(name);
}

export function playerVideo() {
  return document.getElementById("cameraPlayerVideo");
}

export function fmtWall(ms) {
  const n = Number(ms);
  if (!n) return "—";
  try {
    return new Date(n).toLocaleString(undefined, {
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
  } catch (e) {
    return String(ms);
  }
}

export function timeline() {
  return state.dvrTimeline || {};
}

export function selectedDayKey() {
  const k = state.dvrTimelineDay;
  if (typeof k === "string" && /^\d{4}-\d{2}-\d{2}$/.test(k)) return k;
  return todayKey();
}

/**
 * Fixed local-day window [midnight, midnight+24h].
 * For today, live caps scrubbing at "now"; the track still spans the full day.
 */
export function timelineRange() {
  return timelineRangeForDay(selectedDayKey(), timeline());
}

export function wallPlayheadMs() {
  if (state.cameraPlayerMode === "dvr" && playback) {
    const v = playerVideo();
    if (v && Number.isFinite(v.currentTime)) {
      return playback.startUtcMs + Math.max(0, Math.floor(v.currentTime * 1000));
    }
    return playback.startUtcMs + (playback.positionMs || 0);
  }
  const r = timelineRange();
  if (r.isToday && state.cameraPlayerMode === "live" && r.liveAt) {
    return r.liveAt;
  }
  return Number(state.cameraTimelineAtMs) || r.scrubMax || r.liveAt || Date.now();
}

export function liveEdgeTipMs(r) {
  if (!r || !r.start || !r.end || r.end <= r.start) return LIVE_EDGE_TIP_MS;
  const span = r.end - r.start;
  return Math.max(120, Math.min(LIVE_EDGE_TIP_MS, Math.floor(span * 0.002)));
}

export function isLiveEdgeWall(wallMs) {
  const r = timelineRange();
  if (!r.isToday || r.scrubMax == null) return false;
  return wallMs >= r.scrubMax - liveEdgeTipMs(r);
}

// --- speed ------------------------------------------------------------------

export function currentSpeed() {
  const n = Number(state.cameraPlaybackRate);
  return n > 0 ? n : 1;
}

export function formatSpeed(rate) {
  const r = Number(rate) || 1;
  if (r === 1) return "1×";
  return String(r).replace(/\.0$/, "") + "×";
}

function applyPlaybackRate() {
  const v = playerVideo();
  if (!v) return;
  try {
    v.playbackRate = currentSpeed();
  } catch (e) {}
}

function resetPlaybackSpeed() {
  if (currentSpeed() !== 1) patch({ cameraPlaybackRate: 1 });
  applyPlaybackRate();
}

export function nudgeSpeed(dir) {
  const cur = currentSpeed();
  let i = 0;
  let best = Infinity;
  for (let k = 0; k < SPEED_STEPS.length; k++) {
    const d = Math.abs(SPEED_STEPS[k] - cur);
    if (d < best) {
      best = d;
      i = k;
    }
  }
  i = Math.max(0, Math.min(SPEED_STEPS.length - 1, i + dir));
  patch({ cameraPlaybackRate: SPEED_STEPS[i] });
  syncTransport();
}

/** Record the playhead, repaint transport DOM and apply the playback rate. */
export function syncTransport() {
  state.cameraTimelineAtMs = wallPlayheadMs();
  emit("sync");
  applyPlaybackRate();
}

// --- recording playback -----------------------------------------------------

function removeStreamListeners() {
  if (!streamListeners) return;
  const v = streamListeners.video;
  streamListeners.handlers.forEach(function (h) {
    v.removeEventListener(h[0], h[1]);
  });
  streamListeners = null;
}

function detachVideo() {
  removeStreamListeners();
  const media = mediaTransport();
  media.stopPlayback();
  const v = playerVideo();
  if (!v) return;
  if (v.srcObject) media.stopLive(v);
  try {
    v.pause();
    v.removeAttribute("src");
    v.load();
  } catch (e) {}
}

function attachStream(name, fromMs) {
  const v = playerVideo();
  if (!v || !playback || !canPlayRecording(name)) return;
  removeStreamListeners();
  const startSec = Math.max(0, (fromMs || 0) / 1000);
  try {
    v.muted = false;
    v.setAttribute("playsinline", "");
    v.setAttribute("webkit-playsinline", "");
  } catch (e) {}
  const onMeta = function () {
    v.removeEventListener("loadedmetadata", onMeta);
    try {
      if (startSec > 0 && Number.isFinite(v.duration)) {
        v.currentTime = Math.min(startSec, Math.max(0, v.duration - 0.05));
      }
      v.play().catch(function () {});
    } catch (e) {}
  };
  const onWaiting = function () {
    patch({ cameraPlaybackLoading: true });
  };
  const onPlaying = function () {
    patch({ cameraPlaybackLoading: false });
  };
  streamListeners = {
    video: v,
    handlers: [
      ["loadedmetadata", onMeta],
      ["waiting", onWaiting],
      ["playing", onPlaying],
      ["canplay", onPlaying],
    ],
  };
  streamListeners.handlers.forEach(function (h) {
    v.addEventListener(h[0], h[1]);
  });
  const onError = function (e) {
    patch({ cameraPlaybackLoading: false, cameraPreviewError: errText(e) });
  };
  Promise.resolve(mediaTransport().playRecording(v, name, fromMs || 0, onError)).catch(onError);
}

function clearTimer() {
  if (playback && playback.timer != null) {
    clearInterval(playback.timer);
    playback.timer = null;
  }
}

/** Jump to the next closed segment, live edge, or pause — never snap-loop. */
function advanceAfterSegment() {
  if (!playback || playAtBusy) return;
  clearTimer();
  const segs = (timeline().segments || []).slice().sort(function (a, b) {
    return Number(a.startUtcMs) - Number(b.startUtcMs);
  });
  const curStart = Number(playback.startUtcMs) || 0;
  const next = segs.find(function (s) {
    return Number(s.startUtcMs) > curStart + 250;
  });
  if (next) {
    playAt(Number(next.startUtcMs), liveStarter).catch(function () {});
    return;
  }
  if (timelineRange().recording) {
    backToLive(liveStarter).catch(function () {});
    return;
  }
  patch({ cameraPlaybackPaused: true });
  syncTransport();
}

function tickClock() {
  if (!playback || seeking || state.cameraPlaybackPaused || playAtBusy) return;
  const v = playerVideo();
  if (v) {
    if (v.ended) {
      advanceAfterSegment();
      return;
    }
    if (Number.isFinite(v.currentTime)) {
      playback.positionMs = Math.floor(v.currentTime * 1000);
    }
    // Near media end without ended event: advance once.
    if (playback.durationMs > 0 && playback.positionMs >= playback.durationMs - 200) {
      advanceAfterSegment();
      return;
    }
    syncTransport();
    return;
  }
  playback.positionMs = Math.min(playback.durationMs, playback.positionMs + FRAME_MS);
  if (playback.positionMs >= playback.durationMs) {
    advanceAfterSegment();
    return;
  }
  syncTransport();
}

function startClock() {
  clearTimer();
  if (!playback) return;
  playback.timer = setInterval(tickClock, FRAME_MS);
}

export function stopRecordingPlayback() {
  clearTimer();
  detachVideo();
  playback = null;
  seeking = false;
  if (state.cameraPlayerMode === "dvr") {
    patch({
      cameraPlayerMode: "live",
      cameraPlayingName: "",
      cameraPlayingKind: "",
      cameraPlaybackPaused: false,
      cameraPlaybackLoading: false,
      cameraPlaybackDurationMs: 0,
      cameraPreviewError: "",
    });
  }
}

/**
 * @param {string} name
 * @param {{ durationMs?: number, kind?: string, offsetMs?: number, startUtcMs?: number, endUtcMs?: number, paused?: boolean }} [opts]
 */
export async function playRecording(name, opts) {
  if (!canPlayRecording(name)) {
    patch({
      cameraPreviewError: t("cameras.play.unsupported", "This file cannot be played"),
    });
    return;
  }
  clearTimer();
  detachVideo();
  const media = mediaTransport();
  media.stopLive(playerVideo());
  liveActive = false;
  seeking = false;

  // While recording, the preview seat is shared with the DVR writer; keep it.
  if (!(state.status && state.status.dvr && state.status.dvr.recording)) {
    await media.releaseLive();
  }

  const durationMs = Number(opts && opts.durationMs) > 0 ? Number(opts.durationMs) : 0;
  const startUtcMs = Number(opts && opts.startUtcMs) || 0;
  const endUtcMs = Number(opts && opts.endUtcMs) || (startUtcMs ? startUtcMs + durationMs : 0);
  const offsetMs = Math.max(0, Number(opts && opts.offsetMs) || 0);

  playback = {
    name: name,
    startUtcMs: startUtcMs,
    endUtcMs: endUtcMs || startUtcMs + durationMs,
    durationMs: Math.max(0, durationMs),
    positionMs: offsetMs,
    timer: null,
  };
  patch({
    cameraPlayerMode: "dvr",
    cameraPlayingName: name,
    cameraPlayingKind: "dvr",
    cameraPlaybackPaused: !!(opts && opts.paused),
    cameraPlaybackLoading: true,
    cameraPlaybackDurationMs: playback.durationMs,
    cameraTimelineAtMs: startUtcMs + offsetMs,
    cameraPreviewActive: false,
    cameraPreviewSrc: "",
    cameraPreviewError: "",
  });
  requestAnimationFrame(function () {
    if (!playback || playback.name !== name) return;
    attachStream(name, offsetMs);
    applyPlaybackRate();
    if (opts && opts.paused) {
      const v = playerVideo();
      if (v) v.pause();
      clearTimer();
      patch({ cameraPlaybackLoading: false });
    } else {
      startClock();
    }
    syncTransport();
  });
}

/** Seek wall-clock; snaps gaps server-side. At the live edge → live. */
export async function playAt(wallUtcMs, startLive) {
  const at = Number(wallUtcMs);
  if (!at || playAtBusy) return;
  if (isLiveEdgeWall(at)) {
    await backToLive(startLive);
    syncTransport();
    return;
  }
  playAtBusy = true;
  patch({ cameraPlaybackLoading: true, cameraPreviewError: "" });
  try {
    const res = await api("/api/dvr/play?atMs=" + encodeURIComponent(String(at)));
    if (!res || res.ok === false) {
      throw new Error((res && res.error) || "play failed");
    }
    if (res.live) {
      await backToLive(startLive);
      syncTransport();
      return;
    }
    // Same segment near end → avoid reload thrash; just pause at end.
    if (
      playback &&
      playback.name === res.name &&
      Math.abs(Number(res.offsetMs) - (playback.positionMs || 0)) < 400 &&
      Number(res.offsetMs) >= Number(res.durationMs) - 500
    ) {
      patch({ cameraPlaybackPaused: true, cameraPlaybackLoading: false });
      return;
    }
    await playRecording(res.name, {
      durationMs: res.durationMs,
      offsetMs: res.offsetMs,
      startUtcMs: res.startUtcMs,
      endUtcMs: res.endUtcMs,
      kind: "dvr",
    });
    // Seal may have grown the timeline — refresh quietly.
    try {
      const { loadRecordings } = await import("../pages/cameras.js");
      await loadRecordings();
    } catch (e) {}
  } catch (e) {
    patch({ cameraPlaybackLoading: false, cameraPreviewError: errText(e) });
  } finally {
    playAtBusy = false;
  }
}

export async function togglePlaybackPause() {
  // Live: pause freezes the buffer, or timeshifts into sealed DVR near live.
  if (state.cameraPlayerMode === "live") {
    if (state.cameraPlaybackPaused) {
      patch({ cameraPlaybackPaused: false });
      const v = playerVideo();
      if (v) {
        applyPlaybackRate();
        v.play().catch(function () {});
      }
      syncTransport();
      return;
    }
    const r = timelineRange();
    if (r.isToday && r.segs && r.segs.length) {
      const tip = liveEdgeTipMs(r);
      const max = r.scrubMax != null ? r.scrubMax : r.end - 1;
      let at = wallPlayheadMs();
      if (at >= max - tip) at = max - tip - 1;
      at = Math.max(r.start, Math.min(Math.floor(at), max));
      await playAt(at, liveStarter);
      if (state.cameraPlayerMode === "dvr" && playback) {
        patch({ cameraPlaybackPaused: true });
        const v = playerVideo();
        if (v) v.pause();
        clearTimer();
        syncTransport();
        return;
      }
    }
    const v = playerVideo();
    if (v) v.pause();
    patch({ cameraPlaybackPaused: true });
    syncTransport();
    return;
  }

  if (!playback) return;
  const next = !state.cameraPlaybackPaused;
  patch({ cameraPlaybackPaused: next });
  const v = playerVideo();
  if (v) {
    if (next) v.pause();
    else {
      if (v.ended) v.currentTime = 0;
      applyPlaybackRate();
      v.play().catch(function () {});
      startClock();
    }
  }
  if (next) clearTimer();
}

export function seekPlaybackSeconds(sec) {
  if (!playback) return;
  const ms = Math.max(0, Math.min(playback.durationMs, Number(sec) * 1000));
  playback.positionMs = ms;
  syncTransport();
  const v = playerVideo();
  if (v) {
    try {
      v.currentTime = ms / 1000;
    } catch (e) {}
    if (!state.cameraPlaybackPaused) v.play().catch(function () {});
  }
}

export async function backToLive(startLive) {
  stopRecordingPlayback();
  emit("live");
  resetPlaybackSpeed();
  patch({
    dvrTimelineDay: null,
    cameraTimelineAtMs: Date.now(),
    cameraPlaybackPaused: false,
  });
  if (typeof startLive === "function") await startLive();
  applyPlaybackRate();
}

// --- live -------------------------------------------------------------------

/** Attach / keep live on <video> for `state.cameraPreviewSrc`. */
export async function applyCameraPlayerSrc() {
  if (state.cameraPlayerMode === "dvr") return;
  const want = state.cameraPreviewSrc || "";
  const video = playerVideo();
  const media = mediaTransport(want);
  if (!isLiveSrc(want) || !video) {
    if (liveActive || media.isLivePlaying(video)) {
      media.stopLive(video);
      liveActive = false;
    }
    if (video) {
      video.style.display = "none";
      video.dataset.oaaSrc = "";
    }
    return;
  }
  video.style.display = "";
  if (liveActive && media.isLivePlaying(video)) {
    video.dataset.oaaSrc = want;
    return;
  }
  if (video.dataset.oaaSrc === want && liveActive) return;
  if (liveBusy) return;
  liveBusy = true;
  video.dataset.oaaSrc = want;
  let error = "";
  try {
    let ok = false;
    try {
      ok = await media.startLive(video);
    } catch (e) {
      error = errText(e);
    }
    liveActive = !!ok;
    applyPlaybackRate();
    if (state.cameraPlaybackPaused) {
      try {
        video.pause();
      } catch (e) {}
    }
    if (!ok) {
      video.style.display = "none";
      const msg = error || t("cameras.live.failed", "Live H.264 stream failed — check device logs");
      if (state.cameraPreviewError !== msg) patch({ cameraPreviewError: msg });
    } else if (state.cameraPreviewError) {
      patch({ cameraPreviewError: "" });
    }
  } finally {
    liveBusy = false;
  }
}

export function cameraPlayerView(opts) {
  const mode = state.cameraPlayerMode || "live";
  const playingName = state.cameraPlayingName || "";
  const err = state.cameraPreviewError || "";
  const lastError = (opts && opts.lastError) || "";
  setLiveStarter(opts && opts.startLive);
  const useVideo = mode === "live" || (mode === "dvr" && canPlayRecording(playingName));
  const label = mode === "dvr" ? t("cameras.timeline.dvr", "DVR") : t("cameras.live", "Live");
  const loading = !!state.cameraPlaybackLoading && mode === "dvr";
  const embedded = !!(opts && opts.embedded);

  return html`
    <div
      class=${"card camera-player" + (embedded ? " camera-player-fill" : "")}
      style=${embedded ? "margin:0" : "margin-top:18px"}
    >
      <div
        class="row camera-player-bar"
        style="justify-content:space-between;align-items:center;margin:0 0 10px;gap:8px;flex-wrap:wrap"
      >
        <div class="row" style="margin:0;gap:8px;align-items:center;min-width:0;flex:1 1 auto">
          <span class="badge camera-player-label ${mode === "dvr" ? "accent" : "ok"}" title=${label}
            >${label}</span
          >
          ${loading
            ? html`<span class="sub mono camera-player-loading">${t("cameras.play.loading", "Loading…")}</span>`
            : nothing}
        </div>
      </div>
      <div class="camera-preview-wrap">
        <video
          class="preview"
          id="cameraPlayerVideo"
          playsinline
          ?muted=${mode === "live"}
          style=${useVideo ? "" : "display:none"}
        ></video>
        ${loading
          ? html`<div class="camera-preview-loading" aria-live="polite">${t("cameras.play.loading", "Loading…")}</div>`
          : nothing}
      </div>
      ${err ? html`<p class="sub">${err}</p>` : nothing}
      ${lastError ? html`<p class="sub" style="color:var(--warn)">${lastError}</p>` : nothing}
    </div>
  `;
}

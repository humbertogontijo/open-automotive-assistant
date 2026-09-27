/**
 * Camera player core: live on <video> and wall-clock DVR playback over closed
 * oaa_dvr_*.mp4 segments. Media comes through media-transport.js (car HTTP or
 * hub WebRTC). <oaa-camera-player> owns the <video> and the live seat for as long
 * as it is mounted; the scrubber (<oaa-camera-timeline>) follows via [onPlayerEvent].
 */

import { html, nothing } from "lit";
import { OaaElement } from "../lit/oaa-element.js";
import { camera, dvr, session } from "../store.js";
import { t } from "../i18n.js";
import { api, errText } from "../api.js";
import { mediaTransport, isLiveSrc } from "./media-transport.js";
import { setMediaCloseHandler, reasonText } from "./webrtc-session.js";
import { LIVE_EDGE_TIP_MS, isLiveEdgeWall, todayKey, timelineRangeForDay } from "./dvr-timeline.js";

export { isLiveSrc };

const FRAME_MS = 100;
export const SPEED_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2];

let liveActive = false;
let liveBusy = false;
/** Bumped by stopLive so a start still awaiting its seat gives it back. */
let liveGeneration = 0;
/** @type {{ name: string, startUtcMs: number, endUtcMs: number, durationMs: number, positionMs: number, timer: number|null }|null} */
let playback = null;
let playAtBusy = false;
/** Last known playhead (wall UTC) when neither live nor a recording drives it. */
let timelineAtMs = 0;
/** @type {HTMLVideoElement|null} */
let videoEl = null;
/** @type {{ video: HTMLVideoElement, handlers: Array<[string, EventListener]> }|null} */
let streamListeners = null;

/** @type {{ sync: Set<() => void>, live: Set<() => void> }} */
const hooks = { sync: new Set(), live: new Set() };

/**
 * "sync": the playhead moved (10 Hz while playing).
 * "live": the player went back to today's live edge.
 * @param {"sync" | "live"} name
 * @param {() => void} fn
 * @returns {() => void} unsubscribe
 */
export function onPlayerEvent(name, fn) {
  hooks[name].add(fn);
  return function () {
    hooks[name].delete(fn);
  };
}

function emit(name) {
  hooks[name].forEach(function (fn) {
    fn();
  });
}

setMediaCloseHandler(function (reason) {
  if (!videoEl) return;
  camera.$patch({ previewActive: false, previewSrc: "", previewError: reasonText(reason) });
});

export function isRecordingPlayback() {
  return camera.mode === "dvr";
}

export function canPlayRecording(name) {
  return typeof name === "string" && /\.mp4$/i.test(name);
}

export function playerVideo() {
  return videoEl;
}

const FMT_WALL = new Intl.DateTimeFormat(undefined, {
  month: "short",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
});

export function fmtWall(ms) {
  const n = Number(ms);
  return n ? FMT_WALL.format(n) : "—";
}

export function timeline() {
  return dvr.timeline || { segments: [], recording: false };
}

export function selectedDayKey() {
  const k = dvr.timelineDay;
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
  if (camera.mode === "dvr" && playback) {
    const v = playerVideo();
    if (v && Number.isFinite(v.currentTime)) {
      return playback.startUtcMs + Math.max(0, Math.floor(v.currentTime * 1000));
    }
    return playback.startUtcMs + (playback.positionMs || 0);
  }
  const r = timelineRange();
  if (r.isToday && camera.mode === "live" && r.liveAt) {
    return r.liveAt;
  }
  return timelineAtMs || r.scrubMax || r.liveAt || Date.now();
}

export function liveEdgeTipMs(r) {
  if (!r || !r.start || !r.end || r.end <= r.start) return LIVE_EDGE_TIP_MS;
  const span = r.end - r.start;
  return Math.max(120, Math.min(LIVE_EDGE_TIP_MS, Math.floor(span * 0.002)));
}

/** Whether a wall time on the selected day's scrubber sits at the live edge. */
export function atLiveEdge(wallMs) {
  const r = timelineRange();
  return isLiveEdgeWall(wallMs, r, liveEdgeTipMs(r));
}

// --- timeline data ------------------------------------------------------------

/** Refresh the wall-clock DVR timeline for the scrubber. */
export async function loadRecordings() {
  try {
    const res = await api("/api/dvr/timeline");
    dvr.timeline = {
      segments: (res && res.segments) || [],
      recording: !!(res && res.recording),
    };
  } catch (e) {
    dvr.timeline = { segments: [], recording: false };
  }
}

// --- speed ------------------------------------------------------------------

export function currentSpeed() {
  const n = Number(camera.rate);
  return n > 0 ? n : 1;
}

export function formatSpeed(rate) {
  const r = Number(rate) || 1;
  if (r === 1) return "1×";
  return String(r).replace(/\.0$/, "") + "×";
}

function applyPlaybackRate() {
  const v = playerVideo();
  const rate = currentSpeed();
  if (!v || v.playbackRate === rate) return;
  try {
    v.playbackRate = rate;
  } catch (e) {}
}

function resetPlaybackSpeed() {
  camera.rate = 1;
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
  camera.rate = SPEED_STEPS[i];
  syncTransport();
}

/** Record the playhead, notify the scrubber and apply the playback rate. */
export function syncTransport() {
  timelineAtMs = wallPlayheadMs();
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
    camera.loading = true;
  };
  const onPlaying = function () {
    camera.loading = false;
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
    camera.$patch({ loading: false, previewError: errText(e) });
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
    playAt(Number(next.startUtcMs)).catch(function () {});
    return;
  }
  if (timelineRange().recording) {
    backToLive().catch(function () {});
    return;
  }
  camera.paused = true;
  syncTransport();
}

function tickClock() {
  if (!playback || camera.paused || playAtBusy) return;
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
  if (camera.mode === "dvr") {
    camera.$patch({ mode: "live", playingName: "", paused: false, loading: false, previewError: "" });
  }
}

/**
 * @param {string} name
 * @param {{ durationMs?: number, offsetMs?: number, startUtcMs?: number, endUtcMs?: number, paused?: boolean }} [opts]
 */
export async function playRecording(name, opts) {
  if (!canPlayRecording(name)) {
    camera.previewError = t("cameras.play.unsupported", "This file cannot be played");
    return;
  }
  clearTimer();
  detachVideo();
  const media = mediaTransport();
  media.stopLive(playerVideo());
  liveActive = false;

  // While recording, the preview seat is shared with the DVR writer; keep it.
  const status = session.status;
  if (!(status && status.dvr && status.dvr.recording)) {
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
  timelineAtMs = startUtcMs + offsetMs;
  camera.$patch({
    mode: "dvr",
    playingName: name,
    paused: !!(opts && opts.paused),
    loading: true,
    previewActive: false,
    previewSrc: "",
    previewError: "",
  });
  requestAnimationFrame(function () {
    if (!playback || playback.name !== name) return;
    attachStream(name, offsetMs);
    applyPlaybackRate();
    if (opts && opts.paused) {
      const v = playerVideo();
      if (v) v.pause();
      clearTimer();
      camera.loading = false;
    } else {
      startClock();
    }
    syncTransport();
  });
}

/** Seek wall-clock; snaps gaps server-side. At the live edge → live. */
export async function playAt(wallUtcMs) {
  const at = Number(wallUtcMs);
  if (!at || playAtBusy) return;
  if (atLiveEdge(at)) {
    await backToLive();
    syncTransport();
    return;
  }
  playAtBusy = true;
  camera.$patch({ loading: true, previewError: "" });
  try {
    const res = await api("/api/dvr/play?atMs=" + encodeURIComponent(String(at)));
    if (!res || res.ok === false) {
      throw new Error((res && res.error) || "play failed");
    }
    if (res.live) {
      await backToLive();
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
      camera.$patch({ paused: true, loading: false });
      return;
    }
    await playRecording(res.name, {
      durationMs: res.durationMs,
      offsetMs: res.offsetMs,
      startUtcMs: res.startUtcMs,
      endUtcMs: res.endUtcMs,
    });
    // Seal may have grown the timeline — refresh quietly.
    await loadRecordings();
  } catch (e) {
    camera.$patch({ loading: false, previewError: errText(e) });
  } finally {
    playAtBusy = false;
  }
}

export async function togglePlaybackPause() {
  // Live: pause freezes the buffer, or timeshifts into sealed DVR near live.
  if (camera.mode === "live") {
    if (camera.paused) {
      camera.paused = false;
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
      await playAt(at);
      if (isRecordingPlayback() && playback) {
        camera.paused = true;
        const v = playerVideo();
        if (v) v.pause();
        clearTimer();
        syncTransport();
        return;
      }
    }
    const v = playerVideo();
    if (v) v.pause();
    camera.paused = true;
    syncTransport();
    return;
  }

  if (!playback) return;
  const next = !camera.paused;
  camera.paused = next;
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
  syncTransport();
}

export async function backToLive() {
  stopRecordingPlayback();
  emit("live");
  resetPlaybackSpeed();
  dvr.timelineDay = null;
  timelineAtMs = Date.now();
  camera.paused = false;
  await startLive();
  applyPlaybackRate();
}

// --- live -------------------------------------------------------------------

/** Take the car's live seat for the selected transport (no-op while playing a recording). */
async function startLive() {
  if (!videoEl || isRecordingPlayback()) return;
  if (camera.previewActive && isLiveSrc(camera.previewSrc)) return;
  const gen = liveGeneration;
  const media = mediaTransport();
  try {
    await media.acquireLive();
    if (gen !== liveGeneration) {
      await media.releaseLive();
      return;
    }
    timelineAtMs = Date.now();
    camera.$patch({ previewActive: true, previewSrc: media.liveSrc, mode: "live", playingName: "" });
  } catch (e) {
    if (gen !== liveGeneration) return;
    camera.$patch({ previewActive: false, previewSrc: "", previewError: errText(e) });
  }
}

/** Drop playback and the live seat. */
async function stopLive() {
  liveGeneration++;
  stopRecordingPlayback();
  const media = mediaTransport(camera.previewSrc);
  media.stopLive(videoEl);
  liveActive = false;
  camera.$patch({ previewActive: false, previewSrc: "", mode: "live", playingName: "", previewError: "" });
  await media.close();
}

/** Re-take the live seat, e.g. after the recording storage changed. */
export async function restartLive() {
  await stopLive();
  await startLive();
}

/** Attach / keep live on <video> for `camera.previewSrc`. */
async function applyCameraPlayerSrc() {
  if (camera.mode === "dvr") return;
  const want = camera.previewSrc || "";
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
    if (camera.paused) {
      try {
        video.pause();
      } catch (e) {}
    }
    if (!ok) {
      video.style.display = "none";
      camera.previewError = error || t("cameras.live.failed", "Live H.264 stream failed — check device logs");
    } else if (camera.previewError) {
      camera.previewError = "";
    }
  } finally {
    liveBusy = false;
  }
}

/**
 * Live / DVR video surface. Mounting takes the car's live seat; unmounting releases it
 * and stops any recording playback.
 */
class OaaCameraPlayer extends OaaElement {
  static properties = {
    lastError: { attribute: false },
    embedded: { type: Boolean },
  };

  constructor() {
    super();
    /** @type {string} */
    this.lastError = "";
    this.embedded = false;
  }

  connectedCallback() {
    super.connectedCallback();
    camera.$patch({ previewActive: false, previewSrc: "", mode: "live", playingName: "", paused: false, loading: false });
    dvr.timelineDay = null;
    loadRecordings();
    if (this.hasUpdated) this.mount();
  }

  firstUpdated() {
    this.mount();
  }

  mount() {
    videoEl = this.querySelector("video");
    startLive();
  }

  updated() {
    applyCameraPlayerSrc();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    stopLive();
    videoEl = null;
  }

  render() {
    const mode = camera.mode || "live";
    const playingName = camera.playingName || "";
    const err = camera.previewError || "";
    // Read so a new live source re-renders and updated() attaches it.
    void camera.previewSrc;
    const useVideo = mode === "live" || (mode === "dvr" && canPlayRecording(playingName));
    const label = mode === "dvr" ? t("cameras.timeline.dvr", "DVR") : t("cameras.live", "Live");
    const loading = !!camera.loading && mode === "dvr";

    return html`
      <wa-card class=${"camera-player" + (this.embedded ? " camera-player-fill" : "")}>
        <div class="camera-player-bar">
          <wa-badge class="camera-player-label" variant=${mode === "dvr" ? "brand" : "success"}>${label}</wa-badge>
          ${loading ? html`<wa-spinner label=${t("cameras.play.loading", "Loading…")}></wa-spinner>` : nothing}
        </div>
        <div class="camera-preview-wrap">
          <video class="preview" playsinline ?muted=${mode === "live"} style=${useVideo ? "" : "display:none"}></video>
          ${loading
            ? html`<div class="camera-preview-loading" aria-live="polite">${t("cameras.play.loading", "Loading…")}</div>`
            : nothing}
        </div>
        ${err ? html`<wa-callout variant="warning" size="small">${err}</wa-callout>` : nothing}
        ${this.lastError ? html`<wa-callout variant="danger" size="small">${this.lastError}</wa-callout>` : nothing}
      </wa-card>
    `;
  }
}
customElements.define("oaa-camera-player", OaaCameraPlayer);

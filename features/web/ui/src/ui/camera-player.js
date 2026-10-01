/**
 * Camera player core: one <video> per camera in a grid (tap a tile to view it
 * alone), each showing that camera's WebRTC track. The car never composites or
 * stamps the video; each tile overlays its frame's capture time.
 *
 * Live and DVR use the same tracks: in DVR mode the car replays the recordings
 * over them (`replay_*`), every camera paced by one clock, so the tiles stay in
 * step without the browser syncing anything. The car reports where the replay
 * is with `replay_state`; the scrubber follows via [onPlayerEvent].
 *
 * <oaa-camera-player> owns the videos and the media session for as long as it
 * is mounted.
 */

import { html, nothing } from "lit";
import { repeat } from "lit/directives/repeat.js";
import { OaaElement } from "../lit/oaa-element.js";
import { camera, dvr } from "../store.js";
import { t } from "../i18n.js";
import { api, errText } from "../api.js";
import {
  attachCamera,
  carCameras,
  carNow,
  detachCamera,
  frameWallMs,
  getMediaSession,
  hangupMediaSession,
  onLiveTracks,
  onReplayState,
  reasonText,
  replayPause,
  replayResume,
  replaySeek,
  replaySpeed,
  replayStart,
  replayStop,
  selectCameras,
  setMediaCloseHandler,
} from "./webrtc-session.js";
import { fmtStamp, watchFrames } from "./camera-stamp.js";
import { LIVE_EDGE_TIP_MS, isLiveEdgeWall, orderRoles, todayKey, timelineRangeForDay } from "./dvr-timeline.js";

export { orderRoles };

const FRAME_MS = 100;
export const SPEED_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2];
const ROLE_LABEL = { front: "Front", right: "Right", rear: "Rear", left: "Left" };

/**
 * Last `replay_state` while in DVR mode.
 * @type {{ state: string, atMs: number, anchor: { rtpMs: number, wallMs: number, speed: number } | null }|null}
 */
let replay = null;
/** A replay_start or replay_seek has no replay_state yet. */
let replayPending = false;
/** The car has a replay for this session (replay_start sent, no replay_stop since). */
let replayOpen = false;
/** replay_stop acknowledgements (state "live") still to come; they are not a request to go live. */
let stopAcks = 0;
/** Bumped when leaving DVR, so a replay_start still connecting gives up. */
let replayGeneration = 0;
/** Reload the timeline on the next replay_state (starting a replay seals the recording group). */
let refreshTimeline = false;
/** @type {ReturnType<typeof setInterval>|null} */
let ticker = null;
/** Last known playhead (wall UTC) when no replay clock drives it. */
let timelineAtMs = 0;
let mounted = false;
/** Media session up and the visible cameras selected. */
let camerasOn = false;
/** Bumped by stopCameras so a start still connecting gives up. */
let camerasGeneration = 0;

/**
 * @typedef {{ role: string, video: HTMLVideoElement, frame: HTMLElement, stamp: HTMLElement, stopStamp: () => void,
 *   stopFit: () => void, attaching: boolean }} Tile
 */
/** @type {Map<string, Tile>} */
const tiles = new Map();

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
  if (!mounted) return;
  camerasOn = false;
  replayOpen = false;
  stopAcks = 0;
  stopTicker();
  tiles.forEach(function (tile) {
    if (tile.video.srcObject) detachCamera(tile.video);
  });
  if (camera.mode === "dvr") {
    replayGeneration++;
    replay = null;
    replayPending = false;
    camera.$patch({ mode: "live", playingName: "", recorded: [], paused: false, loading: false });
  }
  camera.$patch({ previewActive: false, streaming: [], previewError: reasonText(reason) });
});

onLiveTracks(function (info) {
  if (!mounted) return;
  if (info.cameras.length) setRoles(info.cameras);
  camera.streaming = info.streaming;
  syncTiles();
});

onReplayState(function (m) {
  if (!mounted) return;
  const state = String(m.state || "");
  if (state === "live") {
    if (stopAcks > 0) {
      stopAcks--;
      return;
    }
    // Nothing sealed at the requested time: the car is already live.
    if (camera.mode === "dvr") backToLive().catch(function () {});
    return;
  }
  if (camera.mode !== "dvr" || !replayOpen) return;
  replayPending = false;
  if (state === "error") {
    camera.$patch({ loading: false, previewError: String(m.error || t("cameras.play.failed", "Playback failed")) });
    return;
  }
  const a = m.anchor;
  replay = {
    state: state,
    atMs: Number(m.atMs) || 0,
    anchor:
      a && !a.live ? { rtpMs: Number(a.rtpMs) || 0, wallMs: Number(a.wallMs) || 0, speed: Number(a.speed) || 1 } : null,
  };
  timelineAtMs = replay.atMs;
  camera.$patch({
    playingName: String(m.group || ""),
    recorded: Array.isArray(m.roles) ? m.roles.map(String) : [],
    paused: state !== "playing",
    loading: false,
    previewError: "",
  });
  if (refreshTimeline) {
    refreshTimeline = false;
    loadRecordings();
  }
  if (state === "ended" && timelineRange().isToday && timelineRange().recording) {
    backToLive().catch(function () {});
    return;
  }
  if (state === "playing") startTicker();
  else stopTicker();
  syncTransport();
});

export function isRecordingPlayback() {
  return camera.mode === "dvr";
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

/** Replay position: the car's replay clock while playing, else where it stopped. */
function replayWallMs() {
  if (replayPending || !replay) return timelineAtMs;
  const a = replay.anchor;
  if (replay.state === "playing" && a) return a.wallMs + (carNow() - a.rtpMs) * a.speed;
  return replay.atMs;
}

export function wallPlayheadMs() {
  if (camera.mode === "dvr") return replayWallMs();
  const r = timelineRange();
  if (r.isToday && r.liveAt) return r.liveAt;
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

// --- cameras ------------------------------------------------------------------

export function roleLabel(role) {
  return t("cameras.role." + role, ROLE_LABEL[role] || role);
}

function setRoles(list) {
  const next = orderRoles(list);
  if (!next.length) return;
  const cur = camera.roles || [];
  if (
    next.length === cur.length &&
    next.every(function (r, i) {
      return r === cur[i];
    })
  ) {
    return;
  }
  camera.roles = next;
  if (camera.focus && next.indexOf(camera.focus) < 0) camera.focus = "";
}

/** Roles on screen: the focused camera alone, or the whole grid. */
export function visibleRoles() {
  const roles = camera.roles || [];
  return camera.focus && roles.indexOf(camera.focus) >= 0 ? [camera.focus] : roles.slice();
}

/** Tap a tile: view that camera alone, or back to the grid. The car only sends what is on screen. */
export function toggleFocus(role) {
  camera.focus = camera.focus === role ? "" : role;
  if (!camerasOn) return;
  selectCameras(visibleRoles());
  syncTiles();
}

// --- timeline data ------------------------------------------------------------

/** Refresh the wall-clock DVR timeline (recording groups) for the scrubber. */
export async function loadRecordings() {
  try {
    const res = await api("/api/dvr/timeline");
    const roles = (res && Array.isArray(res.roles) && res.roles) || [];
    dvr.timeline = {
      segments: (res && res.segments) || [],
      recording: !!(res && res.recording),
      roles: roles,
    };
    setRoles(roles);
  } catch (e) {
    dvr.timeline = { segments: [], recording: false, roles: [] };
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
  if (replayOpen) replaySpeed(camera.rate);
  syncTransport();
}

/** Record the playhead and notify the scrubber. */
export function syncTransport() {
  timelineAtMs = wallPlayheadMs();
  emit("sync");
}

function startTicker() {
  if (ticker == null) ticker = setInterval(syncTransport, FRAME_MS);
}

function stopTicker() {
  if (ticker == null) return;
  clearInterval(ticker);
  ticker = null;
}

// --- tiles ------------------------------------------------------------------

function registerTiles(host) {
  const seen = new Set();
  host.querySelectorAll(".camera-tile[data-role]").forEach(function (el) {
    const role = el.getAttribute("data-role") || "";
    const video = el.querySelector("video");
    const frame = /** @type {HTMLElement|null} */ (el.querySelector(".camera-frame"));
    const stamp = /** @type {HTMLElement|null} */ (el.querySelector(".camera-stamp"));
    if (!role || !video || !frame || !stamp) return;
    seen.add(role);
    const have = tiles.get(role);
    if (have && have.video === video) return;
    if (have) dropTile(have);
    /** @type {Tile} */
    const tile = {
      role: role,
      video: video,
      frame: frame,
      stamp: stamp,
      stopStamp: function () {},
      stopFit: function () {},
      attaching: false,
    };
    tile.stopStamp = watchFrames(video, function (metadata) {
      paintStamp(tile, metadata);
    });
    tile.stopFit = watchFrameSize(tile);
    tiles.set(role, tile);
  });
  tiles.forEach(function (tile, role) {
    if (seen.has(role)) return;
    dropTile(tile);
    tiles.delete(role);
  });
}

function dropTile(tile) {
  tile.stopStamp();
  tile.stopFit();
  detachTile(tile);
}

/**
 * Size the tile's frame to the picture (not the letterboxed tile) and scale the
 * stamp like the car's burn-in: text = clamp(h / 24, 14, 40) source px, margin =
 * max(1.5% of h, 6) px — so the overlay sits where it does in an exported clip.
 */
function fitFrame(tile) {
  const w = tile.video.videoWidth;
  const h = tile.video.videoHeight;
  if (!(w > 0 && h > 0)) return;
  const text = Math.min(40, Math.max(14, h / 24));
  const margin = Math.max(6, Math.floor(h * 0.015));
  const style = tile.frame.style;
  style.setProperty("--frame-ar", String(w / h));
  style.setProperty("--stamp-text", String(text / h));
  style.setProperty("--stamp-margin", String(margin / h));
}

function watchFrameSize(tile) {
  const v = tile.video;
  const onSize = function () {
    fitFrame(tile);
  };
  v.addEventListener("loadedmetadata", onSize);
  v.addEventListener("resize", onSize);
  return function () {
    v.removeEventListener("loadedmetadata", onSize);
    v.removeEventListener("resize", onSize);
  };
}

function dropAllTiles() {
  tiles.forEach(dropTile);
  tiles.clear();
}

function paintStamp(tile, metadata) {
  const rtp = metadata && metadata.rtpTimestamp != null ? metadata.rtpTimestamp : undefined;
  const ms = tile.video.srcObject ? frameWallMs(tile.role, rtp) : 0;
  const text = ms ? fmtStamp(ms) : "";
  if (tile.stamp.textContent !== text) tile.stamp.textContent = text;
}

function detachTile(tile) {
  if (tile.video.srcObject) detachCamera(tile.video);
  tile.stamp.textContent = "";
}

function eachVisibleVideo(fn) {
  visibleRoles().forEach(function (role) {
    const tile = tiles.get(role);
    if (tile && tile.video.srcObject) fn(tile.video);
  });
}

/** Attach the camera tracks to visible tiles and drop them from hidden ones. */
function syncTiles() {
  if (!camerasOn) return;
  const vis = visibleRoles();
  tiles.forEach(function (tile, role) {
    const v = tile.video;
    if (vis.indexOf(role) < 0) {
      if (v.srcObject) detachTile(tile);
      return;
    }
    if (v.srcObject || tile.attaching) return;
    tile.attaching = true;
    const gen = camerasGeneration;
    const stillWanted = function () {
      return gen === camerasGeneration && visibleRoles().indexOf(role) >= 0;
    };
    attachCamera(role, v, stillWanted)
      .then(
        function (attached) {
          if (!attached) return;
          if (!stillWanted()) detachTile(tile);
          else if (camera.paused && camera.mode === "live") v.pause();
        },
        function (e) {
          // A camera still opening on the car retries on the next live_tracks.
          if (gen === camerasGeneration && e && e.reason && e.reason !== "timeout") {
            camera.previewError = errText(e);
          }
        },
      )
      .finally(function () {
        tile.attaching = false;
      });
  });
}

/** Open the media session and have the car send the visible cameras. */
async function startCameras() {
  if (!mounted || camerasOn) return;
  const gen = camerasGeneration;
  try {
    await getMediaSession();
    if (gen !== camerasGeneration || !mounted) return;
    setRoles(carCameras());
    camerasOn = true;
    if (camera.mode === "live") timelineAtMs = carNow();
    selectCameras(visibleRoles());
    camera.$patch({ previewActive: true, previewError: "" });
    syncTiles();
  } catch (e) {
    if (gen !== camerasGeneration) return;
    camera.$patch({ previewActive: false, previewError: errText(e) });
  }
}

/** Stop the camera tracks; the session stays up for cuts. */
function stopCameras() {
  camerasGeneration++;
  camerasOn = false;
  tiles.forEach(detachTile);
  selectCameras([]);
  camera.$patch({ previewActive: false, streaming: [] });
}

/** Re-open live, e.g. after the recording storage changed. */
export async function restartLive() {
  if (isRecordingPlayback()) return;
  stopCameras();
  await startCameras();
}

// --- replay -----------------------------------------------------------------

/** Ask the car to replay from `at` on the camera tracks (or seek the replay it has). */
async function startReplay(at, paused) {
  const gen = replayGeneration;
  replayPending = true;
  refreshTimeline = true;
  timelineAtMs = at;
  camera.$patch({ mode: "dvr", paused: !!paused, loading: true, previewError: "" });
  eachVisibleVideo(function (v) {
    v.play().catch(function () {});
  });
  syncTransport();
  try {
    await startCameras();
    const sent = await replayStart(at, currentSpeed(), !!paused, function () {
      return gen === replayGeneration;
    });
    if (sent) replayOpen = true;
  } catch (e) {
    if (gen !== replayGeneration) return;
    replayPending = false;
    camera.$patch({ mode: "live", paused: false, loading: false, previewError: errText(e) });
    syncTransport();
  }
}

/** Seek wall-clock; the car snaps gaps to the next recording. At the live edge → live. */
export async function playAt(wallUtcMs) {
  const at = Number(wallUtcMs);
  if (!at) return;
  if (atLiveEdge(at)) {
    await backToLive();
    syncTransport();
    return;
  }
  if (!replayOpen) {
    await startReplay(at, camera.mode === "dvr" && camera.paused);
    return;
  }
  replayPending = true;
  timelineAtMs = at;
  camera.$patch({ loading: true, previewError: "" });
  replaySeek(at);
  syncTransport();
}

export async function togglePlaybackPause() {
  // Live: pause freezes the frames, or timeshifts into sealed DVR near live.
  if (camera.mode === "live") {
    if (camera.paused) {
      camera.paused = false;
      eachVisibleVideo(function (v) {
        v.play().catch(function () {});
      });
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
      await startReplay(at, true);
      if (isRecordingPlayback()) return;
    }
    eachVisibleVideo(function (v) {
      v.pause();
    });
    camera.paused = true;
    syncTransport();
    return;
  }

  if (!replayOpen) return;
  if (camera.paused) {
    camera.paused = false;
    replayResume();
  } else {
    timelineAtMs = wallPlayheadMs();
    if (replay) replay = { state: "paused", atMs: timelineAtMs, anchor: replay.anchor };
    camera.paused = true;
    stopTicker();
    replayPause();
  }
  syncTransport();
}

function leaveReplay() {
  replayGeneration++;
  stopTicker();
  if (replayOpen) {
    replayOpen = false;
    stopAcks++;
    replayStop();
  }
  replay = null;
  replayPending = false;
  refreshTimeline = false;
  if (camera.mode === "dvr") {
    camera.$patch({ mode: "live", playingName: "", recorded: [], paused: false, loading: false, previewError: "" });
  }
}

export async function backToLive() {
  leaveReplay();
  emit("live");
  camera.rate = 1;
  dvr.timelineDay = null;
  timelineAtMs = Date.now();
  camera.paused = false;
  eachVisibleVideo(function (v) {
    v.play().catch(function () {});
  });
  await startCameras();
}

/**
 * Camera grid. Mounting opens the media session and streams the visible
 * cameras; unmounting closes it, which also ends any replay on the car.
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
    camera.$patch({
      previewActive: false,
      mode: "live",
      playingName: "",
      paused: false,
      rate: 1,
      loading: false,
      focus: "",
      streaming: [],
      recorded: [],
      previewError: "",
    });
    dvr.timelineDay = null;
    mounted = true;
    loadRecordings();
    startCameras();
  }

  updated() {
    registerTiles(this);
    syncTiles();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    mounted = false;
    leaveReplay();
    stopCameras();
    dropAllTiles();
    replayOpen = false;
    stopAcks = 0;
    hangupMediaSession();
  }

  tileHtml(role, mode, focus) {
    const hidden = !!focus && focus !== role;
    let status = "";
    if (mode === "dvr") {
      if (!camera.loading && (camera.recorded || []).indexOf(role) < 0) {
        status = t("cameras.tile.noRecording", "No recording");
      }
    } else if (camera.previewActive && (camera.streaming || []).indexOf(role) < 0) {
      status = t("cameras.tile.waiting", "Waiting for camera…");
    }
    const tip = focus === role ? t("cameras.grid.all", "All cameras") : t("cameras.grid.solo", "View alone");
    return html`
      <div
        class=${"camera-tile" + (hidden ? " is-hidden" : "")}
        data-role=${role}
        title=${tip}
        @click=${() => toggleFocus(role)}
      >
        <div class="camera-frame">
          <video class="preview" playsinline muted disablepictureinpicture></video>
          <span class="camera-tile-role">${roleLabel(role)}</span>
          <span class="camera-stamp"></span>
        </div>
        ${status ? html`<span class="camera-tile-status">${status}</span>` : nothing}
      </div>
    `;
  }

  render() {
    const mode = camera.mode || "live";
    const roles = camera.roles || [];
    const focus = camera.focus && roles.indexOf(camera.focus) >= 0 ? camera.focus : "";
    const err = camera.previewError || "";
    const label = mode === "dvr" ? t("cameras.timeline.dvr", "DVR") : t("cameras.live", "Live");
    const loading = !!camera.loading && mode === "dvr";
    const shown = focus ? 1 : Math.max(1, Math.min(4, roles.length));

    return html`
      <wa-card class=${"camera-player" + (this.embedded ? " camera-player-fill" : "")}>
        <div class="camera-player-bar">
          <wa-badge class="camera-player-label" variant=${mode === "dvr" ? "brand" : "success"}>${label}</wa-badge>
          ${focus ? html`<span class="camera-player-focus">${roleLabel(focus)}</span>` : nothing}
          ${loading ? html`<wa-spinner label=${t("cameras.play.loading", "Loading…")}></wa-spinner>` : nothing}
          ${focus
            ? html`<wa-button class="camera-player-grid" size="small" appearance="plain" @click=${() => toggleFocus(focus)}>
                ${t("cameras.grid.all", "All cameras")}
              </wa-button>`
            : nothing}
        </div>
        <div class=${"camera-preview-wrap camera-grid n" + shown}>
          ${repeat(
            roles,
            (r) => r,
            (role) => this.tileHtml(role, mode, focus),
          )}
          ${!roles.length && !err
            ? html`<div class="camera-preview-loading">${t("cameras.connecting", "Connecting to cameras…")}</div>`
            : nothing}
          ${loading
            ? html`<div class="camera-preview-loading" aria-live="polite">${t("cameras.play.loading", "Loading…")}</div>`
            : nothing}
        </div>
        ${err ? html`<wa-callout variant="warning" size="s">${err}</wa-callout>` : nothing}
        ${this.lastError ? html`<wa-callout variant="danger" size="s">${this.lastError}</wa-callout>` : nothing}
      </wa-card>
    `;
  }
}
customElements.define("oaa-camera-player", OaaCameraPlayer);

/**
 * Camera player core: one <video> per camera in a grid (tap a tile to view it
 * alone), live over WebRTC and wall-clock DVR playback over recording groups
 * (one oaa_dvr_<stamp>_<role>.mp4 per camera). The car never composites or
 * stamps the video; each tile overlays its frame's capture time.
 *
 * In DVR mode one video (the focused camera, else the first visible one with a
 * file) leads the wall-clock playhead; the others follow it by nudging their
 * playback rate, or seek when they drift too far.
 *
 * <oaa-camera-player> owns the videos and the media session for as long as it
 * is mounted; the scrubber (<oaa-camera-timeline>) follows via [onPlayerEvent].
 */

import { html, nothing } from "lit";
import { repeat } from "lit/directives/repeat.js";
import { OaaElement } from "../lit/oaa-element.js";
import { camera, dvr } from "../store.js";
import { t } from "../i18n.js";
import { api, errText } from "../api.js";
import { mediaTransport } from "./media-transport.js";
import {
  attachLive,
  carCameras,
  carNow,
  detachLive,
  getMediaSession,
  hangupMediaSession,
  liveFrameWallMs,
  onLiveTracks,
  reasonText,
  selectLive,
  setMediaCloseHandler,
} from "./webrtc-session.js";
import { fmtStamp, watchFrames } from "./camera-stamp.js";
import { LIVE_EDGE_TIP_MS, isLiveEdgeWall, orderRoles, todayKey, timelineRangeForDay } from "./dvr-timeline.js";

export { orderRoles };

const FRAME_MS = 100;
export const SPEED_STEPS = [0.5, 0.75, 1, 1.25, 1.5, 2];
const ROLE_LABEL = { front: "Front", right: "Right", rear: "Rear", left: "Left" };
/** Followers within this of the leader play at the leader's rate. */
const SYNC_TOLERANCE_S = 0.1;
/** Followers further than this seek instead of catching up. */
const SEEK_DRIFT_S = 1;
const RATE_NUDGE = 0.1;

/** @typedef {{ name: string, startUtcMs: number, durationMs: number, offsetMs: number }} GroupFile */
/**
 * @type {{ group: string, startUtcMs: number, endUtcMs: number, files: Record<string, GroupFile>,
 *   leader: string, anchorMs: number, timer: ReturnType<typeof setInterval>|null }|null}
 */
let playback = null;
let playAtBusy = false;
/** Last known playhead (wall UTC) when neither live nor a recording drives it. */
let timelineAtMs = 0;
let mounted = false;
let liveOn = false;
/** Bumped by stopLive so a start still connecting gives up. */
let liveGeneration = 0;

/**
 * @typedef {{ role: string, video: HTMLVideoElement, frame: HTMLElement, stamp: HTMLElement, stopStamp: () => void,
 *   stopFit: () => void, attaching: boolean, playing: string, ready: boolean,
 *   listeners: Array<[string, EventListener]> }} Tile
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
  liveOn = false;
  tiles.forEach(function (tile) {
    if (tile.video.srcObject) detachLive(tile.video);
  });
  camera.$patch({ previewActive: false, streaming: [], previewError: reasonText(reason) });
});

onLiveTracks(function (info) {
  if (!mounted) return;
  if (info.cameras.length) setRoles(info.cameras);
  camera.streaming = info.streaming;
  syncLiveTiles();
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

export function wallPlayheadMs() {
  if (camera.mode === "dvr" && playback) {
    const lead = leaderWallMs();
    return lead != null ? lead : playback.anchorMs;
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

/** Tap a tile: view that camera alone, or back to the grid. */
export function toggleFocus(role) {
  camera.focus = camera.focus === role ? "" : role;
  if (camera.mode === "live") {
    if (liveOn) {
      selectLive(visibleRoles());
      syncLiveTiles();
    }
    return;
  }
  if (playback) refreshDvrTiles();
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

function setRate(video, rate) {
  if (video.playbackRate === rate) return;
  try {
    video.playbackRate = rate;
  } catch (e) {}
}

function applyPlaybackRate() {
  if (!playback) return;
  const lead = tiles.get(playback.leader);
  if (lead && lead.playing) setRate(lead.video, currentSpeed());
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
      playing: "",
      ready: false,
      listeners: [],
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

/** Capture time (wall UTC ms) of the frame on screen, or 0. */
function tileWallMs(tile, metadata) {
  const v = tile.video;
  if (v.srcObject) {
    const rtp = metadata && metadata.rtpTimestamp != null ? metadata.rtpTimestamp : undefined;
    return liveFrameWallMs(tile.role, rtp);
  }
  if (playback && tile.playing) {
    const f = playback.files[tile.role];
    if (!f) return 0;
    const sec = metadata && Number.isFinite(metadata.mediaTime) ? metadata.mediaTime : v.currentTime;
    return f.startUtcMs + Math.max(0, Math.floor(sec * 1000));
  }
  return 0;
}

function paintStamp(tile, metadata) {
  const ms = tileWallMs(tile, metadata);
  const text = ms ? fmtStamp(ms) : "";
  if (tile.stamp.textContent !== text) tile.stamp.textContent = text;
}

function removeTileListeners(tile) {
  const v = tile.video;
  tile.listeners.forEach(function (h) {
    v.removeEventListener(h[0], h[1]);
  });
  tile.listeners = [];
}

/** Stop whatever the tile shows (live track or recording). */
function detachTile(tile) {
  removeTileListeners(tile);
  if (tile.playing) mediaTransport().stopPlayback(tile.video);
  tile.playing = "";
  tile.ready = false;
  if (tile.video.srcObject) detachLive(tile.video);
  tile.stamp.textContent = "";
}

// --- recording playback -----------------------------------------------------

/** Media offset (ms) of wall time `wallMs` inside `f`. */
function offsetIn(f, wallMs) {
  return Math.max(0, Math.min(Math.max(0, f.durationMs - 50), wallMs - f.startUtcMs));
}

function covers(f, wallMs) {
  return wallMs >= f.startUtcMs - 250 && wallMs < f.startUtcMs + f.durationMs - 250;
}

/** Leader for `wallMs`: the focused camera, else a visible camera recorded at that time. */
function pickLeader(wallMs) {
  if (!playback) return "";
  const files = playback.files;
  const vis = visibleRoles().filter(function (r) {
    return !!files[r];
  });
  if (camera.focus && vis.indexOf(camera.focus) >= 0) return camera.focus;
  return (
    vis.find(function (r) {
      return covers(files[r], wallMs);
    }) ||
    vis[0] ||
    ""
  );
}

/** Wall time of the leader's current frame, or null until it is positioned. */
function leaderWallMs() {
  if (!playback) return null;
  const tile = tiles.get(playback.leader);
  const f = playback.files[playback.leader];
  if (!tile || !f || !tile.ready || !Number.isFinite(tile.video.currentTime)) return null;
  return f.startUtcMs + Math.max(0, Math.floor(tile.video.currentTime * 1000));
}

function attachRecording(role, offsetMs) {
  const tile = tiles.get(role);
  const pb = playback;
  const f = pb && pb.files[role];
  if (!tile || !f) return;
  detachTile(tile);
  const v = tile.video;
  const startSec = Math.max(0, offsetMs / 1000);
  tile.playing = f.name;
  v.muted = true;
  v.setAttribute("playsinline", "");
  const isLeader = function () {
    return playback === pb && pb.leader === role;
  };
  const onMeta = function () {
    v.removeEventListener("loadedmetadata", onMeta);
    try {
      if (startSec > 0 && Number.isFinite(v.duration)) {
        v.currentTime = Math.min(startSec, Math.max(0, v.duration - 0.05));
      }
    } catch (e) {}
    tile.ready = true;
    setRate(v, currentSpeed());
    if (playback === pb && !camera.paused) v.play().catch(function () {});
  };
  const onWaiting = function () {
    if (isLeader()) camera.loading = true;
  };
  const onPlaying = function () {
    if (isLeader()) camera.loading = false;
  };
  tile.listeners = [
    ["loadedmetadata", onMeta],
    ["waiting", onWaiting],
    ["playing", onPlaying],
    ["canplay", onPlaying],
  ];
  tile.listeners.forEach(function (h) {
    v.addEventListener(h[0], h[1]);
  });
  const onError = function (e) {
    if (playback !== pb || tile.playing !== f.name) return;
    camera.$patch({ loading: false, previewError: roleLabel(role) + ": " + errText(e) });
  };
  Promise.resolve(mediaTransport().playRecording(v, f.name, offsetMs, onError)).catch(onError);
}

/** After a focus change: attach newly visible cameras at the playhead, drop hidden ones. */
function refreshDvrTiles() {
  if (!playback) return;
  const wall = wallPlayheadMs();
  playback.anchorMs = wall;
  const vis = visibleRoles();
  tiles.forEach(function (tile, role) {
    if (vis.indexOf(role) < 0) detachTile(tile);
  });
  playback.leader = pickLeader(wall);
  const files = playback.files;
  vis.forEach(function (role) {
    const tile = tiles.get(role);
    const f = files[role];
    if (tile && f && !tile.playing) attachRecording(role, offsetIn(f, wall));
  });
  if (camera.paused) {
    tiles.forEach(function (tile) {
      if (tile.playing) tile.video.pause();
    });
  }
  syncTransport();
}

function clearTimer() {
  if (playback && playback.timer != null) {
    clearInterval(playback.timer);
    playback.timer = null;
  }
}

/** Jump to the next recording group, live edge, or pause — never snap-loop. */
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

/** Keep followers within SYNC_TOLERANCE_S of the leader's wall time. */
function syncFollowers() {
  if (!playback) return;
  const pb = playback;
  const wall = pb.anchorMs;
  const base = currentSpeed();
  visibleRoles().forEach(function (role) {
    if (role === pb.leader) return;
    const tile = tiles.get(role);
    const f = pb.files[role];
    if (!tile || !f || !tile.ready) return;
    const v = tile.video;
    const target = (wall - f.startUtcMs) / 1000;
    if (target < 0 || target * 1000 > f.durationMs) {
      if (!v.paused) v.pause();
      return;
    }
    if (camera.paused) return;
    if (v.paused || v.ended) v.play().catch(function () {});
    const drift = v.currentTime - target;
    if (Math.abs(drift) > SEEK_DRIFT_S) {
      if (!v.seeking) v.currentTime = target;
      setRate(v, base);
    } else if (Math.abs(drift) > SYNC_TOLERANCE_S) {
      setRate(v, base * (drift > 0 ? 1 - RATE_NUDGE : 1 + RATE_NUDGE));
    } else {
      setRate(v, base);
    }
  });
}

function tickClock() {
  if (!playback || camera.paused || playAtBusy) return;
  const pb = playback;
  const lead = tiles.get(pb.leader);
  const lf = pb.files[pb.leader];
  if (!lead || !lf || !lead.playing) {
    // No visible camera recorded here: run the wall clock and hand over when one starts.
    pb.anchorMs += FRAME_MS * currentSpeed();
    if (pb.anchorMs >= pb.endUtcMs) {
      advanceAfterSegment();
      return;
    }
    const next = pickLeader(pb.anchorMs);
    if (next && pb.files[next] && covers(pb.files[next], pb.anchorMs)) pb.leader = next;
    syncFollowers();
    syncTransport();
    return;
  }
  if (!lead.ready) {
    syncTransport();
    return;
  }
  const v = lead.video;
  const ended = v.ended || (lf.durationMs > 0 && v.currentTime * 1000 >= lf.durationMs - 200);
  if (ended) {
    // A camera that stopped early hands the lead to one still recording.
    const at = lf.startUtcMs + lf.durationMs;
    const other = visibleRoles().find(function (r) {
      return r !== pb.leader && !!pb.files[r] && covers(pb.files[r], at + 300);
    });
    if (other) {
      pb.anchorMs = at;
      pb.leader = other;
      setRate(v, currentSpeed());
      syncTransport();
      return;
    }
    advanceAfterSegment();
    return;
  }
  const wall = leaderWallMs();
  if (wall != null) pb.anchorMs = wall;
  syncFollowers();
  syncTransport();
}

function startClock() {
  clearTimer();
  if (!playback) return;
  playback.timer = setInterval(tickClock, FRAME_MS);
}

export function stopRecordingPlayback() {
  clearTimer();
  tiles.forEach(function (tile) {
    if (tile.playing) detachTile(tile);
  });
  playback = null;
  if (camera.mode === "dvr") {
    camera.$patch({ mode: "live", playingName: "", recorded: [], paused: false, loading: false, previewError: "" });
  }
}

/**
 * Play every visible camera of a resolved recording group (`/api/dvr/play`).
 * @param {any} res
 * @param {boolean} [paused]
 */
function playGroup(res, paused) {
  clearTimer();
  stopLive();
  tiles.forEach(detachTile);
  /** @type {Record<string, GroupFile>} */
  const files = {};
  const cams = (res && res.cameras) || {};
  Object.keys(cams).forEach(function (role) {
    const c = cams[role] || {};
    if (!c.name) return;
    files[role] = {
      name: String(c.name),
      startUtcMs: Number(c.startUtcMs) || 0,
      durationMs: Math.max(0, Number(c.durationMs) || 0),
      offsetMs: Math.max(0, Number(c.offsetMs) || 0),
    };
  });
  const at = Number(res.atUtcMs) || Number(res.startUtcMs) || 0;
  if (!(camera.roles || []).length) setRoles(Object.keys(files));
  const pb = {
    group: String(res.group || ""),
    startUtcMs: Number(res.startUtcMs) || at,
    endUtcMs: Number(res.endUtcMs) || at,
    files: files,
    leader: "",
    anchorMs: at,
    timer: null,
  };
  playback = pb;
  pb.leader = pickLeader(at);
  timelineAtMs = at;
  camera.$patch({
    mode: "dvr",
    playingName: pb.group,
    recorded: Object.keys(files),
    paused: !!paused,
    loading: !!pb.leader,
    previewActive: false,
    previewError: "",
  });
  requestAnimationFrame(function () {
    if (playback !== pb) return;
    visibleRoles().forEach(function (role) {
      const f = files[role];
      if (f) attachRecording(role, f.offsetMs);
    });
    if (paused) camera.loading = false;
    else startClock();
    syncTransport();
  });
}

/** Seek inside the group already playing: move every loaded video instead of reloading. */
function seekInGroup(wallMs) {
  if (!playback) return;
  const pb = playback;
  pb.anchorMs = wallMs;
  pb.leader = pickLeader(wallMs);
  timelineAtMs = wallMs;
  visibleRoles().forEach(function (role) {
    const tile = tiles.get(role);
    const f = pb.files[role];
    if (!tile || !f) return;
    if (!tile.playing) {
      attachRecording(role, offsetIn(f, wallMs));
      return;
    }
    if (!tile.ready) return;
    try {
      tile.video.currentTime = offsetIn(f, wallMs) / 1000;
    } catch (e) {}
    if (!camera.paused && covers(f, wallMs)) tile.video.play().catch(function () {});
  });
  camera.$patch({ loading: false, previewError: "" });
  if (!camera.paused) startClock();
  syncTransport();
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
    // Same group near its end → avoid reload thrash; just pause at end.
    const resAt = Number(res.atUtcMs) || 0;
    if (
      playback &&
      playback.group === res.group &&
      Math.abs(resAt - playback.anchorMs) < 400 &&
      resAt >= Number(res.endUtcMs) - 500
    ) {
      camera.$patch({ paused: true, loading: false });
      return;
    }
    if (playback && playback.group === res.group && resAt > 0) {
      seekInGroup(resAt);
      return;
    }
    playGroup(res, false);
    // Seal may have grown the timeline — refresh quietly.
    await loadRecordings();
  } catch (e) {
    camera.$patch({ loading: false, previewError: errText(e) });
  } finally {
    playAtBusy = false;
  }
}

function eachVideo(fn) {
  visibleRoles().forEach(function (role) {
    const tile = tiles.get(role);
    if (tile) fn(tile);
  });
}

export async function togglePlaybackPause() {
  // Live: pause freezes the frames, or timeshifts into sealed DVR near live.
  if (camera.mode === "live") {
    if (camera.paused) {
      camera.paused = false;
      eachVideo(function (tile) {
        if (tile.video.srcObject) tile.video.play().catch(function () {});
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
      await playAt(at);
      if (isRecordingPlayback() && playback) {
        camera.paused = true;
        eachVideo(function (tile) {
          tile.video.pause();
        });
        clearTimer();
        syncTransport();
        return;
      }
    }
    eachVideo(function (tile) {
      tile.video.pause();
    });
    camera.paused = true;
    syncTransport();
    return;
  }

  if (!playback) return;
  const next = !camera.paused;
  camera.paused = next;
  if (next) {
    clearTimer();
    eachVideo(function (tile) {
      if (tile.playing) tile.video.pause();
    });
  } else {
    const lead = tiles.get(playback.leader);
    if (lead && lead.playing) {
      if (lead.video.ended) lead.video.currentTime = 0;
      lead.video.play().catch(function () {});
    }
    applyPlaybackRate();
    startClock();
  }
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
}

// --- live -------------------------------------------------------------------

/** Open the media session and stream the visible cameras (no-op while playing a recording). */
async function startLive() {
  if (!mounted || isRecordingPlayback() || liveOn) return;
  const gen = liveGeneration;
  try {
    await getMediaSession();
    if (gen !== liveGeneration || !mounted || isRecordingPlayback()) return;
    setRoles(carCameras());
    liveOn = true;
    timelineAtMs = carNow();
    selectLive(visibleRoles());
    camera.$patch({ previewActive: true, previewError: "" });
    syncLiveTiles();
  } catch (e) {
    if (gen !== liveGeneration) return;
    camera.$patch({ previewActive: false, previewError: errText(e) });
  }
}

/** Attach live tracks to visible tiles and drop them from hidden ones. */
function syncLiveTiles() {
  if (!liveOn || camera.mode !== "live") return;
  const vis = visibleRoles();
  tiles.forEach(function (tile, role) {
    const v = tile.video;
    if (vis.indexOf(role) < 0) {
      if (v.srcObject) detachLive(v);
      return;
    }
    if (v.srcObject || tile.attaching) return;
    tile.attaching = true;
    const gen = liveGeneration;
    attachLive(role, v)
      .then(
        function () {
          if (gen !== liveGeneration) detachLive(v);
          else if (camera.paused) v.pause();
        },
        function (e) {
          // A camera still opening on the car retries on the next live_tracks.
          if (gen === liveGeneration && e && e.reason && e.reason !== "timeout") {
            camera.previewError = errText(e);
          }
        },
      )
      .finally(function () {
        tile.attaching = false;
      });
  });
}

/** Stop the live tracks; the session stays up for recordings and cuts. */
function stopLive() {
  liveGeneration++;
  liveOn = false;
  tiles.forEach(function (tile) {
    if (tile.video.srcObject) detachLive(tile.video);
  });
  selectLive([]);
  camera.$patch({ previewActive: false, streaming: [] });
}

/** Re-open live, e.g. after the recording storage changed. */
export async function restartLive() {
  if (isRecordingPlayback()) return;
  stopLive();
  await startLive();
}

/**
 * Camera grid. Mounting opens the media session and streams the visible
 * cameras; unmounting closes it and stops any recording playback.
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
      loading: false,
      focus: "",
      streaming: [],
      recorded: [],
      previewError: "",
    });
    dvr.timelineDay = null;
    mounted = true;
    loadRecordings();
    startLive();
  }

  updated() {
    registerTiles(this);
    syncLiveTiles();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    mounted = false;
    stopRecordingPlayback();
    stopLive();
    dropAllTiles();
    hangupMediaSession();
  }

  tileHtml(role, mode, focus) {
    const hidden = !!focus && focus !== role;
    let status = "";
    if (mode === "dvr") {
      if ((camera.recorded || []).indexOf(role) < 0) status = t("cameras.tile.noRecording", "No recording");
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

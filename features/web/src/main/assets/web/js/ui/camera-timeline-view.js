/**
 * DVR day scrubber: day picker, transport row, segment ticks, axis labels and
 * pointer / keyboard seeking. Playback state lives in camera-player.js.
 */
import { html, nothing } from "../lit.js";
import { state, patch } from "../store.js";
import { t } from "../i18n.js";
import { dayKeyFromMs, todayKey, dayBounds, availableTimelineDays as daysFromSegments } from "./dvr-timeline.js";
import {
  SPEED_STEPS,
  backToLive,
  currentSpeed,
  fmtWall,
  formatSpeed,
  getLiveStarter,
  isLiveEdgeWall,
  nudgeSpeed,
  onPlayerEvent,
  playAt,
  selectedDayKey,
  setLiveStarter,
  syncTransport,
  timeline,
  timelineRange,
  togglePlaybackPause,
  wallPlayheadMs,
} from "./camera-player.js";
import {
  beginCutDrag,
  clearCutMarks,
  cutBarHtml,
  cutRangeHtml,
  endCutDrag,
  isCutDragging,
  isCutting,
  moveCutDrag,
} from "./camera-cut.js";

let timelineSeeking = false;
let timelinePaintTimer = null;

onPlayerEvent("sync", syncTimelineDom);

/** True while the user is scrubbing or dragging a cut range (event paint should not repaint). */
export function isTimelineBusy() {
  return timelineSeeking || isCutDragging();
}

function pctOf(r, wall) {
  return Math.max(0, Math.min(100, ((wall - r.start) / (r.end - r.start)) * 100));
}

function hasSpan(r) {
  return !!(r && r.start && r.end && r.end > r.start);
}

function scrubMax(r) {
  return r.scrubMax != null ? r.scrubMax : r.end - 1;
}

/** Repaint playhead, live edge, active segment and transport buttons in place. */
function syncTimelineDom() {
  const r = timelineRange();
  const playhead = document.getElementById("cameraTimelinePlayhead");
  if (playhead && !timelineSeeking && !isCutDragging() && hasSpan(r)) {
    playhead.style.left = pctOf(r, wallPlayheadMs()) + "%";
  }
  syncSegmentDom(r);
  const liveBtn = document.getElementById("cameraTimelineLive");
  if (liveBtn) {
    liveBtn.disabled = state.cameraPlayerMode === "live" && !state.cameraPlaybackPaused && !!r.isToday;
  }
  if (r.isToday && r.liveAt != null && hasSpan(r)) {
    const lp = pctOf(r, r.liveAt);
    const liveEdge = document.getElementById("cameraTimelineLiveEdge");
    if (liveEdge) liveEdge.style.left = lp + "%";
    const future = document.querySelector(".camera-timeline-future");
    if (future) {
      future.style.left = lp + "%";
      future.style.width = Math.max(0, 100 - lp) + "%";
    }
    const liveLab = document.querySelector(".camera-timeline-axis-lab.live");
    if (liveLab) liveLab.style.left = lp + "%";
  }
  const pauseBtn = document.getElementById("cameraTransportPause");
  if (pauseBtn) {
    pauseBtn.disabled = state.cameraPlayerMode !== "live" && state.cameraPlayerMode !== "dvr";
    pauseBtn.textContent = state.cameraPlaybackPaused ? "▶" : "❚❚";
    pauseBtn.title = state.cameraPlaybackPaused ? t("cameras.play.resume", "Resume") : t("cameras.play.pause", "Pause");
  }
  const speedLab = document.getElementById("cameraTransportSpeed");
  if (speedLab) speedLab.textContent = formatSpeed(currentSpeed());
}

// --- days -------------------------------------------------------------------

/** Days that have any stored/active segment, plus today. Newest first. */
function availableTimelineDays() {
  return daysFromSegments(timeline().segments || []);
}

function formatDayLabel(dayKey) {
  if (dayKey === todayKey()) return t("cameras.timeline.today", "Today");
  if (dayKey === dayKeyFromMs(dayBounds(todayKey()).start - 1)) {
    return t("cameras.timeline.yesterday", "Yesterday");
  }
  const p = dayKey.split("-").map(Number);
  try {
    return new Date(p[0], p[1] - 1, p[2]).toLocaleDateString(undefined, {
      weekday: "short",
      month: "short",
      day: "numeric",
    });
  } catch (e) {
    return dayKey;
  }
}

function setTimelineDay(dayKey) {
  const days = availableTimelineDays();
  let next = dayKey || todayKey();
  if (days.indexOf(next) < 0) next = todayKey();
  if (next === selectedDayKey()) {
    if (next === todayKey() && state.dvrTimelineDay != null) {
      patch({ dvrTimelineDay: null });
    }
    return;
  }
  if (isCutting()) clearCutMarks();
  patch({ dvrTimelineDay: next === todayKey() ? null : next });
  // Historical day: leave live and scrub into that day's recordings.
  if (next !== todayKey()) {
    const bounds = dayBounds(next);
    const first = (timeline().segments || [])
      .filter(function (s) {
        return (Number(s.endUtcMs) || 0) > bounds.start && (Number(s.startUtcMs) || 0) < bounds.endFull;
      })
      .sort(function (a, b) {
        return Number(a.startUtcMs) - Number(b.startUtcMs);
      })[0];
    const at = first ? Number(first.startUtcMs) || bounds.start : bounds.start;
    playAt(Math.min(at, bounds.scrubMax), getLiveStarter()).catch(function () {});
  }
}

function shiftTimelineDay(dir) {
  const days = availableTimelineDays();
  const i = Math.max(0, days.indexOf(selectedDayKey()));
  // days are newest-first; dir -1 = older, +1 = newer
  const next = days[i - dir];
  if (next) setTimelineDay(next);
}

function dayPickerHtml() {
  const days = availableTimelineDays();
  const cur = selectedDayKey();
  const i = days.indexOf(cur);
  const canOlder = i >= 0 && i < days.length - 1;
  const canNewer = i > 0;
  return html`<div class="row camera-timeline-day">
    <button
      type="button"
      class="btn ghost"
      ?disabled=${!canOlder}
      title=${t("cameras.timeline.prev_day", "Previous day")}
      @click=${function () {
        shiftTimelineDay(-1);
      }}
    >
      ‹
    </button>
    <label class="camera-timeline-day-select">
      <span class="sr-only">${t("cameras.timeline.day", "Day")}</span>
      <select
        @change=${function (ev) {
          setTimelineDay(ev.target.value);
        }}
      >
        ${days.map(function (d) {
          return html`<option value=${d} ?selected=${d === cur}>${formatDayLabel(d)}</option>`;
        })}
      </select>
    </label>
    <button
      type="button"
      class="btn ghost"
      ?disabled=${!canNewer}
      title=${t("cameras.timeline.next_day", "Next day")}
      @click=${function () {
        shiftTimelineDay(1);
      }}
    >
      ›
    </button>
  </div>`;
}

// --- segments ---------------------------------------------------------------

function displaySegsForTrack(r) {
  const segs = (r.segs || [])
    .map(function (s) {
      return {
        startUtcMs: Math.max(r.start, Number(s.startUtcMs) || 0),
        endUtcMs: Math.min(r.end, Number(s.endUtcMs) || 0),
        active: !!s.active,
      };
    })
    .filter(function (s) {
      return s.endUtcMs > s.startUtcMs;
    });
  if (!segs.length || !r.recording || !r.isToday) return segs;
  // Stretch the recording segment to the live edge between timeline refreshes.
  const liveEnd = r.liveAt || r.scrubMax || Date.now();
  let idx = segs.length - 1;
  for (let i = 0; i < segs.length; i++) {
    if (segs[i].active) idx = i;
  }
  if (segs[idx].endUtcMs < liveEnd) {
    segs[idx] = Object.assign({}, segs[idx], { endUtcMs: Math.min(r.end, liveEnd), active: true });
  }
  return segs;
}

function segStyle(r, s) {
  const span = r.end - r.start;
  const left = ((s.startUtcMs - r.start) / span) * 100;
  const width = ((s.endUtcMs - s.startUtcMs) / span) * 100;
  return { left: Math.max(0, left) + "%", width: Math.max(0.35, width) + "%" };
}

function timelineTicksHtml(r) {
  if (!hasSpan(r) || !r.segs.length) return nothing;
  return html`<div class="camera-timeline-ticks" id="cameraTimelineTicks" aria-hidden="true">
    ${displaySegsForTrack(r).map(function (s) {
      const st = segStyle(r, s);
      return html`<span
        class=${"camera-timeline-seg" + (s.active ? " is-active" : "")}
        style=${"left:" + st.left + ";width:" + st.width}
      ></span>`;
    })}
  </div>`;
}

function syncSegmentDom(r) {
  if (!hasSpan(r)) return;
  const wrap = document.getElementById("cameraTimelineTicks");
  if (!wrap) return;
  const segs = displaySegsForTrack(r);
  const nodes = wrap.querySelectorAll(".camera-timeline-seg");
  // Count changed (seal/rotate): the next lit render rebuilds them.
  if (nodes.length !== segs.length) return;
  for (let i = 0; i < segs.length; i++) {
    const st = segStyle(r, segs[i]);
    nodes[i].style.left = st.left;
    nodes[i].style.width = st.width;
    nodes[i].classList.toggle("is-active", segs[i].active);
  }
}

/** Keep timeline paint in sync while the live range stretches (no API needed). */
function ensureTimelinePaintClock() {
  if (timelinePaintTimer != null) return;
  timelinePaintTimer = setInterval(function () {
    if (state.page !== "cameras" && state.page !== "dvr") return;
    const onToday = selectedDayKey() === todayKey();
    if (!onToday && !timeline().recording && state.cameraPlayerMode !== "dvr") return;
    syncTransport();
  }, 500);
}

// --- seeking ----------------------------------------------------------------

function wallFromClientX(trackEl, clientX) {
  const r = timelineRange();
  if (!hasSpan(r) || !trackEl) return null;
  const rect = trackEl.getBoundingClientRect();
  if (rect.width <= 0) return null;
  const pct = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
  return Math.min(Math.floor(r.start + pct * (r.end - r.start)), scrubMax(r));
}

function onTimelineSeekWall(wallMs, startLive) {
  if (wallMs == null) return;
  if (isLiveEdgeWall(wallMs)) {
    backToLive(startLive).catch(function () {});
    return;
  }
  playAt(wallMs, startLive);
}

function updateHoverTip(trackEl, clientX) {
  const tip = document.getElementById("cameraTimelineHover");
  if (!tip || !trackEl) return;
  const wall = wallFromClientX(trackEl, clientX);
  if (wall == null) {
    tip.hidden = true;
    return;
  }
  const rect = trackEl.getBoundingClientRect();
  const pct = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
  tip.hidden = false;
  tip.textContent = isLiveEdgeWall(wall) ? t("cameras.live", "Live") : fmtWall(wall);
  tip.style.left = pct * 100 + "%";
}

function movePlayheadTo(r, wallMs) {
  const ph = document.getElementById("cameraTimelinePlayhead");
  if (ph && wallMs != null && hasSpan(r)) ph.style.left = ((wallMs - r.start) / (r.end - r.start)) * 100 + "%";
}

function grabCut(kind, ev) {
  const track = document.getElementById("cameraTimelineTrack");
  if (!track || !beginCutDrag(kind, wallFromClientX(track, ev.clientX))) return;
  timelineSeeking = false;
  try {
    track.setPointerCapture(ev.pointerId);
  } catch (e) {}
}

// --- axis -------------------------------------------------------------------

/** Nice step (ms) for axis labels across [start, end]. */
function timelineAxisStepMs(spanMs) {
  const candidates = [15e3, 30e3, 60e3, 120e3, 300e3, 600e3, 900e3, 1800e3, 3600e3, 7200e3, 21600e3];
  let step = candidates[0];
  for (let i = 0; i < candidates.length; i++) {
    step = candidates[i];
    if (spanMs / step <= 6) break;
  }
  return step;
}

function fmtAxisTick(ms) {
  const n = Number(ms);
  if (!n) return "—";
  try {
    return new Date(n).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  } catch (e) {
    return String(ms);
  }
}

/** Labels under the scrubber across the fixed 24h day. */
function timelineAxisLabels(r) {
  if (!hasSpan(r)) return [];
  const span = r.end - r.start;
  const step = timelineAxisStepMs(span);
  const out = [{ pct: 0, text: "00:00", edge: "start" }];
  let tick = Math.ceil(r.start / step) * step;
  if (tick <= r.start) tick += step;
  const livePct = r.isToday && r.liveAt ? ((r.liveAt - r.start) / span) * 100 : null;
  for (; tick < r.end; tick += step) {
    const pct = ((tick - r.start) / span) * 100;
    // Keep clear of the edges and the Live label.
    if (pct > 6 && pct < 94 && (livePct == null || Math.abs(pct - livePct) > 6)) {
      out.push({ pct: pct, text: fmtAxisTick(tick), edge: "mid" });
    }
  }
  if (r.isToday && r.liveAt != null) {
    out.push({ pct: Math.max(0, Math.min(100, livePct)), text: t("cameras.live", "Live"), edge: "live" });
  } else {
    out.push({ pct: 100, text: "24:00", edge: "end" });
  }
  return out;
}

function timelineAxisHtml(r) {
  if (!hasSpan(r)) {
    return html`<div class="camera-timeline-axis">
      <span class="sub">${t("cameras.timeline.empty", "No recorded history yet")}</span>
    </div>`;
  }
  const emptyDay = !(r.segs && r.segs.length);
  return html`<div class="camera-timeline-axis" aria-hidden="true">
    ${emptyDay
      ? html`<span class="camera-timeline-axis-empty sub">${t("cameras.timeline.empty_day", "No recordings this day")}</span>`
      : nothing}
    ${timelineAxisLabels(r).map(function (l) {
      return html`<span class=${"camera-timeline-axis-lab " + l.edge} style=${"left:" + l.pct + "%"}>${l.text}</span>`;
    })}
  </div>`;
}

function futureMaskHtml(r) {
  if (!r.isToday || r.liveAt == null || !hasSpan(r)) return nothing;
  const pct = ((r.liveAt - r.start) / (r.end - r.start)) * 100;
  if (pct >= 99.5) return nothing;
  return html`<div
    class="camera-timeline-future"
    style=${"left:" + pct + "%;width:" + Math.max(0, 100 - pct) + "%"}
    aria-hidden="true"
  ></div>`;
}

// --- view -------------------------------------------------------------------

function transportHtml(r, startLive, hasRecordings) {
  const isLive = state.cameraPlayerMode === "live";
  const canTransport = isLive || state.cameraPlayerMode === "dvr";
  const paused = !!state.cameraPlaybackPaused;
  const speed = currentSpeed();
  return html`<div class="row camera-transport">
    <button
      type="button"
      class="btn ghost camera-transport-play"
      id="cameraTransportPause"
      ?disabled=${!canTransport}
      title=${paused ? t("cameras.play.resume", "Resume") : t("cameras.play.pause", "Pause")}
      @click=${async function () {
        if (!canTransport) return;
        await togglePlaybackPause();
        syncTransport();
        patch({});
      }}
    >
      ${paused ? "▶" : "❚❚"}
    </button>
    <button
      type="button"
      class="btn ghost"
      ?disabled=${!canTransport || speed <= SPEED_STEPS[0]}
      title=${t("cameras.play.speed_slower", "Slower")}
      @click=${function () {
        nudgeSpeed(-1);
      }}
    >
      −
    </button>
    <span class="mono camera-transport-speed" id="cameraTransportSpeed">${formatSpeed(speed)}</span>
    <button
      type="button"
      class="btn ghost"
      ?disabled=${!canTransport || speed >= SPEED_STEPS[SPEED_STEPS.length - 1]}
      title=${t("cameras.play.speed_faster", "Faster")}
      @click=${function () {
        nudgeSpeed(1);
      }}
    >
      +
    </button>
    <button
      type="button"
      class="btn ghost"
      id="cameraTimelineLive"
      ?disabled=${isLive && !paused && r.isToday}
      @click=${async function () {
        patch({ cameraPlaybackPaused: false });
        await backToLive(startLive);
      }}
    >
      ${t("cameras.play.live", "Back to live")}
    </button>
    <div class="row camera-cut-bar" style="margin:0;margin-left:auto">${cutBarHtml(hasRecordings)}</div>
  </div>`;
}

export function cameraTimelineView(opts) {
  ensureTimelinePaintClock();
  setLiveStarter(opts && opts.startLive);
  const startLive = getLiveStarter();
  const r = timelineRange();
  const hasTimeline = hasSpan(r);
  const hasRecordings = !!(r.segs && r.segs.length);
  const playPct = hasTimeline ? pctOf(r, wallPlayheadMs()) : 100;
  const livePct = r.isToday && r.liveAt != null && hasTimeline ? pctOf(r, r.liveAt) : null;

  return html`
    <div class="card camera-timeline">
      ${dayPickerHtml()} ${transportHtml(r, startLive, hasRecordings)}
      <div
        class=${"camera-timeline-track" + (hasTimeline ? "" : " is-empty") + (isCutting() ? " is-cutting" : "")}
        id="cameraTimelineTrack"
        role="slider"
        tabindex="0"
        aria-valuemin="0"
        aria-valuemax="100"
        aria-valuenow=${String(Math.round(playPct))}
        aria-label=${t("cameras.timeline.seek", "Scrub timeline")}
        @keydown=${function (ev) {
          if (!hasTimeline) return;
          const key = ev.key;
          if (key !== "ArrowLeft" && key !== "ArrowRight" && key !== "Home" && key !== "End") return;
          ev.preventDefault();
          const step = ev.shiftKey ? 60_000 : 10_000;
          const max = scrubMax(r);
          let wall = wallPlayheadMs();
          if (key === "Home") wall = r.start;
          else if (key === "End") wall = max;
          else if (key === "ArrowLeft") wall -= step;
          else wall += step;
          onTimelineSeekWall(Math.max(r.start, Math.min(Math.floor(wall), max)), startLive);
        }}
        @pointerdown=${function (ev) {
          if (!hasTimeline || isCutDragging()) return;
          const track = ev.currentTarget;
          timelineSeeking = true;
          try {
            track.setPointerCapture(ev.pointerId);
          } catch (e) {}
          movePlayheadTo(r, wallFromClientX(track, ev.clientX));
          updateHoverTip(track, ev.clientX);
        }}
        @pointermove=${function (ev) {
          const track = ev.currentTarget;
          updateHoverTip(track, ev.clientX);
          if (isCutDragging()) {
            moveCutDrag(wallFromClientX(track, ev.clientX));
            return;
          }
          if (timelineSeeking && hasTimeline) movePlayheadTo(r, wallFromClientX(track, ev.clientX));
        }}
        @pointerup=${function (ev) {
          if (isCutDragging()) {
            endCutDrag();
            return;
          }
          if (!timelineSeeking) return;
          timelineSeeking = false;
          onTimelineSeekWall(wallFromClientX(ev.currentTarget, ev.clientX), startLive);
        }}
        @pointercancel=${function () {
          endCutDrag();
          timelineSeeking = false;
        }}
        @pointerleave=${function () {
          const tip = document.getElementById("cameraTimelineHover");
          if (tip && !timelineSeeking && !isCutDragging()) tip.hidden = true;
        }}
      >
        <div class="camera-timeline-rail" aria-hidden="true"></div>
        ${timelineTicksHtml(r)} ${futureMaskHtml(r)} ${cutRangeHtml(r, grabCut)}
        ${livePct != null
          ? html`<span
              class="camera-timeline-live-edge"
              id="cameraTimelineLiveEdge"
              style=${"left:" + livePct + "%"}
              aria-hidden="true"
            ></span>`
          : nothing}
        <span class="camera-timeline-playhead" id="cameraTimelinePlayhead" style=${"left:" + playPct + "%"}></span>
        <span class="camera-timeline-hover mono" id="cameraTimelineHover" hidden></span>
      </div>
      ${timelineAxisHtml(r)}
    </div>
  `;
}

/**
 * DVR day scrubber: day picker, transport row, segment ticks, axis labels and
 * pointer / keyboard seeking. Playback state lives in camera-player.js; this element
 * re-renders on its "sync" ticks and owns the clip range (camera-cut.js).
 */
import { html, nothing } from "lit";
import { OaaElement } from "../lit/oaa-element.js";
import { camera, dvr } from "../store.js";
import { t } from "../i18n.js";
import { icon } from "../icons.js";
import { dayKeyFromMs, todayKey, dayBounds, availableTimelineDays as daysFromSegments } from "./dvr-timeline.js";
import {
  SPEED_STEPS,
  backToLive,
  currentSpeed,
  fmtWall,
  formatSpeed,
  atLiveEdge,
  nudgeSpeed,
  onPlayerEvent,
  playAt,
  selectedDayKey,
  syncTransport,
  timeline,
  timelineRange,
  togglePlaybackPause,
  wallPlayheadMs,
} from "./camera-player.js";
import { CutController } from "./camera-cut.js";

const LIVE_TICK_MS = 2_000;
const FULL_PAINT_MS = 1_000;

function pctOf(r, wall) {
  return Math.max(0, Math.min(100, ((wall - r.start) / (r.end - r.start)) * 100));
}

function hasSpan(r) {
  return !!(r && r.start && r.end && r.end > r.start);
}

function scrubMax(r) {
  return r.scrubMax != null ? r.scrubMax : r.end - 1;
}

// --- days -------------------------------------------------------------------

/** @type {{ segments: any[], today: string, days: string[] } | null} */
let daysMemo = null;

/** Days that have any stored/active segment, plus today. Newest first. */
function availableTimelineDays() {
  const segments = timeline().segments || [];
  const today = todayKey();
  if (!daysMemo || daysMemo.segments !== segments || daysMemo.today !== today) {
    daysMemo = { segments, today, days: daysFromSegments(segments) };
  }
  return daysMemo.days;
}

const FMT_DAY = new Intl.DateTimeFormat(undefined, { weekday: "short", month: "short", day: "numeric" });
const FMT_AXIS = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" });

function formatDayLabel(dayKey) {
  if (dayKey === todayKey()) return t("cameras.timeline.today", "Today");
  if (dayKey === dayKeyFromMs(dayBounds(todayKey()).start - 1)) {
    return t("cameras.timeline.yesterday", "Yesterday");
  }
  const p = dayKey.split("-").map(Number);
  return FMT_DAY.format(new Date(p[0], p[1] - 1, p[2]));
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
  return "left:" + Math.max(0, left) + "%;width:" + Math.max(0.35, width) + "%";
}

function timelineTicksHtml(r) {
  if (!hasSpan(r) || !r.segs.length) return nothing;
  return html`<div class="camera-timeline-ticks" aria-hidden="true">
    ${displaySegsForTrack(r).map(function (s) {
      return html`<span class=${"camera-timeline-seg" + (s.active ? " is-active" : "")} style=${segStyle(r, s)}></span>`;
    })}
  </div>`;
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
  return n ? FMT_AXIS.format(n) : "—";
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

function seekWall(wallMs) {
  if (wallMs == null) return;
  if (atLiveEdge(wallMs)) {
    backToLive().catch(function () {});
    return;
  }
  playAt(wallMs);
}

// --- element ----------------------------------------------------------------

class OaaCameraTimeline extends OaaElement {
  static properties = {
    seeking: { state: true },
    hover: { state: true },
  };

  constructor() {
    super();
    /** @type {number|null} wall time under the pointer while scrubbing */
    this.seeking = null;
    /** @type {{ pct: number, text: string }|null} */
    this.hover = null;
    this.cut = new CutController(this);
    /** @type {(() => void)|null} */
    this.offSync = null;
    this.paintClock = 0;
    this.lastFullPaint = 0;
  }

  connectedCallback() {
    super.connectedCallback();
    this.offSync = onPlayerEvent("sync", () => this.onSync());
    // Keep the live range stretching between timeline refreshes (no API needed).
    this.paintClock = window.setInterval(function () {
      if (selectedDayKey() !== todayKey() && !timeline().recording && camera.mode !== "dvr") return;
      syncTransport();
    }, LIVE_TICK_MS);
  }

  /** Playback ticks at 10 Hz: move the playhead in place, re-render the rest at most once a second. */
  onSync() {
    const now = performance.now();
    if (this.seeking != null || now - this.lastFullPaint >= FULL_PAINT_MS) {
      this.requestUpdate();
      return;
    }
    const r = timelineRange();
    const head = this.querySelector(".camera-timeline-playhead");
    const track = this.track;
    if (!hasSpan(r) || !(head instanceof HTMLElement) || !track) return;
    const wall = wallPlayheadMs();
    const pct = pctOf(r, wall);
    head.style.left = pct + "%";
    track.setAttribute("aria-valuenow", String(Math.round(pct)));
    track.setAttribute("aria-valuetext", fmtWall(wall));
  }

  updated(changed) {
    super.updated(changed);
    this.lastFullPaint = performance.now();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    if (this.offSync) this.offSync();
    this.offSync = null;
    clearInterval(this.paintClock);
  }

  /** @returns {HTMLElement|null} */
  get track() {
    return this.querySelector(".camera-timeline-track");
  }

  wallAt(clientX) {
    const r = timelineRange();
    const track = this.track;
    if (!hasSpan(r) || !track) return null;
    const rect = track.getBoundingClientRect();
    if (rect.width <= 0) return null;
    const pct = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width));
    return Math.min(Math.floor(r.start + pct * (r.end - r.start)), scrubMax(r));
  }

  updateHover(clientX) {
    const track = this.track;
    const wall = this.wallAt(clientX);
    if (wall == null || !track) {
      this.hover = null;
      return;
    }
    const rect = track.getBoundingClientRect();
    const pct = Math.max(0, Math.min(1, (clientX - rect.left) / rect.width)) * 100;
    this.hover = { pct: pct, text: atLiveEdge(wall) ? t("cameras.live", "Live") : fmtWall(wall) };
  }

  setDay(dayKey) {
    const days = availableTimelineDays();
    let next = dayKey || todayKey();
    if (days.indexOf(next) < 0) next = todayKey();
    if (next === selectedDayKey()) {
      if (next === todayKey()) dvr.timelineDay = null;
      return;
    }
    if (this.cut.mode) this.cut.clear();
    dvr.timelineDay = next === todayKey() ? null : next;
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
      playAt(Math.min(at, bounds.scrubMax)).catch(function () {});
    }
  }

  shiftDay(dir) {
    const days = availableTimelineDays();
    const i = Math.max(0, days.indexOf(selectedDayKey()));
    // days are newest-first; dir -1 = older, +1 = newer
    const next = days[i - dir];
    if (next) this.setDay(next);
  }

  dayPickerHtml() {
    const days = availableTimelineDays();
    const cur = selectedDayKey();
    const i = days.indexOf(cur);
    const older = t("cameras.timeline.prev_day", "Previous day");
    const newer = t("cameras.timeline.next_day", "Next day");
    return html`<div class="camera-timeline-day">
      <wa-button
        size="small"
        appearance="plain"
        title=${older}
        ?disabled=${!(i >= 0 && i < days.length - 1)}
        @click=${() => this.shiftDay(-1)}
        >${icon("chevron-left", older)}</wa-button
      >
      <wa-select
        size="small"
        class="camera-timeline-day-select"
        label=${t("cameras.timeline.day", "Day")}
        .value=${cur}
        @change=${(ev) => this.setDay(ev.target.value)}
      >
        ${days.map(function (d) {
          return html`<wa-option value=${d}>${formatDayLabel(d)}</wa-option>`;
        })}
      </wa-select>
      <wa-button size="small" appearance="plain" title=${newer} ?disabled=${!(i > 0)} @click=${() => this.shiftDay(1)}
        >${icon("chevron-right", newer)}</wa-button
      >
    </div>`;
  }

  transportHtml(r, hasRecordings) {
    const isLive = camera.mode === "live";
    const canTransport = isLive || camera.mode === "dvr";
    const paused = !!camera.paused;
    const speed = currentSpeed();
    const playLabel = paused ? t("cameras.play.resume", "Resume") : t("cameras.play.pause", "Pause");
    const slower = t("cameras.play.speed_slower", "Slower");
    const faster = t("cameras.play.speed_faster", "Faster");
    return html`<div class="camera-transport">
      <wa-button-group label=${t("cameras.play.transport", "Playback")}>
        <wa-button
          size="small"
          appearance="outlined"
          class="camera-transport-play"
          title=${playLabel}
          ?disabled=${!canTransport}
          @click=${() => togglePlaybackPause()}
          >${icon(paused ? "play" : "pause", playLabel)}</wa-button
        >
        <wa-button
          size="small"
          appearance="outlined"
          title=${slower}
          ?disabled=${!canTransport || speed <= SPEED_STEPS[0]}
          @click=${() => nudgeSpeed(-1)}
          >${icon("minus", slower)}</wa-button
        >
        <wa-button size="small" appearance="outlined" class="camera-transport-speed" disabled>${formatSpeed(speed)}</wa-button>
        <wa-button
          size="small"
          appearance="outlined"
          title=${faster}
          ?disabled=${!canTransport || speed >= SPEED_STEPS[SPEED_STEPS.length - 1]}
          @click=${() => nudgeSpeed(1)}
          >${icon("plus", faster)}</wa-button
        >
      </wa-button-group>
      <wa-button
        size="small"
        appearance="outlined"
        ?disabled=${isLive && !paused && r.isToday}
        @click=${() => backToLive()}
        >${t("cameras.play.live", "Back to live")}</wa-button
      >
      <div class="camera-cut-bar">${this.cut.barHtml(hasRecordings)}</div>
    </div>`;
  }

  onKeydown(ev, r) {
    if (!hasSpan(r)) return;
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
    seekWall(Math.max(r.start, Math.min(Math.floor(wall), max)));
  }

  onPointerDown(ev, r) {
    if (!hasSpan(r) || this.cut.dragging) return;
    try {
      ev.currentTarget.setPointerCapture(ev.pointerId);
    } catch (e) {}
    this.seeking = this.wallAt(ev.clientX);
    this.updateHover(ev.clientX);
  }

  onPointerMove(ev) {
    this.updateHover(ev.clientX);
    if (this.cut.dragging) {
      this.cut.move(this.wallAt(ev.clientX));
      return;
    }
    if (this.seeking != null) this.seeking = this.wallAt(ev.clientX);
  }

  onPointerUp(ev) {
    if (this.cut.dragging) {
      this.cut.end();
      return;
    }
    if (this.seeking == null) return;
    this.seeking = null;
    seekWall(this.wallAt(ev.clientX));
  }

  /** @param {import("./camera-cut.js").CutDrag} kind @param {PointerEvent} ev */
  grabCut(kind, ev) {
    const track = this.track;
    if (!track || !this.cut.begin(kind, this.wallAt(ev.clientX))) return;
    this.seeking = null;
    try {
      track.setPointerCapture(ev.pointerId);
    } catch (e) {}
  }

  render() {
    const r = timelineRange();
    const hasTimeline = hasSpan(r);
    const hasRecordings = !!(r.segs && r.segs.length);
    const head = this.seeking != null && !this.cut.dragging ? this.seeking : wallPlayheadMs();
    const playPct = hasTimeline ? pctOf(r, head) : 100;
    const livePct = r.isToday && r.liveAt != null && hasTimeline ? pctOf(r, r.liveAt) : null;

    return html`
      <wa-card class="camera-timeline">
        ${this.dayPickerHtml()} ${this.transportHtml(r, hasRecordings)}
        <div
          class=${"camera-timeline-track" + (hasTimeline ? "" : " is-empty") + (this.cut.mode ? " is-cutting" : "")}
          role="slider"
          tabindex="0"
          aria-valuemin="0"
          aria-valuemax="100"
          aria-valuenow=${String(Math.round(playPct))}
          aria-valuetext=${fmtWall(head)}
          aria-label=${t("cameras.timeline.seek", "Scrub timeline")}
          @keydown=${(ev) => this.onKeydown(ev, r)}
          @pointerdown=${(ev) => this.onPointerDown(ev, r)}
          @pointermove=${(ev) => this.onPointerMove(ev)}
          @pointerup=${(ev) => this.onPointerUp(ev)}
          @pointercancel=${() => {
            this.cut.end();
            this.seeking = null;
          }}
          @pointerleave=${() => {
            if (this.seeking == null && !this.cut.dragging) this.hover = null;
          }}
        >
          <div class="camera-timeline-rail" aria-hidden="true"></div>
          ${timelineTicksHtml(r)} ${futureMaskHtml(r)}
          ${this.cut.rangeHtml(r, (kind, ev) => this.grabCut(kind, ev))}
          ${livePct != null
            ? html`<span class="camera-timeline-live-edge" style=${"left:" + livePct + "%"} aria-hidden="true"></span>`
            : nothing}
          <span class="camera-timeline-playhead" style=${"left:" + playPct + "%"}></span>
          ${this.hover
            ? html`<span class="camera-timeline-hover mono" style=${"left:" + this.hover.pct + "%"}>${this.hover.text}</span>`
            : nothing}
        </div>
        ${timelineAxisHtml(r)}
      </wa-card>
    `;
  }
}
customElements.define("oaa-camera-timeline", OaaCameraTimeline);

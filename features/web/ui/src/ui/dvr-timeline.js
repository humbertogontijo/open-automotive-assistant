/**
 * Day-scoped DVR timeline math (pure helpers; no DOM).
 * Local midnight → next midnight; today caps scrub at live.
 */

export const LIVE_EDGE_TIP_MS = 400;
export const DAY_MS = 24 * 60 * 60 * 1000;

/** @param {number} ms */
export function dayKeyFromMs(ms) {
  const d = new Date(Number(ms) || Date.now());
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return y + "-" + m + "-" + day;
}

export function todayKey() {
  return dayKeyFromMs(Date.now());
}

/**
 * @param {string} [dayKey] YYYY-MM-DD
 * @param {number} [nowMs]
 */
export function dayBounds(dayKey, nowMs) {
  const key = dayKey || todayKey();
  const now = nowMs != null ? Number(nowMs) : Date.now();
  const p = String(key)
    .split("-")
    .map(function (x) {
      return Number(x);
    });
  const start = new Date(p[0], p[1] - 1, p[2], 0, 0, 0, 0).getTime();
  const endFull = start + DAY_MS;
  const isToday = key === todayKey();
  const liveAt = isToday ? Math.min(Math.max(now, start), endFull) : null;
  const scrubMax = isToday
    ? Math.max(start, Math.min(now, endFull - 1))
    : endFull - 1;
  return {
    start: start,
    endFull: endFull,
    end: endFull,
    isToday: isToday,
    liveAt: liveAt,
    scrubMax: scrubMax,
  };
}

/**
 * Days that have any segment, plus today. Newest first.
 * @param {{ startUtcMs?: number, endUtcMs?: number }[]} segments
 */
export function availableTimelineDays(segments) {
  const keys = {};
  keys[todayKey()] = true;
  const segs = segments || [];
  for (let i = 0; i < segs.length; i++) {
    const a = Number(segs[i].startUtcMs) || 0;
    const b = Number(segs[i].endUtcMs) || a;
    if (!a) continue;
    let t = dayBounds(dayKeyFromMs(a)).start;
    const last = dayBounds(dayKeyFromMs(b)).start;
    while (t <= last) {
      keys[dayKeyFromMs(t)] = true;
      t += DAY_MS;
    }
  }
  return Object.keys(keys).sort().reverse();
}

/**
 * @param {string} dayKey
 * @param {{ segments?: object[], recording?: boolean }} timeline
 * @param {number} [nowMs]
 */
export function timelineRangeForDay(dayKey, timeline, nowMs) {
  const tl = timeline || {};
  const bounds = dayBounds(dayKey, nowMs);
  const segs = (tl.segments || []).filter(function (s) {
    const a = Number(s.startUtcMs) || 0;
    const b = Number(s.endUtcMs) || 0;
    return b > bounds.start && a < bounds.endFull;
  });
  return {
    start: bounds.start,
    end: bounds.endFull,
    scrubMax: bounds.scrubMax,
    liveAt: bounds.liveAt,
    isToday: bounds.isToday,
    segs: segs,
    recording: !!tl.recording && bounds.isToday,
  };
}

/**
 * @param {number} wallMs
 * @param {{ scrubMax?: number, isToday?: boolean, liveAt?: number|null }} r
 * @param {number} [tipMs]
 */
export function isLiveEdgeWall(wallMs, r, tipMs) {
  if (!r || !r.isToday) return false;
  const tip = tipMs != null ? tipMs : LIVE_EDGE_TIP_MS;
  const max = r.scrubMax != null ? r.scrubMax : r.liveAt;
  if (max == null) return false;
  return Number(wallMs) >= max - tip;
}

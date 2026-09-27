/**
 * Clip range on the DVR scrubber: a draggable [from, to] wall-clock window
 * exported through the media transport (/api/dvr/cut or cut_request).
 */
import { html, nothing } from "../lit.js";
import { patch } from "../store.js";
import { t } from "../i18n.js";
import { errText } from "../api.js";
import { mediaTransport } from "./media-transport.js";
import { timelineRange, wallPlayheadMs, fmtWall, onPlayerEvent } from "./camera-player.js";

const MIN_CUT_MS = 1000;

/** @type {number|null} wall UTC */
let cutFromMs = null;
/** @type {number|null} wall UTC */
let cutToMs = null;
let cutBusy = false;
let cutMode = false;
/** @type {"from"|"to"|"move"|null} */
let cutDrag = null;
/** @type {{ from: number, to: number, wall: number }|null} */
let cutDragOrigin = null;

onPlayerEvent("sync", syncCutDom);
onPlayerEvent("live", clearCutMarks);

export function isCutDragging() {
  return !!cutDrag;
}

export function clearCutMarks() {
  cutFromMs = null;
  cutToMs = null;
  cutMode = false;
  cutDrag = null;
  cutDragOrigin = null;
}

function scrubMax(r) {
  return r.scrubMax != null ? r.scrubMax : r.end - 1;
}

function enterCutMode() {
  const r = timelineRange();
  if (!r.start || !r.end || r.end <= r.start) return;
  const maxEnd = scrubMax(r);
  const span = Math.max(MIN_CUT_MS, maxEnd - r.start);
  const win = Math.max(MIN_CUT_MS * 5, Math.min(60_000, Math.floor(span * 0.12)));
  let center = wallPlayheadMs();
  if (!center || center < r.start || center > maxEnd) {
    center = maxEnd - Math.floor(win / 2);
  }
  let from = Math.floor(center - win / 2);
  let to = Math.floor(center + win / 2);
  if (from < r.start) {
    to += r.start - from;
    from = r.start;
  }
  if (to > maxEnd) {
    from -= to - maxEnd;
    to = maxEnd;
  }
  from = Math.max(r.start, from);
  to = Math.min(maxEnd, Math.max(from + MIN_CUT_MS, to));
  cutFromMs = from;
  cutToMs = to;
  cutMode = true;
  cutDrag = null;
  cutDragOrigin = null;
  patch({});
}

function exitCutMode() {
  clearCutMarks();
  patch({});
}

function clampCutRange(from, to) {
  const r = timelineRange();
  if (!r.start || !r.end || r.end <= r.start) return;
  const maxEnd = scrubMax(r);
  let a = Math.max(r.start, Math.min(from, maxEnd - MIN_CUT_MS));
  let b = Math.min(maxEnd, Math.max(to, r.start + MIN_CUT_MS));
  if (b - a < MIN_CUT_MS) {
    if (cutDrag === "from") a = b - MIN_CUT_MS;
    else b = a + MIN_CUT_MS;
    a = Math.max(r.start, a);
    b = Math.min(maxEnd, b);
  }
  cutFromMs = Math.floor(a);
  cutToMs = Math.floor(b);
}

function canSaveCut() {
  return cutFromMs != null && cutToMs != null && cutFromMs < cutToMs && !cutBusy;
}

function saveBlob(blob, filename) {
  const obj = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = obj;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(function () {
    URL.revokeObjectURL(obj);
  }, 2000);
}

async function downloadCut() {
  if (!canSaveCut()) return;
  cutBusy = true;
  patch({ cameraPreviewError: "" });
  try {
    const res = await mediaTransport().cut(cutFromMs, cutToMs);
    saveBlob(res.blob, res.name);
    clearCutMarks();
  } catch (e) {
    patch({ cameraPreviewError: errText(e) });
  } finally {
    cutBusy = false;
    patch({});
  }
}

/** Start dragging a handle (or the whole range) from wall time [wall]. */
export function beginCutDrag(kind, wall) {
  if (!cutMode || cutFromMs == null || cutToMs == null || wall == null) return false;
  cutDrag = kind;
  cutDragOrigin = { from: cutFromMs, to: cutToMs, wall: wall };
  return true;
}

export function moveCutDrag(wall) {
  if (!cutDrag || !cutDragOrigin || wall == null) return;
  if (cutDrag === "from") {
    clampCutRange(wall, cutDragOrigin.to);
  } else if (cutDrag === "to") {
    clampCutRange(cutDragOrigin.from, wall);
  } else {
    const delta = wall - cutDragOrigin.wall;
    const len = cutDragOrigin.to - cutDragOrigin.from;
    const r = timelineRange();
    const maxEnd = scrubMax(r);
    let from = cutDragOrigin.from + delta;
    let to = cutDragOrigin.to + delta;
    if (from < r.start) {
      from = r.start;
      to = from + len;
    }
    if (to > maxEnd) {
      to = maxEnd;
      from = to - len;
    }
    clampCutRange(from, to);
  }
  syncCutDom();
}

export function endCutDrag() {
  if (!cutDrag) return;
  cutDrag = null;
  cutDragOrigin = null;
  patch({});
}

function cutGeometry(r) {
  if (!cutMode || cutFromMs == null || cutToMs == null || !r.start || r.end <= r.start) return null;
  const span = r.end - r.start;
  const left = ((cutFromMs - r.start) / span) * 100;
  const width = ((cutToMs - cutFromMs) / span) * 100;
  return { left: left, width: width, right: left + width };
}

function syncCutDom() {
  const mark = document.getElementById("cameraPlayerCutLabel");
  if (mark) {
    const on = cutMode && cutFromMs != null && cutToMs != null;
    mark.textContent = on ? fmtWall(cutFromMs) + " – " + fmtWall(cutToMs) : "";
    mark.style.display = on ? "" : "none";
  }
  const saveBtn = document.getElementById("cameraPlayerCutSave");
  if (saveBtn) saveBtn.disabled = !canSaveCut();
  const g = cutGeometry(timelineRange());
  if (!g) return;
  const el = document.getElementById("cameraTimelineCut");
  if (el) {
    el.style.left = g.left + "%";
    el.style.width = Math.max(0.4, g.width) + "%";
  }
  const maskL = document.getElementById("cameraTimelineCutMaskL");
  if (maskL) maskL.style.width = Math.max(0, g.left) + "%";
  const maskR = document.getElementById("cameraTimelineCutMaskR");
  if (maskR) {
    maskR.style.left = g.right + "%";
    maskR.style.width = Math.max(0, 100 - g.right) + "%";
  }
}

/**
 * Range overlay for the scrubber track.
 * @param {object} r timeline range
 * @param {(kind: "from"|"to"|"move", ev: PointerEvent) => void} onGrab
 */
export function cutRangeHtml(r, onGrab) {
  const g = cutGeometry(r);
  if (!g) return nothing;
  const grab = function (kind) {
    return function (ev) {
      if (kind !== "move") ev.preventDefault();
      ev.stopPropagation();
      onGrab(kind, ev);
    };
  };
  return html`
    <div
      class="camera-timeline-cut-mask start"
      id="cameraTimelineCutMaskL"
      style=${"width:" + Math.max(0, g.left) + "%"}
      aria-hidden="true"
    ></div>
    <div
      class="camera-timeline-cut-mask end"
      id="cameraTimelineCutMaskR"
      style=${"left:" + g.right + "%;width:" + Math.max(0, 100 - g.right) + "%"}
      aria-hidden="true"
    ></div>
    <div
      class="camera-timeline-cut"
      id="cameraTimelineCut"
      style=${"left:" + g.left + "%;width:" + Math.max(0.4, g.width) + "%"}
      @pointerdown=${grab("move")}
    >
      <button
        type="button"
        class="camera-timeline-cut-handle start"
        aria-label=${t("cameras.cut.handle_start", "Cut start")}
        @pointerdown=${grab("from")}
      ></button>
      <button
        type="button"
        class="camera-timeline-cut-handle end"
        aria-label=${t("cameras.cut.handle_end", "Cut end")}
        @pointerdown=${grab("to")}
      ></button>
    </div>
  `;
}

export function isCutting() {
  return cutMode;
}

/** Cut / Cancel / Download controls for the transport row. */
export function cutBarHtml(hasRecordings) {
  if (!cutMode) {
    return html`
      <button
        type="button"
        class="btn ghost"
        ?disabled=${!hasRecordings}
        title=${t("cameras.cut.edit", "Select a clip range on the timeline")}
        @click=${enterCutMode}
      >
        ${t("cameras.cut.edit", "Cut")}
      </button>
      <span class="sub mono" id="cameraPlayerCutLabel" style="display:none"></span>
    `;
  }
  return html`
    <span class="sub mono" id="cameraPlayerCutLabel">${fmtWall(cutFromMs)} – ${fmtWall(cutToMs)}</span>
    <button type="button" class="btn ghost" @click=${exitCutMode}>${t("cameras.cut.cancel", "Cancel")}</button>
    <button type="button" class="btn primary" id="cameraPlayerCutSave" ?disabled=${!canSaveCut()} @click=${downloadCut}>
      ${t("cameras.cut.download", "Download clip")}
    </button>
  `;
}

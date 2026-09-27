/**
 * Clip range on the DVR scrubber: a draggable [from, to] wall-clock window
 * exported through the media transport (/api/dvr/cut or cut_request).
 * A reactive controller owned by <oaa-camera-timeline>.
 */
import { html, nothing } from "lit";
import { camera } from "../store.js";
import { t } from "../i18n.js";
import { errText } from "../api.js";
import { mediaTransport } from "./media-transport.js";
import { timelineRange, wallPlayheadMs, fmtWall, onPlayerEvent } from "./camera-player.js";

const MIN_CUT_MS = 1000;

function scrubMax(r) {
  return r.scrubMax != null ? r.scrubMax : r.end - 1;
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

/** @typedef {"from" | "to" | "move"} CutDrag */

export class CutController {
  /** @param {import("lit").ReactiveControllerHost} host */
  constructor(host) {
    this.host = host;
    /** @type {number|null} wall UTC */
    this.from = null;
    /** @type {number|null} wall UTC */
    this.to = null;
    this.mode = false;
    this.busy = false;
    /** @type {CutDrag|null} */
    this.drag = null;
    /** @type {{ from: number, to: number, wall: number }|null} */
    this.origin = null;
    /** @type {(() => void)|null} */
    this.offLive = null;
    host.addController(this);
  }

  hostConnected() {
    this.offLive = onPlayerEvent("live", () => this.clear());
  }

  hostDisconnected() {
    if (this.offLive) this.offLive();
    this.offLive = null;
    this.reset();
  }

  get dragging() {
    return !!this.drag;
  }

  reset() {
    this.from = null;
    this.to = null;
    this.mode = false;
    this.drag = null;
    this.origin = null;
  }

  clear() {
    this.reset();
    this.host.requestUpdate();
  }

  enter() {
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
    this.reset();
    this.from = from;
    this.to = to;
    this.mode = true;
    this.host.requestUpdate();
  }

  clamp(from, to) {
    const r = timelineRange();
    if (!r.start || !r.end || r.end <= r.start) return;
    const maxEnd = scrubMax(r);
    let a = Math.max(r.start, Math.min(from, maxEnd - MIN_CUT_MS));
    let b = Math.min(maxEnd, Math.max(to, r.start + MIN_CUT_MS));
    if (b - a < MIN_CUT_MS) {
      if (this.drag === "from") a = b - MIN_CUT_MS;
      else b = a + MIN_CUT_MS;
      a = Math.max(r.start, a);
      b = Math.min(maxEnd, b);
    }
    this.from = Math.floor(a);
    this.to = Math.floor(b);
  }

  get canSave() {
    return this.from != null && this.to != null && this.from < this.to && !this.busy;
  }

  async download() {
    if (!this.canSave) return;
    this.busy = true;
    camera.previewError = "";
    this.host.requestUpdate();
    try {
      const res = await mediaTransport().cut(this.from, this.to);
      saveBlob(res.blob, res.name);
      this.reset();
    } catch (e) {
      camera.previewError = errText(e);
    } finally {
      this.busy = false;
      this.host.requestUpdate();
    }
  }

  /**
   * Start dragging a handle (or the whole range) from wall time [wall].
   * @param {CutDrag} kind
   * @param {number|null} wall
   */
  begin(kind, wall) {
    if (!this.mode || this.from == null || this.to == null || wall == null) return false;
    this.drag = kind;
    this.origin = { from: this.from, to: this.to, wall: wall };
    return true;
  }

  /** @param {number|null} wall */
  move(wall) {
    const o = this.origin;
    if (!this.drag || !o || wall == null) return;
    if (this.drag === "from") {
      this.clamp(wall, o.to);
    } else if (this.drag === "to") {
      this.clamp(o.from, wall);
    } else {
      const delta = wall - o.wall;
      const len = o.to - o.from;
      const r = timelineRange();
      const maxEnd = scrubMax(r);
      let from = o.from + delta;
      let to = o.to + delta;
      if (from < r.start) {
        from = r.start;
        to = from + len;
      }
      if (to > maxEnd) {
        to = maxEnd;
        from = to - len;
      }
      this.clamp(from, to);
    }
    this.host.requestUpdate();
  }

  end() {
    if (!this.drag) return;
    this.drag = null;
    this.origin = null;
    this.host.requestUpdate();
  }

  geometry(r) {
    if (!this.mode || this.from == null || this.to == null || !r.start || r.end <= r.start) return null;
    const span = r.end - r.start;
    const left = ((this.from - r.start) / span) * 100;
    const width = ((this.to - this.from) / span) * 100;
    return { left: left, width: width, right: left + width };
  }

  /**
   * Range overlay for the scrubber track.
   * @param {object} r timeline range
   * @param {(kind: CutDrag, ev: PointerEvent) => void} onGrab
   */
  rangeHtml(r, onGrab) {
    const g = this.geometry(r);
    if (!g) return nothing;
    const grab = function (kind) {
      return function (ev) {
        if (kind !== "move") ev.preventDefault();
        ev.stopPropagation();
        onGrab(kind, ev);
      };
    };
    return html`
      <div class="camera-timeline-cut-mask start" style=${"width:" + Math.max(0, g.left) + "%"} aria-hidden="true"></div>
      <div
        class="camera-timeline-cut-mask end"
        style=${"left:" + g.right + "%;width:" + Math.max(0, 100 - g.right) + "%"}
        aria-hidden="true"
      ></div>
      <div
        class="camera-timeline-cut"
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

  /** Cut / Cancel / Download controls for the transport row. */
  barHtml(hasRecordings) {
    if (!this.mode) {
      return html`
        <wa-button
          size="small"
          appearance="outlined"
          ?disabled=${!hasRecordings}
          title=${t("cameras.cut.edit", "Select a clip range on the timeline")}
          @click=${() => this.enter()}
          >${t("cameras.cut.edit", "Cut")}</wa-button
        >
      `;
    }
    return html`
      <span class="sub mono">${fmtWall(this.from)} – ${fmtWall(this.to)}</span>
      <wa-button size="small" appearance="outlined" @click=${() => this.clear()}
        >${t("cameras.cut.cancel", "Cancel")}</wa-button
      >
      <wa-button
        size="small"
        variant="brand"
        ?disabled=${!this.canSave}
        ?loading=${this.busy}
        @click=${() => this.download()}
        >${t("cameras.cut.download", "Download clip")}</wa-button
      >
    `;
  }
}

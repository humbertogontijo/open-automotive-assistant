/**
 * Clip range on the DVR scrubber: a draggable [from, to] wall-clock window and
 * a camera picker. Each picked camera downloads as its own MP4 (the car burns
 * the timestamp in) through the media transport (cut_request or /api/dvr/cut).
 * A reactive controller owned by <oaa-camera-timeline>.
 */
import { html, nothing } from "lit";
import { live } from "lit/directives/live.js";
import { camera } from "../store.js";
import { t } from "../i18n.js";
import { errText } from "../api.js";
import { mediaTransport } from "./media-transport.js";
import { maxCutMs } from "./webrtc-session.js";
import { timelineRange, wallPlayheadMs, fmtWall, onPlayerEvent, orderRoles, roleLabel } from "./camera-player.js";

const MIN_CUT_MS = 1000;

/** Percent for a cut progress report, or null when the car can't tell. */
export function cutPercent(p) {
  if (!p || !(p.total > 0)) return null;
  return Math.max(0, Math.min(100, Math.round((p.done / p.total) * 100)));
}

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
    /** Cameras to export. */
    /** @type {Set<string>} */
    this.roles = new Set();
    /** Status line while exporting, e.g. "Front: preparing 40%". */
    this.progress = "";
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
    this.progress = "";
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
    const win = Math.min(maxCutMs(), Math.max(MIN_CUT_MS * 5, Math.min(60_000, Math.floor(span * 0.12))));
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
    const all = camera.roles || [];
    this.roles = new Set(camera.focus && all.indexOf(camera.focus) >= 0 ? [camera.focus] : all);
    this.host.requestUpdate();
  }

  toggleRole(role, on) {
    if (on) this.roles.add(role);
    else this.roles.delete(role);
    this.host.requestUpdate();
  }

  clamp(from, to) {
    const r = timelineRange();
    if (!r.start || !r.end || r.end <= r.start) return;
    const maxEnd = scrubMax(r);
    const maxLen = maxCutMs();
    let a = Math.max(r.start, Math.min(from, maxEnd - MIN_CUT_MS));
    let b = Math.min(maxEnd, Math.max(to, r.start + MIN_CUT_MS));
    if (b - a < MIN_CUT_MS) {
      if (this.drag === "from") a = b - MIN_CUT_MS;
      else b = a + MIN_CUT_MS;
      a = Math.max(r.start, a);
      b = Math.min(maxEnd, b);
    }
    if (b - a > maxLen) {
      if (this.drag === "from") a = b - maxLen;
      else b = a + maxLen;
    }
    this.from = Math.floor(a);
    this.to = Math.floor(b);
  }

  get canSave() {
    return this.from != null && this.to != null && this.from < this.to && !this.busy && this.roles.size > 0;
  }

  /** One MP4 per picked camera, exported one after another. */
  async download() {
    if (!this.canSave) return;
    const from = /** @type {number} */ (this.from);
    const to = /** @type {number} */ (this.to);
    const roles = orderRoles(Array.from(this.roles));
    this.busy = true;
    camera.previewError = "";
    this.host.requestUpdate();
    const failed = [];
    for (const role of roles) {
      const label = roleLabel(role);
      this.setProgress(label, null);
      try {
        const res = await mediaTransport().cut(role, from, to, (p) => this.setProgress(label, p));
        saveBlob(res.blob, res.name);
      } catch (e) {
        failed.push(label + ": " + errText(e));
      }
    }
    this.busy = false;
    if (failed.length) {
      camera.previewError = failed.join(" · ");
      this.progress = "";
      this.host.requestUpdate();
    } else {
      this.clear();
    }
  }

  /** @param {string} label @param {{phase: string, done: number, total: number}|null} p */
  setProgress(label, p) {
    const pct = cutPercent(p);
    const what =
      p && p.phase === "transfer"
        ? t("cameras.cut.downloading", "downloading")
        : t("cameras.cut.preparing", "preparing");
    this.progress = label + ": " + what + (pct != null ? " " + pct + "%" : "…");
    this.host.requestUpdate();
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
    const roles = camera.roles || [];
    const n = this.roles.size;
    return html`
      <span class="sub mono">${fmtWall(this.from)} – ${fmtWall(this.to)}</span>
      ${roles.length > 1
        ? html`<span class="camera-cut-roles">
            ${roles.map(
              (role) => html`<wa-checkbox
                size="small"
                .checked=${live(this.roles.has(role))}
                ?disabled=${this.busy}
                @change=${(ev) => this.toggleRole(role, ev.target.checked)}
                >${roleLabel(role)}</wa-checkbox
              >`,
            )}
          </span>`
        : nothing}
      ${this.progress ? html`<span class="sub camera-cut-progress" aria-live="polite">${this.progress}</span>` : nothing}
      <wa-button size="small" appearance="outlined" ?disabled=${this.busy} @click=${() => this.clear()}
        >${t("cameras.cut.cancel", "Cancel")}</wa-button
      >
      <wa-button
        size="small"
        variant="brand"
        ?disabled=${!this.canSave}
        ?loading=${this.busy}
        title=${t("cameras.cut.max", "Up to {n} min per clip").replace("{n}", String(Math.round(maxCutMs() / 60000)))}
        @click=${() => this.download()}
        >${n > 1
          ? t("cameras.cut.download_n", "Download {n} clips").replace("{n}", String(n))
          : t("cameras.cut.download", "Download clip")}</wa-button
      >
    `;
  }
}

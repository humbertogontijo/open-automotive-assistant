import { html, svg, nothing } from "lit";
import { OaaElement } from "../lit/oaa-element.js";
import { catalog, i18n, findEntity } from "../store.js";
import { OaaPage } from "../lit/oaa-page.js";
import { t, entityLabel, entityValueLabel, optionLabel, hasKey } from "../i18n.js";
import { api } from "../api.js";
import { fmt } from "../format.js";
import { segmentToggle, choiceSelect } from "../ui/cards/choice.js";
import { formatDisplayNumber, unitLabelFor } from "../units.js";

function historyEntityMeta(id) {
  const e = findEntity(id);
  const dc = (e && e.deviceClass) || inferHistoryDeviceClass(id);
  const input = (e && e.input) || (dc === "enum" ? "choice" : "sensor");
  return {
    id: id,
    deviceClass: dc,
    input: input,
    options: (e && e.options) || [],
    unitLabel: (e && e.unitLabel) || "",
    unitOfMeasurement: (e && e.unitOfMeasurement) || null,
    valueMapId: e && e.valueMapId,
    binary: e && e.binary,
  };
}

function inferHistoryDeviceClass(id) {
  const s = String(id || "");
  if (/soc|battery/i.test(s)) return "battery";
  if (/fuel/i.test(s)) return "fuel";
  if (/temp|hvac/i.test(s)) return "temperature";
  if (/speed/i.test(s)) return "speed";
  if (/range|odometer|distance/i.test(s)) return "distance";
  if (/charge_a|current/i.test(s)) return "current";
  if (/voltage/i.test(s)) return "voltage";
  if (/power|energy/i.test(s)) return "energy";
  if (/gear|drive_mode|plug|regen|mode/i.test(s)) return "enum";
  return null;
}

function historyViewsFor(meta) {
  const numeric = {
    battery: 1,
    fuel: 1,
    temperature: 1,
    speed: 1,
    distance: 1,
    current: 1,
    voltage: 1,
    power: 1,
    energy: 1,
    duration: 1,
    pressure: 1,
    humidity: 1,
  };
  const dc = meta && meta.deviceClass;
  const views = [];
  if (dc && numeric[dc]) views.push("graph");
  if (!dc || dc === "enum" || (meta && (meta.input === "bool" || meta.input === "choice"))) {
    views.push("timeline");
  } else if (views.indexOf("graph") >= 0) {
    views.push("timeline");
  }
  views.push("list");
  return views;
}

function historyEntityLabel(id) {
  const e = findEntity(id);
  if (e) return entityLabel(e);
  if (String(id).indexOf("sensor_") === 0) {
    return t("sensor." + String(id).slice("sensor_".length), id);
  }
  return t("control." + id, id);
}

function historyFormatValue(value, meta) {
  if (value == null || value === "") return "—";
  const raw = String(value);
  // Samples may be raw ints, or legacy i18n keys from telemetry labels.
  if (hasKey(raw)) return t(raw);

  if (meta) {
    const mapped = entityValueLabel(
      Object.assign({}, meta, { value: value }),
      value,
    );
    if (mapped && mapped !== raw) return mapped;
  }
  if (meta && meta.options && meta.options.length) {
    const hit = meta.options.find(function (o) {
      return String(o.value) === raw;
    });
    if (hit) return optionLabel(hit);
  }
  if (meta && meta.input === "bool") {
    const on = value === "1" || value === "true" || value === "on";
    return on ? t("common.on", "On") : t("common.off", "Off");
  }
  const n = parseFloat(value);
  if (!isNaN(n) && meta && meta.unitOfMeasurement) {
    const shown = formatDisplayNumber(meta.unitOfMeasurement, n, meta.input || "sensor");
    const unit = unitLabelFor({
      unitOfMeasurement: meta.unitOfMeasurement,
      unitLabel: meta.unitLabel,
    });
    return unit ? shown + " " + unit : shown;
  }
  if (meta && meta.unitLabel) return fmt(value) + " " + meta.unitLabel;
  return fmt(value);
}

const FMT_FULL = new Intl.DateTimeFormat(undefined, {
  year: "numeric",
  month: "numeric",
  day: "numeric",
  hour: "numeric",
  minute: "numeric",
  second: "numeric",
});
const FMT_AXIS_MIN = new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
const FMT_AXIS_HOUR = new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric", hour: "2-digit" });
const FMT_AXIS_DAY = new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" });

function fmtTs(ts) {
  const n = Number(ts);
  return n ? FMT_FULL.format(n) : "—";
}

function fmtAxisTs(ts, spanMs) {
  const n = Number(ts);
  if (!n) return "";
  const f = spanMs <= 36 * 3600 * 1000 ? FMT_AXIS_MIN : spanMs <= 8 * 24 * 3600 * 1000 ? FMT_AXIS_HOUR : FMT_AXIS_DAY;
  return f.format(n);
}

function fmtYTick(n) {
  if (!isFinite(n)) return "";
  const a = Math.abs(n);
  if (a >= 1000) return n.toFixed(0);
  if (a >= 100) return String(Math.round(n * 10) / 10);
  if (a >= 10) return String(Math.round(n * 100) / 100);
  return String(Math.round(n * 1000) / 1000);
}

function timeRange(points) {
  if (!points || !points.length) return { t0: 0, t1: 1, span: 1 };
  let t0 = Number(points[0].ts) || 0;
  let t1 = t0;
  for (let i = 1; i < points.length; i++) {
    const ts = Number(points[i].ts) || 0;
    if (ts < t0) t0 = ts;
    if (ts > t1) t1 = ts;
  }
  const span = Math.max(1, t1 - t0);
  return { t0: t0, t1: t1, span: span };
}

function axisTicks(t0, t1, count) {
  const span = Math.max(1, t1 - t0);
  const n = Math.max(2, count || 4);
  const out = [];
  for (let i = 0; i < n; i++) {
    out.push(t0 + (span * i) / (n - 1));
  }
  return out;
}

/**
 * Hover tooltip anchored at the pointer, flipped towards the inside near the edges.
 * @typedef {{ x: number, y: number, flipX: boolean, flipY: boolean, lines: string[] }} HistTip
 */

/** @returns {HistTip} */
function tipAt(wrap, ev, lines) {
  const rect = wrap.getBoundingClientRect();
  const x = ev.clientX - rect.left;
  const y = ev.clientY - rect.top;
  return { x: x, y: y, flipX: x > rect.width - 180, flipY: y > rect.height - 64, lines: lines };
}

/** @param {HistTip|null} tip */
function tipHtml(tip) {
  if (!tip) return nothing;
  const tx = tip.flipX ? "calc(-100% - 12px)" : "12px";
  const ty = tip.flipY ? "calc(-100% - 4px)" : "-8px";
  return html`<div
    class="hist-tip"
    style=${"left:" + tip.x + "px;top:" + tip.y + "px;transform:translate(" + tx + "," + ty + ")"}
  >
    <div class="hist-tip-val">${tip.lines[0]}</div>
    <div class="hist-tip-time">${tip.lines[1]}</div>
  </div>`;
}

const GRAPH = { w: 640, h: 220, padL: 44, padR: 14, padT: 14, padB: 34 };

/** Line graph of numeric samples with a nearest-sample crosshair. */
class OaaHistoryGraph extends OaaElement {
  static properties = {
    points: { attribute: false },
    meta: { attribute: false },
    hover: { state: true },
  };

  constructor() {
    super();
    /** @type {any[]} */
    this.points = [];
    /** @type {any} */
    this.meta = null;
    /** @type {(HistTip & { px: number, py: number })|null} */
    this.hover = null;
    /** @type {any} */
    this.geo = null;
  }

  willUpdate(changed) {
    if (changed.has("points")) {
      this.geo = graphGeometry(this.points || []);
      this.hover = null;
    }
  }

  onMove(ev) {
    const g = this.geo;
    const wrap = ev.currentTarget;
    const svgEl = wrap.querySelector("svg");
    if (!g || !svgEl) return;
    const rect = svgEl.getBoundingClientRect();
    const mx = ((ev.clientX - rect.left) / rect.width) * GRAPH.w;
    const plotX = Math.max(GRAPH.padL, Math.min(GRAPH.padL + g.plotW, mx));
    const ts = g.range.t0 + ((plotX - GRAPH.padL) / g.plotW) * g.range.span;
    let best = g.nums[0];
    let bestD = Math.abs(best.ts - ts);
    for (let i = 1; i < g.nums.length; i++) {
      const d = Math.abs(g.nums[i].ts - ts);
      if (d < bestD) {
        best = g.nums[i];
        bestD = d;
      }
    }
    const tip = tipAt(wrap, ev, [historyFormatValue(best.value, this.meta), fmtTs(best.ts)]);
    this.hover = Object.assign(tip, { px: g.xAt(best.ts), py: g.yAt(best.n) });
  }

  render() {
    const points = this.points || [];
    const g = this.geo;
    if (!points.length) {
      return html`<p class="persist-note">${t("history.no_points", "No samples in this range")}</p>`;
    }
    if (!g) {
      return html`
        <p class="persist-note">${t("history.graph.need_numeric", "Need at least two numeric samples for a graph")}</p>
        ${historyTable(points, this.meta)}
      `;
    }
    const { w, h, padL, padT } = GRAPH;
    const hv = this.hover;
    return html`
      <div class="hist-chart" @mousemove=${(ev) => this.onMove(ev)} @mouseleave=${() => (this.hover = null)}>
        <svg class="hist-svg" viewBox="0 0 ${w} ${h}" width="100%" height="220" aria-hidden="true">
          ${svg`
            <line class="hist-axis" x1=${padL} y1=${padT} x2=${padL} y2=${padT + g.plotH}></line>
            <line class="hist-axis" x1=${padL} y1=${padT + g.plotH} x2=${padL + g.plotW} y2=${padT + g.plotH}></line>
            ${g.yTicks.map(function (v) {
              const y = g.yAt(v);
              return svg`
                <line class="hist-grid" x1=${padL} y1=${y} x2=${padL + g.plotW} y2=${y}></line>
                <text class="hist-tick hist-tick-y" x=${padL - 6} y=${y + 3.5} text-anchor="end">${fmtYTick(v)}</text>
              `;
            })}
            ${g.xTicks.map(function (ts, i) {
              const x = g.xAt(ts);
              const anchor = i === 0 ? "start" : i === g.xTicks.length - 1 ? "end" : "middle";
              return svg`
                <line class="hist-tick-mark" x1=${x} y1=${padT + g.plotH} x2=${x} y2=${padT + g.plotH + 5}></line>
                <text class="hist-tick hist-tick-x" x=${x} y=${padT + g.plotH + 20} text-anchor=${anchor}
                  >${fmtAxisTs(ts, g.range.span)}</text>
              `;
            })}
            <polyline class="hist-line" fill="none" stroke-width="2.5" points=${g.coords}></polyline>
            ${hv
              ? svg`
                  <line class="hist-crosshair" x1=${hv.px} y1=${padT} x2=${hv.px} y2=${padT + g.plotH}></line>
                  <circle class="hist-hover-dot" r="4.5" cx=${hv.px} cy=${hv.py}></circle>
                `
              : nothing}
          `}
        </svg>
        ${tipHtml(hv)}
      </div>
    `;
  }
}
customElements.define("oaa-history-graph", OaaHistoryGraph);

/** Scales and polyline for [points]; null when there are fewer than two numeric samples. */
function graphGeometry(points) {
  const nums = points
    .map(function (p) {
      const n = parseFloat(p.value);
      return isNaN(n) ? null : { n: n, ts: Number(p.ts) || 0, value: p.value };
    })
    .filter(Boolean);
  if (nums.length < 2) return null;
  let min = nums[0].n;
  let max = nums[0].n;
  nums.forEach(function (x) {
    if (x.n < min) min = x.n;
    if (x.n > max) max = x.n;
  });
  if (min === max) {
    min -= 1;
    max += 1;
  }
  const span = max - min;
  const range = timeRange(nums);
  const plotW = GRAPH.w - GRAPH.padL - GRAPH.padR;
  const plotH = GRAPH.h - GRAPH.padT - GRAPH.padB;
  const xAt = (ts) => GRAPH.padL + ((ts - range.t0) / range.span) * plotW;
  const yAt = (n) => GRAPH.padT + (1 - (n - min) / span) * plotH;
  return {
    nums: nums,
    range: range,
    plotW: plotW,
    plotH: plotH,
    xAt: xAt,
    yAt: yAt,
    yTicks: [max, (min + max) / 2, min],
    xTicks: axisTicks(range.t0, range.t1, 4),
    coords: nums
      .map(function (x) {
        return xAt(x.ts).toFixed(1) + "," + yAt(x.n).toFixed(1);
      })
      .join(" "),
  };
}

function timelineTone(value, meta, index) {
  if (meta && meta.input === "bool") {
    const on = value === "1" || value === "true" || value === "on";
    return on ? "on" : "off";
  }
  return "tone-" + (index % 6);
}

/** State bands for enum / bool samples, one band per value run. */
class OaaHistoryTimeline extends OaaElement {
  static properties = {
    points: { attribute: false },
    meta: { attribute: false },
    hover: { state: true },
  };

  constructor() {
    super();
    /** @type {any[]} */
    this.points = [];
    /** @type {any} */
    this.meta = null;
    /** @type {HistTip|null} */
    this.hover = null;
    /** @type {ReturnType<typeof timelineModel> | null} */
    this.model = null;
  }

  willUpdate(changed) {
    if (changed.has("points") || changed.has("meta")) {
      this.model = timelineModel(this.points || [], this.meta);
      this.hover = null;
    }
  }

  onMove(ev, segments, t0, span) {
    const wrap = ev.currentTarget;
    const track = wrap.querySelector(".hist-timeline-track");
    if (!track || !segments.length) return;
    const rect = track.getBoundingClientRect();
    if (rect.width <= 0) return;
    const pct = Math.max(0, Math.min(1, (ev.clientX - rect.left) / rect.width));
    const ts = t0 + pct * span;
    let best = segments[0];
    for (let i = 0; i < segments.length; i++) {
      const s = segments[i];
      const sEnd = s.ts + (s.width / 100) * span;
      if (ts >= s.ts && ts <= sEnd) {
        best = s;
        break;
      }
      if (Math.abs(s.ts - ts) < Math.abs(best.ts - ts)) best = s;
    }
    this.hover = tipAt(wrap, ev, [best.label, fmtTs(best.ts)]);
  }

  render() {
    const m = this.model;
    if (!m) {
      return html`<p class="persist-note">${t("history.no_points", "No samples in this range")}</p>`;
    }
    const { sorted, range, span, xTicks, segments } = m;

    return html`
      <div
        class="hist-timeline"
        @mousemove=${(ev) => this.onMove(ev, segments, range.t0, span)}
        @mouseleave=${() => (this.hover = null)}
      >
        <div class="hist-timeline-track">
          ${segments.map(function (s) {
            return html`<div
              class="hist-timeline-seg hist-tone-${s.tone}"
              style="left:${s.left.toFixed(2)}%;width:${s.width.toFixed(2)}%"
              title=${s.label + " · " + fmtTs(s.ts)}
            ></div>`;
          })}
          ${segments.map(function (s) {
            return html`<div class="hist-timeline-mark" style="left:${s.left.toFixed(2)}%"></div>`;
          })}
        </div>
        <div class="hist-timeline-axis">
          ${xTicks.map(function (ts) {
            const left = ((ts - range.t0) / span) * 100;
            return html`<span class="hist-timeline-tick" style="left:${left.toFixed(2)}%">
              <span class="hist-timeline-tick-mark"></span>
              <span class="hist-timeline-tick-label">${fmtAxisTs(ts, span)}</span>
            </span>`;
          })}
        </div>
        ${tipHtml(this.hover)}
      </div>
      <p class="sub hist-chart-meta">${sorted.length} ${t("history.samples", "samples")}</p>
    `;
  }
}
customElements.define("oaa-history-timeline", OaaHistoryTimeline);

/** Sorted samples (last 300) as bands; null when there are none. */
function timelineModel(points, meta) {
  if (!points.length) return null;
  const sorted = points
    .slice()
    .sort(function (a, b) {
      return (Number(a.ts) || 0) - (Number(b.ts) || 0);
    })
    .slice(-300);
  const range = timeRange(sorted);
  const endTs = Math.max(range.t1, Date.now());
  const span = Math.max(1, endTs - range.t0);
  const segments = sorted.map(function (p, i) {
    const ts = Number(p.ts) || 0;
    const nextTs = i + 1 < sorted.length ? Number(sorted[i + 1].ts) || endTs : endTs;
    return {
      left: ((ts - range.t0) / span) * 100,
      width: Math.max(0.35, ((nextTs - ts) / span) * 100),
      ts: ts,
      tone: timelineTone(p.value, meta, i),
      label: historyFormatValue(p.value, meta),
    };
  });
  return { sorted, range, span, xTicks: axisTicks(range.t0, endTs, 4), segments };
}

function historyTable(points, meta) {
  if (!points || !points.length) {
    return html`<p class="persist-note">${t("history.no_points", "No samples in this range")}</p>`;
  }
  const rows = points.slice().reverse().slice(0, 400);
  return html`<div class="table-scroll" style="max-height:360px;overflow:auto">
    <table class="table">
      <thead>
        <tr>
          <th>${t("history.col.time", "Time")}</th>
          <th>${t("history.col.value", "Value")}</th>
        </tr>
      </thead>
      <tbody>
        ${rows.map(function (p) {
          return html`<tr>
            <td class="mono">${fmtTs(p.ts)}</td>
            <td class="mono">${historyFormatValue(p.value, meta)}</td>
          </tr>`;
        })}
      </tbody>
    </table>
  </div>`;
}

/** @param {string} id @param {number} hours */
async function fetchPoints(id, hours) {
  const end = Date.now();
  const start = end - hours * 60 * 60 * 1000;
  try {
    const res = await api("/api/history/" + encodeURIComponent(id) + "?start=" + start + "&end=" + end + "&limit=2000");
    return (res && res.points) || [];
  } catch (e) {
    return [];
  }
}

function viewLabel(v) {
  if (v === "graph") return t("history.view.graph", "Graph");
  if (v === "timeline") return t("history.view.timeline", "Timeline");
  return t("history.view.list", "List");
}

class OaaPageHistory extends OaaPage {
  static properties = {
    selected: { state: true },
    hours: { state: true },
    view: { state: true },
    points: { state: true },
  };

  constructor() {
    super();
    this.selected = "";
    this.hours = 24;
    /** @type {string | null} */
    this.view = null;
    /** @type {any[]} */
    this.points = [];
  }

  async load() {
    const entities = catalog.historyEntities;
    if (!this.selected || entities.indexOf(this.selected) < 0) this.selected = entities[0] || "";
    const id = this.selected;
    const hours = this.hours;
    const points = id ? await fetchPoints(id, hours) : [];
    if (id === this.selected && hours === this.hours) this.points = points;
  }

  /** New `meta` / option objects only when their inputs change, so the charts don't re-render. */
  memoMeta(id) {
    const e = findEntity(id);
    const m = this._meta;
    if (m && m.id === id && m.e === e) return m.meta;
    this._meta = { id, e, meta: historyEntityMeta(id) };
    return this._meta.meta;
  }

  memoOptions(entities) {
    const o = this._options;
    if (o && o.entities === entities && o.catalog === catalog.entities && o.strings === i18n.strings) return o.options;
    const options = entities.map((id) => ({ value: id, label: historyEntityLabel(id) }));
    this._options = { entities, catalog: catalog.entities, strings: i18n.strings, options };
    return options;
  }

  render() {
    const entities = catalog.historyEntities;
    const selected = this.selected;
    const meta = this.memoMeta(selected);
    const views = historyViewsFor(meta);
    const view = this.view && views.indexOf(this.view) >= 0 ? this.view : views[0];
    const points = this.points;
    const rangeOpts = [
      { value: "6", label: t("history.range.6h", "6h") },
      { value: "24", label: t("history.range.24h", "24h") },
      { value: "72", label: t("history.range.72h", "3d") },
      { value: "168", label: t("history.range.7d", "7d") },
    ];

    const body = !entities.length
      ? html`<p class="persist-note">${t("history.empty", "No history yet")}</p>`
      : html`
          <div class="hist-toolbar">
            <div class="hist-field hist-entity">
              <span class="hist-field-label">${t("history.entity", "Entity")}</span>
              ${choiceSelect({
                options: this.memoOptions(entities),
                current: selected,
                onSelect: (next) => {
                  this.selected = next;
                  this.points = [];
                  this.load();
                },
              })}
            </div>
            <div class="hist-field">
              <span class="hist-field-label">${t("history.range", "Range")}</span>
              <div class="hist-seg">
                ${segmentToggle({
                  options: rangeOpts,
                  current: String(this.hours),
                  onSelect: (next) => {
                    this.hours = parseInt(next, 10) || 24;
                    this.load();
                  },
                })}
              </div>
            </div>
            <div class="hist-field">
              <span class="hist-field-label">${t("history.view", "View")}</span>
              <div class="hist-seg">
                ${segmentToggle({
                  options: views.map((v) => ({ value: v, label: viewLabel(v) })),
                  current: view,
                  onSelect: (next) => (this.view = next),
                })}
              </div>
            </div>
          </div>
          ${view === "graph"
            ? html`<oaa-history-graph .points=${points} .meta=${meta}></oaa-history-graph>`
            : view === "timeline"
              ? html`<oaa-history-timeline .points=${points} .meta=${meta}></oaa-history-timeline>`
              : historyTable(points, meta)}
        `;

    return html`
      <h1>${t("section.history.title", "History")}</h1>
      <p class="sub">${t("section.history.sub", "Samples only when a value changes")}</p>
      <wa-card class="hist-card">${body}</wa-card>
    `;
  }
}
customElements.define("oaa-page-history", OaaPageHistory);

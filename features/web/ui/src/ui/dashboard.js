
import { html, svg, nothing } from "lit";
import { t, entityLabel, entityValueLabel } from "../i18n.js";
import { fmt } from "../format.js";
import { formatDisplayNumber, unitLabelFor } from "../units.js";
import { icon } from "../icons.js";

/**
 * Shared helpers for composed section dashboards (HA Energy-style layouts
 * on top of the same entity cards — not a separate dashboard product).
 */

export function entityById(items, id) {
  if (!items) return null;
  for (let i = 0; i < items.length; i++) {
    if (items[i].id === id) return items[i];
  }
  return null;
}

export function pickEntities(items, ids) {
  const out = [];
  for (let i = 0; i < ids.length; i++) {
    const e = entityById(items, ids[i]);
    if (e && (e.status === "ok" || e.status === "cached" || e.available)) out.push(e);
  }
  return out;
}

export function displayValue(e) {
  if (!e) return "—";
  if (e.value == null || e.value === "") return "—";
  const mapped = entityValueLabel(e);
  if (e.valueMapId || e.binary || (e.options && e.options.length)) {
    if (mapped && mapped !== String(e.value)) return mapped;
  }
  const n = parseFloat(e.value);
  if (!isNaN(n) && e.unitOfMeasurement) {
    const shown = formatDisplayNumber(e.unitOfMeasurement, n, e.input || "sensor");
    const unit = unitLabelFor(e);
    return unit ? shown + " " + unit : shown;
  }
  if (mapped) return mapped;
  return fmt(e.value);
}

/** Hero summary strip: large gauges for key sensors. */
export function dashSummary(entities, opts) {
  opts = opts || {};
  if (!entities || !entities.length) return nothing;
  return html`<div class="dash-summary ${opts.className || ""}">
    ${entities.map(function (e) {
      return html`<div class="dash-gauge" data-id=${e.id}>
        <div class="dash-gauge-icon">${icon(e.icon || "sensor")}</div>
        <div class="dash-gauge-body">
          <div class="dash-gauge-label">${e.friendlyName || entityLabel(e)}</div>
          <div class="dash-gauge-value">${displayValue(e)}</div>
        </div>
      </div>`;
    })}
  </div>`;
}

/** Polyline points per history array and size. @type {WeakMap<object[], { key: string, coords: string }>} */
const sparkCache = new WeakMap();

/** @param {any[]} points @param {number} w @param {number} h @returns {string} "" when under two numeric points */
function sparkCoords(points, w, h) {
  const key = w + "x" + h;
  const hit = sparkCache.get(points);
  if (hit && hit.key === key) return hit.coords;
  const nums = [];
  for (const p of points) {
    const n = parseFloat(p.value);
    if (!isNaN(n)) nums.push(n);
  }
  let coords = "";
  if (nums.length >= 2) {
    let min = Math.min(...nums);
    let max = Math.max(...nums);
    if (min === max) {
      min -= 1;
      max += 1;
    }
    const pad = 4;
    const span = max - min;
    coords = nums
      .map((n, i) => {
        const x = pad + (i / (nums.length - 1)) * (w - pad * 2);
        const y = pad + (1 - (n - min) / span) * (h - pad * 2);
        return x.toFixed(1) + "," + y.toFixed(1);
      })
      .join(" ");
  }
  sparkCache.set(points, { key, coords });
  return coords;
}

/** Compact sparkline from history points `[{ts, value}, ...]`. */
export function dashSparkline(points, opts) {
  opts = opts || {};
  const w = opts.width || 280;
  const h = opts.height || 56;
  const coords = points ? sparkCoords(points, w, h) : "";
  if (!coords) {
    return html`<p class="sub dash-spark-empty">
      ${t("dash.spark.empty", "Not enough history yet")}
    </p>`;
  }
  const title = opts.title || "";
  return html`<div class="dash-spark">
    ${title ? html`<div class="dash-spark-title">${title}</div>` : nothing}
    ${svg`<svg class="dash-spark-svg" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none" aria-hidden="true">
      <polyline fill="none" stroke="currentColor" stroke-width="2" points=${coords} />
    </svg>`}
  </div>`;
}

/**
 * Exclude summary hero ids from the remaining card grid so entities are not
 * duplicated on the same page.
 */
export function withoutIds(items, ids) {
  const skip = {};
  for (let i = 0; i < (ids || []).length; i++) skip[ids[i]] = true;
  return (items || []).filter(function (e) {
    return !skip[e.id];
  });
}

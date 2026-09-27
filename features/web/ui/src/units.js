/**
 * Unit conversion: platform/catalog values stay in a canonical unit; the UI
 * converts to the user's preferred unit per dimension for display (and back on write).
 */
import { prefs } from "./store.js";
import { t } from "./i18n.js";

export var DIMENSION = {
  temperature: "temperature",
  distance: "distance",
  speed: "speed",
  fuel_economy: "fuel_economy",
  energy_economy: "energy_economy",
};

var FALLBACK = {
  percent: "%",
  celsius: "°C",
  fahrenheit: "°F",
  km: "km",
  mi: "mi",
  m: "m",
  km_h: "km/h",
  mph: "mph",
  A: "A",
  V: "V",
  W: "W",
  kWh: "kWh",
  kwh_100km: "kWh/100km",
  km_kwh: "km/kWh",
  wh_km: "Wh/km",
  l_100km: "L/100km",
  km_l: "km/L",
  mpg: "mpg",
  mpg_uk: "mpg (UK)",
  min: "min",
};

/** Unit id → dimension. */
var UNIT_DIM = {
  celsius: DIMENSION.temperature,
  fahrenheit: DIMENSION.temperature,
  km: DIMENSION.distance,
  mi: DIMENSION.distance,
  m: DIMENSION.distance,
  km_h: DIMENSION.speed,
  mph: DIMENSION.speed,
  l_100km: DIMENSION.fuel_economy,
  km_l: DIMENSION.fuel_economy,
  mpg: DIMENSION.fuel_economy,
  mpg_uk: DIMENSION.fuel_economy,
  kwh_100km: DIMENSION.energy_economy,
  km_kwh: DIMENSION.energy_economy,
  wh_km: DIMENSION.energy_economy,
};

/** Choices offered in Settings per dimension (canonical platform units first). */
export var UNIT_CHOICES = {
  temperature: ["celsius", "fahrenheit"],
  distance: ["km", "mi"],
  speed: ["km_h", "mph"],
  fuel_economy: ["l_100km", "km_l", "mpg", "mpg_uk"],
  energy_economy: ["kwh_100km", "km_kwh", "wh_km"],
};

/** Canonical platform units. */
var DEFAULT_UNIT_PREFS = {
  temperature: "celsius",
  distance: "km",
  speed: "km_h",
  fuel_economy: "l_100km",
  energy_economy: "kwh_100km",
};

export function defaultUnitPrefs() {
  return Object.assign({}, DEFAULT_UNIT_PREFS);
}

/** Normalized per-dimension prefs object. */
export function unitPrefs() {
  var raw = prefs.units || {};
  var out = defaultUnitPrefs();
  Object.keys(UNIT_CHOICES).forEach(function (dim) {
    var v = raw[dim];
    if (v && UNIT_CHOICES[dim].indexOf(v) >= 0) out[dim] = v;
  });
  return out;
}

export function preferredUnitId(canonicalId) {
  if (!canonicalId) return null;
  var dim = UNIT_DIM[canonicalId];
  if (!dim) return canonicalId;
  return unitPrefs()[dim] || canonicalId;
}

export function displayUnitId(canonicalId) {
  return preferredUnitId(canonicalId) || canonicalId;
}

export function unitLabelFor(c) {
  if (!c) return "";
  var canonical = c.unitOfMeasurement || null;
  var id = displayUnitId(canonical) || canonical;
  if (!id) return c.unitLabel || "";
  return t("unit." + id, FALLBACK[id] || c.unitLabel || id);
}

/**
 * Convert [value] from unit [fromId] to [toId]. Returns null if incompatible.
 * Economy units with zero/near-zero consumption map to Infinity → handled by caller.
 */
export function convert(fromId, toId, value) {
  if (fromId == null || toId == null) return value;
  if (fromId === toId) return value;
  if (value == null || isNaN(value)) return value;
  var dim = UNIT_DIM[fromId];
  if (!dim || dim !== UNIT_DIM[toId]) return null;

  if (dim === DIMENSION.temperature) {
    var c = fromId === "celsius" ? value : ((value - 32) * 5) / 9;
    return toId === "celsius" ? c : (c * 9) / 5 + 32;
  }
  if (dim === DIMENSION.distance) {
    // m stays m; km ↔ mi
    if (fromId === "m" || toId === "m") return fromId === toId ? value : null;
    var km = fromId === "km" ? value : value / 0.621371192;
    return toId === "km" ? km : km * 0.621371192;
  }
  if (dim === DIMENSION.speed) {
    var kmh = fromId === "km_h" ? value : value / 0.621371192;
    return toId === "km_h" ? kmh : kmh * 0.621371192;
  }
  if (dim === DIMENSION.fuel_economy) {
    var l100 = toL100km(fromId, value);
    if (l100 == null || !isFinite(l100)) return l100;
    return fromL100km(toId, l100);
  }
  if (dim === DIMENSION.energy_economy) {
    var kwh100 = toKwh100km(fromId, value);
    if (kwh100 == null || !isFinite(kwh100)) return kwh100;
    return fromKwh100km(toId, kwh100);
  }
  return null;
}

function toL100km(id, v) {
  if (id === "l_100km") return v;
  if (v === 0) return Infinity;
  if (id === "km_l") return 100 / v;
  if (id === "mpg") return 235.214583 / v; // US gallon
  if (id === "mpg_uk") return 282.480936 / v; // Imperial gallon
  return null;
}

function fromL100km(id, l100) {
  if (id === "l_100km") return l100;
  if (!isFinite(l100) || l100 === 0) return Infinity;
  if (id === "km_l") return 100 / l100;
  if (id === "mpg") return 235.214583 / l100;
  if (id === "mpg_uk") return 282.480936 / l100;
  return null;
}

function toKwh100km(id, v) {
  if (id === "kwh_100km") return v;
  if (id === "wh_km") return v / 10; // Wh/km → kWh/100km
  if (v === 0) return Infinity;
  if (id === "km_kwh") return 100 / v;
  return null;
}

function fromKwh100km(id, kwh100) {
  if (id === "kwh_100km") return kwh100;
  if (id === "wh_km") return kwh100 * 10;
  if (!isFinite(kwh100) || kwh100 === 0) return Infinity;
  if (id === "km_kwh") return 100 / kwh100;
  return null;
}

export function toDisplayNumber(canonicalUnitId, n) {
  if (n == null || isNaN(n)) return n;
  var to = displayUnitId(canonicalUnitId);
  if (!to || to === canonicalUnitId) return n;
  var out = convert(canonicalUnitId, to, n);
  return out == null ? n : out;
}

function decimalsFor(displayId, input) {
  if (
    displayId === "celsius" ||
    displayId === "fahrenheit" ||
    displayId === "km_l" ||
    displayId === "mpg" ||
    displayId === "mpg_uk" ||
    displayId === "km_kwh" ||
    displayId === "kwh_100km" ||
    displayId === "wh_km" ||
    input === "float"
  ) {
    return 1;
  }
  return 0;
}

export function formatDisplayNumber(canonicalUnitId, n, input) {
  var v = toDisplayNumber(canonicalUnitId, n);
  if (v == null || isNaN(v)) return "—";
  if (!isFinite(v)) return "—";
  var disp = displayUnitId(canonicalUnitId) || canonicalUnitId;
  var d = decimalsFor(disp, input);
  var factor = Math.pow(10, d);
  return String(Math.round(v * factor) / factor);
}

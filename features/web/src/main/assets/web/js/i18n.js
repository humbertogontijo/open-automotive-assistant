import { api } from "./api.js";
import { state, notify } from "./store.js";

export function t(key, fallback) {
  const strings = (state.i18n && state.i18n.strings) || {};
  if (key && strings[key] != null) return strings[key];
  return fallback != null ? fallback : key || "";
}

/** True when [key] exists in the loaded dictionary (even if empty). */
export function hasKey(key) {
  if (!key) return false;
  const strings = (state.i18n && state.i18n.strings) || {};
  return Object.prototype.hasOwnProperty.call(strings, key);
}

export function valueLabel(mapId, raw) {
  if (raw == null || mapId == null) return "";
  const maps = (state.i18n && state.i18n.valueMaps) || {};
  const key = maps[mapId] && maps[mapId][String(raw)];
  if (key) return t(key, String(raw));
  return String(raw);
}

/** Product entity title: prefers labelKey, falls back to user label / id. */
export function entityLabel(c) {
  if (!c) return "";
  if (c.labelKey) return t(c.labelKey, c.label || c.id || "");
  if (c.label) return c.label;
  return c.id || "";
}

/** Hint/description when hintKey is present in the dictionary. */
export function entityHint(c) {
  if (!c) return "";
  const key = c.hintKey || (c.labelKey ? c.labelKey + ".hint" : null);
  if (key && hasKey(key)) {
    const s = t(key);
    return s && s !== key ? s : "";
  }
  return c.hint || c.description || "";
}

export function optionLabel(o) {
  if (!o) return "";
  if (o.labelKey) return t(o.labelKey, o.label != null ? String(o.label) : String(o.value));
  if (o.label != null) return String(o.label);
  return o.value != null ? String(o.value) : "";
}

function isOnValue(v) {
  const s = String(v).toLowerCase();
  return s === "1" || s === "true" || s === "on";
}

function isOffValue(v) {
  const s = String(v).toLowerCase();
  return s === "0" || s === "false" || s === "off";
}

/**
 * Localized display for an entity's current value.
 * Uses options[], valueMaps, binary/open-closed conventions, then raw.
 */
export function entityValueLabel(c, raw) {
  if (!c) return raw == null ? "" : String(raw);
  const value = raw !== undefined ? raw : c.value != null ? c.value : c.state;
  if (value == null || value === "") return "";

  // Telemetry/history sometimes store i18n keys (opt.drive_mode.2) instead of raw ints.
  const asKey = String(value);
  if (hasKey(asKey)) return t(asKey);

  const opts = c.options;
  if (Array.isArray(opts) && opts.length) {
    const hit = opts.find(function (o) {
      return o != null && String(o.value) === String(value);
    });
    if (hit) return optionLabel(hit);
  }

  const mapId = c.valueMapId || c.id;
  if (mapId) {
    const maps = (state.i18n && state.i18n.valueMaps) || {};
    if (maps[mapId] && maps[mapId][String(value)] != null) {
      return valueLabel(mapId, value);
    }
  }

  if (c.binary || c.input === "bool") {
    if (isOnValue(value)) return t("common.on", "On");
    if (isOffValue(value)) return t("common.off", "Off");
  }

  const s = String(value).toLowerCase();
  if (s === "open") return t("common.open", "Open");
  if (s === "closed" || s === "close") return t("common.closed", "Closed");

  return String(value);
}

/** Persist pin display: map persistValue through the entity's valueMap / options. */
export function persistLabel(c) {
  if (!c || c.persistValue == null || c.persistValue === "") return "";
  return entityValueLabel(c, c.persistValue);
}

export async function loadI18n() {
  state.i18n = await api("/api/i18n");
  applyChrome();
  return state.i18n;
}

export async function setLocale(locale) {
  const body = new URLSearchParams({ locale: locale });
  const res = await api("/api/locale", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: body.toString(),
  });
  state.i18n = {
    locale: res.locale,
    locales: (state.i18n && state.i18n.locales) || ["pt-BR", "en"],
    strings: res.strings || {},
    valueMaps: res.valueMaps || {},
  };
  try {
    localStorage.setItem("oaa_locale", res.locale);
  } catch (e) {}
  document.documentElement.lang = res.locale || "pt-BR";
  applyChrome();
  notify();
  return state.i18n;
}

export function applyChrome() {
  document.querySelectorAll(".nav-item[data-page]").forEach(function (el) {
    const sec = el.getAttribute("data-page");
    const label = el.querySelector(".label");
    if (label) label.textContent = t("nav." + sec, label.textContent);
  });
}

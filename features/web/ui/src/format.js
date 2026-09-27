/** Display formatting shared by pages and cards. */
import { t } from "./i18n.js";

/** Placeholder dash for missing values. */
export function fmt(v) {
  return v == null || v === "" ? "—" : v;
}

/** @param {number | string} n bytes */
export function fmtBytes(n) {
  const v = Number(n) || 0;
  if (v < 1024) return v + " B";
  if (v < 1024 * 1024) return (v / 1024).toFixed(1) + " KB";
  if (v < 1024 * 1024 * 1024) return (v / (1024 * 1024)).toFixed(1) + " MB";
  return (v / (1024 * 1024 * 1024)).toFixed(2) + " GB";
}

/** Truthy switch values as the car and HA report them ("1", "true", "on", true, 1). */
export function isOn(v) {
  if (v === true || v === 1) return true;
  const s = String(v).toLowerCase();
  return s === "1" || s === "true" || s === "on";
}

/**
 * Label for a storage volume or DVR storage target.
 * @param {{ labelKey?: string, label?: string, id?: string, available?: boolean, writable?: boolean } | null | undefined} s
 * @param {{ markUnavailable?: boolean }} [opts] append "Not available" to removable media that cannot be written
 */
export function storageLabel(s, opts) {
  if (!s) return "";
  if (s.labelKey === "cameras.storage.usb.none") {
    return t("cameras.storage.usb.none", "USB / Flash (none)");
  }
  if (s.labelKey === "cameras.storage.sd" || s.labelKey === "cameras.storage.usb") {
    const base = t(
      s.labelKey,
      s.labelKey === "cameras.storage.usb" ? "USB / Flash ({label})" : "SD ({label})",
    ).replace("{label}", s.label || "");
    if (opts?.markUnavailable && (s.available === false || s.writable === false)) {
      return base + " — " + t("cameras.storage.unavailable", "Not available");
    }
    return base;
  }
  return t(s.labelKey || "cameras.storage.app", s.label || s.id || "");
}

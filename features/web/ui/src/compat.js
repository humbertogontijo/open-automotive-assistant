/**
 * Hub ↔ car compatibility. A hub serves its own copy of this UI to every car it opens, so the
 * car's app may be newer (pages and cards this copy does not know) or older (pages it lacks).
 */

/** Pages every car served before cars reported `pages` in `/api/status`. Never extend it: new pages stay hidden on those cars. */
const LEGACY_PAGES = Object.freeze([
  "home", "cars", "history", "controls", "drive", "energy", "lights", "adas",
  "assistant", "display", "sound", "connect", "vehicle", "cameras", "store",
  "shortcuts", "plugins", "settings", "lab", "about", "login", "setup",
]);

/** @param {unknown} v @returns {number[] | null} */
function versionParts(v) {
  if (typeof v !== "string" || !v) return null;
  const parts = v.split(/[-+]/)[0].split(".").map((n) => Number.parseInt(n, 10));
  return parts.some(Number.isNaN) ? null : parts;
}

/**
 * Dotted numeric compare ("0.1.10" > "0.1.9"); `-dev` / `+build` suffixes are ignored.
 * @returns {-1 | 0 | 1 | null} null when either version is missing or not numeric
 */
export function compareVersions(a, b) {
  const pa = versionParts(a);
  const pb = versionParts(b);
  if (!pa || !pb) return null;
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d) return d < 0 ? -1 : 1;
  }
  return 0;
}

/** @returns {"newer" | "older" | null} how the car's app version relates to the hub's */
export function carVersionSkew(carVersion, hubVersion) {
  const c = compareVersions(carVersion, hubVersion);
  return c === 1 ? "newer" : c === -1 ? "older" : null;
}

/** Whether a car whose `/api/status` is [status] serves [page]. */
export function carServesPage(page, status) {
  const pages = status && Array.isArray(status.pages) ? status.pages : LEGACY_PAGES;
  return pages.includes(page);
}

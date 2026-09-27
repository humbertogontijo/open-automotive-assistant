/**
 * Canonical page ids for routing + SPA fallback allowlist.
 * Keep in sync with pageView map in index.js.
 */
export const PAGE_IDS = [
  "home",
  "fleet",
  "history",
  "controls",
  "drive",
  "energy",
  "lights",
  "adas",
  "assistant",
  "display",
  "sound",
  "connect",
  "vehicle",
  "cameras",
  "dvr",
  "store",
  "shortcuts",
  "plugins",
  "settings",
  "system",
  "climate",
  "cabin",
  "safety",
  "lab",
  "about",
];

const PAGE_SET = new Set(PAGE_IDS);

export function isKnownPage(id) {
  return !!id && PAGE_SET.has(id);
}

/** Pathname for a page id (`home` → `/`). */
export function pagePath(id) {
  if (!id || id === "home") return "/";
  return "/" + id;
}

/** Page id from pathname (`/cameras` → `cameras`). Unknown → `home`. */
export function pathToPage(pathname) {
  var path = pathname || "/";
  if (path.length > 1 && path.charAt(path.length - 1) === "/") {
    path = path.slice(0, -1);
  }
  if (path === "/" || path === "") return "home";
  var id = path.slice(1).split("/")[0];
  return isKnownPage(id) ? id : "home";
}

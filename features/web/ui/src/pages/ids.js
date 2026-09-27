/**
 * Page table: every routable id, the element that renders it, and how it loads.
 * `alias` pages render their target; `load` pages ship as separate chunks fetched on first entry.
 */

import { appUrl, stripBase } from "../base.js";

/** @typedef {{ tag: string, group?: string, load?: () => Promise<unknown> }} PageDef */

/** @type {Record<string, PageDef | { alias: string }>} */
const PAGES = {
  home: { tag: "oaa-page-home" },
  fleet: { tag: "oaa-page-fleet" },
  history: { tag: "oaa-page-history", load: () => import("./history.js") },
  controls: { tag: "oaa-page-controls" },
  drive: { tag: "oaa-page-drive" },
  energy: { tag: "oaa-page-energy" },
  lights: { tag: "oaa-page-group", group: "lights" },
  adas: { tag: "oaa-page-group", group: "adas" },
  assistant: { tag: "oaa-page-assistant" },
  display: { tag: "oaa-page-group", group: "display" },
  sound: { tag: "oaa-page-sound" },
  connect: { tag: "oaa-page-connect" },
  vehicle: { tag: "oaa-page-group", group: "vehicle" },
  cameras: { tag: "oaa-page-cameras", load: () => import("./cameras.js") },
  dvr: { alias: "cameras" },
  store: { tag: "oaa-page-store" },
  shortcuts: { tag: "oaa-page-shortcuts", load: () => import("./shortcuts.js") },
  plugins: { tag: "oaa-page-plugins" },
  settings: { tag: "oaa-page-settings", load: () => import("./settings.js") },
  system: { alias: "settings" },
  climate: { alias: "controls" },
  cabin: { alias: "controls" },
  safety: { alias: "adas" },
  lab: { tag: "oaa-page-lab", load: () => import("./lab.js") },
  about: { tag: "oaa-page-about" },
};

export const PAGE_IDS = Object.keys(PAGES);

/** Definition behind a page id, aliases followed; unknown ids resolve to home. @returns {PageDef} */
export function pageDef(id) {
  const p = (id && PAGES[id]) || PAGES.home;
  return /** @type {PageDef} */ ("alias" in p ? PAGES[p.alias] : p);
}

export function isKnownPage(id) {
  return !!id && Object.prototype.hasOwnProperty.call(PAGES, id);
}

/** Browser pathname for a page id (`home` → BASE_PATH, `cameras` → BASE_PATH + `cameras`). */
export function pagePath(id) {
  return appUrl(!id || id === "home" ? "/" : "/" + id);
}

/** Page id from a browser pathname (`/cameras` → `cameras`). Unknown → `home`. */
export function pathToPage(pathname) {
  var path = stripBase(pathname);
  if (path.length > 1 && path.charAt(path.length - 1) === "/") {
    path = path.slice(0, -1);
  }
  if (path === "/" || path === "") return "home";
  var id = path.slice(1).split("/")[0];
  return isKnownPage(id) ? id : "home";
}

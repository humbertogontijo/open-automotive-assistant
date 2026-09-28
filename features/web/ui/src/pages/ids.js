/**
 * Page table: every routable id, the element that renders it, and how it loads.
 * `load` pages ship as separate chunks fetched on first entry.
 */

import { appUrl, stripBase } from "../base.js";
import { session } from "../store.js";

/** @typedef {{ tag: string, group?: string, load?: () => Promise<unknown> }} PageDef */

/** @type {Record<string, PageDef>} */
const PAGES = {
  home: { tag: "oaa-page-home" },
  cars: { tag: "oaa-page-cars" },
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
  store: { tag: "oaa-page-store" },
  shortcuts: { tag: "oaa-page-shortcuts", load: () => import("./shortcuts.js") },
  plugins: { tag: "oaa-page-plugins" },
  settings: { tag: "oaa-page-settings", load: () => import("./settings.js") },
  lab: { tag: "oaa-page-lab", load: () => import("./lab.js") },
  about: { tag: "oaa-page-about" },
};

export const PAGE_IDS = Object.keys(PAGES);

/** Definition behind a page id; unknown ids resolve to home. @returns {PageDef} */
export function pageDef(id) {
  return (id && PAGES[id]) || PAGES.home;
}

export function isKnownPage(id) {
  return !!id && Object.prototype.hasOwnProperty.call(PAGES, id);
}

/**
 * Browser pathname for a page. On the car: `/`, `/cameras`. On a hub the car list is `/`, hub
 * settings `/settings`, and a car's pages live under its id: `/<node>/`, `/<node>/cameras`.
 * [node] defaults to the open car; "" addresses the hub itself.
 * @param {string} id @param {string} [node]
 */
export function pagePath(id, node) {
  if (session.role !== "hub") return appUrl(!id || id === "home" ? "/" : "/" + id);
  const car = node === undefined ? session.selectedNodeId : node;
  if (!car || id === "cars") return appUrl(id === "settings" && !car ? "/settings" : "/");
  return appUrl("/" + encodeURIComponent(car) + "/" + (!id || id === "home" ? "" : id));
}

function decodeSegment(s) {
  try {
    return decodeURIComponent(s);
  } catch (e) {
    return s;
  }
}

/**
 * Node and page from a browser pathname: `/cameras` → `{node: "", page: "cameras"}`,
 * `/node-1/cameras` → `{node: "node-1", page: "cameras"}`. Unknown pages → `home`.
 * @returns {{ node: string, page: string }}
 */
export function parseLocation(pathname) {
  const segs = stripBase(pathname).split("/").filter(Boolean).map(decodeSegment);
  if (!segs.length) return { node: "", page: "home" };
  if (isKnownPage(segs[0])) return { node: "", page: segs[0] };
  return { node: segs[0], page: isKnownPage(segs[1]) ? segs[1] : "home" };
}

/**
 * What a parsed location shows for [role]. The car ignores node segments; a hub with no car
 * open shows the car list (or its own settings).
 * @param {{ node: string, page: string }} loc @param {string} role
 */
export function resolveRoute(loc, role) {
  if (role !== "hub") return { node: "", page: loc.page };
  if (!loc.node || loc.page === "cars") return { node: "", page: !loc.node && loc.page === "settings" ? "settings" : "cars" };
  return loc;
}

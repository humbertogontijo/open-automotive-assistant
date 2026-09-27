/**
 * Path-based page routes (@lit-labs/router). <oaa-app> owns the Router controller; other
 * modules navigate through gotoPage(). One catch-all route: the pathname carries the open car
 * on hubs (`/<node>/<page>`), which parseLocation() splits out.
 */
import { Router } from "@lit-labs/router";
import { PAGE_IDS, pagePath, parseLocation, isKnownPage } from "./pages/ids.js";

if (typeof (/** @type {any} */ (globalThis).URLPattern) === "undefined") {
  await import("urlpattern-polyfill");
}

/** @type {Router | null} */
let router = null;

/**
 * @param {import("lit").ReactiveControllerHost & HTMLElement} host
 * @param {{ view(): unknown, enter(loc: { node: string, page: string }): Promise<void> }} hooks
 */
export function createRouter(host, hooks) {
  const route = {
    path: "/*",
    enter: async () => {
      await hooks.enter(parseLocation(window.location.pathname || "/"));
      return true;
    },
    render: () => hooks.view(),
  };
  router = new Router(host, [route], { fallback: route });
  return router;
}

/**
 * Navigate to a page; updates history when the path changes.
 * @param {string} page
 * @param {{ replace?: boolean, node?: string }} [options] node: car to open ("" = hub), default the open one
 */
export function gotoPage(page, options) {
  if (!isKnownPage(page)) page = "home";
  const path = pagePath(page, options && options.node);
  const current = window.location.pathname || "/";
  if (current !== path) {
    if (options && options.replace) window.history.replaceState({}, "", path);
    else window.history.pushState({}, "", path);
  }
  return router ? router.goto(path) : Promise.resolve();
}

export { pagePath, isKnownPage, PAGE_IDS };

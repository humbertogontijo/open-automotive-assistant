/**
 * Path-based page routes (@lit-labs/router). <oaa-app> owns the Router controller; other
 * modules navigate through gotoPage().
 */
import { Router } from "@lit-labs/router";
import { PAGE_IDS, pagePath, pathToPage, isKnownPage } from "./pages/ids.js";

if (typeof (/** @type {any} */ (globalThis).URLPattern) === "undefined") {
  await import("urlpattern-polyfill");
}

/** @type {Router | null} */
let router = null;

/**
 * @param {import("lit").ReactiveControllerHost & HTMLElement} host
 * @param {{ view(page: string): unknown, enter(page: string, prev: string): Promise<void> }} hooks
 */
export function createRouter(host, hooks) {
  let lastPage = pathToPage(window.location.pathname || "/");
  const route = (page) => ({
    name: page,
    path: pagePath(page).replace(/[:*?+(){}\\]/g, "\\$&"),
    enter: async () => {
      const prev = lastPage;
      lastPage = page;
      await hooks.enter(page, prev);
      return true;
    },
    render: () => hooks.view(page),
  });
  const routes = [route("home"), ...PAGE_IDS.filter((id) => id !== "home").map(route)];
  router = new Router(host, routes, { fallback: route("home") });
  return router;
}

/**
 * Navigate to a page; updates history when the path changes.
 * @param {string} page
 * @param {{ replace?: boolean }} [options]
 */
export function gotoPage(page, options) {
  if (!isKnownPage(page)) page = "home";
  const path = pagePath(page);
  const current = window.location.pathname || "/";
  if (current !== path && current !== path + "/") {
    if (options && options.replace) window.history.replaceState({}, "", path);
    else window.history.pushState({}, "", path);
  }
  return router ? router.goto(path) : Promise.resolve();
}

export { pagePath, pathToPage, isKnownPage, PAGE_IDS };

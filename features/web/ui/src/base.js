/**
 * Where the SPA is mounted: `/` on the car and a plain hub, `/api/hassio_ingress/<token>/`
 * behind Home Assistant Ingress. Derived from the bundle's own URL, since every chunk is
 * served from `<base>/static/assets/`.
 */
export const BASE_PATH = new URL("../../", import.meta.url).pathname;

/** Same-origin URL for an app path such as `/api/status`. */
export function appUrl(path) {
  return BASE_PATH + String(path).replace(/^\/+/, "");
}

/** WebSocket URL for an app path on the current host. */
export function wsUrl(path) {
  const loc = window.location;
  return (loc.protocol === "https:" ? "wss:" : "ws:") + "//" + loc.host + appUrl(path);
}

/** App path (`/cameras`) from a browser pathname under [BASE_PATH]. */
export function stripBase(pathname) {
  const p = pathname || "/";
  if (p.startsWith(BASE_PATH)) return "/" + p.slice(BASE_PATH.length);
  return p === BASE_PATH.replace(/\/$/, "") ? "/" : p;
}

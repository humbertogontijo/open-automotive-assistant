/**
 * Fetch helper for app paths (`/api/...`, resolved under BASE_PATH). On hub hosts,
 * selectedNodeId is sent as X-Oaa-Node so the same SPA pages talk to one car at a time.
 */
import { appUrl } from "./base.js";
import { session } from "./store.js";

/** @type {(() => void)|null} */
let onUnauthorized = null;

/** Hub session lost (401 on an API call other than /api/auth/*). */
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn;
}

export async function api(path, opts) {
  const options = Object.assign({}, opts || {});
  const headers = Object.assign({}, options.headers || {});
  if (session.role === "hub" && !("X-Oaa-Node" in headers)) {
    headers["X-Oaa-Node"] = session.selectedNodeId || "";
  }
  options.headers = headers;
  const r = await fetch(appUrl(path), options);
  if (r.status === 401 && onUnauthorized && path.indexOf("/api/") === 0 && path.indexOf("/api/auth/") !== 0) {
    onUnauthorized();
  }
  const ct = r.headers.get("content-type") || "";
  if (ct.indexOf("json") >= 0) return r.json();
  return r.text();
}

/** @param {string} path @param {string | Record<string, string> | URLSearchParams} fields */
export function postForm(path, fields) {
  const body = typeof fields === "string" ? fields : new URLSearchParams(fields).toString();
  return api(path, { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body });
}

/** @param {string} path @param {unknown} [data] omitted sends an empty POST */
export function postJson(path, data) {
  if (data === undefined) return api(path, { method: "POST" });
  return api(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) });
}

/** @param {string} path @param {Blob} file @param {Record<string, string>} [headers] */
export async function postBytes(path, file, headers) {
  return api(path, {
    method: "POST",
    headers: Object.assign({ "Content-Type": "application/octet-stream" }, headers),
    body: await file.arrayBuffer(),
  });
}

/** Human-readable message for a thrown value. */
export function errText(e) {
  return String(e && e.message ? e.message : e);
}


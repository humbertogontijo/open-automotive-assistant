/**
 * Relative fetch helper. On hub hosts, selectedNodeId is sent as X-Oaa-Node
 * so the same SPA pages talk to one car at a time.
 */
import { state } from "./store.js";

/** @type {(() => void)|null} */
let onUnauthorized = null;

/** Hub session lost (401 on an API call other than /api/auth/*). */
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn;
}

export async function api(path, opts) {
  const options = Object.assign({}, opts || {});
  const headers = Object.assign({}, options.headers || {});
  if (state.role === "hub" && state.selectedNodeId) {
    headers["X-Oaa-Node"] = state.selectedNodeId;
  }
  options.headers = headers;
  const r = await fetch(path, options);
  if (r.status === 401 && onUnauthorized && path.indexOf("/api/") === 0 && path.indexOf("/api/auth/") !== 0) {
    onUnauthorized();
  }
  const ct = r.headers.get("content-type") || "";
  if (ct.indexOf("json") >= 0) return r.json();
  return r.text();
}

/** Human-readable message for a thrown value. */
export function errText(e) {
  return String(e && e.message ? e.message : e);
}

export function fmt(v) {
  return v == null || v === "" ? "—" : v;
}

export function $(id) {
  return document.getElementById(id);
}

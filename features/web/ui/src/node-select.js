/**
 * Open car on hub hosts. The URL carries it (`/<node>/…`); it is mirrored in the `oaa_node`
 * cookie so plain links (`/debug/export`, the /debug pages) reach the same car as
 * `X-Oaa-Node` API calls.
 */
import { BASE_PATH } from "./base.js";
import { session } from "./store.js";

const COOKIE = "oaa_node";

/** @type {((id: string) => (void|Promise<void>))|null} */
let onSelect = null;

/** Record [id] as the open car without navigating (route changes, stale ids). */
export function rememberNode(id) {
  const v = id || "";
  session.selectedNodeId = v;
  document.cookie = v
    ? COOKIE + "=" + encodeURIComponent(v) + "; path=" + BASE_PATH + "; SameSite=Lax"
    : COOKIE + "=; path=" + BASE_PATH + "; Max-Age=0; SameSite=Lax";
}

/** Navigation for a new selection (set by boot). */
export function setNodeSelectHandler(fn) {
  onSelect = fn;
}

/** Open car [id] ("" = no car, back to the fleet). */
export async function selectNode(id) {
  if (onSelect) await onSelect(id || "");
}

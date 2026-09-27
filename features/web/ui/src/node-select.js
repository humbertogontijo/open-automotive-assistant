/**
 * Selected car on hub hosts. Persisted across reloads and mirrored in the
 * `oaa_node` cookie so plain links (`/debug/export`, the /debug pages) reach
 * the same car as `X-Oaa-Node` API calls.
 */
import { session } from "./store.js";

const STORAGE_KEY = "oaa_selected_node";
const COOKIE = "oaa_node";

/** @type {((id: string) => (void|Promise<void>))|null} */
let onSelect = null;

export function loadSelectedNode() {
  try {
    return localStorage.getItem(STORAGE_KEY) || "";
  } catch (e) {
    return "";
  }
}

/** Record [id] as the selection without reloading anything (bootstrap / stale id). */
export function rememberNode(id) {
  const v = id || "";
  session.selectedNodeId = v;
  try {
    if (v) localStorage.setItem(STORAGE_KEY, v);
    else localStorage.removeItem(STORAGE_KEY);
  } catch (e) {}
  document.cookie = v
    ? COOKIE + "=" + encodeURIComponent(v) + "; path=/; SameSite=Lax"
    : COOKIE + "=; path=/; Max-Age=0; SameSite=Lax";
}

/** What a new selection triggers app-wide (events reconnect, navigation, refresh). */
export function setNodeSelectHandler(fn) {
  onSelect = fn;
}

/** Switch the UI to car [id] ("" = no car, back to the fleet). */
export async function selectNode(id) {
  rememberNode(id);
  if (onSelect) await onSelect(session.selectedNodeId);
}

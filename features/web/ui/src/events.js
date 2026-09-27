/**
 * WebSocket event stream from `/api/events` — replaces softRefresh polling.
 */
import { session, catalog } from "./store.js";
import { heldTransportValue, reloadEntities, reloadStatus } from "./actions.js";

let socket = null;
let reconnectTimer = 0;
let backoffMs = 1000;
let everConnected = false;

function isCatalogOnlyEntity(row) {
  if (!row) return false;
  return row.update === "catalog" || row.composite === true;
}

function patchEntityRow(id, value, status) {
  if (!id) return false;
  // Ignore stale transport while a local play/pause hold is active.
  const held = heldTransportValue(id);
  if (
    held &&
    value != null &&
    value !== held &&
    (value === "playing" || value === "paused" || value === "idle")
  ) {
    value = held;
  }
  let changed = false;
  function bump(list) {
    if (!list || !list.length) return list;
    let next = null;
    for (let i = 0; i < list.length; i++) {
      const row = list[i];
      if (!row || row.id !== id) continue;
      // Catalog-only composites: never apply binding attr-raw as product state.
      if (isCatalogOnlyEntity(row) && value !== undefined) {
        if (status != null && status !== row.status) {
          if (next == null) next = list.slice();
          next[i] = Object.assign({}, row, { status: status });
          changed = true;
        }
        break;
      }
      if (next == null) next = list.slice();
      const updated = Object.assign({}, row);
      if (value !== undefined) {
        updated.value = value;
        if (updated.state !== undefined) updated.state = value;
      }
      if (status != null) updated.status = status;
      next[i] = updated;
      changed = true;
      break;
    }
    return next || list;
  }
  const entities = bump(catalog.entities);
  const controls = bump(catalog.controls);
  if (changed) catalog.$patch({ entities, controls });
  return changed;
}

function handleMessage(raw) {
  let msg;
  try {
    msg = JSON.parse(raw);
  } catch (e) {
    return;
  }
  if (!msg || !msg.t) return;
  switch (msg.t) {
    case "dvr":
      if (msg.dvr && session.status) session.status = { ...session.status, dvr: { ...session.status.dvr, ...msg.dvr } };
      return;
    case "entity": {
      patchEntityRow(msg.id, msg.value, msg.status);
      return;
    }
    case "catalog":
      reloadEntities();
      return;
    case "pair_request":
      session.pairRequests = Array.isArray(msg.pending) ? msg.pending : [];
      return;
    default:
      return;
  }
}

function wsUrl() {
  const loc = window.location;
  const proto = loc.protocol === "https:" ? "wss:" : "ws:";
  return proto + "//" + loc.host + "/api/events" +
    (session.role === "hub" && session.selectedNodeId
      ? "?node=" + encodeURIComponent(session.selectedNodeId)
      : "");
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  const wait = backoffMs;
  backoffMs = Math.min(backoffMs * 2, 15000);
  reconnectTimer = setTimeout(function () {
    reconnectTimer = 0;
    connectEvents();
  }, wait);
}

/**
 * Open (or reopen) the events WebSocket. Safe to call multiple times.
 */
export function connectEvents() {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return;
  }
  let ws;
  try {
    ws = new WebSocket(wsUrl());
  } catch (e) {
    session.eventsOpen = false;
    scheduleReconnect();
    return;
  }
  socket = ws;
  ws.onopen = function () {
    if (socket !== ws) return;
    backoffMs = 1000;
    session.eventsOpen = true;
    // After a drop, pull once so the first paint is not stale.
    if (everConnected) {
      reloadEntities();
      reloadStatus();
    }
    everConnected = true;
  };
  ws.onmessage = function (ev) {
    if (socket === ws) handleMessage(ev.data);
  };
  ws.onclose = function () {
    if (socket !== ws) return;
    session.eventsOpen = false;
    socket = null;
    scheduleReconnect();
  };
  ws.onerror = function () {
    try {
      ws.close();
    } catch (e) {}
  };
}

function dropSocket() {
  const ws = socket;
  socket = null;
  if (!ws) return;
  ws.onopen = ws.onmessage = ws.onclose = ws.onerror = null;
  try {
    ws.close();
  } catch (e) {}
}

/** Force reconnect (e.g. after hub fleet node selection changes). */
export function reconnectEvents() {
  dropSocket();
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = 0;
  }
  backoffMs = 1000;
  connectEvents();
}

document.addEventListener("visibilitychange", function () {
  if (document.visibilityState === "visible") {
    connectEvents();
  }
});

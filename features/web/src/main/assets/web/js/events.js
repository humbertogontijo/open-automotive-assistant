/**
 * WebSocket event stream from `/api/events` — replaces softRefresh polling.
 */
import { api } from "./api.js";
import { state, patchSilent, notify } from "./store.js";
import { isTimelineBusy } from "./pages/index.js";
import { heldTransportValue, mergeTransportHold } from "./actions.js";

let socket = null;
let reconnectTimer = 0;
let backoffMs = 1000;
let catalogReloadBusy = false;
let everConnected = false;

function shouldSkipPaint() {
  // Heavy editors / open menus — rebuilding the tree would drop focus or
  // dismiss the picker. Do not skip merely because a range/input is focused:
  // that blocked media_player and other card updates after transport clicks.
  if (document.querySelector(".choice-select.open") || state.openChoiceId) return true;
  if (state.shortcutEdit) return true;
  if (state.pluginEditId) return true;
  if (typeof isTimelineBusy === "function" && isTimelineBusy()) return true;
  const ae = document.activeElement;
  if (ae && ae.closest && ae.closest(".choice-select, .choice-menu")) return true;
  return false;
}

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
  const entities = bump(state.entities);
  const controls = bump(state.controls);
  if (changed) {
    patchSilent({ entities: entities, controls: controls });
  }
  return changed;
}

async function reloadCatalog() {
  if (catalogReloadBusy) return;
  catalogReloadBusy = true;
  try {
    const pair = await Promise.all([api("/api/entities"), api("/api/controls")]);
    patchSilent({
      entities: mergeTransportHold(pair[0]),
      controls: mergeTransportHold(pair[1]),
    });
    if (!shouldSkipPaint()) notify();
  } catch (e) {
  } finally {
    catalogReloadBusy = false;
  }
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
    case "hello":
    case "ping":
      return;
    case "telemetry": {
      const onCam = state.page === "cameras" || state.page === "dvr";
      const livePreview = onCam && !!state.cameraPreviewActive;
      const status = Object.assign({}, state.status || {});
      if (msg.telemetry) status.telemetry = msg.telemetry;
      if (msg.dvr) status.dvr = msg.dvr;
      patchSilent({ status: status });
      if (livePreview) return;
      if (!shouldSkipPaint()) notify();
      return;
    }
    case "entity": {
      const changed = patchEntityRow(msg.id, msg.value, msg.status);
      if (changed && !shouldSkipPaint()) notify();
      return;
    }
    case "catalog":
      reloadCatalog();
      return;
    default:
      return;
  }
}

function wsUrl() {
  const loc = window.location;
  const proto = loc.protocol === "https:" ? "wss:" : "ws:";
  return proto + "//" + loc.host + "/api/events" +
    (state.role === "hub" && state.selectedNodeId
      ? "?node=" + encodeURIComponent(state.selectedNodeId)
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
    state._eventsOpen = false;
    scheduleReconnect();
    return;
  }
  socket = ws;
  ws.onopen = function () {
    if (socket !== ws) return;
    backoffMs = 1000;
    state._eventsOpen = true;
    // After a drop, pull once so the first paint is not stale.
    if (everConnected) {
      reloadCatalog();
      api("/api/status")
        .then(function (s) {
          if (s) patchSilent({ status: s });
          if (!shouldSkipPaint()) notify();
        })
        .catch(function () {});
    }
    everConnected = true;
  };
  ws.onmessage = function (ev) {
    if (socket === ws) handleMessage(ev.data);
  };
  ws.onclose = function () {
    if (socket !== ws) return;
    state._eventsOpen = false;
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

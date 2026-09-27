import { api } from "./api.js";
import { state, patch, notify, findControl } from "./store.js";
import { setTheme } from "./theme.js";
import { setLocale, t } from "./i18n.js";
import { unitPrefs } from "./units.js";

async function saveUnitPrefs(unitsObj) {
  const json = JSON.stringify(unitsObj);
  try {
    await api("/api/prefs", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: "units=" + encodeURIComponent(json),
    });
  } catch (e) {}
  try {
    localStorage.setItem("oaa_units", json);
  } catch (e) {}
  patch({ prefs: Object.assign({}, state.prefs || {}, { units: unitsObj }) });
}

/** Soft reload of live entity/control values after a write. */
export async function reloadEntities() {
  try {
    const pair = await Promise.all([api("/api/entities"), api("/api/controls")]);
    patch({
      entities: mergeTransportHold(pair[0]),
      controls: mergeTransportHold(pair[1]),
    });
  } catch (e) {
    notify();
  }
}

/** Brief hold so a slow NotificationListener catalog cannot undo play/pause. */
const transportHoldUntil = Object.create(null);
const transportHoldValue = Object.create(null);
const TRANSPORT_HOLD_MS = 2500;

export function mediaStateLabel(value) {
  if (value === "playing") return t("media_player.playing", "Playing");
  if (value === "paused") return t("media_player.paused", "Paused");
  if (value === "idle") return t("media_player.idle", "Idle");
  return null;
}

/** @returns {string|null} held transport value while the hold is active */
export function heldTransportValue(id) {
  if (!id) return null;
  if ((transportHoldUntil[id] || 0) < Date.now()) return null;
  return transportHoldValue[id] || null;
}

function setTransportHold(id, value) {
  transportHoldValue[id] = value;
  transportHoldUntil[id] = Date.now() + TRANSPORT_HOLD_MS;
}

function applyTransportHold(row) {
  if (!row || !row.id) return row;
  const held = heldTransportValue(row.id);
  if (!held || row.value === held) return row;
  if (row.value !== "playing" && row.value !== "paused" && row.value !== "idle") {
    return row;
  }
  const updated = Object.assign({}, row, {
    value: held,
  });
  if (updated.state !== undefined) updated.state = held;
  return updated;
}

/** Merge active play/pause holds into a catalog list (SSE reload + setControl). */
export function mergeTransportHold(list) {
  if (!list || !list.length) return list;
  let next = null;
  for (let i = 0; i < list.length; i++) {
    const merged = applyTransportHold(list[i]);
    if (merged !== list[i]) {
      if (next == null) next = list.slice();
      next[i] = merged;
    }
  }
  return next || list;
}

/** Optimistically patch one entity/control row so transport UI flips immediately. */
function optimisticControlValue(id, value) {
  if (!id || value == null) return;
  setTransportHold(id, value);
  function bump(list) {
    if (!list || !list.length) return list;
    let next = null;
    for (let i = 0; i < list.length; i++) {
      const row = list[i];
      if (!row || row.id !== id) continue;
      if (next == null) next = list.slice();
      const updated = Object.assign({}, row, { value: value });
      if (updated.state !== undefined) updated.state = value;
      next[i] = updated;
      break;
    }
    return next || list;
  }
  patch({
    entities: bump(state.entities),
    controls: bump(state.controls),
  });
}

export async function setControl(id, val) {
  // Media transport: flip local state before the round-trip. NotificationListener
  // can lag ~1–2s; hold must not be clobbered by a stale catalog during that window.
  if (val === "play") optimisticControlValue(id, "playing");
  else if (val === "pause") optimisticControlValue(id, "paused");

  await api("/api/controls/" + encodeURIComponent(id), {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: "value=" + encodeURIComponent(val),
  });

  // Prefer SSE entity/catalog events when connected — avoid a full catalog
  // reload racing the transport hold on every click.
  if (state._eventsOpen) {
    notify();
    return;
  }
  await reloadEntities();
}

export async function setPersist(id, opts) {
  const body = new URLSearchParams();
  if (opts.enabled != null) body.set("enabled", opts.enabled ? "1" : "0");
  if (opts.value != null) body.set("value", String(opts.value));
  await api("/api/controls/" + encodeURIComponent(id) + "/persist", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: body.toString(),
  });
  await reloadEntities();
}

async function refreshHiddenEntities() {
  try {
    const res = await api("/api/entities/hidden");
    const list = (res && res.entities) || [];
    const updates = { hiddenEntities: list };
    if (state.showHiddenGroup) {
      const still = list.some(function (e) {
        return e.group === state.showHiddenGroup;
      });
      if (!still) updates.showHiddenGroup = null;
    }
    patch(updates);
  } catch (e) {
    notify();
  }
}

export async function hideEntity(id) {
  await api("/api/entities/" + encodeURIComponent(id) + "/visibility", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: "hidden=1",
  });
  await Promise.all([reloadEntities(), refreshHiddenEntities()]);
}

export async function unhideEntity(id) {
  await api("/api/entities/" + encodeURIComponent(id) + "/visibility", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: "hidden=0",
  });
  await Promise.all([reloadEntities(), refreshHiddenEntities()]);
}

/**
 * Pref handlers previously in bindPage.
 * @param {string} pref
 * @param {string} next
 * @param {object} [extra]
 */
export async function runPref(pref, next, extra) {
  extra = extra || {};
  if (pref === "theme") {
    setTheme(next);
    notify();
    return;
  }
  if (pref === "locale") {
    await setLocale(next);
    notify();
    return;
  }
  if (pref.indexOf("unit_") === 0) {
    const dim = pref.slice("unit_".length);
    const cur = Object.assign({}, unitPrefs());
    cur[dim] = next;
    await saveUnitPrefs(cur);
    return;
  }
  if (pref === "adb") {
    if (next === "0" && !confirm(t("system.adb.warn", "Disable wireless ADB?"))) return;
    const res = await api("/api/adb", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: next === "1" ? "enabled=1&port=5566" : "enabled=0",
    });
    if (res && res.ok === false) {
      alert(res.message || t("system.adb.failed", "Wireless ADB toggle failed"));
    }
    try {
      const adb = await api("/api/adb");
      patch({ adb: adb, adbMessage: null });
    } catch (e) {
      notify();
    }
    return;
  }
  if (pref === "ha-enabled" || pref === "sc-enabled") {
    // Editor-only toggles: caller updates draft; just re-render.
    notify();
    return;
  }
  if (pref === "lab-tab") {
    const updates = { labTab: next };
    if (next === "obd2" && !state.obd2) {
      try {
        updates.obd2 = await api(
          "/debug/obd2?token=" + encodeURIComponent(state.token),
        );
      } catch (e) {
        updates.obd2 = { results: [], summary: { available: false } };
      }
    }
    patch(updates);
    return;
  }
  if (pref === "lab-bound") {
    patch({ probeBoundFilter: next || "all" });
    return;
  }
  if (pref === "cam-storage") {
    await api("/api/dvr/storage", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: "id=" + encodeURIComponent(next),
    });
    try {
      const s = await api("/api/status");
      patch({ status: s });
    } catch (e) {
      if (state.status && state.status.dvr) {
        state.status.dvr.storageId = next;
      }
      notify();
    }
    const { loadRecordings, stopCameraLive } = await import("./pages/cameras.js");
    await loadRecordings();
    await stopCameraLive();
    return;
  }
  if (pref === "cam-mode") {
    const storage =
      (state.status && state.status.dvr && state.status.dvr.storageId) || "";
    await api("/api/dvr/mode", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body:
        "mode=" +
        encodeURIComponent(next) +
        "&storage=" +
        encodeURIComponent(storage || ""),
    });
    try {
      const s = await api("/api/status");
      patch({ status: s });
    } catch (e) {
      notify();
    }
    const { loadRecordings } = await import("./pages/cameras.js");
    await loadRecordings();
    return;
  }
  if (pref === "cam-retention-size") {
    await api("/api/dvr/policy", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: "maxTotalMb=" + encodeURIComponent(next),
    });
    try {
      const s = await api("/api/status");
      patch({ status: s });
    } catch (e) {
      notify();
    }
    return;
  }
  if (pref === "cam-retention-age") {
    await api("/api/dvr/policy", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: "maxAgeDays=" + encodeURIComponent(next),
    });
    try {
      const s = await api("/api/status");
      patch({ status: s });
    } catch (e) {
      notify();
    }
    return;
  }
  if (pref === "hist-range") {
    patch({ historyRangeHours: parseInt(next, 10) || 24 });
    const { loadHistoryPoints } = await import("./pages/history.js");
    await loadHistoryPoints();
    notify();
    return;
  }
  if (pref === "hist-view") {
    patch({ historyView: next });
    return;
  }
  if (pref === "cam-rec") {
    // Legacy binary toggle → DVR on / off
    const storage =
      (state.status && state.status.dvr && state.status.dvr.storageId) || "";
    const mode = next === "1" ? "dvr" : "off";
    await api("/api/dvr/mode", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body:
        "mode=" +
        encodeURIComponent(mode) +
        "&storage=" +
        encodeURIComponent(storage || ""),
    });
    try {
      const s = await api("/api/status");
      patch({ status: s });
    } catch (e) {
      notify();
    }
    return;
  }
  if (pref === "sc-overlay") {
    await api("/api/shortcuts/overlay", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled: next === "1" }),
    });
    const { loadShortcuts } = await import("./pages/shortcuts.js");
    await loadShortcuts();
    notify();
    return;
  }
  if (pref === "sc-slot") {
    const slot = parseInt(extra.slot, 10);
    await api("/api/shortcuts/slots", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        slot: isNaN(slot) ? 0 : slot,
        shortcutId: next || null,
      }),
    });
    const { loadShortcuts } = await import("./pages/shortcuts.js");
    await loadShortcuts();
    notify();
    return;
  }
}

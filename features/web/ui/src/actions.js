import { api, postForm, postJson } from "./api.js";
import { session, catalog, prefs } from "./store.js";
import { setTheme } from "./theme.js";
import { setLocale, t } from "./i18n.js";
import { unitPrefs } from "./units.js";
import { loadShortcuts } from "./shortcuts-data.js";
import { confirmDialog, alertDialog } from "./ui/confirm.js";

async function saveUnitPrefs(unitsObj) {
  const json = JSON.stringify(unitsObj);
  try {
    await postForm("/api/prefs", "units=" + encodeURIComponent(json));
  } catch (e) {}
  try {
    localStorage.setItem("oaa_units", json);
  } catch (e) {}
  prefs.units = unitsObj;
}

/** Soft reload of live entity/control values after a write. */
/** @type {Promise<void> | null} */
let catalogReload = null;
let catalogReloadAgain = false;

/**
 * Re-read entities and controls. Calls made while a reload is in flight share it and
 * trigger one more pass, so a reload requested after a write always sees that write.
 */
export function reloadEntities() {
  if (catalogReload) {
    catalogReloadAgain = true;
    return catalogReload;
  }
  catalogReload = (async () => {
    do {
      catalogReloadAgain = false;
      try {
        const [entities, controls] = await Promise.all([api("/api/entities"), api("/api/controls")]);
        // A hub answers an error object while no car is selected.
        if (Array.isArray(entities) && Array.isArray(controls)) {
          catalog.$patch({ entities: mergeTransportHold(entities), controls: mergeTransportHold(controls) });
        }
      } catch (e) {}
    } while (catalogReloadAgain);
    catalogReload = null;
  })();
  return catalogReload;
}

/** Re-read /api/status after a setting change. */
export async function reloadStatus() {
  try {
    session.status = await api("/api/status");
  } catch (e) {}
}

/** Brief hold so a slow NotificationListener catalog cannot undo play/pause. */
const transportHoldUntil = Object.create(null);
const transportHoldValue = Object.create(null);
const TRANSPORT_HOLD_MS = 2500;

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
  catalog.$patch({ entities: bump(catalog.entities), controls: bump(catalog.controls) });
}

export async function setControl(id, val) {
  // Media transport: flip local state before the round-trip. NotificationListener
  // can lag ~1–2s; hold must not be clobbered by a stale catalog during that window.
  if (val === "play") optimisticControlValue(id, "playing");
  else if (val === "pause") optimisticControlValue(id, "paused");

  await postForm("/api/controls/" + encodeURIComponent(id), "value=" + encodeURIComponent(val));

  // Prefer SSE entity/catalog events when connected — avoid a full catalog
  // reload racing the transport hold on every click.
  if (session.eventsOpen) return;
  await reloadEntities();
}

export async function setPersist(id, opts) {
  const body = new URLSearchParams();
  if (opts.enabled != null) body.set("enabled", opts.enabled ? "1" : "0");
  if (opts.value != null) body.set("value", String(opts.value));
  await postForm("/api/controls/" + encodeURIComponent(id) + "/persist", body);
  await reloadEntities();
}

async function refreshHiddenEntities() {
  try {
    const res = await api("/api/entities/hidden");
    const list = (res && res.entities) || [];
    const group = catalog.showHiddenGroup;
    catalog.$patch({
      hiddenEntities: list,
      showHiddenGroup: group && list.some((e) => e.group === group) ? group : null,
    });
  } catch (e) {}
}

export async function hideEntity(id) {
  await postForm("/api/entities/" + encodeURIComponent(id) + "/visibility", "hidden=1");
  await Promise.all([reloadEntities(), refreshHiddenEntities()]);
}

export async function unhideEntity(id) {
  await postForm("/api/entities/" + encodeURIComponent(id) + "/visibility", "hidden=0");
  await Promise.all([reloadEntities(), refreshHiddenEntities()]);
}

async function setDvrMode(mode) {
  const dvr = session.status && session.status.dvr;
  await postForm("/api/dvr/mode", "mode=" + encodeURIComponent(mode) + "&storage=" + encodeURIComponent((dvr && dvr.storageId) || ""));
  await reloadStatus();
}

async function setDvrPolicy(field, value) {
  await postForm("/api/dvr/policy", field + "=" + encodeURIComponent(value));
  await reloadStatus();
}

/**
 * Handlers behind `prefSegment` / `prefSelect` cards.
 * @param {string} pref
 * @param {string} next
 * @param {object} [extra]
 */
export async function runPref(pref, next, extra) {
  extra = extra || {};
  if (pref === "theme") {
    setTheme(next);
    return;
  }
  if (pref === "locale") {
    await setLocale(next);
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
    if (next === "0" && !(await confirmDialog(t("system.adb.warn", "Disable wireless ADB?"), { danger: true }))) return;
    const res = await postForm("/api/adb", next === "1" ? "enabled=1&port=5566" : "enabled=0");
    if (res && res.ok === false) {
      alertDialog(res.message || t("system.adb.failed", "Wireless ADB toggle failed"));
    }
    try {
      session.adb = await api("/api/adb");
    } catch (e) {}
    return;
  }
  if (pref === "cam-storage") {
    await postForm("/api/dvr/storage", "id=" + encodeURIComponent(next));
    await reloadStatus();
    const { loadRecordings, restartLive } = await import("./pages/cameras.js");
    await loadRecordings();
    await restartLive();
    return;
  }
  if (pref === "cam-mode") {
    await setDvrMode(next);
    const { loadRecordings } = await import("./pages/cameras.js");
    await loadRecordings();
    return;
  }
  if (pref === "cam-rec") {
    await setDvrMode(next === "1" ? "dvr" : "off");
    return;
  }
  if (pref === "cam-retention-size") {
    await setDvrPolicy("maxTotalMb", next);
    return;
  }
  if (pref === "cam-retention-age") {
    await setDvrPolicy("maxAgeDays", next);
    return;
  }
  if (pref === "sc-overlay") {
    await postJson("/api/shortcuts/overlay", { enabled: next === "1" });
    await loadShortcuts();
    return;
  }
  if (pref === "sc-slot") {
    const slot = parseInt(extra.slot, 10);
    await postJson("/api/shortcuts/slots", {
        slot: isNaN(slot) ? 0 : slot,
        shortcutId: next || null,
      });
    await loadShortcuts();
    return;
  }
}

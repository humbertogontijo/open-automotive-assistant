import { api, errText } from "./api.js";
import { session, shortcuts } from "./store.js";
import { toastError } from "./ui/toast.js";

/** @type {Promise<void> | null} */
let appsLoad = null;
let appsNode = "";

/** Launchable apps for the app picker; fetched once per car, on first use. */
export function ensureApps() {
  if (appsNode !== session.selectedNodeId) {
    appsNode = session.selectedNodeId;
    appsLoad = null;
  }
  appsLoad ??= api("/api/apps").then(
    (res) => {
      shortcuts.apps = (res && res.apps) || [];
    },
    () => {
      appsLoad = null;
    },
  );
}

/** @type {Promise<void> | null} */
let servicesLoad = null;
let servicesNode = "";

/** Per-domain services for control actions; fetched once per car. */
export function ensureServices() {
  if (servicesNode !== session.selectedNodeId) {
    servicesNode = session.selectedNodeId;
    servicesLoad = null;
    shortcuts.services = null;
  }
  servicesLoad ??= api("/api/services").then(
    (res) => {
      if (res && Array.isArray(res.domains)) shortcuts.services = res.domains;
      else servicesLoad = null;
    },
    () => {
      servicesLoad = null;
    },
  );
}

/** Shortcuts, routines, scenes and overlay slots. */
export async function loadShortcuts() {
  ensureServices();
  try {
    const res = await api("/api/shortcuts");
    shortcuts.$patch({
      shortcuts: (res && res.shortcuts) || [],
      routines: (res && res.routines) || [],
      scenes: (res && res.scenes) || [],
      slots: (res && res.slots) || {},
      overlay: (res && res.overlay) || {},
      wheelKeys: (res && res.wheelKeys) || shortcuts.wheelKeys,
    });
  } catch (e) {
    toastError(errText(e));
  }
}

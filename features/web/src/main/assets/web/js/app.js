import { api, $ } from "./api.js";
import { loadSelectedNode, rememberNode, setNodeSelectHandler } from "./node-select.js";
import { state, patch, notify, subscribe } from "./store.js";
import { render as litRender } from "./lit.js";
import { pageView } from "./pages/index.js";
import {
  loadHistoryPoints,
  loadEnergyDash,
  loadRecordings,
  loadSounds,
  startCameraLive,
  stopCameraLive,
  stopLabLogs,
  applyCameraPlayerSrc,
  ensureStoreLoaded,
} from "./pages/index.js";
import { loadShortcuts } from "./pages/shortcuts.js";
import { loadFleet } from "./pages/fleet.js";
import { shouldShowSetup, renderSetupOverlay } from "./ui/setup.js";
import {
  ensureHubAuth,
  onHubAuthenticated,
  renderHubAuthOverlay,
  shouldShowHubLogin,
  shouldShowHubSetup,
} from "./ui/hub-auth.js";
import { setTheme } from "./theme.js";
import { defaultUnitPrefs } from "./units.js";
import { loadI18n } from "./i18n.js";
import { loadIcons, mountNavIcons } from "./icons.js";
import { stashCurrentScroll, restorePageScroll, rememberScroll } from "./nav.js";
import { connectEvents, reconnectEvents } from "./events.js";
import {
  setRouteEnterHandler,
  startRouter,
  gotoPage,
  router,
} from "./router.js";

function updateNavActive(sec) {
  document.querySelectorAll(".nav-item").forEach(function (n) {
    n.classList.toggle("active", n.getAttribute("data-page") === sec);
  });
}

/** Show/hide nav by host role and capabilities. */
function applyCapabilityNav() {
  const role = state.role || "local";
  const caps = state.capabilities || [];
  const has = function (name) {
    return caps.indexOf(name) >= 0;
  };
  document.querySelectorAll(".nav-item[data-role]").forEach(function (el) {
    const need = el.getAttribute("data-role");
    el.style.display = !need || need === role ? "" : "none";
  });
  document.querySelectorAll(".nav-item[data-cap]").forEach(function (el) {
    if (role === "hub" && !state.selectedNodeId) {
      el.style.display = "none";
      return;
    }
    const needed = (el.getAttribute("data-cap") || "").split(",");
    const ok = needed.some(function (c) {
      return c && has(c.trim());
    });
    el.style.display = ok ? "" : "none";
    if (!ok && state.page === el.getAttribute("data-page")) {
      goPage("home", { replace: true });
    }
  });
  const fleetNav = document.querySelector('.nav-item[data-page="fleet"]');
  if (fleetNav) fleetNav.style.display = role === "hub" ? "" : "none";
  applyHubLevel();
}

/** Hub with no car open: fleet-only view, no car sidebar. */
function applyHubLevel() {
  const hub = state.role === "hub";
  document.body.classList.toggle("hub-fleet", hub && !state.selectedNodeId);
  const carEl = document.getElementById("sideCar");
  if (!carEl) return;
  const nodes = (state.fleet && state.fleet.nodes) || [];
  const node = nodes.find(function (n) { return n.id === state.selectedNodeId; });
  carEl.textContent = node ? node.name || node.id : state.selectedNodeId || "";
  carEl.hidden = !(hub && state.selectedNodeId);
}

let hubBooted = false;

function paint() {
  const main = $("main");
  if (!main) return;
  const probeScrollEl = document.getElementById("probeScroll");
  const prevProbeScroll = probeScrollEl ? probeScrollEl.scrollTop : 0;

  var outlet = router.outlet();
  litRender(outlet != null ? outlet : pageView(state.page), main);
  renderSetupOverlay();
  renderHubAuthOverlay();
  mountNavIcons();
  updateNavActive(state.page);

  if (state.page === "store") ensureStoreLoaded();

  if (state.page === "cameras" || state.page === "dvr") {
    if (state.cameraPlayerMode !== "dvr") {
      Promise.resolve(startCameraLive()).then(function () {
        return applyCameraPlayerSrc();
      });
    }
  } else if (state.cameraPreviewActive || state.cameraPlayerMode === "dvr") {
    stopCameraLive();
  }

  restorePageScroll(state.page);
  const probeAfter = document.getElementById("probeScroll");
  if (probeAfter) probeAfter.scrollTop = prevProbeScroll;
}

// Lit signals only: store.subscribe bumps version → re-run paint.
subscribe(function () {
  try {
    paint();
  } catch (e) {}
});

export async function refresh() {
  await loadIcons();
  await loadI18n();
  await ensureHubAuth();
  if (shouldShowHubSetup() || shouldShowHubLogin()) {
    document.body.classList.add("hub-fleet");
    notify();
    return;
  }
  const status = await api("/api/status");
  const role = (status && status.role) || "local";
  let selectedNodeId = state.selectedNodeId || loadSelectedNode();
  if (role === "hub") {
    const fleet = status.fleet || (await api("/api/nodes").catch(function () {
      return { nodes: [] };
    }));
    const nodes = (fleet && fleet.nodes) || [];
    if (selectedNodeId && !nodes.some(function (n) { return n.id === selectedNodeId; })) {
      selectedNodeId = "";
    }
    // Single-car convenience on first load only; returning to the fleet must stick.
    if (!selectedNodeId && !hubBooted && state.page !== "fleet" && nodes.length === 1 && nodes[0].online) {
      selectedNodeId = nodes[0].id;
    }
    hubBooted = true;
    state.role = role;
    rememberNode(selectedNodeId);
    state.fleet = fleet;
    if (!selectedNodeId) {
      Object.assign(state, {
        status: status,
        role: role,
        fleet: fleet,
        selectedNodeId: "",
        setup: status.setup || { complete: true },
        entities: [],
        controls: [],
        historyEntities: [],
        hiddenEntities: [],
        capabilities: [],
        hubJoin: status.hub || null,
      });
      applyCapabilityNav();
      if (state.page !== "fleet") goPage("fleet", { replace: true });
      notify();
      return;
    }
  } else {
    state.role = "local";
    state.selectedNodeId = "";
  }

  const setup = status.setup || (await api("/api/setup").catch(function () { return { complete: true }; }));
  const entities = await api("/api/entities");
  const controls = await api("/api/controls");
  let historyEntities = [];
  try {
    const hist = await api("/api/history");
    historyEntities = (hist && hist.entities) || [];
  } catch (e) {}
  let hiddenEntities = [];
  try {
    const hidden = await api("/api/entities/hidden");
    hiddenEntities = (hidden && hidden.entities) || [];
  } catch (e) {}
  let adb = null;
  try {
    adb = (status && status.adb) || (await api("/api/adb"));
  } catch (e) {}
  let lab = null;
  let token = state.token;
  try {
    lab = await api("/api/lab");
    if (lab && lab.token) token = lab.token;
    else if (lab && !lab.contributor) token = "";
  } catch (e) {}
  try {
    const hint = await api("/debug/adb-hint");
    if (!token && hint.contributor && hint.tokenHint) token = hint.tokenHint;
  } catch (e) {}
  let updatesPrefs = { units: defaultUnitPrefs() };
  try {
    const prefs = await api("/api/prefs");
    if (prefs.theme) setTheme(prefs.theme);
    let units = prefs.units;
    try {
      const local = localStorage.getItem("oaa_units");
      if (local) units = local;
      if (typeof units === "string") units = JSON.parse(units);
    } catch (e) {}
    if (units && typeof units === "object") {
      updatesPrefs = { units: units };
    }
    if (prefs.homeLat != null) updatesPrefs.homeLat = prefs.homeLat;
    if (prefs.homeLon != null) updatesPrefs.homeLon = prefs.homeLon;
    if (prefs.homeRadiusM != null) updatesPrefs.homeRadiusM = prefs.homeRadiusM;
  } catch (e) {}

  const updates = {
    status: status,
    role: role,
    selectedNodeId: selectedNodeId,
    fleet: status.fleet || state.fleet,
    hubJoin: status.hub || null,
    setup: setup,
    entities: entities,
    controls: controls,
    historyEntities: historyEntities,
    hiddenEntities: hiddenEntities,
    adb: adb,
    lab: lab,
    token: token,
    prefs: updatesPrefs,
    capabilities: (status && status.capabilities) || state.capabilities || [],
  };
  if (state._setupInit == null) {
    updates._setupInit = true;
    updates.showSetup = shouldShowSetup(setup);
  }
  Object.assign(state, updates);
  applyCapabilityNav();

  if (state.page === "fleet") {
    await loadFleet();
  }
  if (state.page === "history") {
    if (!state.historySelected && state.historyEntities && state.historyEntities.length) {
      patch({ historySelected: state.historyEntities[0] });
    }
    if (state.historySelected) {
      await loadHistoryPoints();
    }
  }
  if (state.page === "energy") {
    await loadEnergyDash();
  }
  if (state.page === "sound" || !state._soundsLoaded) {
    state._soundsLoaded = true;
    await loadSounds();
  }
  if (state.page === "cameras" || state.page === "dvr") {
    await loadRecordings();
  }
  if (state.page === "shortcuts" || state.page === "settings" || state.page === "system" || !state._shortcutsLoaded) {
    state._shortcutsLoaded = true;
    await loadShortcuts();
  }
  notify();
}

onHubAuthenticated(refresh);
setNodeSelectHandler(async function (id) {
  reconnectEvents();
  goPage(id ? "home" : "fleet");
  await refresh();
});

function onPageEnter(page, prev) {
  if (prev && prev !== page) {
    stashCurrentScroll();
    if (
      (prev === "cameras" || prev === "dvr") &&
      page !== "cameras" &&
      page !== "dvr"
    ) {
      stopCameraLive();
    }
    if (prev === "lab") stopLabLogs();
  }

  if (page === "fleet" && state.role === "hub" && state.selectedNodeId) {
    rememberNode("");
    applyHubLevel();
    reconnectEvents();
    refresh();
  }

  updateNavActive(page);

  const updates = {
    page: page,
    openChoiceId: null,
    choiceSearchQuery: "",
    showHiddenGroup: null,
  };
  if (page === "store") updates._storeLoaded = false;
  patch(updates);

  if (page === "shortcuts" || page === "settings" || page === "system") {
    loadShortcuts().then(function () {
      notify();
    });
  } else if (page === "fleet") {
    loadFleet().then(function () {
      notify();
    });
  } else if (page === "history") {
    if (!state.historySelected && state.historyEntities && state.historyEntities.length) {
      patch({ historySelected: state.historyEntities[0], historyView: null });
    } else {
      patch({ historyView: null });
    }
    loadHistoryPoints().then(function () {
      notify();
    });
  } else if (page === "energy") {
    loadEnergyDash().then(function () {
      notify();
    });
  } else if (page === "cameras" || page === "dvr") {
    patch({
      cameraPreviewActive: false,
      cameraPreviewSrc: "",
      cameraPlayerMode: "live",
      cameraPlayingName: "",
      cameraPlayingKind: "",
      cameraPlaybackPaused: false,
      cameraPlaybackLoading: false,
    });
    loadRecordings().then(function () {
      notify();
    });
  } else if (page === "sound") {
    loadSounds().then(function () {
      notify();
    });
  } else if (page === "lab") {
    api("/api/lab")
      .then(function (lab) {
        state.lab = lab;
        if (lab && lab.token) state.token = lab.token;
        const tab = state.labTab || "vhal";
        if (tab === "obd2") {
          return api("/debug/obd2?token=" + encodeURIComponent(state.token));
        }
        if (tab === "entities") return null;
        return api("/debug/probe?token=" + encodeURIComponent(state.token));
      })
      .then(function (p) {
        if (p) {
          if (state.labTab === "obd2") state.obd2 = p;
          else state.probe = p;
        }
        notify();
      })
      .catch(function () {
        notify();
      });
  }
}

function goPage(page, options) {
  if (!page) return;
  return gotoPage(page, options);
}

/** Native quick-entry (MainActivity) navigates in-page through this. */
window.__oaaGoPage = goPage;

setRouteEnterHandler(onPageEnter);
startRouter(function () {
  notify();
});

/**
 * Bootstrap once over HTTP, then live updates via `/api/events` WebSocket
 * (see events.js). No softRefresh interval — that stacked with camera live
 * preview and pegged the SoC.
 */
refresh().then(function () {
  connectEvents();
});

function isTextField(el) {
  if (!el) return false;
  if (el.tagName === "TEXTAREA" || el.isContentEditable) return true;
  if (el.tagName !== "INPUT") return false;
  return !/^(checkbox|radio|range|button|submit|reset|color|file|hidden)$/.test(el.type);
}

// The HU keyboard shrinks the WebView (adjustResize); keep the focused field visible.
function revealFocusedField() {
  const el = document.activeElement;
  if (isTextField(el)) el.scrollIntoView({ block: "center", behavior: "smooth" });
}
document.addEventListener("focusin", function (ev) {
  if (isTextField(ev.target)) setTimeout(revealFocusedField, 350);
});
(window.visualViewport || window).addEventListener("resize", revealFocusedField);

// Persist in-session scroll while scrolling.
document.addEventListener(
  "scroll",
  function (ev) {
    if (ev.target && ev.target.id === "main") {
      rememberScroll(state.page, ev.target.scrollTop);
    }
  },
  true,
);

/**
 * Data bootstrap. <oaa-app> calls refresh() once and enterPage() on every route change;
 * page elements load their own data (see OaaPage).
 */
import { api } from "./api.js";
import { loadSelectedNode, rememberNode, setNodeSelectHandler } from "./node-select.js";
import { session, catalog, prefs } from "./store.js";
import { loadPageModule } from "./pages/index.js";
import { reloadPage } from "./lit/oaa-page.js";
import { shouldShowSetup } from "./ui/setup.js";
import {
  ensureHubAuth,
  headUnitNeedsReauth,
  onHubAuthenticated,
  requestHeadUnitReauth,
  shouldShowCarPair,
  shouldShowHubLogin,
  shouldShowHubSetup,
} from "./ui/hub-auth.js";
import { setTheme } from "./theme.js";
import { defaultUnitPrefs } from "./units.js";
import { loadI18n } from "./i18n.js";
import { stashCurrentScroll } from "./nav.js";
import { connectEvents, reconnectEvents } from "./events.js";
import { gotoPage } from "./router.js";
import { isPageGated } from "./shell/oaa-nav.js";

let hubBooted = false;

/** @param {string} page @param {{ replace?: boolean }} [options] */
export function goPage(page, options) {
  if (!page) return;
  return gotoPage(page, options);
}

/** Leave pages the selected car cannot show (capability-gated nav entries). */
function enforcePageGating() {
  if (isPageGated(session.page)) goPage("home", { replace: true });
}

export async function refresh() {
  await ensureHubAuth();
  const onHub = session.hubAuth && session.hubAuth.role === "hub";
  if (headUnitNeedsReauth()) {
    requestHeadUnitReauth();
    await loadI18n();
    return;
  }
  if (shouldShowHubSetup() || shouldShowHubLogin() || shouldShowCarPair()) {
    await loadI18n(onHub ? "" : undefined);
    return;
  }
  // The oaa_node cookie would route a plain request to the selected car.
  let status = await api("/api/status", onHub ? { headers: { "X-Oaa-Node": "" } } : undefined);
  const role = (status && status.role) || "local";
  let selectedNodeId = session.selectedNodeId || loadSelectedNode();
  if (role === "hub") {
    const fleet =
      status.fleet ||
      (await api("/api/nodes").catch(function () {
        return { nodes: [] };
      }));
    const nodes = (fleet && fleet.nodes) || [];
    if (selectedNodeId && !nodes.some((n) => n.id === selectedNodeId)) selectedNodeId = "";
    // Single-car convenience on first load only; returning to the fleet must stick.
    if (!selectedNodeId && !hubBooted && session.page !== "fleet" && nodes.length === 1 && nodes[0].online) {
      selectedNodeId = nodes[0].id;
    }
    hubBooted = true;
    session.role = role;
    rememberNode(selectedNodeId);
    session.fleet = fleet;
    if (!selectedNodeId) {
      session.$patch({
        status,
        role,
        fleet,
        selectedNodeId: "",
        setup: status.setup || { complete: true },
        capabilities: [],
        hubJoin: status.hub || null,
      });
      catalog.$patch({ entities: [], controls: [], historyEntities: [], hiddenEntities: [] });
      await loadI18n();
      if (session.page !== "fleet") goPage("fleet", { replace: true });
      return;
    }
    const hubStatus = status;
    status = Object.assign({}, await api("/api/status"), { fleet, hub: hubStatus.hub });
  } else {
    session.role = "local";
    session.selectedNodeId = "";
  }
  await loadI18n(undefined, role === "hub");

  const setup = status.setup || (await api("/api/setup").catch(() => ({ complete: true })));
  const [entities, controls] = await Promise.all([api("/api/entities"), api("/api/controls")]);
  const optional = (path, pick) => api(path).then(pick, () => null);
  const [historyEntities, hiddenEntities, adb, prefsRes] = await Promise.all([
    optional("/api/history", (h) => (h && h.entities) || []),
    optional("/api/entities/hidden", (h) => (h && h.entities) || []),
    status && status.adb ? Promise.resolve(status.adb) : optional("/api/adb", (a) => a),
    optional("/api/prefs", (p) => p),
  ]);

  let units = defaultUnitPrefs();
  if (prefsRes) {
    if (prefsRes.theme) setTheme(prefsRes.theme, false);
    let u = prefsRes.units;
    try {
      const local = localStorage.getItem("oaa_units");
      if (local) u = local;
      if (typeof u === "string") u = JSON.parse(u);
    } catch (e) {}
    if (u && typeof u === "object") units = u;
    prefs.$patch({
      units,
      homeLat: prefsRes.homeLat ?? null,
      homeLon: prefsRes.homeLon ?? null,
      homeRadiusM: prefsRes.homeRadiusM ?? null,
    });
  } else {
    prefs.units = units;
  }

  session.$patch({
    status,
    role,
    selectedNodeId,
    fleet: status.fleet || session.fleet,
    hubJoin: status.hub || null,
    setup,
    adb,
    capabilities: (status && status.capabilities) || session.capabilities || [],
  });
  catalog.$patch({
    entities: Array.isArray(entities) ? entities : [],
    controls: Array.isArray(controls) ? controls : [],
    historyEntities: historyEntities || [],
    hiddenEntities: hiddenEntities || [],
  });
  if (!session.setupInit) {
    session.setupInit = true;
    session.showSetup = shouldShowSetup(setup);
  }
  enforcePageGating();
  await reloadPage();
}

/** Route enter hook: load the page chunk; the page element loads its own data. */
export async function enterPage(page, prev) {
  await loadPageModule(page);
  if (prev && prev !== page) stashCurrentScroll();

  if (page === "fleet" && session.role === "hub" && session.selectedNodeId) {
    rememberNode("");
    reconnectEvents();
    refresh();
  }

  session.page = page;
  catalog.showHiddenGroup = null;
}

onHubAuthenticated(refresh);
setNodeSelectHandler(async function (id) {
  reconnectEvents();
  goPage(id ? "home" : "fleet");
  await refresh();
});

/** Bootstrap once over HTTP, then live updates via the `/api/events` WebSocket. */
export function start() {
  refresh()
    .finally(() => {
      session.booted = true;
    })
    .then(connectEvents);
}


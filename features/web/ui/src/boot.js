/**
 * Data bootstrap. <oaa-app> calls refresh() once and enterPage() on every route change;
 * page elements load their own data (see OaaPage).
 */
import { api } from "./api.js";
import { rememberNode, setNodeSelectHandler } from "./node-select.js";
import { session, catalog, prefs } from "./store.js";
import { loadPageModule } from "./pages/index.js";
import { pagePath, resolveRoute } from "./pages/ids.js";
import { t } from "./i18n.js";
import { toastError } from "./ui/toast.js";
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

/** Last location the router entered, before role resolution (the role is known only after refresh). */
let routed = { node: "", page: "home" };

/** @param {string} page @param {{ replace?: boolean, node?: string }} [options] */
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
  let selectedNodeId = "";
  let carName = "";
  if (role === "hub") {
    const fleet =
      status.fleet ||
      (await api("/api/nodes").catch(function () {
        return { nodes: [] };
      }));
    const nodes = (fleet && fleet.nodes) || [];
    const route = resolveRoute(routed, role);
    selectedNodeId = route.node;
    const car = nodes.find((n) => n.id === selectedNodeId);
    carName = (car && car.name) || selectedNodeId;
    session.role = role;
    session.fleet = fleet;
    if (selectedNodeId && !(car && car.online)) {
      await loadI18n();
      return backToFleet(
        car
          ? t("fleet.car_offline", "{name} is offline").replace("{name}", carName)
          : t("fleet.car_unknown", "That car is not paired with this hub"),
      );
    }
    rememberNode(selectedNodeId);
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
      await showRoute(route);
      return;
    }
    const hubStatus = status;
    const carStatus = await api("/api/status").catch(() => null);
    if (!carStatus || carStatus.ok === false) {
      await loadI18n();
      return backToFleet(t("fleet.car_unreachable", "{name} is not answering").replace("{name}", carName));
    }
    status = Object.assign({}, carStatus, { fleet, hub: hubStatus.hub });
    await showRoute(route);
  } else {
    session.role = "local";
    session.selectedNodeId = "";
  }
  await loadI18n(undefined, role === "hub");

  const setup = status.setup || (await api("/api/setup").catch(() => ({ complete: true })));
  const [entities, controls] = await Promise.all([api("/api/entities"), api("/api/controls")]).catch(() => [null, null]);
  if (role === "hub" && (!Array.isArray(entities) || !Array.isArray(controls))) {
    return backToFleet(t("fleet.car_unreachable", "{name} is not answering").replace("{name}", carName));
  }
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

/** Show [route] and make the address bar say it (`/cameras` without a car becomes `/`). */
async function showRoute(route) {
  const path = pagePath(route.page, route.node);
  if (window.location.pathname !== path) window.history.replaceState({}, "", path);
  await loadPageModule(route.page);
  session.page = route.page;
}

/** The open car cannot be shown (offline, unknown, not answering): back to the fleet at `/`. */
async function backToFleet(message) {
  toastError(message);
  rememberNode("");
  reconnectEvents();
  await goPage("fleet", { replace: true, node: "" });
  return refresh();
}

/** Route enter hook: load the page chunk; the page element loads its own data. */
export async function enterPage(loc) {
  routed = loc;
  const route = resolveRoute(loc, session.role);
  await loadPageModule(route.page);
  if (session.page && session.page !== route.page) stashCurrentScroll();

  const carChanged = session.role === "hub" && route.node !== session.selectedNodeId;
  if (carChanged) {
    rememberNode(route.node);
    reconnectEvents();
  }
  session.page = route.page;
  catalog.showHiddenGroup = null;
  if (carChanged) refresh();
}

onHubAuthenticated(refresh);
setNodeSelectHandler((id) => goPage(id ? "home" : "fleet", { node: id }));

/** Bootstrap once over HTTP, then live updates via the `/api/events` WebSocket. */
export function start() {
  refresh()
    .finally(() => {
      session.booted = true;
    })
    .then(connectEvents);
}


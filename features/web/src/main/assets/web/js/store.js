/**
 * Reactive app store — Lit signals only.
 *
 * Mutate via patch() / notify() / patchSilent(). Notifies bump a root
 * `version` signal (rAF-coalesced). UI paint and other reactions use
 * effect() from signals.js (or subscribe() which is that wrapper).
 */
import { Signal, effect } from "./signals.js";

export const state = {
  status: null,
  controls: [],
  entities: [],
  setup: null,
  probe: null,
  lab: null,
  page: "home",
  token: "",
  showSetup: false,
  /** Host role from /api/status: local | hub */
  role: "local",
  /** Hub fleet snapshot { nodes: [...] } */
  fleet: null,
  /** Selected car node id when role=hub */
  selectedNodeId: "",
  hubJoin: null,
  pairingOffer: null,
  i18n: { locale: "pt-BR", locales: ["pt-BR", "en"], strings: {}, valueMaps: {} },
  /** UI prefs from /api/prefs (theme mirrored in DOM; units map here). */
  prefs: {
    units: {
      temperature: "celsius",
      distance: "km",
      speed: "km_h",
      fuel_economy: "l_100km",
      energy_economy: "kwh_100km",
    },
  },
  adb: null,
  adbMessage: null,
  storeQuery: "",
  storeResults: [],
  storeDetail: null,
  storeBusy: false,
  storeMessage: null,
  historyEntities: [],
  historyPoints: null,
  historySelected: null,
  historyRangeHours: 24,
  historyView: null,
  energySparks: null,
  recordings: [],
  dvrTimeline: { segments: [], recording: false },
  /** Local calendar day for the DVR scrubber: "YYYY-MM-DD". null = today. */
  dvrTimelineDay: null,
  cameraTimelineAtMs: 0,
  cameraPreviewActive: false,
  /** Stable HLS URL while preview is active — avoid resetting video src on every render. */
  cameraPreviewSrc: "",
  cameraPreviewError: "",
  /** Shared player: "live" HLS or "dvr" clip playback. */
  cameraPlayerMode: "live",
  cameraPlayingName: "",
  cameraPlayingKind: "",
  cameraPlaybackPaused: false,
  /** Playback rate for live + DVR <video> (0.5–2). */
  cameraPlaybackRate: 1,
  cameraPlaybackLoading: false,
  cameraPlaybackDurationMs: 0,
  sounds: null,
  hiddenEntities: [],
  /** When set to a group id, that page shows only its hidden cards. */
  showHiddenGroup: null,
  labTab: "vhal",
  obd2: null,
  /** VHAL catalog: all | bound | missing */
  probeBoundFilter: "all",
  probeFilter: "",
  /** Open choice-select control id (lit-managed). */
  openChoiceId: null,
  /** Filter text while a searchable choice-select is open. */
  choiceSearchQuery: "",
  shortcutEdit: null,
  pluginEditId: null,
  setupMsg: "",
  apkMessage: null,
};

/**
 * Root invalidate signal. Read inside effect() so the effect re-runs after
 * patch/notify. Plain `state` fields are not signals yet — bump this instead.
 */
export const version = new Signal.State(0);

let raf = 0;

/**
 * Subscribe to store updates via Lit signals (effect + version).
 * Prefer importing effect() from signals.js for new code that already
 * reads other signals; use this when you only care about store ticks.
 */
export function subscribe(fn) {
  return effect(function () {
    version.get();
    fn();
  });
}

/** Apply a shallow merge and schedule a version bump (rAF-coalesced). */
export function patch(partial) {
  if (partial) Object.assign(state, partial);
  scheduleNotify();
}

/**
 * Mutate state without re-rendering. Used by event patches while camera live
 * preview is active so we do not tear down HLS / rebuild the catalog UI.
 */
export function patchSilent(partial) {
  if (partial) Object.assign(state, partial);
}

/** Notify after in-place mutations (e.g. nested object fields). */
export function notify() {
  scheduleNotify();
}

function scheduleNotify() {
  if (raf) return;
  raf = requestAnimationFrame(function () {
    raf = 0;
    // untrack: avoid nesting this read in some accidental consumer.
    var next =
      Signal.subtle.untrack(function () {
        return version.get();
      }) + 1;
    version.set(next);
  });
}

export function entitiesByGroup(group) {
  return state.entities.filter(function (e) {
    return e.group === group;
  });
}

export function hiddenEntitiesByGroup(group) {
  return (state.hiddenEntities || []).filter(function (e) {
    return e.group === group;
  });
}

export function isShowingHidden(group) {
  return (
    state.showHiddenGroup === group && hiddenEntitiesByGroup(group).length > 0
  );
}

/** Bucket by product subsection (`section`), falling back to domain. */
export function groupBySection(list) {
  const map = {};
  list.forEach(function (e) {
    const k = e.section || e.domain || "other";
    if (!map[k]) map[k] = [];
    map[k].push(e);
  });
  return map;
}

export function findControl(id) {
  return (state.controls || []).concat(state.entities || []).find(function (c) {
    return c.id === id;
  });
}

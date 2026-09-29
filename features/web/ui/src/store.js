/**
 * App store: typed slices whose properties are signals. Reading `session.role` inside an
 * OaaElement render (or an effect) subscribes to it; assigning it re-renders the readers.
 * Assigning the same value (or the same object) does not notify; nested mutation never
 * does, so always assign a new object.
 */
import { signal, computed } from "./signals.js";

/**
 * @template {Record<string, any>} T
 * @typedef {T & { $patch(partial: Partial<T>): void }} Slice
 */

/**
 * @template {Record<string, any>} T
 * @param {T} initial
 * @returns {Slice<T>}
 */
export function createSlice(initial) {
  /** @type {Record<string, ReturnType<typeof signal>>} */
  const slots = {};
  const slice = {};
  for (const key of Object.keys(initial)) {
    const s = signal(initial[key], { equals: Object.is });
    slots[key] = s;
    Object.defineProperty(slice, key, {
      enumerable: true,
      get: () => s.get(),
      set: (v) => {
        s.set(v);
      },
    });
  }
  Object.defineProperty(slice, "$patch", {
    value(partial) {
      for (const k of Object.keys(partial || {})) {
        if (k in slots) slots[k].set(partial[k]);
      }
    },
  });
  return /** @type {Slice<T>} */ (slice);
}

/** @typedef {import("../types/api").components["schemas"]["Status"] & Record<string, any>} Status */
/** @typedef {import("../types/api").components["schemas"]["Fleet"]} Fleet */
/** @typedef {import("../types/api").components["schemas"]["Entity"] & Record<string, any>} Entity */

export const session = createSlice({
  /** Host role from /api/status: local | hub */
  role: /** @type {"local" | "hub"} */ ("local"),
  /** Selected car node id when role=hub */
  selectedNodeId: "",
  /** @type {Status | null} */
  status: null,
  /** App version of the hub serving this UI (role=hub); `status.version` is the open car's. */
  hubVersion: "",
  /** @type {string[]} */
  capabilities: [],
  /** @type {any} */
  setup: null,
  showSetup: false,
  setupInit: false,
  setupMsg: "",
  /** Hub fleet snapshot { nodes: [...] } */
  /** @type {Fleet | null} */
  fleet: null,
  /** @type {any} */
  hubJoin: null,
  /** @type {any} */
  hubAuth: null,
  hubAuthMsg: "",
  /** Open requests to pair with this car (head unit only, from `pair_request` events). */
  /** @type {{ id: string, kind: string, name: string, code: string, source: string, expiresAtMs: number }[]} */
  pairRequests: [],
  token: "",
  /** @type {any} */
  adb: null,
  page: "home",
  eventsOpen: false,
  /** First refresh finished (strings, status and catalog loaded, or the auth gate is up). */
  booted: false,
});

export const catalog = createSlice({
  /** @type {Entity[]} */
  controls: [],
  /** @type {Entity[]} */
  entities: [],
  /** @type {Entity[]} */
  hiddenEntities: [],
  /** Entity ids with recorded history. */
  /** @type {string[]} */
  historyEntities: [],
  /** When set to a group id, that page shows only its hidden cards. */
  /** @type {string | null} */
  showHiddenGroup: null,
});

/** @typedef {{ temperature: string, distance: string, speed: string, fuel_economy: string, energy_economy: string }} UnitPrefs */

export const prefs = createSlice({
  /** data-theme on <html>: dark | light | contrast */
  theme: document.documentElement.getAttribute("data-theme") || "dark",
  /** @type {UnitPrefs} */
  units: {
    temperature: "celsius",
    distance: "km",
    speed: "km_h",
    fuel_economy: "l_100km",
    energy_economy: "kwh_100km",
  },
  /** @type {number | null} */
  homeLat: null,
  /** @type {number | null} */
  homeLon: null,
  /** @type {number | null} */
  homeRadiusM: null,
});

export const i18n = createSlice({
  locale: "pt-BR",
  locales: ["pt-BR", "en"],
  /** @type {Record<string, string>} */
  strings: {},
  /** @type {Record<string, Record<string, string>>} */
  valueMaps: {},
});

export const dvr = createSlice({
  /** @type {{ segments: any[], recording: boolean } & Record<string, any>} */
  timeline: { segments: [], recording: false },
  /** Local calendar day for the DVR scrubber: "YYYY-MM-DD". null = today. */
  /** @type {string | null} */
  timelineDay: null,
});

/** Camera player: live preview seat and DVR playback transport. */
export const camera = createSlice({
  previewActive: false,
  previewSrc: "",
  previewError: "",
  /** @type {"live" | "dvr"} */
  mode: "live",
  playingName: "",
  paused: false,
  rate: 1,
  loading: false,
});

export const shortcuts = createSlice({
  /** @type {any[]} */
  shortcuts: [],
  /** @type {any[]} */
  routines: [],
  /** @type {any[]} */
  scenes: [],
  /** @type {Record<string, any>} */
  slots: {},
  /** @type {Record<string, any>} */
  overlay: {},
  /** @type {any[]} */
  apps: [],
  wheelKeys: ["custom", "mute", "top", "left", "right", "bottom", "vr", "menu", "confirm"],
});

export const store = createSlice({
  /** @type {any[]} */
  results: [],
  /** @type {any} */
  detail: null,
  busy: false,
  /** @type {string | null} */
  message: null,
});

export const lab = createSlice({
  /** GET /api/lab payload */
  /** @type {any} */
  info: null,
  /** @type {any} */
  probe: null,
  /** @type {any} */
  obd2: null,
});

export const sounds = createSlice({
  /** @type {any} */
  list: null,
});

/** @typedef {{ id: number, variant: "brand" | "success" | "warning" | "danger" | "neutral", message: string }} Toast */

export const toasts = createSlice({
  /** @type {Toast[]} */
  items: [],
});

/** Parts of `session.status` whose readers should not re-render on unrelated status changes. */
export const statusVersion = computed(() => session.status?.version);
export const statusDvr = computed(() => session.status?.dvr);

/** Shared empty result; frozen so a caller mutating it fails loudly. @type {Entity[]} */
const NONE = /** @type {any} */ (Object.freeze([]));

/** @param {Entity[]} list */
function byGroup(list) {
  /** @type {Map<string, Entity[]>} */
  const map = new Map();
  for (const e of list || []) {
    const bucket = map.get(e.group);
    if (bucket) bucket.push(e);
    else map.set(e.group, [e]);
  }
  return map;
}

const entityGroups = computed(() => byGroup(catalog.entities));
const entitiesById = computed(() => new Map((catalog.entities || []).map((e) => [e.id, e])));
const hiddenGroups = computed(() => byGroup(catalog.hiddenEntities));
const controlsById = computed(() => {
  /** @type {Map<string, Entity>} */
  const map = new Map();
  for (const c of (catalog.controls || []).concat(catalog.entities || [])) {
    if (!map.has(c.id)) map.set(c.id, c);
  }
  return map;
});

/** @returns {Entity[]} */
export function entitiesByGroup(group) {
  return entityGroups.get().get(group) || NONE;
}

/** @returns {Entity[]} */
export function hiddenEntitiesByGroup(group) {
  return hiddenGroups.get().get(group) || NONE;
}

export function isShowingHidden(group) {
  return catalog.showHiddenGroup === group && hiddenEntitiesByGroup(group).length > 0;
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

/** @returns {Entity | undefined} */
export function findEntity(id) {
  return entitiesById.get().get(id);
}

/** @returns {Entity | undefined} */
export function findControl(id) {
  return controlsById.get().get(id);
}

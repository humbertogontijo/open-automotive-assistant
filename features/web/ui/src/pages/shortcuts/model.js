/**
 * Shortcut / routine / scene model helpers: plugin contributions, normalisation,
 * summary labels and picker options. No rendering, no state of their own.
 */
import { session, catalog, shortcuts, findControl } from "../../store.js";
import { computed } from "../../signals.js";
import { t, entityLabel } from "../../i18n.js";
import { valueOptionsForControl } from "./fields.js";

function pluginEntries() {
  return (session.status && session.status.plugins) || [];
}

function pluginId(p) {
  return p.id || (p.status && p.status.id);
}

function pluginConfigured(id) {
  const p = pluginById(id);
  if (!p) return false;
  const st = p.status || {};
  const cfg = p.config || {};
  if (st.enabled === false || cfg.enabled === false) return false;
  if (cfg.configured === true || st.configured === true) return true;
  if ((cfg.tokenSet || st.hasToken) && (cfg.baseUrl || st.baseUrl)) return true;
  return !!(st.enabled || cfg.enabled);
}

function configuredPlugins() {
  return pluginEntries().filter(function (p) {
    const id = pluginId(p);
    return id && pluginConfigured(id);
  });
}

function pluginById(id) {
  return (
    pluginEntries().find(function (p) {
      return pluginId(p) === id;
    }) || null
  );
}

export function pluginDisplayName(id) {
  const p = pluginById(id);
  if (!p) return id || "?";
  const st = p.status || {};
  return p.displayName || st.displayName || id;
}

export function pluginParamKeys(id, kind, name) {
  const p = pluginById(id);
  if (!p) return [];
  const st = p.status || p;
  const map = kind === "action" ? st.actionParams : st.triggerParams;
  return (map && map[name]) || [];
}

export function pluginSelectOptions() {
  return configuredPlugins().map(function (p) {
    const id = pluginId(p);
    return { value: id, label: pluginDisplayName(id) };
  });
}

/** @param {"action" | "trigger"} kind */
export function pluginContributionOptions(id, kind) {
  const p = pluginById(id);
  const st = (p && p.status) || {};
  const names = (kind === "action" ? st.actions : st.triggers) || [];
  return names.map(function (n) {
    return { value: n, label: n };
  });
}

// --- normalisation ------------------------------------------------------------

export function normalizeTrigger(tr) {
  if (!tr || tr.type !== "plugin") return tr;
  return {
    type: "plugin",
    pluginId: tr.pluginId || "",
    trigger: tr.trigger || "",
    params: Object.assign({}, tr.params || {}),
  };
}

export function normalizeAction(a) {
  if (!a || a.type !== "plugin") return a;
  let params = Object.assign({}, a.params || {});
  if (params.data != null && typeof params.data !== "string") {
    try {
      params = Object.assign({}, params, { data: JSON.stringify(params.data) });
    } catch (e) {}
  }
  return { type: "plugin", pluginId: a.pluginId || "", action: a.action || "", params: params };
}

export function normalizeCondition(c) {
  if (!c || !c.type) return { type: "entity_equals", entityId: "", value: "" };
  if (c.type === "entity_equals") {
    return { type: "entity_equals", entityId: c.entityId || "", value: c.value != null ? String(c.value) : "" };
  }
  if (c.type === "gear_equals") return { type: "gear_equals", gear: c.gear != null ? Number(c.gear) : 4 };
  if (c.type === "wifi_ssid") return { type: "wifi_ssid", ssid: c.ssid || "", contains: !!c.contains };
  return c;
}

/** Deep copy of a saved shortcut / routine with every row normalised, ready to edit. */
export function toDraft(saved) {
  const d = JSON.parse(JSON.stringify(saved));
  d.actions = (d.actions || []).map(normalizeAction);
  if (d.triggers) d.triggers = d.triggers.map(normalizeTrigger);
  d.conditions = (d.conditions || []).map(normalizeCondition);
  return d;
}

/** Trimmed plugin params without blanks; `data` parsed back to JSON when it is JSON. */
function apiParams(params, parseData) {
  const out = {};
  for (const k of Object.keys(params || {})) {
    const v = typeof params[k] === "string" ? params[k].trim() : params[k];
    if (v === "" || v == null) continue;
    out[k] = v;
  }
  if (parseData && typeof out.data === "string") {
    try {
      out.data = JSON.parse(out.data);
    } catch (e) {}
  }
  return out;
}

export function toApiShortcut(body) {
  const actions = (body.actions || []).map(function (a) {
    a = normalizeAction(a);
    if (a.type !== "plugin") return a;
    return { type: "plugin", pluginId: a.pluginId, action: a.action, params: apiParams(a.params, true) };
  });
  const triggers = (body.triggers || []).map(function (tr) {
    tr = normalizeTrigger(tr);
    if (tr.type !== "plugin") return tr;
    return { type: "plugin", pluginId: tr.pluginId, trigger: tr.trigger, params: apiParams(tr.params, false) };
  });
  return Object.assign({}, body, {
    actions: actions,
    triggers: triggers,
    conditions: (body.conditions || []).map(normalizeCondition),
  });
}

// --- labels -------------------------------------------------------------------

export function wheelKeyLabel(k) {
  const map = {
    custom: t("wheel.key.custom", "Star / custom"),
    mute: t("wheel.key.mute", "Mute"),
    top: t("wheel.key.top", "D-pad up"),
    left: t("wheel.key.left", "D-pad left"),
    right: t("wheel.key.right", "D-pad right"),
    bottom: t("wheel.key.bottom", "D-pad down"),
    vr: t("wheel.key.vr", "Voice"),
    menu: t("wheel.key.menu", "Menu"),
    confirm: t("wheel.key.confirm", "OK / confirm"),
  };
  return map[k] || k;
}

function gearName(g) {
  return g === 4 ? "P" : g === 2 ? "R" : g === 1 ? "N" : g === 8 ? "D" : g != null ? String(g) : "?";
}

export function triggerLabel(tr) {
  tr = normalizeTrigger(tr);
  if (!tr || !tr.type) return "";
  switch (tr.type) {
    case "boot":
      return t("shortcuts.trigger.boot", "Boot");
    case "ui_card":
      return t("shortcuts.trigger.ui_card", "UI card") + (tr.group ? ": " + tr.group : "");
    case "screen":
      return (
        t("shortcuts.trigger.screen", "Screen") +
        ": " +
        (tr.on !== false ? t("shortcuts.trigger.screen.on", "On") : t("shortcuts.trigger.screen.off", "Off"))
      );
    case "gear":
      return t("shortcuts.trigger.gear", "Gear") + ": " + gearName(tr.gear);
    case "wheel_key":
      return (
        t("shortcuts.trigger.wheel", "Wheel key") +
        ": " +
        wheelKeyLabel(tr.key || "") +
        (tr.longPress ? " (" + t("shortcuts.trigger.long_press", "long press") + ")" : "")
      );
    case "wifi_ssid":
      return t("shortcuts.trigger.wifi", "Wi‑Fi SSID") + (tr.ssid ? ": " + tr.ssid : "");
    case "entity_state":
      return (
        t("shortcuts.trigger.entity_state", "Entity") +
        ": " +
        (tr.entityId || "?") +
        (tr.value != null && tr.value !== "" ? "=" + tr.value : "")
      );
    case "plugin": {
      const p = tr.params || {};
      const summary = Object.keys(p)
        .filter((k) => p[k])
        .slice(0, 2)
        .map((k) => p[k])
        .join(" ");
      return pluginDisplayName(tr.pluginId) + " " + (tr.trigger || "?") + (summary ? ": " + summary : "");
    }
    default:
      return tr.type;
  }
}

export function actionLabel(a) {
  a = normalizeAction(a);
  if (!a || !a.type) return "";
  switch (a.type) {
    case "set_control":
      return (a.entityId || "?") + "=" + (a.value || "");
    case "launch_app":
      return a.packageName || "?";
    case "delay_ms":
      return (a.ms || 0) + "ms";
    case "set_scene":
      return "scene:" + (a.sceneId || "?") + " " + (a.active === true ? "on" : a.active === false ? "off" : "toggle");
    case "run_routine":
      return "routine:" + (a.routineId || "?");
    case "plugin": {
      const p = a.params || {};
      return (
        pluginDisplayName(a.pluginId) +
        " " +
        (a.action || "?") +
        (p.domain || p.service ? " " + (p.domain || "") + "." + (p.service || "") : "")
      );
    }
    default:
      return a.type;
  }
}

// --- options ------------------------------------------------------------------

export function triggerTypeOptions() {
  const opts = [
    { value: "boot", label: t("shortcuts.trigger.boot", "Boot") },
    { value: "screen", label: t("shortcuts.trigger.screen", "Screen") },
    { value: "gear", label: t("shortcuts.trigger.gear", "Gear") },
    { value: "wheel_key", label: t("shortcuts.trigger.wheel", "Wheel key") },
    { value: "wifi_ssid", label: t("shortcuts.trigger.wifi", "Wi‑Fi SSID") },
    { value: "entity_state", label: t("shortcuts.trigger.entity_state", "Entity") },
    { value: "ui_card", label: t("shortcuts.trigger.ui_card", "UI card") },
  ];
  if (configuredPlugins().length) opts.push({ value: "plugin", label: t("shortcuts.trigger.plugin", "Plugin") });
  return opts;
}

export function actionTypeOptions() {
  const opts = [
    { value: "set_control", label: t("shortcuts.action.set_control", "Set control") },
    { value: "set_scene", label: t("shortcuts.action.set_scene", "Set scene") },
    { value: "run_routine", label: t("shortcuts.action.run_routine", "Run routine") },
    { value: "launch_app", label: t("shortcuts.action.launch_app", "Launch app") },
    { value: "delay_ms", label: t("shortcuts.action.delay", "Delay") },
  ];
  if (configuredPlugins().length) opts.push({ value: "plugin", label: t("shortcuts.action.plugin", "Plugin") });
  return opts;
}

export function conditionTypeOptions() {
  return [
    { value: "entity_equals", label: t("shortcuts.condition.entity_equals", "Entity equals") },
    { value: "gear_equals", label: t("shortcuts.condition.gear_equals", "Gear equals") },
    { value: "wifi_ssid", label: t("shortcuts.condition.wifi_ssid", "Wi‑Fi SSID") },
  ];
}

export function screenStateOptions() {
  return [
    { value: "on", label: t("shortcuts.trigger.screen.on", "On") },
    { value: "off", label: t("shortcuts.trigger.screen.off", "Off") },
  ];
}

export function wheelKeyOptions() {
  return (shortcuts.wheelKeys || []).map(function (k) {
    return { value: k, label: wheelKeyLabel(k) };
  });
}

export function gearOptions() {
  return [
    { value: "4", label: t("opt.gear.4", "P") },
    { value: "2", label: t("opt.gear.2", "R") },
    { value: "1", label: t("opt.gear.1", "N") },
    { value: "8", label: t("opt.gear.8", "D") },
  ];
}

export function uiCardGroupOptions() {
  return [
    { value: "assistant", label: t("section.assistant.title", "Assistant") },
    { value: "controls", label: t("section.controls.title", "Controls") },
    { value: "home", label: t("section.home.title", "Home") },
    { value: "energy", label: t("section.energy.title", "Energy") },
    { value: "drive", label: t("section.drive.title", "Drive") },
    { value: "lights", label: t("section.lights.title", "Lights") },
  ];
}

export function sceneActiveOptions() {
  return [
    { value: "on", label: t("scenes.active.on", "On") },
    { value: "off", label: t("scenes.active.off", "Off") },
    { value: "toggle", label: t("scenes.active.toggle", "Toggle") },
  ];
}

// --- blanks -------------------------------------------------------------------

export function blankConditionForType(next) {
  if (next === "gear_equals") return { type: "gear_equals", gear: 4 };
  if (next === "wifi_ssid") return { type: "wifi_ssid", ssid: "", contains: false };
  return { type: "entity_equals", entityId: "", value: "" };
}

export function blankActionForType(next) {
  /** @type {Record<string, any>} */
  const blank = { type: next };
  if (next === "delay_ms") blank.ms = 500;
  else if (next === "set_control") {
    blank.entityId = "";
    blank.value = "";
  } else if (next === "set_scene") {
    blank.sceneId = (shortcuts.scenes[0] && shortcuts.scenes[0].id) || "";
    blank.active = true;
  } else if (next === "run_routine") {
    blank.routineId = (shortcuts.routines[0] && shortcuts.routines[0].id) || "";
  } else if (next === "launch_app") {
    blank.packageName = "";
  } else if (next === "plugin") {
    const pid = (pluginSelectOptions()[0] || {}).value || "";
    blank.pluginId = pid;
    blank.action = ((pid && pluginContributionOptions(pid, "action")[0]) || {}).value || "";
    blank.params = {};
  }
  return blank;
}

export function blankTriggerForType(next) {
  if (next === "wheel_key") return { type: "wheel_key", key: "custom", longPress: false };
  if (next === "wifi_ssid") return { type: "wifi_ssid", ssid: "" };
  if (next === "entity_state") return { type: "entity_state", entityId: "", value: "" };
  if (next === "gear") return { type: "gear", gear: 4 };
  if (next === "screen") return { type: "screen", on: true };
  if (next === "ui_card") return { type: "ui_card", group: "assistant" };
  if (next === "plugin") {
    const pid = (pluginSelectOptions()[0] || {}).value || "";
    return {
      type: "plugin",
      pluginId: pid,
      trigger: ((pid && pluginContributionOptions(pid, "trigger")[0]) || {}).value || "",
      params: {},
    };
  }
  return { type: next };
}

// --- entities -----------------------------------------------------------------

const readable = computed(() => {
  const byId = {};
  catalog.entities.forEach(function (e) {
    if (e && e.id) byId[e.id] = e;
  });
  catalog.controls.forEach(function (c) {
    if (c && c.id && !byId[c.id]) byId[c.id] = c;
  });
  return Object.keys(byId)
    .sort()
    .map((id) => byId[id]);
});
const writable = computed(() => catalog.controls.filter((c) => c.writable !== false));
const toOptions = (list) => list.map((e) => ({ value: e.id, label: entityLabel(e) }));
const readableOptions = computed(() => toOptions(readable.get()));
const writableOptions = computed(() => toOptions(writable.get()));

/** Readable entities for condition / entity_state pickers (includes sensors & trackers). */
export function readableEntities() {
  return readable.get();
}

export function writableControls() {
  return writable.get();
}

/** Picker options for [readableEntities] / [writableControls], rebuilt only when the catalog or strings change. */
export function readableEntityOptions() {
  return readableOptions.get();
}

export function writableControlOptions() {
  return writableOptions.get();
}

export function entityById(id) {
  return id ? findControl(id) || null : null;
}

/** Keep [current] when it is one of the entity's values, else its first option. */
export function pickDefaultValue(entity, current) {
  const opts = valueOptionsForControl(entity);
  if (!opts || !opts.length) return current != null ? current : "";
  const cur = current != null ? String(current) : "";
  if (cur && opts.some((o) => String(o.value) === cur)) return cur;
  return String(opts[0].value);
}

/**
 * Control-action services (`GET /api/services`, Home Assistant style): which services apply
 * to an entity, their labels, and each field's options, range and starting value. No rendering.
 */
import { catalog, shortcuts } from "../../store.js";
import { computed } from "../../signals.js";
import { t, entityLabel } from "../../i18n.js";
import { valueOptionsForControl } from "./fields.js";

function humanize(s) {
  const text = String(s || "").replace(/_/g, " ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** True once the car listed its services. */
export function servicesAvailable() {
  return Array.isArray(shortcuts.services);
}

function domainServices(domain) {
  const list = Array.isArray(shortcuts.services) ? shortcuts.services : [];
  const d = list.find((x) => x.domain === domain);
  return (d && d.services) || [];
}

/** Entity field, else attribute (`min` / `min_temp`, `options` / `hvac_modes`, …). */
function entityProp(entity, key) {
  if (!entity || !key) return undefined;
  if (entity[key] != null) return entity[key];
  return entity.attributes ? entity.attributes[key] : undefined;
}

function hasAttr(entity, key) {
  return !!(entity && entity.attributes && entity.attributes[key] != null);
}

/** Services of the entity's domain whose required attribute it exposes. */
export function servicesForEntity(entity) {
  if (!entity) return [];
  return domainServices(entity.domain).filter((s) => !s.requires || hasAttr(entity, s.requires));
}

/** @param {string} id `domain.service` */
export function serviceDef(id) {
  const dot = (id || "").indexOf(".");
  if (dot < 0) return null;
  const name = id.slice(dot + 1);
  return domainServices(id.slice(0, dot)).find((s) => s.service === name) || null;
}

/** @param {string} id `domain.service` */
export function serviceLabel(id) {
  const name = (id || "").slice((id || "").indexOf(".") + 1);
  return t("service." + id, humanize(name));
}

export function fieldLabel(field) {
  return t("service.field." + field.key, humanize(field.key));
}

/** Fields that apply to [entity]; all of them while the entity is unknown (car asleep, hub offline). */
export function fieldsFor(def, entity) {
  return ((def && def.fields) || []).filter((f) => !f.requires || !entity || hasAttr(entity, f.requires));
}

function num(v) {
  return v == null || v === "" || isNaN(Number(v)) ? undefined : Number(v);
}

/** @returns {{ min?: number, max?: number, step?: number }} */
export function fieldRange(field, entity) {
  return {
    min: num(field.min != null ? field.min : entityProp(entity, field.minFrom)),
    max: num(field.max != null ? field.max : entityProp(entity, field.maxFrom)),
    step: num(field.step != null ? field.step : entityProp(entity, field.stepFrom)),
  };
}

/** @returns {{ value: string, label?: string, labelKey?: string }[]} */
export function fieldOptions(field, entity) {
  if (field.optionsFrom === "options") return valueOptionsForControl(entity) || [];
  const list = entityProp(entity, field.optionsFrom);
  if (!Array.isArray(list)) return [];
  return list.map((v) => ({
    value: String(v),
    label: field.optionLabelPrefix ? t(field.optionLabelPrefix + v, String(v)) : String(v),
  }));
}

/** Starting data for a newly picked service: defaults, then the entity's current values for required fields. */
export function initialData(def, entity) {
  /** @type {Record<string, any>} */
  const data = {};
  for (const f of fieldsFor(def, entity)) {
    if (f.default != null) {
      data[f.key] = f.default;
      continue;
    }
    if (!f.required) continue;
    const current = entityProp(entity, f.key);
    if (f.type === "number") {
      const v = num(current) ?? fieldRange(f, entity).min;
      if (v != null) data[f.key] = v;
    } else if (f.type === "select") {
      const opts = fieldOptions(f, entity);
      const hit = current != null && opts.find((o) => String(o.value) === String(current));
      if (hit || opts.length) data[f.key] = (hit || opts[0]).value;
    } else if (current != null) {
      data[f.key] = String(current);
    }
  }
  return data;
}

/**
 * Entities that have services: everything in the catalog (HU media, radios and volumes
 * included) except rows that read fine but refuse writes.
 */
const serviceEntities = computed(() => {
  const byId = new Map();
  for (const e of (catalog.entities || []).concat(catalog.controls || [])) {
    if (e && e.id && !byId.has(e.id)) byId.set(e.id, e);
  }
  const ready = Array.isArray(shortcuts.services);
  return [...byId.values()]
    .filter((e) => ready && servicesForEntity(e).length && !(e.writable === false && e.status === "ok"))
    .sort((a, b) => entityLabel(a).localeCompare(entityLabel(b)));
});

/** Picker options for service targets; keeps [currentId] listed even when it no longer qualifies. */
export function serviceEntityOptions(currentId) {
  const list = serviceEntities.get();
  const opts = list.map((e) => ({ value: e.id, label: entityLabel(e) }));
  if (currentId && !list.some((e) => e.id === currentId)) opts.unshift({ value: currentId, label: currentId });
  return opts;
}

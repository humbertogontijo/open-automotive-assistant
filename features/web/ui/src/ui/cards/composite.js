import { html, nothing } from "lit";
import { t, valueLabel, hasKey } from "../../i18n.js";
import { i18n } from "../../store.js";
import { controlShell } from "./shared.js";
import { isOn } from "../../format.js";
import { segmentToggle, choiceSelect } from "./choice.js";
import { OaaCard } from "./card-element.js";
import { toggleGrid } from "./toggle-button.js";

const META_ATTRS = {
  friendly_name: 1,
  device_class: 1,
  unit_of_measurement: 1,
  icon: 1,
  group: 1,
  input: 1,
  stale: 1,
  history: 1,
  writable: 1,
  composite: 1,
  update: 1,
  virtual: 1,
  virtual_kind: 1,
  open: 1,
  status: 1,
  move: 1,
};

/** Domain → primary choice attribute (segmented control). */
const PRIMARY_CHOICE = {
  drivetrain: "mode",
  steering: "assist_level",
  chassis: "brake_pedal",
};

/** Attribute → i18n valueMaps id for enum labels / secondary options. */
const ATTR_VALUE_MAP = {
  gear: "gear",
  mode: "drive_mode",
  regen: "regen",
  battery_mode: "battery_mode",
  brake_pedal: "brake_pedal",
  assist_level: "steer_assist_level",
  custom_key: "wheel_custom_key",
  parking_brake: "parking_brake",
  plug: "charge_plug",
};

const READ_ONLY = {
  gear: 1,
  parking_brake: 1,
  plug: 1,
};

const BOOL_ATTRS = {
  esc: 1,
  hdc: 1,
  auto_hold: 1,
  epb: 1,
  sync_drive_mode: 1,
  intelligent: 1,
  battery_hold: 1,
  battery_save: 1,
  snow: 1,
  ar: 1,
  active: 1,
  plug: 1,
  switch: 1,
  pre_now: 1,
  v2l: 1,
  v2v: 1,
  parking: 1,
  external_light: 1,
};

/** Attrs rendered as labeled choice rows (not raw status text). */
const CHOICE_ATTRS = {
  regen: 1,
  battery_mode: 1,
  custom_key: 1,
  brake_pedal: 1,
  assist_level: 1,
  mode: 1,
};

/**
 * Display order for generic composites: attributes arrive in no particular order, and the
 * main switch should lead. Unlisted attributes follow alphabetically.
 */
const ATTR_ORDER = [
  "switch", "active", "pre_now", "parking", "v2l", "v2v", "external_light", "snow", "ar",
  "plug", "current", "limit", "work_current", "work_voltage", "soc_max", "soc_min",
  "discharge_soc", "estimated_time", "energy", "percent", "hybrid_soc", "temp_c", "level_raw",
];

function byAttrOrder(a, b) {
  const ia = ATTR_ORDER.indexOf(a);
  const ib = ATTR_ORDER.indexOf(b);
  if (ia !== ib) return ia < 0 ? 1 : ib < 0 ? -1 : ia - ib;
  return a < b ? -1 : a > b ? 1 : 0;
}

function attrLabel(attr) {
  const fallback = attr.replace(/_/g, " ");
  return t("attr." + attr, fallback.charAt(0).toUpperCase() + fallback.slice(1));
}

function productAttrs(raw) {
  const out = {};
  Object.keys(raw || {}).forEach(function (k) {
    if (META_ATTRS[k]) return;
    if (raw[k] == null || raw[k] === "") return;
    out[k] = raw[k];
  });
  return out;
}

function attrDisplay(attr, raw) {
  const mapId = ATTR_VALUE_MAP[attr];
  if (mapId) {
    const mapped = valueLabel(mapId, raw);
    if (mapped && mapped !== String(raw)) return mapped;
  }
  if (BOOL_ATTRS[attr] || attr === "parking_brake") {
    return isOn(raw) ? t("common.on", "On") : t("common.off", "Off");
  }
  return String(raw);
}

/** Build choice options from a valueMap, preferring compact numeric keys. */
function optionsFromValueMap(mapId, current) {
  const map = i18n.valueMaps[mapId];
  if (!map) return [];
  const byLabel = {};
  Object.keys(map).forEach(function (k) {
    if (!/^-?\d+$/.test(k)) return;
    const n = Number(k);
    const labelKey = map[k];
    const cur = current != null && String(current) === k;
    const compact = n >= 0 && n < 0x10000;
    if (!compact && !cur) return;
    const prev = byLabel[labelKey];
    if (!prev || cur || (compact && Number(prev.value) >= 0x10000)) {
      byLabel[labelKey] = { value: k, labelKey: labelKey, label: t(labelKey, k) };
    }
  });
  const opts = Object.keys(byLabel).map(function (lk) {
    return byLabel[lk];
  });
  opts.sort(function (a, b) {
    return Number(a.value) - Number(b.value);
  });
  return opts;
}

function choiceOptions(c, attr) {
  if (attr === PRIMARY_CHOICE[c.domain] && c.options && c.options.length) {
    return c.options;
  }
  const mapId = ATTR_VALUE_MAP[attr];
  if (!mapId) return [];
  const attrs = c.attributes || {};
  return optionsFromValueMap(mapId, attrs[attr]);
}

function statRow(attr, raw) {
  const onClass = attr === "parking_brake" && isOn(raw) ? " is-on" : "";
  return html`
    <div class="composite-stat-row">
      <span class="k">${attrLabel(attr)}</span>
      <span class="v${onClass}">${attrDisplay(attr, raw)}</span>
    </div>
  `;
}

function labeledChoice(c, attr, locked, sendAttr, preferSelect) {
  const attrs = c.attributes || {};
  if (attrs[attr] == null || attrs[attr] === "") return nothing;
  const opts = choiceOptions(c, attr);
  if (!opts.length || READ_ONLY[attr]) return statRow(attr, attrs[attr]);
  const current = String(attrs[attr]);
  const onSelect = (v) => sendAttr(attr, v);
  return html`
    <div class="composite-field">
      <div class="composite-row-label">${attrLabel(attr)}</div>
      ${preferSelect || opts.length > 4
        ? choiceSelect({ options: opts, current, locked, onSelect })
        : segmentToggle({ options: opts, current, locked, onSelect })}
    </div>
  `;
}

function toggleHint(attr) {
  const key = "attr." + attr + ".hint";
  if (hasKey(key)) return t(key);
  const controlHints = {
    esc: "control.esc_sport.hint",
    hdc: "control.hdc.hint",
    auto_hold: "control.auto_hold.hint",
    epb: "control.epb.hint",
    sync_drive_mode: "control.steer_sync_drive_mode.hint",
    intelligent: "control.intelligent_steer.hint",
    battery_hold: "control.battery_hold.hint",
    battery_save: "control.battery_save.hint",
  };
  const ck = controlHints[attr];
  return ck && hasKey(ck) ? t(ck) : "";
}

/** Writable on/off attributes as toggle buttons; read-only ones belong in a stat row. */
function featureToggles(keys, attrs, locked, sendAttr, ariaLabel) {
  const present = keys.filter((k) => attrs[k] != null && attrs[k] !== "" && !READ_ONLY[k]);
  return toggleGrid(
    present.map((attr) => ({
      label: attrLabel(attr),
      on: isOn(attrs[attr]),
      disabled: locked,
      hint: toggleHint(attr),
      onToggle: (on) => sendAttr(attr, on ? "1" : "0"),
    })),
    ariaLabel || t("composite.features", "Features"),
  );
}

/** Gear letter for the header icon slot; long labels ("Unknown") stay out of it. */
function gearGlyph(attrs) {
  if (attrs.gear == null) return undefined;
  const text = attrDisplay("gear", attrs.gear);
  return text.length <= 2 ? { text, label: attrLabel("gear") + ": " + text } : undefined;
}

function drivetrainBody(c, attrs, locked, sendAttr) {
  return html`
    ${attrs.gear != null && !gearGlyph(attrs) ? statRow("gear", attrs.gear) : nothing}
    ${labeledChoice(c, "mode", locked, sendAttr)}
    ${labeledChoice(c, "regen", locked, sendAttr)}
    ${labeledChoice(c, "battery_mode", locked, sendAttr, true)}
    ${featureToggles(
      ["battery_hold", "battery_save"],
      attrs,
      locked,
      sendAttr,
      t("composite.battery", "Battery policy"),
    )}
  `;
}

function chassisBody(c, attrs, locked, sendAttr) {
  return html`
    ${labeledChoice(c, "brake_pedal", locked, sendAttr)}
    ${featureToggles(["esc", "hdc", "auto_hold", "epb"], attrs, locked, sendAttr, t("entity.chassis", "Chassis"))}
    ${labeledChoice(c, "parking_brake", locked, sendAttr)}
  `;
}

function steeringBody(c, attrs, locked, sendAttr) {
  return html`
    ${labeledChoice(c, "assist_level", locked, sendAttr)}
    ${featureToggles(["sync_drive_mode", "intelligent"], attrs, locked, sendAttr, t("entity.steering", "Steering"))}
    ${labeledChoice(c, "custom_key", locked, sendAttr, true)}
  `;
}

function genericBody(c, attrs, locked, sendAttr, domain) {
  const primaryKey = PRIMARY_CHOICE[domain];
  const keys = Object.keys(attrs).sort(byAttrOrder);
  const boolKeys = [];
  const choiceKeys = [];
  const statusKeys = [];

  keys.forEach(function (attr) {
    if (attr === primaryKey) return;
    if (BOOL_ATTRS[attr] && !READ_ONLY[attr]) boolKeys.push(attr);
    else if (CHOICE_ATTRS[attr] && choiceOptions(c, attr).length) choiceKeys.push(attr);
    else statusKeys.push(attr);
  });

  const primaryVal =
    primaryKey && attrs[primaryKey] != null ? attrs[primaryKey] : c.value;
  const primaryOpts =
    primaryKey && !READ_ONLY[primaryKey] ? choiceOptions(c, primaryKey) : [];
  const hasChoice = primaryKey && primaryOpts.length;

  return html`
    ${hasChoice
      ? segmentToggle({
          options: primaryOpts,
          current: primaryVal != null ? String(primaryVal) : null,
          locked: locked,
          onSelect: (v) => sendAttr(primaryKey, v),
        })
      : nothing}
    ${choiceKeys.map((attr) => labeledChoice(c, attr, locked, sendAttr))}
    ${featureToggles(boolKeys, attrs, locked, sendAttr)}
    ${statusKeys.length
      ? html`
          <dl class="composite-status">
            ${statusKeys.map(
              (attr) => html`
                <div class="composite-stat">
                  <dt>${attrLabel(attr)}</dt>
                  <dd>${attrDisplay(attr, attrs[attr])}</dd>
                </div>
              `,
            )}
          </dl>
        `
      : nothing}
  `;
}

class OaaCompositeCard extends OaaCard {
  renderCard(c) {
    const locked = this.locked;
    const attrs = productAttrs(c.attributes);
    const domain = c.domain || c.id;
    const sendAttr = (attr, value) => {
      if (!READ_ONLY[attr]) this.send(attr + ":" + value);
    };
    const body =
      domain === "drivetrain"
        ? drivetrainBody(c, attrs, locked, sendAttr)
        : domain === "chassis"
          ? chassisBody(c, attrs, locked, sendAttr)
          : domain === "steering"
            ? steeringBody(c, attrs, locked, sendAttr)
            : genericBody(c, attrs, locked, sendAttr, domain);
    return controlShell(c, {
      restore: this.restore,
      dense: true,
      cls: "composite-card",
      bodyCls: "composite-body",
      glyph: domain === "drivetrain" ? gearGlyph(attrs) : undefined,
      body,
    });
  }
}
customElements.define("oaa-composite-card", OaaCompositeCard);

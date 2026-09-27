import { loadCss } from "../load-css.js";
loadCss("/static/js/ui/cards/composite.css");

import { html, nothing } from "../../lit.js";
import { t, entityLabel, entityHint, valueLabel, hasKey } from "../../i18n.js";
import { setControl, setPersist } from "../../actions.js";
import { state } from "../../store.js";
import { icon, pinSnapshot, hideBtn, cardSpan, statusNote } from "./shared.js";
import { segmentToggle, choiceSelect } from "./choice.js";

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
};

const READ_ONLY = {
  gear: 1,
  parking_brake: 1,
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

function attrLabel(attr) {
  return t("attr." + attr, attr.replace(/_/g, " "));
}

function isOn(v) {
  return v === 1 || v === "1" || v === true || v === "true" || v === "on";
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
  const maps = (state.i18n && state.i18n.valueMaps) || {};
  const map = maps[mapId];
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

function cardShell(c, restore, locked, pinned, pinTitle, span, body) {
  return html`
    <div
      class="ctrl-card composite-card ${locked ? "locked" : ""} ${pinned ? "pinned" : ""}"
      data-card=${c.id}
      data-col-span=${span.cols}
      data-row-span=${span.rows}
    >
      <div class="ctrl-head">
        <div class="ctrl-icon">${icon(c.icon || c.id)}</div>
        <div class="ctrl-meta">
          <h3>${entityLabel(c)}</h3>
          ${entityHint(c) ? html`<p class="hint">${entityHint(c)}</p>` : nothing}
        </div>
        <div class="card-actions">
          ${hideBtn(c.id, restore)}
          ${restore
            ? nothing
            : html`
                <button
                  type="button"
                  class="pin-btn ${pinned ? "active" : ""}"
                  title=${pinTitle}
                  aria-label=${pinTitle}
                  aria-pressed=${pinned ? "true" : "false"}
                  ?disabled=${!pinned && (c.value == null || c.value === "")}
                  @click=${function (ev) {
                    ev.stopPropagation();
                    if (pinned) {
                      setPersist(c.id, { enabled: false });
                      return;
                    }
                    if (c.value == null || c.value === "") return;
                    setPersist(c.id, { enabled: true, value: String(c.value) });
                  }}
                >
                  ${icon("pin")}
                </button>`}
        </div>
      </div>
      <div class="ctrl-body composite-body">
        ${c.stale
          ? html`<div class="lock-note">${t("status.cached", "Último conhecido")}</div>`
          : locked && c.status && c.status !== "ok"
            ? html`<div class="lock-note">${statusNote(c)}</div>`
            : nothing}
        ${body}
      </div>
    </div>
  `;
}

function heroReadout(value, opts) {
  opts = opts || {};
  return html`
    <div class="composite-hero ${opts.className || ""}">
      <div class="composite-hero-readout">
        ${opts.label
          ? html`<span class="composite-row-label">${opts.label}</span>`
          : nothing}
        <span class="composite-hero-value">${value}</span>
      </div>
    </div>
  `;
}

function labeledChoice(c, attr, locked, sendAttr, preferSelect) {
  const attrs = c.attributes || {};
  if (attrs[attr] == null || attrs[attr] === "") return nothing;
  const opts = choiceOptions(c, attr);
  if (!opts.length || READ_ONLY[attr]) {
    const onClass = attr === "parking_brake" && isOn(attrs[attr]) ? " is-on" : "";
    return html`
      <div class="composite-stat-row">
        <span class="k">${attrLabel(attr)}</span>
        <span class="v${onClass}">${attrDisplay(attr, attrs[attr])}</span>
      </div>
    `;
  }
  const useSelect = preferSelect || opts.length > 4;
  return html`
    <div class="composite-field">
      <div class="composite-row-label">${attrLabel(attr)}</div>
      ${useSelect
        ? choiceSelect({
            options: opts,
            current: attrs[attr] != null ? String(attrs[attr]) : null,
            locked: locked,
            choiceKey: "cmp:" + c.id + ":" + attr,
            onSelect: function (v) {
              sendAttr(attr, v);
            },
          })
        : segmentToggle({
            options: opts,
            current: attrs[attr] != null ? String(attrs[attr]) : null,
            locked: locked,
            choiceKey: "cmp:" + c.id + ":" + attr,
            onSelect: function (v) {
              sendAttr(attr, v);
            },
          })}
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

function featureToggles(keys, attrs, locked, sendAttr, ariaLabel) {
  const present = keys.filter(function (k) {
    return attrs[k] != null && attrs[k] !== "";
  });
  if (!present.length) return nothing;
  return html`
    <div
      class="composite-toggles"
      role="group"
      aria-label=${ariaLabel || t("composite.features", "Features")}
    >
      ${present.map(function (attr) {
        const on = isOn(attrs[attr]);
        const hint = toggleHint(attr);
        return html`
          <button
            type="button"
            class="composite-toggle ${on ? "active" : ""}"
            ?disabled=${locked || READ_ONLY[attr]}
            aria-pressed=${on ? "true" : "false"}
            title=${hint || nothing}
            @click=${function () {
              if (READ_ONLY[attr]) return;
              sendAttr(attr, on ? "0" : "1");
            }}
          >
            ${attrLabel(attr)}
          </button>
        `;
      })}
    </div>
  `;
}

function drivetrainBody(c, attrs, locked, sendAttr) {
  const gear = attrs.gear;
  const gearLabel = gear != null ? attrDisplay("gear", gear) : "—";

  return html`
    ${heroReadout(gearLabel, {
      className: "is-gear",
      label: attrLabel("gear"),
    })}
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
    ${labeledChoice(c, "parking_brake", locked, sendAttr)}
    ${featureToggles(
      ["esc", "hdc", "auto_hold", "epb"],
      attrs,
      locked,
      sendAttr,
      t("entity.chassis", "Chassis"),
    )}
  `;
}

function steeringBody(c, attrs, locked, sendAttr) {
  return html`
    ${labeledChoice(c, "assist_level", locked, sendAttr)}
    ${featureToggles(
      ["sync_drive_mode", "intelligent"],
      attrs,
      locked,
      sendAttr,
      t("entity.steering", "Steering"),
    )}
    ${labeledChoice(c, "custom_key", locked, sendAttr, true)}
  `;
}

function genericBody(c, attrs, locked, sendAttr, domain) {
  const primaryKey = PRIMARY_CHOICE[domain];
  const keys = Object.keys(attrs);
  const boolKeys = [];
  const choiceKeys = [];
  const statusKeys = [];

  keys.forEach(function (attr) {
    if (attr === primaryKey) return;
    if (BOOL_ATTRS[attr]) boolKeys.push(attr);
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
      ? html`
          <div class="composite-primary">
            ${segmentToggle({
              options: primaryOpts,
              current: primaryVal != null ? String(primaryVal) : null,
              locked: locked,
              choiceKey: "cmp:" + c.id + ":" + primaryKey,
              onSelect: function (v) {
                sendAttr(primaryKey, v);
              },
            })}
          </div>
        `
      : nothing}

    ${choiceKeys.map(function (attr) {
      return labeledChoice(c, attr, locked, sendAttr);
    })}

    ${featureToggles(boolKeys, attrs, locked, sendAttr)}

    ${statusKeys.length
      ? html`
          <div class="composite-status">
            ${statusKeys.map(function (attr) {
              return html`
                <span class="composite-stat">
                  <span class="k">${attrLabel(attr)}</span>
                  <span class="v">${attrDisplay(attr, attrs[attr])}</span>
                </span>
              `;
            })}
          </div>
        `
      : nothing}
  `;
}

export function compositeCard(c, restore) {
  const locked = c.status !== "ok" && c.status !== "cached";
  const attrs = productAttrs(c.attributes);
  const domain = c.domain || c.id;
  const pinned = !!c.persistEnabled && pinSnapshot(c) != null;
  const pinTitle = pinned
    ? t("persist.unpin", "Unpin reboot value")
    : t("persist.pin", "Pin current value for reboot");
  const span = cardSpan(c);

  function sendAttr(attr, value) {
    if (locked || READ_ONLY[attr]) return;
    setControl(c.id, attr + ":" + value);
  }

  let body;
  if (domain === "drivetrain") {
    body = drivetrainBody(c, attrs, locked, sendAttr);
  } else if (domain === "chassis") {
    body = chassisBody(c, attrs, locked, sendAttr);
  } else if (domain === "steering") {
    body = steeringBody(c, attrs, locked, sendAttr);
  } else {
    body = genericBody(c, attrs, locked, sendAttr, domain);
  }

  return cardShell(c, restore, locked, pinned, pinTitle, span, body);
}

import { loadCss } from "./load-css.js";
loadCss("/static/js/ui/scene-editor.css");

import { html, nothing } from "../lit.js";
import { live } from "../lit.js";
import { repeat } from "../lit.js";
import { api } from "../api.js";
import { state, notify } from "../store.js";
import { prefCard, boolToggle, choiceSelect, boolOpts } from "./cards.js";
import { t, entityLabel, optionLabel } from "../i18n.js";

function writableControls() {
  return (state.controls || state.entities || []).filter(function (c) {
    return c && c.writable !== false && !c.virtual;
  });
}

function controlById(id) {
  if (!id) return null;
  const list = writableControls();
  for (var i = 0; i < list.length; i++) {
    if (list[i].id === id) return list[i];
  }
  return null;
}

/** Options for a control/entity value, preferring API i18n labels (same as cards). */
export function valueOptionsForControl(c) {
  if (!c) return null;
  if (c.input === "bool") {
    return boolOpts();
  }
  if (c.options && c.options.length) {
    return c.options.map(function (o) {
      return {
        value: String(o.value),
        label: optionLabel(o),
        labelKey: o.labelKey,
      };
    });
  }
  const domain = c.domain;
  if (domain === "device_tracker") {
    return [
      { value: "home", label: t("device_tracker.home", "Home") },
      { value: "not_home", label: t("device_tracker.not_home", "Away") },
    ];
  }
  if (domain === "media_player" || c.input === "media_player") {
    return [
      { value: "playing", label: t("media_player.playing", "Playing") },
      { value: "paused", label: t("media_player.paused", "Paused") },
      { value: "idle", label: t("media_player.idle", "Idle") },
    ];
  }
  if (c.domain === "climate" || c.id === "climate.cabin" || c.id === "climate" || c.input === "climate") {
    const modes = (c.attributes && c.attributes.hvac_modes) || [
      "off", "manual", "auto",
    ];
    return modes.map(function (m) {
      const key = String(m);
      return {
        value: key === "off" ? key : "hvac_mode:" + key,
        label: t("climate.mode." + key, key),
      };
    });
  }
  const maps = (state.i18n && state.i18n.valueMaps) || {};
  const mapId = c.valueMapId || null;
  if (mapId && maps[mapId]) {
    return Object.keys(maps[mapId])
      .map(function (k) {
        return {
          value: String(k),
          label: t(maps[mapId][k], String(k)),
        };
      })
      .sort(function (a, b) {
        const na = parseInt(a.value, 10);
        const nb = parseInt(b.value, 10);
        if (!isNaN(na) && !isNaN(nb)) return na - nb;
        return a.value < b.value ? -1 : a.value > b.value ? 1 : 0;
      });
  }
  return null;
}

/**
 * Value editor: choice select when mapped options exist, else free-text field.
 * @param {object} opts
 * @param {object|null} [opts.entity] - entity/control row (for options)
 * @param {*} opts.current
 * @param {string} opts.choiceKey
 * @param {function} opts.onSelect
 * @param {string} [opts.placeholder]
 * @param {string} [opts.inputStyle]
 */
export function entityValueField(opts) {
  const options = opts.options != null
    ? opts.options
    : valueOptionsForControl(opts.entity);
  const current = opts.current != null ? String(opts.current) : "";
  if (options && options.length) {
    return choiceSelect({
      options: options,
      current: current,
      choiceKey: opts.choiceKey,
      onSelect: function (next) {
        opts.onSelect(next);
      },
    });
  }
  return html`
    <input
      class="field scene-editor-field tight"
      placeholder=${opts.placeholder || ""}
      .value=${live(current)}
      @input=${function (ev) {
        opts.onSelect(ev.target.value);
      }}
    />
  `;
}

function blankTarget() {
  return {
    entityId: "",
    onValue: "1",
    off: { policy: "restore" },
  };
}

/**
 * @param {object} opts
 * @param {object} opts.edit - scene draft
 * @param {function} opts.onChange - mutate + notify
 * @param {function} opts.onSave
 * @param {function} opts.onCancel
 * @param {string} [opts.title]
 */
export function sceneEditorCard(opts) {
  const edit = opts.edit || {};
  const targets = edit.targets || [];
  const controls = writableControls();

  return prefCard({
    icon: edit.icon || "climate",
    title:
      opts.title ||
      (edit.id
        ? t("scenes.edit", "Edit scene")
        : t("scenes.new", "New scene")),
    body: html`
      <label class="hint">${t("shortcuts.name", "Name")}</label>
      <input
        class="field scene-editor-field"
        .value=${live(edit.name || "")}
        @input=${function (ev) {
          edit.name = ev.target.value;
          opts.onChange && opts.onChange();
        }}
      />
      <div class="scene-editor-enabled">
        ${boolToggle(edit.enabled !== false, function (val) {
          edit.enabled = val === "1";
          opts.onChange && opts.onChange();
          notify();
        })}
      </div>
      <h3 class="scene-editor-section-title">${t("scenes.targets", "Targets")}</h3>
      <div>
        ${repeat(
          targets,
          function (_, idx) {
            return "st-" + idx;
          },
          function (raw, i) {
            return targetRow(edit, raw, i, controls, opts);
          },
        )}
      </div>
      <button
        class="btn scene-editor-add"
        type="button"
        @click=${function () {
          edit.targets = (edit.targets || []).concat([blankTarget()]);
          opts.onChange && opts.onChange();
          notify();
        }}
      >
        ${t("scenes.add_target", "Add target")}
      </button>
      <div class="row scene-editor-actions">
        <button
          class="btn primary"
          type="button"
          @click=${function () {
            opts.onSave && opts.onSave();
          }}
        >
          ${t("shortcuts.save", "Save")}
        </button>
        <button
          class="btn ghost"
          type="button"
          @click=${function () {
            opts.onCancel && opts.onCancel();
          }}
        >
          ${t("shortcuts.cancel", "Cancel")}
        </button>
      </div>
    `,
  });
}

function targetRow(edit, raw, i, controls, opts) {
  const trow = raw || blankTarget();
  const off = trow.off || { policy: "restore" };
  const policy = off.policy === "set" ? "set" : "restore";
  const ctrl = controlById(trow.entityId);
  const valueOpts = valueOptionsForControl(ctrl);
  return html`
    <div class="shortcut-action">
      ${choiceSelect({
        options: controls.map(function (c) {
          return { value: c.id, label: entityLabel(c) };
        }),
        current: trow.entityId || "",
        choiceKey: "scene-target-entity-" + i,
        searchable: true,
        onSelect: function (next) {
          edit.targets[i].entityId = next;
          const c = controlById(next);
          const optsFor = valueOptionsForControl(c);
          if (optsFor && optsFor.length && !optsFor.some(function (o) {
            return String(o.value) === String(edit.targets[i].onValue);
          })) {
            edit.targets[i].onValue = String(optsFor[0].value);
          }
          opts.onChange && opts.onChange();
          notify();
        },
      })}
      <label class="hint">${t("scenes.on_value", "On value")}</label>
      <div class="field-gap">
        ${entityValueField({
          options: valueOpts,
          current: trow.onValue,
          choiceKey: "scene-target-on-" + i,
          onSelect: function (next) {
            edit.targets[i].onValue = next;
            opts.onChange && opts.onChange();
            notify();
          },
        })}
      </div>
      <label class="hint">${t("scenes.off_policy", "Off")}</label>
      <div class="field-gap">
        ${choiceSelect({
          options: [
            { value: "restore", label: t("scenes.off.restore", "Restore snapshot") },
            { value: "set", label: t("scenes.off.set", "Set value") },
          ],
          current: policy,
          choiceKey: "scene-target-off-" + i,
          onSelect: function (next) {
            if (next === "set") {
              const def =
                (valueOpts && valueOpts[0] && String(valueOpts[0].value)) || "0";
              edit.targets[i].off = {
                policy: "set",
                value: (edit.targets[i].off && edit.targets[i].off.value) || def,
              };
            } else {
              edit.targets[i].off = { policy: "restore" };
            }
            opts.onChange && opts.onChange();
            notify();
          },
        })}
      </div>
      ${policy === "set"
        ? html`
            <label class="hint">${t("scenes.off_value", "Off value")}</label>
            <div class="field-gap">
              ${entityValueField({
                options: valueOpts,
                current: off.value != null ? off.value : "0",
                choiceKey: "scene-target-offval-" + i,
                onSelect: function (next) {
                  edit.targets[i].off = { policy: "set", value: next };
                  opts.onChange && opts.onChange();
                  notify();
                },
              })}
            </div>
          `
        : nothing}
      <button
        class="btn ghost btn-remove"
        type="button"
        @click=${function () {
          edit.targets.splice(i, 1);
          opts.onChange && opts.onChange();
          notify();
        }}
      >
        ×
      </button>
    </div>
  `;
}

export async function saveScene(edit) {
  const body = {
    id: edit.id || undefined,
    name: (edit.name || "").trim() || "Scene",
    icon: edit.icon || "climate",
    enabled: edit.enabled !== false,
    targets: (edit.targets || [])
      .filter(function (x) {
        return x && x.entityId;
      })
      .slice(0, 16)
      .map(function (x) {
        return {
          entityId: x.entityId,
          onValue: x.onValue != null ? String(x.onValue) : "0",
          off:
            x.off && x.off.policy === "set"
              ? { policy: "set", value: String(x.off.value != null ? x.off.value : "0") }
              : { policy: "restore" },
        };
      }),
  };
  const res = await api("/api/scenes", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return res;
}

export function blankScene() {
  return {
    name: "",
    enabled: true,
    icon: "climate",
    targets: [blankTarget()],
  };
}

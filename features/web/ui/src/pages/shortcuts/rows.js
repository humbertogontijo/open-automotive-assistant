/**
 * Trigger, condition and action rows. Each renders one entry of the editor's draft and
 * mutates it in place, then asks the editor to re-render.
 */
import { html, nothing } from "lit";
import { live } from "lit/directives/live.js";
import { repeat } from "lit/directives/repeat.js";
import { shortcuts } from "../../store.js";
import { segmentToggle, choiceSelect } from "../../ui/cards/choice.js";
import { t } from "../../i18n.js";
import { ensureApps } from "../../shortcuts-data.js";
import { entityValueField, removeRowButton } from "./fields.js";
import {
  actionTypeOptions,
  blankActionForType,
  blankConditionForType,
  blankTriggerForType,
  conditionTypeOptions,
  entityById,
  gearOptions,
  normalizeCondition,
  normalizeTrigger,
  normalizeAction,
  pickDefaultValue,
  pluginContributionOptions,
  pluginParamKeys,
  pluginSelectOptions,
  readableEntityOptions,
  sceneActiveOptions,
  screenStateOptions,
  triggerTypeOptions,
  uiCardGroupOptions,
  wheelKeyOptions,
  writableControlOptions,
} from "./model.js";

/** @typedef {import("./fields.js").OaaDraftEditor} Editor */

/**
 * @param {Editor} ed
 * @param {any} row
 * @param {"action" | "trigger"} kind
 */
function pluginFields(ed, row, kind) {
  const pluginOpts = pluginSelectOptions();
  const pluginId = row.pluginId || (pluginOpts[0] && pluginOpts[0].value) || "";
  const nameKey = kind === "action" ? "action" : "trigger";
  const nameOpts = pluginContributionOptions(pluginId, kind);
  const name = row[nameKey] || (nameOpts[0] && nameOpts[0].value) || "";
  const params = row.params || {};
  const keys = pluginParamKeys(pluginId, kind, name);
  const useKeys = keys.length ? keys : Object.keys(params);
  return html`
    ${choiceSelect({
      options: pluginOpts,
      current: pluginId,
      onSelect: (next) => {
        row.pluginId = next;
        row[nameKey] = (pluginContributionOptions(next, kind)[0] || {}).value || "";
        row.params = {};
        ed.changed();
      },
    })}
    ${pluginId
      ? choiceSelect({
          options: nameOpts,
          current: name,
          onSelect: (next) => {
            row[nameKey] = next;
            row.params = row.params || {};
            ed.changed();
          },
        })
      : nothing}
    ${!pluginId || !name
      ? html`<p class="persist-note">${t("shortcuts.plugin.pick", "Choose a plugin and action")}</p>`
      : useKeys.map(
          (key) => html`<wa-input
            label=${key}
            .value=${live(params[key] != null ? String(params[key]) : "")}
            @input=${(ev) => {
              row.params = Object.assign({}, row.params, { [key]: ev.target.value });
            }}
          ></wa-input>`,
        )}
  `;
}

// --- triggers -------------------------------------------------------------------

/** @param {Editor} ed @param {any} tr @param {number} i */
function triggerFields(ed, tr, i) {
  const set = (patch) => {
    Object.assign(ed.draft.triggers[i], patch);
    ed.changed();
  };
  switch (tr.type) {
    case "wheel_key":
      return html`
        ${choiceSelect({ options: wheelKeyOptions(), current: tr.key || "custom", onSelect: (key) => set({ key }) })}
        <wa-checkbox .checked=${live(!!tr.longPress)} @change=${(ev) => set({ longPress: ev.target.checked })}
          >${t("shortcuts.trigger.long_press", "Long press")}</wa-checkbox
        >
      `;
    case "wifi_ssid":
      return html`<wa-input
        placeholder=${t("shortcuts.trigger.wifi.hint", "SSID (blank = any)")}
        .value=${live(tr.ssid || "")}
        @input=${(ev) => {
          ed.draft.triggers[i].ssid = ev.target.value;
        }}
      ></wa-input>`;
    case "entity_state":
      return html`
        ${choiceSelect({
          options: readableEntityOptions(),
          current: tr.entityId || "",
          searchable: true,
          onSelect: (entityId) => set({ entityId }),
        })}
        ${entityValueField({
          entity: entityById(tr.entityId),
          current: tr.value || "",
          placeholder: t("shortcuts.trigger.entity_value", "Value (optional)"),
          onSelect: (value) => set({ value }),
        })}
      `;
    case "gear":
      return choiceSelect({
        options: gearOptions(),
        current: tr.gear != null ? String(tr.gear) : "4",
        onSelect: (next) => set({ gear: parseInt(next, 10) }),
      });
    case "screen":
      return choiceSelect({
        options: screenStateOptions(),
        current: tr.on === false ? "off" : "on",
        onSelect: (next) => set({ on: next !== "off" }),
      });
    case "ui_card":
      return html`
        <p class="hint">
          ${t("shortcuts.trigger.ui_card.hint", "Shows a control card. With a Set scene action, the card toggles that scene.")}
        </p>
        ${choiceSelect({ options: uiCardGroupOptions(), current: tr.group || "assistant", onSelect: (group) => set({ group }) })}
      `;
    case "plugin":
      return pluginFields(ed, ed.draft.triggers[i], "trigger");
    default:
      return nothing;
  }
}

/** @param {Editor} ed */
export function triggersBlock(ed) {
  const d = ed.draft;
  const triggers = (d.triggers || []).map(normalizeTrigger);
  return html`
    ${ed.section(t("shortcuts.triggers", "Triggers"))}
    ${repeat(
      triggers,
      (_, i) => "t-" + i,
      (tr, i) => html`<div class="editor-row">
        ${segmentToggle({
          options: triggerTypeOptions(),
          current: tr.type || "boot",
          onSelect: (next) => {
            if (d.triggers[i].type === next) return;
            d.triggers[i] = blankTriggerForType(next);
            ed.changed();
          },
        })}
        ${triggerFields(ed, tr, i)}
        ${removeRowButton(() => {
          d.triggers.splice(i, 1);
          ed.changed();
        })}
      </div>`,
    )}
    ${ed.addButton(t("shortcuts.add_trigger", "Add trigger"), () => {
      d.triggers = (d.triggers || []).concat([{ type: "boot" }]);
      ed.changed();
    })}
  `;
}

// --- conditions -----------------------------------------------------------------

/** @param {Editor} ed @param {any} c @param {number} i */
function conditionFields(ed, c, i) {
  const set = (patch) => {
    Object.assign(ed.draft.conditions[i], patch);
    ed.changed();
  };
  if (c.type === "entity_equals") {
    return html`
      ${choiceSelect({
        options: readableEntityOptions(),
        current: c.entityId || "",
        searchable: true,
        onSelect: (entityId) => set({ entityId, value: pickDefaultValue(entityById(entityId), c.value) }),
      })}
      ${entityValueField({
        entity: entityById(c.entityId),
        current: c.value || "",
        placeholder: t("shortcuts.condition.value", "Value (e.g. home)"),
        onSelect: (value) => set({ value }),
      })}
    `;
  }
  if (c.type === "gear_equals") {
    return choiceSelect({
      options: gearOptions(),
      current: String(c.gear != null ? c.gear : 4),
      onSelect: (next) => set({ gear: parseInt(next, 10) }),
    });
  }
  if (c.type === "wifi_ssid") {
    return html`
      <wa-input
        placeholder=${t("shortcuts.trigger.wifi.hint", "SSID (blank = any)")}
        .value=${live(c.ssid || "")}
        @input=${(ev) => {
          ed.draft.conditions[i].ssid = ev.target.value;
        }}
      ></wa-input>
      <wa-checkbox .checked=${live(!!c.contains)} @change=${(ev) => set({ contains: ev.target.checked })}
        >${t("shortcuts.condition.contains", "Contains")}</wa-checkbox
      >
    `;
  }
  return nothing;
}

/** @param {Editor} ed */
export function conditionsBlock(ed) {
  const d = ed.draft;
  d.conditions = (d.conditions || []).map(normalizeCondition);
  return html`
    ${ed.section(
      t("shortcuts.conditions", "Conditions"),
      t("shortcuts.conditions.hint", "All conditions must pass (AND). Empty = always run."),
    )}
    ${repeat(
      d.conditions,
      (_, i) => "c-" + i,
      (c, i) => html`<div class="editor-row">
        ${segmentToggle({
          options: conditionTypeOptions(),
          current: c.type || "entity_equals",
          onSelect: (next) => {
            if (d.conditions[i].type === next) return;
            d.conditions[i] = blankConditionForType(next);
            ed.changed();
          },
        })}
        ${conditionFields(ed, c, i)}
        ${removeRowButton(() => {
          d.conditions.splice(i, 1);
          ed.changed();
        })}
      </div>`,
    )}
    ${ed.addButton(t("shortcuts.add_condition", "Add condition"), () => {
      d.conditions = d.conditions.concat([blankConditionForType("entity_equals")]);
      ed.changed();
    })}
  `;
}

// --- actions --------------------------------------------------------------------

/** Ask the page to open another editor (scene or routine) from inside this one. */
function openLinked(ed, kind, id) {
  ed.dispatchEvent(new CustomEvent("oaa-edit", { bubbles: true, detail: { kind, id: id || null } }));
}

/** @param {Editor} ed @param {any} a @param {number} i */
function actionFields(ed, a, i) {
  const set = (patch) => {
    Object.assign(ed.draft.actions[i], patch);
    ed.changed();
  };
  switch (a.type) {
    case "set_control":
      return html`
        ${choiceSelect({
          options: writableControlOptions(),
          current: a.entityId || "",
          searchable: true,
          onSelect: (entityId) => set({ entityId, value: pickDefaultValue(entityById(entityId), a.value) }),
        })}
        ${entityValueField({
          entity: entityById(a.entityId),
          current: a.value || "",
          placeholder: "value",
          onSelect: (value) => set({ value }),
        })}
      `;
    case "set_scene":
      return html`
        ${choiceSelect({
          options: shortcuts.scenes.map((s) => ({ value: s.id, label: s.name || s.id })),
          current: a.sceneId || "",
          onSelect: (sceneId) => set({ sceneId }),
        })}
        ${choiceSelect({
          options: sceneActiveOptions(),
          current: a.active === true ? "on" : a.active === false ? "off" : "toggle",
          onSelect: (next) => set({ active: next === "on" ? true : next === "off" ? false : null }),
        })}
        <wa-button size="small" appearance="plain" @click=${() => openLinked(ed, "scene", a.sceneId)}
          >${t("scenes.edit_inline", "Edit scene")}</wa-button
        >
      `;
    case "run_routine":
      return html`
        ${choiceSelect({
          options: shortcuts.routines.map((r) => ({ value: r.id, label: r.name || r.id })),
          current: a.routineId || "",
          onSelect: (routineId) => set({ routineId }),
        })}
        <wa-button size="small" appearance="plain" @click=${() => openLinked(ed, "routine", a.routineId)}
          >${t("routines.edit_inline", "Edit routine")}</wa-button
        >
      `;
    case "launch_app":
      ensureApps();
      return choiceSelect({
        options: shortcuts.apps.map((ap) => ({ value: ap.packageName, label: ap.label || ap.packageName })),
        current: a.packageName || "",
        onSelect: (packageName) => set({ packageName }),
      });
    case "plugin":
      return pluginFields(ed, ed.draft.actions[i], "action");
    default:
      return html`<wa-input
        type="number"
        min="0"
        max="5000"
        label=${t("shortcuts.action.delay", "Delay") + " (ms)"}
        .value=${live(a.ms != null ? String(a.ms) : "500")}
        @input=${(ev) => {
          const ms = parseInt(ev.target.value || "0", 10);
          ed.draft.actions[i].ms = isNaN(ms) ? 0 : ms;
        }}
      ></wa-input>`;
  }
}

/**
 * @param {Editor} ed
 * @param {any} blank row appended by "Add action"
 */
export function actionsBlock(ed, blank) {
  const d = ed.draft;
  const actions = (d.actions || []).map(normalizeAction);
  return html`
    ${ed.section(
      t("shortcuts.actions", "Actions"),
      t("shortcuts.actions.hint", "Use Set scene / Run routine to compose reusable blocks."),
    )}
    ${repeat(
      actions,
      (_, i) => "a-" + i,
      (a, i) => html`<div class="editor-row">
        ${segmentToggle({
          options: actionTypeOptions(),
          current: a.type || "delay_ms",
          onSelect: (next) => {
            if (d.actions[i].type === next) return;
            d.actions[i] = blankActionForType(next);
            ed.changed();
          },
        })}
        ${actionFields(ed, a, i)}
        ${removeRowButton(() => {
          d.actions.splice(i, 1);
          ed.changed();
        })}
      </div>`,
    )}
    ${ed.addButton(t("shortcuts.add_action", "Add action"), () => {
      d.actions = (d.actions || []).concat([Object.assign({}, blank)]);
      ed.changed();
    })}
  `;
}
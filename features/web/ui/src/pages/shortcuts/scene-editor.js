import { html, nothing } from "lit";
import { repeat } from "lit/directives/repeat.js";
import { t, entityLabel } from "../../i18n.js";
import { choiceSelect } from "../../ui/cards/choice.js";
import { OaaDraftEditor, entityValueField, removeRowButton, valueOptionsForControl } from "./fields.js";
import { writableControls } from "./model.js";
import { computed } from "../../signals.js";

function blankTarget() {
  return { entityId: "", onValue: "1", off: { policy: "restore" } };
}

export function blankScene() {
  return { name: "", enabled: true, icon: "climate", targets: [blankTarget()] };
}

const sceneControls = computed(() => writableControls().filter((c) => c && !c.virtual));
const sceneControlOptions = computed(() => sceneControls.get().map((c) => ({ value: c.id, label: entityLabel(c) })));

/** Scene: target values applied on activation, restored or set on deactivation. */
class OaaSceneEditor extends OaaDraftEditor {
  get endpoint() {
    return "/api/scenes";
  }

  body() {
    const d = this.draft;
    return {
      id: d.id || undefined,
      name: (d.name || "").trim() || "Scene",
      icon: d.icon || "climate",
      enabled: d.enabled !== false,
      targets: (d.targets || [])
        .filter((x) => x && x.entityId)
        .slice(0, 16)
        .map((x) => ({
          entityId: x.entityId,
          onValue: x.onValue != null ? String(x.onValue) : "0",
          off:
            x.off && x.off.policy === "set"
              ? { policy: "set", value: String(x.off.value != null ? x.off.value : "0") }
              : { policy: "restore" },
        })),
    };
  }

  targetRow(target, i, controls) {
    const d = this.draft;
    const off = target.off || { policy: "restore" };
    const policy = off.policy === "set" ? "set" : "restore";
    const valueOpts = valueOptionsForControl(controls.find((c) => c.id === target.entityId));
    const set = (patch) => {
      Object.assign(d.targets[i], patch);
      this.changed();
    };
    return html`<div class="editor-row">
      ${choiceSelect({
        options: sceneControlOptions.get(),
        current: target.entityId || "",
        searchable: true,
        onSelect: (entityId) => {
          const opts = valueOptionsForControl(controls.find((c) => c.id === entityId));
          const keep = !opts || !opts.length || opts.some((o) => String(o.value) === String(target.onValue));
          set({ entityId, onValue: keep ? target.onValue : String(opts[0].value) });
        },
      })}
      <label class="hint">${t("scenes.on_value", "On value")}</label>
      ${entityValueField({ options: valueOpts, current: target.onValue, onSelect: (onValue) => set({ onValue }) })}
      <label class="hint">${t("scenes.off_policy", "Off")}</label>
      ${choiceSelect({
        options: [
          { value: "restore", label: t("scenes.off.restore", "Restore snapshot") },
          { value: "set", label: t("scenes.off.set", "Set value") },
        ],
        current: policy,
        onSelect: (next) => {
          const def = (valueOpts && valueOpts[0] && String(valueOpts[0].value)) || "0";
          set({ off: next === "set" ? { policy: "set", value: off.value || def } : { policy: "restore" } });
        },
      })}
      ${policy === "set"
        ? html`<label class="hint">${t("scenes.off_value", "Off value")}</label>
            ${entityValueField({
              options: valueOpts,
              current: off.value != null ? off.value : "0",
              onSelect: (value) => set({ off: { policy: "set", value } }),
            })}`
        : nothing}
      ${removeRowButton(() => {
        d.targets.splice(i, 1);
        this.changed();
      })}
    </div>`;
  }

  render() {
    const d = this.draft;
    const controls = sceneControls.get();
    return this.shell({
      icon: d.icon || "climate",
      title: d.id ? t("scenes.edit", "Edit scene") : t("scenes.new", "New scene"),
      body: html`
        ${this.section(t("scenes.targets", "Targets"))}
        ${repeat(
          d.targets || [],
          (_, i) => "st-" + i,
          (target, i) => this.targetRow(target || blankTarget(), i, controls),
        )}
        ${this.addButton(t("scenes.add_target", "Add target"), () => {
          d.targets = (d.targets || []).concat([blankTarget()]);
          this.changed();
        })}
      `,
    });
  }
}
customElements.define("oaa-scene-editor", OaaSceneEditor);

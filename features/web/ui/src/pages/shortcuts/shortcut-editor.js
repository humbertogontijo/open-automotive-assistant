import { t } from "../../i18n.js";
import { OaaDraftEditor } from "./fields.js";
import { triggersBlock, conditionsBlock, actionsBlock } from "./rows.js";
import { toApiShortcut } from "./model.js";

export function blankShortcut() {
  return { name: "", enabled: true, actions: [{ type: "run_routine", routineId: "" }], triggers: [], conditions: [] };
}

/** Shortcut: triggers, conditions and up to ten actions. */
class OaaShortcutEditor extends OaaDraftEditor {
  get endpoint() {
    return "/api/shortcuts";
  }

  body() {
    const d = this.draft;
    return toApiShortcut({
      id: d.id || undefined,
      name: (d.name || "").trim() || "Shortcut",
      icon: d.icon || "drive",
      enabled: d.enabled !== false,
      actions: (d.actions || []).slice(0, 10),
      triggers: d.triggers || [],
      conditions: d.conditions || [],
    });
  }

  render() {
    return this.shell({
      icon: "drive",
      title: this.draft.id ? t("shortcuts.edit", "Edit shortcut") : t("shortcuts.new", "New shortcut"),
      body: [triggersBlock(this), conditionsBlock(this), actionsBlock(this, { type: "run_routine", routineId: "" })],
    });
  }
}
customElements.define("oaa-shortcut-editor", OaaShortcutEditor);

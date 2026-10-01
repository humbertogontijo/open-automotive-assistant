import { t } from "../../i18n.js";
import { OaaDraftEditor } from "./fields.js";
import { conditionsBlock, actionsBlock } from "./rows.js";
import { blankActionForType, toApiShortcut } from "./model.js";

export function blankRoutine() {
  return { name: "", enabled: true, icon: "drive", actions: [blankActionForType("service")], conditions: [] };
}

/** Routine: a reusable, trigger-less action sequence with optional conditions. */
class OaaRoutineEditor extends OaaDraftEditor {
  get endpoint() {
    return "/api/routines";
  }

  body() {
    const d = this.draft;
    const body = toApiShortcut({
      id: d.id || undefined,
      name: (d.name || "").trim() || "Routine",
      icon: d.icon || "drive",
      enabled: d.enabled !== false,
      actions: (d.actions || []).slice(0, 10),
      conditions: d.conditions || [],
    });
    delete body.triggers;
    return body;
  }

  render() {
    return this.shell({
      icon: this.draft.icon || "drive",
      title: this.draft.id ? t("routines.edit", "Edit routine") : t("routines.new", "New routine"),
      body: [conditionsBlock(this), actionsBlock(this, blankActionForType("service"))],
    });
  }
}
customElements.define("oaa-routine-editor", OaaRoutineEditor);

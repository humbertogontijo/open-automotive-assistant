import { t } from "../i18n.js";
import { pageGroup } from "./group.js";

export function pageAssistant() {
  return pageGroup(t("section.assistant.title", "Assistente"), "", "assistant");
}

import { html } from "lit";
import {
  entitiesByGroup,
  hiddenEntitiesByGroup,
  isShowingHidden,
} from "../store.js";
import { t } from "../i18n.js";
import { pageHead } from "../ui/cards/page-head.js";
import { familySections } from "./group.js";

export function pageControls() {
  const group = "controls";
  const viewing = isShowingHidden(group);
  const items = viewing ? hiddenEntitiesByGroup(group) : entitiesByGroup(group);

  return html`
    ${pageHead(
      t("section.controls.title", "Controles"),
      group,
      t("section.controls.sub", "Cabin climate, covers, and comfort"),
    )}
    ${familySections(items, viewing ? { restore: true } : null)}
  `;
}

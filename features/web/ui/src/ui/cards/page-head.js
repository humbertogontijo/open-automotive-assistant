import { html, nothing } from "lit";
import { t } from "../../i18n.js";
import { catalog, hiddenEntitiesByGroup, isShowingHidden } from "../../store.js";
import { icon } from "./shared.js";

/** Page title row with optional hidden-cards toggle for entity groups. */
export function pageHead(title, group, sub) {
  const hidden = group ? hiddenEntitiesByGroup(group) : [];
  const viewing = group ? isShowingHidden(group) : false;
  const toggle =
    group && hidden.length
      ? html`<wa-button
          class="hidden-toggle"
          appearance=${viewing ? "filled" : "outlined"}
          variant=${viewing ? "brand" : "neutral"}
          aria-pressed=${viewing ? "true" : "false"}
          @click=${() => catalog.$patch({ showHiddenGroup: viewing ? null : group })}
        >
          <span slot="start">${icon("hide")}</span>
          ${viewing ? t("entity.hidden.exit", "Show all") : t("entity.hidden.title", "Hidden cards") + " (" + hidden.length + ")"}
        </wa-button>`
      : nothing;

  return html`
    <div class="page-head">
      <h1>${title}</h1>
      ${toggle}
    </div>
    ${sub ? html`<p class="sub">${sub}</p>` : nothing}
    ${viewing ? html`<p class="sub">${t("entity.hidden.viewing", "Showing hidden cards only")}</p>` : nothing}
  `;
}

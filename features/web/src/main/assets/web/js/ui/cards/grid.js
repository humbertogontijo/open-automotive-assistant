import { html, repeat } from "../../lit.js";
import { t } from "../../i18n.js";
import { controlCard } from "./control.js";

/**
 * @param {Array} items
 * @param {{ restore?: boolean }} [opts]
 */
export function entityGrid(items, opts) {
  const restore = !!(opts && opts.restore);
  if (!items || !items.length) {
    return html`<p class="sub">
      ${restore
        ? t("entity.hidden.empty", "No hidden cards")
        : t("empty.controls", "Nenhum controle neste grupo")}
    </p>`;
  }
  return html`<div class="grid">
    ${repeat(
      items,
      function (c) {
        return c.id;
      },
      function (c) {
        return controlCard(c, restore);
      },
    )}
  </div>`;
}

export function entityLabel(type) {
  return t("entity." + (type || "extra"), type || "Extra");
}

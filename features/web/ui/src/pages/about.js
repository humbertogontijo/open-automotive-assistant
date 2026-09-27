import { html } from "lit";
import { session } from "../store.js";
import { t } from "../i18n.js";

export function pageAbout() {
  const s = session.status || /** @type {Record<string, any>} */ ({});
  const setup = session.setup || {};
  const rows = [
    ["integration", s.integration],
    ["variant", s.variant],
    ["webPort", s.webPort],
    ["accessMode", setup.accessMode],
  ].filter(function (r) {
    return r[1] != null && r[1] !== "";
  });

  return html`
    <h1>${t("nav.about", "Sobre")}</h1>
    <wa-card class="about-card">
      <p>${t("about.blurb", "Open Automotive Assistant — unified HU + web shell.")}</p>
      ${rows.map((r) => html`<div class="about-row"><span>${r[0]}</span><span class="mono">${String(r[1])}</span></div>`)}
    </wa-card>
  `;
}

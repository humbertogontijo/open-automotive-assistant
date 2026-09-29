import { html, nothing } from "lit";
import { OaaElement } from "../lit/oaa-element.js";
import { session } from "../store.js";
import { t } from "../i18n.js";
import { carVersionSkew } from "../compat.js";

/** Above a car's pages on a hub: its app and the hub's copy of this UI are different versions. */
class OaaVersionNotice extends OaaElement {
  render() {
    if (session.role !== "hub" || !session.selectedNodeId) return nothing;
    const car = session.status && session.status.version;
    const hub = session.hubVersion;
    const skew = carVersionSkew(car, hub);
    if (!skew) return nothing;
    const fill = (/** @type {string} */ s) => s.replace("{car}", car).replace("{hub}", hub);
    return skew === "newer"
      ? html`<wa-callout class="version-notice" variant="warning" size="s">
          ${fill(t("fleet.skew.car_newer", "This car runs app {car}, newer than the hub ({hub}). Update the hub so every page and card shows up."))}
        </wa-callout>`
      : html`<wa-callout class="version-notice" variant="neutral" size="s">
          ${fill(t("fleet.skew.car_older", "This car runs app {car}; the hub has {hub}. Pages the car does not have yet are hidden until you update it from the Cars page."))}
        </wa-callout>`;
  }
}
customElements.define("oaa-version-notice", OaaVersionNotice);

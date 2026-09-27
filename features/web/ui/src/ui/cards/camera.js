import { html } from "lit";
import { t, entityHint, entityValueLabel } from "../../i18n.js";
import { controlShell } from "./shared.js";
import { OaaCard } from "./card-element.js";

/**
 * Read-only camera entity card (role + idle/streaming).
 * Live mosaic remains on the /cameras DVR page.
 */
class OaaCameraCard extends OaaCard {
  renderCard(c) {
    const role =
      (c.attributes && c.attributes.role) ||
      (c.id && String(c.id).indexOf("camera.") === 0 ? String(c.id).slice("camera.".length) : "");
    const streaming = c.value === "streaming";
    const stateLabel =
      entityValueLabel(c) || (streaming ? t("camera.state.streaming", "Streaming") : t("camera.state.idle", "Idle"));
    return controlShell(c, {
      restore: this.restore,
      pinnable: false,
      cls: "camera-card " + (streaming ? "streaming" : ""),
      iconName: c.icon || "camera",
      hint: entityHint(c) || role,
      body: html`<div class="entity-value">${stateLabel}</div>`,
    });
  }
}
customElements.define("oaa-camera-card", OaaCameraCard);

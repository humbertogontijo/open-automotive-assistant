import { nothing } from "lit";
import { html as staticHtml, unsafeStatic } from "lit/static-html.js";
import { controlShell } from "./shared.js";
import { inputWidget } from "./input.js";
import { OaaCard } from "./card-element.js";
import "./sensor.js";
import "./media-player.js";
import "./climate.js";
import "./light.js";
import "./camera.js";
import "./cover.js";
import "./composite.js";

/**
 * Domain → dedicated card element. Lookup by `c.domain` (HA-shaped).
 * Domains not listed fall through to the atomic widget card.
 */
const DOMAIN_TAGS = {
  climate: "oaa-climate-card",
  light: "oaa-light-card",
  cover: "oaa-cover-card",
  media_player: "oaa-media-player-card",
  camera: "oaa-camera-card",
  drivetrain: "oaa-composite-card",
  chassis: "oaa-composite-card",
  steering: "oaa-composite-card",
  charger: "oaa-composite-card",
  ev_battery: "oaa-composite-card",
  hud: "oaa-composite-card",
  sensor: "oaa-sensor-card",
};

/** Single-value control (bool, choice, number, text, command) on the shared shell. */
class OaaControlCard extends OaaCard {
  renderCard(c) {
    return controlShell(c, {
      restore: this.restore,
      pinnable: !c.writeOnly && c.input !== "command",
      body: inputWidget(c),
    });
  }
}
customElements.define("oaa-control-card", OaaControlCard);

export function controlCard(c, restore) {
  if (c.domain === "sensor" && !restore && c.status !== "ok") return nothing;
  const tag = unsafeStatic(DOMAIN_TAGS[c.domain] || "oaa-control-card");
  return staticHtml`<${tag} .control=${c} ?restore=${!!restore}></${tag}>`;
}

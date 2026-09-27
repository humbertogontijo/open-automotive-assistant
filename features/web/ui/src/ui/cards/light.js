import { html, nothing } from "lit";
import { live } from "lit/directives/live.js";
import { t } from "../../i18n.js";
import { controlShell } from "./shared.js";
import { segmentToggle } from "./choice.js";
import { OaaCard } from "./card-element.js";
import { slider } from "./slider.js";

function attr(c, key) {
  const attrs = c.attributes || {};
  return attrs[key] != null && attrs[key] !== "" ? attrs[key] : null;
}

function lightOn(c) {
  const state = c.state != null ? c.state : c.value;
  if (state === "on" || state === "off") return state === "on";
  const bri = attr(c, "brightness");
  return bri != null && Number(bri) > 0;
}

class OaaLightCard extends OaaCard {
  renderCard(c) {
    const locked = this.locked;
    const on = lightOn(c);
    const briRaw = attr(c, "brightness");
    const brightness = briRaw != null && !isNaN(Number(briRaw)) ? Number(briRaw) : 0;
    const color = attr(c, "color");
    const min = c.min != null ? Number(c.min) : 0;
    const max = c.max != null ? Number(c.max) : 100;
    const step = c.step != null ? Number(c.step) : 1;
    const modes = c.options || [];

    const power = html`<wa-switch
      class="light-power"
      .checked=${live(on)}
      ?disabled=${locked}
      aria-label=${on ? t("common.on", "On") : t("common.off", "Off")}
      @change=${(ev) =>
        this.send(ev.target.checked ? "brightness:" + String(Math.max(step, Math.round(max * 0.5))) : "off")}
    ></wa-switch>`;

    return controlShell(c, {
      restore: this.restore,
      dense: true,
      cls: "light-card " + (on ? "is-on" : ""),
      bodyCls: "light-body",
      iconName: c.icon || "light",
      hint: on ? t("common.on", "On") : t("common.off", "Off"),
      body: html`
        ${modes.length
          ? segmentToggle({
              options: modes,
              current: color != null ? String(color) : null,
              locked,
              onSelect: (v) => this.send("color:" + v),
            })
          : nothing}
        ${slider({
          value: Math.round(brightness),
          min,
          max,
          step,
          suffix: "%",
          label: t("attr.brightness", "Brightness"),
          disabled: locked,
          lead: power,
          onCommit: (v) => this.send("brightness:" + v),
        })}
      `,
    });
  }
}
customElements.define("oaa-light-card", OaaLightCard);

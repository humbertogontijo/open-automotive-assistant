import { html, nothing } from "lit";
import { live } from "lit/directives/live.js";
import "@awesome.me/webawesome/dist/components/color-picker/color-picker.js";
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

/** HA `rgb_color` ([r, g, b] or "r,g,b") → `#rrggbb`. */
function rgbHex(raw) {
  const parts = Array.isArray(raw) ? raw : String(raw).split(",");
  if (parts.length !== 3) return null;
  return "#" + parts.map((p) => Math.max(0, Math.min(255, Number(p) | 0)).toString(16).padStart(2, "0")).join("");
}

function hexRgb(hex) {
  const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})/i.exec(String(hex || ""));
  return m ? [m[1], m[2], m[3]].map((h) => parseInt(h, 16)).join(",") : null;
}

class OaaLightCard extends OaaCard {
  renderCard(c) {
    const locked = this.locked;
    const on = lightOn(c);
    const briRaw = attr(c, "brightness");
    const brightness = briRaw != null && !isNaN(Number(briRaw)) ? Number(briRaw) : null;
    const effect = attr(c, "effect");
    const modes = c.options || [];
    const colorModes = attr(c, "supported_color_modes") || [];
    const hasBrightness = c.min != null && c.max != null && colorModes.some((m) => m !== "onoff");
    const min = Number(c.min);
    const max = Number(c.max);
    const rgb = attr(c, "rgb_color");
    const hex = rgb != null ? rgbHex(rgb) : null;

    const power = html`<wa-switch
      class="light-power"
      .checked=${live(on)}
      ?disabled=${locked}
      aria-label=${on ? t("common.on", "On") : t("common.off", "Off")}
      @change=${(ev) => this.send(ev.target.checked ? "on" : "off")}
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
              current: effect != null ? String(effect) : null,
              locked,
              onSelect: (v) => this.send("effect:" + v),
            })
          : nothing}
        ${hasBrightness
          ? slider({
              value: brightness != null ? brightness : min,
              min,
              max,
              step: c.step != null ? Number(c.step) : undefined,
              format: (v) => Math.round(((v - min) / (max - min)) * 100),
              suffix: "%",
              label: t("attr.brightness", "Brightness"),
              disabled: locked,
              lead: power,
              onCommit: (v) => this.send("brightness:" + v),
            })
          : power}
        ${colorModes.includes("rgb")
          ? html`<div class="light-color">
              <span class="light-color-label">${t("attr.color", "Color")}</span>
              <wa-color-picker
                size="small"
                format="hex"
                without-format-toggle
                label=${t("attr.color", "Color")}
                .value=${live(hex || "")}
                ?disabled=${locked}
                @change=${(ev) => {
                  const v = hexRgb(ev.target.value);
                  if (v) this.send("rgb_color:" + v);
                }}
              ></wa-color-picker>
            </div>`
          : nothing}
      `,
    });
  }
}
customElements.define("oaa-light-card", OaaLightCard);

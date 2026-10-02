import { html, nothing } from "lit";
import { t } from "../../i18n.js";
import { formatDisplayNumber } from "../../units.js";
import { displayUnit, controlShell } from "./shared.js";
import { segmentToggle, choiceSelect } from "./choice.js";
import { OaaCard } from "./card-element.js";
import { slider } from "./slider.js";
import { stepper } from "./number.js";
import { toggleButton } from "./toggle-button.js";

function climateAttr(c, snake) {
  const attrs = c.attributes || {};
  if (attrs[snake] != null && attrs[snake] !== "") return attrs[snake];
  if (c[snake] != null && c[snake] !== "") return c[snake];
  return null;
}

function num(v, fallback) {
  return v != null && v !== "" && !isNaN(Number(v)) ? Number(v) : fallback;
}

function climateModeLabel(mode) {
  const m = String(mode || "").toLowerCase();
  if (m === "off") return t("climate.mode.off", "Off");
  if (m === "auto") return t("climate.mode.auto", "Auto");
  if (m === "manual" || m === "on") return t("climate.mode.manual", "Manual");
  return mode || "—";
}

/** Prefer attributes.hvac_mode; unknown values fall back to off. */
function climateMode(c) {
  const fromAttr = climateAttr(c, "hvac_mode");
  if (fromAttr != null && fromAttr !== "") return String(fromAttr).toLowerCase();
  const raw = c.value != null && c.value !== "" ? c.value : c.state;
  if (raw == null || raw === "") return "off";
  const s = String(raw).toLowerCase();
  if (s === "off" || s === "auto" || s === "manual") return s;
  if (s === "on") return "manual";
  return "off";
}

function numbers(list) {
  return Array.isArray(list) ? list.map(Number).filter((n) => !isNaN(n)) : [];
}

/** Smallest gap between declared levels (slider step). */
function levelStep(levels) {
  const sorted = levels.slice().sort((a, b) => a - b);
  let step = Infinity;
  for (let i = 1; i < sorted.length; i++) step = Math.min(step, sorted[i] - sorted[i - 1]);
  return isFinite(step) && step > 0 ? step : undefined;
}

class OaaClimateCard extends OaaCard {
  renderCard(c) {
    const locked = this.locked;
    const mode = climateMode(c);
    const modesRaw = climateAttr(c, "hvac_modes");
    const modes = Array.isArray(modesRaw) ? modesRaw.map(String) : ["off", "manual", "auto"];
    const tempMin = num(climateAttr(c, "min_temp"), num(c.min, null));
    const tempMax = num(climateAttr(c, "max_temp"), num(c.max, null));
    const tempStep = num(climateAttr(c, "target_temp_step"), num(c.step, null));
    const hasTempRange = tempMin != null && tempMax != null && tempStep != null;
    const temp = num(climateAttr(c, "temperature"), null);
    const current = num(climateAttr(c, "current_temperature"), null);
    const fanModes = numbers(climateAttr(c, "fan_modes"));
    const fanMin = fanModes.length ? Math.min.apply(null, fanModes) : null;
    const fanMax = fanModes.length ? Math.max.apply(null, fanModes) : null;
    const fan = num(climateAttr(c, "fan_mode"), fanMin);
    const dirRaw = climateAttr(c, "fan_direction");
    const fanDirection = num(dirRaw, null) != null ? String(Number(dirRaw)) : null;
    const fanDirections = numbers(climateAttr(c, "fan_directions"));
    const acOn = Number(climateAttr(c, "ac") || 0) !== 0;
    const recircOn = Number(climateAttr(c, "recirc") || 0) !== 0;
    const unit = displayUnit(c) || "°C";

    const nudgeTemp = (delta) => {
      if (temp == null || !hasTempRange) return;
      const next = Math.min(tempMax, Math.max(tempMin, temp + delta));
      this.send("temperature:" + Math.round(next / tempStep) * tempStep);
    };

    return controlShell(c, {
      restore: this.restore,
      dense: true,
      cls: "climate-card " + (mode !== "off" ? "is-on" : ""),
      bodyCls: "climate-body",
      iconName: c.icon || "climate",
      hint:
        current != null
          ? t("climate.current", "Now") + " " + formatDisplayNumber(c.unitOfMeasurement, current, "sensor") + unit
          : undefined,
      pinValue: temp != null ? "hvac_mode:" + mode + ";temperature:" + temp : mode,
      body: html`
        ${segmentToggle({
          options: modes.map((m) => ({ value: m, label: climateModeLabel(m) })),
          current: mode,
          locked,
          onSelect: (v) => this.send(v === "off" ? v : "hvac_mode:" + v),
        })}

        <div role="group" aria-label=${t("control.hvac_temp", "Temperature")}>
          ${stepper({
            value: html`${temp != null ? formatDisplayNumber(c.unitOfMeasurement, temp, "float") : "—"}<span
                class="unit"
                >${unit}</span
              >`,
            locked: locked || temp == null || !hasTempRange,
            onMinus: () => nudgeTemp(-tempStep),
            onPlus: () => nudgeTemp(tempStep),
          })}
        </div>

        ${fanModes.length
          ? slider({
              value: fan,
              min: fanMin,
              max: fanMax,
              step: levelStep(fanModes),
              suffix: "/" + fanMax,
              label: t("control.hvac_fan", "Fan"),
              disabled: locked,
              icon: "fan",
              onCommit: (v) => this.send("fan_mode:" + Math.round(v)),
            })
          : nothing}

        <div class="climate-air-row" role="group" aria-label=${t("climate.toggles", "Climate options")}>
          ${fanDirections.length
            ? choiceSelect({
                options: fanDirections.map((d) => ({
                  value: String(d),
                  labelKey: "opt.hvac_fan_direction." + d,
                  label: String(d),
                })),
                current: fanDirection,
                locked,
                onSelect: (v) => this.send("fan_direction:" + v),
              })
            : nothing}
          ${toggleButton({
            label: t("control.hvac_ac", "A/C"),
            icon: "snow",
            iconOnly: true,
            on: acOn,
            disabled: locked,
            onToggle: (on) => this.send("ac:" + (on ? "1" : "0")),
          })}
          ${toggleButton({
            label: t("control.hvac_recirc", "Recirculation"),
            icon: "recirc",
            iconOnly: true,
            on: recircOn,
            disabled: locked,
            onToggle: (on) => this.send("recirc:" + (on ? "1" : "0")),
          })}
        </div>
      `,
    });
  }
}
customElements.define("oaa-climate-card", OaaClimateCard);

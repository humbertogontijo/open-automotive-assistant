import { html, nothing } from "lit";
import { fmt } from "../../format.js";
import { entityValueLabel } from "../../i18n.js";
import { formatDisplayNumber } from "../../units.js";
import { displayUnit, controlShell } from "./shared.js";
import { OaaCard } from "./card-element.js";

export function sensorDisplay(c) {
  const mapped = entityValueLabel(c);
  if (mapped && mapped !== String(c.value != null ? c.value : "")) return mapped;
  // Prefer mapped enum/binary labels; otherwise format numeric + unit.
  if (c.valueMapId || c.binary || (c.options && c.options.length)) {
    if (mapped) return mapped;
  }
  const n = parseFloat(c.value);
  if (!isNaN(n) && c.unitOfMeasurement) {
    return formatDisplayNumber(c.unitOfMeasurement, n, c.input || "sensor");
  }
  if (mapped) return mapped;
  return fmt(c.value);
}

class OaaSensorCard extends OaaCard {
  renderCard(c) {
    const unit = displayUnit(c);
    return controlShell(c, {
      restore: this.restore,
      pinnable: false,
      cls: "sensor-card",
      iconName: c.icon || "sensor",
      body: html`<div class="entity-value">
        ${sensorDisplay(c)}${unit ? html`<span class="unit">${unit}</span>` : nothing}
      </div>`,
    });
  }
}
customElements.define("oaa-sensor-card", OaaSensorCard);

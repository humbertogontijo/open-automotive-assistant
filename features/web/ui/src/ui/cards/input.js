import { html, nothing } from "lit";
import { live } from "lit/directives/live.js";
import { fmt } from "../../format.js";
import { t, entityValueLabel } from "../../i18n.js";
import { setControl } from "../../actions.js";
import { formatDisplayNumber } from "../../units.js";
import { displayUnit, pinSnapshot, pinChip } from "./shared.js";
import { boolToggle } from "./bool.js";
import { segmentToggle, commandButtons } from "./choice.js";
import { onStep, stepper } from "./number.js";

export function inputWidget(c) {
  const locked = c.status !== "ok" && c.status !== "cached";
  const val = c.value;
  const id = c.id;
  const input = c.input || "int";
  const pin = pinSnapshot(c);
  const write = (v) => setControl(id, v);

  if (input === "bool") return boolToggle(val, write, locked, pin, c);

  if (input === "choice") {
    return segmentToggle({ options: c.options || [], current: val, locked, pinnedVal: pin, onSelect: write });
  }

  if (input === "command") {
    return html`
      <div class="command-actions">
        ${!locked
          ? html`<div class="lock-note command-note">${t("status.write_only", "Write-only command")}</div>`
          : nothing}
        ${commandButtons({ options: c.options || [], locked, onSelect: write })}
      </div>
    `;
  }

  if (input === "int" || input === "float") {
    const step = c.step != null ? Number(c.step) : null;
    const num = parseFloat(val);
    const unitId = c.unitOfMeasurement || null;
    const unit = displayUnit(c);
    const display = isNaN(num) ? "—" : formatDisplayNumber(unitId, num, input);
    const pinNum = pin != null ? parseFloat(pin) : NaN;
    const match = pin != null && !isNaN(num) && !isNaN(pinNum) && num === pinNum;
    const pinLabel =
      pin == null || match ? "" : isNaN(pinNum) ? String(pin) : formatDisplayNumber(unitId, pinNum, input);
    return stepper({
      cls: pin == null ? "" : match ? "pin-match" : "pin-diff",
      locked: locked || step == null,
      value: html`${display}${unit && !isNaN(num) ? html`<span class="unit">${unit}</span>` : nothing}`,
      onMinus: () => onStep(id, -step),
      onPlus: () => onStep(id, step),
      after: pin != null && !match ? pinChip(pinLabel + (unit ? " " + unit : "")) : nothing,
    });
  }

  if (input === "text") {
    const match = pin != null && String(val || "") === String(pin);
    return html`
      <div class="text-input ${pin == null ? "" : match ? "pin-match" : "pin-diff"}">
        <wa-input
          .value=${live(val || "")}
          ?disabled=${locked}
          @change=${function (ev) {
            write(ev.target.value);
          }}
        ></wa-input>
        ${pin != null && !match ? pinChip(String(pin)) : nothing}
      </div>
    `;
  }

  return html`<span class="mono"
    >${entityValueLabel(c) ||
    (function () {
      const n = parseFloat(val);
      if (!isNaN(n) && c.unitOfMeasurement) {
        return formatDisplayNumber(c.unitOfMeasurement, n, input);
      }
      return fmt(val);
    })()}${displayUnit(c) ? " " + displayUnit(c) : ""}</span
  >`;
}

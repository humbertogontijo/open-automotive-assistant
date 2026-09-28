import { html, nothing } from "lit";
import { findControl } from "../../store.js";
import { setControl } from "../../actions.js";
import { t } from "../../i18n.js";
import { icon } from "../../icons.js";

export function onStep(id, delta) {
  const c = findControl(id);
  if (!c) return;
  const curRaw = c && c.value;
  let cur = parseFloat(curRaw);
  if (isNaN(cur)) {
    if (c.min == null) return;
    cur = Number(c.min);
  }
  // Step in vehicle-native units; display layer converts for the UI.
  let next = cur + delta;
  if (delta < 0 && c.min != null) next = Math.max(Number(c.min), next);
  if (delta > 0 && c.max != null) next = Math.min(Number(c.max), next);
  if (delta > 0 && next < cur) next = cur;
  if (delta < 0 && next > cur) next = cur;
  if (next === cur) return;
  if (c.input === "int" || (c.step && Number(c.step) === 1)) next = Math.round(next);
  else next = Math.round(next * 10) / 10;
  setControl(id, String(next));
}

/**
 * Minus / value / plus. Stays a stepper (not a number input): each press is one car write.
 * @param {{ value: any, locked?: boolean, cls?: string, onMinus: () => void, onPlus: () => void, after?: any }} o
 */
export function stepper(o) {
  return html`
    <div class="stepper ${o.cls || ""}">
      <wa-button
        appearance="filled"
                ?disabled=${!!o.locked}
        @click=${o.onMinus}
        >${icon("minus", t("common.decrease", "Decrease"))}</wa-button
      >
      <span class="val">${o.value}</span>
      <wa-button
        appearance="filled"
                ?disabled=${!!o.locked}
        @click=${o.onPlus}
        >${icon("plus", t("common.increase", "Increase"))}</wa-button
      >
      ${o.after || nothing}
    </div>
  `;
}

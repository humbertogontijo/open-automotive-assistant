import { html, nothing } from "lit";
import { ifDefined } from "lit/directives/if-defined.js";
import { OaaElement } from "../../lit/oaa-element.js";

/**
 * Readout plus wa-slider. The readout follows the thumb while dragging; `oaa-commit` fires
 * once on release, since each commit is one car write. The dragged value is held until the
 * car reports a new `value`, so the thumb doesn't snap back while the write is in flight.
 * Range comes from the entity (`min` / `max` / `step`); the UI never assumes one.
 * @fires oaa-commit - `detail.value` is the committed number
 */
export class OaaSlider extends OaaElement {
  static properties = {
    value: { type: Number },
    min: { type: Number },
    max: { type: Number },
    step: { type: Number },
    suffix: {},
    label: {},
    disabled: { type: Boolean },
    lead: { attribute: false },
    format: { attribute: false },
    drag: { state: true },
  };

  constructor() {
    super();
    this.value = 0;
    /** @type {number | undefined} */
    this.min = undefined;
    /** @type {number | undefined} */
    this.max = undefined;
    /** @type {number | undefined} */
    this.step = undefined;
    this.suffix = "";
    this.label = "";
    this.disabled = false;
    /** Content before the readout (e.g. a power switch). */
    this.lead = null;
    /** @type {((v: number) => any) | null} Readout formatter (value stays in entity units). */
    this.format = null;
    /** @type {number | null} */
    this.drag = null;
  }

  willUpdate(changed) {
    if (changed.has("value")) this.drag = null;
  }

  render() {
    const shown = this.drag != null ? this.drag : this.value;
    return html`
      <div class="slider-row ${this.lead ? "has-lead" : ""}" role="group" aria-label=${this.label || nothing}>
        ${this.lead || nothing}
        <span class="slider-readout">${this.format ? this.format(shown) : shown}<span class="unit">${this.suffix}</span></span>
        <wa-slider
          min=${ifDefined(this.min)}
          max=${ifDefined(this.max)}
          step=${ifDefined(this.step)}
          .value=${this.value}
          ?disabled=${this.disabled}
          label=${this.label}
          @input=${(ev) => {
            this.drag = Number(ev.target.value);
          }}
          @change=${(ev) => {
            const v = Number(ev.target.value);
            this.drag = v;
            this.dispatchEvent(new CustomEvent("oaa-commit", { detail: { value: v } }));
          }}
        ></wa-slider>
      </div>
    `;
  }
}
customElements.define("oaa-slider", OaaSlider);

/**
 * @param {{ value: number, min?: number, max?: number, step?: number, format?: (v: number) => any, suffix?: string, label?: string, disabled?: boolean, lead?: any, onCommit: (v: number) => void }} o
 */
export function slider(o) {
  return html`<oaa-slider
    .value=${o.value}
    .min=${o.min}
    .max=${o.max}
    .step=${o.step}
    .format=${o.format || null}
    .suffix=${o.suffix || ""}
    .label=${o.label || ""}
    .lead=${o.lead || null}
    ?disabled=${!!o.disabled}
    @oaa-commit=${(ev) => o.onCommit(ev.detail.value)}
  ></oaa-slider>`;
}

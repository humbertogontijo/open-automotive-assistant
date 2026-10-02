import { html, nothing } from "lit";
import { ifDefined } from "lit/directives/if-defined.js";
import { OaaElement } from "../../lit/oaa-element.js";
import { icon } from "../../icons.js";

/**
 * Thick fill bar over a wa-slider, with an icon inside the fill and the readout at the far end.
 * The readout follows the fill while dragging; `oaa-commit` fires once on release, since each
 * commit is one car write. The dragged value is held until the car reports a new `value`, so
 * the fill doesn't snap back while the write is in flight.
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
    icon: {},
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
    /** Icon name drawn inside the start of the fill. */
    this.icon = "";
    /** Content before the bar (e.g. a power switch). */
    this.lead = null;
    /** @type {((v: number) => any) | null} Readout formatter (value stays in entity units). */
    this.format = null;
    /** @type {number | null} */
    this.drag = null;
  }

  willUpdate(changed) {
    if (changed.has("value")) this.drag = null;
  }

  /** @param {number} v */
  readout(v) {
    return `${this.format ? this.format(v) : v}${this.suffix}`;
  }

  render() {
    const shown = this.drag != null ? this.drag : this.value;
    // wa-slider's own defaults when the entity omits a bound.
    const min = this.min ?? 0;
    const max = this.max ?? 100;
    const fill = max > min ? Math.min(1, Math.max(0, (shown - min) / (max - min))) : 0;
    const value = html`${this.format ? this.format(shown) : shown}<span class="unit">${this.suffix}</span>`;
    return html`
      <div class="slider-row ${this.lead ? "has-lead" : ""}" role="group" aria-label=${this.label || nothing}>
        ${this.lead || nothing}
        <div
          class="slider-bar ${this.icon ? "has-icon" : ""} ${this.disabled ? "disabled" : ""}"
          style="--fill: ${fill}"
        >
          <wa-slider
            min=${ifDefined(this.min)}
            max=${ifDefined(this.max)}
            step=${ifDefined(this.step)}
            .value=${this.value}
            .valueFormatter=${(v) => this.readout(v)}
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
          ${this.icon ? html`<span class="slider-icon">${icon(this.icon)}</span>` : nothing}
          <span class="slider-readout" aria-hidden="true">${value}</span>
          <span class="slider-readout on-fill" aria-hidden="true">${value}</span>
        </div>
      </div>
    `;
  }
}
customElements.define("oaa-slider", OaaSlider);

/**
 * @param {{ value: number, min?: number, max?: number, step?: number, format?: (v: number) => any, suffix?: string, label?: string, disabled?: boolean, icon?: string, lead?: any, onCommit: (v: number) => void }} o
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
    .icon=${o.icon || ""}
    .lead=${o.lead || null}
    ?disabled=${!!o.disabled}
    @oaa-commit=${(ev) => o.onCommit(ev.detail.value)}
  ></oaa-slider>`;
}

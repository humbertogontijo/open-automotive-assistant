import { html, nothing } from "lit";
import { icon } from "../../icons.js";
import { OaaElement } from "../../lit/oaa-element.js";

/** How long a tap shows its new state without the car confirming it. */
const PENDING_MS = 4000;

/**
 * Pressable on/off button for a card feature flag (A/C, ESC, …). The tapped state is held
 * until the car reports a new `on` (or PENDING_MS passes), so the button doesn't flicker
 * back while the write is in flight.
 * @fires oaa-toggle - `detail.on` is the requested state
 */
export class OaaToggleButton extends OaaElement {
  static properties = {
    on: { type: Boolean },
    label: {},
    icon: {},
    iconOnly: { type: Boolean },
    hint: {},
    disabled: { type: Boolean },
    pending: { state: true },
  };

  constructor() {
    super();
    this.on = false;
    this.label = "";
    this.icon = "";
    this.iconOnly = false;
    this.hint = "";
    this.disabled = false;
    /** @type {boolean | null} */
    this.pending = null;
    /** @type {ReturnType<typeof setTimeout> | undefined} */
    this.timer = undefined;
  }

  willUpdate(changed) {
    if (changed.has("on")) this.pending = null;
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    clearTimeout(this.timer);
  }

  toggle() {
    const next = !(this.pending != null ? this.pending : this.on);
    this.pending = next;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => (this.pending = null), PENDING_MS);
    this.dispatchEvent(new CustomEvent("oaa-toggle", { detail: { on: next } }));
  }

  render() {
    const on = this.pending != null ? this.pending : this.on;
    return html`<button
      type="button"
      class="toggle-btn ${this.iconOnly ? "icon-only" : ""}"
      aria-pressed=${on ? "true" : "false"}
      aria-label=${this.iconOnly ? this.label : nothing}
      title=${this.hint || (this.iconOnly ? this.label : nothing)}
      ?disabled=${this.disabled}
      @click=${() => this.toggle()}
    >
      ${this.icon ? icon(this.icon) : nothing}
      ${this.iconOnly ? nothing : html`<span class="toggle-btn-label">${this.label}</span>`}
    </button>`;
  }
}
customElements.define("oaa-toggle-button", OaaToggleButton);

/**
 * @typedef {{ label: any, on: boolean, icon?: string, iconOnly?: boolean, disabled?: boolean, hint?: string, onToggle: (next: boolean) => void }} ToggleOpts
 * @param {ToggleOpts} o
 */
export function toggleButton(o) {
  return html`<oaa-toggle-button
    .on=${!!o.on}
    .label=${o.label}
    .icon=${o.icon || ""}
    .iconOnly=${!!o.iconOnly}
    .hint=${o.hint || ""}
    ?disabled=${!!o.disabled}
    @oaa-toggle=${(ev) => o.onToggle(ev.detail.on)}
  ></oaa-toggle-button>`;
}

/**
 * Grid of toggle buttons: rows of three when that leaves no gap (3, 5, 6…), else rows of two.
 * @param {ToggleOpts[]} items
 * @param {string} ariaLabel
 */
export function toggleGrid(items, ariaLabel) {
  const n = items.length;
  if (!n) return nothing;
  const cols = n === 1 ? 1 : n === 3 || n >= 5 ? 3 : 2;
  return html`<div class="toggle-grid" data-cols=${cols} role="group" aria-label=${ariaLabel}>
    ${items.map(toggleButton)}
  </div>`;
}

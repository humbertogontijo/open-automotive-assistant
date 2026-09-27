import { html, nothing } from "lit";
import { live } from "lit/directives/live.js";
import { fmt } from "../../format.js";
import { t, optionLabel as resolveOptionLabel } from "../../i18n.js";
import { OaaElement } from "../../lit/oaa-element.js";
import { pinChip } from "./shared.js";

/**
 * @typedef {{ value: any, label?: string, labelKey?: string, disabled?: boolean, title?: string }} ChoiceOption
 * @typedef {{
 *   options: ChoiceOption[],
 *   current: any,
 *   locked?: boolean,
 *   pinnedVal?: any,
 *   searchable?: boolean,
 *   onSelect: (value: any) => void,
 * }} ChoiceOpts
 */

function lookupOptionLabel(opts, val) {
  const o = opts.find(function (x) {
    return String(x.value) === String(val);
  });
  return o ? resolveOptionLabel(o) : fmt(val);
}

const has = (v) => v != null && v !== "";

function optionTitle(o, pinned) {
  if (o.disabled) return o.title || t("cameras.storage.unavailable", "Not available");
  if (pinned) return t("persist.back_hint", "Applied only after the car restarts");
  return nothing;
}

/**
 * Up to 4 options as a segmented wa-radio-group; more fall back to choiceSelect.
 * @param {ChoiceOpts} opts
 */
export function segmentToggle(opts) {
  const list = opts.options || [];
  if (!list.length) return nothing;
  if (list.length > 4) return choiceSelect(opts);
  const current = has(opts.current) ? String(opts.current) : "";
  const pinnedVal = has(opts.pinnedVal) ? String(opts.pinnedVal) : null;
  return html`
    <wa-radio-group
      class="toggle-group ${current ? "" : "unset"}"
      orientation="horizontal"
      data-count=${list.length}
      .value=${live(current)}
      ?disabled=${!!opts.locked}
      @change=${function (ev) {
        const v = ev.target.value;
        if (v != null && v !== current) opts.onSelect(v);
      }}
    >
      ${list.map(function (o) {
        const val = String(o.value);
        const pinned = pinnedVal != null && pinnedVal === val;
        return html`<wa-radio
          appearance="button"
          class="toggle-seg ${pinned ? "pin-mark" : ""}"
          value=${val}
          ?disabled=${!!o.disabled}
          title=${optionTitle(o, pinned)}
          >${resolveOptionLabel(o)}</wa-radio
        >`;
      })}
    </wa-radio-group>
  `;
}

/**
 * Momentary actions (write-only commands): one button per option, nothing stays selected.
 * @param {{ options: ChoiceOption[], locked?: boolean, onSelect: (value: any) => void }} opts
 */
export function commandButtons(opts) {
  const list = opts.options || [];
  if (!list.length) return nothing;
  return html`
    <wa-button-group class="command-group" orientation="horizontal">
      ${list.map(
        (o) => html`<wa-button
          appearance="outlined"
          ?disabled=${!!opts.locked || !!o.disabled}
          @click=${() => opts.onSelect(o.value)}
          >${resolveOptionLabel(o)}</wa-button
        >`,
      )}
    </wa-button-group>
  `;
}

/** @param {ChoiceOpts} opts */
export function choiceSelect(opts) {
  if (!(opts.options || []).length) return nothing;
  return html`<oaa-choice-select
    .options=${opts.options}
    .current=${opts.current}
    .pinnedVal=${opts.pinnedVal}
    .locked=${!!opts.locked}
    .searchable=${!!opts.searchable}
    @oaa-select=${(ev) => opts.onSelect(ev.detail.value)}
  ></oaa-choice-select>`;
}

/**
 * Dropdown for more than 4 options: a wa-select, or a wa-dropdown with a filter field
 * when searchable. Open/filter state lives here, so nothing global tracks open menus.
 * @fires oaa-select {{ value: any }}
 */
export class OaaChoiceSelect extends OaaElement {
  static properties = {
    options: { attribute: false },
    current: { attribute: false },
    pinnedVal: { attribute: false },
    locked: { type: Boolean },
    searchable: { type: Boolean },
    query: { state: true },
    open: { state: true },
  };

  constructor() {
    super();
    /** @type {ChoiceOption[]} */
    this.options = [];
    this.current = null;
    this.pinnedVal = null;
    this.locked = false;
    this.searchable = false;
    this.query = "";
    /** Items render only while the menu is open; closed, only the current option exists. */
    this.open = false;
  }

  /** @param {any} value */
  pick(value) {
    this.query = "";
    this.dispatchEvent(new CustomEvent("oaa-select", { detail: { value }, bubbles: true, composed: true }));
  }

  render() {
    const list = this.options || [];
    const hasCurrent = has(this.current);
    const hasPin = has(this.pinnedVal);
    const match = hasPin && hasCurrent && String(this.current) === String(this.pinnedVal);
    this.classList.toggle("pin-match", hasPin && match);
    this.classList.toggle("pin-diff", hasPin && !match);
    this.classList.toggle("unset", !hasCurrent);
    const chip = hasPin && !match ? pinChip(lookupOptionLabel(list, this.pinnedVal)) : nothing;
    return html`${this.searchable ? this.renderSearchable(list, hasCurrent) : this.renderSelect(list, hasCurrent)}${chip}`;
  }

  renderSelect(list, hasCurrent) {
    const pinned = has(this.pinnedVal) ? String(this.pinnedVal) : null;
    const cur = hasCurrent ? String(this.current) : "";
    const items = this.open ? list : list.filter((o) => String(o.value) === cur);
    return html`<wa-select
      class="choice-select"
      .value=${live(cur)}
      placeholder=${t("persist.pick_short", "Select…")}
      ?disabled=${this.locked}
      @wa-show=${() => (this.open = true)}
      @wa-after-hide=${() => (this.open = false)}
      @change=${(ev) => {
        const v = ev.target.value;
        if (v != null && String(v) !== String(this.current)) this.pick(v);
      }}
    >
      ${items.map((o) => {
        const val = String(o.value);
        const isPinned = pinned != null && pinned === val;
        return html`<wa-option
          value=${val}
          class=${isPinned ? "pin-mark" : ""}
          ?disabled=${!!o.disabled}
          title=${optionTitle(o, isPinned)}
          >${resolveOptionLabel(o)}</wa-option
        >`;
      })}
    </wa-select>`;
  }

  renderSearchable(list, hasCurrent) {
    const q = this.query.trim().toLowerCase();
    const filtered = !this.open
      ? []
      : !q
      ? list
      : list.filter((o) => {
          const lab = String(resolveOptionLabel(o) || "").toLowerCase();
          const val = String(o.value != null ? o.value : "").toLowerCase();
          return lab.includes(q) || val.includes(q);
        });
    const label = hasCurrent ? lookupOptionLabel(list, this.current) : t("persist.pick_short", "Select…");
    const current = hasCurrent ? String(this.current) : null;
    return html`<wa-dropdown
      class="choice-select searchable"
      placement="bottom-start"
      @wa-select=${(ev) => this.pick(ev.detail.item.value)}
      @wa-show=${() => (this.open = true)}
      @wa-after-show=${() => {
        const input = /** @type {HTMLElement|null} */ (this.querySelector(".choice-search"));
        if (input) input.focus();
      }}
      @wa-after-hide=${() => {
        this.query = "";
        this.open = false;
      }}
    >
      <wa-button slot="trigger" class="choice-trigger" appearance="outlined" with-caret ?disabled=${this.locked}>${label}</wa-button>
      <wa-input
        class="choice-search"
        type="search"
        autocomplete="off"
        enterkeyhint="search"
        placeholder=${t("common.search", "Search…")}
        .value=${live(this.query)}
        @input=${(ev) => (this.query = ev.target.value || "")}
        @keydown=${(ev) => {
          // The dropdown's type-to-select would otherwise swallow the letters.
          if (ev.key !== "Escape" && ev.key !== "ArrowDown" && ev.key !== "ArrowUp") ev.stopPropagation();
        }}
      ></wa-input>
      ${filtered.length
        ? filtered.map(
            (o) => html`<wa-dropdown-item
              value=${String(o.value)}
              type="checkbox"
              ?checked=${current != null && current === String(o.value)}
              ?disabled=${!!o.disabled}
              >${resolveOptionLabel(o)}</wa-dropdown-item
            >`,
          )
        : html`<p class="choice-empty hint">${t("common.no_results", "No matches")}</p>`}
    </wa-dropdown>`;
  }
}
customElements.define("oaa-choice-select", OaaChoiceSelect);

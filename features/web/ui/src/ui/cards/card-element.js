import { html, nothing } from "lit";
import { OaaElement } from "../../lit/oaa-element.js";
import { setControl } from "../../actions.js";
import { fmt } from "../../format.js";
import { controlShell, displayUnit, isLocked } from "./shared.js";

/** Entity ids whose card already logged a render failure. */
const warned = new Set();

/**
 * Base for the per-domain control cards. `.control` is the catalog entity; `restore` renders
 * it on the hidden-cards view (show button, no pin). The host is `display: contents`, so the
 * inner wa-card is the dashboard grid item.
 *
 * A card that throws (an entity shape this UI does not know, e.g. from a newer car behind an
 * older hub) falls back to a read-only value card instead of leaving a hole in the grid.
 */
export class OaaCard extends OaaElement {
  static properties = {
    control: { attribute: false },
    restore: { type: Boolean },
  };

  constructor() {
    super();
    /** @type {any} */
    this.control = null;
    this.restore = false;
  }

  get locked() {
    return isLocked(this.control);
  }

  /** @param {string} cmd */
  send(cmd) {
    if (!this.locked) setControl(this.control.id, cmd);
  }

  render() {
    const c = this.control;
    if (!c) return nothing;
    try {
      return this.renderCard(c);
    } catch (e) {
      if (!warned.has(c.id)) {
        warned.add(c.id);
        console.warn("card " + c.id + " (" + (c.domain || c.input) + ") failed to render", e);
      }
      return fallbackCard(c, this.restore);
    }
  }

  /**
   * @param {any} _c
   * @returns {unknown}
   */
  renderCard(_c) {
    return nothing;
  }
}

/** @param {any} c */
function fallbackCard(c, restore) {
  try {
    const unit = displayUnit(c);
    return controlShell(c, {
      restore,
      pinnable: false,
      body: html`<span class="mono">${fmt(c.value)}${unit ? " " + unit : ""}</span>`,
    });
  } catch (e) {
    return nothing;
  }
}

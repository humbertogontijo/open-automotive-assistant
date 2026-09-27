import { nothing } from "lit";
import { OaaElement } from "../../lit/oaa-element.js";
import { setControl } from "../../actions.js";

/**
 * Base for the per-domain control cards. `.control` is the catalog entity; `restore` renders
 * it on the hidden-cards view (show button, no pin). The host is `display: contents`, so the
 * inner wa-card is the dashboard grid item.
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
    const c = this.control;
    return c.status !== "ok" && c.status !== "cached";
  }

  /** @param {string} cmd */
  send(cmd) {
    if (!this.locked) setControl(this.control.id, cmd);
  }

  render() {
    return this.control ? this.renderCard(this.control) : nothing;
  }

  /**
   * @param {any} _c
   * @returns {unknown}
   */
  renderCard(_c) {
    return nothing;
  }
}

import { OaaElement } from "./oaa-element.js";

/** @type {OaaPage | null} */
let mounted = null;

/**
 * Base for routed pages (`<oaa-page-*>`): loads its data when connected and again after the
 * app refreshes (node switch, hub login, reconnect).
 */
export class OaaPage extends OaaElement {
  connectedCallback() {
    super.connectedCallback();
    mounted = this;
    this.load();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    if (mounted === this) mounted = null;
  }

  /** Fetch the page's data into the store. */
  load() {}
}

/** Re-run the mounted page's loader. */
export function reloadPage() {
  return mounted ? mounted.load() : undefined;
}

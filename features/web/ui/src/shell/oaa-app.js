import { html } from "lit";
import { OaaElement } from "../lit/oaa-element.js";
import { session } from "../store.js";
import { createRouter } from "../router.js";
import { pageView } from "../pages/index.js";
import { enterPage, goPage, start } from "../boot.js";
import { restorePageScroll, rememberScroll } from "../nav.js";
import { headUnitNeedsReauth, shouldShowCarPair, shouldShowHubLogin, shouldShowHubSetup } from "../ui/hub-auth.js";
import "./oaa-nav.js";
import "../ui/setup.js";
import "../ui/hub-auth.js";
import "../ui/car-pair.js";
import "../ui/confirm.js";
import "../ui/toast.js";

/** Text inputs that summon the soft keyboard (native and Web Awesome hosts). */
function isTextField(el) {
  if (!el) return false;
  if (el.tagName === "TEXTAREA" || el.tagName === "WA-TEXTAREA" || el.isContentEditable) return true;
  if (el.tagName === "WA-INPUT") return true;
  if (el.tagName !== "INPUT") return false;
  return !/^(checkbox|radio|range|button|submit|reset|color|file|hidden)$/.test(el.type);
}

// The HU keyboard shrinks the viewport; keep the focused field visible.
function revealFocusedField() {
  const el = document.activeElement;
  if (isTextField(el)) el.scrollIntoView({ block: "center", behavior: "smooth" });
}

export class OaaApp extends OaaElement {
  constructor() {
    super();
    this.router = createRouter(this, { view: () => pageView(session.page), enter: enterPage });
    this._onGoto = (/** @type {CustomEvent} */ ev) => {
      if (typeof ev.detail !== "string" || !ev.detail) return;
      ev.preventDefault();
      goPage(ev.detail);
    };
    this._onFocusIn = (/** @type {FocusEvent} */ ev) => {
      if (isTextField(ev.target)) setTimeout(revealFocusedField, 350);
    };
    this._onScroll = (/** @type {Event} */ ev) => {
      const target = /** @type {HTMLElement} */ (ev.target);
      if (target && target.id === "main") rememberScroll(session.page, target.scrollTop);
    };
  }

  connectedCallback() {
    super.connectedCallback();
    // Native quick-entry (GeckoView bridge extension) navigates in-page through this event.
    window.addEventListener("oaa:goto", this._onGoto);
    document.addEventListener("focusin", this._onFocusIn);
    (window.visualViewport || window).addEventListener("resize", revealFocusedField);
    document.addEventListener("scroll", this._onScroll, true);
    start();
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    window.removeEventListener("oaa:goto", this._onGoto);
    document.removeEventListener("focusin", this._onFocusIn);
    (window.visualViewport || window).removeEventListener("resize", revealFocusedField);
    document.removeEventListener("scroll", this._onScroll, true);
  }

  render() {
    const outlet = this.router.outlet();
    return html`
      ${session.booted && !headUnitNeedsReauth()
        ? html`<div class="shell">
            <oaa-nav></oaa-nav>
            <main class="main" id="main">${outlet != null ? outlet : pageView(session.page)}</main>
          </div>`
        : html`<div class="boot-gate"><wa-spinner></wa-spinner></div>`}
      <oaa-setup-overlay></oaa-setup-overlay>
      <oaa-hub-auth></oaa-hub-auth>
      <oaa-car-pair></oaa-car-pair>
      <oaa-pair-requests></oaa-pair-requests>
      <oaa-dialog-host></oaa-dialog-host>
      <oaa-toast-stack></oaa-toast-stack>
    `;
  }

  updated() {
    const authGate = shouldShowHubSetup() || shouldShowHubLogin() || shouldShowCarPair();
    document.body.classList.toggle("hub-fleet", authGate || (session.role === "hub" && !session.selectedNodeId));
    restorePageScroll(session.page);
  }
}
customElements.define("oaa-app", OaaApp);

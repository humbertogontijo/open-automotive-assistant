import { html, nothing } from "lit";
import { session } from "../store.js";
import { OaaElement } from "../lit/oaa-element.js";
import { t } from "../i18n.js";
import { api, errText, postJson } from "../api.js";
import { reconnectEvents } from "../events.js";
import { carPaired, shouldShowCarPair } from "./hub-auth.js";

/** A readable default for the device list on the head unit. */
function defaultDeviceName() {
  const ua = navigator.userAgent || "";
  const os = /iPhone|iPad/.test(ua)
    ? "iOS"
    : /Android/.test(ua)
      ? "Android"
      : /Mac OS X/.test(ua)
        ? "macOS"
        : /Windows/.test(ua)
          ? "Windows"
          : /Linux/.test(ua)
            ? "Linux"
            : "";
  const browser = /Firefox\//.test(ua) ? "Firefox" : /Edg\//.test(ua) ? "Edge" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "";
  return [browser, os].filter(Boolean).join(" · ") || t("pair.device", "Browser");
}

function formatCode(code) {
  return String(code || "").replace(/^(\d{3})(\d{3})$/, "$1 $2");
}

/** Untrusted browser on the car's LAN address: ask the car for a code and type it here. */
export class OaaCarPair extends OaaElement {
  static properties = {
    requestId: { state: true },
    name: { state: true },
    code: { state: true },
    busy: { state: true },
  };

  constructor() {
    super();
    this.requestId = "";
    this.name = defaultDeviceName();
    this.code = "";
    this.busy = false;
  }

  async request() {
    this.busy = true;
    session.hubAuthMsg = "";
    try {
      const res = await postJson("/api/auth/pair/request", { kind: "browser", name: this.name });
      if (res && res.ok) {
        this.requestId = res.requestId;
        this.code = "";
      } else {
        session.hubAuthMsg = (res && res.error) || t("pair.request_failed", "The car did not answer");
      }
    } catch (e) {
      session.hubAuthMsg = errText(e);
    } finally {
      this.busy = false;
    }
  }

  async confirm(ev) {
    ev.preventDefault();
    this.busy = true;
    try {
      const res = await postJson("/api/auth/pair/confirm", { requestId: this.requestId, code: this.code.replace(/\D/g, "") });
      if (res && res.ok) {
        this.requestId = "";
        carPaired();
        reconnectEvents();
        return;
      }
      const err = (res && res.error) || "";
      if (/expired|unknown/.test(err)) this.requestId = "";
      session.hubAuthMsg = err || t("pair.wrong_code", "Wrong code");
    } catch (e) {
      session.hubAuthMsg = errText(e);
    } finally {
      this.busy = false;
    }
  }

  render() {
    const open = shouldShowCarPair();
    const msg = session.hubAuthMsg;
    return html`<wa-dialog
      class="oaa-dialog auth-dialog"
      label=${t("pair.title", "Pair with this car")}
      without-header
      ?open=${open}
      @wa-hide=${(ev) => {
        if (ev.target === ev.currentTarget && shouldShowCarPair()) ev.preventDefault();
      }}
    >
      ${open
        ? html`<h1>${t("pair.title", "Pair with this car")}</h1>
            ${this.requestId
              ? html`<form class="auth-form" @submit=${(ev) => this.confirm(ev)}>
                  <p class="sub">${t("pair.enter_code", "Type the 6-digit code now shown on the car's screen.")}</p>
                  <wa-input
                    label=${t("pair.code", "Code")}
                    inputmode="numeric"
                    autocomplete="one-time-code"
                    maxlength="7"
                    required
                    .value=${this.code}
                    @input=${(ev) => (this.code = ev.target.value)}
                  ></wa-input>
                  ${msg ? html`<wa-callout variant="danger" size="s" class="auth-msg">${msg}</wa-callout>` : nothing}
                  <wa-button type="submit" variant="brand" ?loading=${this.busy}>${t("pair.pair", "Pair")}</wa-button>
                  <wa-button appearance="plain" @click=${() => (this.requestId = "")}>${t("common.cancel", "Cancel")}</wa-button>
                </form>`
              : html`<div class="auth-form">
                  <p class="sub">
                    ${t(
                      "pair.intro",
                      "This car only answers devices it trusts. Ask for a code, then type the code the car shows.",
                    )}
                  </p>
                  <wa-input
                    label=${t("pair.device_name", "This device")}
                    .value=${this.name}
                    @input=${(ev) => (this.name = ev.target.value)}
                  ></wa-input>
                  ${msg ? html`<wa-callout variant="danger" size="s" class="auth-msg">${msg}</wa-callout>` : nothing}
                  <wa-button variant="brand" ?loading=${this.busy} @click=${() => this.request()}
                    >${t("pair.show_code", "Show a code on the car")}</wa-button
                  >
                </div>`}`
        : nothing}
    </wa-dialog>`;
  }
}
customElements.define("oaa-car-pair", OaaCarPair);

/** Head unit: the code to read out while someone pairs with this car. */
export class OaaPairRequests extends OaaElement {
  static properties = {
    dismissed: { state: true },
  };

  constructor() {
    super();
    /** @type {string[]} */
    this.dismissed = [];
    this.tick = 0;
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    clearInterval(this.tick);
    this.tick = 0;
  }

  visible() {
    const a = session.hubAuth;
    if (!a || !a.headUnit) return [];
    const now = Date.now();
    return (session.pairRequests || []).filter((r) => r.expiresAtMs > now && this.dismissed.indexOf(r.id) < 0);
  }

  async decline(r) {
    try {
      await api("/api/auth/pair/pending/" + encodeURIComponent(r.id), { method: "DELETE" });
    } catch (e) {}
    this.dismissed = this.dismissed.concat(r.id);
  }

  updated() {
    const open = this.visible().length > 0;
    // Re-render once a second for the countdown while a request is open.
    if (open && !this.tick) this.tick = window.setInterval(() => this.requestUpdate(), 1000);
    if (!open && this.tick) {
      clearInterval(this.tick);
      this.tick = 0;
    }
  }

  render() {
    const list = this.visible();
    const now = Date.now();
    return html`<wa-dialog
      class="oaa-dialog auth-dialog pair-requests"
      label=${t("pair.requests_title", "Pairing request")}
      ?open=${list.length > 0}
      @wa-hide=${(ev) => {
        if (ev.target === ev.currentTarget) this.dismissed = this.dismissed.concat(list.map((r) => r.id));
      }}
    >
      ${list.map(
        (r) => html`<div class="pair-request">
          <p class="sub">
            ${(r.kind === "hub"
              ? t("pair.hub_wants", "Hub {name} wants to pair")
              : t("pair.device_wants", "{name} wants to pair")
            ).replace("{name}", r.name || r.source)}
          </p>
          <p class="pair-code mono">${formatCode(r.code)}</p>
          <p class="hint">
            ${r.source} ·
            ${t("pair.expires_in", "expires in {s}s").replace("{s}", String(Math.max(0, Math.round((r.expiresAtMs - now) / 1000))))}
          </p>
          <wa-button appearance="outlined" variant="danger" @click=${() => this.decline(r)}
            >${t("pair.decline", "Decline")}</wa-button
          >
        </div>`,
      )}
      <p class="hint">${t("pair.requests_hint", "Only type or read this code to a device you are setting up.")}</p>
    </wa-dialog>`;
  }
}
customElements.define("oaa-pair-requests", OaaPairRequests);

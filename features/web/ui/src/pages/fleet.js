import { html, nothing } from "lit";
import { session } from "../store.js";
import { t } from "../i18n.js";
import { api, errText, postJson } from "../api.js";
import { selectNode } from "../node-select.js";
import { prefCard } from "../ui/cards/prefs.js";
import { confirmDialog } from "../ui/confirm.js";
import { toast, toastError } from "../ui/toast.js";
import { OaaPage } from "../lit/oaa-page.js";

const OTA_POLL_MS = 3000;
const DISCOVER_POLL_MS = 5000;
const OTA_TERMINAL = ["installed", "failed"];
const OTA_TEXT = {
  pending: "Update queued",
  offered: "Update offered",
  downloading: "Downloading update",
  verifying: "Verifying update",
  installing: "Installing update",
  pending_user: "Confirm the install on the car",
  installed: "Update installed",
  failed: "Update failed",
};

function isAdmin() {
  const user = (session.status && session.status.user) || (session.hubAuth && session.hubAuth.user);
  return !!(user && user.role === "admin");
}

function otaActive(nodes) {
  return nodes.some((n) => n.ota && OTA_TERMINAL.indexOf(n.ota.state) < 0);
}

/** Refresh the hub's node list. */
export async function loadFleet() {
  if (session.role !== "hub") return;
  try {
    session.fleet = await api("/api/nodes");
  } catch (e) {
    session.fleet = { nodes: [] };
  }
}

/** Node-face URL to type on the car: the published one, else this host on the node port. */
function nodeDialUrl(offer) {
  const hub = session.hubJoin || {};
  const published =
    (offer && (offer.publicDialUrl || offer.publicNodeUrl)) || hub.publicDialUrl || hub.publicNodeUrl;
  if (published) return published;
  const host = window.location.hostname;
  const loopback = host === "localhost" || host === "127.0.0.1" || host === "::1" || host === "[::1]";
  return "http://" + (loopback ? "<hub-LAN-IP>" : host) + ":" + (hub.nodePort || 8788);
}

function appLine(app) {
  if (!app || !app.versionName) return "";
  const code = app.versionCode != null ? " (" + app.versionCode + ")" : "";
  return t("fleet.app_version", "App {version}").replace("{version}", app.versionName + code);
}

function otaLine(ota) {
  if (!ota || !ota.state) return "";
  let text = t("fleet.ota." + ota.state, OTA_TEXT[ota.state] || ota.state);
  if (ota.state === "downloading" && ota.progress != null) text += " · " + ota.progress + "%";
  if (ota.state === "failed" && ota.error) text += ": " + ota.error;
  return text;
}

async function forget(n) {
  if (!(await confirmDialog(t("fleet.forget_confirm", "Forget this car?"), { danger: true }))) return;
  try {
    await api("/api/nodes/" + encodeURIComponent(n.id), { method: "DELETE" });
    if (session.selectedNodeId === n.id) await selectNode("");
    await loadFleet();
  } catch (e) {
    toastError(errText(e));
  }
}

function nodeCard(n) {
  const online = !!n.online;
  const app = appLine(n.app);
  return prefCard({
    icon: "sensor",
    title: n.name || n.id,
    body: html`
      <p class="hint">
        ${online ? t("fleet.online", "Online") : t("fleet.offline", "Offline")} ${n.integration ? " · " + n.integration : ""}
      </p>
      ${app ? html`<p class="hint mono">${app}</p>` : nothing}
      ${n.ota
        ? n.ota.state === "failed"
          ? html`<wa-callout variant="warning" size="small">${otaLine(n.ota)}</wa-callout>`
          : html`<p class="hint">${otaLine(n.ota)}</p>`
        : nothing}
      <div class="pref-actions">
        <wa-button variant="brand" ?disabled=${!online} @click=${() => selectNode(n.id)}>${t("fleet.open", "Open")}</wa-button>
        <wa-button appearance="outlined" variant="danger" @click=${() => forget(n)}>${t("fleet.forget", "Forget")}</wa-button>
      </div>
    `,
  });
}

class OaaPageFleet extends OaaPage {
  static properties = {
    offer: { state: true },
    discovered: { state: true },
    invite: { state: true },
    inviteCode: { state: true },
    addHost: { state: true },
    busy: { state: true },
  };

  constructor() {
    super();
    /** @type {any} */
    this.offer = null;
    /** @type {any[]} */
    this.discovered = [];
    /** Open hub-initiated pairing: { inviteId, name, expiresAtMs } */
    /** @type {any} */
    this.invite = null;
    this.inviteCode = "";
    this.addHost = "";
    this.busy = false;
    this.pollTimer = 0;
    this.discoverTimer = 0;
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    clearTimeout(this.pollTimer);
    clearTimeout(this.discoverTimer);
    this.pollTimer = 0;
    this.discoverTimer = 0;
  }

  /** Poll while any car is mid-update. */
  async load() {
    clearTimeout(this.pollTimer);
    this.pollTimer = 0;
    this.loadDiscovered();
    await loadFleet();
    const nodes = (session.fleet && session.fleet.nodes) || [];
    if (this.isConnected && otaActive(nodes)) this.pollTimer = window.setTimeout(() => this.load(), OTA_POLL_MS);
  }

  /** Cars announcing themselves on the LAN; refreshed while the page is open. */
  async loadDiscovered() {
    clearTimeout(this.discoverTimer);
    this.discoverTimer = 0;
    if (!isAdmin()) return;
    try {
      const res = await api("/api/nodes/discovered");
      this.discovered = (res && res.cars) || [];
    } catch (e) {
      this.discovered = [];
    }
    if (this.isConnected) this.discoverTimer = window.setTimeout(() => this.loadDiscovered(), DISCOVER_POLL_MS);
  }

  async generate() {
    try {
      this.offer = await api("/api/nodes/pairing", { method: "POST" });
    } catch (e) {
      this.offer = null;
      toastError(errText(e));
    }
  }

  /** Ask a car to show a pairing code; [target] is `{ nodeId }` or `{ host }`. */
  async startInvite(target) {
    this.busy = true;
    try {
      const res = await postJson("/api/nodes/invite", target);
      if (res && res.ok) {
        this.invite = res;
        this.inviteCode = "";
      } else {
        toastError(t("fleet.invite_failed", "Could not reach the car") + (res && res.error ? ": " + res.error : ""));
      }
    } catch (e) {
      toastError(errText(e));
    } finally {
      this.busy = false;
    }
  }

  async confirmInvite(ev) {
    ev.preventDefault();
    const invite = this.invite;
    if (!invite) return;
    this.busy = true;
    try {
      const res = await postJson("/api/nodes/invite/" + encodeURIComponent(invite.inviteId) + "/confirm", { code: this.inviteCode });
      if (res && res.ok) {
        toast(t("fleet.invite_ok", "{name} added").replace("{name}", invite.name || invite.nodeId), { variant: "success" });
        this.invite = null;
        this.addHost = "";
        await this.load();
        return;
      }
      const err = (res && res.error) || "";
      if (/expired/.test(err)) this.invite = null;
      toastError(err || t("pair.wrong_code", "Wrong code"));
    } catch (e) {
      toastError(errText(e));
    } finally {
      this.busy = false;
    }
  }

  async cancelInvite() {
    const invite = this.invite;
    this.invite = null;
    if (invite) api("/api/nodes/invite/" + encodeURIComponent(invite.inviteId), { method: "DELETE" }).catch(() => {});
  }

  inviteCard() {
    const invite = this.invite;
    return prefCard({
      cls: "form-card",
      icon: "lock",
      title: t("fleet.enter_code_title", "Code from {name}").replace("{name}", invite.name || invite.nodeId),
      body: html`<form class="auth-form" @submit=${(ev) => this.confirmInvite(ev)}>
        <p class="hint">${t("fleet.enter_code", "The car's screen now shows a 6-digit code. Type it here.")}</p>
        <wa-input
          label=${t("pair.code", "Code")}
          inputmode="numeric"
          autocomplete="one-time-code"
          maxlength="7"
          required
          .value=${this.inviteCode}
          @input=${(ev) => (this.inviteCode = ev.target.value)}
        ></wa-input>
        <div class="pref-actions">
          <wa-button type="submit" variant="brand" ?loading=${this.busy}>${t("pair.pair", "Pair")}</wa-button>
          <wa-button appearance="outlined" @click=${() => this.cancelInvite()}>${t("common.cancel", "Cancel")}</wa-button>
        </div>
      </form>`,
    });
  }

  nearbyCard() {
    const cars = this.discovered || [];
    return prefCard({
      cls: "form-card",
      icon: "sensor",
      title: t("fleet.nearby", "Nearby cars"),
      body: html`
        ${cars.length
          ? html`<div class="trusted-list">
              ${cars.map(
                (c) => html`<div class="trusted-row">
                  <div>
                    <strong>${c.name || c.id}</strong>
                    <p class="hint">${c.host}${c.integration ? " · " + c.integration : ""}</p>
                  </div>
                  <wa-button size="small" variant="brand" ?disabled=${this.busy} @click=${() => this.startInvite({ nodeId: c.id })}
                    >${t("fleet.add", "Add")}</wa-button
                  >
                </div>`,
              )}
            </div>`
          : html`<p class="hint">
              ${t("fleet.nearby_empty", "No unpaired cars found on this network. Cars announce themselves until they join a hub.")}
            </p>`}
        <wa-input
          label=${t("fleet.add_by_ip", "Add by address")}
          placeholder="192.168.1.50"
          .value=${this.addHost}
          @input=${(ev) => (this.addHost = ev.target.value)}
        ></wa-input>
        <div class="pref-actions">
          <wa-button
            appearance="outlined"
            ?disabled=${!this.addHost.trim() || this.busy}
            @click=${() => this.startInvite({ host: this.addHost.trim() })}
            >${t("fleet.ask_code", "Ask the car for a code")}</wa-button
          >
        </div>
      `,
    });
  }

  render() {
    const nodes = (session.fleet && session.fleet.nodes) || [];
    const offer = this.offer;
    return html`
      <h1>${t("nav.fleet", "Fleet")}</h1>
      <p class="hint">
        ${t("fleet.hint_invite", "Open a car to control it with the same UI. Add a nearby car, then type the code its screen shows.")}
      </p>
      <div class="grid">
        ${this.invite ? this.inviteCard() : isAdmin() ? this.nearbyCard() : nothing}
        ${nodes.length
          ? nodes.map(nodeCard)
          : prefCard({
              icon: "about",
              title: t("fleet.empty", "No cars paired"),
              body: html`<p class="hint">
                ${t("fleet.empty_hint_invite", "Add a car from Nearby cars, or pair manually from the car's Settings → Hub.")}
              </p>`,
            })}
        ${prefCard({
          cls: "form-card",
          icon: "plugins",
          title: t("fleet.pairing_manual", "Manual pairing"),
          body: html`
            <p class="hint">
              ${offer
                ? t("fleet.code", "Code: {code} (expires soon)").replace("{code}", offer.code)
                : t("fleet.manual_hint", "For cars outside this network: generate a code and type it in the car's Settings → Hub.")}
            </p>
            <p class="hint mono">${t("fleet.cloud_url", "Cars dial:")} ${nodeDialUrl(offer)}</p>
            <wa-button appearance="outlined" @click=${() => this.generate()}>${t("fleet.generate", "Generate pairing code")}</wa-button>
          `,
        })}
      </div>
    `;
  }
}
customElements.define("oaa-page-fleet", OaaPageFleet);

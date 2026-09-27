import { html, nothing } from "lit";
import "@awesome.me/webawesome/dist/components/details/details.js";
import { quickEntryCard } from "./shortcuts/slots.js";
import { prefCard, prefSegment } from "../ui/cards/prefs.js";
import { theme } from "../theme.js";
import { session, prefs, i18n } from "../store.js";
import { t } from "../i18n.js";
import { api, errText, postForm, postJson } from "../api.js";
import { unitPrefs, UNIT_CHOICES } from "../units.js";
import { OaaPage } from "../lit/oaa-page.js";
import { toast, toastError } from "../ui/toast.js";
import { confirmDialog } from "../ui/confirm.js";
import { loadShortcuts } from "../shortcuts-data.js";

async function updatePrefs(body) {
  const res = await postForm("/api/prefs", body);
  prefs.$patch({
    homeLat: res.homeLat,
    homeLon: res.homeLon,
    homeRadiusM: res.homeRadiusM != null ? res.homeRadiusM : prefs.homeRadiusM,
  });
  return res;
}

async function useCurrentLocation() {
  try {
    const res = await api("/api/location/home/here", { method: "POST" });
    if (res && res.ok === false) {
      toastError(res.error || t("prefs.home.failed", "Could not read GPS"));
      return;
    }
    prefs.$patch({ homeLat: res.homeLat, homeLon: res.homeLon, homeRadiusM: res.homeRadiusM });
  } catch (e) {
    toastError(errText(e));
  }
}

function unitDimensionCards() {
  const current = unitPrefs();
  const dims = [
    ["temperature", "temp", t("units.dim.temperature", "Temperature")],
    ["distance", "drive", t("units.dim.distance", "Distance")],
    ["speed", "drive", t("units.dim.speed", "Speed")],
    ["fuel_economy", "energy", t("units.dim.fuel", "Fuel economy")],
    ["energy_economy", "battery", t("units.dim.energy", "Energy economy")],
  ];
  return dims.map(([key, ico, title]) =>
    prefCard({
      icon: ico,
      title,
      body: prefSegment(
        "unit_" + key,
        (UNIT_CHOICES[key] || []).map((id) => ({ value: id, label: t("unit." + id, id) })),
        current[key],
      ),
    }),
  );
}

function homeCard() {
  const { homeLat, homeLon } = prefs;
  const homeRadius = prefs.homeRadiusM != null ? prefs.homeRadiusM : 100;
  const configured = homeLat != null && homeLon != null;
  return prefCard({
    cls: "form-card",
    icon: "drive",
    title: t("prefs.home", "Home location"),
    body: html`
      <p class="hint">
        ${configured
          ? t("prefs.home.coords", "Lat {lat}, Lon {lon}")
              .replace("{lat}", Number(homeLat).toFixed(5))
              .replace("{lon}", Number(homeLon).toFixed(5))
          : t("prefs.home.unset", "Not set — use current location")}
      </p>
      <wa-input
        type="number"
        label=${t("prefs.home.radius", "Radius (m)")}
        min="20"
        max="5000"
        step="10"
        .value=${String(homeRadius)}
        @change=${(ev) => {
          const r = parseFloat(ev.target.value);
          if (!isNaN(r)) updatePrefs("homeRadiusM=" + encodeURIComponent(String(r))).catch(() => {});
        }}
      ></wa-input>
      <div class="pref-actions">
        <wa-button variant="brand" @click=${useCurrentLocation}>${t("prefs.home.here", "Use current location")}</wa-button>
        <wa-button
          appearance="outlined"
          ?disabled=${!configured}
          @click=${() => updatePrefs("clearHome=1").catch(() => {})}
          >${t("prefs.home.clear", "Clear")}</wa-button
        >
      </div>
    `,
  });
}

function setupCard() {
  return prefCard({
    icon: "system",
    title: t("prefs.setup", "Setup"),
    body: html`<div class="pref-actions">
      <wa-button appearance="outlined" @click=${() => (session.showSetup = true)}>${t("setup.open", "Abrir setup")}</wa-button>
      <wa-button
        appearance="outlined"
        variant="danger"
        @click=${async () => {
          const setup = await postForm("/api/setup", "reset=1");
          session.$patch({ setup, showSetup: true });
        }}
        >${t("setup.reset", "Resetar setup")}</wa-button
      >
    </div>`,
  });
}

function applyHub(res) {
  session.$patch({ hubJoin: res, status: /** @type {import("../store.js").Status} */ ({ ...session.status, hub: res }) });
}

const CLIENT_KIND = {
  browser: ["Browser", "system"],
  hub: ["Hub", "plugins"],
  tool: ["Tool", "lab"],
};

function isHeadUnit() {
  return !!(session.hubAuth && session.hubAuth.headUnit);
}

class OaaPageSettings extends OaaPage {
  static properties = {
    hubUrl: { state: true },
    hubCode: { state: true },
    joining: { state: true },
    clients: { state: true },
  };

  constructor() {
    super();
    /** @type {string | null} */
    this.hubUrl = null;
    this.hubCode = "";
    this.joining = false;
    /** @type {any[] | null} */
    this.clients = null;
  }

  load() {
    return Promise.all([loadShortcuts(), this.loadClients()]);
  }

  async loadClients() {
    if (!isHeadUnit()) return;
    try {
      const res = await api("/api/auth/clients");
      this.clients = (res && res.clients) || [];
    } catch (e) {
      this.clients = [];
    }
  }

  async revoke(c) {
    const msg = t("trusted.revoke_confirm", "Remove {name}? It will need a new code to connect.").replace("{name}", c.name || c.id);
    if (!(await confirmDialog(msg, { danger: true }))) return;
    try {
      await api("/api/auth/clients/" + encodeURIComponent(c.id), { method: "DELETE" });
      if (c.kind === "hub") applyHub(await api("/api/hub"));
    } catch (e) {
      toastError(errText(e));
    }
    await this.loadClients();
  }

  trustedCard() {
    if (!isHeadUnit()) return nothing;
    const list = this.clients || [];
    return prefCard({
      cls: "form-card",
      icon: "lock",
      title: t("trusted.title", "Trusted devices"),
      body: html`
        <p class="hint">
          ${t("trusted.hint", "Phones, computers and hubs that paired with a code shown on this screen.")}
        </p>
        ${list.length
          ? html`<div class="trusted-list">
              ${list.map((c) => {
                const kind = CLIENT_KIND[c.kind] || [c.kind, "about"];
                const seen = c.lastSeenMs ? new Date(c.lastSeenMs).toLocaleString() : "—";
                return html`<div class="trusted-row">
                  <div>
                    <strong>${c.name || c.id}</strong>
                    <p class="hint">${t("trusted.kind." + c.kind, kind[0])} · ${t("trusted.last_seen", "Last seen")} ${seen}</p>
                  </div>
                  <wa-button size="small" appearance="outlined" variant="danger" @click=${() => this.revoke(c)}
                    >${t("trusted.revoke", "Remove")}</wa-button
                  >
                </div>`;
              })}
            </div>`
          : html`<p class="hint">${t("trusted.empty", "No devices paired yet.")}</p>`}
      `,
    });
  }

  async joinHub(url) {
    this.joining = true;
    try {
      const res = await postJson("/api/hub", { hubUrl: url, code: this.hubCode });
      applyHub(res);
      if (res.ok) {
        toast(t("hub.paired", "Paired"), { variant: "success" });
        this.hubCode = "";
      } else {
        toastError(t("hub.failed", "Pairing failed") + (res.error ? ": " + res.error : ""));
      }
    } catch (e) {
      toastError(t("hub.failed", "Pairing failed") + ": " + errText(e));
    } finally {
      this.joining = false;
    }
  }

  hubCard() {
    const hub = (session.status && session.status.hub) || session.hubJoin || {};
    const url = this.hubUrl != null ? this.hubUrl : hub.hubUrl || "";
    const paired = !!hub.paired;
    return prefCard({
      cls: "form-card",
      icon: "plugins",
      title: t("hub.title", "Hub"),
      body: html`
        <p class="hint">
          ${paired
            ? (hub.online ? t("hub.online", "Connected to hub") : t("hub.offline", "Paired — reconnecting…")) +
              (hub.hubName ? " · " + hub.hubName : "")
            : t(
                "hub.hint_invite",
                "Add this car from the hub: Fleet → Nearby cars. The hub asks for the code this screen then shows.",
              )}
          ${hub.lastError ? " · " + hub.lastError : ""}
        </p>
        ${hub.nodeUrl ? html`<p class="hint mono">${t("hub.node_url", "Dial-out:")} ${hub.nodeUrl}</p>` : nothing}
        ${paired
          ? html`<div class="pref-actions">
              <wa-button
                appearance="outlined"
                @click=${async () => {
                  applyHub(await api("/api/hub", { method: "DELETE" }).catch(() => hub));
                  this.loadClients();
                }}
                >${t("hub.leave", "Leave")}</wa-button
              >
            </div>`
          : nothing}
        <wa-details summary=${t("hub.manual", "Pair manually with a hub code")} ?open=${!paired && !!this.hubCode}>
          <p class="hint">
            ${t("hub.hint", "Use the hub's node address (port 8788) and a code from the hub's Fleet page.")}
          </p>
          <wa-input
            type="url"
            label=${t("hub.url", "Hub node URL")}
            placeholder="http://192.168.1.10:8788 or https://….ui.nabu.casa/api/oaa_node"
            .value=${url}
            @input=${(ev) => (this.hubUrl = ev.target.value)}
          ></wa-input>
          <wa-input
            label=${t("hub.code", "Pairing code")}
            inputmode="numeric"
            placeholder="123456"
            .value=${this.hubCode}
            @input=${(ev) => (this.hubCode = ev.target.value)}
          ></wa-input>
          <div class="pref-actions">
            <wa-button variant="brand" ?loading=${this.joining} @click=${() => this.joinHub(url)}
              >${t("hub.join", "Join hub")}</wa-button
            >
          </div>
        </wa-details>
      `,
    });
  }

  render() {
    const localeOpts = i18n.locales.map((loc) => ({
      value: loc,
      label: loc === "pt-BR" ? t("locale.pt-BR", "Português") : t("locale." + loc, loc),
    }));
    return html`
      <h1>${t("nav.settings", "Settings")}</h1>
      <div class="grid">
        ${quickEntryCard()}
        ${prefCard({
          icon: "system",
          title: t("prefs.theme", "Tema"),
          body: prefSegment(
            "theme",
            [
              { value: "dark", label: t("theme.dark", "Dark") },
              { value: "light", label: t("theme.light", "Light") },
              { value: "contrast", label: t("theme.contrast", "Contrast") },
            ],
            theme(),
          ),
        })}
        ${prefCard({
          icon: "about",
          title: t("prefs.locale", "Idioma"),
          body: prefSegment("locale", localeOpts, i18n.locale),
        })}
        ${unitDimensionCards()} ${homeCard()} ${setupCard()} ${session.role === "local" ? this.hubCard() : nothing} ${session.role === "local" ? this.trustedCard() : nothing}
      </div>
    `;
  }
}
customElements.define("oaa-page-settings", OaaPageSettings);

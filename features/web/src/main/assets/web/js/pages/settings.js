import { quickEntryCard } from "./shortcuts.js";
import { prefCard, prefSegment } from "../ui/cards.js";
import { theme } from "../theme.js";
import { html } from "../lit.js";
import { state, patch } from "../store.js";
import { t } from "../i18n.js";
import { api, errText } from "../api.js";
import { unitPrefs, UNIT_CHOICES } from "../units.js";

async function updatePrefs(body) {
  const res = await api("/api/prefs", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: body,
  });
  patch({
    prefs: Object.assign({}, state.prefs || {}, {
      homeLat: res.homeLat,
      homeLon: res.homeLon,
      homeRadiusM: res.homeRadiusM != null ? res.homeRadiusM : state.prefs && state.prefs.homeRadiusM,
    }),
  });
  return res;
}

function unitChoiceOpts(dim) {
  return (UNIT_CHOICES[dim] || []).map(function (id) {
    return { value: id, label: t("unit." + id, id) };
  });
}

function unitDimensionCards() {
  const prefs = unitPrefs();
  const dims = [
    {
      key: "temperature",
      icon: "temp",
      title: t("units.dim.temperature", "Temperature"),
    },
    {
      key: "distance",
      icon: "drive",
      title: t("units.dim.distance", "Distance"),
    },
    {
      key: "speed",
      icon: "drive",
      title: t("units.dim.speed", "Speed"),
    },
    {
      key: "fuel_economy",
      icon: "energy",
      title: t("units.dim.fuel", "Fuel economy"),
    },
    {
      key: "energy_economy",
      icon: "battery",
      title: t("units.dim.energy", "Energy economy"),
    },
  ];

  return dims.map(function (d) {
    return prefCard({
      icon: d.icon,
      title: d.title,
      body: prefSegment("unit_" + d.key, unitChoiceOpts(d.key), prefs[d.key], {
        choiceKey: "unit:" + d.key,
      }),
    });
  });
}

export function pageSettings() {
  const th = theme();
  const locale = (state.i18n && state.i18n.locale) || "pt-BR";
  const locales = (state.i18n && state.i18n.locales) || ["pt-BR", "en"];
  const prefs = state.prefs || {};
  const homeLat = prefs.homeLat;
  const homeLon = prefs.homeLon;
  const homeRadius = prefs.homeRadiusM != null ? prefs.homeRadiusM : 100;
  const homeConfigured = homeLat != null && homeLon != null;

  const themeOpts = [
    { value: "dark", label: t("theme.dark", "Dark") },
    { value: "light", label: t("theme.light", "Light") },
    { value: "contrast", label: t("theme.contrast", "Contrast") },
  ];
  const localeOpts = locales.map(function (loc) {
    return {
      value: loc,
      label: loc === "pt-BR" ? t("locale.pt-BR", "Português") : t("locale." + loc, loc),
    };
  });

  return html`
    <h1>${t("nav.settings", "Settings")}</h1>
    <div class="grid">
      ${quickEntryCard()}
      ${prefCard({
        icon: "system",
        title: t("prefs.theme", "Tema"),
        body: prefSegment("theme", themeOpts, th),
      })}
      ${prefCard({
        icon: "about",
        title: t("prefs.locale", "Idioma"),
        body: prefSegment("locale", localeOpts, locale),
      })}
      ${unitDimensionCards()}
      ${prefCard({
        icon: "drive",
        title: t("prefs.home", "Home location"),
        body: html`
          <p class="hint">
            ${homeConfigured
              ? t("prefs.home.coords", "Lat {lat}, Lon {lon}")
                  .replace("{lat}", Number(homeLat).toFixed(5))
                  .replace("{lon}", Number(homeLon).toFixed(5))
              : t("prefs.home.unset", "Not set — use current location")}
          </p>
          <label class="hint" style="display:block;margin-top:8px"
            >${t("prefs.home.radius", "Radius (m)")}</label
          >
          <input
            class="field"
            type="number"
            min="20"
            max="5000"
            step="10"
            style="width:100%;margin:4px 0 8px"
            .value=${String(homeRadius)}
            @change=${async function (ev) {
              const r = parseFloat(ev.target.value);
              if (isNaN(r)) return;
              try {
                await updatePrefs("homeRadiusM=" + encodeURIComponent(String(r)));
              } catch (e) {}
            }}
          />
          <div class="row" style="width:100%;margin:0;gap:8px">
            <button
              class="btn primary"
              style="flex:1"
              @click=${async function () {
                try {
                  const res = await api("/api/location/home/here", {
                    method: "POST",
                  });
                  if (res && res.ok === false) {
                    patch({
                      shortcutMessage:
                        res.error ||
                        t("prefs.home.failed", "Could not read GPS"),
                    });
                    return;
                  }
                  patch({
                    prefs: Object.assign({}, state.prefs || {}, {
                      homeLat: res.homeLat,
                      homeLon: res.homeLon,
                      homeRadiusM: res.homeRadiusM,
                    }),
                  });
                } catch (e) {
                  patch({
                    shortcutMessage: errText(e),
                  });
                }
              }}
            >
              ${t("prefs.home.here", "Use current location")}
            </button>
            <button
              class="btn ghost"
              style="flex:1"
              ?disabled=${!homeConfigured}
              @click=${async function () {
                try {
                  await updatePrefs("clearHome=1");
                } catch (e) {}
              }}
            >
              ${t("prefs.home.clear", "Clear")}
            </button>
          </div>
        `,
      })}
      ${prefCard({
        icon: "system",
        title: t("prefs.setup", "Setup"),
        body: html`<div class="row" style="width:100%;margin:0">
          <button
            class="btn"
            style="flex:1"
            @click=${function () {
              patch({ showSetup: true });
            }}
          >
            ${t("setup.open", "Abrir setup")}
          </button>
          <button
            class="btn ghost"
            style="flex:1"
            @click=${async function () {
              const setup = await api("/api/setup", {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: "reset=1",
              });
              patch({ setup: setup, showSetup: true });
            }}
          >
            ${t("setup.reset", "Resetar setup")}
          </button>
        </div>`,
      })}
      ${state.role === "local"
        ? prefCard({
            icon: "plugins",
            title: t("hub.title", "Hub"),
            body: hubJoinBody(),
          })
        : ""}
    </div>
  `;
}

function hubJoinBody() {
  const hub = (state.status && state.status.hub) || state.hubJoin || {};
  const url = hub.hubUrl || "";
  const paired = !!hub.paired;
  const online = !!hub.online;
  return html`
    <p class="hint">
      ${paired
        ? online
          ? t("hub.online", "Connected to hub")
          : t("hub.offline", "Paired — reconnecting…")
        : t(
            "hub.hint",
            "Pair this car with a self-hosted OAA hub. Use the hub's node address (port 8788), shown on the hub's Fleet page.",
          )}
      ${hub.lastError ? " · " + hub.lastError : ""}
    </p>
    <label class="hint" style="display:block;margin-top:8px"
      >${t("hub.url", "Hub node URL")}</label
    >
    <input
      class="field"
      type="url"
      id="hubUrlInput"
      placeholder="http://192.168.1.10:8788 or https://….ui.nabu.casa/api/oaa_node"
      style="width:100%;margin:4px 0 8px"
      .value=${url}
    />
    ${hub.nodeUrl
      ? html`<p class="hint mono">
          ${t("hub.node_url", "Dial-out:")} ${hub.nodeUrl}
        </p>`
      : ""}
    <label class="hint" style="display:block"
      >${t("hub.code", "Pairing code")}</label
    >
    <input
      class="field"
      type="text"
      id="hubCodeInput"
      inputmode="numeric"
      placeholder="123456"
      style="width:100%;margin:4px 0 8px"
    />
    <div class="row" style="width:100%;margin:0;gap:8px">
      <button
        class="btn primary"
        style="flex:1"
        @click=${async function () {
          const hubUrl = (document.getElementById("hubUrlInput") || {}).value || "";
          const code = (document.getElementById("hubCodeInput") || {}).value || "";
          try {
            const res = await api("/api/hub", {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ hubUrl: hubUrl, code: code }),
            });
            patch({
              hubJoin: res,
              status: Object.assign({}, state.status || {}, { hub: res }),
              hubMessage: res.ok
                ? { ok: true, text: t("hub.paired", "Paired") }
                : { ok: false, text: t("hub.failed", "Pairing failed") + (res.error ? ": " + res.error : "") },
            });
          } catch (e) {
            patch({ hubMessage: { ok: false, text: t("hub.failed", "Pairing failed") + ": " + errText(e) } });
          }
        }}
      >
        ${t("hub.join", "Join hub")}
      </button>
      <button
        class="btn ghost"
        style="flex:1"
        ?disabled=${!paired}
        @click=${async function () {
          try {
            const res = await api("/api/hub", { method: "DELETE" });
            patch({
              hubJoin: res,
              status: Object.assign({}, state.status || {}, { hub: res }),
            });
          } catch (e) {}
        }}
      >
        ${t("hub.leave", "Leave")}
      </button>
    </div>
    ${state.hubMessage
      ? html`<p class="hint" style=${state.hubMessage.ok ? "" : "color:var(--warn, #c90)"}>
          ${state.hubMessage.text}
        </p>`
      : ""}
  `;
}

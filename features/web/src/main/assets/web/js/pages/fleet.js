import { html } from "../lit.js";
import { state, patch, notify } from "../store.js";
import { t } from "../i18n.js";
import { api, errText } from "../api.js";
import { selectNode } from "../node-select.js";
import { prefCard } from "../ui/cards.js";

const OTA_POLL_MS = 3000;
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

let otaPollTimer = 0;

function otaActive(nodes) {
  return nodes.some(function (n) {
    return n.ota && OTA_TERMINAL.indexOf(n.ota.state) < 0;
  });
}

export async function loadFleet() {
  if (state.role !== "hub") return;
  if (otaPollTimer) clearTimeout(otaPollTimer);
  otaPollTimer = 0;
  try {
    const fleet = await api("/api/nodes");
    patch({ fleet: fleet });
  } catch (e) {
    patch({ fleet: { nodes: [] } });
  }
  const nodes = (state.fleet && state.fleet.nodes) || [];
  if (state.page === "fleet" && otaActive(nodes)) {
    otaPollTimer = setTimeout(loadFleet, OTA_POLL_MS);
  }
}

/** Node-face URL to type on the car: the published one, else this host on the node port. */
function nodeDialUrl(offer) {
  const hub = state.hubJoin || {};
  const published = (offer && offer.publicNodeUrl) || hub.publicNodeUrl;
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

export function pageFleet() {
  const nodes = (state.fleet && state.fleet.nodes) || [];
  const offer = state.pairingOffer;

  return html`
    <h1>${t("nav.fleet", "Fleet")}</h1>
    <p class="hint">
      ${t(
        "fleet.hint",
        "Open a car to control it with the same UI. Generate a pairing code for a new car.",
      )}
    </p>
    <div class="grid">
      ${prefCard({
        icon: "plugins",
        title: t("fleet.pairing", "Pairing"),
        body: html`
          <p class="hint">
            ${offer
              ? t("fleet.code", "Code: {code} (expires soon)").replace(
                  "{code}",
                  offer.code,
                )
              : t("fleet.no_code", "No active code")}
          </p>
          <p class="hint mono">${t("fleet.cloud_url", "Cars dial:")} ${nodeDialUrl(offer)}</p>
          <button
            class="btn primary"
            style="margin-top:8px"
            @click=${async function () {
              try {
                const res = await api("/api/nodes/pairing", { method: "POST" });
                patch({ pairingOffer: res });
                notify();
              } catch (e) {
                patch({
                  pairingOffer: null,
                  shortcutMessage: errText(e),
                });
                notify();
              }
            }}
          >
            ${t("fleet.generate", "Generate pairing code")}
          </button>
        `,
      })}
      ${nodes.length
        ? nodes.map(function (n) {
            const online = !!n.online;
            return prefCard({
              icon: "sensor",
              title: n.name || n.id,
              body: html`
                <p class="hint">
                  ${online
                    ? t("fleet.online", "Online")
                    : t("fleet.offline", "Offline")}
                  ${n.integration ? " · " + n.integration : ""}
                </p>
                ${appLine(n.app) ? html`<p class="hint mono">${appLine(n.app)}</p>` : ""}
                ${n.ota
                  ? html`<p class="hint" style=${n.ota.state === "failed" ? "color:var(--warn, #c90)" : ""}>
                      ${otaLine(n.ota)}
                    </p>`
                  : ""}
                <div class="row" style="gap:8px;margin-top:8px;width:100%">
                  <button
                    class="btn primary"
                    style="flex:1"
                    ?disabled=${!online}
                    @click=${function () {
                      selectNode(n.id);
                    }}
                  >
                    ${t("fleet.open", "Open")}
                  </button>
                  <button
                    class="btn"
                    @click=${async function () {
                      if (
                        !confirm(
                          t("fleet.forget_confirm", "Forget this car?"),
                        )
                      )
                        return;
                      try {
                        await api("/api/nodes/" + encodeURIComponent(n.id), {
                          method: "DELETE",
                        });
                        if (state.selectedNodeId === n.id) await selectNode("");
                        await loadFleet();
                        notify();
                      } catch (e) {}
                    }}
                  >
                    ${t("fleet.forget", "Forget")}
                  </button>
                </div>
              `,
            });
          })
        : prefCard({
            icon: "about",
            title: t("fleet.empty", "No cars paired"),
            body: html`<p class="hint">${t(
              "fleet.empty_hint",
              "Generate a code, then open Settings → Hub on the car.",
            )}</p>`,
          })}
    </div>
  `;
}

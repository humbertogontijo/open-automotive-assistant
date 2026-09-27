import { html, nothing } from "lit";
import { api, postForm } from "../api.js";
import { session } from "../store.js";
import { t } from "../i18n.js";
import { OaaElement } from "../lit/oaa-element.js";

export function shouldShowSetup(setup) {
  return !!(setup && setup.needsSetup);
}

function hostCmd() {
  const s = session.setup;
  return (
    (s && s.adbHints && s.adbHints[0]) ||
    "./tools/oaa-setup -i " + ((session.status && session.status.integration) || "PLATFORM_ID") + " -H <ip> setup"
  );
}

async function requestRuntime() {
  session.setupMsg = "Solicitando…";
  await api("/api/setup/actions/request-runtime", { method: "POST" });
  session.setupMsg = "Aceite o diálogo no head unit, depois Verifique de novo.";
}

async function copyHostCmd() {
  const cmd = hostCmd();
  try {
    await navigator.clipboard.writeText(cmd);
    session.setupMsg = "Copiado: " + cmd;
  } catch (e) {
    session.setupMsg = cmd;
  }
}

async function dismissSetup() {
  const setup = await postForm("/api/setup", "dismiss=1");
  session.$patch({ setup, showSetup: false, setupMsg: "" });
}

async function recheckSetup() {
  const setup = await api("/api/setup");
  session.$patch({ setup, showSetup: shouldShowSetup(setup), setupMsg: "" });
}

function setupBody(s) {
  const actions = s.actions || {};
  return html`
    <p class="sub">${t("setup.sub", "Grant runtime permissions on the HU.")}</p>
    ${s.accessMode
      ? html`<p class="sub">
          VHAL: <code class="mono">${s.accessMode}</code>${s.accessMode === "grpc" ? " (VenusVehicleServer)" : ""}
        </p>`
      : nothing}
    ${(s.steps || []).map(
      (st) => html`<div class="setup-step ${st.done ? "done" : ""}">
        <div class="mark">${st.done ? "✓" : "·"}</div>
        <div class="body">
          <h3>${t(st.titleKey || st.title, st.title || st.id)}</h3>
          <p>${t(st.detailKey || st.detail, st.detail || "")}</p>
        </div>
      </div>`,
    )}
    <ul class="setup-perms">
      ${(s.permissions || []).map(
        (p) => html`<li>
          <span>${t(p.labelKey || p.label, p.label || p.id)} <span class="mono">(${p.kind})</span></span>
          <wa-badge variant=${p.granted ? "success" : "warning"}>${p.granted ? "OK" : "Pendente"}</wa-badge>
        </li>`,
      )}
    </ul>
    <p class="sub">${session.setupMsg || ""}</p>
    <div class="setup-actions">
      <wa-button variant="brand" @click=${requestRuntime}
        >${t(actions.grantKey || "setup.action.grant", actions.grant || "Conceder permissões")}</wa-button
      >
      <wa-button appearance="outlined" @click=${copyHostCmd}
        >${t(actions.hostKey || "setup.action.host", actions.host || "Comando no PC")}</wa-button
      >
    </div>
    <p class="sub">ADB / host</p>
    <p>${(s.adbHints || []).map((h) => html`<code class="mono">${h}</code><br />`)}</p>
    <div slot="footer" class="dialog-actions">
      <wa-button appearance="outlined" @click=${recheckSetup}
        >${t(actions.refreshKey || "setup.action.refresh", actions.refresh || "Atualizar")}</wa-button
      >
      ${s.runtimeOk && s.hasBasicTelemetry
        ? html`<wa-button variant="brand" @click=${dismissSetup}>${t("setup.continue", "Continuar")}</wa-button>`
        : nothing}
    </div>
  `;
}

/** First-run permissions dialog; only Continue (once the car is readable) closes it. */
export class OaaSetupOverlay extends OaaElement {
  render() {
    const s = session.setup;
    const open = !!(s && session.showSetup);
    return html`<wa-dialog
      class="oaa-dialog setup-dialog"
      label=${t("setup.title", "Configuração")}
      without-header
      ?open=${open}
      @wa-hide=${(ev) => {
        if (ev.target === ev.currentTarget && session.showSetup) ev.preventDefault();
      }}
    >
      ${open ? html`<h1>${t("setup.title", "Configuração")}</h1>${setupBody(s)}` : nothing}
    </wa-dialog>`;
  }
}
customElements.define("oaa-setup-overlay", OaaSetupOverlay);

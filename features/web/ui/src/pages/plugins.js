import { html, nothing } from "lit";
import { live } from "lit/directives/live.js";
import { repeat } from "lit/directives/repeat.js";
import { errText, postJson } from "../api.js";
import { session } from "../store.js";
import { prefCard } from "../ui/cards/prefs.js";
import { boolToggle } from "../ui/cards/bool.js";
import { t } from "../i18n.js";
import { OaaPage } from "../lit/oaa-page.js";
import { toastError } from "../ui/toast.js";
import { reloadStatus } from "../actions.js";

function pluginList() {
  return (session.status && session.status.plugins) || [];
}

function isConfigured(plugin) {
  const cfg = plugin.config || {};
  const st = plugin.status || {};
  if (cfg.configured === true || st.configured === true) return true;
  if (cfg.tokenSet && cfg.baseUrl) return true;
  if (st.hasToken && st.baseUrl) return true;
  return false;
}

function isEnabled(plugin) {
  const cfg = plugin.config || {};
  const st = plugin.status || {};
  if (cfg.enabled === false || st.enabled === false) return false;
  if (cfg.enabled === true || st.enabled === true) return true;
  return isConfigured(plugin);
}

function fieldValue(plugin, f, draft) {
  const cfg = plugin.config || {};
  const key = f.key;
  if (f.type === "bool") {
    if (draft[key] != null) return !!draft[key];
    if (cfg[key] != null) return !!cfg[key];
    return true;
  }
  if (f.type === "password") {
    return draft[key] != null ? String(draft[key]) : "";
  }
  if (draft[key] != null) return String(draft[key]);
  if (cfg[key] != null) return String(cfg[key]);
  return "";
}

function labeledGrid(label, cards) {
  if (!cards || !cards.length) return nothing;
  return html`
    <h2 class="page-label">${label}</h2>
    <div class="grid">${cards}</div>
  `;
}

class OaaPagePlugins extends OaaPage {
  static properties = {
    editId: { state: true },
    busy: { state: true },
  };

  constructor() {
    super();
    /** @type {string | null} */
    this.editId = null;
    /** @type {Record<string, any>} */
    this.draft = {};
    this.busy = false;
  }

  /** @param {string | null} id */
  edit(id) {
    this.editId = id;
    this.draft = {};
  }

  async save(plugin) {
    const fields = (plugin.schema && plugin.schema.fields) || [];
    const draft = this.draft;
    const body = {};
    fields.forEach(function (f) {
      const key = f.key;
      if (f.type === "bool") {
        body[key] = fieldValue(plugin, f, draft);
        return;
      }
      const val = draft[key] != null ? String(draft[key]) : "";
      if (f.type === "password" && !val) return;
      if (val !== "" || !f.optional) body[key] = val;
    });
    if (body.enabled == null) body.enabled = true;
    this.busy = true;
    try {
      await postJson("/api/plugins/" + encodeURIComponent(plugin.id), body);
      this.edit(null);
      await reloadStatus();
    } catch (e) {
      toastError(errText(e));
    } finally {
      this.busy = false;
    }
  }

  fieldHtml(plugin, f) {
    const cfg = plugin.config || {};
    const key = f.key;
    const label = f.label || key;
    if (f.type === "bool") {
      return html`<div class="plugin-field">
        <p class="hint">${label}</p>
        ${boolToggle(fieldValue(plugin, f, this.draft), (val) => {
          this.draft[key] = val === "1";
          this.requestUpdate();
        })}
      </div>`;
    }
    const isPassword = f.type === "password";
    const tokenSaved = isPassword && cfg.tokenSet;
    return html`<wa-input
      class="plugin-field"
      type=${isPassword ? "password" : "text"}
      label=${label + (f.optional ? "" : " *")}
      placeholder=${tokenSaved ? t("plugins.token_replace", "Paste a new token to replace (optional)") : f.placeholder || ""}
      ?password-toggle=${isPassword}
      autocomplete=${isPassword ? "off" : nothing}
      .value=${live(fieldValue(plugin, f, this.draft))}
      @input=${(ev) => (this.draft[key] = ev.target.value)}
      >${tokenSaved
        ? html`<span slot="hint"
            >${t("plugins.token_saved", "A token is already saved")}${cfg.tokenHint ? " (" + cfg.tokenHint + "). " : ". "}
            ${t("plugins.token_keep", "Leave empty to keep it, or paste a new token to replace.")}</span
          >`
        : nothing}</wa-input
    >`;
  }

  setupForm(plugin) {
    const fields = (plugin.schema && plugin.schema.fields) || [];
    return prefCard({
      cls: "form-card",
      icon: "energy",
      title: plugin.displayName || plugin.id,
      body: html`
        ${repeat(
          fields,
          (f) => f.key,
          (f) => this.fieldHtml(plugin, f),
        )}
        <div class="pref-actions">
          <wa-button variant="brand" ?loading=${this.busy} @click=${() => this.save(plugin)}
            >${t("plugins.save", "Save")}</wa-button
          >
          <wa-button appearance="outlined" @click=${() => this.edit(null)}>${t("plugins.cancel", "Cancel")}</wa-button>
        </div>
      `,
    });
  }

  catalogCard(plugin) {
    return prefCard({
      icon: "energy",
      title: plugin.displayName || plugin.id,
      body: html`<wa-button class="plugin-add full-width" variant="brand" @click=${() => this.edit(plugin.id)}
        >${t("plugins.add", "Add / Set up")}</wa-button
      >`,
    });
  }

  activeCard(plugin) {
    const cfg = plugin.config || {};
    const st = plugin.status || {};
    const enabled = isEnabled(plugin);
    const connected = !!(cfg.connected || st.connected);
    return prefCard({
      icon: "energy",
      title: plugin.displayName || plugin.id,
      sub: enabled
        ? connected
          ? t("plugins.connected", "Connected")
          : t("plugins.disconnected", "Not connected")
        : t("plugins.status.disabled", "Disabled"),
      body: html`<div class="pref-actions">
        <wa-button class="plugin-edit" appearance="outlined" @click=${() => this.edit(plugin.id)}
          >${t("plugins.configure", "Configure")}</wa-button
        >
        <wa-button
          class="plugin-toggle"
          appearance="outlined"
          @click=${async () => {
            await postJson("/api/plugins/" + encodeURIComponent(plugin.id), { enabled: !enabled }).catch((e) => toastError(errText(e)));
            await reloadStatus();
          }}
          >${enabled ? t("plugins.disable", "Disable") : t("plugins.enable", "Enable")}</wa-button
        >
      </div>`,
    });
  }

  render() {
    const plugins = pluginList();
    const editing = this.editId ? plugins.find((p) => p.id === this.editId) : null;
    const title = html`<h1>${t("nav.plugins", "Plugins")}</h1>`;
    if (editing) return html`${title}<div class="grid">${this.setupForm(editing)}</div>`;
    if (!plugins.length) {
      return html`${title}
      ${labeledGrid(t("plugins.available", "Available"), [
        prefCard({ icon: "energy", title: t("plugins.empty", "No plugins available"), body: nothing }),
      ])}`;
    }
    return html`${title}
    ${labeledGrid(
      t("plugins.available", "Available"),
      plugins.filter((p) => !isConfigured(p)).map((p) => this.catalogCard(p)),
    )}
    ${labeledGrid(
      t("plugins.installed", "Installed"),
      plugins.filter(isConfigured).map((p) => this.activeCard(p)),
    )}`;
  }
}
customElements.define("oaa-page-plugins", OaaPagePlugins);

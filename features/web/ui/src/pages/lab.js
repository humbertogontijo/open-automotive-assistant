import { html, nothing } from "lit";
import { repeat } from "lit/directives/repeat.js";
import "@awesome.me/webawesome/dist/components/tab-group/tab-group.js";
import "@awesome.me/webawesome/dist/components/tab/tab.js";
import "@awesome.me/webawesome/dist/components/tab-panel/tab-panel.js";
import { session, catalog, lab } from "../store.js";
import { t, entityLabel, entityValueLabel } from "../i18n.js";
import { api, errText, postForm } from "../api.js";
import { appUrl, wsUrl } from "../base.js";
import { fmt } from "../format.js";
import { segmentToggle } from "../ui/cards/choice.js";
import { OaaElement } from "../lit/oaa-element.js";
import { OaaPage } from "../lit/oaa-page.js";

/** @typedef {"vhal" | "obd2" | "entities"} LabTab */
/** @typedef {"all" | "bound" | "missing"} BoundFilter */

const LAB_ROW_CAP = 400;
const LOG_LINE_CAP = 500;

/** Rows with a lowercase search string, built once per source array. @type {WeakMap<object[], { row: any, hay: string }[]>} */
const indexed = new WeakMap();

/** @param {any[]} src @param {(src: any[]) => any[]} [build] */
function indexRows(src, build) {
  let out = indexed.get(src);
  if (!out) {
    out = (build ? build(src) : src).map((r) => ({
      row: r,
      hay: (r.name + r.family + r.status + (r.permission || "") + (r.value || "") + (r.entity || "")).toLowerCase(),
    }));
    indexed.set(src, out);
  }
  return out;
}

/** @param {any[]} entities */
function entityRows(entities) {
  return entities.map((e) => ({
    name: e.id || entityLabel(e) || "",
    family: e.domain || e.group || "",
    status: e.status || "",
    value: entityValueLabel(e) || e.value || "",
    permission: e.group || "",
    entity: e.id || "",
  }));
}

/** @param {LabTab} tab @param {string} query @param {BoundFilter} bound */
function probeRows(tab, query, bound) {
  const q = query.toLowerCase();
  let rows;
  if (tab === "entities") {
    rows = indexRows(catalog.entities, entityRows);
  } else {
    const src = tab === "obd2" ? lab.obd2 : lab.probe;
    rows = indexRows((src && src.results) || []);
  }
  const out = [];
  for (const { row, hay } of rows) {
    if (tab === "vhal") {
      if (bound === "bound" && !row.entity) continue;
      if (bound === "missing" && row.entity) continue;
    }
    if (!q || hay.includes(q)) out.push(row);
  }
  return out;
}

function logStreamUrl(token) {
  let url = wsUrl("/debug/logs/stream?token=" + encodeURIComponent(token));
  if (session.role === "hub" && session.selectedNodeId) {
    url += "&node=" + encodeURIComponent(session.selectedNodeId);
  }
  return url;
}

function adbHint() {
  const hint = (lab.info && lab.info.adbHint) || "";
  if (!hint) return nothing;
  return html`<span class="sub">${t("lab.adb", "ADB")}: <code class="mono">${hint}</code></span>`;
}

/** Live logcat tail over `/debug/logs/stream`; the socket lives only while mounted. */
class OaaLabLogs extends OaaElement {
  static properties = {
    token: {},
    on: { state: true },
    text: { state: true },
    error: { state: true },
  };

  constructor() {
    super();
    this.token = "";
    this.on = false;
    this.text = "";
    this.error = "";
    /** @type {WebSocket|null} */
    this.socket = null;
    /** @type {string[]} */
    this.lines = [];
    this.flushTimer = 0;
    this.stick = true;
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this.stop();
  }

  start() {
    this.stop();
    this.lines = [];
    const ws = new WebSocket(logStreamUrl(this.token));
    this.socket = ws;
    ws.onmessage = (ev) => {
      if (this.socket !== ws) return;
      this.lines.push(String(ev.data));
      if (this.lines.length > LOG_LINE_CAP) this.lines.splice(0, this.lines.length - LOG_LINE_CAP);
      if (!this.flushTimer) {
        this.flushTimer = window.setTimeout(() => {
          this.flushTimer = 0;
          this.text = this.lines.join("\n");
        }, 250);
      }
    };
    ws.onclose = (ev) => {
      if (this.socket !== ws) return;
      this.socket = null;
      this.on = false;
      this.error = ev.reason || "";
    };
    this.on = true;
    this.text = "";
    this.error = "";
    this.stick = true;
  }

  stop() {
    const ws = this.socket;
    this.socket = null;
    clearTimeout(this.flushTimer);
    this.flushTimer = 0;
    if (ws) ws.close();
    this.on = false;
  }

  willUpdate(changed) {
    const pre = this.querySelector("pre");
    if (changed.has("text") && pre) this.stick = pre.scrollHeight - pre.scrollTop - pre.clientHeight < 24;
  }

  updated(changed) {
    const pre = this.querySelector("pre");
    if (changed.has("text") && pre && this.stick) pre.scrollTop = pre.scrollHeight;
  }

  render() {
    return html`
      <wa-card class="lab-logs">
        <div class="lab-logs-bar">
          <wa-button
            variant=${this.on ? "danger" : "brand"}
            appearance=${this.on ? "outlined" : "filled"}
            ?disabled=${!this.token}
            @click=${() => (this.on ? this.stop() : this.start())}
          >
            ${this.on ? t("lab.logs.stop", "Stop live logs") : t("lab.logs.start", "Live logs")}
          </wa-button>
          ${adbHint()}
        </div>
        ${this.error ? html`<wa-callout variant="warning" size="s">${this.error}</wa-callout>` : nothing}
        ${this.on || this.text ? html`<pre class="mono lab-logs-out">${this.text || "…"}</pre>` : nothing}
      </wa-card>
    `;
  }
}
customElements.define("oaa-lab-logs", OaaLabLogs);

function labToken() {
  const info = lab.info || {};
  return (info.contributor && (info.token || session.token)) || "";
}

class OaaPageLab extends OaaPage {
  static properties = {
    tab: { state: true },
    query: { state: true },
    bound: { state: true },
    overrideDraft: { state: true },
    hint: { state: true },
    probing: { state: true },
  };

  constructor() {
    super();
    /** @type {LabTab} */
    this.tab = "vhal";
    this.query = "";
    /** @type {BoundFilter} */
    this.bound = "all";
    /** @type {string | null} */
    this.overrideDraft = null;
    this.hint = "";
    this.probing = false;
  }

  async load() {
    try {
      const info = await api("/api/lab");
      lab.info = info;
      session.token = (info && info.token) || "";
      await this.fetchTab(this.tab, false);
    } catch (e) {}
  }

  /** @param {LabTab} tab @param {boolean} force */
  async fetchTab(tab, force) {
    const tok = encodeURIComponent(session.token);
    const f = force ? "force=1&" : "";
    if (tab === "obd2") {
      if (!force && lab.obd2) return;
      lab.obd2 = await api("/debug/obd2?" + f + "token=" + tok).catch(() => ({
        results: [],
        summary: { available: false },
      }));
    } else if (tab === "entities") {
      if (force) catalog.entities = await api("/api/entities");
    } else if (force || !lab.probe) {
      lab.probe = await api("/debug/probe?" + f + "token=" + tok);
    }
  }

  async reprobe() {
    this.probing = true;
    try {
      await this.fetchTab(this.tab, true);
    } catch (e) {
      this.hint = errText(e);
    } finally {
      this.probing = false;
    }
  }

  async setContributor(on) {
    try {
      const next = await postForm("/api/lab/contributor", "enabled=" + (on ? "1" : "0"));
      lab.info = next;
      session.token = (next && next.token) || "";
    } catch (e) {
      this.hint = errText(e);
    }
  }

  async applyOverride(current) {
    const id = this.overrideDraft != null ? this.overrideDraft : current;
    try {
      const res = await postForm("/api/lab/integration-override", "id=" + encodeURIComponent(id));
      lab.info = res;
      this.hint = (res && res.hint) || "";
      this.overrideDraft = null;
    } catch (e) {
      this.hint = errText(e);
    }
  }

  contributorCard() {
    const info = lab.info || {};
    const token = labToken();
    const override = info.integrationOverride || "";
    return html`<wa-card class="lab-card">
      <div class="lab-row">
        <wa-switch ?checked=${!!info.contributor} @change=${(ev) => this.setContributor(ev.target.checked)}
          >${t("lab.contributor", "Contributor mode")}</wa-switch
        >
        <span class="mono">${t("lab.token", "Token")}: ${token ? html`<code>${token}</code>` : "—"}</span>
      </div>
      <div class="lab-row lab-row-end">
        <wa-select
          class="lab-override"
          label=${t("lab.override", "Integration override")}
          .value=${this.overrideDraft != null ? this.overrideDraft : override}
          @change=${(ev) => (this.overrideDraft = ev.target.value)}
        >
          <wa-option value="">${t("lab.override.auto", "Auto (fingerprint match)")}</wa-option>
          ${(info.integrations || []).map((id) => html`<wa-option value=${id}>${id}</wa-option>`)}
        </wa-select>
        <wa-button variant="brand" @click=${() => this.applyOverride(override)}
          >${t("lab.override.apply", "Apply")}</wa-button
        >
      </div>
      ${this.hint
        ? html`<wa-callout variant="warning" size="s" class="lab-hint">${this.hint}</wa-callout>`
        : html`<p class="sub lab-hint">
            ${t("lab.override.hint", "Override applies after force-stop or reboot. Matched now: ")}<code class="mono"
              >${info.integration || "—"}</code
            >
          </p>`}
    </wa-card>`;
  }

  /** @param {LabTab} tab */
  sourceHint(tab) {
    if (tab === "obd2") return t("lab.source.obd2", "OBD2_LIVE_FRAME / OBD2_FREEZE_FRAME (VHAL)");
    if (tab === "entities") return t("lab.source.entities", "Bound product entities (ControlCatalog + i18n description)");
    return t("lab.source.vhal", "Full VHAL catalog — filter Missing to see props not yet cards");
  }

  /** @param {LabTab} tab */
  panelHtml(tab) {
    const token = labToken();
    const p = tab === "obd2" ? lab.obd2 : tab === "entities" ? null : lab.probe;
    const sum =
      tab === "entities"
        ? { source: "ControlCatalog /api/entities", count: catalog.entities.length }
        : p && p.summary
          ? p.summary
          : p;
    const all = probeRows(tab, this.query, this.bound);
    const rows = all.length > LAB_ROW_CAP ? all.slice(0, LAB_ROW_CAP) : all;
    const showEntityCol = tab === "vhal";
    const probeTotal = tab === "vhal" && lab.probe && lab.probe.results ? lab.probe.results.length : 0;
    return html`
      <p class="sub">${this.sourceHint(tab)}</p>
      ${tab === "vhal"
        ? html`<p class="hint">
              ${t(
                "lab.gap.hint",
                "All VHAL props from platform.json are listed here. Only props with a product description (i18n + ControlCatalog + platform.json entity) become cards. Use Missing to find candidates.",
              )}
            </p>
            ${segmentToggle({
              options: [
                { value: "all", label: t("lab.bound.all", "All") },
                { value: "bound", label: t("lab.bound.bound", "Bound (cards)") },
                { value: "missing", label: t("lab.bound.missing", "Missing") },
              ],
              current: this.bound,
              onSelect: (v) => (this.bound = /** @type {BoundFilter} */ (v || "all")),
            })}`
        : nothing}
      <div class="lab-row lab-row-end">
        <wa-button variant="brand" ?loading=${this.probing} @click=${() => this.reprobe()}
          >${t("lab.probe", "Re-probe")}</wa-button
        >
        <wa-button appearance="outlined" href=${appUrl("/debug/export?token=" + encodeURIComponent(token))}
          >${t("lab.export", "Export zip")}</wa-button
        >
        <wa-input
          class="lab-filter"
          placeholder=${t("lab.filter", "filter…")}
          with-clear
          .value=${this.query}
          @input=${(ev) => (this.query = ev.target.value || "")}
        ></wa-input>
      </div>
      <pre class="mono">${JSON.stringify(sum || { tip: t("lab.probe.tip", "Click Re-probe") }, null, 2)}</pre>
      <p class="sub">
        ${t("lab.rows", "Showing")} ${rows.length}
        ${all.length > rows.length ? html` / ${all.length}` : probeTotal ? html` / ${probeTotal}` : nothing}
      </p>
      <div class="table-scroll lab-table">
        <table class="table">
          <thead>
            <tr>
              <th>${t("lab.col.name", "Name")}</th>
              <th>${t("lab.col.family", "Family")}</th>
              ${showEntityCol ? html`<th>${t("lab.col.entity", "Entity")}</th>` : nothing}
              <th>${t("lab.col.status", "Status")}</th>
              <th>${t("lab.col.value", "Value")}</th>
              <th>${t("lab.col.perm", "Perm")}</th>
            </tr>
          </thead>
          <tbody>
            ${repeat(
              rows,
              (r) => r.name + "|" + (r.entity || "") + "|" + r.family,
              (r) => html`<tr>
                <td class="mono">${r.name}</td>
                <td>${r.family}</td>
                ${showEntityCol ? html`<td class="mono">${r.entity || "—"}</td>` : nothing}
                <td>${r.status}</td>
                <td class="mono">${fmt(r.value)}</td>
                <td class="mono">${fmt(r.permission)}</td>
              </tr>`,
            )}
          </tbody>
        </table>
      </div>
    `;
  }

  render() {
    /** @type {[LabTab, string][]} */
    const tabs = [
      ["vhal", t("lab.tab.vhal", "VHAL catalog")],
      ["obd2", t("lab.tab.obd2", "OBD2")],
      ["entities", t("lab.tab.entities", "Product entities")],
    ];
    return html`
      <h1>${t("lab.title", "Lab / Contributor")}</h1>
      <p class="sub">${t("lab.sub", "Probe data sources · enable Contributor mode for /debug writes")}</p>
      ${this.contributorCard()}
      <oaa-lab-logs .token=${labToken()}></oaa-lab-logs>
      <wa-card class="lab-card">
        <wa-tab-group
          class="lab-tabs"
          .active=${this.tab}
          @wa-tab-show=${(/** @type {CustomEvent} */ ev) => {
            const name = /** @type {LabTab} */ (ev.detail.name);
            if (ev.target !== ev.currentTarget || name === this.tab) return;
            this.tab = name;
            this.fetchTab(name, false).catch((e) => (this.hint = errText(e)));
          }}
        >
          ${tabs.map(([name, label]) => html`<wa-tab slot="nav" panel=${name}>${label}</wa-tab>`)}
          ${tabs.map(
            ([name]) => html`<wa-tab-panel name=${name}>${this.tab === name ? this.panelHtml(name) : nothing}</wa-tab-panel>`,
          )}
        </wa-tab-group>
      </wa-card>
    `;
  }
}
customElements.define("oaa-page-lab", OaaPageLab);

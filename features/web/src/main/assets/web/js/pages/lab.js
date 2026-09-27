import { html, nothing, repeat } from "../lit.js";
import { state, patch, notify } from "../store.js";
import { t, entityLabel, entityValueLabel } from "../i18n.js";
import { api, fmt, errText } from "../api.js";
import { prefSegment } from "../ui/cards.js";

function probeRows() {
  const tab = state.labTab || "vhal";
  const q = (state.probeFilter || "").toLowerCase();
  const boundFilter = state.probeBoundFilter || "all";
  let rows;
  if (tab === "entities") {
    rows = (state.entities || []).map(function (e) {
      return {
        name: e.id || entityLabel(e) || "",
        family: e.domain || e.group || "",
        status: e.status || "",
        value: entityValueLabel(e) || e.value || "",
        permission: e.group || "",
        entity: e.id || "",
      };
    });
  } else {
    const src = tab === "obd2" ? state.obd2 : state.probe;
    rows = (src && src.results) || [];
  }
  return rows.filter(function (r) {
    if (tab === "vhal") {
      if (boundFilter === "bound" && !r.entity) return false;
      if (boundFilter === "missing" && r.entity) return false;
    }
    if (!q) return true;
    return (
      (
        r.name +
        r.family +
        r.status +
        (r.permission || "") +
        (r.value || "") +
        (r.entity || "")
      )
        .toLowerCase()
        .indexOf(q) >= 0
    );
  });
}

const LAB_ROW_CAP = 400;
const LOG_LINE_CAP = 500;

let logSocket = null;
let logLines = [];
let logFlushTimer = 0;

function logStreamUrl(token) {
  const loc = window.location;
  let url =
    (loc.protocol === "https:" ? "wss:" : "ws:") +
    "//" +
    loc.host +
    "/debug/logs/stream?token=" +
    encodeURIComponent(token);
  if (state.role === "hub" && state.selectedNodeId) {
    url += "&node=" + encodeURIComponent(state.selectedNodeId);
  }
  return url;
}

function flushLogs() {
  logFlushTimer = 0;
  patch({ labLogs: logLines.join("\n") });
}

export function stopLabLogs() {
  const ws = logSocket;
  logSocket = null;
  if (logFlushTimer) clearTimeout(logFlushTimer);
  logFlushTimer = 0;
  if (ws) ws.close();
  if (state.labLogsOn) patch({ labLogsOn: false });
}

function startLabLogs(token) {
  stopLabLogs();
  logLines = [];
  const ws = new WebSocket(logStreamUrl(token));
  logSocket = ws;
  ws.onmessage = function (ev) {
    if (logSocket !== ws) return;
    logLines.push(String(ev.data));
    if (logLines.length > LOG_LINE_CAP) logLines.splice(0, logLines.length - LOG_LINE_CAP);
    if (!logFlushTimer) logFlushTimer = setTimeout(flushLogs, 250);
  };
  ws.onclose = function (ev) {
    if (logSocket !== ws) return;
    logSocket = null;
    patch({ labLogsOn: false, labLogsError: ev.reason || "" });
  };
  patch({ labLogsOn: true, labLogs: "", labLogsError: "" });
}

function logsCard(token) {
  const on = !!state.labLogsOn;
  return html`
    <div class="card" style="margin-bottom:12px">
      <div class="row" style="align-items:center;gap:12px;flex-wrap:wrap">
        <button
          class="btn"
          ?disabled=${!token}
          @click=${function () {
            if (on) stopLabLogs();
            else startLabLogs(token);
          }}
        >
          ${on ? t("lab.logs.stop", "Stop live logs") : t("lab.logs.start", "Live logs")}
        </button>
        ${adbHint()}
      </div>
      ${state.labLogsError
        ? html`<p class="sub" style="margin:8px 0 0;color:var(--warn, #c90)">${state.labLogsError}</p>`
        : nothing}
      ${on || state.labLogs
        ? html`<pre class="mono" style="max-height:360px;overflow:auto;margin-top:10px">${state.labLogs || "…"}</pre>`
        : nothing}
    </div>
  `;
}

function adbHint() {
  const hint = (state.lab && state.lab.adbHint) || "";
  if (!hint) return nothing;
  return html`<span class="sub">${t("lab.adb", "ADB")}: <code class="mono">${hint}</code></span>`;
}

function cappedProbeRows() {
  const all = probeRows();
  if (all.length <= LAB_ROW_CAP) {
    return { rows: all, total: all.length, capped: false };
  }
  return {
    rows: all.slice(0, LAB_ROW_CAP),
    total: all.length,
    capped: true,
  };
}

export function pageLab() {
  const lab = state.lab || {};
  const tab = state.labTab || "vhal";
  const p = tab === "obd2" ? state.obd2 : tab === "entities" ? null : state.probe;
  const sum =
    tab === "entities"
      ? {
          source: "ControlCatalog /api/entities",
          count: (state.entities || []).length,
        }
      : p && p.summary
        ? p.summary
        : p;
  const sourceHint =
    tab === "obd2"
      ? t("lab.source.obd2", "OBD2_LIVE_FRAME / OBD2_FREEZE_FRAME (VHAL)")
      : tab === "entities"
        ? t(
            "lab.source.entities",
            "Bound product entities (ControlCatalog + i18n description)",
          )
        : t(
            "lab.source.vhal",
            "Full VHAL catalog — filter Missing to see props not yet cards",
          );
  const contributorOn = !!lab.contributor;
  const token = (contributorOn && (lab.token || state.token)) || "";
  const override = lab.integrationOverride || "";
  const integrations = lab.integrations || [];
  const tabOpts = [
    { value: "vhal", label: t("lab.tab.vhal", "VHAL catalog") },
    { value: "obd2", label: t("lab.tab.obd2", "OBD2") },
    { value: "entities", label: t("lab.tab.entities", "Product entities") },
  ];
  const boundOpts = [
    { value: "all", label: t("lab.bound.all", "All") },
    { value: "bound", label: t("lab.bound.bound", "Bound (cards)") },
    { value: "missing", label: t("lab.bound.missing", "Missing") },
  ];
  const probe = cappedProbeRows();
  const rows = probe.rows;
  const showEntityCol = tab === "vhal";

  return html`
    <h1>${t("lab.title", "Lab / Contributor")}</h1>
    <p class="sub">
      ${t("lab.sub", "Probe data sources · enable Contributor mode for /debug writes")}
    </p>
    <div class="card" style="margin-bottom:16px">
      <div class="row" style="align-items:center;gap:12px;flex-wrap:wrap">
        <label style="display:flex;align-items:center;gap:8px">
          <input
            type="checkbox"
            .checked=${contributorOn}
            @change=${async function (ev) {
              const enabled = ev.target.checked ? "1" : "0";
              try {
                const next = await api("/api/lab/contributor", {
                  method: "POST",
                  headers: { "Content-Type": "application/x-www-form-urlencoded" },
                  body: "enabled=" + enabled,
                });
                patch({
                  lab: next,
                  token: next && next.token ? next.token : "",
                });
              } catch (e) {
                state.lab = state.lab || {};
                state.lab.restartHint = errText(e);
                notify();
              }
            }}
          />
          ${t("lab.contributor", "Contributor mode")}
        </label>
        <span class="mono"
          >${t("lab.token", "Token")}:
          ${token ? html`<code>${token}</code>` : "—"}</span
        >
      </div>
      <div class="row" style="align-items:center;gap:12px;margin-top:12px;flex-wrap:wrap">
        <label>
          ${t("lab.override", "Integration override")}
          <select
            id="labOverride"
            .value=${override}
            @change=${function (ev) {
              state._labOverrideDraft = ev.target.value;
            }}
          >
            <option value="">
              ${t("lab.override.auto", "Auto (fingerprint match)")}
            </option>
            ${integrations.map(function (id) {
              return html`<option value=${id} ?selected=${id === override}>${id}</option>`;
            })}
          </select>
        </label>
        <button
          class="btn"
          @click=${async function () {
            const id =
              state._labOverrideDraft != null
                ? state._labOverrideDraft
                : (document.getElementById("labOverride") &&
                    document.getElementById("labOverride").value) ||
                  "";
            try {
              const res = await api("/api/lab/integration-override", {
                method: "POST",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: "id=" + encodeURIComponent(id),
              });
              if (res && res.hint) res.restartHint = res.hint;
              patch({ lab: res });
            } catch (e) {
              state.lab = state.lab || {};
              state.lab.restartHint = errText(e);
              notify();
            }
          }}
        >
          ${t("lab.override.apply", "Apply")}
        </button>
      </div>
      ${lab.restartHint
        ? html`<p class="sub" style="margin:8px 0 0;color:var(--warn, #c90)">
            ${lab.restartHint}
          </p>`
        : html`<p class="sub" style="margin:8px 0 0">
            ${t(
              "lab.override.hint",
              "Override applies after force-stop or reboot. Matched now: ",
            )}<code class="mono">${lab.integration || "—"}</code>
          </p>`}
    </div>
    ${logsCard(token)}
    <div class="card" style="margin-bottom:12px">
      ${prefSegment("lab-tab", tabOpts, tab)}
      <p class="sub" style="margin:10px 0 0">${sourceHint}</p>
      ${tab === "vhal"
        ? html`
            <p class="hint" style="margin:8px 0 0">
              ${t(
                "lab.gap.hint",
                "All VHAL props from platform.json are listed here. Only props with a product description (i18n + ControlCatalog + platform.json entity) become cards. Use Missing to find candidates.",
              )}
            </p>
            <div style="margin-top:10px">
              ${prefSegment("lab-bound", boundOpts, state.probeBoundFilter || "all")}
            </div>
          `
        : nothing}
    </div>
    <div class="card">
      <div class="row">
        <button
          class="btn primary"
          @click=${async function () {
            const tok = encodeURIComponent(token);
            try {
              if (tab === "obd2") {
                patch({ obd2: await api("/debug/obd2?force=1&token=" + tok) });
              } else if (tab === "entities") {
                patch({ entities: await api("/api/entities") });
              } else {
                patch({ probe: await api("/debug/probe?force=1&token=" + tok) });
              }
            } catch (e) {
              patch({ lab: Object.assign({}, lab, { restartHint: errText(e) }) });
            }
          }}
        >
          ${t("lab.probe", "Re-probe")}
        </button>
        <a class="btn" href=${"/debug/export?token=" + encodeURIComponent(token)}
          >${t("lab.export", "Export zip")}</a
        >
        <input
          type="text"
          placeholder=${t("lab.filter", "filter…")}
          .value=${state.probeFilter || ""}
          style="flex:1"
          @input=${function (ev) {
            patch({ probeFilter: ev.target.value || "" });
          }}
        />
      </div>
      <pre class="mono">
${JSON.stringify(sum || { tip: t("lab.probe.tip", "Click Re-probe") }, null, 2)}</pre
      >
      <p class="sub" style="margin:8px 0">
        ${t("lab.rows", "Showing")} ${rows.length}
        ${probe.capped
          ? html` / ${probe.total}`
          : tab === "vhal" && state.probe && state.probe.results
            ? html` / ${(state.probe.results || []).length}`
            : nothing}
      </p>
      <div id="probeScroll" class="table-scroll" style="max-height:520px;overflow:auto">
        <table class="table" id="probeTable">
          <thead>
            <tr>
              <th>${t("lab.col.name", "Name")}</th>
              <th>${t("lab.col.family", "Family")}</th>
              ${showEntityCol
                ? html`<th>${t("lab.col.entity", "Entity")}</th>`
                : nothing}
              <th>${t("lab.col.status", "Status")}</th>
              <th>${t("lab.col.value", "Value")}</th>
              <th>${t("lab.col.perm", "Perm")}</th>
            </tr>
          </thead>
          <tbody>
            ${repeat(
              rows,
              function (r) {
                return r.name + "|" + (r.entity || "") + "|" + r.family;
              },
              function (r) {
                return html`<tr>
                  <td class="mono">${r.name}</td>
                  <td>${r.family}</td>
                  ${showEntityCol
                    ? html`<td class="mono">${r.entity || "—"}</td>`
                    : nothing}
                  <td>${r.status}</td>
                  <td class="mono">${fmt(r.value)}</td>
                  <td class="mono">${fmt(r.permission)}</td>
                </tr>`;
              },
            )}
          </tbody>
        </table>
      </div>
    </div>
  `;
}

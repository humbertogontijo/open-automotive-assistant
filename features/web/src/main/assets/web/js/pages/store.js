import { html, nothing, unsafeHTML } from "../lit.js";
import { state, patch } from "../store.js";
import { t } from "../i18n.js";
import { api, errText } from "../api.js";
import { iconSvg } from "../icons.js";

function icon(name) {
  return unsafeHTML(iconSvg(name));
}

async function runSearch() {
  const q = (state.storeQuery || "").trim();
  patch({ storeDetail: null, storeBusy: true, storeMessage: null, storeQuery: q });
  try {
    const res = await api("/api/store/search?q=" + encodeURIComponent(q));
    const apps = (res && res.apps) || [];
    patch({
      storeResults: apps,
      storeBusy: false,
      storeMessage: apps.length ? null : t("store.empty", "Nenhum resultado"),
    });
  } catch (e) {
    patch({
      storeResults: [],
      storeBusy: false,
      storeMessage: errText(e),
    });
  }
}

async function openPackage(pkg) {
  patch({ storeBusy: true, storeMessage: null });
  try {
    const res = await api("/api/store/package/" + encodeURIComponent(pkg));
    patch({ storeDetail: res, storeBusy: false });
  } catch (e) {
    patch({
      storeBusy: false,
      storeMessage: errText(e),
    });
  }
}

async function installDetail() {
  const detail = state.storeDetail;
  if (!detail) return;
  const sel = document.getElementById("storeVer");
  const vc = sel && sel.value;
  patch({ storeBusy: true, storeMessage: t("store.installing", "Baixando e instalando…") });
  try {
    const body = new URLSearchParams({ packageName: detail.packageName });
    if (vc) body.set("versionCode", vc);
    const res = await api("/api/store/install", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: body.toString(),
    });
    if (res && res.ok === false) {
      patch({
        storeBusy: false,
        storeMessage: res.message || res.error || t("store.install_failed", "Install failed"),
      });
    } else {
      patch({ storeBusy: false, storeMessage: t("store.installed", "Installed") });
    }
  } catch (e) {
    patch({
      storeBusy: false,
      storeMessage: errText(e),
    });
  }
}

async function installApkFile(file) {
  patch({ apkMessage: t("store.installing", "Baixando e instalando…") });
  try {
    const buf = await file.arrayBuffer();
    const r = await fetch("/api/install/binary", {
      method: "POST",
      headers: { "Content-Type": "application/octet-stream" },
      body: buf,
    });
    const json = await r.json();
    patch({
      apkMessage:
        json && json.ok === false
          ? json.message || json.error || t("store.install_failed", "Install failed")
          : t("store.installed", "Installed"),
    });
  } catch (e) {
    patch({ apkMessage: errText(e) });
  }
}

export function pageStore() {
  const q = state.storeQuery || "";
  const results = state.storeResults || [];
  const detail = state.storeDetail;
  const busy = !!state.storeBusy;
  const msg = state.storeMessage;

  let body;
  if (detail) {
    const versions = detail.versions || [];
    const suggested = detail.suggestedVersionCode;
    const ready = detail.installReady !== false;
    body = html`
      <div class="card" style="margin-top:12px">
        <button
          class="btn"
          type="button"
          @click=${function () {
            patch({ storeDetail: null, storeMessage: null });
          }}
        >
          ${t("store.back", "Voltar")}
        </button>
        <div class="row" style="gap:16px;margin-top:14px;align-items:flex-start;flex-wrap:wrap">
          ${detail.iconUrl
            ? html`<img
                src=${detail.iconUrl}
                alt=""
                width="72"
                height="72"
                style="border-radius:16px;object-fit:cover;background:var(--surface-2);flex-shrink:0"
              />`
            : nothing}
          <div style="flex:1;min-width:12rem">
            <h2 style="margin:0 0 6px">${detail.name || detail.packageName}</h2>
            <p class="mono sub" style="margin:0 0 8px;word-break:break-all">${detail.packageName}</p>
            <p class="sub">${detail.summary || ""}</p>
            ${!ready
              ? html`<p class="sub" style="color:var(--warn)">
                  ${t(
                    "store.need_mirror",
                    "Sem URL de download. Configure apkUrl em store/extras.json (espelho próprio).",
                  )}
                </p>`
              : nothing}
          </div>
        </div>
        ${versions.length
          ? html`<label class="sub" style="display:block;margin-top:14px"
                >${t("store.version", "Versão")}</label
              ><select class="field" id="storeVer" style="width:100%;margin-top:6px">
                ${versions.map(function (v) {
                  return html`<option
                    value=${v.versionCode}
                    ?selected=${String(v.versionCode) === String(suggested)}
                  >
                    ${v.versionName || v.versionCode} (${v.versionCode})
                  </option>`;
                })}
              </select>`
          : nothing}
        <button
          class="btn primary"
          style="width:100%;margin-top:14px"
          ?disabled=${busy || !ready}
          @click=${installDetail}
        >
          ${busy
            ? t("store.installing", "Baixando e instalando…")
            : t("store.install", "Instalar")}
        </button>
        ${msg
          ? html`<p class="persist-note" style="margin-top:12px">${msg}</p>`
          : nothing}
      </div>
    `;
  } else {
    body = html`
      <div class="row" style="gap:10px;margin-top:8px;flex-wrap:wrap">
        <input
          class="field"
          type="search"
          placeholder=${t("store.search_ph", "Buscar apps")}
          .value=${q}
          style="flex:1;min-width:10rem"
          @input=${function (ev) {
            state.storeQuery = ev.target.value;
          }}
          @keydown=${function (ev) {
            if (ev.key === "Enter") runSearch();
          }}
        />
        <button class="btn primary" type="button" ?disabled=${busy} @click=${runSearch}>
          ${t("store.search", "Buscar")}
        </button>
      </div>
      ${msg && !results.length
        ? html`<p class="sub" style="margin-top:12px">${msg}</p>`
        : nothing}
      <div style="margin-top:16px">
        ${busy
          ? html`<p class="sub">${t("store.searching", "Buscando…")}</p>`
          : nothing}
        ${results.length
            ? results.map(function (a) {
                return html`<button
                  type="button"
                  class="card store-hit"
                  style="display:flex;gap:14px;align-items:center;width:100%;text-align:left;cursor:pointer;margin-bottom:10px"
                  @click=${function () {
                    openPackage(a.packageName);
                  }}
                >
                  ${a.iconUrl
                    ? html`<img
                        src=${a.iconUrl}
                        alt=""
                        width="48"
                        height="48"
                        style="border-radius:12px;object-fit:cover;background:var(--surface-2);flex-shrink:0"
                      />`
                    : html`<span class="ico" style="flex-shrink:0">${icon("store")}</span>`}
                  <div style="min-width:0;flex:1">
                    <strong
                      >${a.name}${a.installReady === false
                        ? html` <span class="chip" style="font-size:0.7rem"
                            >${t("store.no_url", "sem URL")}</span
                          >`
                        : nothing}</strong
                    >
                    <p
                      class="sub"
                      style="margin:0.25rem 0 0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap"
                    >
                      ${a.summary || a.packageName}
                    </p>
                  </div>
                </button>`;
              })
            : !busy
              ? html`<p class="sub">${t("store.empty", "Nenhum resultado")}</p>`
              : nothing}
      </div>
    `;
  }

  return html`
    <div class="page-head">
      <h1>${t("section.store.title", "Loja")}</h1>
      <button
        class="btn"
        type="button"
        @click=${function () {
          const input = document.getElementById("apk");
          if (input) input.click();
        }}
      >
        ${t("install.title", "Instalar APK")}
      </button>
      <input
        class="hidden"
        type="file"
        id="apk"
        accept=".apk"
        @change=${async function (ev) {
          const f = ev.target.files && ev.target.files[0];
          if (!f) return;
          await installApkFile(f);
          ev.target.value = "";
        }}
      />
    </div>
    ${state.apkMessage
      ? html`<p class="persist-note" style="margin:0 0 12px">${state.apkMessage}</p>`
      : nothing}
    ${body}
  `;
}

/** Ensure store search results load once when entering the section. */
export function ensureStoreLoaded() {
  if (state.storeDetail || state._storeLoaded) return;
  state._storeLoaded = true;
  patch({ storeBusy: true, storeMessage: null });
  // Phase 1: curated extras (instant) so the page is never blank.
  api("/api/store/search?q=&fdroid=0")
    .then(function (res) {
      const apps = (res && res.apps) || [];
      if (apps.length) {
        patch({ storeResults: apps, storeMessage: null });
      }
    })
    .catch(function () {})
    .then(function () {
      // Phase 2: extras + F-Droid browse (search API).
      return api("/api/store/search?q=");
    })
    .then(function (res) {
      const apps = (res && res.apps) || [];
      patch({
        storeResults: apps,
        storeBusy: false,
        storeMessage: apps.length ? null : t("store.empty", "Nenhum resultado"),
      });
    })
    .catch(function (e) {
      patch({
        storeBusy: false,
        storeMessage:
          (state.storeResults && state.storeResults.length)
            ? null
            : errText(e),
      });
    });
}

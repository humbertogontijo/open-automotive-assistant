import { html, nothing } from "lit";
import { store } from "../store.js";
import { t } from "../i18n.js";
import { api, errText, postForm, postBytes } from "../api.js";
import { icon } from "../icons.js";
import { OaaPage } from "../lit/oaa-page.js";
import { toast, toastError } from "../ui/toast.js";

function emptyMessage(apps) {
  return apps.length ? null : t("store.empty", "Nenhum resultado");
}

/** @param {string} q */
async function runSearch(q) {
  store.$patch({ detail: null, busy: true, message: null });
  try {
    const res = await api("/api/store/search?q=" + encodeURIComponent(q));
    const apps = (res && res.apps) || [];
    store.$patch({ results: apps, busy: false, message: emptyMessage(apps) });
  } catch (e) {
    store.$patch({ results: [], busy: false, message: errText(e) });
  }
}

async function openPackage(pkg) {
  store.$patch({ busy: true, message: null });
  try {
    store.$patch({ detail: await api("/api/store/package/" + encodeURIComponent(pkg)), busy: false });
  } catch (e) {
    store.$patch({ busy: false, message: errText(e) });
  }
}

/** @param {string} versionCode */
async function installDetail(versionCode) {
  const detail = store.detail;
  if (!detail) return;
  store.busy = true;
  try {
    const body = new URLSearchParams({ packageName: detail.packageName });
    if (versionCode) body.set("versionCode", versionCode);
    const res = await postForm("/api/store/install", body);
    if (res && res.ok === false) toastError(res.message || res.error || t("store.install_failed", "Install failed"));
    else toast(t("store.installed", "Installed"), { variant: "success" });
  } catch (e) {
    toastError(errText(e));
  } finally {
    store.busy = false;
  }
}

async function installApkFile(file) {
  toast(t("store.installing", "Baixando e instalando…"));
  try {
    const json = await postBytes("/api/install/binary", file);
    if (json && json.ok === false) toastError(json.message || json.error || t("store.install_failed", "Install failed"));
    else toast(t("store.installed", "Installed"), { variant: "success" });
  } catch (e) {
    toastError(errText(e));
  }
}

/** Curated extras first (instant), then extras + F-Droid browse. */
async function browse() {
  store.$patch({ busy: true, message: null });
  try {
    const quick = await api("/api/store/search?q=&fdroid=0");
    if (quick && quick.apps && quick.apps.length) store.results = quick.apps;
  } catch (e) {}
  try {
    const res = await api("/api/store/search?q=");
    const apps = (res && res.apps) || [];
    store.$patch({ results: apps, busy: false, message: emptyMessage(apps) });
  } catch (e) {
    store.$patch({ busy: false, message: store.results.length ? null : errText(e) });
  }
}

function appIcon(url, size) {
  return url
    ? html`<img class="store-icon" src=${url} alt="" width=${size} height=${size} />`
    : html`<span class="ico">${icon("store")}</span>`;
}

class OaaPageStore extends OaaPage {
  static properties = {
    query: { state: true },
    versionCode: { state: true },
  };

  constructor() {
    super();
    this.query = "";
    /** @type {string | null} */
    this.versionCode = null;
  }

  load() {
    if (store.detail) return;
    return browse();
  }

  search() {
    this.query = this.query.trim();
    return runSearch(this.query);
  }

  detailHtml(detail) {
    const versions = detail.versions || [];
    const ready = detail.installReady !== false;
    const vc = this.versionCode != null ? this.versionCode : String(detail.suggestedVersionCode ?? "");
    return html`<wa-card class="store-detail">
      <wa-button
        appearance="outlined"
        @click=${() => {
          store.$patch({ detail: null, message: null });
          this.versionCode = null;
        }}
        >${icon("chevron-left")} ${t("store.back", "Voltar")}</wa-button
      >
      <div class="store-detail-head">
        ${appIcon(detail.iconUrl, 72)}
        <div class="store-detail-text">
          <h2>${detail.name || detail.packageName}</h2>
          <p class="mono sub">${detail.packageName}</p>
          <p class="sub">${detail.summary || ""}</p>
          ${!ready
            ? html`<wa-callout variant="warning" size="s"
                >${t(
                  "store.need_mirror",
                  "Sem URL de download. Configure apkUrl em store/extras.json (espelho próprio).",
                )}</wa-callout
              >`
            : nothing}
        </div>
      </div>
      ${versions.length
        ? html`<wa-select label=${t("store.version", "Versão")} .value=${vc} @change=${(ev) => (this.versionCode = ev.target.value)}>
            ${versions.map(
              (v) =>
                html`<wa-option value=${String(v.versionCode)}>${v.versionName || v.versionCode} (${v.versionCode})</wa-option>`,
            )}
          </wa-select>`
        : nothing}
      <wa-button variant="brand" ?loading=${store.busy} ?disabled=${!ready} @click=${() => installDetail(vc)}
        >${t("store.install", "Instalar")}</wa-button
      >
      ${store.message ? html`<p class="persist-note">${store.message}</p>` : nothing}
    </wa-card>`;
  }

  listHtml() {
    const results = store.results;
    const busy = store.busy;
    return html`
      <div class="store-search">
        <wa-input
          type="search"
          placeholder=${t("store.search_ph", "Buscar apps")}
          with-clear
          .value=${this.query}
          @input=${(ev) => (this.query = ev.target.value)}
          @keydown=${(ev) => {
            if (ev.key === "Enter") this.search();
          }}
        ></wa-input>
        <wa-button variant="brand" ?loading=${busy} @click=${() => this.search()}>${t("store.search", "Buscar")}</wa-button>
      </div>
      ${store.message && !results.length ? html`<p class="sub">${store.message}</p>` : nothing}
      ${busy && !results.length ? html`<p class="sub">${t("store.searching", "Buscando…")}</p>` : nothing}
      <div class="store-hits">
        ${results.map(
          (a) => html`<button type="button" class="store-hit" @click=${() => openPackage(a.packageName)}>
            ${appIcon(a.iconUrl, 48)}
            <div class="store-hit-text">
              <strong
                >${a.name}${a.installReady === false
                  ? html` <wa-tag size="small">${t("store.no_url", "sem URL")}</wa-tag>`
                  : nothing}</strong
              >
              <p class="sub">${a.summary || a.packageName}</p>
            </div>
          </button>`,
        )}
      </div>
    `;
  }

  render() {
    const detail = store.detail;
    return html`
      <div class="page-head">
        <h1>${t("section.store.title", "Loja")}</h1>
        <wa-button appearance="outlined" @click=${() => /** @type {HTMLInputElement} */ (this.querySelector(".store-apk")).click()}
          >${t("install.title", "Instalar APK")}</wa-button
        >
        <input
          class="hidden store-apk"
          type="file"
          accept=".apk"
          @change=${async (ev) => {
            const f = ev.target.files && ev.target.files[0];
            if (!f) return;
            await installApkFile(f);
            ev.target.value = "";
          }}
        />
      </div>
      ${detail ? this.detailHtml(detail) : this.listHtml()}
    `;
  }
}
customElements.define("oaa-page-store", OaaPageStore);

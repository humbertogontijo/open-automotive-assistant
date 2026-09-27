import { html, nothing } from "lit";
import { sounds, entitiesByGroup } from "../store.js";
import { t } from "../i18n.js";
import { api, errText, postForm, postBytes } from "../api.js";
import { familySections } from "./group.js";
import { prefCard } from "../ui/cards/prefs.js";
import { confirmDialog } from "../ui/confirm.js";
import { toastError } from "../ui/toast.js";
import { OaaPage } from "../lit/oaa-page.js";
import { fmtBytes } from "../format.js";

export async function loadSounds() {
  try {
    sounds.list = await api("/api/sounds");
  } catch (e) {
    sounds.list = null;
  }
}

async function uploadSound(kind, file) {
  const json = await postBytes(
    "/api/sounds/upload?kind=" + encodeURIComponent(kind) + "&name=" + encodeURIComponent(file.name),
    file,
    { "X-Filename": file.name },
  );
  if (json && json.ok === false) toastError(json.error || t("sounds.upload_failed", "Upload failed"));
  await loadSounds();
}

/** @param {string} path @param {string} kind @param {string} name */
function postSound(path, kind, name) {
  return postForm(path, "kind=" + encodeURIComponent(kind) + "&name=" + encodeURIComponent(name));
}

async function applySound(kind, name) {
  await postSound("/api/sounds/apply", kind, name).catch((e) => toastError(errText(e)));
  await loadSounds();
}

async function deleteSound(kind, name) {
  if (!(await confirmDialog(t("sounds.delete.confirm", "Delete this sound?"), { danger: true }))) return;
  await api("/api/sounds/" + encodeURIComponent(kind) + "/" + encodeURIComponent(name), { method: "DELETE" }).catch((e) =>
    toastError(errText(e)),
  );
  await loadSounds();
}

function fileRow(kind, f, active) {
  const on = f.active || f.name === active;
  return html`<li class="file-row">
    <div class="file-row-meta">
      <strong class=${on ? "file-row-active" : ""}
        >${f.name}${on ? html` <wa-badge variant="brand" pill>${t("sounds.active", "active")}</wa-badge>` : nothing}</strong
      >
      <p class="sub">${fmtBytes(f.size)}</p>
    </div>
    <div class="file-row-actions">
      <wa-button size="small" appearance="plain" @click=${() => postSound("/api/sounds/preview", kind, f.name || "")}
        >${t("sounds.preview", "Play")}</wa-button
      >
      ${on
        ? nothing
        : html`<wa-button size="small" appearance="outlined" @click=${() => applySound(kind, f.name || "")}
            >${t("sounds.apply", "Use")}</wa-button
          >`}
      <wa-button size="small" appearance="plain" variant="danger" @click=${() => deleteSound(kind, f.name)}
        >${t("sounds.delete", "Delete")}</wa-button
      >
    </div>
  </li>`;
}

function soundKindCard(kind, title) {
  const snap = (sounds.list && sounds.list[kind]) || {};
  const files = snap.files || [];
  const active = snap.active;
  return prefCard({
    icon: kind === "lock" ? "lock" : "system",
    title: title,
    body: html`
      ${files.length
        ? html`<ul class="file-list">
            ${files.map((f) => fileRow(kind, f, active))}
          </ul>`
        : html`<p class="persist-note">${t("sounds.empty", "No custom files yet")}</p>`}
      <div class="pref-actions">
        <wa-button
          variant="brand"
          @click=${(ev) => /** @type {HTMLInputElement} */ (ev.target.closest(".ctrl-body").querySelector("input[type=file]")).click()}
          >${t("sounds.upload", "Upload")}</wa-button
        >
        ${active
          ? html`<wa-button appearance="outlined" @click=${() => applySound(kind, "")}>${t("sounds.clear", "Clear active")}</wa-button>`
          : nothing}
      </div>
      <input
        class="hidden"
        type="file"
        accept=".wav,.mp3,.ogg,.m4a,audio/*"
        @change=${async (ev) => {
          const f = ev.target.files && ev.target.files[0];
          if (!f) return;
          try {
            await uploadSound(kind, f);
          } catch (e) {
            toastError(errText(e));
          }
          ev.target.value = "";
        }}
      />
    `,
  });
}

class OaaPageSound extends OaaPage {
  load() {
    return loadSounds();
  }

  render() {
    const snap = sounds.list || {};
    const note =
      snap.note || t("sounds.note", "Custom files play via app MediaPlayer; OEM AVAS still uses esm_sound / esm_volume.");
    return html`
      <h1>${t("section.sound.title", "Som")}</h1>
      ${familySections(entitiesByGroup("sound"))}
      <h2 class="page-label sound-label">${t("sounds.title", "Custom sounds")}</h2>
      <p class="sub">${note}</p>
      <div class="grid">${soundKindCard("avas", t("sounds.avas", "AVAS"))} ${soundKindCard("lock", t("sounds.lock", "Lock"))}</div>
    `;
  }
}
customElements.define("oaa-page-sound", OaaPageSound);

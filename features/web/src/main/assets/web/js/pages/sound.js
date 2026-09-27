import { html, nothing } from "../lit.js";
import { state, patch } from "../store.js";
import { t } from "../i18n.js";
import { api, errText } from "../api.js";
import { entitiesByGroup } from "../store.js";
import { familySections } from "./group.js";
import { prefCard } from "../ui/cards.js";

function fmtBytes(n) {
  const v = Number(n) || 0;
  if (v < 1024) return v + " B";
  if (v < 1024 * 1024) return (v / 1024).toFixed(1) + " KB";
  return (v / (1024 * 1024)).toFixed(1) + " MB";
}

export async function loadSounds() {
  try {
    const sounds = await api("/api/sounds");
    patch({ sounds: sounds });
  } catch (e) {
    patch({ sounds: null });
  }
}

async function uploadSound(kind, file) {
  const buf = await file.arrayBuffer();
  const r = await fetch(
    "/api/sounds/upload?kind=" +
      encodeURIComponent(kind) +
      "&name=" +
      encodeURIComponent(file.name),
    {
      method: "POST",
      headers: { "Content-Type": "application/octet-stream", "X-Filename": file.name },
      body: buf,
    },
  );
  const json = await r.json();
  if (json && json.ok === false) {
    alert(json.error || t("sounds.upload_failed", "Upload failed"));
  }
  await loadSounds();
}

function soundKindCard(kind, title) {
  const snap = (state.sounds && state.sounds[kind]) || {};
  const files = snap.files || [];
  const active = snap.active;
  const fileInputId = "sound-file-" + kind;

  const rows = files.length
    ? html`<ul class="file-list" style="width:100%">
        ${files.map(function (f) {
          const on = f.active || f.name === active;
          return html`<li class="file-row">
            <div class="file-row-meta">
              <strong style=${on ? "color:var(--accent)" : nothing}
                >${f.name}${on
                  ? html` <span class="chip">${t("sounds.active", "active")}</span>`
                  : nothing}</strong
              >
              <p class="sub" style="margin:2px 0 0">${fmtBytes(f.size)}</p>
            </div>
            <div class="file-row-actions">
              <button
                type="button"
                class="btn ghost"
                @click=${async function () {
                  await api("/api/sounds/preview", {
                    method: "POST",
                    headers: { "Content-Type": "application/x-www-form-urlencoded" },
                    body:
                      "kind=" +
                      encodeURIComponent(kind) +
                      "&name=" +
                      encodeURIComponent(f.name || ""),
                  });
                }}
              >
                ${t("sounds.preview", "Play")}
              </button>
              ${on
                ? nothing
                : html`<button
                    type="button"
                    class="btn"
                    @click=${async function () {
                      await api("/api/sounds/apply", {
                        method: "POST",
                        headers: { "Content-Type": "application/x-www-form-urlencoded" },
                        body:
                          "kind=" +
                          encodeURIComponent(kind) +
                          "&name=" +
                          encodeURIComponent(f.name || ""),
                      });
                      await loadSounds();
                    }}
                  >
                    ${t("sounds.apply", "Use")}
                  </button>`}
              <button
                type="button"
                class="btn ghost"
                @click=${async function () {
                  if (!confirm(t("sounds.delete.confirm", "Delete this sound?"))) return;
                  await api(
                    "/api/sounds/" +
                      encodeURIComponent(kind) +
                      "/" +
                      encodeURIComponent(f.name),
                    { method: "DELETE" },
                  );
                  await loadSounds();
                }}
              >
                ${t("sounds.delete", "Delete")}
              </button>
            </div>
          </li>`;
        })}
      </ul>`
    : html`<p class="persist-note" style="margin:0">
        ${t("sounds.empty", "No custom files yet")}
      </p>`;

  return prefCard({
    icon: kind === "lock" ? "lock" : "system",
    title: title,
    body: html`
      ${rows}
      <div class="row" style="width:100%;margin:12px 0 0;gap:8px">
        <button
          type="button"
          class="btn primary"
          style="flex:1"
          @click=${function () {
            const input = document.getElementById(fileInputId);
            if (input) input.click();
          }}
        >
          ${t("sounds.upload", "Upload")}
        </button>
        ${active
          ? html`<button
              type="button"
              class="btn ghost"
              @click=${async function () {
                await api("/api/sounds/apply", {
                  method: "POST",
                  headers: { "Content-Type": "application/x-www-form-urlencoded" },
                  body: "kind=" + encodeURIComponent(kind) + "&name=",
                });
                await loadSounds();
              }}
            >
              ${t("sounds.clear", "Clear active")}
            </button>`
          : nothing}
      </div>
      <input
        class="hidden"
        id=${fileInputId}
        type="file"
        accept=".wav,.mp3,.ogg,.m4a,audio/*"
        @change=${async function (ev) {
          const f = ev.target.files && ev.target.files[0];
          if (!f) return;
          try {
            await uploadSound(kind, f);
          } catch (e) {
            alert(errText(e));
          }
          ev.target.value = "";
        }}
      />
    `,
  });
}

export function pageSound() {
  const snap = state.sounds || {};
  const note =
    snap.note ||
    t(
      "sounds.note",
      "Custom files play via app MediaPlayer; OEM AVAS still uses esm_sound / esm_volume.",
    );
  return html`
    <h1>${t("section.sound.title", "Som")}</h1>
    ${familySections(entitiesByGroup("sound"))}
    <h2 class="page-label" style="margin:28px 0 10px">${t("sounds.title", "Custom sounds")}</h2>
    <p class="sub" style="margin:0 0 12px">${note}</p>
    <div class="grid">
      ${soundKindCard("avas", t("sounds.avas", "AVAS"))}
      ${soundKindCard("lock", t("sounds.lock", "Lock"))}
    </div>
  `;
}

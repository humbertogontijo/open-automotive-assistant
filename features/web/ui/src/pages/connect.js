import { html, nothing } from "lit";
import { t } from "../i18n.js";
import { session, entitiesByGroup, hiddenEntitiesByGroup, isShowingHidden } from "../store.js";
import { familySections } from "./group.js";
import { prefCard, prefBool } from "../ui/cards/prefs.js";
import { pageHead } from "../ui/cards/page-head.js";
import { api } from "../api.js";
import { fmtBytes, storageLabel } from "../format.js";

/** Connection / HU helpers page (wifi, bt, ADB, storage). */
export function pageConnect() {
  const group = "connect";
  const viewing = isShowingHidden(group);
  const items = viewing
    ? hiddenEntitiesByGroup(group)
    : entitiesByGroup(group);

  const adb = session.adb || {};
  const volumes =
    (session.status && session.status.storage && session.status.storage.volumes) ||
    (session.status && session.status.dvr && session.status.dvr.storages) ||
    [];
  const adbSub =
    adb.enabled && adb.port
      ? t("system.adb.port", "Porta") + " " + adb.port
      : t("system.adb.sub", "TCP debugging on the head unit");
  const adbBody = html`
    ${prefBool("adb", adb.enabled)}
    ${adb.canToggle === false
      ? html`<p class="persist-note">
          ${t("system.adb.unavailable", "Toggle unavailable on this build")}
        </p>`
      : nothing}
  `;

  const storageBody = volumes.length
    ? html`<ul class="volume-list">
        ${volumes.map(function (v) {
          const total = Number(v.totalBytes) || 0;
          const free = Number(v.freeBytes) || 0;
          const used = total > 0 ? Math.max(0, total - free) : 0;
          const pct = total > 0 ? Math.min(100, Math.round((used / total) * 100)) : 0;
          return html`<li>
            <div class="volume-head">
              <strong>${storageLabel(v)}</strong>
              ${v.writable === false
                ? html`<wa-badge variant="warning">${t("connect.storage.readonly", "Read-only")}</wa-badge>`
                : nothing}
            </div>
            <wa-progress-bar class="storage-bar" value=${pct} title=${pct + "%"} label=${storageLabel(v)}></wa-progress-bar>
            <p class="sub">
              ${t("connect.storage.used_free", "{used} used · {free} free of {total}")
                .replace("{used}", fmtBytes(used))
                .replace("{free}", fmtBytes(free))
                .replace("{total}", fmtBytes(total))}
            </p>
          </li>`;
        })}
      </ul>`
    : html`<p class="persist-note">${t("connect.storage.empty", "No volumes reported")}</p>`;

  return html`
    ${pageHead(
      t("section.connect.title", "Connection"),
      group,
      t("section.connect.sub", "Radios, ADB, storage, and system helpers"),
    )}
    ${familySections(items, viewing ? { restore: true } : null)}
    ${viewing
      ? nothing
      : html`<div class="grid connect-grid">
      ${prefCard({
        icon: "usb",
        title: t("system.adb.title", "ADB sem fio"),
        sub: adbSub,
        body: adbBody,
      })}
      ${prefCard({
        icon: "system",
        title: t("connect.storage", "Storage"),
        body: storageBody,
      })}
      ${prefCard({
        icon: "system",
        title: t("system.android_settings", "Configurações do Android"),
        body: html`<wa-button
          class="full-width"
          variant="brand"
          @click=${() => api("/api/system/open-android-settings", { method: "POST" })}
          >${t("system.android_settings", "Configurações do Android")}</wa-button
        >`,
      })}
    </div>`}
  `;
}

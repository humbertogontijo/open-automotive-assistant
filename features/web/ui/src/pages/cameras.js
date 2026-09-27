import { html, nothing } from "lit";
import { camera, statusDvr, entitiesByGroup } from "../store.js";
import { t } from "../i18n.js";
import { errText, postForm } from "../api.js";
import { prefCard, prefSegment } from "../ui/cards/prefs.js";
import { boolToggle } from "../ui/cards/bool.js";
import { entityGrid } from "../ui/cards/grid.js";
import { loadRecordings, backToLive } from "../ui/camera-player.js";
import "../ui/camera-timeline-view.js";
import { confirmDialog } from "../ui/confirm.js";
import { runPref, reloadStatus } from "../actions.js";
import { OaaPage } from "../lit/oaa-page.js";
import { fmtBytes, storageLabel } from "../format.js";

export { loadRecordings, restartLive } from "../ui/camera-player.js";

function spaceBar(used, total) {
  const tot = Number(total) || 0;
  const u = Number(used) || 0;
  const pct = tot > 0 ? Math.min(100, Math.round((u / tot) * 100)) : 0;
  return html`<wa-progress-bar
    class="storage-bar"
    value=${pct}
    title=${pct + "%"}
    label=${t("cameras.storage", "Save to")}
  ></wa-progress-bar>`;
}

async function clearRecordings() {
  const ok = await confirmDialog(
    t("cameras.clear.confirm", "Delete all DVR recordings on this storage? This cannot be undone."),
    { danger: true },
  );
  if (!ok) return;
  try {
    await postForm("/api/dvr/clear", "includeLocked=1");
    await reloadStatus();
    await loadRecordings();
    await backToLive();
  } catch (e) {
    camera.previewError = errText(e);
  }
}

function pageCameras() {
  const dvr = statusDvr.get() || {};
  const dvrActive = dvr.mode === "dvr" || (!!dvr.recording && dvr.mode !== "off");
  const cameraEntities = entitiesByGroup("cameras");
  const storages = dvr.storages || [];
  const storageId = dvr.storageId || "app";
  const selected =
    storages.find(function (s) {
      return s.id === storageId;
    }) ||
    storages[0] ||
    {};
  const storageOpts = storages.map(function (s) {
    const unavailable = s.available === false || s.writable === false;
    return {
      value: s.id,
      label: storageLabel(s, { markUnavailable: true }),
      disabled: unavailable,
      title: unavailable
        ? t("cameras.storage.usb.hint", "Plug in a USB flash drive to save recordings here")
        : undefined,
    };
  });
  if (!storageOpts.length) {
    storageOpts.push({ value: "app", label: t("cameras.storage.app", "App") });
  }
  const policy = dvr.policy || {};
  const maxTotalMb = Number(policy.maxTotalMb) || 2048;
  const maxAgeDays = Number(policy.maxAgeDays) || 0;
  const sizeOpts = [512, 1024, 2048, 4096, 8192].map(function (mb) {
    return { value: String(mb), label: mb >= 1024 ? mb / 1024 + " GB" : mb + " MB" };
  });
  const ageOpts = [
    { value: "0", label: t("cameras.policy.age.off", "Off") },
    { value: "1", label: t("cameras.policy.age.days", "{n} days").replace("{n}", "1") },
    { value: "3", label: t("cameras.policy.age.days", "{n} days").replace("{n}", "3") },
    { value: "7", label: t("cameras.policy.age.days", "{n} days").replace("{n}", "7") },
    { value: "14", label: t("cameras.policy.age.days", "{n} days").replace("{n}", "14") },
    { value: "30", label: t("cameras.policy.age.days", "{n} days").replace("{n}", "30") },
  ];
  const freeB = Number(selected.freeBytes) || Number(dvr.selectedFreeBytes) || 0;
  const totalB = Number(selected.totalBytes) || Number(dvr.selectedTotalBytes) || 0;
  const usedB = totalB > 0 ? Math.max(0, totalB - freeB) : 0;
  const usageBytes = Number(dvr.usageBytes) || 0;
  const usageCount = Number(dvr.usageCount) || 0;
  const capBytes = maxTotalMb * 1024 * 1024;

  return html`
    <h1>${t("section.cameras.title", "Câmeras")}</h1>

    ${cameraEntities.length
      ? html`
          <div class="cameras-sources" style="margin-bottom:16px">
            <p class="sub" style="margin:0 0 8px">
              ${t("cameras.sources", "Cameras")}
            </p>
            ${entityGrid(cameraEntities)}
          </div>
        `
      : nothing}

    <div class="cameras-stage">
      <oaa-camera-player embedded .lastError=${dvr.lastError || ""}></oaa-camera-player>

      <div class="cameras-side">
        ${prefCard({
          icon: "camera",
          title: t("cameras.mode.dvr", "DVR"),
          body: html`
            <p class="sub cameras-side-hint">
              ${t(
                "cameras.mode.dvr.hint",
                "Continuous recording. Starts on ACC/boot, stops on sleep. Scrub the day timeline and use Cut to download a clip.",
              )}
            </p>
            ${boolToggle(dvrActive, async function (val) {
              await runPref("cam-mode", val === "1" || val === true ? "dvr" : "off");
            })}
            <hr class="cameras-side-sep" />
            <p class="sub" style="margin:0 0 6px">${t("cameras.storage", "Save to")}</p>
            ${prefSegment("cam-storage", storageOpts, storageId)}
            ${dvr.storageNote
              ? html`<p class="sub" style="color:var(--wa-color-warning-fill-loud);margin:8px 0 0">${dvr.storageNote}</p>`
              : nothing}
            <div style="margin-top:12px">
              ${spaceBar(usedB, totalB)}
              <p class="sub" style="margin:6px 0 0">
                ${t("cameras.storage.used_free", "{used} used · {free} free of {total}")
                  .replace("{used}", fmtBytes(usedB))
                  .replace("{free}", fmtBytes(freeB))
                  .replace("{total}", fmtBytes(totalB))}
              </p>
              <p class="sub" style="margin:2px 0 0">
                ${t("cameras.storage.clips", "Recordings: {used} in {count} files (cap {cap})")
                  .replace("{used}", fmtBytes(usageBytes))
                  .replace("{count}", String(usageCount))
                  .replace("{cap}", fmtBytes(capBytes))}
              </p>
            </div>
            <p class="sub" style="margin:14px 0 6px">${t("cameras.policy.max_size", "Max total size")}</p>
            ${prefSegment("cam-retention-size", sizeOpts, String(maxTotalMb))}
            <p class="sub" style="margin:10px 0 6px">${t("cameras.policy.max_age", "Max age")}</p>
            ${prefSegment("cam-retention-age", ageOpts, String(maxAgeDays))}
            <wa-button
              class="cameras-clear"
              variant="danger"
              appearance="outlined"
              ?disabled=${usageCount <= 0}
              @click=${clearRecordings}
            >
              ${t("cameras.clear", "Clear recordings")}
              ${usageCount > 0 ? " (" + String(usageCount) + ")" : ""}
            </wa-button>
          `,
        })}
      </div>

      <oaa-camera-timeline></oaa-camera-timeline>
    </div>
  `;
}

class OaaPageCameras extends OaaPage {
  load() {
    return loadRecordings();
  }

  render() {
    return pageCameras();
  }
}
customElements.define("oaa-page-cameras", OaaPageCameras);

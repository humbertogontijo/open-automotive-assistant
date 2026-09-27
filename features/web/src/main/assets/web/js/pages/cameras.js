import { html, nothing } from "../lit.js";
import { state, patch, patchSilent, entitiesByGroup } from "../store.js";
import { t } from "../i18n.js";
import { api, errText } from "../api.js";
import { prefCard, prefSegment, boolToggle, entityGrid } from "../ui/cards.js";
import {
  cameraPlayerView,
  applyCameraPlayerSrc,
  stopRecordingPlayback,
  isRecordingPlayback,
  backToLive,
} from "../ui/camera-player.js";
import { cameraTimelineView } from "../ui/camera-timeline-view.js";
import { mediaTransport, isLiveSrc } from "../ui/media-transport.js";
import { setMediaCloseHandler, reasonText } from "../ui/webrtc-session.js";

setMediaCloseHandler(function (reason) {
  if (state.page !== "cameras" && state.page !== "dvr") return;
  patch({
    cameraPreviewActive: false,
    cameraPreviewSrc: "",
    cameraPreviewError: reasonText(reason),
  });
});

export { isTimelineBusy } from "../ui/camera-timeline-view.js";

function fmtBytes(n) {
  const v = Number(n) || 0;
  if (v < 1024) return v + " B";
  if (v < 1024 * 1024) return (v / 1024).toFixed(1) + " KB";
  if (v < 1024 * 1024 * 1024) return (v / (1024 * 1024)).toFixed(1) + " MB";
  return (v / (1024 * 1024 * 1024)).toFixed(2) + " GB";
}

function storageLabel(s) {
  if (!s) return "";
  if (s.labelKey === "cameras.storage.usb.none") {
    return t("cameras.storage.usb.none", "USB / Flash (none)");
  }
  if (s.labelKey === "cameras.storage.sd" || s.labelKey === "cameras.storage.usb") {
    const base = t(
      s.labelKey,
      s.labelKey === "cameras.storage.usb" ? "USB / Flash ({label})" : "SD ({label})",
    ).replace("{label}", s.label || "");
    if (s.available === false || s.writable === false) {
      return base + " — " + t("cameras.storage.unavailable", "Not available");
    }
    return base;
  }
  return t(s.labelKey || "cameras.storage.app", s.label || s.id);
}

function spaceBar(used, total) {
  const tot = Number(total) || 0;
  const u = Number(used) || 0;
  const pct = tot > 0 ? Math.min(100, Math.round((u / tot) * 100)) : 0;
  return html`
    <div class="storage-bar" title=${pct + "%"}>
      <div class="storage-bar-fill" style="width:${pct}%"></div>
    </div>
  `;
}

async function setCamMode(mode) {
  const storage =
    (state.status && state.status.dvr && state.status.dvr.storageId) || "";
  await api("/api/dvr/mode", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body:
      "mode=" +
      encodeURIComponent(mode) +
      "&storage=" +
      encodeURIComponent(storage || ""),
  });
  try {
    const s = await api("/api/status");
    patch({ status: s });
  } catch (e) {
    patch({});
  }
  await loadRecordings();
}

/**
 * Soft-refresh wall-clock DVR timeline (also used by app poll as loadRecordings).
 * @param {{ silent?: boolean }} [opts] — silent skips lit re-render (live preview path).
 */
export async function loadRecordings(opts) {
  const silent = !!(opts && opts.silent);
  const apply = silent ? patchSilent : patch;
  try {
    const res = await api("/api/dvr/timeline");
    apply({
      dvrTimeline: {
        segments: (res && res.segments) || [],
        recording: !!(res && res.recording),
      },
    });
  } catch (e) {
    apply({
      dvrTimeline: { segments: [], recording: false },
    });
  }
}

export async function startCameraLive() {
  if (isRecordingPlayback()) return;
  if (state.cameraPreviewActive && isLiveSrc(state.cameraPreviewSrc)) return;
  const media = mediaTransport();
  try {
    await media.acquireLive();
    patch({
      cameraPreviewActive: true,
      cameraPreviewSrc: media.liveSrc,
      cameraPlayerMode: "live",
      cameraPlayingName: "",
      cameraPlayingKind: "",
      cameraTimelineAtMs: Date.now(),
    });
  } catch (e) {
    patch({ cameraPreviewActive: false, cameraPreviewSrc: "", cameraPreviewError: errText(e) });
  }
}

export async function stopCameraLive() {
  stopRecordingPlayback();
  const media = mediaTransport(state.cameraPreviewSrc);
  media.stopLive(document.getElementById("cameraPlayerVideo"));
  patch({
    cameraPreviewActive: false,
    cameraPreviewSrc: "",
    cameraPlayerMode: "live",
    cameraPlayingName: "",
    cameraPlayingKind: "",
    cameraPreviewError: "",
  });
  await media.close();
}

export { applyCameraPlayerSrc };

export function pageCameras() {
  const dvr = (state.status && state.status.dvr) || {};
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
      label: storageLabel(s),
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
      ${cameraPlayerView({
        lastError: dvr.lastError || "",
        startLive: startCameraLive,
        embedded: true,
      })}

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
              await setCamMode(val === "1" || val === true ? "dvr" : "off");
            })}
            <hr class="cameras-side-sep" />
            <p class="sub" style="margin:0 0 6px">${t("cameras.storage", "Save to")}</p>
            ${prefSegment("cam-storage", storageOpts, storageId)}
            ${dvr.storageNote
              ? html`<p class="sub" style="color:var(--warn);margin:8px 0 0">${dvr.storageNote}</p>`
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
            <div style="margin-top:14px">
              <button
                type="button"
                class="btn ghost"
                style="width:100%"
                ?disabled=${usageCount <= 0}
                @click=${async function () {
                  if (
                    !confirm(
                      t(
                        "cameras.clear.confirm",
                        "Delete all DVR recordings on this storage? This cannot be undone.",
                      ),
                    )
                  ) {
                    return;
                  }
                  try {
                    await api("/api/dvr/clear", {
                      method: "POST",
                      headers: {
                        "Content-Type": "application/x-www-form-urlencoded",
                      },
                      body: "includeLocked=1",
                    });
                    const s = await api("/api/status");
                    patch({ status: s });
                    await loadRecordings();
                    await backToLive(startCameraLive);
                  } catch (e) {
                    patch({ cameraPreviewError: errText(e) });
                  }
                }}
              >
                ${t("cameras.clear", "Clear recordings")}
                ${usageCount > 0 ? " (" + String(usageCount) + ")" : ""}
              </button>
            </div>
          `,
        })}
      </div>

      ${cameraTimelineView({ startLive: startCameraLive })}
    </div>
  `;
}

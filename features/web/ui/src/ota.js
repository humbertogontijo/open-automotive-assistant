import { t } from "./i18n.js";

const OTA_TEXT = {
  pending: "Update queued",
  offered: "Update offered",
  downloading: "Downloading update",
  verifying: "Verifying update",
  installing: "Installing update",
  pending_user: "Confirm the install on the car",
  installed: "Update installed",
  failed: "Update failed",
};

/** Rollout states that end without a new app process; anything else is still moving. */
export const OTA_DONE = new Set(["installed", "failed"]);

/** One line for an `ota_status`-shaped `{state, progress, error}`. */
export function otaLine(ota) {
  if (!ota || !ota.state) return "";
  let text = t("fleet.ota." + ota.state, OTA_TEXT[ota.state] || ota.state);
  if (ota.state === "downloading" && ota.progress != null) text += " · " + ota.progress + "%";
  if (ota.state === "failed" && ota.error) text += ": " + ota.error;
  return text;
}

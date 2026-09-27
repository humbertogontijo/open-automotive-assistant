import { t } from "../i18n.js";
import { pageHome } from "./home.js";
import { pageGroup } from "./group.js";
import { pageEnergy, loadEnergyDash } from "./energy.js";
import { pageControls } from "./controls.js";
import { pageDrive } from "./drive.js";
import { pageSound, loadSounds } from "./sound.js";
import { pageConnect } from "./connect.js";
import { pageAssistant } from "./assistant.js";
import { pageCameras, loadRecordings, startCameraLive, stopCameraLive, applyCameraPlayerSrc, isTimelineBusy } from "./cameras.js";
import { pageHistory, loadHistoryPoints } from "./history.js";
import { pageStore, ensureStoreLoaded } from "./store.js";
import { pageSettings } from "./settings.js";
import { pageLab, stopLabLogs } from "./lab.js";
import { pageAbout } from "./about.js";
import { pageShortcuts } from "./shortcuts.js";
import { pagePlugins } from "./plugins.js";
import { pageFleet, loadFleet } from "./fleet.js";

export {
  pageHome,
  pageGroup,
  pageEnergy,
  pageControls,
  pageDrive,
  pageSound,
  pageConnect,
  pageAssistant,
  pageCameras,
  pageHistory,
  pageStore,
  pageSettings,
  pageLab,
  pageAbout,
  pageShortcuts,
  pagePlugins,
  pageFleet,
  loadFleet,
  loadHistoryPoints,
  loadEnergyDash,
  loadRecordings,
  loadSounds,
  startCameraLive,
  stopCameraLive,
  stopLabLogs,
  applyCameraPlayerSrc,
  ensureStoreLoaded,
  isTimelineBusy,
};

export function pageView(page) {
  const map = {
    home: pageHome,
    fleet: pageFleet,
    history: pageHistory,
    controls: pageControls,
    drive: pageDrive,
    energy: pageEnergy,
    lights: function () {
      return pageGroup(t("section.lights.title", "Iluminação"), "", "lights");
    },
    adas: function () {
      return pageGroup(t("section.adas.title", "ADAS"), "", "adas");
    },
    assistant: pageAssistant,
    display: function () {
      return pageGroup(t("section.display.title", "Tela"), "", "display");
    },
    sound: pageSound,
    connect: pageConnect,
    vehicle: function () {
      return pageGroup(t("section.vehicle.title", "Meu Veículo"), "", "vehicle");
    },
    cameras: pageCameras,
    dvr: pageCameras,
    store: pageStore,
    shortcuts: pageShortcuts,
    plugins: pagePlugins,
    settings: pageSettings,
    // Legacy deep-links / quick-entry
    system: pageSettings,
    climate: pageControls,
    cabin: pageControls,
    safety: function () {
      return pageGroup(t("section.adas.title", "ADAS"), "", "adas");
    },
    lab: pageLab,
    about: pageAbout,
  };
  const fn = map[page] || pageHome;
  return fn();
}

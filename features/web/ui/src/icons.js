import { html } from "lit";
import { registerIconLibrary } from "@awesome.me/webawesome/dist/components/icon/library.js";
import { appUrl } from "./base.js";

const SPRITE = appUrl("/static/icons/sprite.svg");

/** Control / page names → sprite symbol ids (public/icons/sprite.svg). */
const ICON_MAP = {
  home: "i-home",
  drive: "i-drive",
  controls: "i-cabin",
  climate: "i-climate",
  energy: "i-energy",
  lights: "i-light",
  light: "i-light",
  adas: "i-adas",
  assistant: "i-assistant",
  display: "i-hud",
  sound: "i-sound",
  connect: "i-usb",
  vehicle: "i-sensor",
  history: "i-history",
  cabin: "i-cabin",
  camera: "i-camera",
  cameras: "i-camera",
  system: "i-system",
  settings: "i-system",
  store: "i-store",
  lab: "i-lab",
  about: "i-about",
  back: "i-back",
  pin: "i-pin",
  hide: "i-hide",
  flip: "i-flip",
  regen: "i-regen",
  brake: "i-brake",
  steer: "i-steer",
  fan: "i-fan",
  temp: "i-temp",
  snow: "i-snow",
  recirc: "i-recirc",
  battery: "i-battery",
  charge: "i-charge",
  lock: "i-lock",
  hud: "i-hud",
  seat: "i-seat",
  window: "i-window",
  usb: "i-usb",
  sensor: "i-sensor",
  plugins: "i-plugins",
  plugin: "i-plugins",
  play: "i-play",
  pause: "i-pause",
  previous: "i-previous",
  next: "i-next",
  plus: "i-plus",
  minus: "i-minus",
  close: "i-close",
  "chevron-left": "i-chevron-left",
  "chevron-right": "i-chevron-right",
  check: "i-check",
  drive_mode: "i-drive",
  mirror: "i-cabin",
};

/** Sprite symbol id for a control, page or glyph name (unknown names fall back to a sensor). */
export function iconId(name) {
  return ICON_MAP[name] || ICON_MAP[name && name.replace(/-.*/, "")] || "i-sensor";
}

// Every <wa-icon name=…> resolves into the local sprite; nothing is fetched from a CDN.
registerIconLibrary("default", {
  spriteSheet: true,
  resolver: (name) => SPRITE + "#" + iconId(name),
});

/**
 * @param {string} [name]
 * @param {string} [label] accessible label; decorative when omitted
 */
export function icon(name, label) {
  return html`<wa-icon name=${name || "sensor"} label=${label || ""}></wa-icon>`;
}

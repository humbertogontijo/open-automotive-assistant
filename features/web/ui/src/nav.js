import { session } from "./store.js";

/** In-session scroll positions by section (no localStorage — resets on process kill). */
const scrollByPage = Object.create(null);

export function stashCurrentScroll() {
  const main = document.getElementById("main");
  if (!main || !session.page) return;
  scrollByPage[session.page] = main.scrollTop || 0;
}

export function rememberScroll(sec, scrollTop) {
  if (!sec) return;
  scrollByPage[sec] = scrollTop || 0;
}

export function getScroll(sec) {
  const n = scrollByPage[sec || session.page];
  return typeof n === "number" ? n : 0;
}

export function restorePageScroll(sec) {
  const main = document.getElementById("main");
  if (!main) return;
  main.scrollTop = getScroll(sec || session.page);
}

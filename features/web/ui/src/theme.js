import { postForm } from "./api.js";
import { prefs } from "./store.js";

export function theme() {
  return prefs.theme || "dark";
}

/** Web Awesome's color scheme class for a data-theme (contrast is a dark variant). */
export function waScheme(t) {
  return t === "light" ? "wa-light" : "wa-dark";
}

/** Apply [t] locally; [persist] also stores it on the car (skip when it came from there). */
export function setTheme(t, persist = true) {
  const root = document.documentElement;
  root.setAttribute("data-theme", t);
  root.classList.remove("wa-light", "wa-dark");
  root.classList.add(waScheme(t));
  prefs.theme = t;
  try {
    localStorage.setItem("oaa_theme", t);
  } catch (e) {}
  if (!persist) return;
  postForm("/api/prefs", "theme=" + encodeURIComponent(t)).catch(function () {});
}

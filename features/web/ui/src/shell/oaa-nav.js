import { html, nothing } from "lit";
import { OaaElement } from "../lit/oaa-element.js";
import { session, statusVersion } from "../store.js";
import { t } from "../i18n.js";
import { icon } from "../icons.js";
import { pagePath } from "../pages/ids.js";

/**
 * @typedef {{ page: string, icon: string, label: string, cap?: string[], role?: "hub" | "local" }} NavItem
 */

/** @type {NavItem[]} */
export const NAV_ITEMS = [
  { page: "fleet", icon: "back", label: "Fleet", role: "hub" },
  { page: "home", icon: "home", label: "Início" },
  { page: "controls", icon: "cabin", label: "Controles" },
  { page: "drive", icon: "drive", label: "Condução" },
  { page: "energy", icon: "energy", label: "Energia", cap: ["CHARGING", "HYBRID_ENERGY"] },
  { page: "lights", icon: "light", label: "Iluminação" },
  { page: "adas", icon: "adas", label: "ADAS" },
  { page: "assistant", icon: "assistant", label: "Assistente" },
  { page: "display", icon: "hud", label: "Tela" },
  { page: "sound", icon: "sound", label: "Som" },
  { page: "connect", icon: "system", label: "Conexão" },
  { page: "vehicle", icon: "sensor", label: "Meu Veículo" },
  { page: "history", icon: "history", label: "Histórico" },
  { page: "cameras", icon: "camera", label: "Câmeras", cap: ["CAMERAS_DVR"] },
  { page: "store", icon: "store", label: "Loja" },
  { page: "shortcuts", icon: "pin", label: "Atalhos" },
  { page: "plugins", icon: "plugins", label: "Plugins" },
  { page: "settings", icon: "system", label: "Ajustes" },
  { page: "lab", icon: "lab", label: "Lab" },
  { page: "about", icon: "about", label: "Sobre" },
];

/** Role and capability gating for a nav entry (hub with no car open shows only the fleet). */
export function navItemVisible(item) {
  const role = session.role || "local";
  if (item.role && item.role !== role) return false;
  if (!item.cap) return true;
  if (role === "hub" && !session.selectedNodeId) return false;
  const caps = session.capabilities || [];
  return item.cap.some((c) => caps.includes(c));
}

/** Page ids hidden by capability gating (the shell redirects away from them). */
export function isPageGated(page) {
  const item = NAV_ITEMS.find((i) => i.page === page);
  return !!item && !!item.cap && !navItemVisible(item);
}

function selectedCarName() {
  const nodes = (session.fleet && session.fleet.nodes) || [];
  const node = nodes.find((n) => n.id === session.selectedNodeId);
  return node ? node.name || node.id : session.selectedNodeId || "";
}

export class OaaNav extends OaaElement {
  render() {
    const page = session.page;
    const showCar = session.role === "hub" && !!session.selectedNodeId;
    const version = statusVersion.get();
    return html`
      <nav class="sidebar">
        <div class="brand">OAA</div>
        ${showCar ? html`<div class="brand-car">${selectedCarName()}</div>` : nothing}
        <div class="nav-list">
          ${NAV_ITEMS.filter(navItemVisible).map(
            (item) => html`<a
              class="nav-item ${page === item.page ? "active" : ""}"
              href=${pagePath(item.page)}
              data-page=${item.page}
              ><span class="ico">${icon(item.icon)}</span
              ><span class="label">${t("nav." + item.page, item.label)}</span></a
            >`,
          )}
        </div>
        <div class="ver">${version ? "v" + version : ""}</div>
      </nav>
    `;
  }
}
customElements.define("oaa-nav", OaaNav);

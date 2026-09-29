import { html, nothing } from "lit";
import { OaaElement } from "../lit/oaa-element.js";
import { session, statusVersion } from "../store.js";
import { t } from "../i18n.js";
import { icon } from "../icons.js";
import { pagePath } from "../pages/ids.js";
import { carServesPage } from "../compat.js";

/**
 * @typedef {{ page: string, icon: string, label: string, cap?: string[], role?: "hub" | "local", admin?: boolean }} NavItem
 */

/** Hub with no car open. @type {NavItem[]} */
const HUB_NAV_ITEMS = [
  { page: "cars", icon: "car", label: "Cars" },
  { page: "settings", icon: "system", label: "Settings", admin: true },
];

/** A car: on the car itself, or opened from a hub. @type {NavItem[]} */
export const NAV_ITEMS = [
  { page: "cars", icon: "back", label: "Cars", role: "hub" },
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

function isHubTop() {
  return session.role === "hub" && !session.selectedNodeId;
}

function isAdmin() {
  const user = (session.status && session.status.user) || (session.hubAuth && session.hubAuth.user);
  return !!(user && user.role === "admin");
}

/** Role, page and capability gating for a car nav entry. */
export function navItemVisible(item) {
  const role = session.role || "local";
  if (item.role && item.role !== role) return false;
  if (role === "hub" && session.selectedNodeId && !carServesPage(item.page, session.status)) return false;
  if (!item.cap) return true;
  if (role === "hub" && !session.selectedNodeId) return false;
  const caps = session.capabilities || [];
  return item.cap.some((c) => caps.includes(c));
}

/** Page ids hidden by page or capability gating (the shell redirects away from them). */
export function isPageGated(page) {
  const item = NAV_ITEMS.find((i) => i.page === page);
  return !!item && !item.role && !navItemVisible(item);
}

function selectedCarName() {
  const nodes = (session.fleet && session.fleet.nodes) || [];
  const node = nodes.find((n) => n.id === session.selectedNodeId);
  return node ? node.name || node.id : session.selectedNodeId || "";
}

export class OaaNav extends OaaElement {
  render() {
    const page = session.page;
    const hubTop = isHubTop();
    const showCar = session.role === "hub" && !hubTop;
    const items = hubTop ? HUB_NAV_ITEMS.filter((i) => !i.admin || isAdmin()) : NAV_ITEMS.filter(navItemVisible);
    const version = statusVersion.get();
    return html`
      <nav class="sidebar">
        <div class="brand">OAA</div>
        ${showCar ? html`<div class="brand-car">${selectedCarName()}</div>` : nothing}
        ${hubTop ? html`<div class="brand-car">${t("nav.hub", "Hub")}</div>` : nothing}
        <div class="nav-list">
          ${items.map(
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

import { loadCss } from "../load-css.js";
loadCss("/static/js/ui/cards/shell.css");

import { html, unsafeHTML } from "../../lit.js";
import { t } from "../../i18n.js";
import { iconSvg } from "../../icons.js";
import { unitLabelFor } from "../../units.js";
import { hideEntity, unhideEntity } from "../../actions.js";

/** Grid span overrides by domain (default 1×1). */
export const DOMAIN_SPAN = {
  climate: { cols: 1, rows: 2 },
  media_player: { cols: 1, rows: 2 },
  charger: { cols: 1, rows: 2 },
  light: { cols: 1, rows: 2 },
  drivetrain: { cols: 1, rows: 2 },
  chassis: { cols: 1, rows: 2 },
  steering: { cols: 1, rows: 2 },
  cover: { cols: 1, rows: 1 },
};

export function icon(name) {
  return unsafeHTML(iconSvg(name || "sensor"));
}

export function displayUnit(c) {
  return unitLabelFor(c);
}

export function pinSnapshot(c) {
  if (!c || !c.persistEnabled) return null;
  if (c.persistValue == null || c.persistValue === "") return null;
  const input = c.input || "int";
  if (input === "bool") {
    const v = c.persistValue;
    return v === "1" || v === "true" || v === true || v === 1 || v === "on"
      ? "1"
      : "0";
  }
  return String(c.persistValue);
}

export function pinChip(label) {
  const title = t("persist.back_hint", "Applied only after the car restarts");
  return html`<span class="pin-chip" title=${title}>${icon("pin")}${label}</span>`;
}

export function statusNote(c) {
  if (c.needsPrivilege || c.status === "denied") {
    return t("status.denied", "Permission denied");
  }
  if (c.status === "failed") {
    return c.permission || t("status.failed", "Read failed");
  }
  if (c.status === "unavailable") {
    return t("status.unavailable", "Unavailable");
  }
  return t("status." + c.status, c.status);
}

export function hideBtn(id, restore) {
  const title = restore
    ? t("entity.unhide", "Show card")
    : t("entity.hide", "Hide card");
  return html`
    <button
      type="button"
      class="hide-btn ${restore ? "restore" : ""}"
      title=${title}
      aria-label=${title}
      @click=${function (ev) {
        ev.stopPropagation();
        if (restore) unhideEntity(id);
        else hideEntity(id);
      }}
    >
      ${icon("hide")}
    </button>
  `;
}

/**
 * Dashboard grid span by domain (frontend-only).
 * @returns {{ cols: number, rows: number }}
 */
export function cardSpan(c) {
  if (!c) return { cols: 1, rows: 1 };
  const domain = c.domain || c.input;
  if (DOMAIN_SPAN[domain]) return DOMAIN_SPAN[domain];
  if (c.input === "climate" || c.input === "media_player" || c.input === "light") {
    return { cols: 1, rows: 2 };
  }
  return { cols: 1, rows: 1 };
}

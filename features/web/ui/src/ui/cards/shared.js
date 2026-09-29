import { html, nothing } from "lit";
import { t, entityLabel, entityHint } from "../../i18n.js";
import { icon } from "../../icons.js";
import { unitLabelFor } from "../../units.js";
import { hideEntity, unhideEntity, setPersist } from "../../actions.js";

/** Grid span overrides by domain (default 1×1). */
export const DOMAIN_SPAN = {
  climate: { cols: 1, rows: 2 },
  media_player: { cols: 1, rows: 2 },
  charger: { cols: 1, rows: 2 },
  drivetrain: { cols: 1, rows: 2 },
  chassis: { cols: 1, rows: 2 },
  steering: { cols: 1, rows: 2 },
  cover: { cols: 1, rows: 1 },
};

export { icon };

export function displayUnit(c) {
  return unitLabelFor(c);
}

export function pinSnapshot(c) {
  if (!c || !c.persistEnabled) return null;
  if (c.persistValue == null || c.persistValue === "") return null;
  const input = c.input || "int";
  if (input === "bool") {
    const v = c.persistValue;
    return v === "1" || v === "true" || v === true || v === 1 || v === "on" ? "1" : "0";
  }
  return String(c.persistValue);
}

/** Reboot value that differs from the live one. */
export function pinChip(label) {
  const title = t("persist.back_hint", "Applied only after the car restarts");
  return html`<wa-tag class="pin-chip" size="small" title=${title}>${icon("pin")}${label}</wa-tag>`;
}

/** @param {string} text @param {"brand"|"neutral"|"success"|"warning"|"danger"} [variant] */
export function badge(text, variant = "neutral", cls = "") {
  return html`<wa-badge class=${cls} variant=${variant} appearance="filled-outlined">${text}</wa-badge>`;
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

/**
 * Icon-only wa-button used for card header actions.
 * @param {{ name: string, label: string, cls?: string, pressed?: boolean, disabled?: boolean, onClick: (ev: Event) => void }} o
 */
export function iconBtn(o) {
  return html`<wa-button
    class="icon-btn ${o.cls || ""} ${o.pressed ? "active" : ""}"
    appearance="plain"
    size="small"
    title=${o.label}
    aria-pressed=${o.pressed == null ? nothing : o.pressed ? "true" : "false"}
    ?disabled=${!!o.disabled}
    @click=${function (ev) {
      ev.stopPropagation();
      o.onClick(ev);
    }}
    >${icon(o.name, o.label)}</wa-button
  >`;
}

export function hideBtn(id, restore) {
  return iconBtn({
    name: "hide",
    cls: "hide-btn" + (restore ? " restore" : ""),
    label: restore ? t("entity.unhide", "Show card") : t("entity.hide", "Hide card"),
    onClick: () => (restore ? unhideEntity(id) : hideEntity(id)),
  });
}

/**
 * Pin (or unpin) the value applied after a car restart.
 * @param {any} c control
 * @param {string|null} [value] what to pin; defaults to the live value
 */
export function pinBtn(c, value) {
  const pinned = !!c.persistEnabled && pinSnapshot(c) != null;
  const v = value !== undefined ? value : c.value == null || c.value === "" ? null : String(c.value);
  return iconBtn({
    name: "pin",
    cls: "pin-btn",
    label: pinned ? t("persist.unpin", "Unpin reboot value") : t("persist.pin", "Pin current value for reboot"),
    pressed: pinned,
    disabled: !pinned && v == null,
    onClick: () => {
      if (pinned) setPersist(c.id, { enabled: false });
      else if (v != null) setPersist(c.id, { enabled: true, value: v });
    },
  });
}

/** Inputs are disabled: no usable reading, or the car accepts writes but OAA keeps them locked. */
export function isLocked(c) {
  return !!c.writeLocked || (c.status !== "ok" && c.status !== "cached");
}

/** Write-locked / cached / unavailable note above a card body. */
export function lockNote(c) {
  const writeLocked = c.writeLocked
    ? html`<div
        class="lock-note write-locked"
        title=${t("status.write_locked.hint", "The car accepts writes to this property, but OAA keeps it locked")}
      >
        ${icon("lock")}${t("status.write_locked", "Locked")}
      </div>`
    : nothing;
  if (c.stale) return html`${writeLocked}<div class="lock-note">${t("status.cached", "Último conhecido")}</div>`;
  if (c.status && c.status !== "ok" && c.status !== "cached") {
    return html`${writeLocked}<div class="lock-note">${statusNote(c)}</div>`;
  }
  return writeLocked;
}

/**
 * Card surface shared by every control and pref card.
 * @param {{
 *   cls?: string, id?: string, span?: { cols: number, rows: number },
 *   iconName?: string, glyph?: { text: string, label: string }, title: any, hint?: any, badge?: any,
 *   actions?: any, bodyCls?: string, body?: any,
 * }} o
 */
export function cardShell(o) {
  return html`
    <wa-card
      class="ctrl-card ${o.cls || ""}"
      data-card=${o.id || nothing}
      data-col-span=${o.span ? o.span.cols : nothing}
      data-row-span=${o.span ? o.span.rows : nothing}
    >
      <div class="ctrl-head">
        ${o.glyph
          ? html`<div class="ctrl-icon is-glyph" role="img" aria-label=${o.glyph.label} title=${o.glyph.label}>
              ${o.glyph.text}
            </div>`
          : html`<div class="ctrl-icon">${icon(o.iconName || "sensor")}</div>`}
        <div class="ctrl-meta">
          <h3>${o.title}</h3>
          ${o.hint ? html`<p class="hint" title=${typeof o.hint === "string" ? o.hint : nothing}>${o.hint}</p>` : nothing}
          ${o.badge || nothing}
        </div>
        ${o.actions ? html`<div class="card-actions">${o.actions}</div>` : nothing}
      </div>
      <div class="ctrl-body ${o.bodyCls || ""}">${o.body || nothing}</div>
    </wa-card>
  `;
}

/**
 * Standard control card: icon, label, hint, hide + pin actions, lock note, then [body].
 * @param {any} c control
 * `dense` is for multi-control cards: shorter rows and a one-line hint so every control fits.
 * @param {{ restore?: boolean, cls?: string, bodyCls?: string, dense?: boolean, iconName?: string, glyph?: { text: string, label: string }, hint?: any, pinValue?: string|null, pinnable?: boolean, body: any }} o
 */
export function controlShell(c, o) {
  const locked = isLocked(c);
  const pinned = !!c.persistEnabled && pinSnapshot(c) != null;
  const pinnable = o.pinnable !== false && !o.restore && !c.writeLocked;
  return cardShell({
    cls: [o.cls || "", o.dense ? "dense" : "", locked ? "locked" : "", pinned ? "pinned" : ""].join(" "),
    id: c.id,
    span: cardSpan(c),
    iconName: o.iconName || c.icon || c.id,
    glyph: o.glyph,
    title: entityLabel(c),
    hint: o.hint !== undefined ? o.hint : entityHint(c),
    badge: c.acronym ? badge(c.acronym, "neutral", "acronym") : nothing,
    actions: html`${hideBtn(c.id, o.restore)}${pinnable ? pinBtn(c, o.pinValue) : nothing}`,
    bodyCls: o.bodyCls,
    body: html`${lockNote(c)}${o.body}`,
  });
}

/**
 * Dashboard grid span by domain (frontend-only).
 * @returns {{ cols: number, rows: number }}
 */
export function cardSpan(c) {
  if (!c) return { cols: 1, rows: 1 };
  const domain = c.domain || c.input;
  if (DOMAIN_SPAN[domain]) return DOMAIN_SPAN[domain];
  if (c.input === "climate" || c.input === "media_player") {
    return { cols: 1, rows: 2 };
  }
  return { cols: 1, rows: 1 };
}

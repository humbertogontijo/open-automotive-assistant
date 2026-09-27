import { html, nothing } from "../../lit.js";
import { t, entityLabel, entityHint } from "../../i18n.js";
import { setPersist } from "../../actions.js";
import {
  icon,
  pinSnapshot,
  statusNote,
  hideBtn,
  cardSpan,
} from "./shared.js";
import { inputWidget } from "./input.js";
import { sensorCard } from "./sensor.js";
import { mediaPlayerCard } from "./media-player.js";
import { climateCard } from "./climate.js";
import { lightCard } from "./light.js";
import { cameraCard } from "./camera.js";
import { coverCard } from "./cover.js";
import { compositeCard } from "./composite.js";

/**
 * Domain → dedicated card renderer. Lookup by `c.domain` (HA-shaped).
 * Domains not listed fall through to the atomic widget shell.
 */
const DOMAIN_CARDS = {
  climate: climateCard,
  light: lightCard,
  cover: coverCard,
  media_player: mediaPlayerCard,
  camera: cameraCard,
  drivetrain: compositeCard,
  chassis: compositeCard,
  steering: compositeCard,
  charger: compositeCard,
  ev_battery: compositeCard,
  hud: compositeCard,
  sensor: sensorCard,
};

export function controlCard(c, restore) {
  const domain = c.domain;
  const render = DOMAIN_CARDS[domain];
  if (render) {
    if (domain === "sensor" && !restore && c.status !== "ok") return nothing;
    return render(c, restore);
  }

  const locked = c.status !== "ok" && c.status !== "cached";
  const pinned = !!c.persistEnabled && pinSnapshot(c) != null;
  const hasValue = c.value != null && c.value !== "";
  const pinTitle = pinned
    ? t("persist.unpin", "Unpin reboot value")
    : t("persist.pin", "Pin current value for reboot");
  const span = cardSpan(c);

  return html`
    <div
      class="ctrl-card ${locked ? "locked" : ""} ${pinned ? "pinned" : ""}"
      data-card=${c.id}
      data-col-span=${span.cols}
      data-row-span=${span.rows}
    >
      <div class="ctrl-head">
        <div class="ctrl-icon">${icon(c.icon || c.id)}</div>
        <div class="ctrl-meta">
          <h3>${entityLabel(c)}</h3>
          ${entityHint(c) ? html`<p class="hint">${entityHint(c)}</p>` : nothing}
          ${c.acronym
            ? html`<span class="badge acronym">${c.acronym}</span>`
            : nothing}
        </div>
        <div class="card-actions">
          ${hideBtn(c.id, restore)}
          ${restore || c.writeOnly || c.input === "command"
            ? nothing
            : html`
          <button
            type="button"
            class="pin-btn ${pinned ? "active" : ""}"
            title=${pinTitle}
            aria-label=${pinTitle}
            aria-pressed=${pinned ? "true" : "false"}
            ?disabled=${!pinned && !hasValue}
            @click=${function (ev) {
              ev.stopPropagation();
              if (pinned) {
                setPersist(c.id, { enabled: false });
                return;
              }
              if (c.value == null || c.value === "") return;
              setPersist(c.id, { enabled: true, value: String(c.value) });
            }}
          >
            ${icon("pin")}
          </button>`}
        </div>
      </div>
      <div class="ctrl-body">
        ${c.stale
          ? html`<div class="lock-note">${t("status.cached", "Último conhecido")}</div>`
          : locked && c.status && c.status !== "ok"
            ? html`<div class="lock-note">${statusNote(c)}</div>`
            : nothing}
        ${inputWidget(c)}
      </div>
    </div>
  `;
}

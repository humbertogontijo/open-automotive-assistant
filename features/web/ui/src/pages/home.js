import { html, nothing } from "lit";
import { catalog, hiddenEntitiesByGroup, isShowingHidden } from "../store.js";
import { t } from "../i18n.js";
import { entityGrid } from "../ui/cards/grid.js";
import { pageHead } from "../ui/cards/page-head.js";
import { dashSummary, pickEntities } from "../ui/dashboard.js";

var HOME_HERO_IDS = [
  "TYPE_EV_BATTERY_PERCENTAGE",
  "sensor.soc",
  "RANGE_REMAINING",
  "sensor.range",
  "PERF_VEHICLE_SPEED",
  "sensor.speed",
  "sensor.gear",
  "GEAR_SELECTION",
  "drivetrain.vehicle",
  "sensor.fuel",
];

export function pageHome() {
  // Dashboard sensors (group=home) + drivetrain composite.
  const viewing = isShowingHidden("home");
  const items = viewing
    ? hiddenEntitiesByGroup("home").filter(function (e) {
        return e.domain === "sensor";
      })
    : catalog.entities.filter(function (e) {
        return e.group === "home" && e.domain === "sensor" && e.status === "ok";
      });
  const heroPool = viewing
    ? items
    : catalog.entities.filter(function (e) {
        if (!(e.status === "ok" || e.status === "cached")) return false;
        if (e.id === "drivetrain.vehicle") return true;
        return e.group === "home" && e.domain === "sensor";
      });
  const hero = pickEntities(heroPool, HOME_HERO_IDS);
  const heroIds = {};
  hero.forEach(function (e) {
    heroIds[e.id] = true;
  });
  const rest = items.filter(function (e) {
    return !heroIds[e.id];
  });

  return html`
    ${pageHead(
      t("section.home.title", "Início"),
      "home",
      t("section.home.sub", "At a glance"),
    )}
    ${viewing
      ? entityGrid(items, { restore: true })
      : html`
          ${dashSummary(hero, { className: "dash-home" })}
          ${rest.length
            ? html`
                <h2 class="page-label" style="margin:20px 0 10px">
                  ${t("dash.home.more", "More sensors")}
                </h2>
                ${entityGrid(rest)}
              `
            : nothing}
        `}
  `;
}

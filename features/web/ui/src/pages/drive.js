import { html, nothing } from "lit";
import {
  entitiesByGroup,
  hiddenEntitiesByGroup,
  isShowingHidden,
  catalog,
} from "../store.js";
import { t } from "../i18n.js";
import { entityGrid } from "../ui/cards/grid.js";
import { pageHead } from "../ui/cards/page-head.js";
import { familySections } from "./group.js";
import { dashSummary, pickEntities, withoutIds } from "../ui/dashboard.js";

var DRIVE_HERO_IDS = [
  "PERF_VEHICLE_SPEED",
  "sensor.speed",
  "sensor.gear",
  "GEAR_SELECTION",
];

/** Product composites lead the drive grid so packing stays aligned. */
var DRIVE_COMPOSITE_IDS = [
  "drivetrain.vehicle",
  "chassis.vehicle",
  "steering.vehicle",
];

export function pageDrive() {
  const group = "drive";
  const viewing = isShowingHidden(group);
  const items = viewing ? hiddenEntitiesByGroup(group) : entitiesByGroup(group);
  // Status strip: telemetry only. Drive composites stay in the card grid below.
  const pool = viewing
    ? items
    : catalog.entities.filter(function (e) {
        return (
          (e.id === "PERF_VEHICLE_SPEED" ||
            e.id === "sensor.speed" ||
            e.id === "sensor.gear" ||
            e.id === "GEAR_SELECTION") &&
          (e.status === "ok" || e.status === "cached")
        );
      });
  const strip = pickEntities(pool, DRIVE_HERO_IDS);

  const rest = withoutIds(
    items,
    strip.map(function (e) {
      return e.id;
    }),
  );
  const composites = pickEntities(rest, DRIVE_COMPOSITE_IDS);
  const more = withoutIds(
    rest,
    composites.map(function (e) {
      return e.id;
    }),
  );
  // Composites first, then remaining drive entities — one labeled section.
  const ordered = composites.concat(more);

  return html`
    ${pageHead(
      t("section.drive.title", "Condução"),
      group,
      t("section.drive.sub", "Drive mode, regen, and chassis"),
    )}
    ${viewing
      ? familySections(items, { restore: true })
      : html`
          ${dashSummary(strip, { className: "dash-drive" })}
          ${ordered.length
            ? html`
                <h2 class="page-label" style="margin:20px 0 10px">
                  ${t("dash.drive.controls", "Drive controls")}
                </h2>
                ${entityGrid(ordered)}
              `
            : nothing}
        `}
  `;
}

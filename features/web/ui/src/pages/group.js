import { html } from "lit";
import {
  entitiesByGroup,
  groupBySection,
  hiddenEntitiesByGroup,
  isShowingHidden,
} from "../store.js";
import { t } from "../i18n.js";
import { entityGrid } from "../ui/cards/grid.js";
import { pageHead } from "../ui/cards/page-head.js";

/** Preferred section order within a page (unknown sections sort last, A–Z). */
var SECTION_ORDER = [
  "climate",
  "hvac",
  "seat",
  "window",
  "mirror",
  "lock",
  "exterior",
  "scene",
  "drive",
  "adas",
  "charge",
  "hybrid",
  "light",
  "ambience",
  "hud",
  "brightness",
  "telemetry",
  "setting",
  "obd2",
  "other",
];

function sectionTitle(sectionId) {
  return t("section." + sectionId + ".title", sectionId);
}

/** Group entities into page subsections by `section` (not HA domain). */
export function familySections(items, opts) {
  opts = opts || {};
  if (!items || !items.length) {
    return entityGrid(items, opts);
  }
  var buckets = groupBySection(items);
  var keys = Object.keys(buckets);
  keys.sort(function (a, b) {
    var ia = SECTION_ORDER.indexOf(a);
    var ib = SECTION_ORDER.indexOf(b);
    if (ia < 0) ia = SECTION_ORDER.length;
    if (ib < 0) ib = SECTION_ORDER.length;
    if (ia !== ib) return ia - ib;
    return a < b ? -1 : a > b ? 1 : 0;
  });
  if (keys.length === 1) {
    return entityGrid(buckets[keys[0]], opts);
  }
  return keys.map(function (sec) {
    return html`
      <h2 class="page-label" style="margin:20px 0 10px">${sectionTitle(sec)}</h2>
      ${entityGrid(buckets[sec], opts)}
    `;
  });
}

export function pageGroup(title, sub, group) {
  const viewing = isShowingHidden(group);
  const items = viewing
    ? hiddenEntitiesByGroup(group)
    : entitiesByGroup(group);
  return html`
    ${pageHead(title, group, sub)}
    ${familySections(items, viewing ? { restore: true } : null)}
  `;
}

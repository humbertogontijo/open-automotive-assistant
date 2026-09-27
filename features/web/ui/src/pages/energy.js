import { html, nothing } from "lit";
import { catalog, entitiesByGroup, hiddenEntitiesByGroup, isShowingHidden } from "../store.js";
import { t, entityLabel } from "../i18n.js";
import { api } from "../api.js";
import { pageHead } from "../ui/cards/page-head.js";
import { OaaPage } from "../lit/oaa-page.js";
import { familySections } from "./group.js";
import { dashSummary, dashSparkline, pickEntities, withoutIds } from "../ui/dashboard.js";

const ENERGY_HERO_IDS = [
  "TYPE_EV_BATTERY_PERCENTAGE",
  "sensor.soc",
  "sensor.hybrid_soc",
  "RANGE_REMAINING",
  "sensor.range",
  "sensor.range_ev",
  "sensor.fuel",
  "sensor.charge_plug",
  "CHARGE_FUNC_CHARGING_PLUG_STATE",
  "charger.vehicle",
  "sensor.charge_energy",
  "sensor.charge_eta",
];

const ENERGY_SPARK_IDS = ["TYPE_EV_BATTERY_PERCENTAGE", "sensor.soc", "sensor.charge_energy", "sensor.avg_energy"];

/** Home/energy sensors that live on Início but belong in the energy hero. */
const HERO_EXTRA_IDS = ["sensor.soc", "sensor.hybrid_soc", "sensor.range", "sensor.range_ev", "sensor.fuel"];

/** Last 24 h of the spark entities; entities without history are skipped. */
async function loadSparks() {
  const end = Date.now();
  const start = end - 24 * 60 * 60 * 1000;
  /** @type {Record<string, any[]>} */
  const sparks = {};
  await Promise.all(
    ENERGY_SPARK_IDS.map(async function (id) {
      try {
        const res = await api("/api/history/" + encodeURIComponent(id) + "?start=" + start + "&end=" + end + "&limit=200");
        if (res && res.points && res.points.length) sparks[id] = res.points;
      } catch (e) {}
    }),
  );
  return sparks;
}

class OaaPageEnergy extends OaaPage {
  static properties = {
    sparks: { state: true },
  };

  constructor() {
    super();
    /** @type {Record<string, any[]>} */
    this.sparks = {};
    this.loading = false;
  }

  async load() {
    if (this.loading) return;
    this.loading = true;
    try {
      this.sparks = await loadSparks();
    } finally {
      this.loading = false;
    }
  }

  render() {
    const group = "energy";
    const viewing = isShowingHidden(group);
    const items = viewing ? hiddenEntitiesByGroup(group) : entitiesByGroup(group);
    const pool = viewing
      ? items
      : catalog.entities.filter(
          (e) =>
            (e.group === "energy" || HERO_EXTRA_IDS.indexOf(e.id) >= 0) && (e.status === "ok" || e.status === "cached"),
        );
    const hero = pickEntities(pool, ENERGY_HERO_IDS);
    const rest = withoutIds(
      items,
      hero.map((e) => e.id),
    );
    const sparkBlocks = ENERGY_SPARK_IDS.map((id) => {
      const pts = this.sparks[id];
      if (!pts || pts.length < 2) return null;
      const ent = pickEntities(pool, [id])[0];
      return dashSparkline(pts, { title: (ent && (ent.friendlyName || entityLabel(ent))) || id });
    }).filter(Boolean);

    return html`
      ${pageHead(t("section.energy.title", "Energia"), group, t("section.energy.sub", "Battery, charging, and consumption"))}
      ${viewing
        ? familySections(items, { restore: true })
        : html`
            ${dashSummary(hero, { className: "dash-energy" })}
            ${sparkBlocks.length ? html`<div class="dash-sparks">${sparkBlocks}</div>` : nothing}
            <h2 class="page-label energy-label">${t("dash.energy.controls", "Charge & hybrid")}</h2>
            ${familySections(rest)}
          `}
    `;
  }
}
customElements.define("oaa-page-energy", OaaPageEnergy);

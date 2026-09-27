import { html, unsafeStatic } from "lit/static-html.js";
import { ifDefined } from "lit/directives/if-defined.js";
import { pageDef } from "./ids.js";
import { t } from "../i18n.js";
import { OaaElement } from "../lit/oaa-element.js";
import { pageHome } from "./home.js";
import { pageGroup } from "./group.js";
import { pageControls } from "./controls.js";
import { pageDrive } from "./drive.js";
import { pageConnect } from "./connect.js";
import { pageAssistant } from "./assistant.js";
import { pageAbout } from "./about.js";
import "./energy.js";
import "./sound.js";
import "./store.js";
import "./plugins.js";
import "./fleet.js";

/** Catalog-only pages: no data of their own, just a view of the store. */
function definePage(tag, view) {
  customElements.define(
    tag,
    class extends OaaElement {
      render() {
        return view();
      }
    },
  );
}
definePage("oaa-page-home", pageHome);
definePage("oaa-page-controls", pageControls);
definePage("oaa-page-drive", pageDrive);
definePage("oaa-page-connect", pageConnect);
definePage("oaa-page-assistant", pageAssistant);
definePage("oaa-page-about", pageAbout);

/** @type {Record<string, [string, string]>} */
const GROUP_TITLES = {
  lights: ["section.lights.title", "Iluminação"],
  adas: ["section.adas.title", "ADAS"],
  display: ["section.display.title", "Tela"],
  vehicle: ["section.vehicle.title", "Meu Veículo"],
};

/** One entity group (lights, ADAS, display, vehicle). */
class OaaPageGroup extends OaaElement {
  static properties = {
    group: {},
  };

  constructor() {
    super();
    this.group = "";
  }

  render() {
    const [key, fallback] = GROUP_TITLES[this.group] || ["", this.group];
    return pageGroup(t(key, fallback), "", this.group);
  }
}
customElements.define("oaa-page-group", OaaPageGroup);

/** Load the chunk behind `page` (no-op for pages in the main bundle). */
export function loadPageModule(page) {
  const load = pageDef(page).load;
  return load ? load() : Promise.resolve(null);
}

export function pageView(page) {
  const def = pageDef(page);
  const tag = unsafeStatic(def.tag);
  // eslint-disable-next-line lit/binding-positions, lit/no-invalid-html -- static-html tag
  return html`<${tag} group=${ifDefined(def.group)}></${tag}>`;
}

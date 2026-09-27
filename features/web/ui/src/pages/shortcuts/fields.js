/**
 * Shared pieces of the shortcut, routine and scene editors: entity value options and
 * fields, and the editor base element that owns a draft.
 */
import { html, nothing } from "lit";
import { live } from "lit/directives/live.js";
import { i18n } from "../../store.js";
import { OaaElement } from "../../lit/oaa-element.js";
import { prefCard } from "../../ui/cards/prefs.js";
import { boolToggle, boolOpts } from "../../ui/cards/bool.js";
import { choiceSelect } from "../../ui/cards/choice.js";
import { t, optionLabel } from "../../i18n.js";
import { icon } from "../../icons.js";
import { errText, postJson } from "../../api.js";
import { toastError } from "../../ui/toast.js";
import { loadShortcuts } from "../../shortcuts-data.js";

/** Options for a control/entity value, preferring API i18n labels (same as cards). */
export function valueOptionsForControl(c) {
  if (!c) return null;
  if (c.input === "bool") return boolOpts(c);
  if (c.options && c.options.length) {
    return c.options.map(function (o) {
      return { value: String(o.value), label: optionLabel(o), labelKey: o.labelKey };
    });
  }
  if (c.domain === "device_tracker") {
    return [
      { value: "home", label: t("device_tracker.home", "Home") },
      { value: "not_home", label: t("device_tracker.not_home", "Away") },
    ];
  }
  if (c.domain === "media_player" || c.input === "media_player") {
    return [
      { value: "playing", label: t("media_player.playing", "Playing") },
      { value: "paused", label: t("media_player.paused", "Paused") },
      { value: "idle", label: t("media_player.idle", "Idle") },
    ];
  }
  if (c.domain === "climate" || c.input === "climate") {
    const modes = (c.attributes && c.attributes.hvac_modes) || ["off", "manual", "auto"];
    return modes.map(function (m) {
      const key = String(m);
      return { value: key === "off" ? key : "hvac_mode:" + key, label: t("climate.mode." + key, key) };
    });
  }
  const map = c.valueMapId && i18n.valueMaps[c.valueMapId];
  if (map) {
    return Object.keys(map)
      .map((k) => ({ value: String(k), label: t(map[k], String(k)) }))
      .sort(function (a, b) {
        const na = parseInt(a.value, 10);
        const nb = parseInt(b.value, 10);
        if (!isNaN(na) && !isNaN(nb)) return na - nb;
        return a.value < b.value ? -1 : a.value > b.value ? 1 : 0;
      });
  }
  return null;
}

/**
 * Value editor: a choice select when the entity has known values, else free text.
 * @param {{ entity?: any, options?: any[] | null, current: any, placeholder?: string, onSelect: (v: string) => void }} opts
 */
export function entityValueField(opts) {
  const options = opts.options != null ? opts.options : valueOptionsForControl(opts.entity);
  const current = opts.current != null ? String(opts.current) : "";
  if (options && options.length) {
    return choiceSelect({ options: options, current: current, onSelect: opts.onSelect });
  }
  return html`<wa-input
    placeholder=${opts.placeholder || ""}
    .value=${live(current)}
    @input=${(ev) => opts.onSelect(ev.target.value)}
  ></wa-input>`;
}

/** Icon-only remove button for an editor row. */
export function removeRowButton(onClick) {
  const label = t("common.remove", "Remove");
  return html`<wa-button class="editor-remove" size="small" appearance="plain" title=${label} @click=${onClick}
    >${icon("close", label)}</wa-button
  >`;
}

/**
 * Editor for one draft (shortcut, routine or scene). The draft is mutated in place and the
 * element re-renders through [changed]; the page closes it on `oaa-editor-close`.
 */
export class OaaDraftEditor extends OaaElement {
  static properties = {
    draft: { attribute: false },
    busy: { state: true },
  };

  constructor() {
    super();
    /** @type {Record<string, any>} */
    this.draft = {};
    this.busy = false;
  }

  changed() {
    this.requestUpdate();
  }

  close() {
    this.dispatchEvent(new CustomEvent("oaa-editor-close", { bubbles: true }));
  }

  async firstUpdated() {
    this.scrollIntoView({ behavior: "smooth", block: "start" });
    const name = /** @type {import("lit").ReactiveElement & HTMLElement | null} */ (this.querySelector(".editor-name"));
    if (!name) return;
    await name.updateComplete;
    name.focus();
  }

  /** POST target for the draft. */
  get endpoint() {
    return "";
  }

  /** Request body built from the draft. */
  body() {
    return {};
  }

  /** @returns {Promise<boolean>} true to close the editor */
  async save() {
    try {
      const res = await postJson(this.endpoint, this.body());
      await loadShortcuts();
      if (res && res.ok === false) {
        toastError(res.error || "save failed");
        return false;
      }
      return true;
    } catch (e) {
      toastError(errText(e));
      return false;
    }
  }

  async onSave() {
    if (this.busy) return;
    this.busy = true;
    try {
      if (await this.save()) this.close();
    } finally {
      this.busy = false;
    }
  }

  /**
   * @param {{ icon: string, title: string, body: unknown }} o
   */
  shell(o) {
    const d = this.draft;
    return prefCard({
      cls: "editor-card form-card",
      icon: o.icon,
      title: o.title,
      body: html`
        <wa-input
          class="editor-name"
          label=${t("shortcuts.name", "Name")}
          .value=${live(d.name || "")}
          @input=${(ev) => {
            d.name = ev.target.value;
          }}
        ></wa-input>
        ${boolToggle(d.enabled !== false, (val) => {
          d.enabled = val === "1" || val === true;
          this.changed();
        })}
        ${o.body}
        <div class="editor-actions">
          <wa-button variant="brand" ?loading=${this.busy} @click=${() => this.onSave()}
            >${t("shortcuts.save", "Save")}</wa-button
          >
          <wa-button appearance="outlined" @click=${() => this.close()}>${t("shortcuts.cancel", "Cancel")}</wa-button>
        </div>
      `,
    });
  }

  /** @param {string} title @param {string} [hint] */
  section(title, hint) {
    return html`<h3 class="editor-section">${title}</h3>
      ${hint ? html`<p class="hint">${hint}</p>` : nothing}`;
  }

  /** @param {string} label @param {() => void} onClick */
  addButton(label, onClick) {
    return html`<wa-button class="editor-add" size="small" appearance="outlined" @click=${onClick}
      >${icon("plus")} ${label}</wa-button
    >`;
  }
}

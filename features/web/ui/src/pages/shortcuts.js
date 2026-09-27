import { html, nothing } from "lit";
import "@awesome.me/webawesome/dist/components/tab-group/tab-group.js";
import "@awesome.me/webawesome/dist/components/tab/tab.js";
import "@awesome.me/webawesome/dist/components/tab-panel/tab-panel.js";
import { api, errText, postJson } from "../api.js";
import { shortcuts } from "../store.js";
import { t } from "../i18n.js";
import { OaaPage } from "../lit/oaa-page.js";
import { prefCard } from "../ui/cards/prefs.js";
import { boolToggle } from "../ui/cards/bool.js";
import { confirmDialog } from "../ui/confirm.js";
import { toast, toastError } from "../ui/toast.js";
import { loadShortcuts } from "../shortcuts-data.js";
import { toDraft, triggerLabel, actionLabel } from "./shortcuts/model.js";
import { blankShortcut } from "./shortcuts/shortcut-editor.js";
import { blankRoutine } from "./shortcuts/routine-editor.js";
import { blankScene } from "./shortcuts/scene-editor.js";
import "./shortcuts/slots.js";

/** @typedef {"shortcut" | "routine" | "scene"} EditKind */
/** @typedef {{ kind: EditKind, draft: any }} Editing */

/** @type {[string, EditKind, string][]} */
const TABS = [
  ["flows", "shortcut", "Shortcuts"],
  ["scenes", "scene", "Scenes"],
  ["routines", "routine", "Routines"],
];

/** @param {EditKind} kind */
function listOf(kind) {
  if (kind === "scene") return shortcuts.scenes;
  if (kind === "routine") return shortcuts.routines;
  return shortcuts.shortcuts;
}

/** @param {EditKind} kind */
function blankFor(kind) {
  if (kind === "scene") return blankScene();
  if (kind === "routine") return blankRoutine();
  return blankShortcut();
}

/** @param {string} path @param {string} failMsg */
async function post(path, failMsg, body) {
  try {
    const res = await postJson(path, body || undefined);
    if (res && res.ok === false) toastError(res.error || failMsg);
    return res;
  } catch (e) {
    toastError(errText(e));
    return null;
  }
}

class OaaPageShortcuts extends OaaPage {
  static properties = {
    tab: { state: true },
    editing: { state: true },
  };

  constructor() {
    super();
    this.tab = "flows";
    /** @type {Editing | null} */
    this.editing = null;
    this.addEventListener("oaa-editor-close", () => {
      this.editing = null;
    });
    this.addEventListener("oaa-edit", (/** @type {CustomEvent} */ ev) => {
      const { kind, id } = ev.detail || {};
      this.tab = kind === "scene" ? "scenes" : "routines";
      this.open(kind, id);
    });
  }

  load() {
    return loadShortcuts();
  }

  /** @param {EditKind} kind @param {string} [id] */
  open(kind, id) {
    const saved = id ? listOf(kind).find((x) => x.id === id) : null;
    this.editing = { kind, draft: saved ? toDraft(saved) : blankFor(kind) };
  }

  /** @param {Editing} e */
  editorHtml(e) {
    if (e.kind === "scene") return html`<oaa-scene-editor .draft=${e.draft}></oaa-scene-editor>`;
    if (e.kind === "routine") return html`<oaa-routine-editor .draft=${e.draft}></oaa-routine-editor>`;
    return html`<oaa-shortcut-editor .draft=${e.draft}></oaa-shortcut-editor>`;
  }

  /** @param {EditKind} kind @param {string} path @param {string} message */
  async deleteItem(kind, id, path, message) {
    if (!(await confirmDialog(message, { danger: true }))) return;
    await api(path + encodeURIComponent(id), { method: "DELETE" }).catch((e) => toastError(errText(e)));
    if (this.editing && this.editing.kind === kind && this.editing.draft.id === id) this.editing = null;
    await loadShortcuts();
  }

  async run(path, id) {
    const res = await post(path + encodeURIComponent(id) + "/run", t("shortcuts.run_failed", "Run failed"));
    if (res && res.ok !== false) toast(t("shortcuts.ran", "Done"), { variant: "success", duration: 2000 });
  }

  /** @param {string} runPath @param {() => void} onEdit @param {() => void} onDelete */
  buttons(runPath, id, onEdit, onDelete, deleteLabel = t("shortcuts.delete", "Delete")) {
    return html`<div class="list-actions">
      ${runPath
        ? html`<wa-button class="sc-run" variant="brand" @click=${() => this.run(runPath, id)}
            >${t("shortcuts.run", "Run")}</wa-button
          >`
        : nothing}
      <wa-button class="sc-edit" appearance="outlined" @click=${onEdit}>${t("shortcuts.edit", "Edit")}</wa-button>
      <wa-button class="sc-del" appearance="plain" variant="danger" @click=${onDelete}>${deleteLabel}</wa-button>
    </div>`;
  }

  shortcutCard(s) {
    const trig = (s.triggers || []).map(triggerLabel).join(", ");
    const acts = (s.actions || []).map(actionLabel).join(" → ");
    return prefCard({
      icon: s.icon || "drive",
      title: s.name || s.id,
      sub:
        (s.enabled === false ? t("value.off", "Off") + " · " : "") +
        (trig || t("shortcuts.no_triggers", "No auto triggers")),
      body: html`<p class="mono hint list-summary">${acts || "—"}</p>
        ${this.buttons(
          "/api/shortcuts/",
          s.id,
          () => this.open("shortcut", s.id),
          () => this.deleteItem("shortcut", s.id, "/api/shortcuts/", t("shortcuts.delete_confirm", "Delete this shortcut?")),
        )}`,
    });
  }

  sceneCard(s) {
    return prefCard({
      icon: s.icon || "climate",
      title: s.name || s.id,
      sub: s.builtin ? t("scenes.builtin", "Built-in") : "",
      body: html`${boolToggle(!!s.active, async (val) => {
          await post("/api/scenes/" + encodeURIComponent(s.id) + "/set", "failed", { active: val === "1" });
          await loadShortcuts();
        })}
        ${this.buttons(
          "",
          s.id,
          () => this.open("scene", s.id),
          () => this.deleteItem("scene", s.id, "/api/scenes/", t("scenes.delete_confirm", "Delete this scene?")),
          s.builtin ? t("scenes.reset", "Reset") : t("shortcuts.delete", "Delete"),
        )}`,
    });
  }

  routineCard(r) {
    const acts = (r.actions || []).map(actionLabel).filter(Boolean).join(" · ");
    return prefCard({
      icon: r.icon || "drive",
      title: r.name || r.id,
      sub: acts || t("shortcuts.no_actions", "No actions"),
      body: this.buttons(
        "/api/routines/",
        r.id,
        () => this.open("routine", r.id),
        () => this.deleteItem("routine", r.id, "/api/routines/", t("routines.delete_confirm", "Delete this routine?")),
      ),
    });
  }

  /** @param {EditKind} kind */
  panelHtml(kind) {
    const e = this.editing && this.editing.kind === kind ? this.editing : null;
    const editingId = e && e.draft.id;
    const list = listOf(kind);
    const card =
      kind === "scene"
        ? (s) => this.sceneCard(s)
        : kind === "routine"
          ? (r) => this.routineCard(r)
          : (s) => this.shortcutCard(s);
    const cards = list.map((x) => (editingId && x.id === editingId ? this.editorHtml(e) : card(x)));
    const newLabel =
      kind === "scene"
        ? t("scenes.new", "New scene")
        : kind === "routine"
          ? t("routines.new", "New routine")
          : t("shortcuts.new", "New shortcut");
    /** @type {unknown} */
    let empty = nothing;
    if (!list.length && !e && kind !== "scene") {
      empty =
        kind === "routine"
          ? prefCard({
              icon: "drive",
              title: t("routines.empty", "No routines yet"),
              sub: t("routines.empty.hint", "Routines are reusable action sequences."),
              body: nothing,
            })
          : prefCard({
              icon: "drive",
              title: t("shortcuts.empty", "No shortcuts yet"),
              sub: t("shortcuts.empty.hint", "Create a shortcut with triggers that run scenes or routines."),
              body: nothing,
            });
    }
    return html`
      <div class="page-head page-head-tight">
        <wa-button variant="brand" @click=${() => this.open(kind)}>${newLabel}</wa-button>
      </div>
      <div class="grid">${cards}${e && !editingId ? this.editorHtml(e) : nothing}${empty}</div>
      ${kind === "shortcut" ? html`<oaa-shortcut-slots></oaa-shortcut-slots>` : nothing}
    `;
  }

  render() {
    return html`
      <div class="page-head">
        <h1>${t("section.shortcuts.title", "Shortcuts")}</h1>
      </div>
      <wa-tab-group
        class="shortcuts-tabs"
        .active=${this.tab}
        @wa-tab-show=${(/** @type {CustomEvent} */ ev) => {
          if (ev.target !== ev.currentTarget || ev.detail.name === this.tab) return;
          this.tab = ev.detail.name;
          this.editing = null;
        }}
      >
        ${TABS.map(
          ([name, , label]) => html`<wa-tab slot="nav" panel=${name}>${t("shortcuts.tab." + name, label)}</wa-tab>`,
        )}
        ${TABS.map(
          ([name, kind]) =>
            html`<wa-tab-panel name=${name}>${this.tab === name ? this.panelHtml(kind) : nothing}</wa-tab-panel>`,
        )}
      </wa-tab-group>
    `;
  }
}
customElements.define("oaa-page-shortcuts", OaaPageShortcuts);

import { html, nothing } from "lit";
import { shortcuts } from "../../store.js";
import { t } from "../../i18n.js";
import { api } from "../../api.js";
import { OaaElement } from "../../lit/oaa-element.js";
import { prefCard, prefSegment } from "../../ui/cards/prefs.js";
import { choiceSelect } from "../../ui/cards/choice.js";
import { runPref } from "../../actions.js";

const SLOT_COUNT = 8;

/** The eight overlay pin slots, each bound to one shortcut. */
class OaaShortcutSlots extends OaaElement {
  render() {
    const opts = [{ value: "", label: "—" }].concat(
      shortcuts.shortcuts.map((s) => ({ value: s.id, label: s.name || s.id })),
    );
    const slots = shortcuts.slots || {};
    return html`
      <h2 class="page-label">${t("shortcuts.slots.title", "Pin slots")}</h2>
      <div class="grid">
        ${Array.from({ length: SLOT_COUNT }, (_, i) =>
          prefCard({
            icon: "drive",
            title: t("shortcuts.slot", "Slot") + " " + (i + 1),
            body: choiceSelect({
              options: opts,
              current: slots[String(i)] || "",
              onSelect: (val) => runPref("sc-slot", val, { slot: i }),
            }),
          }),
        )}
      </div>
    `;
  }
}
customElements.define("oaa-shortcut-slots", OaaShortcutSlots);

/** HU float chip / status-bar icon — shown on Settings. */
export function quickEntryCard() {
  const overlay = shortcuts.overlay || {};
  const on = overlay.overlayEnabled !== false;
  const style = overlay.style || "float_chip";
  const canDraw = overlay.canDrawOverlays !== false;
  const opts = [
    { value: "1", label: t("shortcuts.topbar.show", "Show") },
    { value: "0", label: t("shortcuts.topbar.hide", "Hide") },
  ];
  return prefCard({
    icon: "pin",
    title: t("shortcuts.topbar.title", "Floating menu"),
    sub:
      style === "status_bar"
        ? t("shortcuts.topbar.hint_flyme", "Status-bar icon that opens the app menu")
        : t("shortcuts.topbar.hint", "Status-bar or overlay chip that opens the app menu"),
    body: html`
      ${prefSegment("sc-overlay", opts, on ? "1" : "0")}
      ${style === "float_chip" && !canDraw
        ? html`<wa-button
            class="full-width"
            @click=${() => api("/api/shortcuts/overlay/request", { method: "POST" })}
            >${t("shortcuts.overlay.grant", "Grant overlay permission")}</wa-button
          >`
        : nothing}
    `,
  });
}

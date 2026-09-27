import { html, nothing } from "lit";
import { signal } from "../signals.js";
import { OaaElement } from "../lit/oaa-element.js";
import { t } from "../i18n.js";

/**
 * @typedef {{
 *   kind: "confirm" | "alert",
 *   title?: string,
 *   message: string,
 *   confirmLabel?: string,
 *   cancelLabel?: string,
 *   danger?: boolean,
 *   resolve: (ok: boolean) => void,
 * }} DialogRequest
 */

/** @type {import("../signals.js").Signal.State<DialogRequest | null>} */
const current = signal(null);
/** @type {DialogRequest[]} */
const queue = [];

function open(req) {
  return new Promise((resolve) => {
    const r = Object.assign({}, req, { resolve });
    if (current.get()) queue.push(r);
    else current.set(r);
  });
}

function settle(ok) {
  const r = current.get();
  if (!r) return;
  current.set(queue.shift() || null);
  r.resolve(ok);
}

/**
 * Promise-based replacement for window.confirm().
 * @param {string} message
 * @param {{ title?: string, confirmLabel?: string, cancelLabel?: string, danger?: boolean }} [opts]
 * @returns {Promise<boolean>}
 */
export function confirmDialog(message, opts = {}) {
  return /** @type {Promise<boolean>} */ (open(Object.assign({ kind: "confirm", message }, opts)));
}

/**
 * Promise-based replacement for window.alert().
 * @param {string} message
 * @param {{ title?: string }} [opts]
 * @returns {Promise<void>}
 */
export async function alertDialog(message, opts = {}) {
  await open(Object.assign({ kind: "alert", message }, opts));
}

/** The single wa-dialog behind confirmDialog/alertDialog; hosted once by <oaa-app>. */
export class OaaDialogHost extends OaaElement {
  render() {
    const r = current.get();
    const confirm = r && r.kind === "confirm";
    return html`<wa-dialog
      class="oaa-dialog"
      label=${(r && r.title) || (confirm ? t("common.confirm", "Confirm") : t("common.notice", "Notice"))}
      ?open=${!!r}
      @wa-hide=${(ev) => {
        // Only user dismissals (Esc, close button); a settled request already moved on.
        if (ev.target === ev.currentTarget && r && current.get() === r) settle(false);
      }}
    >
      ${r ? html`<p class="dialog-message">${r.message}</p>` : nothing}
      <div slot="footer" class="dialog-actions">
        ${confirm
          ? html`<wa-button appearance="outlined" @click=${() => settle(false)}
              >${r.cancelLabel || t("common.cancel", "Cancel")}</wa-button
            >`
          : nothing}
        <wa-button
          variant=${r && r.danger ? "danger" : "brand"}
          @click=${() => settle(true)}
          >${(r && r.confirmLabel) || t("common.ok", "OK")}</wa-button
        >
      </div>
    </wa-dialog>`;
  }
}
customElements.define("oaa-dialog-host", OaaDialogHost);

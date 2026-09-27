import { html } from "lit";
import { repeat } from "lit/directives/repeat.js";
import { toasts } from "../store.js";
import { OaaElement } from "../lit/oaa-element.js";
import { t } from "../i18n.js";
import { icon } from "../icons.js";

let nextId = 1;

/**
 * Show a transient message in the global stack.
 * @param {string} message
 * @param {{ variant?: import("../store.js").Toast["variant"], duration?: number }} [opts] duration 0 = sticky
 */
export function toast(message, opts = {}) {
  if (!message) return 0;
  const variant = opts.variant || "neutral";
  const id = nextId++;
  toasts.items = [...toasts.items.filter((x) => x.message !== message), { id, variant, message: String(message) }];
  const duration = opts.duration != null ? opts.duration : variant === "danger" ? 8000 : 4000;
  if (duration > 0) setTimeout(() => dismissToast(id), duration);
  return id;
}

/** @param {string} message */
export function toastError(message) {
  return toast(message, { variant: "danger" });
}

/** @param {number} id */
export function dismissToast(id) {
  if (toasts.items.some((x) => x.id === id)) toasts.items = toasts.items.filter((x) => x.id !== id);
}

/** Bottom stack of wa-callouts rendered from the toasts slice; hosted once by <oaa-app>. */
export class OaaToastStack extends OaaElement {
  render() {
    return html`<div class="toast-stack" role="status" aria-live="polite">
      ${repeat(
        toasts.items,
        (x) => x.id,
        (x) => html`<wa-callout class="toast" variant=${x.variant} appearance="filled-outlined">
          <span>${x.message}</span>
          <wa-button
            class="toast-close"
            appearance="plain"
            size="small"
            aria-label=${t("common.close", "Close")}
            @click=${() => dismissToast(x.id)}
            >${icon("close")}</wa-button
          >
        </wa-callout>`,
      )}
    </div>`;
  }
}
customElements.define("oaa-toast-stack", OaaToastStack);

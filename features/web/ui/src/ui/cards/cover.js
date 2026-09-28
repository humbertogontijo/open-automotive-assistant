import { html, nothing } from "lit";
import { t } from "../../i18n.js";
import { controlShell } from "./shared.js";
import { segmentToggle } from "./choice.js";
import { OaaCard } from "./card-element.js";
import { slider } from "./slider.js";

const OPEN_CLOSE = [
  { value: "closed", labelKey: "common.closed" },
  { value: "open", labelKey: "common.open" },
];

function currentPosition(c) {
  const raw = (c.attributes || {}).current_position;
  if (raw == null || raw === "") return null;
  const n = typeof raw === "number" ? raw : parseFloat(raw);
  return isNaN(n) ? null : Math.round(n);
}

/** Position covers expose `current_position` and the position range; trunk (status-only) does not. */
function supportsPosition(c) {
  return c.min != null && c.max != null && (currentPosition(c) != null || !c.composite);
}

class OaaCoverCard extends OaaCard {
  renderCard(c) {
    const locked = this.locked;
    const open = (c.state != null ? c.state : c.value) === "open";
    const pos = currentPosition(c);
    const min = Number(c.min);
    const max = Number(c.max);
    return controlShell(c, {
      restore: this.restore,
      dense: true,
      cls: "cover-card " + (open ? "is-open" : ""),
      bodyCls: "cover-body",
      iconName: c.icon || "window",
      body: html`
        ${segmentToggle({
          options: OPEN_CLOSE,
          current: open ? "open" : "closed",
          locked,
          onSelect: (v) => this.send(v),
        })}
        ${supportsPosition(c)
          ? slider({
              value: pos != null ? pos : open ? max : min,
              min,
              max,
              step: c.step != null ? Number(c.step) : undefined,
              suffix: "%",
              label: t("attr.position", "Position"),
              disabled: locked,
              onCommit: (v) => this.send(String(Math.max(min, Math.min(max, Math.round(v))))),
            })
          : nothing}
      `,
    });
  }
}
customElements.define("oaa-cover-card", OaaCoverCard);

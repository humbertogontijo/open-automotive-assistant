import { runPref } from "../../actions.js";
import { cardShell } from "./shared.js";
import { segmentToggle } from "./choice.js";
import { boolToggle } from "./bool.js";

/** Pref toggle that runs runPref(pref, value, extra). */
export function prefSegment(pref, options, current, extra) {
  extra = extra || {};
  return segmentToggle({
    options: options,
    current: current,
    locked: !!extra.locked,
    pinnedVal: extra.pinnedVal,
    onSelect: function (val) {
      runPref(pref, val, extra);
    },
  });
}

export function prefBool(pref, current, extra) {
  return boolToggle(
    current,
    function (val) {
      runPref(pref, val, extra);
    },
    extra && extra.locked,
    extra && extra.pinnedVal,
  );
}

/**
 * Pref / system card: a wa-card with icon + title header and [body].
 * @param {{ icon?: string, title: any, sub?: any, body?: any, cls?: string }} opts
 */
export function prefCard(opts) {
  return cardShell({
    cls: "pref-card " + (opts.cls || ""),
    iconName: opts.icon || "system",
    title: opts.title,
    hint: opts.sub,
    body: opts.body,
  });
}

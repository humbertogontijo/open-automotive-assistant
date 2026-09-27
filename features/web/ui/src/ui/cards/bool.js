import { entityValueLabel } from "../../i18n.js";
import { isOn } from "../../format.js";
import { segmentToggle } from "./choice.js";

/**
 * Off/on options; with [entity], labels come from its value map (Locked/Unlocked, …) and
 * fall back to On/Off.
 */
export function boolOpts(entity) {
  if (entity) {
    return ["0", "1"].map((value) => ({ value, label: entityValueLabel(entity, value) }));
  }
  return [
    { value: "0", labelKey: "common.off", label: "Off" },
    { value: "1", labelKey: "common.on", label: "On" },
  ];
}

const bit = (v) => (v == null || v === "" ? null : isOn(v) ? "1" : "0");

/**
 * On/off as a two-button segment group; [onSelect] receives "1" or "0". A pinned reboot value
 * marks its segment, like choice controls.
 */
export function boolToggle(current, onSelect, locked, pinnedVal, entity) {
  return segmentToggle({
    options: boolOpts(entity),
    current: bit(current),
    locked,
    pinnedVal: bit(pinnedVal),
    onSelect,
  });
}

import { loadCss } from "../load-css.js";
loadCss("/static/js/ui/cards/choice.css");
loadCss("/static/js/ui/cards/bool.css"); // segmentToggle shares .toggle-group

import { html, nothing, classMap, live } from "../../lit.js";
import { fmt } from "../../api.js";
import { t, optionLabel as resolveOptionLabel } from "../../i18n.js";
import { state, patch } from "../../store.js";
import { pinChip } from "./shared.js";

let choiceSearchTimer = 0;

function optionIndex(opts, val) {
  const i = opts.findIndex(function (o) {
    return String(o.value) === String(val);
  });
  return i >= 0 ? i : 0;
}

function nextOptionValue(opts, val) {
  if (!opts.length) return val;
  const i = optionIndex(opts, val);
  return opts[(i + 1) % opts.length].value;
}

function lookupOptionLabel(opts, val) {
  const o = opts.find(function (x) {
    return String(x.value) === String(val);
  });
  return o ? resolveOptionLabel(o) : fmt(val);
}

export function decodeOpts(raw) {
  if (!raw) return [];
  return String(raw)
    .split("&")
    .filter(Boolean)
    .map(function (pair) {
      const i = pair.indexOf("=");
      if (i < 0) return { value: decodeURIComponent(pair), label: decodeURIComponent(pair) };
      return {
        value: decodeURIComponent(pair.slice(0, i)),
        label: decodeURIComponent(pair.slice(i + 1)),
      };
    });
}

export function cycleNext(opts, val) {
  return nextOptionValue(opts, val);
}

function closeChoices() {
  if (state.openChoiceId != null || state.choiceSearchQuery) {
    patch({ openChoiceId: null, choiceSearchQuery: "" });
  }
}

document.addEventListener("click", closeChoices);

/**
 * @param {object} opts
 * @param {Array} opts.options
 * @param {*} opts.current
 * @param {boolean} [opts.locked]
 * @param {*} [opts.pinnedVal]
 * @param {function} opts.onSelect (value) => void
 * @param {string} [opts.choiceKey] stable id for open menu tracking
 */
export function segmentToggle(opts) {
  const list = opts.options || [];
  if (!list.length) return nothing;
  if (list.length > 4) {
    return choiceSelect(opts);
  }
  const current = opts.current;
  const locked = !!opts.locked;
  const pinnedVal = opts.pinnedVal;
  const hasCurrent = current != null && current !== "";
  const hasPin = pinnedVal != null && pinnedVal !== "";
  const pinTitle = t("persist.back_hint", "Applied only after the car restarts");
  return html`
    <div
      class="toggle-group ${hasCurrent ? "" : "unset"}"
      role="group"
      data-count=${list.length}
    >
      ${list.map(function (o) {
        const active = hasCurrent && String(current) === String(o.value);
        const pinned = hasPin && String(pinnedVal) === String(o.value);
        return html`
          <button
            type="button"
            class="toggle-seg ${active ? "active" : ""} ${pinned ? "pin-mark" : ""}"
            data-val=${o.value}
            ?disabled=${locked || !!o.disabled}
            aria-pressed=${active ? "true" : "false"}
            title=${o.disabled
              ? o.title || t("cameras.storage.unavailable", "Not available")
              : pinned
                ? pinTitle
                : nothing}
            @click=${function (ev) {
              ev.stopPropagation();
              if (locked || o.disabled) return;
              opts.onSelect(o.value);
            }}
          >
            ${resolveOptionLabel(o)}
          </button>
        `;
      })}
    </div>
  `;
}

export function choiceSelect(opts) {
  const list = opts.options || [];
  if (!list.length) return nothing;
  const current = opts.current;
  const locked = !!opts.locked;
  const pinnedVal = opts.pinnedVal;
  const choiceKey = opts.choiceKey || "choice";
  const searchable = !!opts.searchable;
  const hasCurrent = current != null && current !== "";
  const hasPin = pinnedVal != null && pinnedVal !== "";
  const pinTitle = t("persist.back_hint", "Applied only after the car restarts");
  const match = hasPin && hasCurrent && String(current) === String(pinnedVal);
  const label = hasCurrent
    ? lookupOptionLabel(list, current)
    : t("persist.pick_short", "Select…");
  const open = state.openChoiceId === choiceKey;
  const query = open && searchable ? String(state.choiceSearchQuery || "") : "";
  const q = query.trim().toLowerCase();
  const filtered = !q
    ? list
    : list.filter(function (o) {
        const lab = String(resolveOptionLabel(o) || "").toLowerCase();
        const val = String(o.value != null ? o.value : "").toLowerCase();
        return lab.indexOf(q) >= 0 || val.indexOf(q) >= 0;
      });
  const rootClass = classMap({
    "choice-select": true,
    "pin-match": hasPin && match,
    "pin-diff": hasPin && !match,
    unset: !hasCurrent,
    open: open,
    searchable: searchable,
  });
  return html`
    <div
      class=${rootClass}
      data-choice-select
      @click=${function (ev) {
        ev.stopPropagation();
      }}
    >
      <button
        type="button"
        class="choice-trigger"
        ?disabled=${locked}
        aria-haspopup="listbox"
        aria-expanded=${open ? "true" : "false"}
        @click=${function (ev) {
          ev.stopPropagation();
          if (locked) return;
          const nextOpen = !open;
          patch({
            openChoiceId: nextOpen ? choiceKey : null,
            choiceSearchQuery: "",
          });
          if (nextOpen && searchable) {
            requestAnimationFrame(function () {
              const input = document.querySelector(
                '[data-choice-select].open .choice-search',
              );
              if (input) input.focus();
            });
          }
        }}
      >
        <span class="choice-label">${label}</span>
        <svg class="choice-chevron" viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M6 9l6 6 6-6"
            fill="none"
            stroke="currentColor"
            stroke-width="1.8"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </button>
      <div class="choice-menu" role="listbox" ?hidden=${!open}>
        ${searchable
          ? html`
              <input
                class="field choice-search"
                type="search"
                autocomplete="off"
                enterkeyhint="search"
                placeholder=${t("common.search", "Search…")}
                .value=${live(query)}
                @click=${function (ev) {
                  ev.stopPropagation();
                }}
                @input=${function (ev) {
                  const v = ev.target.value;
                  if (choiceSearchTimer) clearTimeout(choiceSearchTimer);
                  choiceSearchTimer = setTimeout(function () {
                    choiceSearchTimer = 0;
                    patch({ choiceSearchQuery: v });
                  }, 120);
                }}
                @keydown=${function (ev) {
                  ev.stopPropagation();
                  if (ev.key === "Escape") {
                    patch({ openChoiceId: null, choiceSearchQuery: "" });
                  }
                }}
              />
            `
          : nothing}
        ${filtered.length
          ? filtered.map(function (o) {
              const active = hasCurrent && String(current) === String(o.value);
              const pinned = hasPin && String(pinnedVal) === String(o.value);
              return html`
                <button
                  type="button"
                  class="choice-opt ${active ? "active" : ""} ${pinned ? "pin-mark" : ""}"
                  role="option"
                  data-val=${o.value}
                  ?disabled=${locked || !!o.disabled}
                  aria-selected=${active ? "true" : "false"}
                  title=${o.disabled
                    ? o.title || t("cameras.storage.unavailable", "Not available")
                    : pinned
                      ? pinTitle
                      : nothing}
                  @click=${function (ev) {
                    ev.stopPropagation();
                    if (locked || o.disabled) return;
                    patch({ openChoiceId: null, choiceSearchQuery: "" });
                    opts.onSelect(o.value);
                  }}
                >
                  ${resolveOptionLabel(o)}
                </button>
              `;
            })
          : html`<p class="choice-empty hint">
              ${t("common.no_results", "No matches")}
            </p>`}
      </div>
      ${hasPin && !match ? pinChip(lookupOptionLabel(list, pinnedVal)) : nothing}
    </div>
  `;
}

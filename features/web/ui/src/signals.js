/**
 * App-facing signals API (TC39 signals via signal-polyfill, re-exported by @lit-labs/signals).
 * Elements extending OaaElement re-render when a signal they read changes; effect() is for
 * side effects outside components.
 */
import { Signal, signal, computed } from "@lit-labs/signals";

export { Signal, signal, computed };

/**
 * Run `callback` when any signals it reads change; returns a dispose function.
 * The flush runs in a microtask after the Watcher notify.
 * @param {() => (void | (() => void))} callback
 */
export function effect(callback) {
  /** @type {void | (() => void)} */
  let cleanup;
  let disposed = false;
  let pending = false;
  function runCleanup() {
    if (typeof cleanup === "function") {
      try {
        cleanup();
      } catch (e) {}
    }
    cleanup = undefined;
  }
  const watcher = new Signal.subtle.Watcher(function () {
    // Watcher notify must not read/write signals — only schedule work.
    if (pending || disposed) return;
    pending = true;
    queueMicrotask(function () {
      pending = false;
      if (disposed) return;
      for (const s of watcher.getPending()) {
        try {
          s.get();
        } catch (e) {}
      }
      try {
        watcher.watch();
      } catch (e) {}
    });
  });
  const run = new Signal.Computed(function () {
    runCleanup();
    cleanup = callback();
  });
  watcher.watch(run);
  try {
    run.get();
  } catch (e) {}
  return function dispose() {
    if (disposed) return;
    disposed = true;
    try {
      watcher.unwatch(run);
    } catch (e) {}
    runCleanup();
  };
}

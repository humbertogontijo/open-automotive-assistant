import { LitElement } from "lit";
import { SignalWatcher } from "@lit-labs/signals";

/**
 * Base for every app element. Renders into light DOM so the global theme and layout CSS
 * apply unchanged (Web Awesome components keep their own shadow DOM), and re-renders when
 * any store signal read during render changes.
 *
 * Reactive properties go in `static properties` and are initialised in the constructor,
 * never as class fields (esbuild's class-field lowering would shadow the accessors).
 */
export class OaaElement extends SignalWatcher(LitElement) {
  createRenderRoot() {
    return this;
  }
}

// Relays native navigation requests to the SPA. The page acknowledges by calling
// preventDefault() on the cancelable `oaa:goto` event; unhandled requests make the
// shell fall back to a full load of the section URL.
const port = browser.runtime.connectNative("oaa");

port.onMessage.addListener((msg) => {
  if (!msg || typeof msg.goto !== "string") return;
  const ev = new window.CustomEvent("oaa:goto", { detail: msg.goto, cancelable: true });
  const handled = !window.dispatchEvent(ev);
  port.postMessage({ id: msg.id, handled });
});

// The page lost its head unit session (401); the shell reloads it with the local key.
window.addEventListener("oaa:reauth", () => {
  port.postMessage({ reauth: true, path: window.location.pathname });
});

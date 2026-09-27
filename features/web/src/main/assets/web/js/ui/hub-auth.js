import { html, render, nothing } from "../lit.js";
import { state, patch, notify } from "../store.js";
import { t } from "../i18n.js";
import { api, errText, setUnauthorizedHandler } from "../api.js";

let authBootstrapped = false;
/** @type {(() => void)|null} */
let onAuthenticated = null;

/** Called once the user signs in or creates the admin (app.js reloads data). */
export function onHubAuthenticated(fn) {
  onAuthenticated = fn;
}

// A hub session that expires mid-use turns API calls into 401s: show the login again.
setUnauthorizedHandler(function () {
  const a = state.hubAuth;
  if (!a || !a.authenticated || a.role === "local") return;
  patch({
    hubAuth: Object.assign({}, a, { authenticated: false }),
    hubAuthMsg: t("auth.expired", "Session expired — sign in again"),
  });
  notify();
});

export async function ensureHubAuth() {
  if (authBootstrapped) return state.hubAuth || null;
  authBootstrapped = true;
  let st = null;
  try {
    const r = await fetch("/api/auth/status", { credentials: "same-origin" });
    if (r.ok) st = await r.json();
  } catch (e) {}
  // Network error: fall back to the car's local UI, which has no login.
  patch({ hubAuth: st || { role: "local", authenticated: true, setupRequired: false } });
  return state.hubAuth;
}

export function shouldShowHubLogin() {
  const a = state.hubAuth;
  if (!a) return false;
  if (a.addon && a.authenticated) return false;
  if (a.setupRequired) return false;
  return !a.authenticated;
}

export function shouldShowHubSetup() {
  const a = state.hubAuth;
  return !!(a && a.setupRequired);
}

export function hubAuthOverlayTemplate() {
  if (shouldShowHubSetup()) return hubSetupTemplate();
  if (shouldShowHubLogin()) return hubLoginTemplate();
  return nothing;
}

/** POST the form to [path]; on success mark the session authenticated and reload. */
async function submitAuth(ev, path, fallbackError) {
  ev.preventDefault();
  const form = ev.currentTarget;
  const body = {};
  new FormData(form).forEach(function (v, k) {
    body[k] = String(v);
  });
  if ("displayName" in body && !body.displayName) body.displayName = body.username;
  try {
    const res = await api(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!res || !res.ok) {
      patch({ hubAuthMsg: (res && res.error) || fallbackError });
      notify();
      return;
    }
    patch({
      hubAuth: Object.assign({}, state.hubAuth, { setupRequired: false, authenticated: true, user: res.user }),
      hubAuthMsg: "",
    });
    notify();
    if (onAuthenticated) onAuthenticated();
  } catch (e) {
    patch({ hubAuthMsg: errText(e) });
    notify();
  }
}

function hubSetupTemplate() {
  return html`
    <div class="setup-overlay" id="hubAuthOverlay">
      <form
        class="setup-panel"
        @submit=${function (ev) {
          return submitAuth(ev, "/api/auth/setup", t("auth.setup_failed", "Setup failed"));
        }}
      >
        <h1>${t("auth.setup_title", "Create hub admin")}</h1>
        <p class="sub">
          ${t(
            "auth.setup_sub",
            "First-run setup. There is no password recovery — wipe the auth store or rebuild to reset.",
          )}
        </p>
        <label>${t("auth.username", "Username")}
          <input name="username" type="text" autocomplete="username" autocapitalize="none" required />
        </label>
        <label>${t("auth.password", "Password")}
          <input name="password" type="password" autocomplete="new-password" required />
        </label>
        <label>${t("auth.display_name", "Display name")}
          <input name="displayName" type="text" autocomplete="nickname" />
        </label>
        <p class="sub auth-msg" id="hubAuthMsg">${state.hubAuthMsg || ""}</p>
        <button type="submit" class="btn primary">${t("auth.create_admin", "Create admin")}</button>
      </form>
    </div>
  `;
}

function hubLoginTemplate() {
  const providers = (state.hubAuth && state.hubAuth.providers) || { local: true };
  return html`
    <div class="setup-overlay" id="hubAuthOverlay">
      <form
        class="setup-panel"
        @submit=${function (ev) {
          return submitAuth(ev, "/api/auth/login", t("auth.login_failed", "Sign-in failed"));
        }}
      >
        <h1>${t("auth.login_title", "Sign in")}</h1>
        <p class="sub">
          ${t("auth.login_sub", "Hub access requires a session. Entity control is never anonymous.")}
        </p>
        <label>${t("auth.username", "Username")}
          <input name="username" type="text" autocomplete="username" autocapitalize="none" required />
        </label>
        <label>${t("auth.password", "Password")}
          <input name="password" type="password" autocomplete="current-password" required />
        </label>
        <p class="sub auth-msg" id="hubAuthMsg">${state.hubAuthMsg || ""}</p>
        <button type="submit" class="btn primary">${t("auth.sign_in", "Sign in")}</button>
        ${providers.homeassistant
          ? html`<p style="margin-top:16px">
              <a class="btn" href="/api/auth/authorize?provider_id=homeassistant"
                >${t("auth.sign_in_ha", "Sign in with Home Assistant")}</a
              >
            </p>`
          : nothing}
      </form>
    </div>
  `;
}

export function renderHubAuthOverlay() {
  const host = document.getElementById("setupHost") || document.body;
  let el = document.getElementById("hubAuthRoot");
  if (!el) {
    el = document.createElement("div");
    el.id = "hubAuthRoot";
    host.appendChild(el);
  }
  render(hubAuthOverlayTemplate(), el);
}

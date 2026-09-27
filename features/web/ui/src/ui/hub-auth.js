import { html, nothing } from "lit";
import { session } from "../store.js";
import { OaaElement } from "../lit/oaa-element.js";
import { t } from "../i18n.js";
import { errText, setUnauthorizedHandler, postJson } from "../api.js";

let authBootstrapped = false;
/** @type {(() => void)|null} */
let onAuthenticated = null;

/** Called once the user signs in or creates the admin (boot reloads data). */
export function onHubAuthenticated(fn) {
  onAuthenticated = fn;
}

/** Ask the head unit shell to reload with its local key (the session cookie is gone). */
export function requestHeadUnitReauth() {
  window.dispatchEvent(new CustomEvent("oaa:reauth"));
}

// An expired hub session or a revoked car pairing turns API calls into 401s.
setUnauthorizedHandler(function () {
  const a = session.hubAuth;
  if (!a || !a.authenticated) return;
  if (a.role === "local") {
    if (a.headUnit || a.loopback) {
      requestHeadUnitReauth();
      return;
    }
    session.$patch({
      hubAuth: Object.assign({}, a, { authenticated: false }),
      hubAuthMsg: t("pair.revoked", "This device is no longer paired with the car"),
    });
    return;
  }
  session.$patch({
    hubAuth: Object.assign({}, a, { authenticated: false }),
    hubAuthMsg: t("auth.expired", "Session expired — sign in again"),
  });
});

export async function ensureHubAuth() {
  if (authBootstrapped) return session.hubAuth || null;
  authBootstrapped = true;
  let st = null;
  try {
    const r = await fetch("/api/auth/status", { credentials: "same-origin" });
    if (r.ok) st = await r.json();
  } catch (e) {}
  // Network error: assume the car UI and let the API calls decide.
  session.hubAuth = st || { role: "local", authenticated: true, setupRequired: false };
  return session.hubAuth;
}

export function shouldShowHubLogin() {
  const a = session.hubAuth;
  if (!a || a.role === "local") return false;
  if (a.addon && a.authenticated) return false;
  if (a.setupRequired) return false;
  return !a.authenticated;
}

/** A browser the car does not know yet: pair with a code shown on the head unit. */
export function shouldShowCarPair() {
  const a = session.hubAuth;
  return !!(a && a.role === "local" && !a.authenticated && !a.loopback);
}

/** The head unit page loaded without its session (e.g. the app restarted under it). */
export function headUnitNeedsReauth() {
  const a = session.hubAuth;
  return !!(a && a.role === "local" && !a.authenticated && a.loopback);
}

/** Mark this browser paired with the car and reload the app data. */
export function carPaired() {
  session.$patch({
    hubAuth: Object.assign({}, session.hubAuth, { authenticated: true, kind: "browser" }),
    hubAuthMsg: "",
  });
  if (onAuthenticated) onAuthenticated();
}

export function shouldShowHubSetup() {
  const a = session.hubAuth;
  return !!(a && a.setupRequired);
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
    const res = await postJson(path, body);
    if (!res || !res.ok) {
      session.hubAuthMsg = (res && res.error) || fallbackError;
      return;
    }
    session.$patch({
      hubAuth: Object.assign({}, session.hubAuth, { setupRequired: false, authenticated: true, user: res.user }),
      hubAuthMsg: "",
    });
    if (onAuthenticated) onAuthenticated();
  } catch (e) {
    session.hubAuthMsg = errText(e);
  }
}

function authMessage() {
  const msg = session.hubAuthMsg;
  return msg ? html`<wa-callout variant="danger" size="small" class="auth-msg">${msg}</wa-callout>` : nothing;
}

function usernameField() {
  return html`<wa-input
    name="username"
    label=${t("auth.username", "Username")}
    autocomplete="username"
    autocapitalize="off"
    required
  ></wa-input>`;
}

function hubSetupForm() {
  return html`<form
    class="auth-form"
    @submit=${(ev) => submitAuth(ev, "/api/auth/setup", t("auth.setup_failed", "Setup failed"))}
  >
    <p class="sub">
      ${t("auth.setup_sub", "First-run setup. There is no password recovery — wipe the auth store or rebuild to reset.")}
    </p>
    ${usernameField()}
    <wa-input
      name="password"
      type="password"
      label=${t("auth.password", "Password")}
      autocomplete="new-password"
      password-toggle
      required
    ></wa-input>
    <wa-input name="displayName" label=${t("auth.display_name", "Display name")} autocomplete="nickname"></wa-input>
    ${authMessage()}
    <wa-button type="submit" variant="brand">${t("auth.create_admin", "Create admin")}</wa-button>
  </form>`;
}

function hubLoginForm() {
  const providers = (session.hubAuth && session.hubAuth.providers) || { local: true };
  return html`<form
    class="auth-form"
    @submit=${(ev) => submitAuth(ev, "/api/auth/login", t("auth.login_failed", "Sign-in failed"))}
  >
    <p class="sub">${t("auth.login_sub", "Hub access requires a session. Entity control is never anonymous.")}</p>
    ${usernameField()}
    <wa-input
      name="password"
      type="password"
      label=${t("auth.password", "Password")}
      autocomplete="current-password"
      password-toggle
      required
    ></wa-input>
    ${authMessage()}
    <wa-button type="submit" variant="brand">${t("auth.sign_in", "Sign in")}</wa-button>
    ${providers.homeassistant
      ? html`<wa-button appearance="outlined" href="/api/auth/authorize?provider_id=homeassistant"
          >${t("auth.sign_in_ha", "Sign in with Home Assistant")}</wa-button
        >`
      : nothing}
  </form>`;
}

/** Hub admin creation / sign-in; stays open until the session is authenticated. */
export class OaaHubAuth extends OaaElement {
  render() {
    const setup = shouldShowHubSetup();
    const login = !setup && shouldShowHubLogin();
    return html`<wa-dialog
      class="oaa-dialog auth-dialog"
      label=${setup ? t("auth.setup_title", "Create hub admin") : t("auth.login_title", "Sign in")}
      without-header
      ?open=${setup || login}
      @wa-hide=${(ev) => {
        if (ev.target === ev.currentTarget && (shouldShowHubSetup() || shouldShowHubLogin())) ev.preventDefault();
      }}
    >
      ${setup
        ? html`<h1>${t("auth.setup_title", "Create hub admin")}</h1>${hubSetupForm()}`
        : login
          ? html`<h1>${t("auth.login_title", "Sign in")}</h1>${hubLoginForm()}`
          : nothing}
    </wa-dialog>`;
  }
}
customElements.define("oaa-hub-auth", OaaHubAuth);

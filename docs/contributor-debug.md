# Contributor remote debug

Anyone on the car LAN can inspect app state, pull logs, and attach Android Studio without reverse-engineering the HU alone.

## 0. adb-free loop through a hub

Once the car is paired with a [hub](hub.md), day-to-day development needs no adb:

```bash
export OAA_HUB_URL=http://hub.local:8787
export OAA_HUB_USER=admin            # or OAA_HUB_TOKEN=<admin session / system token>
./tools/oaa-setup hub-deploy         # build → sign → upload → OTA rollout → wait
./tools/oaa-setup hub-deploy --node node-abc123 --no-build
./tools/oaa-setup hub-deploy --all --no-wait
```

The password is prompted once (or read from `OAA_HUB_PASSWORD`); the session token is cached in `~/.config/oaa/hub-token` (mode 600). With exactly one paired car, `--node` is optional.

Both loops send only what changed. `hub-deploy` offers the car a delta patch when its installed build is still among the hub's last 5 artifacts (see [hub.md](hub.md#app-updates-ota)). Over adb, `./tools/oaa-setup -H CAR_IP setup` (or `install`) keeps each installed APK in `~/.cache/oaa/apks`. On the next run it builds a patch against the car's installed build with `:apk-delta:diff`, pushes the patch, rebuilds the APK on the HU with `dd`, checks its hash and then runs `pm install`. `--full` (or `OAA_FULL=1`) forces a whole-APK push. Head-unit builds also skip the emulator's x86_64 libraries (`-Poaa.arm64Only`).

Then open the hub UI, pick the car in Fleet and use **Lab**: re-probe, OBD2, **Export zip** and **Live logs** all go through the hub (admin only). adb is still needed for the very first install and for attaching a debugger.

## 1. Wireless ADB

1. On the HU: enable Developer options → Wireless debugging (or an existing Wi‑Fi ADB setup).
2. From your laptop:

```bash
adb connect CAR_IP:5566
adb devices
```

3. Android Studio → **Attach Debugger to Android Process** → package `cc.opencar.assistant` (or `.debug` / `.contributor` suffix).

Debug and contributor build types ship with `android:debuggable=true`. Release stays non-debuggable.

App debug is app-level and works with package-install rights only.

### Inspecting the in-car UI

The app renders the UI with an embedded GeckoView (Firefox engine), not the system WebView, so `chrome://inspect` does not see it. Debug and contributor builds enable Gecko remote debugging:

1. Keep the adb connection from section 1 open.
2. In desktop Firefox open `about:debugging` → **Setup** → enable USB devices, then pick the car under **USB** (adb over Wi‑Fi shows up there too).
3. Choose the `127.0.0.1:8787` tab → **Inspect** for the console, DOM and network panels.

The same UI is usually easier to iterate on through the hub in a desktop browser (`./gradlew :server:runHub`).

## 2. Contributor mode (Lab)

Open the product UI → **Lab** tab (`/lab`).

1. Toggle **Contributor mode** on. On `userdebug` / test-keys HUs this defaults on; on stock `user` builds you must enable it here.
2. The short LAN **token** appears next to the toggle (also on `/debug` HTML and `GET /api/lab`).
3. Contributor mode unlocks `/debug` writes and sensitive reads behind that token.

APIs:

| Method | Path | Body | Notes |
|--------|------|------|-------|
| GET | `/api/lab` | — | contributor, token, integrationOverride, integrations[] |
| POST | `/api/lab/contributor` | `enabled=1` or `0` | form-urlencoded |
| POST | `/api/lab/integration-override` | `id=<platform>` or empty | form-urlencoded; **restart required** |

### Integration override

Lab → **Integration override** forces a specific `integrations/<id>/` match (e.g. test `ihu629g` UI on an Antora HU). Apply, then **force-stop** the app or reboot so `AssistantRuntime` rematches.

adb fallback after Lab Apply (force rematch):

```bash
# Adjust package suffix for debug / contributor builds
adb shell am force-stop cc.opencar.assistant.debug
adb shell monkey -p cc.opencar.assistant.debug -c android.intent.category.LAUNCHER 1
```

Prefs key: `oaa_runtime` / `integration_override` (set via Lab or `POST /api/lab/integration-override`).

## 3. HTTP `/debug` API

Base: `http://CAR_IP:8787`. Every route needs a paired caller ([adr/0004-car-auth.md](adr/0004-car-auth.md)): pair the browser with the code the car shows, or run `./tools/oaa-setup -H CAR_IP pair` and send `Authorization: Bearer <token>` from scripts. The contributor `token=` is checked on top of that.

| Route | Notes |
|-------|--------|
| `GET /debug` | HTML status: platform links, token hint |
| `GET /api/lab` | Lab snapshot (preferred for UI) |
| `GET /debug/logs?token=…` | Ring-buffer app logs |
| `WS /debug/logs/stream?token=…` | Live log stream (Lab → **Live logs**) |
| `GET /debug/integration` | Matched integration, variant, catalog size |
| `GET /debug/props?token=…&q=` | Safe catalog browse |
| `GET /debug/props/{key}?token=…` | Read one property (VIN redacted) |
| `POST /debug/props/{key}?token=…` | Write if allowlisted + contributor |
| `GET /debug/export?token=…` | Zip: logs, identity, capabilities, telemetry |
| `GET /debug/adb-hint` | Wi‑Fi IP + suggested `adb connect` |

Through a hub, use the same paths on the hub URL with `?node=ID` (or the `oaa_node` cookie set by Fleet); responses over 2.5 MiB return 413.

Token is required when Contributor mode is on. Without Contributor mode, write/debug routes refuse (probe summary may still run for Lab re-probe when mode is off).

### VHAL catalog vs product entities

Lab → **VHAL catalog** lists every property from `platform.json` → `properties` (after `extends` merge). Each row may show an **Entity** id when that VHAL key is product-bound (identity: entity id = property key) or claimed by a composite attribute.

| Filter | Meaning |
|--------|---------|
| **All** | Full HU / platform catalog |
| **Bound (cards)** | Props with a product entity (curated or auto) |
| **Missing** | On the car / in `properties`, but not yet claimed as a card |

**Product entities** (cards): curated composites stay HA-shaped (`climate.cabin`); atomics use the **property key** as entity id. Titles/hints live in i18n (`control.<PROPERTY_KEY>`). Pages use `group`; subsections use `section` (not HA domain). See [ADR-0002](adr/0002-model-variant-bindings.md).

Probe summary includes `boundEntities` / `unbound` counts. Re-probe after updating `platform.json` (`force=1`).

## 4. Build types

| Type | Debuggable | Debug routes |
|------|------------|--------------|
| `debug` | yes | contributor default-on for userdebug |
| `contributor` | yes (release-signed style) | on — for sharing test APKs |
| `release` | no | off unless Contributor mode + token |

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleContributor
./gradlew :app:assembleRelease
```

## 5. Filing a bug

1. Enable Contributor mode in Lab, note the token.
2. Open `http://CAR_IP:8787/debug/export?token=TOKEN` (or use **Export zip** in Lab).
3. Attach the zip to a GitHub issue (VIN already redacted).
4. Include platform id + variant from `/debug/integration` or About.

## 6. Adding a platform integration

See [adding-an-integration.md](adding-an-integration.md). Prefer **ihu629g** as the simple CarProperty-only reference.

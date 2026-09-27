# Open Automotive Assistant — Architecture

Open Automotive Assistant is a head-unit app where the **vehicle platform is a plugin** (`VehicleIntegration`). The shell owns UI, web, install, DVR, debug, and **external bridge plugins**. Integrations are keyed by **chip/HU family** (not marketing badge).

North-star decisions: [adr/0001-architecture-north-star.md](adr/0001-architecture-north-star.md). Doc map: [README.md](README.md).

## Modules

| Module | Path | Role |
|--------|------|------|
| `:app` | `app/` | GeckoView host (embedded engine, not the system WebView) → `http://127.0.0.1:8787`, `AssistantRuntime`, FGS, boot |
| `:integration-api` | `libs/api/` | SPI, entity contract, `EntityPackLoader`, ServiceLoader registries |
| `:oaa-support` | `libs/oaa-support/` | i18n packs, `LastKnownStore` |
| `:car-stubs` | `libs/car-stubs/` | Compile-only `android.car` (never packaged) |
| `:signing` | `libs/signing/` | Community testkey + re-sign CLI |
| `:oaartc` | `libs/oaartc/` | Car-side WebRTC (Pion via gomobile) for the hub media plane |
| `:integrations:platform:aaos` | `integrations/platform/aaos/` | AAOS plumbing + parent `platform/aaos/platform.json` |
| `:integrations:platform:flyme` | `integrations/platform/flyme/` | Flyme/ECARX family helpers only |
| `:integrations:demo` | `integrations/demo/` | In-memory fake vehicle (CI / no HU) |
| `:integrations:antora1000` | `integrations/antora1000/` | Antora/SE1000 — gRPC + CarProperty fallback |
| `:integrations:ihu629g` | `integrations/ihu629g/` | IHU629G — CarProperty only (thin reference) |
| `:feature-web` | `features/web/` | Ktor car server + `/debug`; packages the `:webui` bundle |
| `:webui` | `features/web/ui/` | Product UI (Lit + Web Awesome, npm/esbuild), shared by car and hub |
| `:feature-*` | `features/<id>/` | memory, install, DVR, debug, history, shortcuts |
| `:plugin-homeassistant` | `plugins/homeassistant/` | Inbound HA bridge (car → HA) |
| `:server` | `server/` | Self-hosted **hub** (JVM/Ktor, dual faces 8787/8788, multi-car) |
| `:protocol` | `libs/protocol/` | Shared wire constants (headers, paths, event types) |

HU and phone share the same web UI. Native code owns VHAL, cameras, install, and the HTTP server.

## Integration SPI

```kotlin
interface VehicleIntegration {
  val id: String
  fun matches(device: DeviceFingerprint): Boolean
  fun detectVariant(session: VehicleSession): PlatformVariant
  fun capabilities(variant: PlatformVariant): Set<Capability>
  suspend fun connect(context: Context): VehicleSession
}
```

Features never hardcode VHAL hex. They use `EntityRegistry.property(key)` / binding keys; `platform.json` maps those to native ids. Parent families: `"extends": ["aaos"]` → `platform/aaos/platform.json`.

### Poll vs push

`VehiclePropertyBackend.observe()` is optional: push when the transport can stream; otherwise the session polls (~1s). The web UI is event-driven via `/api/events` either way.

## Plugin SPI

External bridges implement `OaaPlugin`. See [plugins.md](plugins.md).

## Discovery

- **Integrations / plugins** — folder-auto + ServiceLoader (`integrations/<id>/`, `plugins/<id>/`).
- **Features** — curated list in `settings.gradle.kts` + `AssistantRuntime`.
- `:app` / `:feature-*` depend on `:integration-api` only — never concrete integration classes.

## Runtime flow

1. Match `VehicleIntegration` (Lab can override; `demo` for fake vehicle).
2. `connect` → `VehicleSession` + variant + capabilities.
3. Start feature controllers when capabilities allow.
4. Start plugins; wire shortcut contributions.
5. Bind Ktor on `0.0.0.0:8787`.
6. Foreground service watchdog (reapply / web / DVR).

## Shipping platforms (summary)

| Id | Transport | Notes |
|----|-----------|--------|
| `demo` | In-memory | CI / laptop; Lab override or fingerprint `demo` |
| `ihu629g` | CarProperty | Thin hardware reference for new SoCs |
| `antora1000` | Venus gRPC → CarProperty fallback | Large `platform.json`; SKU `p145_eu` + profiles phev/bev |

How-to: [adding-an-integration.md](adding-an-integration.md). Antora HVAC/cover tables: [composites.md](composites.md).

## Entity contract

Portable product surface: HA-shaped `domain.object_id`. See [`EntityContract`](../libs/api/src/main/java/cc/opencar/assistant/api/EntityContract.kt).

| Concept | Role |
|---------|------|
| Entity id | Stable catalog id (`climate.cabin`, `sensor.soc`, …) |
| Domain | [`EntityType`](../libs/api/src/main/java/cc/opencar/assistant/api/EntityType.kt) — card family |
| Binding | SKU allowlist in `models/<sku>.json` → identity bind (VHAL key = entity id); profile is detect/capabilities only |
| Availability | Unbound = omitted (like HA) |
| Declarative pack | `entities/standard-pilot.json` merged via [`EntityPackLoader`](../libs/api/src/main/java/cc/opencar/assistant/api/EntityPackLoader.kt) |

**Rules:** automations/UI use registry ids only; one catalog (no per-make forks); scenes tolerate missing targets. Taxonomy: [domains.md](domains.md). Wire format: [openapi/open-automotive-assistant-v1.yaml](openapi/open-automotive-assistant-v1.yaml).

Outbound MQTT/HA discovery is deferred for the HU. **Self-hosted hub** (Docker / HAOS app) and a HACS custom component expose car entities into Home Assistant — see [hub.md](hub.md), and [adr/0003-hub.md](adr/0003-hub.md). Remote camera media (live, playback, download) uses a WebRTC media plane next to the JSON control plane — see [webrtc.md](webrtc.md). Inbound HA remains `:plugin-homeassistant` on the car.

## Web UI

`:webui` (`features/web/ui/`) is one npm project built by esbuild through Gradle (node is downloaded by the build, no global install needed). Output lands in `build/dist/web/`; `:feature-web` packs it into the APK assets and `:server` into the hub jar.

- **Stack:** Lit custom elements rendering into light DOM (`OaaElement` = `SignalWatcher(LitElement)`), Web Awesome 3 controls (`src/wa.js` registers only the components in use), `@lit-labs/router`, and signal store slices in `src/store.js` (`session`, `catalog`, `prefs`, `camera`, `shortcuts`, …). Each page is an `<oaa-page-*>` element; heavy pages (cameras, history, shortcuts, lab, store) are lazy chunks. Toasts and `confirmDialog` replace `alert()` / `confirm()`.
- **Themes:** `css/palette-ha.css` + `css/themes.css` set flat Home Assistant–style Web Awesome tokens per `data-theme` (dark, light, contrast). Short names such as `--accent` and `--surface` are aliases of those tokens.
- **Types and checks:** JSDoc with `checkJs`, API types generated from the OpenAPI spec (`npm run gen:api`), eslint and `node --test`. `npm run check` runs all three; `./gradlew :webui:checkWeb` runs them in CI.
- **Payload:** minified, code-split bundles with hashed names under `assets/`, one CSS entry, and `.br` / `.gz` variants. `OaaStatic` serves hashed assets `immutable` and everything else (the SPA shell) `no-store`, picking the precompressed variant from `Accept-Encoding`. `npm run size` prints the size report.
- **Runtime:** bootstrap via HTTP; live via WebSocket `telemetry` / `entity` / `catalog`. i18n is client-side (`GET /api/i18n`). Cards by domain; nav `group` is the section id (`home`, `controls`, …).

### Developing the UI without a car

Run the hub against the live build output with a demo car attached:

```bash
cd features/web/ui && npm run dev        # esbuild watch → build/dist/web
OAA_WEB_DIR=$PWD/features/web/ui/build/dist/web OAA_DEMO_NODE=1 ./gradlew :server:runHub
```

`OAA_WEB_DIR` makes the hub serve files from disk instead of the jar, so a browser reload picks up each rebuild. `OAA_DEMO_NODE=1` attaches an in-process demo car (`DemoNodeTransport`) that serves the demo entity catalog plus a fake DVR timeline, entity history, and in-memory shortcuts / routines / scenes, so every page renders without a head unit.

## Feature highlights (pointers)

| Area | Where |
|------|--------|
| Settings memory (pin / reapply) | `:feature-memory` |
| Shortcuts / scenes / routines | [shortcuts.md](shortcuts.md) |
| DVR / cameras | `:feature-dvr`; roles from `platform.json` → `cameras[]` |
| Writable allowlist / LAN threat model | [safety.md](safety.md) |

## Extending

| Goal | Doc |
|------|-----|
| New SoC/HU | [adding-an-integration.md](adding-an-integration.md) |
| External bridge | [plugins.md](plugins.md) |
| Shell feature | [adding-a-feature.md](adding-a-feature.md) |

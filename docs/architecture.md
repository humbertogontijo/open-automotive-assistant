# Open Automotive Assistant — Architecture

Open Automotive Assistant is a head-unit app where the **vehicle platform is a plugin** (`VehicleIntegration`). The shell owns UI, web, install, DVR, debug, and **external bridge plugins**. Integrations are keyed by **chip/HU family** (not marketing badge).

North-star decisions: [adr/0001-architecture-north-star.md](adr/0001-architecture-north-star.md). Doc map: [README.md](README.md).

## Modules

| Module | Path | Role |
|--------|------|------|
| `:app` | `app/` | WebView host → `http://127.0.0.1:8787`, `AssistantRuntime`, FGS, boot |
| `:integration-api` | `libs/api/` | SPI, entity contract, `EntityPackLoader`, ServiceLoader registries |
| `:oaa-support` | `libs/oaa-support/` | i18n packs, `LastKnownStore` |
| `:car-stubs` | `libs/car-stubs/` | Compile-only `android.car` (never packaged) |
| `:signing` | `libs/signing/` | Community testkey + re-sign CLI |
| `:integrations:platform:aaos` | `integrations/platform/aaos/` | AAOS plumbing + parent `platform/aaos/platform.json` |
| `:integrations:platform:flyme` | `integrations/platform/flyme/` | Flyme/ECARX family helpers only |
| `:integrations:demo` | `integrations/demo/` | In-memory fake vehicle (CI / no HU) |
| `:integrations:antora1000` | `integrations/antora1000/` | Antora/SE1000 — gRPC + CarProperty fallback |
| `:integrations:ihu629g` | `integrations/ihu629g/` | IHU629G — CarProperty only (thin reference) |
| `:feature-web` | `features/web/` | Product UI (lit-html) + Ktor + `/debug` |
| `:feature-*` | `features/<id>/` | memory, telemetry, install, DVR, debug, history, shortcuts |
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

## Web UI (sketch)

`:feature-web` — vendored lit-html, reactive `store.js`, pages under `web/js/pages/`. Bootstrap via HTTP; live via WebSocket `telemetry` / `entity` / `catalog`. i18n client-side (`GET /api/i18n`). Assets `Cache-Control: no-store`. Cards by domain; nav `group` is section id (`home`, `controls`, …).

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

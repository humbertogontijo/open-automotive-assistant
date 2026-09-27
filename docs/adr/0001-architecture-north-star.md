# ADR-0001: Architecture north star

## Status

Accepted

Doc index: [../README.md](../README.md).

## Context

Open Automotive Assistant targets Android Automotive head units. Contributors need a clear split between product core, vehicle platforms, and UI so the project can grow like Home Assistant without rewriting the HU runtime in a server language.

## Decision

1. **Monorepo** (`open-automotive-assistant`) for at least the next 18–24 months. Split repos only after the protocol is versioned and integrations are numerous.
2. **Kotlin + coroutines + Ktor on the HU.** Cars need AAOS APIs (`CarPropertyManager`, Camera2, Audio). Do not move core to Python/Node on-device.
3. **Protocol-first.** The in-process REST + WebSocket API is the product contract. Clients (HU GeckoView, phone browser, HA bridge, future MQTT) consume that contract; UI is not the source of truth.
4. **Platform families under `integrations/platform/`:**
   - **`aaos`** — shared AAOS plumbing (`PlatformConfig`, CarProperty stack) and parent `platform/aaos/platform.json` (AOSP VHAL stubs + HU settings transport). Integrations use `"extends": ["aaos"]`.
   - **`flyme`** — ECARX/Geely family helpers only; depends on aaos.
   - SoC modules (`antora1000`, `ihu629g`, …) stay folder-discovered vehicle integrations.
5. **Entity model direction.** Core defines standard car entities; integrations bind via `platform.json` (and optional overlays). Features reference registry entity ids only — never VHAL hex.
6. **Features stay curated; integrations/plugins stay plug-and-play** (ServiceLoader).

## Consequences

- New AAOS cars start from `:integrations:platform:aaos` + `"extends": ["aaos"]`.
- Parent asset path is `platform/<family>/platform.json` so it never collides with a SoC’s root `assets/platform.json`.
- ADRs in `docs/adr/` record further protocol, entity-schema, and safety decisions.
- Protocol stub: [`docs/openapi/open-automotive-assistant-v1.yaml`](../openapi/open-automotive-assistant-v1.yaml).
- CI fake vehicle: `integrations/demo` (Lab override or fingerprint `demo`).
- Declarative entity pilot: `entities/standard-pilot.json` via `EntityPackLoader`.

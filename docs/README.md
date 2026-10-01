# Docs index

Start here. Prefer the how-to for your task; use architecture only for the module map.

## By audience

| You want to… | Read |
|--------------|------|
| Build / install / contribute | [../README.md](../README.md), [../CONTRIBUTING.md](../CONTRIBUTING.md) |
| Understand modules & SPI | [architecture.md](architecture.md), [adr/0001-architecture-north-star.md](adr/0001-architecture-north-star.md) |
| Hub / multi-car / self-host | [adr/0003-hub.md](adr/0003-hub.md), [hub.md](hub.md), [webrtc.md](webrtc.md) |
| Car access control / pairing | [adr/0004-car-auth.md](adr/0004-car-auth.md) |
| Add a vehicle platform | [adding-an-integration.md](adding-an-integration.md) — start from **`demo`** or **`ihu629g`** |
| Add an external bridge | [plugins.md](plugins.md) |
| Add a shell feature | [adding-a-feature.md](adding-a-feature.md) |
| Debug on a head unit | [contributor-debug.md](contributor-debug.md) |
| Know entity domains / taxonomy | [domains.md](domains.md) |
| Antora binding notes (HVAC zones, Wave) | [composites.md](composites.md) |
| Automations (flows / scenes / routines) | [shortcuts.md](shortcuts.md) |
| HTTP / WebSocket contract | [openapi/open-automotive-assistant-v1.yaml](openapi/open-automotive-assistant-v1.yaml) |
| Threat model / install safety | [safety.md](safety.md), [disclaimer.md](disclaimer.md), [webrtc.md](webrtc.md) (media plane) |
## What belongs where

| Doc | Owns | Does **not** own |
|-----|------|------------------|
| `architecture.md` | Module graph, SPI, discovery, runtime sketch | Per-platform VHAL tables, UI token essays |
| `domains.md` | Portable product domains (AAOS + Ultra cross-check) | Antora Wave / area bitmasks |
| `composites.md` | Antora-specific composite/cover bindings | Generic domain inventory |
| `adding-an-integration.md` | How to ship `integrations/<id>/` | Entity taxonomy design |
| ADR / OpenAPI | Decisions and wire contract | Tutorials |

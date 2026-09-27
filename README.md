# Open Automotive Assistant

Local-first, capability-based companion for **Android Automotive** head units. Vehicle platforms are plugins; the product surface is HA-shaped entities (`climate.cabin`, `sensor.soc`, …).

**Canonical repository name:** `open-automotive-assistant`  
Web UI: `http://127.0.0.1:8787` on the HU, or `http://CAR_IP:8787` on the LAN after pairing with a code shown on the car ([ADR-0004](docs/adr/0004-car-auth.md))  

Self-hosted **hub** (multi-car, Docker / HAOS): [docs/hub.md](docs/hub.md) · `deploy/docker` · `deploy/homeassistant`

Short name: **OAA**. Host tool: `./tools/oaa-setup` (env `OAA_*`). Android package id remains `cc.opencar.assistant` for install continuity.

First shipping platforms: Antora 1000 / SE1000 and IHU629G (Flyme Auto family), plus **`demo`** for CI without a head unit. More SoCs welcome via [docs/adding-an-integration.md](docs/adding-an-integration.md).

License: [Apache-2.0](LICENSE) · [NOTICE](NOTICE) (community testkey) · [Disclaimer](docs/disclaimer.md) · [Code of Conduct](CODE_OF_CONDUCT.md) · [Security](SECURITY.md)

## Build without a head unit

Requires **JDK 17** and an **Android SDK** with platform 36 and the NDK from `oaa.ndkVersion` in `gradle.properties` (`ANDROID_HOME`, or `sdk.dir` in `local.properties` — never commit that file). Gradle fetches Go and Node.js itself.

```bash
./gradlew :app:assembleDebug
./gradlew check   # unit tests, lint and web checks, as in CI
```

Compiles against `libs/car-stubs`. Use Lab → integration override **`demo`**, or match fingerprint `demo`, for an in-memory vehicle. Live VHAL needs real AAOS hardware.

## Install on a head unit (optional)

Owned userdebug HU on the same LAN:

```bash
# Antora / SE1000 — VenusVehicleServer gRPC
./tools/oaa-setup -i antora1000 -H CAR_IP setup

# IHU629G — CarPropertyManager (simple hardware reference)
./tools/oaa-setup -i ihu629g -H CAR_IP setup
```

Builds, signs with the [community testkey](libs/signing/README.md), installs under **`/data`**, grants runtime permissions. See [docs/safety.md](docs/safety.md).

Other commands: `connect` · `build` · `sign` · `install` · `grant` · `check` · `start` · `uninstall`

## Contributor paths

| Goal | Doc |
|------|-----|
| New **vehicle platform** | [adding-an-integration.md](docs/adding-an-integration.md) — **`demo`** (no HU) or **`ihu629g`** (hardware) |
| New **external plugin** | [plugins.md](docs/plugins.md) |
| New **shell feature** | [adding-a-feature.md](docs/adding-a-feature.md) (curated) |
| Install / debug on HU | [contributor-debug.md](docs/contributor-debug.md) |

Integrations and plugins are plug-and-play. First-party features and the product entity catalog are curated on purpose.

## Threat model (short)

- Ktor listens cleartext on `0.0.0.0:8787` — do **not** expose to the public internet.
- Contributor debug can show a short Lab / `/debug` token when enabled.
- Host tools target user-space `/data` on **owned** userdebug HUs.
- Details: [docs/safety.md](docs/safety.md) · [docs/disclaimer.md](docs/disclaimer.md).

## Docs

Full index (by audience): **[docs/README.md](docs/README.md)**

Highlights: [Architecture](docs/architecture.md) · [Hub / self-host](docs/hub.md) · [ADR-0001](docs/adr/0001-architecture-north-star.md) · [ADR-0003 hub](docs/adr/0003-hub.md) · [Protocol OpenAPI](docs/openapi/open-automotive-assistant-v1.yaml) · [Domains](docs/domains.md) · [Contributing](CONTRIBUTING.md)

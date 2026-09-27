# Adding a first-party feature

Shell features under `features/<id>/` are **curated** — not folder-auto-discovered like integrations or plugins. Expect a small multi-file PR that wires Gradle, the composition root, and usually the web UI.

Integrations and plugins stay plug-and-play; see [adding-an-integration.md](adding-an-integration.md) and [plugins.md](plugins.md).

## Checklist

1. **Create the module**
   - Folder `features/<id>/` with `build.gradle.kts` (Android library, depend on `:integration-api` and optionally `:oaa-support`).
   - Package under `cc.opencar.assistant.feature.<id>`.
   - Gradle project name stays `:feature-<id>` (see [settings.gradle.kts](../settings.gradle.kts)).

2. **Register Gradle**
   - Add `"<id>"` to the curated features list in [settings.gradle.kts](../settings.gradle.kts).
   - `implementation(project(":feature-<id>"))` in [app/build.gradle.kts](../app/build.gradle.kts).
   - If the web layer needs the types: `api(project(":feature-<id>"))` in [features/web/build.gradle.kts](../features/web/build.gradle.kts).

3. **Wire `AssistantRuntime`**
   - Construct the controller in [AssistantRuntime.kt](../app/src/main/java/cc/opencar/assistant/AssistantRuntime.kt) after the session is ready.
   - Gate on `Capability` when the feature needs vehicle writes/reads.

4. **Expose HTTP (usual)**
   - Add fields to [OaaWebDeps.kt](../features/web/src/main/java/cc/opencar/assistant/feature/web/OaaWebDeps.kt) if needed.
   - Pass them from `OaaWebServer` / `AssistantRuntime`.
   - Add `Routes<Id>.kt` and call `register*Routes` from [OaaWebServer.kt](../features/web/src/main/java/cc/opencar/assistant/feature/web/OaaWebServer.kt).

5. **Product UI (if user-facing)**
   - Page under `features/web` assets (`pages/` + `index.html` nav).
   - Strings in `libs/oaa-support/src/main/assets/i18n/common/{en,pt-BR}.json` (served via `/api/i18n`; resolved client-side with `t()` / `entityLabel()`).

6. **Verify**
   - `./gradlew :app:assembleDebug`
   - On-device smoke via `./tools/oaa-setup -i <platform> -H <ip> …` when vehicle-related.

## What not to do

- Do not put OEM VHAL hex IDs in features — use `EntityRegistry.property(key)` / opaque `VehicleProperty`.
- Do not import concrete `integrations.*` classes from `:app` or `:feature-*`.
- Prefer a new **plugin** (`plugins/<id>/`) for external bridges (Home Assistant-style), not a feature module.

## New product controls (not a whole feature)

Adding a visible control card usually touches:

1. [EntityRegistry.kt](../libs/api/src/main/java/cc/opencar/assistant/api/EntityRegistry.kt) — `EntityDef` (binding key = VHAL property key)
2. Common i18n (`control.<id>` / options) — **required**: only described controls become product entities. Keys ship in entity payloads (`labelKey`); the web client translates them.
3. Optional pin map in [SettingsMemoryController.kt](../features/memory/src/main/java/cc/opencar/assistant/feature/memory/SettingsMemoryController.kt)
4. Each platform’s `platform.json` property entry (`entity` binding key + `access`)

For a **composite** (like `climate.cabin` or `drivetrain.vehicle`), add one `EntityDef` with `bindingKey = null` and `attributes` → binding keys; keep `platform.json` entity fields as the atomic keys (`HVAC_POWER_ON`, `DM_FUNC_DRIVE_MODE_SELECT`, …). Domain selects the card family in the web UI. See [composites.md](composites.md) for the car-native type set and Wave-1 bindings.

**Camera entities** (`camera.front` / `rear` / `left` / `right`) are virtual (Camera2), not VHAL: declare roles in `platform.json` → `cameras[]` (`role` + `cameraId`), register defs in `EntityRegistry`, and let ControlCatalog merge them when DVR is available. Mosaic/live stream stays under `/api/dvr/*` — do not add a mosaic entity.

Discover candidates in Lab → **VHAL catalog** → filter **Missing** (see [contributor-debug.md](contributor-debug.md)). The full HU property list lives in each integration’s `platform.json` → `properties` (shared AOSP stubs via `"extends": ["aaos"]`); it is for probing / gap tracking until you attach an `entity`.

That sprawl is intentional for v1 (single product registry). Platform-only enum labels belong in integration `valueMaps`, not in EntityRegistry.

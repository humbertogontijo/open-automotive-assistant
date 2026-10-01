# Adding a platform integration

Add a new module only when the **hardware/software platform** differs (new SoC / HU family). Do **not** add a module for a new badge or market trim of the same chip.

Collaborator work stays under `integrations/<platform-id>/`. Gradle auto-includes the folder and wires it into `:app`. Runtime discovery uses Java ServiceLoader — no edits to `AssistantRuntime`, `settings.gradle.kts`, or `app/build.gradle.kts`.

## Folder layout (copy this)

```
integrations/<platform-id>/
  host.sh                      # oaa-setup defaults (ADB host/port/user)
  build.gradle.kts
  src/main/assets/
    platform.json              # extends, properties, android overlays, models + profiles lists
    models/<sku>.json          # build/market SKU (matchDevice, optional property allowlist)
    profiles/<id>.json         # energy/capability profile (detect, extraCapabilities)

    i18n/<platform-id>/        # optional overrides + valueMaps (en.json, pt-BR.json)
  src/main/java/...            # thin Integration + bridge only when needed
  src/main/resources/META-INF/services/
    cc.opencar.assistant.api.VehicleIntegration   # FQCN of your implementation
```

Product install is **user-space `/data`**. Prefer a transport that works without priv-app (Antora: gRPC). If your HU only exposes VHAL via `CarPropertyManager`, use shared `CarPropertyBackend` from `:integrations:platform:aaos`. Formal `signature|privileged` grants (priv-app whitelist, OEM platform key) stay inside the platform folder if you need them — core `oaa-setup` does not elevate.

Shared layers (not auto-registered as integrations):

```
integrations/platform/aaos/     # AAOS plumbing: PlatformConfig, CarPropertyBridge, …
  src/main/assets/platform/aaos/platform.json   # Shared parent via "extends": ["aaos"]
integrations/platform/flyme/    # Flyme Auto family helpers
libs/oaa-support/                   # :oaa-support — I18nBundle, LastKnownStore (product helpers)
libs/api/                       # :integration-api — SPI
```

## Steps

1. Create `integrations/<platform-id>/` as above.
2. Fill `platform.json` (`backend`: `vhal`, `"extends": ["aaos"]`, `"models": ["default"]`, `"profiles": ["default"]`). Prefer config over Kotlin for properties / match. Put the full HU property catalog under `properties` (`id`, `key`, `access`, `areas`) — **do not** set `entity` here. SKU / market allowlists in `models/<sku>.json` drive identity product bindings (VHAL key = entity id); profiles hold detect / capabilities only (`matchDevice` from `ro.product.device`). See [ADR-0002](adr/0002-model-variant-bindings.md). Copy structure from **`ihu629g`** (simple) or **`demo`** (in-memory CI fake) rather than Antora when starting out. The aaos parent's `android` block is the **AAOS HU settings transport** (`settings` + `volumeGroups`) — product domains are `switch` / `number` / `media_player` (see [domains.md](domains.md)). For surround cameras, add `cameras: [{ "role": "front", "cameraId": "0" }, …]` (roles: front/right/rear/left) — each camera's stream size/fps come from the camera, not a `dvr` quality block.
3. Implement `VehicleIntegration` (+ optional `warm`, `createQuickEntry`, `wakeSignals`) on `VehiclePropertyBackend` / `CarPropertyBackend`. Prefer implementing `observe()` when the transport can push property changes; leave it null so the session polls (~1s). The product UI is event-driven (`session.telemetry()` / `events()` → `/api/events` WebSocket) either way.
4. Declare each property's **native range** in `platform.json` when it has one: `min` / `max` / `step`, or `values` for discrete levels (e.g. `"HVAC_FAN_SPEED": min 0, max 9`). OAA maps native ranges onto the canonical domain ranges (see [composites.md](composites.md) § Domain ranges), and the web UI only reads ranges from entities. A vector property can be exposed as one scalar with `"vector": {"read": 1, "write": [1, 4, 5]}` (reads one element; writes replace the listed elements and keep the rest).
5. Product writes are gated by `access` `w`/`rw` (derived allowlist). Take `access` from the HU's `CarPropertyConfig` (`dumpsys car_service --hal`) and add `"writeLocked": true` to HU-writable properties OAA must not write (see [safety.md](safety.md)). When the catalog is a VHAL dump, set `"vhalTypedIds": true` so unrecognized properties get a widget from their VehiclePropertyType (int / float → number, bytes / strings / vectors → sensor). OEM-specific Android bits (e.g. `VOLUME_GROUP/*`) go under `android.volumeGroups` in the integration file — shared wifi/bt/brightness live in `platform/aaos/platform.json`.
6. Register the class in `META-INF/services/cc.opencar.assistant.api.VehicleIntegration` (one FQCN per line).
7. Host setup: `./tools/oaa-setup -i <platform-id> -H <ip> setup` (loads `integrations/<id>/host.sh`).
8. i18n: reuse common keys from `:oaa-support`; add platform packs only for overrides / valueMaps (see below).

## Internationalization

Common strings live in `:oaa-support` assets (`libs/oaa-support/`):

```
libs/oaa-support/src/main/assets/i18n/common/en.json
libs/oaa-support/src/main/assets/i18n/common/pt-BR.json
```

Per-integration packs (path includes the id so APK asset merge does not collide):

```
i18n/<platform-id>/en.json
i18n/<platform-id>/pt-BR.json
```

JSON shape:

```json
{
  "strings": {
    "control.elka": "Emergency lane assist",
    "control.oem_special": "Platform-only control"
  },
  "valueMaps": {
    "drive_mode": {
      "1": "drive_mode.eco",
      "6": "drive_mode.normal"
    }
  }
}
```

- **Reuse common keys** for shared controls (`control.*`, `opt.*`, `nav.*`, `sensor.*`). Integrations do not need to redefine them.
- **Override** a common key in the integration pack when the platform wording differs.
- **valueMaps** map live numeric/string values → i18n keys. The app serves packs via `GET /api/i18n`; the **web client** resolves them at render time (`t()` / `valueLabel()` / `entityLabel()`). Entity APIs send `labelKey`, `hintKey`, `options[].labelKey`, `valueMapId`, and raw `value` — not translated strings.
- `platform.json` `driveModeEnum` values should be **i18n keys** (e.g. `"6": "drive_mode.normal"`), not localized literals.
- Product `EntityDef` uses `labelKey` / `hintKey` defaults `control.<id>` / `control.<id>.hint`.
- Locale: `GET /api/i18n`, `POST /api/locale`, Sistema → Idioma (pt-BR / en). Locale switch reloads the dictionary; cards re-render from keys without a catalog rebuild.
- New OEM enum literals belong in `platform.json` / valueMaps, not in `EntityRegistry`.

## Models vs profiles vs new modules

| Situation | Action |
|-----------|--------|
| Same chip, new market flash (`p145_eu`) | `models/<sku>.json` + Lab probe allowlist |
| Same chip, PHEV vs BEV | `profiles/phev.json` / `profiles/bev.json` |
| Same chip, one market missing a setting | Omit binding on that profile (or key on that SKU allowlist) |
| New SoC / different VHAL family | New `integrations/<id>/` |
| BR/CN EX2 (IHU629G) | `integrations/ihu629g` (VHAL / CarProperty) |
| AU EX2 (Antora) | Same `antora1000` module |

See [ADR-0002](adr/0002-model-variant-bindings.md).

## Lab override

Lab tab → **Integration override** (or `POST /api/lab/integration-override` with `id=<platform>`). Forces a specific integration id — e.g. `demo` with no HU, or `ihu629g` on an Antora unit to test UI without that SoC. **Force-stop or reboot** after applying so runtime rematches. See [contributor-debug.md](contributor-debug.md).

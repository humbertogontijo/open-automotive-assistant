# Shortcuts (flows, scenes, routines)

`:feature-shortcuts` under the web **Shortcuts** section. Home Assistant mapping:

| Home Assistant | Product | Role |
|----------------|-----|------|
| **Automation** | **Shortcut (flow)** | Trigger → AND conditions → actions |
| **Script** | **Routine** | Reusable fire-once action sequence (`run_routine`); optional AND conditions gate every run |
| **Scene** | **Scene** | Multi-entity on/off with restore or forced off-value |
| **Helper** (`input_boolean`, …) | **`ui_card`** virtual control | Bool/command card in the entity grid (`shortcut_<id>`) |
| **Blueprint** | Builtin scenes (e.g. **Sentinel**) | Templates keyed only to catalog entity ids; skip unbound targets |

## Building blocks

- **Shortcut (flow)** — triggers + conditions + actions. Event triggers: `boot` / `screen` (`on` / `off`; HU wake/sleep, debounced ~5s) / `gear` / `wheel_key` / `wifi_ssid` / `entity_state` / plugin triggers. Conditions (`entity_equals` / `gear_equals` / `wifi_ssid`) are AND-gated after a trigger match. Actions may `service`, `set_scene`, `run_routine`, `launch_app`, `delay_ms`, or plugin actions.
- **Scene** — snapshot configured entities, write on-values when activated; on deactivate restore snapshot or force a value per target. Builtin **Sentinel** seeds on first use. An external write to any target of an **active** scene deactivates it. On **boot**, every scene still marked active is restored (off-path) before boot shortcuts run.
- **Routine** — reusable fire-once action sequence. Optional AND conditions evaluated on **every** run; empty = always pass.

## Control actions (services)

Shortcuts and routines change controls the way Home Assistant does: they call a service on an entity, `{type: "service", service: "media_player.volume_set", entityId: "media_player.vehicle", data: {volume_level: 0.4}}`. Each domain has a fixed set of services, defined in [OaaServices](../libs/protocol/src/main/kotlin/cc/opencar/assistant/protocol/OaaServices.kt) and served at `GET /api/services`. The car resolves a call into the values it accepts on `POST /api/controls/{id}` and writes them in order. Services that depend on the current state (`toggle`, `select_next`, `increase_speed`, `button.press`) read the entity first.

| Domain | Services |
|--------|----------|
| `switch` | `turn_on`, `turn_off`, `toggle` |
| `light` | `turn_on` (`brightness` 0–255, `rgb_color`, `effect`), `turn_off`, `toggle` |
| `fan` | `turn_on` (`percentage`), `turn_off`, `toggle`, `set_percentage`, `increase_speed`, `decrease_speed` |
| `cover` | `open_cover`, `close_cover`, `set_cover_position`, `toggle` |
| `lock` | `lock`, `unlock` |
| `climate` | `turn_on`, `turn_off`, `toggle`, `set_hvac_mode`, `set_temperature` (optional `hvac_mode`), `set_fan_mode` |
| `media_player` | `media_play`, `media_pause`, `media_play_pause`, `media_stop`, `media_next_track`, `media_previous_track`, `volume_up`, `volume_down`, `volume_set` (`volume_level` 0–1) |
| `number` | `set_value` |
| `select` | `select_option`, `select_next` / `select_previous` (`cycle`), `select_first`, `select_last` |
| `button` | `press` |
| `text` | `set_value` |
| `drivetrain` | `set_drive_mode`, `set_regeneration`, `set_battery_mode`, `set_battery_hold`, `set_battery_save` |
| `chassis` | `set_auto_hold`, `set_hill_descent`, `set_esc_sport`, `set_epb` |
| `steering` | `set_assist_level`, `set_sync_drive_mode`, `set_intelligent_assist` |
| `charger` | `start_charging`, `stop_charging`, `charge_now`, `set_current_limit`, `set_charge_limit`, `set_minimum_charge`, `set_discharge_limit`, `set_v2l`, `set_v2v`, `set_port_light` |
| `hud` | `turn_on`, `turn_off`, `toggle`, `set_snow_mode`, `set_ar`, `set_display_mode`, `set_angle` |

The Home Assistant domains use HA's service names and fields. The car domains (`drivetrain` through `hud`) have their own. A service with `requires` only applies to entities that expose that attribute; the editor hides the rest.
**Portable automations:** reference [EntityRegistry](../libs/api/src/main/java/cc/opencar/assistant/api/EntityRegistry.kt) entity ids only — never VHAL hex. Builtin scenes skip missing targets.

## Device tracker / home zone

`device_tracker_vehicle` (My Vehicle) exposes GPS presence as HA-style `home` / `not_home` vs prefs `homeLat` / `homeLon` / `homeRadiusM` (`POST /api/location/home/here`). Gate flows with `entity_equals` on that entity. Needs location permissions.

## Quick entry & wake

- Integration may supply `VehicleIntegration.createQuickEntry()` (Flyme status-bar via `:integrations:platform:flyme`; default float chip).
- Shared dropdown (Open / Cameras / Shortcuts / … + pin slots 1–8) lives in `:feature-shortcuts`.
- `screen` on/off: AOSP screen intents + optional platform `WakeSignals` (Flyme ACC/STR). Debounced ~5s.

## APIs

`/api/shortcuts`, `/api/scenes`, `/api/routines`, `/api/services`, `/api/apps`.

HU radios / media product ids are documented in [domains.md](domains.md) § HU settings — not under a separate `android` domain.

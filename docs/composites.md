# Antora composites & covers

Canonical domain inventories: **[domains.md](domains.md)**.  
This page is **Antora-only** binding notes (HVAC zones, Wave tables) — not the portable taxonomy.

Product controls are **composites** (multi-property systems), **covers** (open/close + optional position), or thin **widget** atomics (`switch` / `select` / `number` / `sensor`). Entity ids are Home Assistant–shaped: `domain.object_id`.

HU radios / brightness / cabin volumes are **not** an `android` product domain — they use `switch.wifi`, `switch.bluetooth`, `number.brightness`, `number.vol_*` (transport still comes from `platform/aaos/platform.json`). See [domains.md](domains.md) § HU settings.

## Composites

| EntityType | Product id | Binding attributes (platform.json `entity`) |
|---|---|---|
| `climate` | `climate.cabin` | Core cabin: power, temp, cabin fan, vent direction, ac, auto, recirc, indoor temp. Modes: `off` / `manual` / `auto` (from `hvac_power` + `hvac_auto` — no heat/cool/fan_only). |

### HVAC zones (Antora)

VHAL HVAC uses `VehicleAreaSeat` bitmasks, but **this catalog is single-setpoint**:

| Property | Areas (decoded) | Meaning |
|---|---|---|
| `HVAC_TEMPERATURE_SET` | `R1_L (1)` only | One cabin target temp — no passenger setpoint |
| `HVAC_POWER_ON` | `R1_L+R1_R (5)` | Front-row power |
| `HVAC_AUTO_ON` / `HVAC_FAN_DIRECTION` | `R1_L (1)` | Driver / primary |
| `HVAC_FAN_SPEED` | `5`, `117` | Front or whole-cabin zone (not L/R independent) |
| `HVAC_AC_ON` / recirc / max_* | `117` (`AREA_HVAC_ZONE`) | Cabin-wide |
| `HVAC_SEAT_VENTILATION` | `1`, `4` | True dual-zone → `fan.seat_*`, not climate |

No `HVAC_DUAL` / sync property. If a future platform lists multiple `HVAC_TEMPERATURE_SET` areas, add `climate.passenger` (etc.) the same way covers are instanced per area — keep one card per zone, shared cabin toggles via `attributeAreas` where needed.

## Fans & locks

| Domain | Product id | Binding | Notes |
|---|---|---|---|
| `fan` | `fan.seat_driver` / `fan.seat_passenger` | `hvac_seat_vent` @ seat areas 1 / 4 | Levels 0–3; not the climate cabin fan |
| `lock` | `lock.central` | `central_lock` | Door lock actuator |
| `lock` | `lock.windows` | `window_lock` | Window switch lock |

Access *policy* toggles (`SETTING_FUNC_APPROACH_UNLOCK`, `SETTING_FUNC_AWAY_LOCK`, …) stay switches — not `lock`.

HVAC extras as atomics (not climate attrs): `HVAC_MAX_DEFROST_ON`, `HVAC_MAX_AC_ON`, `HVAC_FUNC_ECO_SWITCH`, `HVAC_FUNC_AUTO_DRY`, `HVAC_RAPID_COOLING` / `HVAC_RAPID_HEATING`, `HVAC_ELECTRIC_DEFROSTER_ON`, `HVAC_AUTO_RECIRC_ON`, `HVAC_FUNC_AUTO_SEAT_VENTILATION`.

| `drivetrain` | `drivetrain.vehicle` | `gear`, `drive_mode`, `regen`, `battery_hold` / `save` / `mode` |
| `chassis` | `chassis.vehicle` | `brake_pedal_mode`, `esc_sport`, `hdc`, `auto_hold`, Wave-1: `epb`, `parking_brake` |
| `steering` | `steering.vehicle` | `steer_assist_level`, `steer_sync_drive_mode`, `intelligent_steer`, `wheel_custom_key` |
| `charger` | `charger.vehicle` | charge cluster + Wave-1 `charge_external_light` |
| `ev_battery` | `ev_battery.main` | `ev_battery_percent`, `ev_battery_level_raw`, `battery_temp_c`, `hybrid_soc` |
| `hud` | `hud.main` | `hud_active` / `snow` / `ar` + Wave-1 `hud_display_mode`, `hud_angle` |
| `light` | `light.ambient` | `effect` ← `SETTING_FUNC_AMBIENCE_LIGHT_MAINCOLOR`, `brightness` (0–255) ← `SETTING_FUNC_AMBIENCE_LIGHT_INTENSITY_SET`, `rgb_color` ← `SETTING_FUNC_AMBIENCE_MODE_COLOR` (packed `0xRRGGBB`) |
| `camera` | `camera.front` / `rear` / `left` / `right` | Camera2 via `platform.json` → `cameras[]` (not VHAL); one stream and recording per camera |
| `seat` | *(planned)* | AAOS `SEAT_*` when position/memory is productized — see [domains.md](domains.md) |

## Domain ranges

Canonical ranges follow Home Assistant (`DomainDefaults` in `:integration-api`). Integrations declare native ranges in `platform.json`; the UI never hard-codes one.

| Domain | Value | Canonical | Behaviour |
|---|---|---|---|
| `light` | `brightness` | 0–255 | fixed scale; native range is scaled (ambient 0–20 → 0–255) |
| `light` | `rgb_color` | `[r, g, b]` | native packed `0xRRGGBB` int; write `rgb_color:r,g,b` |
| `cover` | `position` | 0–100 | fixed scale; native range is scaled |
| `climate` | temperature | 7–35 °C, step 0.5 | native bounds pass through; defaults only when undeclared |
| `climate` | `fan_modes` / `fan_directions` | none | listed only when the integration declares levels / `values` |
| `number` | value | 0–100, step 1 | native bounds pass through; defaults only when undeclared |

Lights also expose `supported_color_modes` / `color_mode` (`onoff`, `brightness`, `rgb`) and the effect options (`effect:<value>`).

## Covers

| Product id | Binding | Position | Notes |
|---|---|---|---|
| `cover.window_driver` … `_rear_right` | `window_pos` @ seat areas | 0–100 int32 | open if position > 1 |
| `cover.sunroof` | `window_pos` @ `0x10000` | 0–100 | glass |
| `cover.sunshade` | `window_pos` @ `0x20000` | 0–100 | curtain |
| `cover.trunk` | write `trunk_move` (DOOR_MOVE); read `trunk_status` | none | status 0/1 = closed |

Sibling atomics: `SETTING_FUNC_AUTO_CLOSE_WINDOW`, `BCM_FUNC_SUNROOF_TILT`, `SETTING_FUNC_TRUNK_OPENING_POSITION`; the window switch lock is `lock.windows`.

Mirrors are atomics (`MIRROR_FOLD`, `SETTING_FUNC_MIRROR_AUTO_FOLDING`). Doors are not product entities on Antora: `DOOR_POS` is ajar-only, `DOOR_MOVE` is the liftgate, lock UX is `lock.central`.

Entities are addressed by their canonical id only; composite attributes are written through the composite (`climate.cabin` with `temperature:22`).

## Wave-1 table (Antora)

| VHAL key | id | Product | HU access |
|---|---|---|---|
| `SETTING_FUNC_STEERING_ASSISTANCE_LEVEL` | `0x21407185` | `steering.vehicle.assist_level` | rw |
| `DM_FUNC_STEERING_WHEEL_FEEL_SYNC_DRIVEMODE` | `0x21207107` | `steering.vehicle.sync_drive_mode` | rw |
| `SETTING_FUNC_PBC_DOUBLE_EPB_SWITCH` | `0x2120701a` | `chassis.vehicle.epb` | rw |
| `PARKING_BRAKE_ON` | `0x11200402` | `chassis.vehicle.parking_brake` | r |
| `SETTING_FUNC_HUD_DISPLAY_MODE` | `0x21407643` | `hud.main.display_mode` | rw |
| `SETTING_FUNC_HUD_ANGLE_ADJUST` | `0x2120720b` | `hud.main.angle` | rw |
| `HVAC_ELECTRIC_DEFROSTER_ON` | `0x13200514` | `HVAC_ELECTRIC_DEFROSTER_ON` | rw |
| `HVAC_AUTO_RECIRC_ON` | `0x15200512` | `HVAC_AUTO_RECIRC_ON` | rw |
| `HVAC_FUNC_AUTO_SEAT_VENTILATION` | `0x254070b5` | `HVAC_FUNC_AUTO_SEAT_VENTILATION` | rw |
| `CHARGE_FUNC_EXTERNAL_CHARGING_LIGHT` | `0x2120727c` | `charger.vehicle.external_light` | rw |
| `WINDOW_LOCK` / `WINDOW_POS` | `0x13200bc4` / `0x13400bc0` | `lock.windows` / `cover.window_*` | rw |
| `DOOR_MOVE` | `0x16400b01` | `cover.trunk` (OPEN=1 CLOSE=0) | rw |
| `BCM_FUNC_TRUNK_DOOR_STATUS` | `0x214073bc` | `cover.trunk` readback | rw |
| `BCM_FUNC_SUNROOF_TILT` | `0x23207156` | `BCM_FUNC_SUNROOF_TILT` | rw |
| `MIRROR_FOLD` | `0x11200b45` | `MIRROR_FOLD` | rw |
| `SETTING_FUNC_MIRROR_AUTO_FOLDING` | `0x24207024` | `SETTING_FUNC_MIRROR_AUTO_FOLDING` | rw |

`DOOR_LOCK` / `DOOR_POS` / `BCM_FUNC_REAR_MIRROR_ADJUST` stay unbound.

Every catalog `access` value mirrors the HU `CarPropertyConfig` dump; `writeLocked` keeps dangerous HU-writable properties off the allowlist (see [safety.md](safety.md)).

## Wave 2 candidates (not bound)

- Exterior light enum mirrors, rain/wiper helpers, welcome/music ambient modes
- ADAS sibling volumes already atomic — keep atomic (no mega-`adas`)
- Continuous telemetry / factory / profile CRUD — leave unbound
- CarConfig letter / `CB_*` junk and hex-only unknowns — never promote

## AdaptAPI gaps

AdaptAPI FUNC names often match `platform.json` `key`, but some AutoSettings FUNCs have **no VHAL id** on this HU yet. Do not invent hex IDs. Vendor prefix → domain mapping is in [domains.md](domains.md) § Vendor AdaptAPI prefixes.

## Exterior UI

Covers are `cover.*` cards (position slider when `current_position` is present). Sibling switches/numbers sit beside them on Controles → Exterior.

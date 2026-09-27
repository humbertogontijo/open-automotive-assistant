package cc.opencar.assistant.api

/**
 * Home Assistant–inspired entity contract for the product surface.
 *
 * Integrations map platform binding keys via `platform.json` → `properties[].entity`;
 * the curated [EntityRegistry] exposes stable catalog **entity ids** used by
 * UI, history, shortcuts, scenes, and routines. Features never hardcode VHAL hex.
 *
 * Composite entities (e.g. `climate.cabin`) have one product id with [EntityDef.attributes]
 * pointing at multiple binding keys (`HVAC_POWER_ON`, `HVAC_TEMPERATURE_SET`, …). Atomic
 * entities keep `id == bindingKey`.
 *
 * JSON maps from `/api/entities` (and virtual shortcut cards) include:
 *
 * | Field | Role |
 * |-------|------|
 * | `id` | Stable catalog id (`climate.cabin`, `sensor.soc`, …) — HA-shaped `domain.object_id` |
 * | `domain` | Same as [EntityType.id] (`sensor`, `climate`, `cover`, `switch`, …) |
 * | `state` / `value` | Current state string (HA `state` / product `value`) |
 * | `friendlyName` / `label` | Localized display name |
 * | `available` | `true` when `status` is `ok` or `cached` |
 * | `status` | `ok` \| `cached` \| `denied` \| `unavailable` \| `failed` |
 * | `deviceClass` | HA-aligned [DeviceClass.id] |
 * | `unitOfMeasurement` | HA-aligned [UnitOfMeasurement.id] |
 * | `attributes` | Nested map of semantic extras (device_class, unit, …) |
 * | `group` | Nav page id (`home`, `energy`, `controls`, …) |
 * | `section` | Subsection within the page (`climate`, `lock`, …) — not HA domain |
 * | `input` | Soft widget hint (`bool`, `choice`, `sensor`, `climate`, …); **domain** selects card family |
 * | `composite` | `true` when one product id spans many binding keys |
 * | `update` | Live-update policy: [UPDATE_ENTITY] (default) or [UPDATE_CATALOG] |
 *
 * **Live updates:** atomic entities may receive WS `entity` deltas with a new `value`.
 * Composites use [UPDATE_CATALOG] — binding-key edges invalidate the catalog; the UI
 * must not apply attr-raw values onto product `state`/`value`.
 *
 * **Availability:** an entity appears only when the platform binds its property
 * (and capability gates allow the section). Unbound = not created — same idea as
 * HA “integration didn’t register the entity.” Composites appear when any core
 * attribute binding exists.
 *
 * **Portability:** shortcuts / scenes / routines must reference catalog `id`s only.
 * Builtin scenes (e.g. Sentinel) skip missing targets at runtime. Composite attributes
 * are written through the composite id (`climate.cabin` with `temperature:22`).
 *
 * MQTT / HA outbound discovery may publish these fields later; inbound HA bridge
 * remains [plugins/homeassistant].
 *
 * Domain taxonomy (AAOS + CarPlay Ultra field inventories): `docs/domains.md`.
 * There is no `android` product domain — HU radios/brightness/volumes use
 * `switch` / `number` / `media_player` with nav groups `connect` / `display` / `sound`.
 */
object EntityContract {
    const val FIELD_ID = "id"
    const val FIELD_DOMAIN = "domain"
    const val FIELD_STATE = "state"
    const val FIELD_VALUE = "value"
    const val FIELD_FRIENDLY_NAME = "friendlyName"
    const val FIELD_LABEL = "label"
    const val FIELD_AVAILABLE = "available"
    const val FIELD_STATUS = "status"
    const val FIELD_ATTRIBUTES = "attributes"
    const val FIELD_DEVICE_CLASS = "deviceClass"
    const val FIELD_UNIT = "unitOfMeasurement"
    const val FIELD_GROUP = "group"
    const val FIELD_SECTION = "section"
    const val FIELD_COMPOSITE = "composite"
    /** How the web shell should apply live updates for this row. */
    const val FIELD_UPDATE = "update"
    /** Patch `value`/`state` from WS `entity` deltas (atomics, media transport). */
    const val UPDATE_ENTITY = "entity"
    /** Reload `/api/entities` — never apply binding attr-raw as product state. */
    const val UPDATE_CATALOG = "catalog"

    /** Status values treated as “entity exists and has a usable reading.” */
    fun isAvailable(status: String?): Boolean =
        status == "ok" || status == "cached"

    fun isComposite(row: Map<String, Any?>): Boolean =
        row[FIELD_COMPOSITE] == true

    /** True when live binding edges must refresh via catalog, not entity value patches. */
    fun isCatalogUpdate(row: Map<String, Any?>): Boolean =
        row[FIELD_UPDATE] == UPDATE_CATALOG || isComposite(row)

    fun updatePolicy(composite: Boolean): String =
        if (composite) UPDATE_CATALOG else UPDATE_ENTITY

    /**
     * Adds HA-shaped fields (`state`, `friendlyName`, `available`, `attributes`)
     * and defaults `domain` to [EntityType.EXTRA].
     * Ensures [FIELD_COMPOSITE] / [FIELD_UPDATE] are always present.
     */
    fun enrich(row: Map<String, Any?>): Map<String, Any?> {
        val composite = row[FIELD_COMPOSITE] == true
        val update = (row[FIELD_UPDATE] as? String)
            ?: updatePolicy(composite)

        if (row[FIELD_DOMAIN] != null &&
            row.containsKey(FIELD_AVAILABLE) &&
            row[FIELD_ATTRIBUTES] is Map<*, *>
        ) {
            if (row.containsKey(FIELD_COMPOSITE) && row.containsKey(FIELD_UPDATE)) {
                return row
            }
            return row + mapOf(
                FIELD_COMPOSITE to composite,
                FIELD_UPDATE to update,
            )
        }
        val status = row[FIELD_STATUS] as? String
        val label = row[FIELD_LABEL] as? String
        val domain = (row[FIELD_DOMAIN] as? String) ?: EntityType.EXTRA.id
        val value = row[FIELD_VALUE]
        @Suppress("UNCHECKED_CAST")
        val existingAttrs = row[FIELD_ATTRIBUTES] as? Map<*, *>
        val attributes = linkedMapOf<String, Any?>().apply {
            if (existingAttrs != null) {
                for ((k, v) in existingAttrs) {
                    if (k is String && v != null) put(k, v)
                }
            }
            put("friendly_name", label)
            put("device_class", row[FIELD_DEVICE_CLASS])
            put("unit_of_measurement", row[FIELD_UNIT])
            put("icon", row["icon"])
            put("group", row[FIELD_GROUP])
            put("input", row["input"])
            put("stale", row["stale"])
            put("history", row["history"])
            put("writable", row["writable"])
            put("composite", composite)
            put("update", update)
            if (row["virtual"] == true) {
                put("virtual", true)
                put("virtual_kind", row["virtualKind"])
            }
        }.filterValues { it != null }
        return row + mapOf(
            FIELD_DOMAIN to domain,
            FIELD_STATE to value,
            FIELD_FRIENDLY_NAME to label,
            FIELD_AVAILABLE to isAvailable(status),
            FIELD_COMPOSITE to composite,
            FIELD_UPDATE to update,
            FIELD_ATTRIBUTES to attributes,
        )
    }
}

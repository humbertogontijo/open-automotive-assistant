package cc.opencar.assistant.api

/**
 * Product entity domains used by the web shell to pick card templates.
 *
 * Entity ids follow Home Assistant shape: `domain.object_id`
 * (e.g. `cover.window_driver`, `switch.wifi`, `climate.cabin`).
 *
 * Domain taxonomy is AAOS + CarPlay Ultra–led — see `docs/domains.md`.
 * There is no `android` product domain; HU radios/brightness/volumes use
 * [SWITCH] / [NUMBER] / [MEDIA_PLAYER] with nav groups `connect` / `display` / `sound`.
 */
enum class EntityType(val id: String) {
    // --- Composites (multi-property) ---
    CLIMATE("climate"),
    MEDIA_PLAYER("media_player"),
    CHARGER("charger"),
    EV_BATTERY("ev_battery"),
    HUD("hud"),
    LIGHT("light"),
    DRIVETRAIN("drivetrain"),
    CHASSIS("chassis"),
    STEERING("steering"),
    CAMERA("camera"),

    // --- Cover / fan / lock / seat ---
    COVER("cover"),
    FAN("fan"),
    LOCK("lock"),
    /** Planned: AAOS `SEAT_*` / CarPlay Ultra Seat — promote when position/memory is bound. */
    SEAT("seat"),

    // --- Atomic widgets ---
    SWITCH("switch"),
    SELECT("select"),
    NUMBER("number"),
    SENSOR("sensor"),

    // --- Platform / virtual ---
    DEVICE_TRACKER("device_tracker"),
    EXTRA("extra"),
    ;

    companion object {
        fun fromId(id: String?): EntityType = entries.firstOrNull { it.id == id } ?: EXTRA
    }
}

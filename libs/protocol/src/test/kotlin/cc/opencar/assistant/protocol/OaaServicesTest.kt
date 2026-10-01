package cc.opencar.assistant.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OaaServicesTest {
    private fun writes(
        service: String,
        entityId: String,
        data: Map<String, Any?> = emptyMap(),
        entity: Map<String, Any?>? = null,
    ): List<String> = OaaServices.resolve(service, entityId, data, entity).getOrThrow()

    @Test
    fun mediaPlayerTransportAndVolume() {
        assertEquals(listOf("play_pause"), writes("media_player.media_play_pause", "media_player.vehicle"))
        assertEquals(listOf("next"), writes("media_player.media_next_track", "media_player.vehicle"))
        assertEquals(listOf("volume_up"), writes("media_player.volume_up", "media_player.vehicle"))
        assertEquals(listOf("volume_level:0.4"), writes("media_player.volume_set", "media_player.vehicle", mapOf("volume_level" to "0.4")))
        assertEquals(listOf("volume_level:1.0"), writes("media_player.volume_set", "media_player.vehicle", mapOf("volume_level" to 3)))
    }

    @Test
    fun climateWritesModeBeforeTemperature() {
        assertEquals(
            listOf("hvac_mode:auto", "temperature:22"),
            writes("climate.set_temperature", "climate.cabin", mapOf("temperature" to 22.0, "hvac_mode" to "auto")),
        )
        assertEquals(listOf("temperature:21.5"), writes("climate.set_temperature", "climate.cabin", mapOf("temperature" to "21.5")))
        assertEquals(listOf("on"), writes("climate.toggle", "climate.cabin", entity = mapOf("value" to "off")))
        assertEquals(listOf("off"), writes("climate.toggle", "climate.cabin", entity = mapOf("value" to "auto")))
    }

    @Test
    fun lightTurnOnCombinesDataOrJustTurnsOn() {
        assertEquals(listOf("on"), writes("light.turn_on", "light.ambient"))
        assertEquals(
            listOf("effect:3", "rgb_color:255,0,10", "brightness:128"),
            writes("light.turn_on", "light.ambient", mapOf("brightness" to 128, "rgb_color" to listOf(255, 0, 10), "effect" to "3")),
        )
    }

    @Test
    fun switchToggleReadsState() {
        assertEquals(listOf("0"), writes("switch.toggle", "switch.wifi", entity = mapOf("value" to "1")))
        assertEquals(listOf("1"), writes("switch.toggle", "switch.wifi", entity = mapOf("value" to "0")))
        assertEquals(listOf("1"), writes("switch.turn_on", "SETTING_FUNC_AUTO_CLOSE_WINDOW"))
    }

    @Test
    fun selectCyclesThroughOptions() {
        val entity = mapOf("value" to "3", "options" to listOf(mapOf("value" to 1), mapOf("value" to 2), mapOf("value" to 3)))
        assertEquals(listOf("1"), writes("select.select_next", "select.x", entity = entity))
        assertEquals(listOf("3"), writes("select.select_next", "select.x", mapOf("cycle" to false), entity))
        assertEquals(listOf("2"), writes("select.select_previous", "select.x", entity = entity))
        assertEquals(listOf("1"), writes("select.select_first", "select.x", entity = entity))
    }

    @Test
    fun fanMapsPercentagesToLevels() {
        val entity = mapOf("value" to "1", "min" to 0, "max" to 3, "step" to 1)
        assertEquals(listOf("3"), writes("fan.set_percentage", "fan.seat_driver", mapOf("percentage" to 100), entity))
        assertEquals(listOf("1"), writes("fan.set_percentage", "fan.seat_driver", mapOf("percentage" to 30), entity))
        assertEquals(listOf("0"), writes("fan.set_percentage", "fan.seat_driver", mapOf("percentage" to 0), entity))
        assertEquals(listOf("2"), writes("fan.increase_speed", "fan.seat_driver", entity = entity))
        assertEquals(listOf("0"), writes("fan.toggle", "fan.seat_driver", entity = entity))
    }

    @Test
    fun carDomainsWriteAttributes() {
        assertEquals(listOf("switch:1"), writes("charger.start_charging", "charger.vehicle"))
        assertEquals(listOf("soc_max:80"), writes("charger.set_charge_limit", "charger.vehicle", mapOf("soc" to "80")))
        assertEquals(listOf("auto_hold:0"), writes("chassis.set_auto_hold", "chassis.vehicle", mapOf("enabled" to false)))
        assertEquals(listOf("active:0"), writes("hud.toggle", "hud.main", entity = mapOf("value" to "1")))
    }

    @Test
    fun rejectsWrongDomainAndMissingData() {
        assertTrue(OaaServices.resolve("light.turn_on", "climate.cabin", emptyMap(), null).isFailure)
        assertTrue(OaaServices.resolve("number.set_value", "number.brightness", emptyMap(), null).isFailure)
        assertTrue(OaaServices.resolve("nope.nothing", "switch.wifi", emptyMap(), null).isFailure)
        assertTrue(OaaServices.resolve("switch.toggle", "switch.wifi", emptyMap(), null).isFailure)
    }

    @Test
    fun catalogListsDomainsWithFields() {
        val media = OaaServices.catalog().first { it["domain"] == "media_player" }
        @Suppress("UNCHECKED_CAST")
        val services = media["services"] as List<Map<String, Any?>>
        assertTrue(services.any { it["service"] == "media_play_pause" })
        val volumeSet = services.first { it["service"] == "volume_set" }
        @Suppress("UNCHECKED_CAST")
        assertEquals("volume_level", (volumeSet["fields"] as List<Map<String, Any?>>).single()["key"])
    }
}

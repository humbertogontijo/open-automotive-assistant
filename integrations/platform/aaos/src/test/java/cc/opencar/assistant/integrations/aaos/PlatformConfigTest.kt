package cc.opencar.assistant.integrations.aaos

import cc.opencar.assistant.api.DeviceFingerprint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlatformConfigTest {
    private val familyAssets = File("src/main/assets/platform")
    private val antoraJson = File("../../antora1000/src/main/assets/platform.json")
    private val antoraModels = File("../../antora1000/src/main/assets/models")
    private val antoraProfiles = File("../../antora1000/src/main/assets/profiles")
    private val ihuJson = File("../../ihu629g/src/main/assets/platform.json")
    private val ihuModels = File("../../ihu629g/src/main/assets/models")
    private val ihuProfiles = File("../../ihu629g/src/main/assets/profiles")

    private fun parentResolver(): (String) -> String = { name ->
        File(familyAssets, "$name/platform.json").readText()
    }

    private fun loadAntora(profileId: String, skuId: String = "p145_eu"): PlatformConfig {
        val cfg = PlatformConfig.parse(antoraJson.readText(), parentResolver())
        val skus = antoraModels.listFiles()?.filter { it.extension == "json" }?.map {
            PlatformConfig.parseSku(it.readText())
        }.orEmpty()
        val profiles = antoraProfiles.listFiles()?.filter { it.extension == "json" }?.map {
            PlatformConfig.parseProfile(it.readText())
        }.orEmpty()
        return cfg.copy(skus = skus, profiles = profiles).forSelection(skuId, profileId)
    }

    @Test
    fun parseAntoraPlatformJson() {
        assertTrue("expected ${antoraJson.absolutePath}", antoraJson.isFile)
        val cfg = loadAntora("phev")
        assertEquals("antora1000", cfg.id)
        assertEquals("vhal", cfg.backend)
        assertTrue(cfg.match.isNotEmpty())
        assertTrue(cfg.bindings.containsKey("PERF_VEHICLE_SPEED"))
        assertTrue(cfg.writableAllowlist.isNotEmpty())
        assertEquals(0x11600207, cfg.bindings.getValue("PERF_VEHICLE_SPEED").nativeId)
        assertEquals(4, cfg.cameras.size)
        assertEquals("front", cfg.cameras.first().role)
        assertEquals("0", cfg.cameras.first().cameraId)
        assertTrue(cfg.properties.size >= 100)
        assertTrue(cfg.android.volumeGroups.any { it.entity == "number.vol_media" })
        assertTrue(cfg.catalogEntries().isNotEmpty())
        assertEquals(
            cfg.writableAllowlist.size,
            cfg.properties.count { it.canWrite },
        )
        assertEquals("p145_eu", cfg.activeSkuId)
        assertEquals("phev", cfg.activeProfileId)
        assertTrue(cfg.skus.any { it.id == "p145_eu" })
        assertTrue(cfg.profiles.any { it.id == "phev" })
        val raw = JSONObject(antoraJson.readText())
        val props = raw.getJSONArray("properties")
        for (i in 0 until minOf(props.length(), 50)) {
            assertFalse(
                "property still has entity: ${props.getJSONObject(i).optString("key")}",
                props.getJSONObject(i).has("entity"),
            )
        }
    }

    @Test
    fun parseIhu629gPlatformJson() {
        assertTrue("expected ${ihuJson.absolutePath}", ihuJson.isFile)
        val cfg = PlatformConfig.parse(ihuJson.readText(), parentResolver())
        val skus = ihuModels.listFiles()?.filter { it.extension == "json" }?.map {
            PlatformConfig.parseSku(it.readText())
        }.orEmpty()
        val profiles = ihuProfiles.listFiles()?.filter { it.extension == "json" }?.map {
            PlatformConfig.parseProfile(it.readText())
        }.orEmpty()
        val applied = cfg.copy(skus = skus, profiles = profiles).forSelection("default", "default")
        assertEquals("ihu629g", applied.id)
        assertEquals("vhal", applied.backend)
        assertTrue(applied.writableAllowlist.isNotEmpty())
        assertTrue(applied.bindings.containsKey("drive_mode") || applied.bindings.containsKey("DM_FUNC_DRIVE_MODE_SELECT"))
        val driveKey = when {
            "drive_mode" in applied.bindings -> "drive_mode"
            else -> "DM_FUNC_DRIVE_MODE_SELECT"
        }
        assertEquals(570491136, applied.bindings.getValue(driveKey).nativeId)
        assertTrue(applied.driveModeEnum.containsKey(570491137))
        assertTrue(605029888 in applied.writableAllowlist)
        assertTrue(applied.android.settings.any { it.entity == "switch.wifi" })
    }

    @Test
    fun matchSkuFromDeviceFingerprint() {
        val cfg = loadAntora("phev")
        val fp = DeviceFingerprint(
            model = "Geely EX5",
            device = "antora1000_p145_eu",
            hardware = "se1000",
            manufacturer = "Geely EX5",
            fingerprint = "GEELYGALAXY/antora1000_p145_eu_car/antora1000_p145_eu:11/…",
            brand = "GEELYGALAXY",
        )
        assertEquals("p145_eu", cfg.matchSku(fp)?.id)
    }

    @Test
    fun antoraPhevProfileAndSkuAllowlist() {
        val sku = PlatformConfig.parseSku(File(antoraModels, "p145_eu.json").readText())
        val profile = PlatformConfig.parseProfile(File(antoraProfiles, "phev.json").readText())
        assertEquals("p145_eu", sku.id)
        assertTrue(sku.matchDevice.any { it.contains("p145_eu") })
        assertTrue((sku.propertyKeys?.size ?: 0) > 100)
        assertEquals("phev", profile.id)
    }

    @Test
    fun accessDerivesAllowlist() {
        val cfg = PlatformConfig.parse(
            """
            {
              "id": "t",
              "displayName": "T",
              "backend": "vhal",
              "match": ["t"],
              "capabilities": ["READ_TELEMETRY"],
              "properties": [
                {
                  "id": "0x11400400",
                  "key": "GEAR_SELECTION",
                  "access": "r",
                  "areas": [0],
                  "entity": "gear"
                },
                {
                  "id": "0x15200510",
                  "key": "HVAC_POWER_ON",
                  "access": "rw",
                  "areas": [5],
                  "entity": "hvac_power"
                }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(0x11400400, cfg.bindings.getValue("gear").nativeId)
        assertEquals(5, cfg.bindings.getValue("hvac_power").areaId)
        assertTrue(0x15200510 in cfg.writableAllowlist)
        assertFalse(0x11400400 in cfg.writableAllowlist)
        assertEquals(2, cfg.catalogEntries().size)
        assertTrue(cfg.catalogEntries().first { it.name == "HVAC_POWER_ON" }.writable)
        assertFalse(cfg.catalogEntries().first { it.name == "GEAR_SELECTION" }.writable)
    }

    @Test
    fun extendsMergesPropertiesAndAndroid() {
        val parents = mapOf(
            "aaos" to """
              {
                "properties": [
                  { "id": "0x15200510", "key": "HVAC_POWER_ON", "access": "r", "areas": [0] }
                ],
                "android": {
                  "settings": [
                    { "settingsKey": "wifi_on", "entity": "switch.wifi", "access": "rw" }
                  ],
                  "volumeGroups": []
                }
              }
            """.trimIndent(),
        )
        val cfg = PlatformConfig.parse(
            """
            {
              "id": "child",
              "extends": ["aaos"],
              "displayName": "Child",
              "backend": "vhal",
              "match": ["c"],
              "capabilities": [],
              "properties": [
                {
                  "id": "0x15200510",
                  "access": "rw",
                  "areas": [5],
                  "entity": "hvac_power"
                }
              ],
              "android": {
                "volumeGroups": [
                  {
                    "groupId": 0,
                    "entity": "number.vol_media",
                    "access": "rw",
                    "writeVia": "media_keyevent"
                  }
                ]
              }
            }
            """.trimIndent(),
        ) { parents.getValue(it) }

        val power = cfg.properties.first { it.id == 0x15200510 }
        assertEquals("HVAC_POWER_ON", power.key)
        assertEquals(listOf(5), power.areas)
        assertEquals("hvac_power", power.entity)
        assertEquals("rw", power.access)
        assertTrue(power.canWrite)
        assertTrue(cfg.android.settings.any { it.entity == "switch.wifi" })
        assertEquals(1, cfg.android.volumeGroups.size)
        assertEquals("number.vol_media", cfg.android.volumeGroups.first().entity)
    }
}

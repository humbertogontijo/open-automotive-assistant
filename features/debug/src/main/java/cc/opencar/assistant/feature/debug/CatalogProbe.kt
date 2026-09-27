package cc.opencar.assistant.feature.debug

import android.content.Context
import android.util.Log
import cc.opencar.assistant.api.CatalogEntry
import cc.opencar.assistant.api.ReadOutcome
import cc.opencar.assistant.api.VehicleSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Walks the vehicle catalog and records readable / permission-denied / failed props.
 */
class CatalogProbe(
    private val context: Context,
    private val session: VehicleSession,
) {
    data class PropResult(
        val name: String,
        val key: String,
        val nativeIdHex: String?,
        val family: String,
        val writable: Boolean,
        val status: String, // ok | denied | unavailable | failed
        val permission: String? = null,
        val areaId: Int? = null,
        val value: String? = null,
        val message: String? = null,
        /** Product entity / control id when this prop is bound in platform.json. */
        val entity: String? = null,
    )

    data class ProbeReport(
        val timestampMs: Long,
        val integrationId: String,
        val catalogSize: Int,
        val results: List<PropResult>,
    ) {
        fun summary(): Map<String, Any> {
            val byStatus = results.groupingBy { it.status }.eachCount()
            val bound = results.count { it.entity != null }
            val byFamily = results.groupBy { it.family }.mapValues { (_, list) ->
                mapOf(
                    "total" to list.size,
                    "ok" to list.count { it.status == "ok" },
                    "denied" to list.count { it.status == "denied" },
                    "unavailable" to list.count { it.status == "unavailable" },
                    "failed" to list.count { it.status == "failed" },
                    "bound" to list.count { it.entity != null },
                )
            }
            return mapOf(
                "timestampMs" to timestampMs,
                "integrationId" to integrationId,
                "catalogSize" to catalogSize,
                "counts" to byStatus,
                "boundEntities" to bound,
                "unbound" to (results.size - bound),
                "families" to byFamily,
            )
        }

        fun toJsonArray(): JSONArray {
            val arr = JSONArray()
            results.forEach { r ->
                arr.put(
                    JSONObject()
                        .put("name", r.name)
                        .put("key", r.key)
                        .put("id", r.nativeIdHex)
                        .put("family", r.family)
                        .put("writable", r.writable)
                        .put("status", r.status)
                        .put("permission", r.permission)
                        .put("areaId", r.areaId)
                        .put("value", r.value)
                        .put("message", r.message)
                        .put("entity", r.entity),
                )
            }
            return arr
        }
    }

    private val cache = AtomicReference<ProbeReport?>(null)

    fun cached(): ProbeReport? = cache.get() ?: loadFromDisk()

    suspend fun run(force: Boolean = false): ProbeReport {
        if (!force) cached()?.let { return it }
        Log.i(TAG, "Catalog probe starting…")
        LogRingBuffer.append("CatalogProbe start catalog=${session.catalog().size}")
        val results = mutableListOf<PropResult>()
        for (entry in session.catalog()) {
            results += probeOne(entry)
        }
        val report = ProbeReport(
            timestampMs = System.currentTimeMillis(),
            integrationId = session.integrationId,
            catalogSize = session.catalog().size,
            results = results,
        )
        cache.set(report)
        saveToDisk(report)
        LogRingBuffer.append(
            "CatalogProbe done ok=${results.count { it.status == "ok" }} " +
                "denied=${results.count { it.status == "denied" }}",
        )
        return report
    }

    private suspend fun probeOne(entry: CatalogEntry): PropResult {
        val family = familyOf(entry.name)
        val nativeId = entry.property.nativeId
        val entity = nativeId?.let { session.entityBindings()[it] }
        val outcome = session.diagnose(entry.property)
        return when (outcome) {
            is ReadOutcome.Ok -> PropResult(
                name = entry.name,
                key = entry.property.key,
                nativeIdHex = entry.property.nativeId?.toString(16),
                family = family,
                writable = entry.writable,
                status = "ok",
                areaId = outcome.areaId,
                value = outcome.value?.display()?.let { redact(entry.name, it) },
                entity = entity,
            )
            is ReadOutcome.Denied -> PropResult(
                name = entry.name,
                key = entry.property.key,
                nativeIdHex = entry.property.nativeId?.toString(16),
                family = family,
                writable = entry.writable,
                status = "denied",
                permission = outcome.permission,
                areaId = outcome.areaId,
                message = outcome.message,
                entity = entity,
            )
            is ReadOutcome.Failed -> PropResult(
                name = entry.name,
                key = entry.property.key,
                nativeIdHex = entry.property.nativeId?.toString(16),
                family = family,
                writable = entry.writable,
                status = "failed",
                areaId = outcome.areaId,
                message = outcome.message,
                entity = entity,
            )
            is ReadOutcome.Unavailable -> PropResult(
                name = entry.name,
                key = entry.property.key,
                nativeIdHex = entry.property.nativeId?.toString(16),
                family = family,
                writable = entry.writable,
                status = "unavailable",
                areaId = outcome.areaId,
                entity = entity,
            )
        }
    }

    private fun cacheFile(): File =
        File(context.filesDir, "oaa_catalog_probe.json")

    private fun saveToDisk(report: ProbeReport) {
        runCatching {
            val root = JSONObject()
                .put("timestampMs", report.timestampMs)
                .put("integrationId", report.integrationId)
                .put("catalogSize", report.catalogSize)
                .put("results", report.toJsonArray())
            cacheFile().writeText(root.toString())
        }
    }

    private fun loadFromDisk(): ProbeReport? {
        return runCatching {
            val root = JSONObject(cacheFile().readText())
            val arr = root.getJSONArray("results")
            val list = mutableListOf<PropResult>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += PropResult(
                    name = o.optString("name"),
                    key = o.optString("key"),
                    nativeIdHex = o.optString("id").ifBlank { null },
                    family = o.optString("family", "other"),
                    writable = o.optBoolean("writable"),
                    status = o.optString("status"),
                    permission = o.optString("permission").ifBlank { null },
                    areaId = if (o.has("areaId") && !o.isNull("areaId")) o.getInt("areaId") else null,
                    value = o.optString("value").ifBlank { null },
                    message = o.optString("message").ifBlank { null },
                    entity = o.optString("entity").ifBlank { null },
                )
            }
            ProbeReport(
                timestampMs = root.optLong("timestampMs"),
                integrationId = root.optString("integrationId"),
                catalogSize = root.optInt("catalogSize"),
                results = list,
            ).also { cache.set(it) }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "OaaProbe"

        fun familyOf(name: String): String {
            val n = name.uppercase()
            return when {
                n.contains("OBD2") || n.startsWith("OBD_") -> "obd2"
                n.startsWith("SCENE_") || n.contains("NAP_MODE") || n.contains("SPACE_CAPSULE") ||
                    n.contains("PARKING_COMFORT") -> "scene"
                n.startsWith("LAMP_") || n.contains("LIGHT_CONTROL") ||
                    (n.contains("LAMP") && !n.contains("FAULT")) -> "light"
                n.contains("DMS") || n.contains("FCDA") || n.contains("DOOR_OPEN_WARN") ||
                    n.contains("RCTA") || n.contains("RCW") || n.contains("ELKA") ||
                    n.contains("IDAS") || n.contains("INTELLIGENT_DRIVING") ||
                    n.contains("LANE") || n.contains("AEB") || n.contains("COLLISION") ||
                    n.contains("CROSS_TRAFFIC") || n.contains("FCW") || n.startsWith("PAS_") -> "adas"
                n.contains("SUNROOF") || n.contains("WINDOW") -> "window"
                n.contains("LOCK") || n.contains("UNLOCK") -> "lock"
                n.contains("HUD") -> "hud"
                n.contains("AMBIENCE") -> "ambience"
                n.contains("BRIGHTNESS") || n.contains("BACKLIGHT") -> "brightness"
                n.contains("SEAT") || n.contains("BELT") || n.contains("OCCUPANCY") -> "seat"
                n.contains("MIRROR") -> "mirror"
                n.startsWith("HYBRID_") -> "hybrid"
                n.startsWith("CHARGE_") -> "charge"
                n.startsWith("HVAC_") -> "hvac"
                n.startsWith("DM_") || n.contains("DRIVE_MODE") -> "drive"
                n.startsWith("INFO_") || n.startsWith("PERF_") || n.contains("GEAR") ||
                    n.contains("IGNITION") || n.contains("RANGE") || n.contains("BATTERY") -> "telemetry"
                n.startsWith("SETTING_FUNC_") || n.startsWith("SETTING_") -> "setting"
                else -> "other"
            }
        }

        private fun redact(name: String, value: String): String {
            if (name.contains("VIN", ignoreCase = true)) {
                return if (value.length < 8) "[redacted]" else value.take(3) + "****" + value.takeLast(4)
            }
            return value.take(80)
        }
    }
}

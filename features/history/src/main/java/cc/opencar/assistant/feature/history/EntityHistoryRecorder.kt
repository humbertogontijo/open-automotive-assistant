package cc.opencar.assistant.feature.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import cc.opencar.assistant.api.VehicleSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Local entity history: store a sample only when the value changes.
 * No periodic heartbeat duplicates.
 */
class EntityHistoryRecorder(
    context: Context,
    private val session: VehicleSession,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
) {
    private val db = HistoryDb(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val lastValues = ConcurrentHashMap<String, String>()

    fun start() {
        if (job != null) return
        job = scope.launch {
            launch {
                while (isActive) {
                    val cutoff = System.currentTimeMillis() - retentionMs
                    runCatching {
                        db.writableDatabase.delete(TABLE, "ts<?", arrayOf(cutoff.toString()))
                    }
                    delay(PURGE_EVERY_MS)
                }
            }
            session.telemetry().collectLatest { snap ->
                val now = System.currentTimeMillis()
                record("sensor.gear", "home", "sensor", snap.gear?.toString(), now)
                record("sensor.speed", "home", "sensor", snap.speedKmh?.let { "%.0f".format(it) }, now)
                record(
                    "sensor.soc", "home", "sensor",
                    (snap.evBatteryPercent ?: snap.hybridSocPercent)?.let { "%.0f".format(it) },
                    now,
                )
                record("sensor.fuel", "home", "sensor", snap.fuelPercent?.let { "%.0f".format(it) }, now)
                record("sensor.range", "home", "sensor", snap.rangeKm?.let { "%.0f".format(it) }, now)
                record("sensor.range_ev", "home", "sensor", snap.rangeEvKm?.let { "%.0f".format(it) }, now)
                record("sensor.range_fuel", "home", "sensor", snap.rangeFuelKm?.let { "%.0f".format(it) }, now)
                record("sensor.odometer", "home", "sensor", snap.odometerKm?.let { "%.0f".format(it) }, now)
                record("drivetrain.vehicle", "drive", "drivetrain",
                    // Prefer raw enum token so the UI can valueMap → i18n.
                    // Fall back to legacy label keys (opt.drive_mode.*) already in DB.
                    snap.extras["driveModeRaw"] ?: snap.driveMode, now)
                record(
                    "climate.cabin", "controls", "climate",
                    snap.hvacTempC?.let { "%.1f".format(it) },
                    now,
                )
                record(
                    "sensor.temp_ambient", "controls", "climate",
                    snap.tempAmbientC?.let { "%.0f".format(it) },
                    now,
                )
                record(
                    "sensor.battery_temp", "energy", "energy",
                    snap.batteryTempC?.let { "%.0f".format(it) },
                    now,
                )
                record(
                    "charge_current", "energy", "charging",
                    snap.chargeCurrentA?.let { "%.1f".format(it) },
                    now,
                )
                record(
                    "sensor.charge_plug", "energy", "charging",
                    snap.chargePlugConnected?.let { if (it) "1" else "0" },
                    now,
                )
                record(
                    "sensor.hybrid_soc", "energy", "energy",
                    snap.hybridSocPercent?.let { "%.0f".format(it) },
                    now,
                )
                record(
                    "sensor.avg_energy", "energy", "energy",
                    snap.avgEnergyKwh100km?.let { "%.1f".format(it) },
                    now,
                )
                record(
                    "sensor.avg_fuel", "energy", "energy",
                    snap.avgFuelL100km?.let { "%.1f".format(it) },
                    now,
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun query(
        entityId: String,
        startMs: Long,
        endMs: Long,
        limit: Int = 2000,
    ): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        db.readableDatabase.query(
            TABLE,
            arrayOf("ts", "value", "family", "group_id"),
            "entity_id=? AND ts>=? AND ts<=?",
            arrayOf(entityId, startMs.toString(), endMs.toString()),
            null,
            null,
            "ts ASC",
            limit.toString(),
        ).use { c ->
            val iTs = c.getColumnIndexOrThrow("ts")
            val iVal = c.getColumnIndexOrThrow("value")
            val iFam = c.getColumnIndexOrThrow("family")
            val iGrp = c.getColumnIndexOrThrow("group_id")
            while (c.moveToNext()) {
                out += mapOf(
                    "ts" to c.getLong(iTs),
                    "value" to c.getString(iVal),
                    "family" to c.getString(iFam),
                    "group" to c.getString(iGrp),
                )
            }
        }
        return out
    }

    fun entitiesTracked(): List<String> {
        val out = mutableListOf<String>()
        db.readableDatabase.rawQuery(
            "SELECT DISTINCT entity_id FROM $TABLE ORDER BY entity_id",
            null,
        ).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    /** Drop all rows (e.g. after switching to change-only recording). */
    fun clearAll(): Int = try {
        db.writableDatabase.delete(TABLE, null, null)
    } catch (t: Throwable) {
        Log.w(TAG, "clear failed: ${t.message}")
        0
    }

    private fun record(entityId: String, group: String, family: String, value: String?, now: Long) {
        if (value.isNullOrBlank()) return
        val shouldWrite = synchronized(this) {
            val prev = lastValues[entityId]
            if (value == prev) return@synchronized false
            lastValues[entityId] = value
            true
        }
        if (!shouldWrite) return
        try {
            val cv = ContentValues().apply {
                put("entity_id", entityId)
                put("group_id", group)
                put("family", family)
                put("ts", now)
                put("value", value)
            }
            db.writableDatabase.insert(TABLE, null, cv)
        } catch (t: Throwable) {
            Log.w(TAG, "record failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "EntityHistory"
        private const val TABLE = "entity_history"
        const val DEFAULT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val PURGE_EVERY_MS = 60 * 60 * 1000L
    }

    private class HistoryDb(context: Context) : SQLiteOpenHelper(
        context,
        "oaa_entity_history.db",
        null,
        1,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  entity_id TEXT NOT NULL,
                  group_id TEXT NOT NULL,
                  family TEXT NOT NULL,
                  ts INTEGER NOT NULL,
                  value TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_hist_entity_ts ON $TABLE(entity_id, ts)")
            db.execSQL("CREATE INDEX idx_hist_family_ts ON $TABLE(family, ts)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}

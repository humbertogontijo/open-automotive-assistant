package cc.opencar.assistant.feature.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import cc.opencar.assistant.api.TelemetrySnapshot
import cc.opencar.assistant.api.VehicleSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Local entity history: store a sample only when the value changes.
 * No periodic heartbeat duplicates.
 */
class EntityHistoryRecorder(
    context: Context,
    private val session: VehicleSession,
    private val retentionMs: Long = DEFAULT_RETENTION_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val db = HistoryDb(context)
    private var job: Job? = null
    private val lastValues = HashMap<String, String>()

    private class Sample(val entityId: String, val group: String, val family: String, val value: String?)

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
            session.telemetry().collectLatest { snap -> record(samples(snap), System.currentTimeMillis()) }
        }
    }

    private fun samples(snap: TelemetrySnapshot) = listOf(
        Sample("sensor.gear", "home", "sensor", snap.gear?.toString()),
        Sample("sensor.speed", "home", "sensor", r0(snap.speedKmh)),
        Sample("sensor.soc", "home", "sensor", r0(snap.evBatteryPercent ?: snap.hybridSocPercent)),
        Sample("sensor.fuel", "home", "sensor", r0(snap.fuelPercent)),
        Sample("sensor.range", "home", "sensor", r0(snap.rangeKm)),
        Sample("sensor.range_ev", "home", "sensor", r0(snap.rangeEvKm)),
        Sample("sensor.range_fuel", "home", "sensor", r0(snap.rangeFuelKm)),
        Sample("sensor.odometer", "home", "sensor", r0(snap.odometerKm)),
        // Prefer raw enum token so the UI can valueMap → i18n.
        // Fall back to legacy label keys (opt.drive_mode.*) already in DB.
        Sample("drivetrain.vehicle", "drive", "drivetrain", snap.extras["driveModeRaw"] ?: snap.driveMode),
        Sample("climate.cabin", "controls", "climate", r1(snap.hvacTempC)),
        Sample("sensor.temp_ambient", "controls", "climate", r0(snap.tempAmbientC)),
        Sample("sensor.battery_temp", "energy", "energy", r0(snap.batteryTempC)),
        Sample("charge_current", "energy", "charging", r1(snap.chargeCurrentA)),
        Sample("sensor.charge_plug", "energy", "charging", snap.chargePlugConnected?.let { if (it) "1" else "0" }),
        Sample("sensor.hybrid_soc", "energy", "energy", r0(snap.hybridSocPercent)),
        Sample("sensor.avg_energy", "energy", "energy", r1(snap.avgEnergyKwh100km)),
        Sample("sensor.avg_fuel", "energy", "energy", r1(snap.avgFuelL100km)),
    )

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

    private fun record(samples: List<Sample>, now: Long) {
        val changed = synchronized(lastValues) {
            samples.filter { s ->
                val value = s.value
                if (value.isNullOrBlank() || lastValues[s.entityId] == value) return@filter false
                lastValues[s.entityId] = value
                true
            }
        }
        if (changed.isEmpty()) return
        try {
            val w = db.writableDatabase
            w.beginTransaction()
            try {
                val cv = ContentValues(5)
                for (s in changed) {
                    cv.put("entity_id", s.entityId)
                    cv.put("group_id", s.group)
                    cv.put("family", s.family)
                    cv.put("ts", now)
                    cv.put("value", s.value)
                    w.insert(TABLE, null, cv)
                }
                w.setTransactionSuccessful()
            } finally {
                w.endTransaction()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "record failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "EntityHistory"
        private const val TABLE = "entity_history"
        const val DEFAULT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val PURGE_EVERY_MS = 60 * 60 * 1000L

        private fun r0(v: Float?): String? = v?.roundToInt()?.toString()

        private fun r1(v: Float?): String? = v?.let { (it * 10).roundToLong() / 10.0 }?.toString()
    }

    private class HistoryDb(context: Context) : SQLiteOpenHelper(
        context,
        "oaa_entity_history.db",
        null,
        1,
    ) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

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

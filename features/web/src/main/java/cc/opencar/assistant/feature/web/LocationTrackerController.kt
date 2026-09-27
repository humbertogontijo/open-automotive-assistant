package cc.opencar.assistant.feature.web

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import cc.opencar.assistant.api.EntityContract
import cc.opencar.assistant.api.EntityType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vehicle GPS presence as a Home Assistant–style [device_tracker] under My Vehicle.
 * State is `home` / `not_home` vs a configurable home zone in [prefs].
 */
class LocationTrackerController(
    private val context: Context,
    private val prefs: SharedPreferences,
) {
    private val locationManager: LocationManager?
        get() = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun homeSnapshot(): Map<String, Any?> {
        val lat = prefs.getString(PREF_HOME_LAT, null)?.toDoubleOrNull()
        val lon = prefs.getString(PREF_HOME_LON, null)?.toDoubleOrNull()
        val radius = prefs.getFloat(PREF_HOME_RADIUS_M, DEFAULT_RADIUS_M)
        return mapOf(
            "homeLat" to lat,
            "homeLon" to lon,
            "homeRadiusM" to radius.toDouble(),
            "configured" to (lat != null && lon != null),
        )
    }

    fun setHome(lat: Double, lon: Double, radiusM: Float? = null): Map<String, Any?> {
        val radius = (radiusM ?: prefs.getFloat(PREF_HOME_RADIUS_M, DEFAULT_RADIUS_M))
            .coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
        prefs.edit()
            .putString(PREF_HOME_LAT, lat.toString())
            .putString(PREF_HOME_LON, lon.toString())
            .putFloat(PREF_HOME_RADIUS_M, radius)
            .apply()
        return mapOf("ok" to true) + homeSnapshot()
    }

    fun setHomeRadius(radiusM: Float): Map<String, Any?> {
        val radius = radiusM.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M)
        prefs.edit().putFloat(PREF_HOME_RADIUS_M, radius).apply()
        return mapOf("ok" to true) + homeSnapshot()
    }

    fun clearHome(): Map<String, Any?> {
        prefs.edit()
            .remove(PREF_HOME_LAT)
            .remove(PREF_HOME_LON)
            .apply()
        return mapOf("ok" to true) + homeSnapshot()
    }

    /** Snapshot current GPS into home prefs. */
    fun setHomeHere(): Map<String, Any?> {
        val fix = currentLocation()
            ?: return mapOf("ok" to false, "error" to "location unavailable") + homeSnapshot()
        return setHome(fix.latitude, fix.longitude)
    }

    fun read(id: String): String? {
        if (id != ID) return null
        return presence()?.state
    }

    fun entityMaps(): List<Map<String, Any?>> {
        val presence = presence()
        val state = presence?.state
        val status = when {
            !hasLocationPermission() -> "denied"
            presence == null -> "unavailable"
            else -> "ok"
        }
        val home = homeSnapshot()
        val attrs = linkedMapOf<String, Any?>().apply {
            presence?.location?.let { loc ->
                put("latitude", loc.latitude)
                put("longitude", loc.longitude)
                if (loc.hasAccuracy()) put("gps_accuracy", loc.accuracy.toDouble())
            }
            put("source_type", "gps")
            home["homeLat"]?.let { put("home_latitude", it) }
            home["homeLon"]?.let { put("home_longitude", it) }
            home["homeRadiusM"]?.let { put("home_radius_m", it) }
        }
        return listOf(
            EntityContract.enrich(
                mapOf(
                    "id" to ID,
                    "group" to "vehicle",
                    "domain" to EntityType.DEVICE_TRACKER.id,
                    "labelKey" to "device_tracker.vehicle",
                    "hintKey" to "device_tracker.vehicle.hint",
                    "input" to "sensor",
                    "icon" to "drive",
                    "writable" to false,
                    "value" to state,
                    "valueMapId" to "device_tracker",
                    "options" to listOf(
                        mapOf(
                            "value" to STATE_HOME,
                            "labelKey" to "device_tracker.home",
                        ),
                        mapOf(
                            "value" to STATE_NOT_HOME,
                            "labelKey" to "device_tracker.not_home",
                        ),
                    ),
                    "status" to status,
                    "needsPrivilege" to false,
                    "stale" to false,
                    "history" to false,
                    "attributes" to attrs,
                ),
            ),
        )
    }

    private data class Presence(val state: String, val location: Location)

    private fun presence(): Presence? {
        val fix = currentLocation() ?: return null
        val homeLat = prefs.getString(PREF_HOME_LAT, null)?.toDoubleOrNull()
        val homeLon = prefs.getString(PREF_HOME_LON, null)?.toDoubleOrNull()
        if (homeLat == null || homeLon == null) {
            return Presence(STATE_NOT_HOME, fix)
        }
        val radius = prefs.getFloat(PREF_HOME_RADIUS_M, DEFAULT_RADIUS_M)
        val dist = haversineMeters(fix.latitude, fix.longitude, homeLat, homeLon)
        val state = if (dist <= radius) STATE_HOME else STATE_NOT_HOME
        return Presence(state, fix)
    }

    private fun currentLocation(): Location? {
        if (!hasLocationPermission()) return null
        val lm = locationManager ?: return null
        return try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
            var best: Location? = null
            for (p in providers) {
                if (!lm.isProviderEnabled(p)) continue
                @Suppress("MissingPermission")
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.time > best.time) best = loc
            }
            best
        } catch (t: SecurityException) {
            Log.w(TAG, "location denied: ${t.message}")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "location failed: ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "LocationTracker"
        const val ID = "device_tracker.vehicle"
        const val STATE_HOME = "home"
        const val STATE_NOT_HOME = "not_home"
        const val PREF_HOME_LAT = "home_lat"
        const val PREF_HOME_LON = "home_lon"
        const val PREF_HOME_RADIUS_M = "home_radius_m"
        const val DEFAULT_RADIUS_M = 100f
        const val MIN_RADIUS_M = 20f
        const val MAX_RADIUS_M = 5_000f

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}

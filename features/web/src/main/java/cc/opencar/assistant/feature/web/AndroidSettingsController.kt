package cc.opencar.assistant.feature.web

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import cc.opencar.assistant.api.AndroidVolumeGroup
import cc.opencar.assistant.api.EntityContract
import cc.opencar.assistant.api.EntityType
import cc.opencar.assistant.feature.memory.ExternalSettingsApplier
import kotlinx.coroutines.CoroutineScope

/**
 * HU radios, brightness, and HA-style media_player for automations.
 *
 * Product domains are platform-agnostic ([EntityType.SWITCH] / [EntityType.NUMBER] /
 * [EntityType.MEDIA_PLAYER]) — see `docs/domains.md`. This controller talks to Android
 * Settings / CarAudio; the catalog ids it emits are portable (`switch.wifi`, …).
 *
 * Cabin volume groups come from `platform.json` → `android.volumeGroups` (via [volumeGroups]).
 * The `android` JSON key is the AAOS transport fragment, not a product domain.
 */
class AndroidSettingsController(
    private val context: Context,
    private val volumeGroups: List<AndroidVolumeGroup> = emptyList(),
) : ExternalSettingsApplier {
    private val wifi: WifiManager?
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val bluetooth: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val mediaGroupId: Int =
        volumeGroups.firstOrNull { it.keyWritable }?.groupId
            ?: volumeGroups.firstOrNull { it.groupId == CarAudioVolume.GROUP_MEDIA }?.groupId
            ?: CarAudioVolume.GROUP_MEDIA

    private val mediaCabinVolume by lazy {
        val def = volumeGroups.firstOrNull { it.groupId == mediaGroupId }
        CarAudioVolume(
            context,
            mediaGroupId,
            keyWritable = def?.keyWritable ?: true,
            settingsKey = def?.settingsKey ?: "android.car.VOLUME_GROUP/$mediaGroupId",
        )
    }
    private val cabinVolumes by lazy {
        volumeGroups.associate { g ->
            g.entityId to CarAudioVolume(
                context,
                g.groupId,
                keyWritable = g.keyWritable,
                settingsKey = g.settingsKey,
            )
        }
    }

    val cabinVolIds: Set<String> = volumeGroups.map { it.entityId }.toSet()
    val allIds: Set<String> = BASE_IDS + cabinVolIds
    val writableIds: Set<String> =
        BASE_IDS + volumeGroups.filter { it.keyWritable }.map { it.entityId }

    /**
     * One-shot MediaSession seed. Live playback/metadata come only from
     * [OaaNotificationListener] callbacks — no backup poll (hides silent listener).
     */
    fun start(@Suppress("UNUSED_PARAMETER") scope: CoroutineScope) {
        val snap = runCatching { mediaSnapshot() }.getOrNull()
        Log.i(
            TAG,
            "media seed playback=${snap?.playback} listener=${OaaNotificationListener.instance != null}",
        )
    }

    fun status(): Map<String, Any?> {
        val w = wifi
        val b = bluetooth
        val media = mediaSnapshot()
        return mapOf(
            "wifiEnabled" to (w?.isWifiEnabled == true),
            "wifiAvailable" to (w != null),
            "bluetoothEnabled" to (b?.isEnabled == true),
            "bluetoothAvailable" to (b != null),
            "brightness" to brightnessOrNull(),
            "canWriteSettings" to Settings.System.canWrite(context),
            "mediaListenerEnabled" to isMediaListenerEnabled(),
            "media" to mapOf(
                "playback" to media.playback,
                "title" to media.title,
                "artist" to media.artist,
                "album" to media.album,
                "package" to media.packageName,
            ),
        )
    }

    fun setWifi(enable: Boolean): Map<String, Any?> {
        val w = wifi ?: return mapOf("ok" to false, "error" to "wifi unavailable", "status" to status())
        return try {
            @Suppress("DEPRECATION")
            val ok = w.setWifiEnabled(enable)
            mapOf("ok" to ok, "status" to status())
        } catch (t: Throwable) {
            Log.w(TAG, "setWifi failed: ${t.message}")
            mapOf("ok" to false, "error" to (t.message ?: "failed"), "status" to status())
        }
    }

    fun setBluetooth(enable: Boolean): Map<String, Any?> {
        val b = bluetooth ?: return mapOf("ok" to false, "error" to "bluetooth unavailable", "status" to status())
        return try {
            @Suppress("DEPRECATION")
            val ok = if (enable) b.enable() else b.disable()
            mapOf("ok" to ok, "status" to status())
        } catch (t: Throwable) {
            Log.w(TAG, "setBluetooth failed: ${t.message}")
            mapOf("ok" to false, "error" to (t.message ?: "failed"), "status" to status())
        }
    }

    fun setBrightness(level: Int): Map<String, Any?> {
        if (!Settings.System.canWrite(context)) {
            return mapOf(
                "ok" to false,
                "error" to "WRITE_SETTINGS not granted",
                "status" to status(),
            )
        }
        val clamped = level.coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX)
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            val ok = Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                clamped,
            )
            mapOf("ok" to ok, "brightness" to clamped, "status" to status())
        } catch (t: Throwable) {
            Log.w(TAG, "setBrightness failed: ${t.message}")
            mapOf("ok" to false, "error" to (t.message ?: "failed"), "status" to status())
        }
    }

    /** Accepts HA-style states, transport commands, or volume (`volume:N` / up / down / bare int). */
    private fun applyMediaPlayer(value: String): Boolean {
        val v = value.trim().lowercase().replace('-', '_')
        when {
            v == "volume_up" || v == "vol_up" -> return mediaCabinVolume.adjust(1)
            v == "volume_down" || v == "vol_down" -> return mediaCabinVolume.adjust(-1)
            v.startsWith("volume_level:") || v.startsWith("volume_level_") -> {
                val level = v.removePrefix("volume_level").trimStart(':', '_').toFloatOrNull()
                    ?: return false
                val st = mediaCabinVolume.read()
                val max = st.max
                val min = st.min
                val idx = (min + level.coerceIn(0f, 1f) * (max - min)).toInt()
                return mediaCabinVolume.set(idx)
            }
            v.startsWith("volume:") || v.startsWith("volume_") -> {
                val rest = v.removePrefix("volume").trimStart(':', '_')
                if (rest == "up" || rest == "down") return false
                val n = rest.toIntOrNull() ?: return false
                return mediaCabinVolume.set(n)
            }
            v.toIntOrNull() != null -> return mediaCabinVolume.set(v.toInt())
        }
        return sendMediaKey(v)
    }

    /** Play / pause / next / previous / stop via media key inject. */
    fun mediaCommand(command: String): Map<String, Any?> {
        val cmd = command.trim().lowercase().replace('-', '_')
        val ok = sendMediaKey(cmd)
        return mapOf(
            "ok" to ok,
            "command" to command,
            "error" to if (ok) null else "transport failed: $command",
            "status" to status(),
        )
    }

    fun setPlayback(state: String): Map<String, Any?> {
        val target = state.trim().lowercase()
        val cmd = when (target) {
            "playing", "play", "1", "on", "true" -> "play"
            "paused", "pause", "0", "off", "false" -> "pause"
            "idle", "stop" -> "stop"
            else -> return mapOf("ok" to false, "error" to "unknown playback: $state", "status" to status())
        }
        return mediaCommand(cmd)
    }

    private fun sendMediaKey(cmd: String): Boolean {
        val key = mediaKeyCode(cmd) ?: return false
        return KeyEventInject.send(context, key)
    }

    private fun mediaKeyCode(cmd: String): Int? = when (cmd) {
        "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
        "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
        "play_pause", "playpause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        "next", "skip_next" -> KeyEvent.KEYCODE_MEDIA_NEXT
        "previous", "prev", "skip_previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
        "playing", "on" -> KeyEvent.KEYCODE_MEDIA_PLAY
        "paused" -> KeyEvent.KEYCODE_MEDIA_PAUSE
        "idle", "off" -> KeyEvent.KEYCODE_MEDIA_STOP
        else -> null
    }

    /**
     * Best-effort enable of [OaaNotificationListener] via Secure settings
     * (works from shell / privileged; no-op for normal apps).
     *
     * Drops any other component of this package from the list so a renamed
     * listener cannot leave a dead entry that still counts as "enabled".
     */
    fun tryEnableMediaListener(): Boolean {
        val target = ComponentName(context, OaaNotificationListener::class.java).flattenToString()
        return try {
            val cr = context.contentResolver
            val key = "enabled_notification_listeners"
            val parts = Settings.Secure.getString(cr, key).orEmpty()
                .split(':')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableSet()
            var changed = false
            val stale = parts.filter {
                it.startsWith("${context.packageName}/") && it != target
            }
            if (stale.isNotEmpty()) {
                parts.removeAll(stale.toSet())
                changed = true
            }
            if (parts.add(target)) changed = true
            if (changed) {
                Settings.Secure.putString(cr, key, parts.joinToString(":"))
            }
            isMediaListenerComponentEnabled()
        } catch (t: Throwable) {
            Log.d(TAG, "tryEnableMediaListener: ${t.message}")
            false
        }
    }

    override suspend fun apply(id: String, value: String): Boolean {
        return when (id) {
            ID_WIFI -> {
                val on = value == "1" || value.equals("true", true) || value.equals("on", true)
                setWifi(on)["ok"] == true
            }
            ID_BT -> {
                val on = value == "1" || value.equals("true", true) || value.equals("on", true)
                setBluetooth(on)["ok"] == true
            }
            ID_BRIGHTNESS -> {
                val n = value.toIntOrNull() ?: return false
                setBrightness(n)["ok"] == true
            }
            ID_MEDIA_PLAYER -> applyMediaPlayer(value)
            in cabinVolIds -> {
                val n = value.toIntOrNull() ?: return false
                cabinVolumes[id]?.set(n) == true
            }
            else -> false
        }
    }

    override suspend fun read(id: String): String? {
        return when (id) {
            ID_WIFI -> if (wifi?.isWifiEnabled == true) "1" else "0"
            ID_BT -> if (bluetooth?.isEnabled == true) "1" else "0"
            ID_BRIGHTNESS -> brightnessOrNull()?.toString()
            ID_MEDIA_PLAYER -> mediaSnapshot().playback
            in cabinVolIds ->
                cabinVolumes[id]?.readOrNull()?.current?.toString()
            else -> null
        }
    }

    fun entityMaps(
        persist: Map<String, Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        val st = status()
        val canWrite = st["canWriteSettings"] == true
        val brightness = brightnessOrNull()
        val media = mediaSnapshot()

        val wifiBt = listOf(
            boolEntity(
                id = ID_WIFI,
                labelKey = "control.${ID_WIFI}",
                hintKey = "control.${ID_WIFI}.hint",
                enabled = st["wifiEnabled"] == true,
                available = st["wifiAvailable"] == true,
                pin = persist[ID_WIFI],
                group = "connect",
            ),
            boolEntity(
                id = ID_BT,
                labelKey = "control.${ID_BT}",
                hintKey = "control.${ID_BT}.hint",
                enabled = st["bluetoothEnabled"] == true,
                available = st["bluetoothAvailable"] == true,
                pin = persist[ID_BT],
                group = "connect",
            ),
        )

        val brightnessEntity = EntityContract.enrich(
            mapOf(
                "id" to ID_BRIGHTNESS,
                "group" to "display",
                "domain" to EntityType.NUMBER.id,
                "labelKey" to "control.${ID_BRIGHTNESS}",
                "hintKey" to "control.${ID_BRIGHTNESS}.hint",
                "input" to "int",
                "icon" to "display",
                "writable" to canWrite,
                "value" to (brightness?.toString() ?: "0"),
                "min" to BRIGHTNESS_MIN,
                "max" to BRIGHTNESS_MAX,
                "step" to 5,
                "status" to if (brightness != null) "ok" else if (canWrite) "ok" else "denied",
                "needsPrivilege" to !canWrite,
                "stale" to false,
                "history" to false,
                "persistEnabled" to (persist[ID_BRIGHTNESS]?.get("enabled") == true),
                "persistValue" to (persist[ID_BRIGHTNESS]?.get("value") as? String),
            ),
        )

        val playbackOpts = listOf(
            mapOf("value" to "playing", "labelKey" to "media_player.playing"),
            mapOf("value" to "paused", "labelKey" to "media_player.paused"),
            mapOf("value" to "idle", "labelKey" to "media_player.idle"),
        )
        val commandOpts = listOf(
            mapOf("value" to "play", "labelKey" to "media_player.play"),
            mapOf("value" to "pause", "labelKey" to "media_player.pause"),
            mapOf("value" to "play_pause", "labelKey" to "media_player.play_pause"),
            mapOf("value" to "next", "labelKey" to "media_player.next"),
            mapOf("value" to "previous", "labelKey" to "media_player.previous"),
            mapOf("value" to "stop", "labelKey" to "media_player.stop"),
        )
        // Always expose a volume range for the card UI (CarVolumeGroup 0).
        val vol = mediaCabinVolume.read()
        val mediaAttrs = linkedMapOf<String, Any?>().apply {
            media.title?.let { put("media_title", it) }
            media.artist?.let { put("media_artist", it) }
            media.album?.let { put("media_album", it) }
            media.packageName?.let { put("app_id", it) }
            put("listener_enabled", isMediaListenerEnabled())
            // Pause|Prev|Next|Play|Stop|Volume — HA supported_features subset.
            put("supported_features", MEDIA_FEATURES)
            put("options", playbackOpts)
            put("commands", commandOpts)
            put("volume", vol.current)
            put("volume_min", vol.min)
            put("volume_max", vol.max)
            val span = (vol.max - vol.min).coerceAtLeast(1)
            put("volume_level", (vol.current - vol.min).toDouble() / span)
        }
        val mediaPlayerEntity = EntityContract.enrich(
            mapOf(
                "id" to ID_MEDIA_PLAYER,
                "group" to "sound",
                "domain" to EntityType.MEDIA_PLAYER.id,
                "labelKey" to "control.${ID_MEDIA_PLAYER}",
                "hintKey" to "control.${ID_MEDIA_PLAYER}.hint",
                "input" to "media_player",
                "icon" to "sound",
                "writable" to true,
                "value" to media.playback,
                "valueMapId" to "media_player",
                "mediaTitle" to media.title,
                "mediaArtist" to media.artist,
                "mediaAlbum" to media.album,
                "volume" to vol.current,
                "volumeMin" to vol.min,
                "volumeMax" to vol.max,
                "min" to vol.min,
                "max" to vol.max,
                "step" to 1,
                "options" to playbackOpts,
                "status" to "ok",
                "needsPrivilege" to (!isMediaListenerEnabled() && media.title == null),
                "stale" to false,
                "history" to false,
                "attributes" to mediaAttrs,
                "persistEnabled" to (persist[ID_MEDIA_PLAYER]?.get("enabled") == true),
                "persistValue" to (persist[ID_MEDIA_PLAYER]?.get("value") as? String),
            ),
        )

        val cabinVolEntities = volumeGroups.map { def ->
            val cv = cabinVolumes.getValue(def.entityId)
            val stVol = cv.read()
            val pin = persist[def.entityId]
            EntityContract.enrich(
                mapOf(
                    "id" to def.entityId,
                    "group" to "sound",
                    "domain" to EntityType.NUMBER.id,
                    "labelKey" to "control.${def.entityId}",
                    "hintKey" to "control.${def.entityId}.hint",
                    "input" to "int",
                    "icon" to "sound",
                    "writable" to def.keyWritable,
                    "value" to stVol.current.toString(),
                    "min" to stVol.min,
                    "max" to stVol.max,
                    "step" to 1,
                    "status" to "ok",
                    "needsPrivilege" to !def.keyWritable,
                    "stale" to false,
                    "history" to false,
                    "persistEnabled" to (pin?.get("enabled") == true),
                    "persistValue" to (pin?.get("value") as? String),
                ),
            )
        }

        return wifiBt + listOf(brightnessEntity, mediaPlayerEntity) + cabinVolEntities
    }

    private fun boolEntity(
        id: String,
        labelKey: String,
        hintKey: String,
        enabled: Boolean,
        available: Boolean,
        pin: Map<String, Any?>?,
        group: String,
    ): Map<String, Any?> {
        val value = if (enabled) "1" else "0"
        val pinEnabled = pin?.get("enabled") == true
        val pVal = pin?.get("value") as? String
        return EntityContract.enrich(
            mapOf(
                "id" to id,
                "group" to group,
                "domain" to EntityType.SWITCH.id,
                "labelKey" to labelKey,
                "hintKey" to hintKey,
                "input" to "bool",
                "icon" to "system",
                "writable" to available,
                "value" to value,
                "binary" to true,
                "status" to if (available) "ok" else "unavailable",
                "needsPrivilege" to false,
                "stale" to false,
                "history" to false,
                "persistEnabled" to pinEnabled,
                "persistValue" to pVal,
            ),
        )
    }

    private fun mediaSnapshot(): MediaSnapshot =
        OaaNotificationListener.refreshFromManager(context)

    private fun brightnessOrNull(): Int? = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (_: Throwable) {
        null
    }

    private fun isMediaListenerEnabled(): Boolean {
        if (OaaNotificationListener.instance != null) return true
        return isMediaListenerComponentEnabled()
    }

    /** True when Secure settings list the current [OaaNotificationListener] component. */
    private fun isMediaListenerComponentEnabled(): Boolean {
        val target = ComponentName(context, OaaNotificationListener::class.java).flattenToString()
        val cur = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return cur.split(':').any { it.trim() == target }
    }

    companion object {
        private const val TAG = "AndroidSettings"
        const val ID_WIFI = "switch.wifi"
        const val ID_BT = "switch.bluetooth"
        const val ID_BRIGHTNESS = "number.brightness"
        /** HU active media session — HA-style media_player domain. */
        const val ID_MEDIA_PLAYER = "media_player.vehicle"

        private const val BRIGHTNESS_MIN = 1
        private const val BRIGHTNESS_MAX = 255
        /** Pause(1)|Prev(16)|Next(32)|Stop(4096)|Play(16384)|Volume(4) — HA subset. */
        private const val MEDIA_FEATURES = 1 + 4 + 16 + 32 + 4096 + 16384

        private val BASE_IDS = setOf(ID_WIFI, ID_BT, ID_BRIGHTNESS, ID_MEDIA_PLAYER)
    }
}

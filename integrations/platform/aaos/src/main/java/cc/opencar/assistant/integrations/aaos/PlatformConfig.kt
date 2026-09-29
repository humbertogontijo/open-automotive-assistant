package cc.opencar.assistant.integrations.aaos

import android.content.Context
import cc.opencar.assistant.api.AndroidVolumeGroup
import cc.opencar.assistant.api.CameraRoleConfig
import cc.opencar.assistant.api.CameraSource
import cc.opencar.assistant.api.Capability
import cc.opencar.assistant.api.CatalogEntry
import cc.opencar.assistant.api.EntityRegistry
import cc.opencar.assistant.api.PlatformVariant
import cc.opencar.assistant.api.ValueRange
import cc.opencar.assistant.api.VehicleProperty
import org.json.JSONArray
import org.json.JSONObject

/**
 * Declarative platform definition loaded from `platform.json` in an integration's assets.
 *
 * Shared family parents live under `platform/<family>/platform.json` (e.g. aaos) and are
 * pulled in via `"extends": ["aaos"]`.
 *
 * Identity layers:
 * - **SKU** (`models/<id>.json`) — build/market trim from `ro.product.device` (e.g. `p145_eu`);
 *   optional property allowlist from Lab probe (drives which catalog keys get product bindings).
 * - **Profile** (`profiles/<id>.json`) — energy/capability surface (`phev` / `bev`);
 *   owns `detect`, `extraCapabilities`, and label (not property bindings).
 *
 * Call [forSelection] after SKU + profile detection.
 *
 * The aaos parent's `android` block is an **AAOS HU settings transport**
 * (Settings keys + volumeGroups) — not a product entity domain. Product ids use
 * `switch.wifi` / `number.brightness` / `number.vol_*` (see `docs/domains.md`).
 */
data class PlatformConfig(
    val id: String,
    val displayName: String,
    val backend: String,
    val match: List<String>,
    val capabilities: Set<Capability>,
    val properties: List<PropertyDef> = emptyList(),
    val android: AndroidConfig = AndroidConfig(),
    val driveModeEnum: Map<Int, String> = emptyMap(),
    /** Camera2 id → surround role map (`platform.json` → `cameras`). */
    val cameras: List<CameraRoleConfig> = emptyList(),
    /** Build / market SKUs under models/. */
    val skus: List<SkuDef> = emptyList(),
    /** Energy / capability profiles under profiles/. */
    val profiles: List<ProfileDef> = emptyList(),
    val activeSkuId: String? = null,
    val activeProfileId: String? = null,
    /** Property ids carry the VehiclePropertyType byte (catalog dumped from the HU's VHAL). */
    val vhalTypedIds: Boolean = false,
) {
    /**
     * Build / market SKU (e.g. `p145_eu` from `antora1000_p145_eu`).
     *
     * @param matchDevice needles matched against [cc.opencar.assistant.api.DeviceFingerprint.device]
     *   / fingerprint (usually the full `ro.product.device` string)
     * @param propertyKeys optional Lab-probe allowlist of VHAL keys for this flash
     */
    data class SkuDef(
        val id: String,
        val label: String,
        val matchDevice: List<String> = emptyList(),
        val propertyKeys: Set<String>? = null,
    )

    /**
     * Energy / capability profile (e.g. `phev`, `bev`).
     *
     * Product property bindings come from the SKU allowlist via [forSelection]
     * (identity: VHAL key → entity).
     */
    data class ProfileDef(
        val id: String,
        val label: String,
        val extraCapabilities: Set<Capability> = emptySet(),
        val detect: String? = null,
    )

    data class Binding(val nativeId: Int, val areaId: Int = 0, val functionId: Int? = null)

    data class PropertyDef(
        val id: Int,
        val key: String,
        /** HU access as reported by the head unit's `CarPropertyConfig`: `r`, `w`, or `rw`. */
        val access: String = "r",
        val changeMode: String? = null,
        val areas: List<Int> = listOf(0),
        /** Product binding key once a profile is applied. */
        val entity: String? = null,
        val functionId: Int? = null,
        /** Native range (`min` / `max` / `step` / `values`); the web layer maps it to domain ranges. */
        val range: ValueRange? = null,
        /** Vector property exposed as one scalar slot. */
        val vector: VectorSlots? = null,
        /** HU-writable but kept off the product write allowlist (factory, power, CarConfig, …). */
        val writeLocked: Boolean = false,
    ) {
        val huWritable: Boolean get() = access == "w" || access == "rw"
        val canWrite: Boolean get() = huWritable && !writeLocked
    }

    /**
     * `"vector": {"read": 1, "write": [1, 4, 5]}` — reads element [read]; writes replace
     * every [write] element and keep the rest of the vector.
     */
    data class VectorSlots(val read: Int, val write: List<Int>)

    data class AndroidSettingDef(
        val settingsKey: String,
        val entity: String,
        val access: String = "rw",
    )

    data class VolumeGroupDef(
        val groupId: Int,
        val entity: String,
        val settingsKey: String? = null,
        val access: String = "r",
        val writeVia: String? = null,
    ) {
        fun toApi(): AndroidVolumeGroup = AndroidVolumeGroup(
            groupId = groupId,
            entityId = entity,
            settingsKey = settingsKey ?: "android.car.VOLUME_GROUP/$groupId",
            access = access,
            writeVia = writeVia,
        )
    }

    data class AndroidConfig(
        val volumeGroups: List<VolumeGroupDef> = emptyList(),
        val settings: List<AndroidSettingDef> = emptyList(),
    )

    /** Product binding key → native binding (from the applied profile). */
    val bindings: Map<String, Binding> by lazy {
        properties.mapNotNull { p ->
            val entity = p.entity ?: return@mapNotNull null
            entity to Binding(p.id, p.areas.firstOrNull() ?: 0, p.functionId)
        }.toMap()
    }

    /** Property key → declared native range. */
    val ranges: Map<String, ValueRange> by lazy {
        properties.mapNotNull { p -> p.range?.let { p.key to it } }.toMap()
    }

    /** Native id → vector slot mapping. */
    val vectorSlots: Map<Int, VectorSlots> by lazy {
        properties.mapNotNull { p -> p.vector?.let { p.id to it } }.toMap()
    }

    /** Product write allowlist (derived from [properties] with `access` `w` / `rw`). */
    val writableAllowlist: Set<Int> by lazy {
        properties.filter { it.canWrite }.map { it.id }.toSet()
    }

    /** Binding key → [VehicleProperty] map for session reads/writes. */
    fun propertyBindings(): Map<VehicleProperty, Pair<Int, Int>> {
        val out = LinkedHashMap<VehicleProperty, Pair<Int, Int>>(bindings.size)
        for ((key, binding) in bindings) {
            if (key.isBlank()) continue
            out[EntityRegistry.property(key)] = binding.nativeId to binding.areaId
        }
        return out
    }

    fun matchSku(device: cc.opencar.assistant.api.DeviceFingerprint): SkuDef? {
        for (sku in skus) {
            if (sku.matchDevice.isEmpty()) continue
            if (sku.matchDevice.any { needle ->
                    device.device.contains(needle, ignoreCase = true) ||
                        device.fingerprint.contains(needle, ignoreCase = true) ||
                        device.containsAny(needle)
                }
            ) {
                return sku
            }
        }
        return skus.firstOrNull { it.id == "default" } ?: skus.firstOrNull()
    }

    fun profileOrNull(id: String): ProfileDef? = profiles.firstOrNull { it.id == id }

    fun selectionVariant(sku: SkuDef?, profile: ProfileDef): PlatformVariant {
        val label = listOfNotNull(
            sku?.label?.takeIf { it.isNotBlank() && it != profile.label },
            profile.label,
        ).joinToString(" · ").ifBlank { profile.id }
        return PlatformVariant(
            id = profile.id,
            label = label,
            extraCapabilities = profile.extraCapabilities,
            skuId = sku?.id,
        )
    }

    /**
     * Apply SKU (optional) + profile. Binding key is always the VHAL [PropertyDef.key]
     * (identity) so atomics use the property key as entity id. Unbound catalog props
     * outside the SKU allowlist stay unbound; everything in-allowlist is product-visible.
     */
    fun forSelection(skuId: String?, profileId: String): PlatformConfig {
        val profile = profiles.firstOrNull { it.id == profileId }
            ?: profiles.firstOrNull { it.id == "default" }
            ?: return this
        val sku = skuId?.let { id -> skus.firstOrNull { it.id == id } }
        val allow = sku?.propertyKeys
        val nextProps = properties.map { p ->
            val inSku = allow == null || p.key in allow || p.key.startsWith("0x")
            // Identity bind everything in the SKU allowlist (or all when no allowlist).
            val entity = if (inSku) p.key else null
            p.copy(entity = entity)
        }
        if (activeProfileId == profile.id &&
            activeSkuId == sku?.id &&
            properties.zip(nextProps).all { (a, b) -> a.entity == b.entity }
        ) {
            return this
        }
        return copy(
            properties = nextProps,
            activeSkuId = sku?.id,
            activeProfileId = profile.id,
        )
    }

    fun catalogEntries(): List<CatalogEntry> =
        properties.map { p ->
            CatalogEntry(
                property = VehicleProperty(
                    namespace = "vhal",
                    key = p.key,
                    nativeId = p.id.toLong() and 0xffff_ffffL,
                    defaultAreaId = p.areas.firstOrNull() ?: 0,
                ),
                name = p.key,
                writable = p.canWrite,
                writeLocked = p.huWritable && p.writeLocked,
                vhalType = if (vhalTypedIds) (p.id ushr 16) and 0xff else null,
                areaIds = p.areas.ifEmpty { listOf(0) },
            )
        }

    fun androidVolumeGroups(): List<AndroidVolumeGroup> =
        android.volumeGroups.map { it.toApi() }



    /**
     * Bind available Camera2 ids to product roles from [cameras].
     * When [cameras] is empty, falls back to unlabeled enumeration (compat).
     * Role order for mosaic tiles: front, right, rear, left, then any extras.
     */
    fun resolveCameras(availableIds: Collection<String>): List<CameraSource> {
        if (cameras.isEmpty()) {
            return availableIds.mapIndexed { i, id ->
                CameraSource(
                    id = id,
                    label = "Camera $i ($id)",
                    cameraId = id,
                )
            }
        }
        val available = availableIds.toSet()
        val byRole = cameras.associateBy { it.role }
        val ordered = (CAMERA_ROLE_ORDER.mapNotNull { byRole[it] } +
            cameras.filter { it.role !in CAMERA_ROLE_ORDER_SET })
            .distinctBy { it.role }
        return ordered.filter { it.cameraId in available }.map { cfg ->
            val label = cfg.role.replaceFirstChar { c -> c.uppercase() }
            CameraSource(
                id = cfg.entityId,
                label = label,
                cameraId = cfg.cameraId,
                role = cfg.role,
            )
        }
    }

    companion object {
        val CAMERA_ROLE_ORDER = listOf("front", "right", "rear", "left")
        private val CAMERA_ROLE_ORDER_SET = CAMERA_ROLE_ORDER.toSet()

        fun load(context: Context, assetPath: String = "platform.json"): PlatformConfig {
            val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val cfg = parse(text) { name ->
                context.assets.open("platform/$name/platform.json").bufferedReader().use { it.readText() }
            }
            val skuIds = root.optJSONArray("models")?.toStringList().orEmpty()
            val profileIds = root.optJSONArray("profiles")?.toStringList().orEmpty()

            val skus = skuIds.mapNotNull { id ->
                runCatching {
                    val m = context.assets.open("models/$id.json").bufferedReader().use { it.readText() }
                    parseSku(m)
                }.getOrNull()
            }
            val profiles = profileIds.mapNotNull { id ->
                runCatching {
                    val m = context.assets.open("profiles/$id.json").bufferedReader().use { it.readText() }
                    parseProfile(m)
                }.getOrNull()
            }

            if (skus.isEmpty() && profiles.isEmpty()) return cfg

            val cleared = cfg.copy(
                skus = skus.ifEmpty {
                    listOf(SkuDef(id = "default", label = cfg.displayName, matchDevice = emptyList()))
                },
                profiles = profiles,
                properties = cfg.properties.map { it.copy(entity = null) },
            )
            val defaultSku = cleared.skus.firstOrNull()?.id
            val defaultProfile = cleared.profiles.firstOrNull()?.id ?: "default"
            return cleared.forSelection(defaultSku, defaultProfile)
        }

        fun parseSku(json: String): SkuDef {
            val o = JSONObject(json)
            val match = o.optJSONArray("matchDevice")?.toStringList()
                ?: o.optString("matchDevice", "").takeIf { it.isNotBlank() }?.let { listOf(it) }
                ?: emptyList()
            val propertyKeys = o.optJSONArray("properties")?.toStringList()?.toSet()
            return SkuDef(
                id = o.getString("id"),
                label = o.optString("label", o.getString("id")),
                matchDevice = match,
                propertyKeys = propertyKeys,
            )
        }

        fun parseProfile(json: String): ProfileDef {
            val o = JSONObject(json)
            val extra = o.optJSONArray("extraCapabilities")?.toStringList()
                ?.mapNotNull { runCatching { Capability.valueOf(it) }.getOrNull() }
                ?.toSet()
                ?: emptySet()
            return ProfileDef(
                id = o.getString("id"),
                label = o.optString("label", o.getString("id")),
                extraCapabilities = extra,
                detect = if (o.has("detect")) o.optString("detect") else null,
            )
        }

        /**
         * Parse a platform document. When [parentResolver] is set, resolves `"extends"` names
         * to JSON text (e.g. asset or filesystem) and merges parents left→right, then child.
         */
        fun parse(json: String, parentResolver: ((String) -> String)? = null): PlatformConfig {
            val root = JSONObject(json)
            val merged = if (parentResolver != null) {
                mergeExtends(root, parentResolver)
            } else {
                root
            }
            return fromMerged(merged)
        }

        fun mergeExtends(child: JSONObject, parentResolver: (String) -> String): JSONObject {
            val extends = child.optJSONArray("extends")?.toStringList() ?: emptyList()
            var acc = JSONObject()
            for (name in extends) {
                val parent = JSONObject(parentResolver(name))
                // Parents may themselves extend — resolve one level of nesting.
                val nested = parent.optJSONArray("extends")?.toStringList().orEmpty()
                if (nested.isNotEmpty()) {
                    acc = deepMerge(acc, mergeExtends(parent, parentResolver))
                } else {
                    acc = deepMerge(acc, parent)
                }
            }
            return deepMerge(acc, child)
        }

        internal fun deepMerge(base: JSONObject, overlay: JSONObject): JSONObject {
            val out = JSONObject()
            val keys = linkedSetOf<String>()
            base.keys().forEach { keys += it }
            overlay.keys().forEach { keys += it }
            for (key in keys) {
                if (key == "extends") continue
                val b = if (base.has(key)) base.get(key) else null
                val o = if (overlay.has(key)) overlay.get(key) else null
                when {
                    o == null -> out.put(key, b)
                    b == null -> out.put(key, o)
                    key == "properties" && b is JSONArray && o is JSONArray ->
                        out.put(key, mergeProperties(b, o))
                    key == "android" && b is JSONObject && o is JSONObject ->
                        out.put(key, mergeAndroid(b, o))
                    o is JSONObject && b is JSONObject ->
                        out.put(key, deepMerge(b, o))
                    o is JSONArray && b is JSONArray && key in META_ARRAY_REPLACE ->
                        out.put(key, o)
                    else -> out.put(key, o)
                }
            }
            return out
        }

        private val META_ARRAY_REPLACE = setOf("match", "capabilities")

        private fun mergeProperties(base: JSONArray, overlay: JSONArray): JSONArray {
            val byId = linkedMapOf<Int, JSONObject>()
            fun ingest(arr: JSONArray) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = parseId(o.opt("id") ?: o.opt("nativeId"))
                    val prev = byId[id]
                    byId[id] = if (prev == null) JSONObject(o.toString()) else deepMerge(prev, o)
                }
            }
            ingest(base)
            ingest(overlay)
            val out = JSONArray()
            for (v in byId.values) out.put(v)
            return out
        }

        private fun mergeAndroid(base: JSONObject, overlay: JSONObject): JSONObject {
            val out = JSONObject()
            for (key in listOf("volumeGroups", "settings")) {
                val b = base.optJSONArray(key) ?: JSONArray()
                val o = overlay.optJSONArray(key) ?: JSONArray()
                if (b.length() == 0 && o.length() == 0) continue
                out.put(key, mergeAndroidList(b, o))
            }
            // Pass through any other android keys (overlay wins).
            val keys = linkedSetOf<String>()
            base.keys().forEach { keys += it }
            overlay.keys().forEach { keys += it }
            for (key in keys) {
                if (key == "volumeGroups" || key == "settings") continue
                if (overlay.has(key)) out.put(key, overlay.get(key))
                else if (base.has(key)) out.put(key, base.get(key))
            }
            return out
        }

        private fun mergeAndroidList(base: JSONArray, overlay: JSONArray): JSONArray {
            val byEntity = linkedMapOf<String, JSONObject>()
            fun ingest(arr: JSONArray) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val entity = o.optString("entity", null)
                        ?: o.optString("settingsKey", null)
                        ?: o.opt("groupId")?.toString()
                        ?: "idx-$i"
                    val prev = byEntity[entity]
                    byEntity[entity] = if (prev == null) JSONObject(o.toString()) else deepMerge(prev, o)
                }
            }
            ingest(base)
            ingest(overlay)
            val out = JSONArray()
            for (v in byEntity.values) out.put(v)
            return out
        }

        private fun fromMerged(root: JSONObject): PlatformConfig {
            val match = root.optJSONArray("match")?.toStringList() ?: emptyList()
            val caps = root.optJSONArray("capabilities")?.toStringList()
                ?.mapNotNull { runCatching { Capability.valueOf(it) }.getOrNull() }
                ?.toSet()
                ?: emptySet()
            val properties = mutableListOf<PropertyDef>()
            val pArr = root.optJSONArray("properties")
            if (pArr != null) {
                for (i in 0 until pArr.length()) {
                    properties += parseProperty(pArr.getJSONObject(i))
                }
            }

            val android = parseAndroid(root.optJSONObject("android"))

            val enumMap = mutableMapOf<Int, String>()
            val eObj = root.optJSONObject("driveModeEnum")
            if (eObj != null) {
                val keys = eObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    enumMap[k.toInt()] = eObj.getString(k)
                }
            }
            val cameras = mutableListOf<CameraRoleConfig>()
            val camArr = root.optJSONArray("cameras")
            if (camArr != null) {
                for (i in 0 until camArr.length()) {
                    val o = camArr.optJSONObject(i) ?: continue
                    val role = o.optString("role", "").trim()
                    val cameraId = o.optString("cameraId", "").trim()
                    if (role.isNotEmpty() && cameraId.isNotEmpty()) {
                        cameras += CameraRoleConfig(role = role, cameraId = cameraId)
                    }
                }
            }
            return PlatformConfig(
                id = root.getString("id"),
                displayName = root.optString("displayName", root.getString("id")),
                backend = root.optString("backend", "vhal"),
                match = match,
                capabilities = caps,
                properties = properties,
                android = android,
                driveModeEnum = enumMap,
                cameras = cameras,
                vhalTypedIds = root.optBoolean("vhalTypedIds", false),
            )
        }

        private fun parseProperty(o: JSONObject): PropertyDef {
            val id = parseId(o.opt("id") ?: o.opt("nativeId"))
            val areas = mutableListOf<Int>()
            val aArr = o.optJSONArray("areas")
            if (aArr != null) {
                for (i in 0 until aArr.length()) {
                    areas += parseId(aArr.get(i))
                }
            } else if (o.has("areaId")) {
                areas += o.optInt("areaId", 0)
            }
            if (areas.isEmpty()) areas += 0
            val access = normalizeAccess(o.opt("access"))
            val changeMode = when {
                o.has("changeMode") -> normalizeChangeMode(o.opt("changeMode"))
                else -> null
            }
            val fn = if (o.has("functionId")) parseId(o.get("functionId")) else null
            return PropertyDef(
                id = id,
                key = o.optString("key", o.optString("name", "0x${Integer.toHexString(id)}")),
                access = access,
                changeMode = changeMode,
                areas = areas,
                entity = o.optString("entity", null)?.takeIf { it.isNotBlank() },
                functionId = fn,
                range = parseRange(o),
                vector = o.optJSONObject("vector")?.let { v ->
                    val read = v.optInt("read", 0)
                    val write = v.optJSONArray("write")
                        ?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } }
                        ?: listOf(read)
                    VectorSlots(read, write)
                },
                writeLocked = o.optBoolean("writeLocked", false),
            )
        }

        private fun parseRange(o: JSONObject): ValueRange? {
            fun num(key: String): Float? =
                if (o.has(key) && !o.isNull(key)) o.optDouble(key).toFloat().takeIf { !it.isNaN() } else null
            val values = o.optJSONArray("values")?.let { arr -> (0 until arr.length()).map { arr.getInt(it) } }
            val range = ValueRange(min = num("min"), max = num("max"), step = num("step"), values = values)
            return range.takeIf { it.min != null || it.max != null || it.step != null || it.values != null }
        }

        private fun parseAndroid(obj: JSONObject?): AndroidConfig {
            if (obj == null) return AndroidConfig()
            val groups = mutableListOf<VolumeGroupDef>()
            val gArr = obj.optJSONArray("volumeGroups")
            if (gArr != null) {
                for (i in 0 until gArr.length()) {
                    val o = gArr.getJSONObject(i)
                    val groupId = o.getInt("groupId")
                    groups += VolumeGroupDef(
                        groupId = groupId,
                        entity = o.getString("entity"),
                        settingsKey = o.optString("settingsKey", null)
                            ?.takeIf { it.isNotBlank() }
                            ?: "android.car.VOLUME_GROUP/$groupId",
                        access = normalizeAccess(o.opt("access")),
                        writeVia = o.optString("writeVia", null)?.takeIf { it.isNotBlank() },
                    )
                }
            }
            val settings = mutableListOf<AndroidSettingDef>()
            val sArr = obj.optJSONArray("settings")
            if (sArr != null) {
                for (i in 0 until sArr.length()) {
                    val o = sArr.getJSONObject(i)
                    settings += AndroidSettingDef(
                        settingsKey = o.getString("settingsKey"),
                        entity = o.getString("entity"),
                        access = normalizeAccess(o.opt("access")),
                    )
                }
            }
            return AndroidConfig(volumeGroups = groups, settings = settings)
        }

        private fun normalizeAccess(raw: Any?): String {
            when (raw) {
                null -> return "r"
                is Number -> return when (raw.toInt()) {
                    1 -> "r"
                    2 -> "w"
                    3 -> "rw"
                    else -> "r"
                }
                is String -> {
                    val s = raw.trim().lowercase()
                    return when (s) {
                        "r", "read", "read_only" -> "r"
                        "w", "write", "write_only" -> "w"
                        "rw", "read_write", "read-write" -> "rw"
                        else -> "r"
                    }
                }
                else -> return "r"
            }
        }

        private fun normalizeChangeMode(raw: Any?): String? {
            when (raw) {
                null -> return null
                is Number -> return when (raw.toInt()) {
                    0 -> "static"
                    1 -> "on_change"
                    2 -> "continuous"
                    else -> raw.toString()
                }
                is String -> {
                    val s = raw.trim().lowercase().replace('-', '_')
                    return when (s) {
                        "static" -> "static"
                        "on_change", "onchange" -> "on_change"
                        "continuous" -> "continuous"
                        else -> s
                    }
                }
                else -> return raw.toString()
            }
        }

        fun parseId(raw: Any?): Int {
            when (raw) {
                is Number -> return raw.toInt()
                is String -> {
                    val s = raw.trim()
                    return if (s.startsWith("0x") || s.startsWith("0X")) {
                        s.substring(2).toLong(16).toInt()
                    } else {
                        s.toLong().toInt()
                    }
                }
                else -> error("bad id: $raw")
            }
        }

        private fun JSONArray.toStringList(): List<String> {
            val out = mutableListOf<String>()
            for (i in 0 until length()) out += getString(i)
            return out
        }
    }
}

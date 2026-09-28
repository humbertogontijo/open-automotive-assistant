package cc.opencar.assistant.integrations.antora1000

/**
 * Hand-rolled `navigation_proto` messages carried in the BYTES props
 * `VENDOR_NAVI_TBT_INFO` / `VENDOR_NAVI_ETA_INFO` (plain protobuf varints, no zigzag).
 *
 * NaviTBTInfo: 1 navi_status, 2 tbt_arrow, 3 tbt_dist, 4 tbt_dist_unit, 5 road_name,
 * 6 road_type, 7 guide_point_type, 8–11 guide enter/exit lgt/lat (double), 12 navi_app_type,
 * 13 road_img_status, 14 traffic_light_status, 15 cross_maneuver_id, 16 light_count_down,
 * 17 wait_round_count.
 *
 * NaviETAInfo: 1 navi_eta_dist, 2 navi_eta_dist_unit, 3 navi_eta_time_min,
 * 4 navi_eta_time_string, 5/6 navi_aim lgt/lat (double), 7 navi_next_service_name,
 * 8 navi_next_srv_type, 9 navi_next_srv_dist, 10 navi_next_srv_dist_unit.
 *
 * Distance units: 0 = metres, 1 = tenths of a kilometre.
 */
internal object NaviProto {
    data class Tbt(
        val status: Int = 0,
        val arrow: Int = 0,
        val distance: Int = 0,
        val distanceUnit: Int = 0,
        val roadName: String = "",
        val appType: Int = 0,
    ) {
        val active: Boolean get() = status != 0
        val distanceMeters: Int? get() = meters(distance, distanceUnit)
    }

    data class Eta(
        val distance: Int = 0,
        val distanceUnit: Int = 0,
        val timeMin: Int = 0,
        val timeString: String = "",
    ) {
        val distanceMeters: Int? get() = meters(distance, distanceUnit)
    }

    fun meters(value: Int, unit: Int): Int? = when (unit) {
        0 -> value
        1 -> value * 100
        else -> null
    }

    fun decodeTbt(bytes: ByteArray): Tbt? {
        var t = Tbt()
        return if (scan(bytes) { fn, v, s ->
                t = when (fn) {
                    1 -> t.copy(status = v.toInt())
                    2 -> t.copy(arrow = v.toInt())
                    3 -> t.copy(distance = v.toInt())
                    4 -> t.copy(distanceUnit = v.toInt())
                    5 -> t.copy(roadName = s ?: "")
                    12 -> t.copy(appType = v.toInt())
                    else -> t
                }
            }
        ) t else null
    }

    fun decodeEta(bytes: ByteArray): Eta? {
        var e = Eta()
        return if (scan(bytes) { fn, v, s ->
                e = when (fn) {
                    1 -> e.copy(distance = v.toInt())
                    2 -> e.copy(distanceUnit = v.toInt())
                    3 -> e.copy(timeMin = v.toInt())
                    4 -> e.copy(timeString = s ?: "")
                    else -> e
                }
            }
        ) e else null
    }

    /**
     * Walks top-level fields, calling [onField] with the varint value (0 for other wire types)
     * and the UTF-8 string for length-delimited fields. False on malformed input.
     */
    private fun scan(bytes: ByteArray, onField: (field: Int, varint: Long, string: String?) -> Unit): Boolean {
        var i = 0
        while (i < bytes.size) {
            val (key, ni) = VhalProto.readVarint(bytes, i)
            if (ni > bytes.size) return false
            i = ni
            val fn = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> {
                    val (v, nj) = VhalProto.readVarint(bytes, i)
                    if (nj > bytes.size) return false
                    i = nj
                    onField(fn, v, null)
                }
                2 -> {
                    val (len, nj) = VhalProto.readVarint(bytes, i)
                    val end = nj + len.toInt()
                    if (len < 0 || end > bytes.size) return false
                    onField(fn, 0, String(bytes, nj, len.toInt(), Charsets.UTF_8))
                    i = end
                }
                1 -> i += 8
                5 -> i += 4
                else -> return false
            }
            if (i > bytes.size) return false
        }
        return true
    }
}

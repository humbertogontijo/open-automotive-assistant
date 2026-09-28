package cc.opencar.assistant.api

import kotlin.math.roundToInt

/**
 * Value range of a property. Integrations declare native ranges in `platform.json`
 * (`min` / `max` / `step` / `values`); [DomainDefaults] holds the canonical OAA ranges.
 */
data class ValueRange(
    val min: Float? = null,
    val max: Float? = null,
    val step: Float? = null,
    /** Discrete supported values (levels, enum or bitmask members). */
    val values: List<Int>? = null,
) {
    val isBounded: Boolean get() = min != null && max != null && max > min

    /** [values] when declared, otherwise every [step] from [min] to [max]. */
    fun levels(): List<Int>? {
        values?.let { return it }
        if (!isBounded) return null
        val s = (step ?: 1f).coerceAtLeast(1f).roundToInt()
        return (min!!.roundToInt()..max!!.roundToInt() step s).toList()
    }

    /** Fills unset fields from [fallback]. */
    fun orElse(fallback: ValueRange): ValueRange = ValueRange(
        min = min ?: fallback.min,
        max = max ?: fallback.max,
        step = step ?: fallback.step,
        values = values ?: fallback.values,
    )
}

/**
 * Canonical per-domain ranges, following Home Assistant.
 *
 * - **Fixed scales** ([LIGHT_BRIGHTNESS], [COVER_POSITION]): every entity of the domain uses
 *   them; integrations declare the native range and [toCanonical] / [fromCanonical] convert.
 * - **Fallback bounds** ([CLIMATE_TEMPERATURE], [NUMBER]): values stay in native units, and
 *   these apply only when the integration declares no range.
 */
object DomainDefaults {
    /** HA `light` brightness: 0–255. */
    val LIGHT_BRIGHTNESS = ValueRange(min = 0f, max = 255f, step = 1f)

    /** HA `cover` / `valve` position: 0 closed – 100 open. */
    val COVER_POSITION = ValueRange(min = 0f, max = 100f, step = 1f)

    /** HA `climate` DEFAULT_MIN_TEMP / DEFAULT_MAX_TEMP (°C); 0.5 is the HA frontend's °C step. */
    val CLIMATE_TEMPERATURE = ValueRange(min = 7f, max = 35f, step = 0.5f)

    /** HA `number` DEFAULT_MIN_VALUE / DEFAULT_MAX_VALUE / DEFAULT_STEP. */
    val NUMBER = ValueRange(min = 0f, max = 100f, step = 1f)

    /** Native → canonical; identity when [native] is unbounded. */
    fun toCanonical(value: Float, native: ValueRange?, canonical: ValueRange): Float {
        val n = native?.takeIf { it.isBounded } ?: return value
        val frac = ((value - n.min!!) / (n.max!! - n.min)).coerceIn(0f, 1f)
        return snap(canonical.min!! + frac * (canonical.max!! - canonical.min), canonical)
    }

    /**
     * Canonical → native; identity when [native] is unbounded. A non-zero canonical value never
     * lands on the native minimum, so "dim but on" stays on.
     */
    fun fromCanonical(value: Float, native: ValueRange?, canonical: ValueRange): Float {
        val n = native?.takeIf { it.isBounded } ?: return value
        val frac = ((value - canonical.min!!) / (canonical.max!! - canonical.min)).coerceIn(0f, 1f)
        val out = snap(n.min!! + frac * (n.max!! - n.min), n)
        return if (frac > 0f && out <= n.min) n.min + (n.step ?: 1f) else out
    }

    private fun snap(v: Float, r: ValueRange): Float {
        val step = r.step ?: return v
        val base = r.min ?: 0f
        return (base + ((v - base) / step).roundToInt() * step).coerceIn(r.min ?: v, r.max ?: v)
    }
}

/** HA `rgb_color` ↔ packed `0xRRGGBB` native values. */
object RgbColor {
    fun toList(packed: Int): List<Int> =
        listOf((packed shr 16) and 0xff, (packed shr 8) and 0xff, packed and 0xff)

    /** Accepts `r,g,b`, `[r,g,b]` or `#rrggbb`. */
    fun parse(raw: String): Int? {
        val s = raw.trim().removePrefix("[").removeSuffix("]")
        if (s.startsWith("#")) {
            return s.drop(1).takeIf { it.length == 6 }?.toIntOrNull(16)
        }
        val parts = s.split(',').map { it.trim().toIntOrNull() ?: return null }
        if (parts.size != 3 || parts.any { it !in 0..255 }) return null
        return (parts[0] shl 16) or (parts[1] shl 8) or parts[2]
    }
}

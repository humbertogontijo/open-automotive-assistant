package cc.opencar.assistant.support

import android.content.Context
import org.json.JSONObject

/**
 * Merged common + integration string packs.
 *
 * Asset layout (no merge collisions across platforms):
 * ```
 * i18n/common/en.json
 * i18n/common/pt-BR.json
 * i18n/<integrationId>/en.json
 * i18n/<integrationId>/pt-BR.json
 * ```
 *
 * JSON shape:
 * ```json
 * {
 *   "strings": { "control.elka": "…", "opt.drive_mode.1": "…" },
 *   "valueMaps": {
 *     "drive_mode": { "1": "opt.drive_mode.1", "6": "drive_mode.normal" }
 *   }
 * }
 * ```
 *
 * Integrations reuse common keys (`control.*`, `opt.*`, `nav.*`) and may
 * override them or add platform-only keys / valueMaps.
 */
class I18nBundle(
    val locale: String,
    val integrationId: String,
    private val strings: Map<String, String>,
    private val valueMaps: Map<String, Map<String, String>>,
) {
    fun t(key: String, fallback: String? = null, vars: Map<String, String> = emptyMap()): String {
        val raw = strings[key] ?: fallback ?: key
        if (vars.isEmpty()) return raw
        var out = raw
        for ((k, v) in vars) {
            out = out.replace("{$k}", v)
        }
        return out
    }

    /** Translate if [raw] is a known string key; otherwise return [raw]. */
    fun resolveMaybe(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        return if (strings.containsKey(raw)) t(raw) else raw
    }

    /**
     * Map a live control/property value through an integration valueMap, then
     * resolve the resulting i18n key. Falls back to [fallbackKey] template or raw.
     */
    fun valueLabel(mapId: String, raw: Any?, fallbackKey: String? = null): String? {
        if (raw == null) return null
        val token = raw.toString()
        val key = valueMaps[mapId]?.get(token)
            ?: valueMaps[mapId]?.get(token.toDoubleOrNull()?.toInt()?.toString())
        if (key != null) return t(key)
        if (fallbackKey != null) return t(fallbackKey, vars = mapOf("value" to token))
        return token
    }

    fun controlLabel(controlId: String): String = t("control.$controlId", controlId)

    fun controlHint(controlId: String): String? {
        val key = "control.$controlId.hint"
        return if (strings.containsKey(key)) t(key) else null
    }

    fun has(key: String): Boolean = strings.containsKey(key)

    fun dictionary(): Map<String, String> = strings

    fun valueMapsSnapshot(): Map<String, Map<String, String>> = valueMaps

    companion object {
        const val PREF_LOCALE = "locale"
        val SUPPORTED = listOf("pt-BR", "en")

        fun normalize(locale: String?): String {
            if (locale.isNullOrBlank()) return "pt-BR"
            val trimmed = locale.trim().replace('_', '-')
            if (SUPPORTED.any { it.equals(trimmed, true) }) {
                return SUPPORTED.first { it.equals(trimmed, true) }
            }
            val lang = trimmed.substringBefore('-').lowercase()
            return when (lang) {
                "pt" -> "pt-BR"
                "en" -> "en"
                else -> "pt-BR"
            }
        }

        fun load(
            context: Context,
            integrationId: String,
            locale: String? = null,
        ): I18nBundle {
            val loc = normalize(
                locale
                    ?: OaaPrefs.ui(context).getString(PREF_LOCALE, null)
                    ?: context.resources.configuration.locales[0]?.toLanguageTag(),
            )
            val cacheKey = "$integrationId|$loc"
            cached?.let { (key, bundle) ->
                if (key == cacheKey) return bundle
            }
            val strings = linkedMapOf<String, String>()
            val valueMaps = linkedMapOf<String, MutableMap<String, String>>()

            // Base English, then requested locale overlays, then integration overlays.
            mergePack(context, "i18n/common/en.json", strings, valueMaps)
            if (loc != "en") mergePack(context, "i18n/common/$loc.json", strings, valueMaps)
            mergePack(context, "i18n/$integrationId/en.json", strings, valueMaps)
            if (loc != "en") mergePack(context, "i18n/$integrationId/$loc.json", strings, valueMaps)

            return I18nBundle(loc, integrationId, strings.toMap(), valueMaps.mapValues { it.value.toMap() })
                .also { cached = cacheKey to it }
        }

        /** Drop cached pack (e.g. after locale change). */
        fun invalidateCache() {
            cached = null
        }

        @Volatile
        private var cached: Pair<String, I18nBundle>? = null

        private fun mergePack(
            context: Context,
            asset: String,
            strings: MutableMap<String, String>,
            valueMaps: MutableMap<String, MutableMap<String, String>>,
        ) {
            val text = try {
                context.assets.open(asset).bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                return
            }
            val root = JSONObject(text)
            val s = root.optJSONObject("strings")
            if (s != null) {
                val keys = s.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    strings[k] = s.getString(k)
                }
            }
            val vm = root.optJSONObject("valueMaps")
            if (vm != null) {
                val mapIds = vm.keys()
                while (mapIds.hasNext()) {
                    val mapId = mapIds.next()
                    val inner = vm.getJSONObject(mapId)
                    val dest = valueMaps.getOrPut(mapId) { linkedMapOf() }
                    val vKeys = inner.keys()
                    while (vKeys.hasNext()) {
                        val vk = vKeys.next()
                        dest[vk] = inner.getString(vk)
                    }
                }
            }
        }
    }
}

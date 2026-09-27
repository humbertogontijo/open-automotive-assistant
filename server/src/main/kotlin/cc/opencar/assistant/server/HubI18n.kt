package cc.opencar.assistant.server

import org.json.JSONObject

/** Common UI strings bundled into the hub jar for pages shown before a car is selected. */
object HubI18n {
    private val SUPPORTED = listOf("en", "pt-BR")
    private val cache = HashMap<String, Map<String, Any?>>()

    fun normalize(raw: String?): String {
        val tag = raw?.split(',')?.firstOrNull()?.substringBefore(';')?.trim().orEmpty()
        return if (tag.lowercase().startsWith("pt")) "pt-BR" else "en"
    }

    @Synchronized
    fun bundle(localeOrAcceptLanguage: String?): Map<String, Any?> {
        val locale = normalize(localeOrAcceptLanguage)
        return cache.getOrPut(locale) {
            val json = resourceBytes("i18n/common/$locale.json")?.let { JSONObject(it.toString(Charsets.UTF_8)) }
            mapOf(
                "locale" to locale,
                "locales" to SUPPORTED,
                "integration" to "hub",
                "strings" to (json?.optJSONObject("strings")?.toMap() ?: emptyMap<String, Any?>()),
                "valueMaps" to (json?.optJSONObject("valueMaps")?.toMap() ?: emptyMap<String, Any?>()),
            )
        }
    }
}

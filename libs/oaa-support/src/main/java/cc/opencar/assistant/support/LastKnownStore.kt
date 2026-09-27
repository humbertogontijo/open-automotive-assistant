package cc.opencar.assistant.support

import android.content.Context

/**
 * Sticky last-known values for comfort controls when the car returns empty/zero (e.g. Park).
 */
class LastKnownStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("oaa_last_known", Context.MODE_PRIVATE)

    fun get(id: String): String? = prefs.getString(id, null)

    fun put(id: String, value: String) {
        prefs.edit().putString(id, value).apply()
    }

    fun clear(id: String) {
        prefs.edit().remove(id).apply()
    }
}

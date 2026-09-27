package cc.opencar.assistant.support

import android.content.Context
import android.content.SharedPreferences

/** SharedPreferences file names shared across modules. */
object OaaPrefs {
    const val UI = "oaa_ui_prefs"

    fun ui(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(UI, Context.MODE_PRIVATE)
}

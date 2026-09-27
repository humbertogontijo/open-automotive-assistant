package cc.opencar.assistant.api

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Cross-process wake/sleep handoff when the HU resumes before the app runtime
 * (and shortcuts) are ready — e.g. process was dead during STR and a
 * manifest-registered platform receiver brought us back up.
 *
 * Platform modules (e.g. Flyme) write via [persist] + [startAssistantService];
 * the app core flushes via [consume] once shortcuts exist.
 */
object PendingWake {
    const val PREFS = "oaa_wake"
    const val KEY_KIND = "pending" // "on" | "off"
    const val KEY_SOURCE = "source"
    const val KEY_AT = "at"

    /** Service action: [EXTRA_KIND] is `on` or `off`, [EXTRA_SOURCE] is the broadcast action. */
    const val ACTION_WAKE_SIGNAL = "cc.opencar.assistant.WAKE_SIGNAL"
    const val EXTRA_KIND = "kind"
    const val EXTRA_SOURCE = "source"

    const val SERVICE_CLASS = "cc.opencar.assistant.AssistantService"

    /** Ignore pending edges older than this (stale after a long sleep without flush). */
    const val MAX_AGE_MS = 120_000L

    data class Edge(val kind: String, val source: String, val atMs: Long)

    /**
     * Device-protected prefs so LOCKED_BOOT / direct-boot wake receivers can
     * persist without waiting for credential-encrypted storage.
     */
    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun persist(context: Context, kind: String, source: String) {
        prefs(context).edit()
            .putString(KEY_KIND, kind)
            .putString(KEY_SOURCE, source)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Read and clear a pending edge if present and fresh.
     * Returns null when empty or older than [MAX_AGE_MS].
     */
    fun consume(context: Context): Edge? {
        val store = prefs(context)
        val kind = store.getString(KEY_KIND, null)
        val source = store.getString(KEY_SOURCE, "pending") ?: "pending"
        val at = store.getLong(KEY_AT, 0L)
        store.edit().clear().apply()
        if (kind == null) return null
        val age = System.currentTimeMillis() - at
        if (age < 0L || age > MAX_AGE_MS) return null
        return Edge(kind = kind, source = source, atMs = at)
    }

    /** Bring up the FGS with an optional wake/sleep payload (package-relative). */
    fun startAssistantService(
        context: Context,
        kind: String? = null,
        source: String? = null,
    ) {
        val svc = Intent()
            .setClassName(context.packageName, SERVICE_CLASS)
        if (kind != null) {
            svc.action = ACTION_WAKE_SIGNAL
            svc.putExtra(EXTRA_KIND, kind)
            svc.putExtra(EXTRA_SOURCE, source ?: "pending")
        }
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc)
        else context.startService(svc)
    }
}

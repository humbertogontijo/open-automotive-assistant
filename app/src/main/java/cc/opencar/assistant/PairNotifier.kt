package cc.opencar.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import cc.opencar.assistant.feature.web.CarAuth
import cc.opencar.assistant.feature.web.CarAuthStore
import cc.opencar.assistant.protocol.OaaCarAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Heads-up notification with the pairing code while someone waits to pair with this car. */
class PairNotifier(private val context: Context, private val auth: CarAuth) {
    private val mgr = context.getSystemService(NotificationManager::class.java)

    fun start(scope: CoroutineScope) {
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.pair_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        scope.launch {
            auth.pending.collect { list ->
                if (list.isEmpty()) mgr.cancel(NOTIF_ID) else runCatching { mgr.notify(NOTIF_ID, build(list)) }
            }
        }
    }

    private fun build(list: List<CarAuthStore.PairRequest>): Notification {
        val latest = list.maxBy { it.expiresAtMs }
        val who = latest.name.ifBlank { latest.source }
        val title = context.getString(
            if (latest.kind == OaaCarAuth.KIND_HUB) R.string.pair_title_hub else R.string.pair_title_device,
            who,
        )
        val open = PendingIntent.getActivity(
            context,
            NOTIF_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_oaa)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.pair_code, latest.code.chunked(3).joinToString(" ")))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter((latest.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(1_000))
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "oaa_pair"
        const val NOTIF_ID = 43
    }
}

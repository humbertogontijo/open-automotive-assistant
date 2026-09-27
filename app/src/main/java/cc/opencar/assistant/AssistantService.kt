package cc.opencar.assistant

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Display
import cc.opencar.assistant.api.PendingWake
import cc.opencar.assistant.api.WakeSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class AssistantService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private var overlayCollectJob: Job? = null

    private var installedWakeSignals: WakeSignals = WakeSignals()
    private var wakeReceiverRegistered = false

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d(TAG, "wakeReceiver action=$action")
            when (action) {
                Intent.ACTION_SCREEN_ON -> notifyWake("broadcast_screen_on")
                Intent.ACTION_USER_PRESENT -> notifyWake("broadcast_user_present")
                Intent.ACTION_USER_UNLOCKED -> notifyWake("broadcast_user_unlocked")
                Intent.ACTION_DREAMING_STOPPED -> notifyWake("broadcast_dreaming_stopped")
                Intent.ACTION_SCREEN_OFF -> notifyScreenOff("broadcast_screen_off")
                Intent.ACTION_DREAMING_STARTED -> notifyScreenOff("broadcast_dreaming_started")
                else -> when (action) {
                    in installedWakeSignals.wakeActions -> notifyWake("vendor_$action")
                    in installedWakeSignals.sleepActions -> notifyScreenOff("vendor_$action")
                }
            }
        }
    }

    private var displayManager: DisplayManager? = null
    private var powerManager: PowerManager? = null
    private var keyguardManager: KeyguardManager? = null
    private var lastDisplayState: Int = Display.STATE_UNKNOWN
    private var lastInteractive: Boolean? = null
    private var lastKeyguardLocked: Boolean? = null
    private var pollTicks: Int = 0
    @Volatile private var lastScreenOffLogged: String? = null
    private val pollHandler = Handler(Looper.getMainLooper())
    private val interactivePoll = object : Runnable {
        override fun run() {
            val pm = powerManager
            val kg = keyguardManager
            val interactive = pm?.isInteractive
            val locked = kg?.isKeyguardLocked
            val displayState = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.state

            val prevInteractive = lastInteractive
            if (prevInteractive == false && interactive == true) {
                notifyWake("poll_interactive_true")
            } else if (prevInteractive == true && interactive == false) {
                notifyScreenOff("poll_interactive_false")
            }
            lastInteractive = interactive

            val prevLocked = lastKeyguardLocked
            if (prevLocked == true && locked == false) {
                // Some HUs keep the display interactive across lock — treat unlock as wake.
                notifyWake("poll_keyguard_unlocked")
            } else if (prevLocked == false && locked == true) {
                notifyScreenOff("poll_keyguard_locked")
            }
            lastKeyguardLocked = locked

            pollTicks++
            if (pollTicks % 20 == 0) {
                Log.d(
                    TAG,
                    "wakePoll interactive=$interactive keyguardLocked=$locked " +
                        "displayState=$displayState lastOffSignaled=$lastScreenOffLogged",
                )
            }
            pollHandler.postDelayed(this, INTERACTIVE_POLL_MS)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val state = displayManager?.getDisplay(displayId)?.state ?: return
            Log.d(TAG, "displayChanged state=$state prev=$lastDisplayState")
            if (state == Display.STATE_ON && lastDisplayState != Display.STATE_ON) {
                notifyWake("display_on")
            } else if (state != Display.STATE_ON && lastDisplayState == Display.STATE_ON) {
                notifyScreenOff("display_off_$state")
            }
            lastDisplayState = state
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification(entryVisible = true))

        installWakeReceiver(currentWakeSignals())

        displayManager = getSystemService(DisplayManager::class.java)
        displayManager?.let { dm ->
            lastDisplayState = dm.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN
            dm.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        }

        powerManager = getSystemService(PowerManager::class.java)
        keyguardManager = getSystemService(KeyguardManager::class.java)
        lastInteractive = powerManager?.isInteractive
        lastKeyguardLocked = keyguardManager?.isKeyguardLocked
        Log.d(
            TAG,
            "wake sensors ready interactive=$lastInteractive keyguardLocked=$lastKeyguardLocked " +
                "displayState=$lastDisplayState vendorWake=${installedWakeSignals.wakeActions.size} " +
                "vendorSleep=${installedWakeSignals.sleepActions.size}",
        )
        pollHandler.postDelayed(interactivePoll, INTERACTIVE_POLL_MS)

        bindShortcutsOverlay()
    }

    override fun onDestroy() {
        pollHandler.removeCallbacks(interactivePoll)
        overlayCollectJob?.cancel()
        overlayCollectJob = null
        val app = applicationContext
        if (app is OaaApp) {
            app.runtime.shortcuts?.detachOverlay()
        }
        runCatching { if (wakeReceiverRegistered) unregisterReceiver(wakeReceiver) }
        wakeReceiverRegistered = false
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        job.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Runtime may finish matching after the service started (boot race) — refresh vendor intents.
        installWakeReceiver(currentWakeSignals())
        bindShortcutsOverlay()
        if (intent?.action == PendingWake.ACTION_WAKE_SIGNAL) {
            val kind = intent.getStringExtra(PendingWake.EXTRA_KIND)
            val source = intent.getStringExtra(PendingWake.EXTRA_SOURCE) ?: "service_wake_signal"
            Log.i(TAG, "WAKE_SIGNAL kind=$kind source=$source")
            when (kind) {
                "on" -> notifyWake(source)
                "off" -> notifyScreenOff(source)
            }
            // Ensure runtime is spinning (process may have been dead during STR).
            (applicationContext as? OaaApp)?.runtime?.startAsync()
        }
        return START_STICKY
    }

    /**
     * Wait for runtime.shortcuts when the service starts before matching finishes,
     * then attach overlay and collect overlayEnabled for the FGS notification.
     * Idempotent: a second call while the wait/collect job is running is a no-op —
     * do not attach from the caller (the job may still be waiting for shortcuts).
     */
    private fun bindShortcutsOverlay() {
        if (overlayCollectJob?.isActive == true) return
        overlayCollectJob = scope.launch {
            val app = applicationContext as? OaaApp ?: return@launch
            val shortcuts = withTimeoutOrNull(60_000L) {
                while (app.runtime.shortcuts == null) {
                    if (app.runtime.ready.value) return@withTimeoutOrNull null
                    delay(200)
                }
                app.runtime.shortcuts
            } ?: app.runtime.shortcuts
            if (shortcuts == null) {
                Log.w(TAG, "shortcuts unavailable — skipping overlay bind")
                return@launch
            }
            shortcuts.attachOverlay(this@AssistantService)
            shortcuts.refreshOverlay()
            // startForeground (not notify): initial onCreate posted an undecorated
            // FGS notif before shortcuts existed; this reapplies Flyme status-icon extras.
            publishEntryNotification(shortcuts.store.isOverlayEnabled())
            shortcuts.store.overlayEnabled
                .distinctUntilChanged()
                .collect { visible -> publishEntryNotification(visible) }
        }
    }

    private fun currentWakeSignals(): WakeSignals {
        val app = applicationContext
        return if (app is OaaApp) app.runtime.wakeSignals() else WakeSignals()
    }

    private fun installWakeReceiver(signals: WakeSignals) {
        if (wakeReceiverRegistered && signals == installedWakeSignals) return
        if (wakeReceiverRegistered) {
            runCatching { unregisterReceiver(wakeReceiver) }
            wakeReceiverRegistered = false
        }
        installedWakeSignals = signals
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
            addAction(Intent.ACTION_DREAMING_STARTED)
            signals.wakeActions.forEach { addAction(it) }
            signals.sleepActions.forEach { addAction(it) }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(wakeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(wakeReceiver, filter)
        }
        wakeReceiverRegistered = true
        if (!signals.isEmpty()) {
            Log.d(
                TAG,
                "wake vendor intents installed wake=${signals.wakeActions.size} " +
                    "sleep=${signals.sleepActions.size}",
            )
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Open Automotive Assistant", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(entryVisible: Boolean): Notification {
        val app = applicationContext as? OaaApp
        val shortcuts = app?.runtime?.shortcuts
        val openPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val contentPi = shortcuts?.notificationContentIntent(this) ?: openPi
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_notification))
            .setSmallIcon(R.drawable.ic_stat_oaa)
            .setContentIntent(contentPi)
            .setOngoing(true)

        val notification = builder.build()
        shortcuts?.decorateNotification(this, notification, entryVisible)
        return notification
    }

    private fun publishEntryNotification(visible: Boolean) {
        try {
            startForeground(NOTIF_ID, buildNotification(entryVisible = visible))
            Log.d(TAG, "quick entry notification published visible=$visible")
        } catch (t: Throwable) {
            Log.w(TAG, "quick entry notification publish failed: ${t.message}")
        }
    }

    private fun notifyWake(source: String) {
        Log.d(TAG, "wake signal: $source")
        val app = applicationContext
        if (app is OaaApp) {
            app.runtime.notifyScreenOn(source)
        } else {
            Log.w(TAG, "wake signal dropped — app not OaaApp")
        }
    }

    private fun notifyScreenOff(source: String) {
        lastScreenOffLogged = source
        Log.d(TAG, "sleep signal: $source")
        val app = applicationContext
        if (app is OaaApp) {
            app.runtime.notifyScreenOff(source)
        } else {
            Log.w(TAG, "sleep signal dropped — app not OaaApp")
        }
    }

    companion object {
        private const val TAG = "AssistantService"
        private const val CHANNEL_ID = "oaa_fg"
        private const val NOTIF_ID = 42
        private const val INTERACTIVE_POLL_MS = 1_500L
    }
}

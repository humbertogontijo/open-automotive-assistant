package cc.opencar.assistant.feature.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.security.MessageDigest

class ApkInstaller(private val context: Context) {
    data class InstallResult(
        val ok: Boolean,
        val message: String,
        val sha256: String? = null,
        val sessionId: Int? = null,
    )

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Commit [apk] to the package installer. With [selfUpdate] the session is
     * scoped to our own package and, on API 31+, asks for no user confirmation.
     * The outcome arrives later on [InstallEvents].
     */
    fun install(apk: File, expectedSha256: String? = null, selfUpdate: Boolean = false): InstallResult {
        if (!apk.exists()) return InstallResult(false, "APK not found")
        val hash = sha256(apk)
        if (expectedSha256 != null && !expectedSha256.equals(hash, ignoreCase = true)) {
            return InstallResult(false, "SHA-256 mismatch", hash)
        }
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (selfUpdate) {
                params.setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                val callback = Intent(ACTION_INSTALL_COMPLETE).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            InstallResult(true, "Install session committed", hash, sessionId)
        } catch (t: Throwable) {
            Log.e(TAG, "install failed", t)
            InstallResult(false, t.message ?: "install failed", hash)
        }
    }

    fun installDir(): File =
        File(context.getExternalFilesDir(null), "incoming-apks").also { it.mkdirs() }

    companion object {
        const val ACTION_INSTALL_COMPLETE = "cc.opencar.assistant.INSTALL_COMPLETE"
        private const val TAG = "OaaInstall"
    }
}

/** Package installer outcomes, keyed by the session id from [ApkInstaller.InstallResult]. */
object InstallEvents {
    data class Event(val sessionId: Int, val status: Int, val message: String?) {
        val success: Boolean get() = status == PackageInstaller.STATUS_SUCCESS
        val pendingUser: Boolean get() = status == PackageInstaller.STATUS_PENDING_USER_ACTION
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    internal fun publish(event: Event) {
        _events.tryEmit(event)
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        Log.i("OaaInstall", "session=$sessionId status=$status msg=$msg")
        InstallEvents.publish(InstallEvents.Event(sessionId, status, msg))
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirm != null) context.startActivity(confirm)
        }
    }
}

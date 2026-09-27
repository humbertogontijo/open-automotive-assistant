package cc.opencar.assistant

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.UserManager
import android.util.Log
import cc.opencar.assistant.api.DeviceFingerprint
import cc.opencar.assistant.feature.debug.LogRingBuffer
import cc.opencar.assistant.feature.debug.OaaLog
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class OaaApp : Application() {
    lateinit var runtime: AssistantRuntime
        private set

    val log = OaaLog()

    /** Gecko allows exactly one runtime per process. */
    val geckoRuntime: GeckoRuntime by lazy {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val settings = GeckoRuntimeSettings.Builder()
            .remoteDebuggingEnabled(debuggable)
            .consoleOutput(debuggable)
            .automaticFontSizeAdjustment(false)
            .fontSizeFactor(1f)
            .build()
        GeckoRuntime.create(this, settings)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        runtime = AssistantRuntime(this)
        // GeckoView child processes (tab, gpu, …) run this Application too; they must not
        // start a second web server, DVR or hub link.
        if (getProcessName() != packageName) return
        LogRingBuffer.append("OaaApp onCreate fingerprint=${Build.FINGERPRINT}")
        val unlocked = runCatching {
            getSystemService(UserManager::class.java)?.isUserUnlocked == true
        }.getOrDefault(true)
        if (unlocked) {
            runtime.startAsync()
        } else {
            // Credential-encrypted work waits for USER_UNLOCKED / BootReceiver.
            Log.i("OaaApp", "direct boot — deferring runtime until user unlock")
            LogRingBuffer.append("OaaApp deferred start (user locked)")
        }
    }

    companion object {
        lateinit var instance: OaaApp
            private set

        fun deviceFingerprint(): DeviceFingerprint =
            DeviceFingerprint(
                model = Build.MODEL.orEmpty(),
                device = Build.DEVICE.orEmpty(),
                hardware = Build.HARDWARE.orEmpty(),
                manufacturer = Build.MANUFACTURER.orEmpty(),
                fingerprint = Build.FINGERPRINT.orEmpty(),
                brand = Build.BRAND.orEmpty(),
            )
    }
}

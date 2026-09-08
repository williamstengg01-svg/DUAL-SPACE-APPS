package com.dualspace.clone

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.dualspace.clone.data.CloneStore
import com.dualspace.clone.data.GmsLinker
import com.dualspace.clone.util.AnrWatchdog
import com.dualspace.clone.util.CrashLog
import com.dualspace.clone.util.DsLog
import com.dualspace.clone.util.NetProbe
import com.dualspace.clone.util.Prefs
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import top.niunaijun.blackbox.utils.Slog
import java.io.File

/**
 * Host application. The virtualization engine (BlackBox) is attached here, *before*
 * anything else, because every cloned app process is spawned from this Application
 * class as well — `attachBaseContext` runs in the host process and in each virtual
 * process, so keep it minimal and only do host-only work when `isMainProcess`.
 *
 * Order matters in [attachBaseContext]: the log file and the system crash handler are set up
 * *before* the engine class is first touched, because its static initialiser replaces the
 * default uncaught-exception handler (see [CrashLog] for why we need the original one).
 */
class DualSpaceApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        appContext = base
        CrashLog.captureSystemHandler()
        DsLog.init(base)
        try {
            BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
                override fun getHostPackageName(): String = base.packageName

                // Keep a lightweight daemon alive so clones keep receiving notifications
                // in the background on aggressive OEM skins (Xiaomi, Vivo, Oppo).
                override fun isEnableDaemonService(): Boolean = true

                override fun isHideRoot(): Boolean = false

                // A clone asked to install another APK — we do not handle that.
                override fun requestInstallPackage(file: File?, userId: Int): Boolean = false

                // Never send logs anywhere. Returning null keeps the engine's log uploader off.
                override fun getLogSenderChatId(): String? = null

                // We keep our own bounded log file; the engine's endless logcat pipe stays off.
                override fun isEnableLogcatCapture(): Boolean = false
            })
            // Engine warnings and errors go into the same log file as ours, plus every
            // decision the engine makes about which activity to start or resume: that routing
            // is where "the clone jumps back to the previous screen" is decided.
            Slog.setSink(DsLog, Log.WARN)
            Slog.setSinkTags("ActivityRouting")
        } catch (e: Throwable) {
            DsLog.e(TAG, "Engine attach failed", e)
        }
        DsLog.logProcessStart(BuildConfig.VERSION_NAME)
    }

    override fun onCreate() {
        super.onCreate()
        try {
            BlackBoxCore.get().doCreate()
        } catch (t: Throwable) {
            DsLog.e(TAG, "Engine create failed", t)
        }
        // Every process: crash recorder (with the main-thread kill policy), ANR watchdog and
        // a lifecycle logger so the log shows which clone was starting when something broke.
        CrashLog.install(this)
        AnrWatchdog.start(this)
        runCatching { BlackBoxCore.get().addAppLifecycleCallback(lifecycleLogger) }

        val main = runCatching { BlackBoxCore.get().isMainProcess }.getOrDefault(false)
        if (!main) return

        // Host-process-only initialisation from here on.
        instance = this
        Prefs.init(this)
        CloneStore.init(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
        AppCompatDelegate.setDefaultNightMode(
            when (Prefs.themeMode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )

        // Make sure Google Play Services is linked in every clone slot and is refreshed
        // whenever the phone's own Play Services was updated — so clones never show a
        // "Play Services missing / out of date" prompt. Also publishes the first status.
        // The engine must know the Play Store preference before anything is linked.
        runCatching { BlackBoxCore.get().setMirrorPlayStore(Prefs.mirrorPlayStore) }
        appScope.launch {
            runCatching { GmsLinker.syncAll(this@DualSpaceApp) }
                .onFailure { DsLog.e(TAG, "GMS sync at start failed", it) }
        }
    }

    private val lifecycleLogger = object : AppLifecycleCallback() {
        override fun beforeMainLaunchApk(packageName: String?, userid: Int) {
            DsLog.i("Clone", "launch requested: $packageName slot $userid")
        }

        override fun beforeCreateApplication(packageName: String?, processName: String?, context: Context?, userId: Int) {
            DsLog.i("Clone", "starting $packageName (slot $userId) in process '${DsLog.processLabel()}' ($processName)")
        }

        override fun afterApplicationOnCreate(packageName: String?, processName: String?, application: Application?, userId: Int) {
            DsLog.i("Clone", "$packageName (slot $userId) Application.onCreate done")
            // From inside the clone's own process: what does *it* see of the network?
            if (application != null) NetProbe.schedule(application, "$packageName/u$userId")
        }

        // The screen trail below is what tells apart "the app went back on its own" from
        // "Android destroyed the screen and rebuilt it from an old saved state". Both look
        // identical to the user; only 'restored' and 'configChange' in this log separate them.
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {
            DsLog.d("Clone", "activity created: ${activity.javaClass.name}" +
                if (savedInstanceState != null) "  (RESTORED from saved state)" else "")
        }

        override fun onActivityResumed(activity: android.app.Activity) {
            DsLog.d("Clone", "activity resumed: ${activity.javaClass.name}")
        }

        override fun onActivityDestroyed(activity: android.app.Activity) {
            DsLog.d("Clone", "activity destroyed: ${activity.javaClass.name}" +
                if (activity.isChangingConfigurations) "  (configChange)" else
                    if (activity.isFinishing) "  (finished)" else "  (killed by the system)")
        }
    }

    companion object {
        private const val TAG = "DualSpaceApp"

        /** Background work that must outlive any single screen (GMS sync, boot re-link). */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        lateinit var appContext: Context
            private set

        /** Set in the host UI process only; null in engine and clone processes. */
        @Volatile
        var instance: DualSpaceApp? = null
            private set
    }
}

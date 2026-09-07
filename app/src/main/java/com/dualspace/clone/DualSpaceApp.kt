package com.dualspace.clone

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.dualspace.clone.data.CloneStore
import com.dualspace.clone.data.GmsLinker
import com.dualspace.clone.util.CrashLog
import com.dualspace.clone.util.Prefs
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import java.io.File

/**
 * Host application. The virtualization engine (BlackBox) is attached here, *before*
 * anything else, because every cloned app process is spawned from this Application
 * class as well — `attachBaseContext` runs in the host process and in each virtual
 * process, so keep it minimal and only do host-only work when `isMainProcess`.
 */
class DualSpaceApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        appContext = base
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
            })
        } catch (e: Exception) {
            Log.e(TAG, "Engine attach failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        BlackBoxCore.get().doCreate()
        CrashLog.install(this)

        if (!BlackBoxCore.get().isMainProcess) return

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
        // "Play Services missing / out of date" prompt.
        appScope.launch { GmsLinker.syncAll(this@DualSpaceApp) }
    }

    companion object {
        private const val TAG = "DualSpaceApp"

        lateinit var appContext: Context
            private set

        lateinit var instance: DualSpaceApp
            private set
    }
}

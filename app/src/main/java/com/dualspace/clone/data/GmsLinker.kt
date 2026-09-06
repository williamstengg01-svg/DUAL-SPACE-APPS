package com.dualspace.clone.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.dualspace.clone.util.Prefs
import top.niunaijun.blackbox.BlackBoxCore

/**
 * Keeps Google Play Services "pre-linked" inside every clone slot.
 *
 * How it works: the engine mirrors the phone's own Google Play Services, Google Services
 * Framework and Play Store packages into a virtual user. Once they are present in a slot,
 * any app cloned into that slot resolves `com.google.android.gms` normally — Google
 * sign-in, FCM push, Maps, in-app billing and Play Integrity all go through it — and the
 * app never sees a "Play Services missing / please link" state.
 *
 * Because the mirror is taken from the host phone, "keeping it up to date" only means:
 * whenever the phone's Play Services version changes, re-mirror it into every slot.
 */
object GmsLinker {
    private const val TAG = "GmsLinker"
    private const val GMS_PKG = "com.google.android.gms"

    /** True when the phone itself has Google Play Services, i.e. we have something to mirror. */
    fun isSupported(): Boolean = runCatching { BlackBoxCore.get().isSupportGms }.getOrDefault(false)

    fun isLinked(userId: Int): Boolean =
        runCatching { BlackBoxCore.get().isInstallGms(userId) }.getOrDefault(false)

    /**
     * Ensure GMS is present in [userId]. Called *before* an app is installed into a slot so
     * the very first launch of the clone already has Play Services. Returns true on success
     * or when GMS is not available on this phone at all (nothing to do).
     */
    @Synchronized
    fun ensure(userId: Int): Boolean {
        if (!isSupported()) return true
        if (isLinked(userId)) return true
        val result = runCatching { BlackBoxCore.get().installGms(userId) }.getOrNull()
        val ok = result?.success == true
        Log.i(TAG, "installGms(user=$userId) -> $ok ${result?.msg ?: ""}")
        return ok
    }

    /**
     * Link GMS into every slot that has clones, and re-link everywhere if the phone's Play
     * Services was updated since we last looked. Safe to call often; it is cheap when
     * nothing changed.
     */
    @Synchronized
    fun syncAll(context: Context) {
        if (!isSupported()) return
        val hostVersion = hostGmsVersion(context)
        val changed = hostVersion != Prefs.hostGmsVersion
        for (userId in CloneStore.userIds()) {
            if (changed && isLinked(userId)) {
                runCatching { BlackBoxCore.get().uninstallGms(userId) }
            }
            ensure(userId)
        }
        if (changed) Prefs.hostGmsVersion = hostVersion
    }

    private fun hostGmsVersion(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(GMS_PKG, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: PackageManager.NameNotFoundException) {
        -1L
    }
}

package com.dualspace.clone.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.dualspace.clone.util.DsLog
import com.dualspace.clone.util.Prefs
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.core.system.ServiceManager
import top.niunaijun.blackbox.core.system.pm.IBPackageManagerService
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps Google Play Services "pre-linked" inside every clone slot and knows, at any moment,
 * whether that link is healthy — the green / red indicator in the UI comes from here.
 *
 * How linking works: the engine mirrors the phone's own Google Play Services, Google Services
 * Framework and Play Store packages into a virtual user. Once they are present in a slot,
 * any app cloned into that slot resolves `com.google.android.gms` normally.
 *
 * Threading: everything that talks to the engine is a Binder call into the `:black` process
 * and can block for a second or more while that process starts. Call those from
 * `Dispatchers.IO` only. [cachedLinked] and [status] are safe on the main thread.
 *
 * Locking is per slot, so linking slot 3 never makes the launch of a clone in slot 0 wait.
 */
object GmsLinker {
    private const val TAG = "GmsLinker"
    private const val GMS_PKG = "com.google.android.gms"

    /** A snapshot of the link health, published on [status]. */
    data class Status(
        /** The phone itself has Google Play Services, i.e. there is something to mirror. */
        val hostHasGms: Boolean,
        /** The engine's service process answered. */
        val engineReachable: Boolean,
        /** Per clone slot: is the GMS mirror installed there. */
        val linked: Map<Int, Boolean>,
        val checkedAt: Long = System.currentTimeMillis()
    ) {
        val unlinkedSlots: Set<Int> get() = linked.filterValues { !it }.keys
        /** Green when true, red otherwise. */
        val connected: Boolean get() = hostHasGms && engineReachable && unlinkedSlots.isEmpty()
    }

    private val locks = ConcurrentHashMap<Int, Any>()
    private fun lockFor(userId: Int): Any = locks.getOrPut(userId) { Any() }

    private val linkedCache = ConcurrentHashMap<Int, Boolean>()
    private val statusLive = MutableLiveData<Status>()

    val status: LiveData<Status> get() = statusLive
    val lastStatus: Status? get() = statusLive.value

    /** Last known link state of a slot without touching the engine (null = not checked yet). */
    fun cachedLinked(userId: Int): Boolean? = linkedCache[userId]

    /** True when the phone itself has Google Play Services. Cheap (host PackageManager). */
    fun isSupported(): Boolean = runCatching { BlackBoxCore.get().isSupportGms }.getOrDefault(false)

    /**
     * Ask the engine's package service directly whether [pkg] is installed in [userId].
     * The client-side wrapper (BPackageManager) switches to a "fallback mode" after a couple of
     * early Binder failures and then answers from the *phone's* package list — which says
     * "installed" for Play Services even when the slot is empty. That is how slot 0 ended up
     * without Play Services in the field. Returns null when the service cannot be reached.
     */
    private fun directIsInstalled(pkg: String, userId: Int): Boolean? = try {
        val binder = BlackBoxCore.get().getService(ServiceManager.PACKAGE_MANAGER)
        if (binder == null || !binder.isBinderAlive) null
        else IBPackageManagerService.Stub.asInterface(binder).isInstalled(pkg, userId)
    } catch (t: Throwable) {
        DsLog.w(TAG, "direct isInstalled($pkg, user=$userId) threw: $t"); null
    }

    /** Binder call — IO thread only. Updates the cache. */
    fun isLinked(userId: Int): Boolean {
        val v = directIsInstalled(GMS_PKG, userId)
            ?: runCatching { BlackBoxCore.get().isInstallGms(userId) }
                .onFailure { DsLog.w(TAG, "isInstallGms(user=$userId) threw", it) }
                .getOrDefault(false)
        linkedCache[userId] = v
        return v
    }

    /** Binder call — IO thread only. Does the engine's service process answer? */
    fun isEngineReachable(): Boolean = runCatching {
        BlackBoxCore.get().getService(ServiceManager.PACKAGE_MANAGER)?.isBinderAlive == true
    }.getOrDefault(false)

    /**
     * Ensure GMS is present in [userId]. Called *before* an app is installed into a slot so
     * the very first launch of the clone already has Play Services. Returns true on success
     * or when GMS is not available on this phone at all (nothing to do).
     */
    fun ensure(userId: Int): Boolean {
        if (!isSupported()) return true
        synchronized(lockFor(userId)) {
            if (isLinked(userId)) return true
            val t0 = SystemClock.elapsedRealtime()
            // The engine skips packages its client wrapper believes are installed; make sure that
            // wrapper is talking to the real service and not to its fallback.
            runCatching { BlackBoxCore.getBPackageManager().resetTransactionThrottler() }
            var result = runCatching { BlackBoxCore.get().installGms(userId) }
                .onFailure { DsLog.e(TAG, "installGms(user=$userId) threw", it) }
                .getOrNull()
            var ok = result?.success == true && (directIsInstalled(GMS_PKG, userId) ?: true)
            if (!ok) {
                DsLog.w(TAG, "installGms(user=$userId) did not leave Play Services installed (success=${result?.success}); re-initialising the package client and retrying")
                runCatching { BlackBoxCore.getBPackageManager().forceReinitialize() }
                result = runCatching { BlackBoxCore.get().installGms(userId) }
                    .onFailure { DsLog.e(TAG, "installGms(user=$userId) retry threw", it) }
                    .getOrNull()
                ok = result?.success == true && (directIsInstalled(GMS_PKG, userId) ?: true)
            }
            linkedCache[userId] = ok
            DsLog.i(TAG, "installGms(user=$userId) -> ${if (ok) "linked" else "FAILED"} in " +
                "${SystemClock.elapsedRealtime() - t0} ms ${result?.msg.orEmpty()}")
            return ok
        }
    }

    /**
     * Link GMS into every slot that has clones, and re-link everywhere if the phone's Play
     * Services was updated since we last looked. Safe to call often; it is cheap when
     * nothing changed. Publishes a fresh [status] when done.
     */
    fun syncAll(context: Context) {
        if (!isSupported()) { refreshStatus(); return }
        val hostVersion = hostGmsVersion(context)
        val changed = hostVersion != Prefs.hostGmsVersion
        if (changed) DsLog.i(TAG, "host Play Services changed (${Prefs.hostGmsVersion} -> $hostVersion); re-linking all slots")
        for (userId in CloneStore.userIds()) {
            synchronized(lockFor(userId)) {
                if (changed && isLinked(userId)) {
                    runCatching { BlackBoxCore.get().uninstallGms(userId) }
                        .onFailure { DsLog.w(TAG, "uninstallGms(user=$userId) threw", it) }
                    linkedCache.remove(userId)
                }
            }
            ensure(userId)
        }
        if (changed) Prefs.hostGmsVersion = hostVersion
        refreshStatus()
    }

    /** Re-check everything and publish. IO thread only. */
    fun refreshStatus(): Status {
        val host = isSupported()
        val engine = isEngineReachable()
        val linked = LinkedHashMap<Int, Boolean>()
        if (engine) {
            for (userId in CloneStore.userIds().sorted()) linked[userId] = host && isLinked(userId)
        } else {
            for (userId in CloneStore.userIds().sorted()) linked[userId] = false
        }
        val s = Status(host, engine, linked)
        statusLive.postValue(s)
        DsLog.i(TAG, "status: host=$host engine=$engine linked=$linked -> ${if (s.connected) "CONNECTED" else "DISCONNECTED"}")
        return s
    }

    /**
     * What the "Fix" button does: wake the engine if needed, then (re)link every slot that
     * is missing the mirror. IO thread only.
     */
    fun repairAll(): Status {
        DsLog.i(TAG, "repair requested by user")
        if (!isEngineReachable()) {
            runCatching { BlackBoxCore.get().ensureBlackProcessInitialized() }
                .onFailure { DsLog.w(TAG, "ensureBlackProcessInitialized threw", it) }
            // Give the service process a moment to come up.
            var waited = 0
            while (!isEngineReachable() && waited < 8_000) { SystemClock.sleep(500); waited += 500 }
        }
        if (isSupported()) {
            for (userId in CloneStore.userIds()) {
                if (!ensure(userId)) {
                    // A half-installed mirror can report "installed" for some packages only;
                    // wipe and redo once.
                    synchronized(lockFor(userId)) {
                        runCatching { BlackBoxCore.get().uninstallGms(userId) }
                        linkedCache.remove(userId)
                    }
                    ensure(userId)
                }
            }
        }
        return refreshStatus()
    }

    private fun hostGmsVersion(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(GMS_PKG, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: PackageManager.NameNotFoundException) {
        -1L
    }
}

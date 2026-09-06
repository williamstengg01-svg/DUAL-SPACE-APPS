package com.dualspace.clone.ui

import android.app.Activity
import android.content.Intent
import com.dualspace.clone.util.Prefs

/**
 * Session-wide unlock state. The host app asks for the PIN/biometric once per foreground
 * session; individual clones marked `locked` ask again on every launch.
 */
object LockGate {
    @Volatile
    private var unlockedAt = 0L
    private const val SESSION_MS = 2 * 60 * 1000L

    fun isUnlocked(): Boolean = !Prefs.lockEnabled || !Prefs.hasPin() ||
        System.currentTimeMillis() - unlockedAt < SESSION_MS

    fun markUnlocked() { unlockedAt = System.currentTimeMillis() }

    fun lock() { unlockedAt = 0L }

    /** Returns true when the caller may continue; otherwise the lock screen was started. */
    fun requireUnlock(activity: Activity, requestCode: Int, force: Boolean = false): Boolean {
        if (!force && isUnlocked()) return true
        if (!Prefs.hasPin()) return true
        activity.startActivityForResult(Intent(activity, LockActivity::class.java), requestCode)
        return false
    }
}

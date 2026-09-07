package com.dualspace.clone.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.dualspace.clone.DualSpaceApp
import java.security.MessageDigest

/**
 * Small typed wrapper over SharedPreferences for host-app settings.
 *
 * Self-initialising: if something touches it before `DualSpaceApp.onCreate` ran (or in a
 * process the engine mis-classified), it falls back to the application context instead of
 * throwing `UninitializedPropertyAccessException` and taking the UI down.
 */
object Prefs {
    private const val FILE = "dualspace_prefs"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private val sp: SharedPreferences
        get() = prefs ?: synchronized(this) {
            prefs ?: DualSpaceApp.appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).also { prefs = it }
        }

    // ---- first-run / setup wizard ----
    var setupCompleted: Boolean
        get() = sp.getBoolean("setup_completed", false)
        set(v) = sp.edit { putBoolean("setup_completed", v) }

    fun isStepDone(key: String) = sp.getBoolean("setup_step_$key", false)
    fun setStepDone(key: String, done: Boolean) = sp.edit { putBoolean("setup_step_$key", done) }

    // ---- app lock ----
    var lockEnabled: Boolean
        get() = sp.getBoolean("lock_enabled", false)
        set(v) = sp.edit { putBoolean("lock_enabled", v) }

    var biometricEnabled: Boolean
        get() = sp.getBoolean("biometric_enabled", true)
        set(v) = sp.edit { putBoolean("biometric_enabled", v) }

    fun hasPin(): Boolean = sp.getString("pin_hash", null) != null

    fun setPin(pin: String) = sp.edit { putString("pin_hash", sha256(pin)) }

    fun checkPin(pin: String): Boolean = sp.getString("pin_hash", null) == sha256(pin)

    fun clearPin() = sp.edit { remove("pin_hash") }

    // ---- UI ----
    var showHidden: Boolean
        get() = sp.getBoolean("show_hidden", false)
        set(v) = sp.edit { putBoolean("show_hidden", v) }

    var themeMode: String
        get() = sp.getString("theme_mode", "system") ?: "system"
        set(v) = sp.edit { putString("theme_mode", v) }

    // ---- permissions for clones ----
    /** Dual Space asked the user for location once (clones inherit it); never nag again. */
    var locationAsked: Boolean
        get() = sp.getBoolean("location_asked", false)
        set(v) = sp.edit { putBoolean("location_asked", v) }

    // ---- diagnostics ----
    /** When the user last opened Diagnostics; newer crash reports trigger a nudge on the home screen. */
    var lastReportSeenAt: Long
        get() = sp.getLong("last_report_seen_at", 0L)
        set(v) = sp.edit { putLong("last_report_seen_at", v) }

    // ---- GMS bookkeeping ----
    var hostGmsVersion: Long
        get() = sp.getLong("host_gms_version", -1L)
        set(v) = sp.edit { putLong("host_gms_version", v) }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

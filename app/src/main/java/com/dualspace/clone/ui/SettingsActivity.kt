package com.dualspace.clone.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.dualspace.clone.BuildConfig
import com.dualspace.clone.R
import com.dualspace.clone.data.GmsLinker
import com.dualspace.clone.util.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.niunaijun.blackbox.BlackBoxCore

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.container, SettingsFragment()).commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == android.R.id.home) { finish(); true } else super.onOptionsItemSelected(item)

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            val lock = findPreference<SwitchPreferenceCompat>("lock_enabled")!!
            lock.isChecked = Prefs.lockEnabled && Prefs.hasPin()
            lock.setOnPreferenceChangeListener { _, v ->
                if (v as Boolean) {
                    startActivityForResult(Intent(requireContext(), LockActivity::class.java).putExtra(LockActivity.EXTRA_SET_PIN, true), 1)
                    false // only flip once the PIN is really set
                } else { Prefs.lockEnabled = false; LockGate.lock(); true }
            }

            findPreference<Preference>("change_pin")!!.setOnPreferenceClickListener {
                startActivityForResult(Intent(requireContext(), LockActivity::class.java).putExtra(LockActivity.EXTRA_SET_PIN, true), 1); true
            }

            val bio = findPreference<SwitchPreferenceCompat>("biometric")!!
            bio.isChecked = Prefs.biometricEnabled
            bio.setOnPreferenceChangeListener { _, v -> Prefs.biometricEnabled = v as Boolean; true }

            val theme = findPreference<ListPreference>("theme")!!
            theme.value = Prefs.themeMode
            theme.setOnPreferenceChangeListener { _, v ->
                Prefs.themeMode = v as String
                AppCompatDelegate.setDefaultNightMode(
                    when (v) { "light" -> AppCompatDelegate.MODE_NIGHT_NO; "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM })
                true
            }

            findPreference<Preference>("setup")!!.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), SetupWizardActivity::class.java)); true
            }

            findPreference<Preference>("diagnostics")!!.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DiagnosticsActivity::class.java)); true
            }

            findPreference<Preference>("gms")!!.apply {
                val s = GmsLinker.lastStatus
                summary = when {
                    !GmsLinker.isSupported() -> getString(R.string.pref_gms_summary_none)
                    s != null && !s.connected -> getString(R.string.gms_status_disconnected)
                    else -> getString(R.string.pref_gms_summary_ok)
                }
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), DiagnosticsActivity::class.java)); true
                }
            }

            val playStore = findPreference<SwitchPreferenceCompat>("mirror_play_store")!!
            playStore.isChecked = Prefs.mirrorPlayStore
            playStore.setOnPreferenceChangeListener { _, v ->
                Prefs.mirrorPlayStore = v as Boolean
                // Applying it touches the engine over Binder: never on the UI thread.
                Thread { runCatching { GmsLinker.applyPlayStorePreference() } }.start()
                true
            }

            findPreference<Preference>("about")!!.summary = getString(
                R.string.pref_about_summary, BuildConfig.VERSION_NAME,
                if (BlackBoxCore.is64Bit()) "arm64-v8a" else "armeabi-v7a")
            findPreference<Preference>("about")!!.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.about_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show(); true
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)
            if (requestCode == 1) {
                findPreference<SwitchPreferenceCompat>("lock_enabled")!!.isChecked = Prefs.lockEnabled && Prefs.hasPin()
            }
        }
    }
}

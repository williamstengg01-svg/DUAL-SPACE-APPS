package com.dualspace.clone.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.dualspace.clone.BuildConfig
import com.dualspace.clone.R
import com.dualspace.clone.data.CloneStore
import com.dualspace.clone.data.GmsLinker
import com.dualspace.clone.databinding.ActivityDiagnosticsBinding
import com.dualspace.clone.util.BrandCompat
import com.dualspace.clone.util.CrashLog
import top.niunaijun.blackbox.BlackBoxCore

/** Shows device info and the last crash reports so the user can share them for support. */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDiagnosticsBinding
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.btnShare.setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Dual Space diagnostics")
                .putExtra(Intent.EXTRA_TEXT, report), getString(R.string.action_share)))
        }
        b.btnClear.setOnClickListener { CrashLog.clear(this); render() }
        render()
    }

    private fun render() {
        val crashes = CrashLog.recent(this)
        report = buildString {
            appendLine("Dual Space ${BuildConfig.VERSION_NAME} (${if (BlackBoxCore.is64Bit()) "arm64" else "arm32"})")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${getString(BrandCompat.detect().label)}")
            appendLine("Clones: ${CloneStore.all().size} · GMS on phone: ${GmsLinker.isSupported()} · battery-optimisation exempt: ${BrandCompat.isIgnoringBatteryOptimizations(this@DiagnosticsActivity)}")
            appendLine()
            if (crashes.isEmpty()) appendLine(getString(R.string.diag_no_crashes))
            crashes.forEachIndexed { i, (name, text) ->
                appendLine("═══ ${i + 1}. $name ═══")
                appendLine(text)
            }
        }
        b.text.text = report
        b.btnClear.isEnabled = crashes.isNotEmpty()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean = true

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == android.R.id.home) { finish(); true } else super.onOptionsItemSelected(item)
}

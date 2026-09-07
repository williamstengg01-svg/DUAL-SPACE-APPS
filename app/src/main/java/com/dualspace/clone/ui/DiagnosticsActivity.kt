package com.dualspace.clone.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.dualspace.clone.BuildConfig
import com.dualspace.clone.R
import com.dualspace.clone.data.CloneStore
import com.dualspace.clone.data.GmsLinker
import com.dualspace.clone.databinding.ActivityDiagnosticsBinding
import com.dualspace.clone.util.BrandCompat
import com.dualspace.clone.util.CrashLog
import com.dualspace.clone.util.DsLog
import com.dualspace.clone.util.Prefs
import com.dualspace.clone.util.StorageUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shows device info, the Play Services link status, every crash / failure / ANR report and
 * the tail of the log file, and lets the user share or save the whole thing. Nothing is
 * uploaded automatically.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var b: ActivityDiagnosticsBinding
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        Prefs.lastReportSeenAt = System.currentTimeMillis()
        b.btnShare.setOnClickListener { shareFile() }
        b.btnExport.setOnClickListener { export() }
        b.btnRefresh.setOnClickListener { render() }
        b.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_clear_logs)
                .setMessage(R.string.confirm_clear_logs)
                .setPositiveButton(R.string.action_clear_logs) { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { CrashLog.clear(this@DiagnosticsActivity); DsLog.clear() }
                        render()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        render()
    }

    private fun header(): String = buildString {
        appendLine("Dual Space ${BuildConfig.VERSION_NAME} (${if (BlackBoxCore.is64Bit()) "arm64" else "arm32"})")
        appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${getString(BrandCompat.detect().label)}")
        appendLine("Clones: ${CloneStore.all().size} · battery-optimisation exempt: ${BrandCompat.isIgnoringBatteryOptimizations(this@DiagnosticsActivity)} · all-files access: ${BrandCompat.hasStorageAccess(this@DiagnosticsActivity)}")
    }

    private fun gmsLine(s: GmsLinker.Status?): String = when {
        s == null -> "Google Play Services: (not checked yet)"
        s.connected -> "Google Play Services: CONNECTED (linked in every clone slot)"
        !s.hostHasGms -> "Google Play Services: DISCONNECTED — not installed on this phone"
        !s.engineReachable -> "Google Play Services: DISCONNECTED — engine service process not reachable"
        else -> "Google Play Services: DISCONNECTED — not linked in slot(s) ${s.unlinkedSlots}"
    }

    /** Everything, for sharing: header, status, reports, full log. Built on IO. */
    private fun fullReport(status: GmsLinker.Status?): String = buildString {
        append(header())
        appendLine(gmsLine(status))
        appendLine("Log size: ${StorageUtil.human(DsLog.sizeBytes())} · file: ${DsLog.file()?.absolutePath}")
        appendLine()
        val crashes = CrashLog.recent(this@DiagnosticsActivity)
        appendLine("═══════════ Crash / failure / ANR reports (${crashes.size}) ═══════════")
        if (crashes.isEmpty()) appendLine(getString(R.string.diag_no_crashes))
        crashes.forEachIndexed { i, (name, text) ->
            appendLine("─── ${i + 1}. $name ───")
            appendLine(text)
        }
        appendLine()
        appendLine("═══════════ Log file ═══════════")
        append(DsLog.fullText())
    }

    private fun render() {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val status = runCatching { GmsLinker.refreshStatus() }.getOrNull() ?: GmsLinker.lastStatus
                val crashes = CrashLog.recent(this@DiagnosticsActivity)
                val tail = DsLog.tail(400)
                buildString {
                    append(header())
                    appendLine(gmsLine(status))
                    appendLine(getString(R.string.diag_log_header, StorageUtil.human(DsLog.sizeBytes()), tail.count { it == '\n' } + 1))
                    appendLine()
                    appendLine("═══════════ ${getString(R.string.diag_section_reports)} (${crashes.size}) ═══════════")
                    if (crashes.isEmpty()) appendLine(getString(R.string.diag_no_crashes))
                    crashes.forEachIndexed { i, (name, t) ->
                        appendLine("─── ${i + 1}. $name ───")
                        appendLine(t)
                    }
                    appendLine()
                    appendLine("═══════════ ${getString(R.string.diag_section_log)} ═══════════")
                    append(tail)
                }
            }
            report = text
            b.text.text = text
            b.progress.visibility = View.GONE
        }
    }

    /** Share the complete report as a text file through the system share sheet. */
    private fun shareFile() {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(cacheDir, "dualspace-report.txt")
                    f.writeText(fullReport(GmsLinker.lastStatus))
                    FileProvider.getUriForFile(this@DiagnosticsActivity, "$packageName.files", f)
                }.onFailure { DsLog.e("Diagnostics", "building share file failed", it) }.getOrNull()
            }
            b.progress.visibility = View.GONE
            if (uri == null) {
                // Fall back to plain text.
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Dual Space diagnostics")
                    .putExtra(Intent.EXTRA_TEXT, report), getString(R.string.action_share)))
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Dual Space diagnostics")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.action_share_log)))
        }
    }

    /** Copy the complete report into Download/DualSpace so any file manager can find it. */
    private fun export() {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = "dualspace-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
                    DsLog.exportToDownloads(this@DiagnosticsActivity, name, fullReport(GmsLinker.lastStatus))
                }
            }
            b.progress.visibility = View.GONE
            result.onSuccess {
                Snackbar.make(b.root, getString(R.string.msg_log_exported, it), Snackbar.LENGTH_LONG).show()
            }.onFailure {
                DsLog.e("Diagnostics", "export failed", it)
                Snackbar.make(b.root, getString(R.string.msg_log_export_failed, it.message ?: it.javaClass.simpleName), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean = true

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == android.R.id.home) { finish(); true } else super.onOptionsItemSelected(item)
}

package com.dualspace.clone.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dualspace.clone.R
import com.dualspace.clone.data.CloneManager
import com.dualspace.clone.data.CloneStore
import com.dualspace.clone.databinding.ActivityAppPickerBinding
import com.dualspace.clone.databinding.DialogProgressBinding
import com.dualspace.clone.databinding.ItemAppBinding
import com.dualspace.clone.model.InstalledApp
import com.dualspace.clone.util.DsLog
import com.dualspace.clone.util.FailureText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.niunaijun.blackbox.BlackBoxCore

/** Lists every launchable app on the phone; tapping one creates a new clone of it. */
class AppPickerActivity : AppCompatActivity() {

    private lateinit var b: ActivityAppPickerBinding
    private val adapter = AppAdapter { onPick(it) }
    private var all: List<InstalledApp> = emptyList()
    private var includeSystem = false
    private var query = ""
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.list.setHasFixedSize(true)
        load()
    }

    private fun load() {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) {
                runCatching { CloneManager.installedApps(this@AppPickerActivity, includeSystem) }
                    .onFailure { DsLog.e("AppPicker", "listing apps failed", it) }
                    .getOrDefault(emptyList())
            }
            b.progress.visibility = View.GONE
            filter()
        }
    }

    private fun filter() {
        val q = query.trim().lowercase()
        adapter.submit(if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) || it.packageName.contains(q) })
    }

    private fun onPick(app: InstalledApp) {
        if (busy) return
        busy = true
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            // Opening the APK to look at its native libraries takes a moment: do it here, for
            // this one app, instead of for every app while building the list.
            val supported = withContext(Dispatchers.IO) { CloneManager.isAbiSupported(app) }
            b.progress.visibility = View.GONE
            busy = false
            if (!supported) {
                val is64 = BlackBoxCore.is64Bit()
                MaterialAlertDialogBuilder(this@AppPickerActivity)
                    .setTitle(R.string.abi_title)
                    .setMessage(getString(if (is64) R.string.abi_need_32 else R.string.abi_need_64, app.label))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            val existing = CloneStore.byPackage(app.packageName).size
            val msg = if (existing == 0) getString(R.string.confirm_clone, app.label)
            else getString(R.string.confirm_clone_more, app.label, existing + 1)
            MaterialAlertDialogBuilder(this@AppPickerActivity)
                .setTitle(R.string.title_pick_app)
                .setMessage(msg)
                .setPositiveButton(R.string.action_clone) { _, _ -> create(app) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun create(app: InstalledApp) {
        if (busy) return
        busy = true
        val pb = DialogProgressBinding.inflate(layoutInflater)
        pb.title.text = getString(R.string.msg_cloning_app, app.label)
        IconCache.load(pb.icon, "app:${app.packageName}", packageManager.defaultActivityIcon) {
            runCatching { packageManager.getApplicationIcon(app.packageName) }.getOrNull()
        }
        val dlg = AlertDialog.Builder(this)
            .setView(pb.root)
            .setCancelable(false)
            .show()
        val main = Handler(Looper.getMainLooper())
        fun show(step: CloneManager.Step) {
            pb.step.setText(
                when (step) {
                    CloneManager.Step.PREPARING -> R.string.clone_step_preparing
                    CloneManager.Step.LINKING_GMS -> R.string.clone_step_gms
                    CloneManager.Step.INSTALLING -> R.string.clone_step_installing
                    CloneManager.Step.FINISHING -> R.string.clone_step_finishing
                    CloneManager.Step.DONE -> R.string.clone_step_done
                }
            )
            pb.percent.text = "${step.percent}%"
            pb.bar.setProgressCompat(step.percent, true)
            if (step == CloneManager.Step.DONE) pb.spinner.visibility = View.INVISIBLE
        }
        show(CloneManager.Step.PREPARING)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    CloneManager.createClone(this@AppPickerActivity, app.packageName) { step ->
                        main.post { show(step) }
                    }
                }.getOrElse { CloneManager.Result.Error(FailureText.describe(it)) }
            }
            busy = false
            runCatching { dlg.dismiss() }
            when (result) {
                is CloneManager.Result.Ok -> {
                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_CLONE_ID, result.clone.id))
                    finish()
                }
                is CloneManager.Result.Error -> MaterialAlertDialogBuilder(this@AppPickerActivity)
                    .setTitle(R.string.msg_clone_failed)
                    .setMessage(result.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.action_details) { _, _ ->
                        startActivity(Intent(this@AppPickerActivity, DiagnosticsActivity::class.java))
                    }
                    .show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_picker, menu)
        val sv = menu.findItem(R.id.action_search).actionView as SearchView
        sv.queryHint = getString(R.string.search_hint)
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean { query = q ?: ""; filter(); return true }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_system -> { includeSystem = !includeSystem; item.isChecked = includeSystem; load(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------ adapter

    private class AppAdapter(val onClick: (InstalledApp) -> Unit) : RecyclerView.Adapter<AppAdapter.VH>() {
        private val items = mutableListOf<InstalledApp>()
        fun submit(list: List<InstalledApp>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(app: InstalledApp) {
                val pm = b.root.context.packageManager
                IconCache.load(b.icon, "app:${app.packageName}", pm.defaultActivityIcon) {
                    runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull()
                }
                b.label.text = app.label
                b.pkg.text = app.packageName
                val n = CloneStore.byPackage(app.packageName).size
                b.count.visibility = if (n > 0) View.VISIBLE else View.GONE
                b.count.text = b.root.context.getString(R.string.clones_existing, n)
                b.abi.visibility = View.GONE
                b.root.alpha = 1f
                b.root.setOnClickListener { onClick(app) }
            }
        }
    }

    companion object {
        const val RESULT_CLONE_ID = "clone_id"
    }
}

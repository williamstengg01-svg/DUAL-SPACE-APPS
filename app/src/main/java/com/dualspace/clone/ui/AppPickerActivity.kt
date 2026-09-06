package com.dualspace.clone.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
import com.dualspace.clone.databinding.ItemAppBinding
import com.dualspace.clone.model.InstalledApp
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        load()
    }

    private fun load() {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) { CloneManager.installedApps(this@AppPickerActivity, includeSystem) }
            b.progress.visibility = View.GONE
            filter()
        }
    }

    private fun filter() {
        val q = query.trim().lowercase()
        adapter.submit(if (q.isEmpty()) all else all.filter { it.label.lowercase().contains(q) || it.packageName.contains(q) })
    }

    private fun onPick(app: InstalledApp) {
        if (!app.abiSupported) {
            val is64 = BlackBoxCore.is64Bit()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.abi_title)
                .setMessage(getString(if (is64) R.string.abi_need_32 else R.string.abi_need_64, app.label))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val existing = CloneStore.byPackage(app.packageName).size
        val msg = if (existing == 0) getString(R.string.confirm_clone, app.label)
        else getString(R.string.confirm_clone_more, app.label, existing + 1)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.title_pick_app)
            .setMessage(msg)
            .setPositiveButton(R.string.action_clone) { _, _ -> create(app) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun create(app: InstalledApp) {
        val dlg = AlertDialog.Builder(this)
            .setView(R.layout.dialog_progress)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { CloneManager.createClone(this@AppPickerActivity, app.packageName) }
            dlg.dismiss()
            when (result) {
                is CloneManager.Result.Ok -> {
                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_CLONE_ID, result.clone.id))
                    finish()
                }
                is CloneManager.Result.Error -> MaterialAlertDialogBuilder(this@AppPickerActivity)
                    .setTitle(R.string.msg_clone_failed)
                    .setMessage(result.message)
                    .setPositiveButton(android.R.string.ok, null)
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
                b.icon.setImageDrawable(runCatching { pm.getApplicationIcon(app.packageName) }.getOrNull())
                b.label.text = app.label
                b.pkg.text = app.packageName
                val n = CloneStore.byPackage(app.packageName).size
                b.count.visibility = if (n > 0) View.VISIBLE else View.GONE
                b.count.text = b.root.context.getString(R.string.clones_existing, n)
                b.abi.visibility = if (app.abiSupported) View.GONE else View.VISIBLE
                b.root.alpha = if (app.abiSupported) 1f else 0.5f
                b.root.setOnClickListener { onClick(app) }
            }
        }
    }

    companion object {
        const val RESULT_CLONE_ID = "clone_id"
    }
}

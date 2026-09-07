package com.dualspace.clone.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.dualspace.clone.R
import com.dualspace.clone.data.CloneManager
import com.dualspace.clone.data.CloneStore
import com.dualspace.clone.databinding.ActivityMainBinding
import com.dualspace.clone.model.Clone
import com.dualspace.clone.util.Prefs
import com.dualspace.clone.util.StorageUtil
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dualspace.clone.util.FailureText

/** Home screen: grid of every clone, tap to launch, long-press to manage, FAB to add. */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var adapter: CloneAdapter
    private var pendingLaunch: Clone? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        adapter = CloneAdapter(
            onClick = { launchClone(it) },
            onLongClick = { openDetail(it) }
        )
        val span = resources.getInteger(R.integer.grid_span)
        b.grid.layoutManager = GridLayoutManager(this, span)
        b.grid.adapter = adapter

        b.fab.setOnClickListener {
            startActivityForResult(Intent(this, AppPickerActivity::class.java), REQ_PICK)
        }

        CloneStore.clonesLive.observe(this) { render(it) }
    }

    override fun onResume() {
        super.onResume()
        if (!LockGate.requireUnlock(this, REQ_UNLOCK_APP)) return
        refreshStorage()
    }

    private fun render(all: List<Clone>) {
        val visible = if (Prefs.showHidden) all else all.filter { !it.hidden }
        adapter.submit(visible.sortedWith(compareBy({ it.packageName }, { it.index })))
        b.empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        b.grid.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
        b.subtitle.text = resources.getQuantityString(R.plurals.clone_count, all.size, all.size)
        refreshStorage()
    }

    private fun refreshStorage() {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { CloneManager.totalStorageBytes() }.getOrDefault(0L) }
            b.storage.text = getString(R.string.storage_total, StorageUtil.human(bytes))
        }
    }

    private fun launchClone(clone: Clone) {
        if (clone.frozen) {
            Snackbar.make(b.root, R.string.msg_frozen, Snackbar.LENGTH_SHORT)
                .setAction(R.string.action_unfreeze) { lifecycleScope.launch(Dispatchers.IO) { CloneManager.setFrozen(clone, false) } }
                .show()
            return
        }
        if (clone.locked) {
            pendingLaunch = clone
            if (!LockGate.requireUnlock(this, REQ_UNLOCK_CLONE, force = true)) return
        }
        doLaunch(clone)
    }

    private fun doLaunch(clone: Clone) {
        b.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CloneManager.launch(clone) }
                    .getOrElse { CloneManager.LaunchResult.Failed(FailureText.describe(it)) }
            }
            b.progress.visibility = View.GONE
            if (result is CloneManager.LaunchResult.Failed) {
                Snackbar.make(b.root, getString(R.string.msg_launch_failed_reason, result.reason), Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_details) {
                        startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java))
                    }
                    .show()
            }
        }
    }

    private fun openDetail(clone: Clone) {
        startActivity(Intent(this, CloneDetailActivity::class.java).putExtra(CloneDetailActivity.EXTRA_ID, clone.id))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_UNLOCK_APP -> if (resultCode != Activity.RESULT_OK) finish()
            REQ_UNLOCK_HIDDEN -> if (resultCode == Activity.RESULT_OK) toggleHidden()
            REQ_UNLOCK_CLONE -> {
                val c = pendingLaunch; pendingLaunch = null
                if (resultCode == Activity.RESULT_OK && c != null) doLaunch(c)
            }
            REQ_PICK -> if (resultCode == Activity.RESULT_OK) {
                val id = data?.getStringExtra(AppPickerActivity.RESULT_CLONE_ID) ?: return
                val clone = CloneStore.byId(id) ?: return
                Snackbar.make(b.root, getString(R.string.msg_clone_created, clone.label), Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_open) { launchClone(clone) }
                    .show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_show_hidden).isChecked = Prefs.showHidden
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_show_hidden -> {
            if (!Prefs.showHidden && Prefs.hasPin()) {
                // Revealing hidden clones always requires the PIN.
                if (LockGate.requireUnlock(this, REQ_UNLOCK_HIDDEN, force = true)) toggleHidden()
            } else toggleHidden()
            true
        }
        R.id.action_setup -> { startActivity(Intent(this, SetupWizardActivity::class.java)); true }
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun toggleHidden() {
        Prefs.showHidden = !Prefs.showHidden
        invalidateOptionsMenu()
        render(CloneStore.all())
    }

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_UNLOCK_APP = 2
        private const val REQ_UNLOCK_CLONE = 3
        private const val REQ_UNLOCK_HIDDEN = 4
    }
}

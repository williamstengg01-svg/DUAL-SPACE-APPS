package com.dualspace.clone.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import com.dualspace.clone.R
import com.dualspace.clone.data.CloneManager
import com.dualspace.clone.data.CloneStore
import com.dualspace.clone.data.GmsLinker
import com.dualspace.clone.databinding.ActivityCloneDetailBinding
import com.dualspace.clone.model.Clone
import com.dualspace.clone.util.FailureText
import com.dualspace.clone.util.Prefs
import com.dualspace.clone.util.StorageUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Per-clone management: rename, icon, freeze, hide, lock, shortcut, clear data, delete. */
class CloneDetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityCloneDetailBinding
    private lateinit var clone: Clone

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCloneDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        clone = CloneStore.byId(intent.getStringExtra(EXTRA_ID) ?: "") ?: run { finish(); return }

        b.btnOpen.setOnClickListener {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { CloneManager.launch(clone) }
                        .getOrElse { CloneManager.LaunchResult.Failed(FailureText.describe(it)) }
                }
                if (result is CloneManager.LaunchResult.Failed) {
                    Toast.makeText(this@CloneDetailActivity, getString(R.string.msg_launch_failed_reason, result.reason), Toast.LENGTH_LONG).show()
                }
                renderGms()
            }
        }
        b.btnRename.setOnClickListener { rename() }
        b.btnIcon.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_ICON)
        }
        b.btnResetIcon.setOnClickListener {
            clone.customIconPath?.let { File(it).delete() }
            clone.customIconPath = null
            IconCache.invalidatePrefix("clone:${clone.id}:")
            save()
        }
        b.switchFreeze.setOnCheckedChangeListener { _, on ->
            if (on != clone.frozen) lifecycleScope.launch(Dispatchers.IO) { runCatching { CloneManager.setFrozen(clone, on) } }
        }
        b.switchHide.setOnCheckedChangeListener { _, on -> if (on != clone.hidden) { clone.hidden = on; save() } }
        b.switchLock.setOnCheckedChangeListener { _, on ->
            if (on == clone.locked) return@setOnCheckedChangeListener
            if (on && !Prefs.hasPin()) {
                b.switchLock.isChecked = false
                startActivityForResult(Intent(this, LockActivity::class.java).putExtra(LockActivity.EXTRA_SET_PIN, true), REQ_SET_PIN)
            } else { clone.locked = on; save() }
        }
        b.btnShortcut.setOnClickListener { addShortcut() }
        b.btnClear.setOnClickListener { confirm(R.string.action_clear_data, R.string.confirm_clear) { CloneManager.clearData(clone) } }
        b.btnDelete.setOnClickListener {
            confirm(R.string.action_delete, R.string.confirm_delete) { CloneManager.delete(clone) }
        }
        // Tapping the red status line re-links Play Services for this slot.
        b.gms.setOnClickListener { repairGms() }
        render()
    }

    private fun render() {
        supportActionBar?.title = clone.label
        IconCache.load(b.icon, CloneAdapter.iconKey(clone), packageManager.defaultActivityIcon) {
            CloneManager.icon(this, clone)
        }
        b.name.text = clone.label
        b.pkg.text = "${clone.packageName}  ·  ${getString(R.string.slot_n, clone.userId)}"
        b.switchFreeze.isChecked = clone.frozen
        b.switchHide.isChecked = clone.hidden
        b.switchLock.isChecked = clone.locked
        b.btnResetIcon.isEnabled = clone.customIconPath != null
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { CloneManager.storageBytes(clone) }.getOrDefault(0L) }
            b.storage.text = getString(R.string.storage_used, StorageUtil.human(bytes))
        }
        renderGms()
    }

    /** The link check is a Binder call into the engine: never on the main thread. */
    private fun renderGms() {
        // Paint what we already know right away, then confirm in the background.
        paintGms(GmsLinker.cachedLinked(clone.userId), GmsLinker.isSupported())
        lifecycleScope.launch {
            val supported = GmsLinker.isSupported()
            val linked = withContext(Dispatchers.IO) { runCatching { GmsLinker.isLinked(clone.userId) }.getOrDefault(false) }
            paintGms(linked, supported)
        }
    }

    private fun paintGms(linked: Boolean?, supported: Boolean) {
        val (text, dot, color) = when {
            !supported -> Triple(R.string.gms_unavailable, R.drawable.dot_red, android.R.color.holo_red_dark)
            linked == true -> Triple(R.string.gms_linked, R.drawable.dot_green, android.R.color.holo_green_dark)
            linked == false -> Triple(R.string.gms_not_linked, R.drawable.dot_red, android.R.color.holo_red_dark)
            else -> Triple(R.string.gms_status_checking, R.drawable.dot_grey, android.R.color.darker_gray)
        }
        b.gms.setText(text)
        b.gms.setTextColor(ContextCompat.getColor(this, color))
        b.gms.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, 0, 0)
        b.gms.compoundDrawablePadding = (6 * resources.displayMetrics.density).toInt()
        b.gms.isClickable = supported && linked == false
    }

    private fun repairGms() {
        if (!GmsLinker.isSupported()) return
        Snackbar.make(b.root, R.string.msg_gms_repairing, Snackbar.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { GmsLinker.ensure(clone.userId) }.getOrDefault(false) }
            renderGms()
            Snackbar.make(b.root, if (ok) R.string.msg_gms_repaired else R.string.msg_gms_repair_failed, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun save() { CloneStore.update(clone); render() }

    private fun rename() {
        val view = layoutInflater.inflate(R.layout.dialog_rename, null)
        val input = view.findViewById<TextInputEditText>(R.id.input)
        input.setText(clone.label)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_rename)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = input.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) { clone.label = t; save() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addShortcut() {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(this)) {
            Snackbar.make(b.root, R.string.msg_shortcut_unsupported, Snackbar.LENGTH_LONG).show(); return
        }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { CloneManager.icon(this@CloneDetailActivity, clone).toBitmap(192, 192) }.getOrNull() }
                ?: return@launch
            val intent = Intent(this@CloneDetailActivity, ShortcutActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .putExtra(ShortcutActivity.EXTRA_ID, clone.id)
            val info = ShortcutInfoCompat.Builder(this@CloneDetailActivity, "clone_${clone.id}")
                .setIntent(intent)
                .setShortLabel(clone.label)
                .setLongLabel(clone.label)
                .setIcon(IconCompat.createWithBitmap(bmp))
                .build()
            runCatching { ShortcutManagerCompat.requestPinShortcut(this@CloneDetailActivity, info, null) }
            Snackbar.make(b.root, R.string.msg_shortcut_requested, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun confirm(titleRes: Int, msgRes: Int, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(msgRes)
            .setPositiveButton(titleRes) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { runCatching(action) }
                    if (CloneStore.byId(clone.id) == null) finish() else render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_ICON -> if (resultCode == Activity.RESULT_OK) {
                val uri = data?.data ?: return
                lifecycleScope.launch {
                    val path = withContext(Dispatchers.IO) {
                        runCatching {
                            val src = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
                            val scaled = Bitmap.createScaledBitmap(src, 256, 256, true)
                            val dir = File(filesDir, "icons").apply { mkdirs() }
                            val f = File(dir, "${clone.id}.png")
                            FileOutputStream(f).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            f.absolutePath
                        }.getOrNull()
                    }
                    if (path != null) {
                        clone.customIconPath = path
                        IconCache.invalidatePrefix("clone:${clone.id}:")
                        save()
                    }
                }
            }
            REQ_SET_PIN -> if (resultCode == Activity.RESULT_OK) { clone.locked = true; save() }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (item.itemId == android.R.id.home) { finish(); true } else super.onOptionsItemSelected(item)

    companion object {
        const val EXTRA_ID = "clone_id"
        private const val REQ_ICON = 10
        private const val REQ_SET_PIN = 11
    }
}

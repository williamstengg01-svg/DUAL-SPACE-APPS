package com.dualspace.clone.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
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
import android.widget.Toast
import com.dualspace.clone.util.FailureText

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
            }
        }
        b.btnRename.setOnClickListener { rename() }
        b.btnIcon.setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), REQ_ICON)
        }
        b.btnResetIcon.setOnClickListener {
            clone.customIconPath?.let { File(it).delete() }
            clone.customIconPath = null
            save()
        }
        b.switchFreeze.setOnCheckedChangeListener { _, on ->
            if (on != clone.frozen) lifecycleScope.launch(Dispatchers.IO) { CloneManager.setFrozen(clone, on) }
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
        render()
    }

    private fun render() {
        supportActionBar?.title = clone.label
        b.icon.setImageDrawable(CloneManager.icon(this, clone))
        b.name.text = clone.label
        b.pkg.text = "${clone.packageName}  ·  ${getString(R.string.slot_n, clone.userId)}"
        b.switchFreeze.isChecked = clone.frozen
        b.switchHide.isChecked = clone.hidden
        b.switchLock.isChecked = clone.locked
        b.btnResetIcon.isEnabled = clone.customIconPath != null
        b.gms.text = getString(
            if (!GmsLinker.isSupported()) R.string.gms_unavailable
            else if (GmsLinker.isLinked(clone.userId)) R.string.gms_linked else R.string.gms_linking
        )
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { CloneManager.storageBytes(clone) }.getOrDefault(0L) }
            b.storage.text = getString(R.string.storage_used, StorageUtil.human(bytes))
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
        val intent = Intent(this, ShortcutActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .putExtra(ShortcutActivity.EXTRA_ID, clone.id)
        val bmp = CloneManager.icon(this, clone).toBitmap(192, 192)
        val info = ShortcutInfoCompat.Builder(this, "clone_${clone.id}")
            .setIntent(intent)
            .setShortLabel(clone.label)
            .setLongLabel(clone.label)
            .setIcon(IconCompat.createWithBitmap(bmp))
            .build()
        ShortcutManagerCompat.requestPinShortcut(this, info, null)
        Snackbar.make(b.root, R.string.msg_shortcut_requested, Snackbar.LENGTH_LONG).show()
    }

    private fun confirm(titleRes: Int, msgRes: Int, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(msgRes)
            .setPositiveButton(titleRes) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { action() }
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
                    if (path != null) { clone.customIconPath = path; save() }
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

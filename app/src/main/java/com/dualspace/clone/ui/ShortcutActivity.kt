package com.dualspace.clone.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dualspace.clone.R
import com.dualspace.clone.data.CloneManager
import com.dualspace.clone.data.CloneStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.dualspace.clone.util.FailureText

/** Invisible trampoline used by home-screen shortcuts: launch one clone, then go away. */
class ShortcutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val clone = CloneStore.byId(intent.getStringExtra(EXTRA_ID) ?: "")
        if (clone == null) {
            Toast.makeText(this, R.string.msg_clone_missing, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        if (clone.locked && !LockGate.requireUnlock(this, REQ_UNLOCK, force = true)) return
        launch()
    }

    private fun launch() {
        val clone = CloneStore.byId(intent.getStringExtra(EXTRA_ID) ?: "") ?: run { finish(); return }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CloneManager.launch(clone) }
                    .getOrElse { CloneManager.LaunchResult.Failed(FailureText.describe(it)) }
            }
            if (result is CloneManager.LaunchResult.Failed) {
                Toast.makeText(this@ShortcutActivity, getString(R.string.msg_launch_failed_reason, result.reason), Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UNLOCK) {
            if (resultCode == Activity.RESULT_OK) launch() else finish()
        }
    }

    companion object {
        const val EXTRA_ID = "clone_id"
        private const val REQ_UNLOCK = 20
    }
}

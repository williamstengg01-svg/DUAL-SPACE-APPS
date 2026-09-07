package com.dualspace.clone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dualspace.clone.data.GmsLinker
import com.dualspace.clone.util.DsLog
import kotlinx.coroutines.launch

/**
 * After a reboot or a Dual Space update, re-check that Play Services inside every clone
 * slot still matches the phone's Play Services. Nothing else — clone data lives on disk
 * in the engine's virtual root and survives reboots by itself.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            DsLog.i("BootReceiver", "received $action; re-syncing GMS")
            val pending = goAsync()
            DualSpaceApp.appScope.launch {
                try {
                    GmsLinker.syncAll(context.applicationContext)
                } catch (t: Throwable) {
                    DsLog.e("BootReceiver", "GMS sync failed", t)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}

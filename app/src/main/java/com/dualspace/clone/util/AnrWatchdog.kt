package com.dualspace.clone.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Detects a stuck main thread ("isn't responding" territory) and records *where* it is
 * stuck. A daemon thread posts a tick to the main looper every couple of seconds; if the
 * tick has not run after [TIMEOUT_MS] (checked twice, to ignore the wake-up after the process
 * was frozen in the background) the main thread's stack is written to the log and to a
 * Diagnostics report. It only observes; it never kills anything.
 */
object AnrWatchdog {
    private const val TAG = "AnrWatchdog"
    private const val INTERVAL_MS = 2_000L
    private const val TIMEOUT_MS = 5_000L
    private const val COOLDOWN_MS = 60_000L

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        thread(name = "ds-anr-watchdog", isDaemon = true) { loop(appContext) }
    }

    private fun loop(context: Context) {
        val handler = Handler(Looper.getMainLooper())
        while (true) {
            val ticked = AtomicBoolean(false)
            handler.post { ticked.set(true) }
            sleep(TIMEOUT_MS)
            if (!ticked.get()) {
                // Second look: right after the process is un-frozen the queue is briefly late.
                sleep(1_500)
                if (!ticked.get()) {
                    report(context)
                    // Wait for the main thread to come back before watching again.
                    while (!ticked.get()) sleep(1_000)
                    sleep(COOLDOWN_MS)
                    continue
                }
            }
            sleep(INTERVAL_MS)
        }
    }

    private fun report(context: Context) {
        // The main thread is inside the system crash handler (crash dialog); that is not an ANR.
        if (CrashLog.handingToSystem) return
        val stack = runCatching {
            Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "    at $it" }
        }.getOrDefault("(stack unavailable)")
        DsLog.e(TAG, "main thread of '${DsLog.processLabel()}' unresponsive for >${TIMEOUT_MS} ms. Stack:\n$stack")
        CrashLog.noteAnr(context, stack)
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (ignored: InterruptedException) { }
    }
}

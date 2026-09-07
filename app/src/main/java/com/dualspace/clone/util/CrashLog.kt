package com.dualspace.clone.util

import android.content.Context
import android.os.Build
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device crash recorder, installed in *every* process Dual Space spawns (host UI, engine
 * server, each clone process). Nothing is uploaded; reports are shown and shared from
 * Settings → Diagnostics and mirrored into the log file ([DsLog]).
 *
 * Kill policy — the important part. The engine stacks several "crash prevention" handlers
 * that log an uncaught exception and then simply *return*. For a background thread that is
 * harmless (the thread dies, the process lives). For the **main thread** it is fatal in a
 * worse way: the looper is gone, the process keeps running with a frozen UI, and a few
 * seconds later Android shows "Dual Space isn't responding". So for main-thread crashes we
 * hand the exception to the system handler captured at startup, which kills the process
 * cleanly (and lets Android relaunch the clone), instead of letting it hang.
 */
object CrashLog {
    private const val TAG = "CrashLog"
    private const val MAX_FILES = 12

    @Volatile private var systemHandler: Thread.UncaughtExceptionHandler? = null
    @Volatile private var installed = false

    /** True while the system handler is showing the crash dialog; the ANR watchdog stays quiet then. */
    @Volatile var handingToSystem = false
        private set

    private fun dir(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, "crashes").apply { mkdirs() }

    /** Call first thing in `attachBaseContext`, before the engine class is touched. */
    fun captureSystemHandler() {
        if (systemHandler == null) systemHandler = Thread.getDefaultUncaughtExceptionHandler()
    }

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val engineChain = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable, "Crash") }
            val isMain = thread === Looper.getMainLooper().thread
            val sys = systemHandler
            if (isMain && sys != null) {
                // Clean death beats a zombie process: let Android's own handler kill us.
                DsLog.e(TAG, "main-thread crash in '${DsLog.processLabel()}' -> handing to system handler")
                handingToSystem = true
                sys.uncaughtException(thread, throwable)
            } else if (engineChain != null) {
                engineChain.uncaughtException(thread, throwable)
            } else {
                sys?.uncaughtException(thread, throwable)
            }
        }
    }

    /** Record a non-fatal failure (a clone that refused to start, an install that errored). */
    fun note(context: Context, what: String, t: Throwable) {
        runCatching { write(context.applicationContext, Thread.currentThread(), t, "Failure: $what") }
    }

    /** Record a suspected ANR (main thread unresponsive) with its stack. */
    fun noteAnr(context: Context, mainStack: String) {
        runCatching {
            val text = header("ANR (main thread not responding)", Looper.getMainLooper().thread) + mainStack
            save(context, "anr", text)
        }
    }

    private fun write(context: Context, thread: Thread, t: Throwable, title: String) {
        val text = header(title, thread) + DsLog.stackTrace(t)
        save(context, if (title.startsWith("Failure")) "failure" else "crash", text)
        DsLog.e(TAG, "$title on thread '${thread.name}'", t)
    }

    private fun header(title: String, thread: Thread): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val clone = cloneInfo()
        return buildString {
            appendLine(title)
            appendLine("Time: $stamp")
            appendLine("Process: ${DsLog.processLabel()}  (pid ${android.os.Process.myPid()})")
            if (clone != null) appendLine("Clone: $clone")
            appendLine("Thread: ${thread.name}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
        }
    }

    private fun save(context: Context, kind: String, text: String) {
        val file = File(dir(context), "${kind}_${System.currentTimeMillis()}.txt")
        file.writeText(text)
        prune(context)
    }

    private fun prune(context: Context) {
        dir(context).listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
    }

    /** Most recent reports, newest first. */
    fun recent(context: Context): List<Pair<String, String>> =
        dir(context).listFiles()?.sortedByDescending { it.lastModified() }
            ?.map { it.name to runCatching { it.readText() }.getOrDefault("") } ?: emptyList()

    fun clear(context: Context) { dir(context).listFiles()?.forEach { it.delete() } }

    /** Timestamp of the newest report, 0 when there is none. */
    fun newestReportTime(context: Context): Long =
        dir(context).listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L

    private fun cloneInfo(): String? = try {
        val pkg = top.niunaijun.blackbox.app.BActivityThread.getAppPackageName()
        if (pkg.isNullOrEmpty()) null else "$pkg (slot ${top.niunaijun.blackbox.app.BActivityThread.getUserId()})"
    } catch (t: Throwable) { null }
}

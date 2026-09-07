package com.dualspace.clone.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import top.niunaijun.blackbox.utils.Slog
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dual Space's on-device log file.
 *
 * One file, `files/logs/dualspace.log`, shared by every process the app spawns (host UI,
 * the engine's `:black` server, and each `:pN` clone process). Each line is appended with a
 * single O_APPEND write so lines from different processes never interleave. When the file
 * passes [MAX_BYTES] it is rotated to `dualspace.1.log`, so the pair never exceeds ~2 MB.
 *
 * It also acts as the sink for the engine's own logger ([Slog]) so warnings and errors from
 * inside the sandbox land in the same file, tagged with the process and the clone they came
 * from. Nothing is ever uploaded; the user exports or shares it from Settings → Diagnostics.
 *
 * Must be initialised in `Application.attachBaseContext` *before* the engine class loads.
 */
object DsLog : Slog.Sink {
    private const val TAG = "DsLog"
    private const val FILE_NAME = "dualspace.log"
    private const val ROTATED_NAME = "dualspace.1.log"
    private const val MAX_BYTES = 1L * 1024 * 1024

    private val lock = Any()
    @Volatile private var dir: File? = null
    @Volatile private var processLabel = "?"
    private var writesSinceSizeCheck = 0
    private val stamp = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    // ------------------------------------------------------------------ setup

    fun init(context: Context) {
        if (dir != null) return
        val d = File(context.filesDir, "logs")
        runCatching { d.mkdirs() }
        dir = d
        processLabel = computeProcessLabel(context)
    }

    val isReady: Boolean get() = dir != null

    fun file(): File? = dir?.let { File(it, FILE_NAME) }
    fun rotatedFile(): File? = dir?.let { File(it, ROTATED_NAME) }

    /** Short name of this process: `main`, `black` (engine server) or `p3` (a clone). */
    fun processLabel(): String = processLabel

    fun logProcessStart(versionName: String) {
        i(TAG, "=== process '$processLabel' started · Dual Space $versionName · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · pid ${android.os.Process.myPid()} ===")
    }

    // ------------------------------------------------------------------ api

    fun d(tag: String, msg: String) = write(Log.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = write(Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write(Log.WARN, tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write(Log.ERROR, tag, msg, t)

    /** Engine ([Slog]) messages arrive here; they already went to logcat. */
    override fun log(priority: Int, tag: String, msg: String) {
        append(priority, "engine/$tag", msg)
    }

    private fun write(priority: Int, tag: String, msg: String, t: Throwable?) {
        val full = if (t == null) msg else msg + "\n" + stackTrace(t)
        Log.println(priority, tag, full)
        append(priority, tag, full)
    }

    private fun append(priority: Int, tag: String, msg: String) {
        val d = dir ?: return
        val line = buildString(msg.length + 64) {
            append(stamp.get()!!.format(Date()))
            append(' ').append(levelChar(priority)).append('/').append(processLabel)
            cloneSuffix()?.let { append('[').append(it).append(']') }
            append(' ').append(tag).append(": ").append(msg)
            if (!msg.endsWith('\n')) append('\n')
        }
        val bytes = line.toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            try {
                val f = File(d, FILE_NAME)
                if (++writesSinceSizeCheck >= 50 || !f.exists()) {
                    writesSinceSizeCheck = 0
                    if (f.length() > MAX_BYTES) rotate(d, f)
                }
                FileOutputStream(f, true).use { it.write(bytes) }
            } catch (t: Throwable) {
                // Never let logging crash the caller.
            }
        }
    }

    private fun rotate(d: File, f: File) {
        val old = File(d, ROTATED_NAME)
        old.delete()
        f.renameTo(old)
    }

    // ------------------------------------------------------------------ reading / export

    /** Last [maxLines] lines of the log (rotated file first, then the current one). */
    fun tail(maxLines: Int = 400): String {
        val cur = file() ?: return ""
        val lines = ArrayList<String>()
        runCatching { rotatedFile()?.takeIf { it.exists() }?.forEachLine { lines += it } }
        runCatching { if (cur.exists()) cur.forEachLine { lines += it } }
        return lastLines(lines, maxLines).joinToString("\n")
    }

    /** Whole log text, rotated file first. */
    fun fullText(): String = buildString {
        rotatedFile()?.takeIf { it.exists() }?.let { runCatching { append(it.readText()) } }
        file()?.takeIf { it.exists() }?.let { runCatching { append(it.readText()) } }
    }

    fun sizeBytes(): Long = (file()?.length() ?: 0L) + (rotatedFile()?.length() ?: 0L)

    fun clear() {
        synchronized(lock) {
            runCatching { file()?.delete() }
            runCatching { rotatedFile()?.delete() }
        }
        i(TAG, "log cleared by user")
    }

    /**
     * Copy [text] into the phone's Downloads folder as `Download/DualSpace/<name>` so the user
     * can pick it up with any file manager and send it. Returns the user-visible location.
     */
    fun exportToDownloads(context: Context, name: String, text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DualSpace")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore refused the file")
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            return "Download/DualSpace/$name"
        } else {
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DualSpace")
            d.mkdirs()
            File(d, name).writeBytes(bytes)
            return "Download/DualSpace/$name"
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The last [n] entries of [lines]; pure logic lives in [LogText] (unit-tested). */
    fun lastLines(lines: List<String>, n: Int): List<String> = LogText.lastLines(lines, n)

    fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun levelChar(p: Int) = when (p) {
        Log.VERBOSE -> 'V'; Log.DEBUG -> 'D'; Log.INFO -> 'I'; Log.WARN -> 'W'; Log.ERROR -> 'E'; else -> 'A'
    }

    /** "com.whatsapp/u1" while running inside a clone process, else null. Cheap and never throws. */
    private fun cloneSuffix(): String? = try {
        if (processLabel.startsWith("p")) {
            val pkg = top.niunaijun.blackbox.app.BActivityThread.getAppPackageName()
            if (pkg.isNullOrEmpty()) null else "$pkg/u${top.niunaijun.blackbox.app.BActivityThread.getUserId()}"
        } else null
    } catch (t: Throwable) { null }

    private fun computeProcessLabel(context: Context): String {
        val name = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.app.Application.getProcessName()
            else File("/proc/self/cmdline").readText().trim { it == ' ' || it == '\n' || it.code == 0 }
        } catch (t: Throwable) { context.packageName }
        val idx = name.indexOf(':')
        return if (idx < 0) "main" else name.substring(idx + 1).ifEmpty { "main" }
    }
}

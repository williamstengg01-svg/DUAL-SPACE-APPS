package com.dualspace.clone.util

/** Pure text helpers for the log viewer (no Android classes, so plain JVM tests cover them). */
object LogText {
    /** The last [n] entries of [lines]; empty when [n] is not positive. */
    fun lastLines(lines: List<String>, n: Int): List<String> =
        if (n <= 0 || lines.isEmpty()) emptyList() else lines.subList(maxOf(0, lines.size - n), lines.size)

    /** Human-readable byte count, e.g. "1.2 MB". */
    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = -1
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(java.util.Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }
}

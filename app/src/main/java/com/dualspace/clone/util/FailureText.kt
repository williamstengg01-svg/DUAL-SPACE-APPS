package com.dualspace.clone.util

/**
 * Turns an exception into one short line a user can read and paste into a bug report.
 * Pure Kotlin on purpose (no Android classes) so it is covered by plain JVM unit tests.
 */
object FailureText {

    private const val MAX_DEPTH = 4
    private const val MAX_LEN = 300

    fun describe(t: Throwable): String {
        val parts = ArrayList<String>()
        var cur: Throwable? = t
        var depth = 0
        val seen = HashSet<Throwable>()
        while (cur != null && depth < MAX_DEPTH && seen.add(cur)) {
            parts += one(cur)
            cur = cur.cause
            depth++
        }
        val text = parts.joinToString(" ← ")
        return if (text.length <= MAX_LEN) text else text.substring(0, MAX_LEN - 1) + "…"
    }

    private fun one(t: Throwable): String {
        val name = t.javaClass.simpleName.ifEmpty { t.javaClass.name }
        val msg = t.message?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            msg == null -> friendly(name) ?: name
            else -> "${friendly(name) ?: name}: $msg"
        }
    }

    /** A few engine failure modes that deserve a plain-language name. */
    private fun friendly(simpleName: String): String? = when (simpleName) {
        "NullPointerException" -> "Engine not ready (NullPointerException)"
        "DeadObjectException" -> "Engine process died (DeadObjectException)"
        "RemoteException" -> "Engine connection error (RemoteException)"
        "SecurityException" -> "Permission problem (SecurityException)"
        else -> null
    }
}

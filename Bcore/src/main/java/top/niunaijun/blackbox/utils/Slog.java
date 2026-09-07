package top.niunaijun.blackbox.utils;

import android.util.Log;

/**
 * Engine logger. Every message still goes to logcat; in addition, the host app can register a
 * {@link Sink} (Dual Space uses it to append warnings/errors to its on-device log file so a
 * crash inside a clone can be diagnosed without a computer attached).
 */
public final class Slog {
    public static final int LOG_ID_SYSTEM = 3;

    /** Receives every message at or above {@link #setSink(Sink, int)}'s minimum priority. */
    public interface Sink {
        void log(int priority, String tag, String msg);
    }

    private static volatile Sink sSink;
    private static volatile int sSinkMinPriority = Log.WARN;

    private Slog() {
    }

    public static void setSink(Sink sink, int minPriority) {
        sSink = sink;
        sSinkMinPriority = minPriority;
    }

    private static int out(int priority, String tag, String msg) {
        int r = Log.println(priority, tag, msg);
        Sink sink = sSink;
        if (sink != null && priority >= sSinkMinPriority) {
            try {
                sink.log(priority, tag, msg);
            } catch (Throwable ignored) {
                // Logging must never take the caller down.
            }
        }
        return r;
    }

    public static int v(String tag, String msg) {
        return out(Log.VERBOSE, tag, msg);
    }

    public static int v(String tag, String msg, Throwable tr) {
        return out(Log.VERBOSE, tag, msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int d(String tag, String msg) {
        return out(Log.DEBUG, tag, msg);
    }

    public static int d(String tag, String msg, Throwable tr) {
        return out(Log.DEBUG, tag, msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int i(String tag, String msg) {
        return out(Log.INFO, tag, msg);
    }

    public static int i(String tag, String msg, Throwable tr) {
        return out(Log.INFO, tag, msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int w(String tag, String msg) {
        return out(Log.WARN, tag, msg);
    }

    public static int w(String tag, String msg, Throwable tr) {
        return out(Log.WARN, tag, msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int w(String tag, Throwable tr) {
        return out(Log.WARN, tag, Log.getStackTraceString(tr));
    }

    public static int e(String tag, String msg) {
        return out(Log.ERROR, tag, msg);
    }

    public static int e(String tag, String msg, Throwable tr) {
        return out(Log.ERROR, tag, msg + '\n' + Log.getStackTraceString(tr));
    }

    public static int println(int priority, String tag, String msg) {
        return out(priority, tag, msg);
    }
}

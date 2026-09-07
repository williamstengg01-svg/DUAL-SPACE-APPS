package top.niunaijun.blackbox.utils;

/**
 * Strips engine frames from uncaught exceptions so a cloned app's own crash reporter does not
 * see "blackbox"/"hook" in the trace.
 *
 * It used to *replace* the default uncaught-exception handler with one that only rewrote the
 * stack and then returned. Returning from the handler without killing the process leaves the
 * main thread dead but the process alive: the UI freezes and Android eventually shows
 * "isn't responding". It now chains to whatever handler was installed before it.
 */
public class StackTraceFilter {
    private static boolean sInstalled = false;

    static {
        install();
    }

    public static synchronized void install() {
        if (sInstalled) return;
        try {
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                try {
                    StackTraceElement[] original = e.getStackTrace();
                    e.setStackTrace(filterStackTrace(original));
                } catch (Throwable ignored) {
                }
                if (previous != null) {
                    previous.uncaughtException(t, e);
                }
            });
            sInstalled = true;
        } catch (Throwable ignored) {}
    }

    private static StackTraceElement[] filterStackTrace(StackTraceElement[] stack) {
        return java.util.Arrays.stream(stack)
            .filter(element -> !isSuspicious(element.getClassName()))
            .toArray(StackTraceElement[]::new);
    }

    private static boolean isSuspicious(String className) {
        return className.toLowerCase().contains("xposed") ||
               className.toLowerCase().contains("epic") ||
               className.toLowerCase().contains("virtual") ||
               className.toLowerCase().contains("blackbox") ||
               className.toLowerCase().contains("hook");
    }
}

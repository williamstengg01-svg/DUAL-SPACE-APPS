package top.niunaijun.blackbox.core.system.am;

/**
 * What to do when an activity the engine asked for never appeared and the clone is left with
 * no window at all. Pure logic so it can be unit tested (see LostStartPolicy Test).
 *
 * A single lost start is usually recoverable by opening the app again. When it happens twice
 * in a row the clone's process is wedged — on Android 16 a hung UI thread in the clone made
 * every later launch a no-op — and only restarting that process gets the user moving again.
 */
public final class LostStartPolicy {

    /** Do not act more often than this for the same clone. */
    public static final long MIN_GAP_MS = 10_000L;
    /** A second lost start within this window means reopening did not help. */
    public static final long ESCALATE_WINDOW_MS = 60_000L;

    public enum Action {
        /** The clone has a window after all. */
        NOTHING,
        /** Acted very recently; let it settle. */
        WAIT,
        /** Open the app again. */
        REOPEN,
        /** Reopening did not help: restart the clone's processes, then open it. */
        RESTART_PROCESSES
    }

    private LostStartPolicy() {
    }

    /**
     * @param cloneHasWindow       the clone still shows something
     * @param triedBefore          this clone was recovered before
     * @param msSinceLastAttempt   time since that attempt (ignored when {@code triedBefore} is false)
     */
    public static Action decide(boolean cloneHasWindow, boolean triedBefore, long msSinceLastAttempt) {
        if (cloneHasWindow) {
            return Action.NOTHING;
        }
        if (!triedBefore) {
            return Action.REOPEN;
        }
        if (msSinceLastAttempt < MIN_GAP_MS) {
            return Action.WAIT;
        }
        if (msSinceLastAttempt < ESCALATE_WINDOW_MS) {
            return Action.RESTART_PROCESSES;
        }
        return Action.REOPEN;
    }
}

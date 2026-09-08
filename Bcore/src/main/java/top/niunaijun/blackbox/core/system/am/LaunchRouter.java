package top.niunaijun.blackbox.core.system.am;

/**
 * Where an activity start should go, expressed without any Android class so it can be unit
 * tested on a plain JVM (see LaunchRouterTest). {@link ActivityStack} asks this first and only
 * falls back to its own legacy handling for the cases marked {@link Decision#CONTINUE}.
 *
 * The rules here are the ones that went wrong in the field:
 *
 *  - an app restarting itself with CLEAR_TASK had its task emptied and its replacement lost,
 *    which left the clone with no window at all (the user saw Dual Space again, with the app
 *    logged in but invisible);
 *  - opening a clone that was already running started a second copy of its launch activity on
 *    top of the live task, so the clone came back on a stale screen;
 *  - and the fix for the second must never swallow the first.
 */
public final class LaunchRouter {

    public enum Decision {
        /** No live task for this app: start the activity as the root of a new task. */
        NEW_TASK,
        /** The app is already running and was asked to open from outside: just show it. */
        RESUME_TASK,
        /** CLEAR_TASK: drop the old task, then start this activity as the root of a new one. */
        CLEAR_TASK_NEW_ROOT,
        /** Anything else — the caller's own launch-mode handling decides. */
        CONTINUE
    }

    /** The facts a routing decision depends on. */
    public static final class Request {
        /** A task for this app exists and still holds at least one unfinished activity. */
        public boolean hasLiveTask;
        /** The intent is ACTION_MAIN + CATEGORY_LAUNCHER ("tap the app icon"). */
        public boolean launcherIntent;
        /** The start came from outside the app (Dual Space, a shortcut), not from the app itself. */
        public boolean fromOutsideApp;
        public boolean clearTask;
        public boolean clearTop;
        public boolean newTask;

        public Request hasLiveTask(boolean v) { hasLiveTask = v; return this; }
        public Request launcherIntent(boolean v) { launcherIntent = v; return this; }
        public Request fromOutsideApp(boolean v) { fromOutsideApp = v; return this; }
        public Request clearTask(boolean v) { clearTask = v; return this; }
        public Request clearTop(boolean v) { clearTop = v; return this; }
        public Request newTask(boolean v) { newTask = v; return this; }
    }

    private LaunchRouter() {
    }

    public static Decision decide(Request r) {
        if (!r.hasLiveTask) {
            return Decision.NEW_TASK;
        }
        // Only a start from outside may be answered by simply showing what is already running.
        // An app restarting *itself* through its launcher intent has to be started for real,
        // because it is about to finish the screens it currently has.
        if (r.launcherIntent && r.fromOutsideApp && !r.clearTask && !r.clearTop) {
            return Decision.RESUME_TASK;
        }
        if (r.clearTask && r.newTask) {
            return Decision.CLEAR_TASK_NEW_ROOT;
        }
        return Decision.CONTINUE;
    }

    /**
     * After the launch-mode handling, an activity can only be started "inside" the task when
     * something in that task is still alive to start it from. Starting from an activity that is
     * already finishing is refused by Android, and the start is lost.
     */
    public static boolean mustStartFreshTask(boolean hasLiveActivityToStartFrom) {
        return !hasLiveActivityToStartFrom;
    }
}

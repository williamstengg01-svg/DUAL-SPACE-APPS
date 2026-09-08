package top.niunaijun.blackbox.core.system.am;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import black.android.app.BRActivityManagerNative;
import black.android.app.BRIActivityManager;
import black.com.android.internal.BRRstyleable;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.core.system.pm.PackageManagerCompat;
import top.niunaijun.blackbox.proxy.ProxyActivity;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.proxy.record.ProxyActivityRecord;
import top.niunaijun.blackbox.utils.ComponentUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;

import static android.content.pm.PackageManager.GET_ACTIVITIES;


@SuppressWarnings({"deprecation", "unchecked"})
public class ActivityStack {
    public static final String TAG = "ActivityStack";

    private final ActivityManager mAms;
    private final Map<Integer, TaskRecord> mTasks = new LinkedHashMap<>();
    private final Set<ActivityRecord> mLaunchingActivities = new HashSet<>();

    public static final int LAUNCH_TIME_OUT = 0;
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case LAUNCH_TIME_OUT:
                    ActivityRecord record = (ActivityRecord) msg.obj;
                    if (record != null) {
                        synchronized (mLaunchingActivities) {
                            mLaunchingActivities.remove(record);
                        }
                        onLaunchTimedOut(record);
                    }
                    break;
                default:
                    break;
            }
        }
    };

    public ActivityStack() {
        mAms = (ActivityManager) BlackBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
    }

    /** Tag mirrored into the Dual Space log file (see Slog.setSinkTags). */
    private static final String ROUTING = "ActivityRouting";

    /** Last time a clone was reopened after a lost start, per "userId:package". */
    private final Map<String, Long> mLastRecovery = new LinkedHashMap<>();
    private static final long RECOVERY_COOLDOWN_MS = 15_000L;

    /**
     * An activity we asked the system to start never reported back. If that leaves the clone
     * with no window at all, the app is running with nothing on screen and the phone shows
     * whatever was behind it — the state this project has been chasing. Reopen the app rather
     * than leave the user staring at Dual Space.
     *
     * Only ever runs when a start was actually in flight, so leaving an app normally (Back out
     * of its last screen) is untouched.
     */
    private void onLaunchTimedOut(ActivityRecord record) {
        try {
            if (record == null || record.info == null) {
                return;
            }
            final int userId = record.userId;
            final String packageName = record.info.packageName;
            Slog.w(ROUTING, "activity " + record.component.getShortClassName()
                    + " never started; checking whether " + packageName + " still has a window");

            synchronized (mTasks) {
                synchronizeTasks();
                for (TaskRecord task : mTasks.values()) {
                    for (ActivityRecord activity : task.activities) {
                        if (activity.userId == userId && !activity.finished) {
                            Slog.w(ROUTING, "  still showing " + activity.component.getShortClassName() + "; nothing to do");
                            return;
                        }
                    }
                }
            }

            String key = userId + ":" + packageName;
            long now = android.os.SystemClock.elapsedRealtime();
            Long last = mLastRecovery.get(key);
            if (last != null && now - last < RECOVERY_COOLDOWN_MS) {
                Slog.w(ROUTING, "  no window, but " + packageName + " was already reopened a moment ago");
                return;
            }
            mLastRecovery.put(key, now);

            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ResolveInfo resolveInfo = BPackageManagerService.get().resolveActivity(launch, GET_ACTIVITIES, null, userId);
            if (resolveInfo == null || resolveInfo.activityInfo == null) {
                Slog.w(ROUTING, "  no launch activity for " + packageName + "; cannot reopen it");
                return;
            }
            launch.setComponent(ComponentUtils.toComponentName(resolveInfo.activityInfo));
            Slog.w(ROUTING, "  -> " + packageName + " has no window left; reopening "
                    + resolveInfo.activityInfo.name);
            synchronized (mTasks) {
                startActivityInNewTaskLocked(userId, launch, resolveInfo.activityInfo, null, 0);
            }
        } catch (Throwable t) {
            Slog.w(ROUTING, "could not reopen the app after a lost start: " + t);
        }
    }

    /** The launch flags that decide where an activity lands, in a form a human can read. */
    private static String describeFlags(Intent intent) {
        int f = intent.getFlags();
        StringBuilder sb = new StringBuilder();
        if ((f & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) sb.append("NEW_TASK ");
        if ((f & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0) sb.append("CLEAR_TASK ");
        if ((f & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) sb.append("CLEAR_TOP ");
        if ((f & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0) sb.append("SINGLE_TOP ");
        if ((f & Intent.FLAG_ACTIVITY_NO_HISTORY) != 0) sb.append("NO_HISTORY ");
        if ((f & Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0) sb.append("REORDER_TO_FRONT ");
        if (sb.length() == 0) sb.append("none");
        return sb.toString().trim();
    }

    /** True for the "tap the app icon" intent: ACTION_MAIN + CATEGORY_LAUNCHER. */
    private static boolean isLauncherIntent(Intent intent) {
        return intent != null
                && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.getCategories() != null
                && intent.getCategories().contains(Intent.CATEGORY_LAUNCHER);
    }

    public boolean containsFlag(Intent intent, int flag) {
        return (intent.getFlags() & flag) != 0;
    }

    public int startActivitiesLocked(int userId, Intent[] intents, String[] resolvedTypes, IBinder resultTo, Bundle options) {
        if (intents == null) {
            throw new NullPointerException("intents is null");
        }
        if (resolvedTypes == null) {
            throw new NullPointerException("resolvedTypes is null");
        }
        if (intents.length != resolvedTypes.length) {
            throw new IllegalArgumentException("intents are length different than resolvedTypes");
        }
        for (int i = 0; i < intents.length; i++) {
            startActivityLocked(userId, intents[i], resolvedTypes[i], resultTo, null, -1, 0, options);
        }
        return 0;
    }

    public int startActivityLocked(int userId, Intent intent, String resolvedType, IBinder resultTo, String resultWho, int requestCode, int flags, Bundle options) {
        synchronized (mTasks) {
            synchronizeTasks();
        }

        ResolveInfo resolveInfo = BPackageManagerService.get().resolveActivity(intent, GET_ACTIVITIES, resolvedType, userId);
        if (resolveInfo == null || resolveInfo.activityInfo == null) {
            return 0;
        }
        Log.d(TAG, "startActivityLocked : " + resolveInfo.activityInfo);
        ActivityInfo activityInfo = resolveInfo.activityInfo;

        ActivityRecord sourceRecord = findActivityRecordByToken(userId, resultTo);
        if (sourceRecord == null) {
            resultTo = null;
        }
        TaskRecord sourceTask = null;
        if (sourceRecord != null) {
            sourceTask = sourceRecord.task;
        }

        String taskAffinity = ComponentUtils.getTaskAffinity(activityInfo);

        int launchModeFlags = 0;
        boolean singleTop = containsFlag(intent, Intent.FLAG_ACTIVITY_SINGLE_TOP) || activityInfo.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP;
        boolean newTask = containsFlag(intent, Intent.FLAG_ACTIVITY_NEW_TASK);
        boolean clearTop = containsFlag(intent, Intent.FLAG_ACTIVITY_CLEAR_TOP);
        boolean clearTask = containsFlag(intent, Intent.FLAG_ACTIVITY_CLEAR_TASK);

        Slog.i(ROUTING, "start " + ComponentUtils.toComponentName(activityInfo)
                + " user=" + userId
                + " flags=[" + describeFlags(intent) + "]"
                + " launchMode=" + activityInfo.launchMode
                + " from=" + (sourceRecord == null ? "outside the app" : sourceRecord.component.getShortClassName()));

        TaskRecord taskRecord = null;
        switch (activityInfo.launchMode) {
            case ActivityInfo.LAUNCH_SINGLE_TOP:
            case ActivityInfo.LAUNCH_MULTIPLE:
            case ActivityInfo.LAUNCH_SINGLE_TASK:
                taskRecord = findTaskRecordByTaskAffinityLocked(userId, taskAffinity);
                if (taskRecord == null && !newTask) {
                    taskRecord = sourceTask;
                }
                break;
            case ActivityInfo.LAUNCH_SINGLE_INSTANCE:
                taskRecord = findTaskRecordByTaskAffinityLocked(userId, taskAffinity);
                break;
        }

        // The three decisions below are unit tested in LaunchRouterTest; everything the router
        // marks CONTINUE is handled by the launch-mode code that follows.
        LaunchRouter.Decision decision = LaunchRouter.decide(new LaunchRouter.Request()
                .hasLiveTask(taskRecord != null && !taskRecord.needNewTask())
                .launcherIntent(isLauncherIntent(intent))
                .fromOutsideApp(sourceRecord == null)
                .clearTask(clearTask)
                .clearTop(clearTop)
                .newTask(newTask));

        if (decision == LaunchRouter.Decision.NEW_TASK) {
            Slog.i(ROUTING, "  -> new task (no live task for this app)");
            return startActivityInNewTaskLocked(userId, intent, activityInfo, resultTo, launchModeFlags);
        }

        mAms.moveTaskToFront(taskRecord.id, 0);

        // Tapping a clone in Dual Space, or its home-screen shortcut, sends the app's launcher
        // intent. A launcher is expected to *resume* the task that is already running, not to
        // stack a second copy of the launch activity on top of it. Stacking is what made a
        // clone come back on its splash screen showing a stale, half-logged-in UI while the
        // real screens sat underneath it.
        //
        // Only for starts that come from outside the app (no source activity). An app
        // restarting *itself* through its launcher intent must really be started: swallowing
        // that start would leave it with no window once it finishes its old screens.
        if (decision == LaunchRouter.Decision.RESUME_TASK && taskRecord.getTopActivityRecord() != null) {
            Slog.i(ROUTING, "  -> resuming task " + taskRecord.id + " (launcher intent from outside the app)");
            return 0;
        }

        boolean notStartToFront = false;
        if (clearTop || singleTop || clearTask) {
            notStartToFront = true;
        }

        boolean startTaskToFront = !notStartToFront
                && ComponentUtils.intentFilterEquals(taskRecord.rootIntent, intent)
                && taskRecord.rootIntent.getFlags() == intent.getFlags();

        if (startTaskToFront)
            return 0;

        ActivityRecord topActivityRecord = taskRecord.getTopActivityRecord();
        ActivityRecord targetActivityRecord = findActivityRecordByComponentName(userId, ComponentUtils.toComponentName(activityInfo));
        ActivityRecord newIntentRecord = null;
        boolean ignore = false;

        if (clearTop) {
            if (targetActivityRecord != null) {
                
                synchronized (targetActivityRecord.task.activities) {
                    for (int i = targetActivityRecord.task.activities.size() - 1; i >= 0; i--) {
                        ActivityRecord next = targetActivityRecord.task.activities.get(i);
                        if (next != targetActivityRecord) {
                            next.finished = true;
                            Log.d(TAG, "makerFinish: " + next.component.toString());
                        } else {
                            if (singleTop) {
                                newIntentRecord = targetActivityRecord;
                            } else {
                                
                                targetActivityRecord.finished = true;
                            }
                            break;
                        }
                    }
                }
            }
        }

        if (singleTop && !clearTop) {
            if (ComponentUtils.intentFilterEquals(topActivityRecord.intent, intent)) {
                newIntentRecord = topActivityRecord;
            } else {
                synchronized (mLaunchingActivities) {
                    for (ActivityRecord launchingActivity : mLaunchingActivities) {
                        if (!launchingActivity.finished && launchingActivity.component.equals(intent.getComponent())) {
                            
                            ignore = true;
                        }
                    }
                }
            }
        }

        if (activityInfo.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK && !clearTop) {
            if (ComponentUtils.intentFilterEquals(topActivityRecord.intent, intent)) {
                newIntentRecord = topActivityRecord;
            } else {
                ActivityRecord record = findActivityRecordByComponentName(userId, ComponentUtils.toComponentName(activityInfo));
                if (record != null) {
                    
                    newIntentRecord = record;
                    
                    synchronized (taskRecord.activities) {
                        for (int i = taskRecord.activities.size() - 1; i >= 0; i--) {
                            ActivityRecord next = taskRecord.activities.get(i);
                            if (next != record) {
                                next.finished = true;
                            } else {
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (activityInfo.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
            newIntentRecord = topActivityRecord;
        }

        // FLAG_ACTIVITY_CLEAR_TASK: throw the task away and make this activity its new root.
        // Apps do this to restart themselves — which is exactly what a banking app does once
        // login succeeds. Clearing was implemented, starting the replacement was not: the code
        // below would hand the system the token of an activity that is already finishing, the
        // start was dropped, and the user was left looking at whatever was behind the clone
        // (Dual Space itself), with the app logged in but its window gone.
        if (decision == LaunchRouter.Decision.CLEAR_TASK_NEW_ROOT) {
            for (ActivityRecord activity : taskRecord.activities) {
                activity.finished = true;
            }
            finishAllActivity(userId);
            Slog.i(ROUTING, "  -> CLEAR_TASK: task emptied, restarting as the root of a new task");
            return startActivityInNewTaskLocked(userId, intent, activityInfo, null, launchModeFlags);
        }


        if (newIntentRecord != null) {
            Slog.i(ROUTING, "  -> onNewIntent to the existing " + newIntentRecord.component.getShortClassName());
            deliverNewIntentLocked(newIntentRecord, intent);
            return 0;
        } else if (ignore) {
            Slog.i(ROUTING, "  -> ignored (the same activity is already starting)");
            return 0;
        }

        if (resultTo == null) {
            ActivityRecord top = taskRecord.getTopActivityRecord();
            if (top != null) {
                resultTo = top.token;
            }
        } else if (sourceTask != null) {
            ActivityRecord top = sourceTask.getTopActivityRecord();
            if (top != null) {
                resultTo = top.token;
            }
        }
        // Everything in the task was just finished (clearTop on the root, or the app finished
        // its own stack). There is no live activity left to start from, so start a new task
        // rather than dereferencing a dead record and losing the activity.
        ActivityRecord liveSource = taskRecord.getTopActivityRecord();
        if (liveSource == null) {
            liveSource = topActivityRecord;
        }
        boolean haveSomethingToStartFrom = liveSource != null
                && liveSource.processRecord != null
                && liveSource.processRecord.appThread != null;
        if (LaunchRouter.mustStartFreshTask(haveSomethingToStartFrom)) {
            Slog.i(ROUTING, "  -> new task (nothing live left in task " + taskRecord.id + " to start from)");
            return startActivityInNewTaskLocked(userId, intent, activityInfo, null, launchModeFlags);
        }
        return startActivityInSourceTask(intent,
                resolvedType, resultTo, resultWho, requestCode, flags, options, userId, liveSource, activityInfo, launchModeFlags);
    }

    private void deliverNewIntentLocked(ActivityRecord activityRecord, Intent intent) {
        try {
            activityRecord.processRecord.bActivityThread.handleNewIntent(activityRecord.token, intent);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private Intent startActivityProcess(int userId, Intent intent, ActivityInfo
            info, ActivityRecord record) {
        ProxyActivityRecord stubRecord = new ProxyActivityRecord(userId, info, intent, record);
        ProcessRecord targetApp = BProcessManagerService.get().startProcessLocked(info.packageName, info.processName, userId, -1, Binder.getCallingPid());
        if (targetApp == null) {
            throw new RuntimeException("Unable to create process, name:" + info.name);
        }
        return getStartStubActivityIntentInner(intent, targetApp.bpid, userId, stubRecord, info);
    }

    private int startActivityInNewTaskLocked(int userId, Intent intent, ActivityInfo
            activityInfo, IBinder resultTo, int launchMode) {
        ActivityRecord record = newActivityRecord(intent, activityInfo, resultTo, userId);
        Intent shadow = startActivityProcess(userId, intent, activityInfo, record);

        shadow.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        shadow.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        shadow.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        shadow.addFlags(launchMode);

        BlackBoxCore.getContext().startActivity(shadow);
        return 0;
    }

    private int startActivityInSourceTask(Intent intent, String resolvedType,
                                          IBinder resultTo, String resultWho, int requestCode, int flags,
                                          Bundle options,
                                          int userId, ActivityRecord sourceRecord, ActivityInfo activityInfo, int launchMode) {
        ActivityRecord selfRecord = newActivityRecord(intent, activityInfo, resultTo, userId);
        Intent shadow = startActivityProcess(userId, intent, activityInfo, selfRecord);
        shadow.setAction(UUID.randomUUID().toString());
        shadow.addFlags(launchMode);
        if (resultTo == null) {
            shadow.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return realStartActivityLocked(sourceRecord.processRecord.appThread, shadow, resolvedType, resultTo, resultWho, requestCode, flags, options);
    }

    private int realStartActivityLocked(IInterface appThread, Intent intent, String resolvedType,
                                        IBinder resultTo, String resultWho, int requestCode, int flags,
                                        Bundle options) {
        try {
            flags &= ~ActivityManagerCompat.START_FLAG_DEBUG;
            flags &= ~ActivityManagerCompat.START_FLAG_NATIVE_DEBUGGING;
            flags &= ~ActivityManagerCompat.START_FLAG_TRACK_ALLOCATION;

            BRIActivityManager.get(BRActivityManagerNative.get().getDefault()).startActivity(appThread, BlackBoxCore.getHostPkg(), intent,
                    resolvedType, resultTo, resultWho, requestCode, flags, null, options);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return 0;
    }

    private ActivityRecord getTopActivityRecord() {
        synchronized (mTasks) {
            synchronizeTasks();
        }
        List<TaskRecord> tasks = new LinkedList<>(mTasks.values());
        if (tasks.isEmpty())
            return null;
        return tasks.get(tasks.size() - 1).getTopActivityRecord();
    }

    private Intent getStartStubActivityIntentInner(Intent intent, int vpid,
                                                   int userId, ProxyActivityRecord target,
                                                   ActivityInfo activityInfo) {
        Intent shadow = new Intent();
        TypedArray typedArray = null;
        try {
            Resources resources = PackageManagerCompat.getResources(BlackBoxCore.getContext(), activityInfo.applicationInfo);
            int id;
            if (activityInfo.theme != 0) {
                id = activityInfo.theme;
            } else {
                id = activityInfo.applicationInfo.theme;
            }
            assert resources != null;
            typedArray = resources.newTheme().obtainStyledAttributes(id, BRRstyleable.get().Window());
            boolean windowIsTranslucent = typedArray.getBoolean(BRRstyleable.get().Window_windowIsTranslucent(), false);
            if (windowIsTranslucent) {
                shadow.setComponent(new ComponentName(BlackBoxCore.getHostPkg(), ProxyManifest.TransparentProxyActivity(vpid)));
            } else {
                shadow.setComponent(new ComponentName(BlackBoxCore.getHostPkg(), ProxyManifest.getProxyActivity(vpid)));
            }
            Slog.d(TAG, activityInfo + ", windowIsTranslucent: " + windowIsTranslucent);
        } catch (Throwable e) {
            e.printStackTrace();
            shadow.setComponent(new ComponentName(BlackBoxCore.getHostPkg(), ProxyManifest.getProxyActivity(vpid)));
        } finally {
            if (typedArray != null) {
                typedArray.recycle();
            }
        }
        ProxyActivityRecord.saveStub(shadow, intent, target.mActivityInfo, target.mActivityRecord, target.mUserId);
        return shadow;
    }

    private void finishAllActivity(int userId) {
        for (TaskRecord task : mTasks.values()) {
            for (ActivityRecord activity : task.activities) {
                if (activity.userId == userId) {
                    if (activity.finished) {
                        try {
                            activity.processRecord.bActivityThread.finishActivity(activity.token);
                        } catch (RemoteException ignored) {
                        }
                    }
                }
            }
        }
    }

    ActivityRecord newActivityRecord(Intent intent, ActivityInfo info, IBinder resultTo,
                                     int userId) {
        ActivityRecord targetRecord = ActivityRecord.create(intent, info, resultTo, userId);
        synchronized (mLaunchingActivities) {
            mLaunchingActivities.add(targetRecord);
            Message obtain = Message.obtain(mHandler, LAUNCH_TIME_OUT, targetRecord);
            mHandler.sendMessageDelayed(obtain, 2000);
        }
        return targetRecord;
    }

    private ActivityRecord findActivityRecordByComponentName(int userId, ComponentName
            componentName) {
        ActivityRecord record = null;
        for (TaskRecord next : mTasks.values()) {
            if (userId == next.userId) {
                for (ActivityRecord activity : next.activities) {
                    if (activity.component.equals(componentName)) {
                        record = activity;
                        break;
                    }
                }
            }
        }
        return record;
    }

    private ActivityRecord findActivityRecordByToken(int userId, IBinder token) {
        ActivityRecord record = null;
        if (token != null) {
            for (TaskRecord next : mTasks.values()) {
                if (userId == next.userId) {
                    for (ActivityRecord activity : next.activities) {
                        if (activity.token == token) {
                            record = activity;
                            break;
                        }
                    }
                }
            }
        }
        return record;
    }

    private TaskRecord findTaskRecordByTaskAffinityLocked(int userId, String taskAffinity) {
        synchronized (mTasks) {
            for (TaskRecord next : mTasks.values()) {
                if (userId == next.userId && next.taskAffinity.equals(taskAffinity))
                    return next;
            }
            return null;
        }
    }

    private TaskRecord findTaskRecordByTokenLocked(int userId, IBinder token) {
        synchronized (mTasks) {
            for (TaskRecord next : mTasks.values()) {
                if (userId == next.userId) {
                    for (ActivityRecord activity : next.activities) {
                        if (activity.token == token) {
                            return next;
                        }
                    }
                }
            }
            return null;
        }
    }

    public void onActivityCreated(ProcessRecord processRecord, int taskId, IBinder
            token, ActivityRecord record) {
        synchronized (mLaunchingActivities) {
            mLaunchingActivities.remove(record);
            mHandler.removeMessages(LAUNCH_TIME_OUT, record);
        }
        synchronized (mTasks) {
            synchronizeTasks();
            TaskRecord taskRecord = mTasks.get(taskId);
            if (taskRecord == null) {
                taskRecord = new TaskRecord(taskId, record.userId, ComponentUtils.getTaskAffinity(record.info));
                taskRecord.rootIntent = record.intent;
                mTasks.put(taskId, taskRecord);
            }
            record.token = token;
            record.processRecord = processRecord;
            record.task = taskRecord;
            taskRecord.addTopActivity(record);
            Log.d(TAG, "onActivityCreated : " + record.component.toString());
        }
    }

    public void onActivityResumed(int userId, IBinder token) {
        synchronized (mTasks) {
            synchronizeTasks();
            ActivityRecord activityRecord = findActivityRecordByToken(userId, token);
            if (activityRecord == null) {
                return;
            }
            Log.d(TAG, "onActivityResumed : " + activityRecord.component.toString());
            activityRecord.task.removeActivity(activityRecord);
            activityRecord.task.addTopActivity(activityRecord);
        }
    }

    public void onActivityDestroyed(int userId, IBinder token) {
        synchronized (mTasks) {
            synchronizeTasks();
            ActivityRecord activityRecord = findActivityRecordByToken(userId, token);
            if (activityRecord == null) {
                return;
            }
            activityRecord.finished = true;
            Log.d(TAG, "onActivityDestroyed : " + activityRecord.component.toString());
            activityRecord.task.removeActivity(activityRecord);
        }
    }

    public void onFinishActivity(int userId, IBinder token) {
        synchronized (mTasks) {
            synchronizeTasks();
            ActivityRecord activityRecord = findActivityRecordByToken(userId, token);
            if (activityRecord == null) {
                return;
            }
            activityRecord.finished = true;
            Log.d(TAG, "onFinishActivity : " + activityRecord.component.toString());
        }
    }

    public String getCallingPackage(IBinder token, int userId) {
        synchronized (mTasks) {
            synchronizeTasks();
            ActivityRecord activityRecordByToken = findActivityRecordByToken(userId, token);
            if (activityRecordByToken != null) {
                ActivityRecord resultTo = findActivityRecordByToken(userId, activityRecordByToken.resultTo);
                if (resultTo != null) {
                    return resultTo.info.packageName;
                }
            }
            return BlackBoxCore.getHostPkg();
        }
    }

    public ComponentName getCallingActivity(IBinder token, int userId) {
        synchronized (mTasks) {
            synchronizeTasks();
            ActivityRecord activityRecordByToken = findActivityRecordByToken(userId, token);
            if (activityRecordByToken != null) {
                ActivityRecord resultTo = findActivityRecordByToken(userId, activityRecordByToken.resultTo);
                if (resultTo != null) {
                    return resultTo.component;
                }
            }
            return new ComponentName(BlackBoxCore.getHostPkg(), ProxyActivity.P0.class.getName());
        }
    }

    @SuppressWarnings("deprecation")
    private void synchronizeTasks() {
        List<ActivityManager.RecentTaskInfo> recentTasks = mAms.getRecentTasks(100, 0);
        Map<Integer, TaskRecord> newTacks = new LinkedHashMap<>();
        for (int i = recentTasks.size() - 1; i >= 0; i--) {
            ActivityManager.RecentTaskInfo next = recentTasks.get(i);
            TaskRecord taskRecord = mTasks.get(next.id);
            if (taskRecord == null)
                continue;
            newTacks.put(next.id, taskRecord);
        }
        mTasks.clear();
        mTasks.putAll(newTacks);
    }
}

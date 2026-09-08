package top.niunaijun.blackbox.core.system.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import top.niunaijun.blackbox.core.system.am.LaunchRouter.Decision;
import top.niunaijun.blackbox.core.system.am.LaunchRouter.Request;

/**
 * The activity-routing cases that broke on real phones. Each test names the situation it
 * came from, so a future change that reintroduces one fails the build.
 */
public class LaunchRouterTest {

    private static Request request() {
        return new Request();
    }

    // ---------------------------------------------------------------- first launch

    @Test
    public void firstLaunchStartsANewTask() {
        Decision d = LaunchRouter.decide(request()
                .hasLiveTask(false)
                .launcherIntent(true)
                .fromOutsideApp(true)
                .newTask(true));
        assertEquals(Decision.NEW_TASK, d);
    }

    @Test
    public void openingAClosedCloneStartsANewTask() {
        // The app was closed (its task holds only finished activities).
        Decision d = LaunchRouter.decide(request()
                .hasLiveTask(false)
                .launcherIntent(true)
                .fromOutsideApp(true));
        assertEquals(Decision.NEW_TASK, d);
    }

    // ---------------------------------------------------------------- resuming

    @Test
    public void tappingACloneThatIsRunningResumesItInsteadOfRestartingIt() {
        // 1.2.4: the clone used to come back on its splash screen with a stale view while the
        // real screens sat underneath.
        Decision d = LaunchRouter.decide(request()
                .hasLiveTask(true)
                .launcherIntent(true)
                .fromOutsideApp(true)
                .newTask(true));
        assertEquals(Decision.RESUME_TASK, d);
    }

    @Test
    public void anAppRestartingItselfIsNeverSwallowedByTheResumeShortcut() {
        // 1.2.5: the same launcher intent, but sent by the app itself. Resuming here would
        // leave the app with no window once it finishes its current screens.
        Decision d = LaunchRouter.decide(request()
                .hasLiveTask(true)
                .launcherIntent(true)
                .fromOutsideApp(false)
                .newTask(true));
        assertFalse("an internal restart must not be answered with RESUME_TASK",
                d == Decision.RESUME_TASK);
    }

    @Test
    public void aResumeIsNotUsedWhenTheCallerAsksToClearSomething() {
        assertFalse(LaunchRouter.decide(request()
                .hasLiveTask(true).launcherIntent(true).fromOutsideApp(true)
                .clearTask(true).newTask(true)) == Decision.RESUME_TASK);
        assertFalse(LaunchRouter.decide(request()
                .hasLiveTask(true).launcherIntent(true).fromOutsideApp(true)
                .clearTop(true)) == Decision.RESUME_TASK);
    }

    @Test
    public void aNonLauncherIntentIsNeverResumed() {
        Decision d = LaunchRouter.decide(request()
                .hasLiveTask(true)
                .launcherIntent(false)
                .fromOutsideApp(true)
                .newTask(true));
        assertEquals(Decision.CONTINUE, d);
    }

    // ---------------------------------------------------------------- CLEAR_TASK restart

    @Test
    public void theRestartAfterLoginClearsTheTaskAndStartsANewRoot() {
        // KBZPay restarts itself the moment login succeeds: CLEAR_TASK + NEW_TASK from inside
        // the app. Before 1.2.5 the task was emptied and nothing replaced it.
        Decision d = LaunchRouter.decide(request()
                .hasLiveTask(true)
                .launcherIntent(true)
                .fromOutsideApp(false)
                .clearTask(true)
                .newTask(true));
        assertEquals(Decision.CLEAR_TASK_NEW_ROOT, d);
    }

    @Test
    public void clearTaskFromOutsideTheAppAlsoRestarts() {
        Decision d = LaunchRouter.decide(request()
                .hasLiveTask(true)
                .launcherIntent(false)
                .fromOutsideApp(true)
                .clearTask(true)
                .newTask(true));
        assertEquals(Decision.CLEAR_TASK_NEW_ROOT, d);
    }

    @Test
    public void clearTaskWithoutNewTaskIsLeftToTheLaunchModeHandling() {
        // CLEAR_TASK only has a meaning together with NEW_TASK.
        Decision d = LaunchRouter.decide(request()
                .hasLiveTask(true)
                .clearTask(true)
                .newTask(false));
        assertEquals(Decision.CONTINUE, d);
    }

    // ---------------------------------------------------------------- ordinary navigation

    @Test
    public void ordinaryScreenToScreenNavigationIsUntouched() {
        Decision d = LaunchRouter.decide(request()
                .hasLiveTask(true)
                .launcherIntent(false)
                .fromOutsideApp(false));
        assertEquals(Decision.CONTINUE, d);
    }

    @Test
    public void clearTopIsLeftToTheLaunchModeHandling() {
        Decision d = LaunchRouter.decide(request()
                .hasLiveTask(true)
                .fromOutsideApp(false)
                .clearTop(true));
        assertEquals(Decision.CONTINUE, d);
    }

    // ---------------------------------------------------------------- the lost-start guard

    @Test
    public void aStartWithNothingAliveToStartFromGoesToItsOwnTask() {
        assertTrue(LaunchRouter.mustStartFreshTask(false));
        assertFalse(LaunchRouter.mustStartFreshTask(true));
    }
}

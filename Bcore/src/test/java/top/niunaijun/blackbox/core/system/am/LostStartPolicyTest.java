package top.niunaijun.blackbox.core.system.am;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import top.niunaijun.blackbox.core.system.am.LostStartPolicy.Action;

/**
 * The recovery has to be careful in both directions: it must not restart an app that is merely
 * slow to start, and it must not keep reopening a clone whose process is wedged.
 */
public class LostStartPolicyTest {

    @Test
    public void aCloneThatStillHasAWindowIsLeftAlone() {
        assertEquals(Action.NOTHING, LostStartPolicy.decide(true, false, 0));
        assertEquals(Action.NOTHING, LostStartPolicy.decide(true, true, 5_000));
    }

    @Test
    public void theFirstLostStartJustReopensTheApp() {
        assertEquals(Action.REOPEN, LostStartPolicy.decide(false, false, 0));
    }

    @Test
    public void twoLostStartsInARowRestartTheClonesProcesses() {
        // Android 16 field log: the clone's UI thread was hung, so every further launch was a
        // no-op and the user kept landing back in Dual Space.
        assertEquals(Action.RESTART_PROCESSES,
                LostStartPolicy.decide(false, true, LostStartPolicy.MIN_GAP_MS + 1));
        assertEquals(Action.RESTART_PROCESSES,
                LostStartPolicy.decide(false, true, LostStartPolicy.ESCALATE_WINDOW_MS - 1));
    }

    @Test
    public void repeatedTimeoutsWithinSecondsDoNotStorm() {
        assertEquals(Action.WAIT, LostStartPolicy.decide(false, true, 0));
        assertEquals(Action.WAIT, LostStartPolicy.decide(false, true, LostStartPolicy.MIN_GAP_MS - 1));
    }

    @Test
    public void anUnrelatedLostStartMuchLaterIsTreatedAsTheFirstOne() {
        assertEquals(Action.REOPEN,
                LostStartPolicy.decide(false, true, LostStartPolicy.ESCALATE_WINDOW_MS + 1));
    }
}

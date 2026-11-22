/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Exit with one Back (#489), the way TikTok's two Back handlers run it: the press-twice-to-quit
 * check compares the time since the last Back with its gap and skips its toast while the
 * activity is finishing, then the main activity's Back names its tab and asks the start page
 * whether to leave.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BackExitTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A quit gap for the simulation; TikTok's own comes from its settings. */
    private static final long GAP = 2_000L;

    @Before public void setUp() {
        reset();
    }

    @After public void tearDown() {
        reset();
        Utils.setActivity(null);
        PausedProcess.set(false);
    }

    private static void reset() {
        Settings.EXIT_WITH_ONE_BACK.resetToDefault();
        Settings.START_PAGE.resetToDefault();
        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.resetToDefault();
        HookStatus.clear();
        StartPage.resetForTests();
    }

    /**
     * One Back through both handlers, with {@code sinceLast} milliseconds since the one before:
     * "quit" when TikTok's own check quits, "left" when the start page puts the app in the
     * background, otherwise "toast, " or "" and the tab Back goes to.
     */
    private static String back(String from, long sinceLast) {
        // TikTok: cmp-long of the time since the last Back with the gap, if-gtz to a first Back.
        if (BackExit.pastTheGap(Long.compare(sinceLast, GAP)) <= 0) return "quit";
        // TikTok: if-nez on isFinishing() skips the toast; the activity here isn't finishing.
        String toast = BackExit.skipAgainToast(0) == 0 ? "toast, " : "";
        StartPage.backPressedOn(from);
        if (StartPage.leaveOnBack(0) != 0) return toast + "left";
        return toast + StartPage.backTab("HOME");
    }

    private static StartPageTest.LeavingHost host() {
        StartPageTest.LeavingHost host = new StartPageTest.LeavingHost();
        Utils.setActivity(host);
        return host;
    }

    @Test public void theSwitchStartsOffAndNeedsNoRestart() {
        assertFalse(Settings.EXIT_WITH_ONE_BACK.defaultValue);
        assertFalse(Settings.EXIT_WITH_ONE_BACK.rebootApp);
    }

    @Test public void offTikTokAsksForASecondBack() {
        StartPageTest.LeavingHost host = host();
        assertEquals("toast, HOME", back("HOME", 60_000L));
        assertEquals("quit", back("HOME", 500L));
        assertEquals("from Profile it still toasts and goes Home", "toast, HOME", back("USER", 60_000L));
        assertFalse(host.movedBack);
        assertEquals(-1, BackExit.pastTheGap(-1));
        assertEquals(0, BackExit.pastTheGap(0));
        assertEquals(1, BackExit.skipAgainToast(1));
    }

    @Test public void onTheFirstBackOnHomeLeavesWithNoToast() {
        Settings.EXIT_WITH_ONE_BACK.save(true);
        StartPageTest.LeavingHost host = host();
        assertEquals("left", back("HOME", 60_000L));
        assertTrue(host.movedBack);
        // A Back right after the last one is no second press TikTok quits on.
        host = host();
        assertEquals("left", back("HOME", 0L));
        assertTrue(host.movedBack);
        assertTrue(String.join(" ", HookStatus.report()).contains(BackExit.FAMILY));
    }

    @Test public void onAnotherTabStillGoesHomeFirstWithNoToast() {
        Settings.EXIT_WITH_ONE_BACK.save(true);
        StartPageTest.LeavingHost host = host();
        assertEquals("HOME", back("USER", 60_000L));
        assertEquals("HOME", back("NOTIFICATION", 300L));
        assertFalse(host.movedBack);
    }

    @Test public void aStartTabTakesHomesPlace() {
        Settings.EXIT_WITH_ONE_BACK.save(true);
        Settings.START_PAGE.save(StartPage.PROFILE);
        StartPageTest.LeavingHost host = host();
        assertEquals("Home goes to the start tab", "USER", back("HOME", 60_000L));
        assertFalse(host.movedBack);
        assertEquals("the start tab leaves, now with no toast", "left", back("USER", 300L));
        assertTrue(host.movedBack);
    }

    @Test public void aGuideTikTokIsClosingKeepsItsBack() {
        Settings.EXIT_WITH_ONE_BACK.save(true);
        StartPageTest.LeavingHost host = host();
        StartPage.backPressedOn("HOME");
        assertEquals(1, StartPage.leaveOnBack(1));
        assertFalse(host.movedBack);
    }

    @Test public void anAppThatCannotLeaveKeepsTikToksBack() {
        Settings.EXIT_WITH_ONE_BACK.save(true);
        StartPageTest.LeavingHost host = host();
        host.refuses = true;
        StartPage.backPressedOn("HOME");
        assertEquals(0, StartPage.leaveOnBack(0));
    }

    @Test public void pausedHushfeedGivesTikTokItsBackAgain() {
        Settings.EXIT_WITH_ONE_BACK.save(true);
        PausedProcess.set(true);
        StartPageTest.LeavingHost host = host();
        assertEquals("toast, HOME", back("HOME", 60_000L));
        assertEquals("quit", back("HOME", 500L));
        assertFalse(host.movedBack);
    }
}

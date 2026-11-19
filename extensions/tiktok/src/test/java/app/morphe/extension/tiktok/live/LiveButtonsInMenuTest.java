/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Dialog;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

/** #359: with the buttons in the menu the room shows no chip, and the menu's rows do their job. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class LiveButtonsInMenuTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> controller;
    /** Held here because LiveRecordChip keeps pages weakly. */
    private LiveAutoTapTest.FakePage page;
    private Activity activity;
    private int downs;

    @Before public void setUp() {
        // The looper is emptied between tests, so a check an earlier test queued never clears this.
        ReflectionHelpers.setStaticField(LiveRecordChip.class, "ticking", false);
        SettingsStatus.liveControlsEnabled = true;
        Settings.LIVE_AUTO_TAP.save(true);
        Settings.LIVE_AUTO_TAP_RATE.save("8");
        Settings.LIVE_BUTTONS_IN_MENU.save(true);
    }

    @After public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
            shadowOf(Looper.getMainLooper()).idle();
        }
        LiveRecordChip.holdForTests(null, false);
        Settings.RECORD_LIVE.resetToDefault();
        Settings.LIVE_AUTO_TAP.resetToDefault();
        Settings.LIVE_AUTO_TAP_RATE.resetToDefault();
        Settings.LIVE_BUTTONS_IN_MENU.resetToDefault();
        SettingsStatus.liveControlsEnabled = false;
        LiveAutoTap.forgetSkippedSpotsForTests();
    }

    private void openRoom() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        FrameLayout room = new FrameLayout(activity);
        room.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) downs++;
            return true;
        });
        activity.setContentView(room, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        controller.windowFocusChanged(true);
        idle();
        page = new LiveAutoTapTest.FakePage(activity, room);
        LiveRecordChip.noteRoomFragment(page);
        idle();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void pass(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private int chips() {
        ViewGroup content = activity.findViewById(android.R.id.content);
        int count = 0;
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child instanceof TextView) count++;
        }
        return count;
    }

    @Test public void theRoomOpensWithNothingOverTheVideo() {
        Settings.RECORD_LIVE.save(true);
        openRoom();
        assertEquals(0, chips());
        assertTrue(LiveRecordChip.inMenu());
        assertTrue(LiveAutoTap.inMenu());
    }

    @Test public void theChipsFollowTheSwitchInAnOpenRoomBothWays() {
        Settings.RECORD_LIVE.save(true);
        Settings.LIVE_BUTTONS_IN_MENU.save(false);
        openRoom();
        assertEquals(2, chips());
        assertFalse(LiveRecordChip.inMenu());
        assertFalse(LiveAutoTap.inMenu());

        Settings.LIVE_BUTTONS_IN_MENU.save(true);
        pass(3000);
        assertEquals("both chips left the video", 0, chips());

        Settings.LIVE_BUTTONS_IN_MENU.save(false);
        pass(3000);
        assertEquals("the room is still watched with no chip up", 2, chips());
    }

    @Test public void theAutoTapRowStartsAndStopsAndSaysSo() {
        openRoom();
        assertEquals("Auto tap", LiveAutoTap.menuLabel());

        LiveAutoTap.menuTap();
        assertEquals("Auto tap started", ShadowToast.getTextOfLatestToast());
        assertEquals("Stop tapping this LIVE", LiveAutoTap.menuLabel());
        pass(2300);
        assertTrue("taps " + downs, downs >= 12);
        assertEquals("still no chip while it runs", 0, chips());

        LiveAutoTap.menuTap();
        assertEquals("Auto tap stopped", ShadowToast.getTextOfLatestToast());
        assertEquals("Auto tap", LiveAutoTap.menuLabel());
        int afterStop = downs;
        pass(2000);
        assertEquals("no taps after Stop", afterStop, downs);
    }

    private void touch(int action, float x, float y, long downAt) {
        MotionEvent event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0);
        activity.getWindow().getCallback().dispatchTouchEvent(event);
        event.recycle();
    }

    private static long holdMs() {
        return ViewConfiguration.getLongPressTimeout() + LiveAutoTap.HOLD_SETTLE_MS + 50;
    }

    /** TikTok ignores a hold this soon after a like, so with taps running the hold stops them instead. */
    @Test public void holdingTheRoomStopsTheTapsSoTheNextHoldOpensTheMenu() {
        openRoom();
        LiveAutoTap.menuTap();
        pass(1000);
        assertTrue(downs > 0);

        long at = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, 300, 600, at);
        pass(holdMs());
        assertEquals("Auto tap stopped", ShadowToast.getTextOfLatestToast());
        assertEquals("Auto tap", LiveAutoTap.menuLabel());

        touch(MotionEvent.ACTION_UP, 300, 600, at);
        int afterHold = downs;
        pass(2000);
        assertEquals("no taps after the hold", afterHold, downs);
    }

    @Test public void aQuickTouchOrASwipeLeavesTheTapsRunning() {
        openRoom();
        LiveAutoTap.menuTap();
        pass(500);

        long at = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, 300, 600, at);
        pass(100);
        touch(MotionEvent.ACTION_UP, 300, 600, at);
        pass(holdMs());
        assertEquals("a quick touch isn't a hold", "Stop tapping this LIVE", LiveAutoTap.menuLabel());

        at = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, 300, 600, at);
        touch(MotionEvent.ACTION_MOVE, 300, 900, at);
        pass(holdMs());
        assertEquals("a finger that moved is a swipe", "Stop tapping this LIVE", LiveAutoTap.menuLabel());
        touch(MotionEvent.ACTION_UP, 300, 900, at);
        int before = downs;
        pass(1500);
        assertTrue("taps carry on after the swipe", downs > before);
        assertEquals("Auto tap started", ShadowToast.getTextOfLatestToast());
    }

    /** When the hold did open the menu, its Stop row is there, so the hold leaves the taps alone. */
    @Test public void aHoldThatOpenedTheMenuLeavesTheStopToItsRow() {
        openRoom();
        LiveAutoTap.menuTap();
        pass(500);

        long at = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, 300, 600, at);
        controller.windowFocusChanged(false);
        pass(holdMs());
        assertEquals("Stop tapping this LIVE", LiveAutoTap.menuLabel());
        assertEquals("Auto tap started", ShadowToast.getTextOfLatestToast());
    }

    @Test public void withTheChipsOnTheVideoAHoldOnlyPausesTheTaps() {
        Settings.LIVE_BUTTONS_IN_MENU.save(false);
        openRoom();
        LiveAutoTap.menuTap();
        pass(500);

        long at = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, 300, 600, at);
        pass(holdMs());
        assertEquals("Stop tapping this LIVE", LiveAutoTap.menuLabel());
        touch(MotionEvent.ACTION_UP, 300, 600, at);
    }

    @Test public void tapsWaitUntilTheMenuHasClosed() {
        openRoom();
        controller.windowFocusChanged(false);
        LiveAutoTap.menuTap();
        pass(1000);
        assertEquals("nothing lands while the menu has the screen", 0, downs);
        assertEquals("Stop tapping this LIVE", LiveAutoTap.menuLabel());

        controller.windowFocusChanged(true);
        pass(1000);
        assertTrue("taps " + downs, downs > 0);
    }

    /** A tap from a moment ago mustn't make the menu look like something that tap opened. */
    @Test public void startingFromTheMenuRightAfterARunIsNotTakenForAPanel() {
        openRoom();
        LiveAutoTap.menuTap();
        pass(1000);
        LiveAutoTap.menuTap();
        assertTrue(downs > 0);

        controller.windowFocusChanged(false);
        LiveAutoTap.menuTap();
        pass(1500);
        assertEquals("Stop tapping this LIVE", LiveAutoTap.menuLabel());
    }

    @Test public void theRecordRowSaysWhatATapWillDo() {
        Settings.RECORD_LIVE.save(true);
        assertEquals("Record", LiveRecordChip.menuLabel());
        LiveRecordChip.holdForTests(null, true);
        assertEquals("Saving the recording", LiveRecordChip.menuLabel());
        LiveRecordChip.menuTap();
        assertEquals("Saving the recording", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aRowCanCloseTheMenuItCameFrom() {
        openRoom();
        Dialog menu = new Dialog(activity);
        menu.show();
        LivePanelTheme.liveMenu(menu);
        LivePanelTheme.closeLiveMenu();
        idle();
        assertFalse(menu.isShowing());
        LivePanelTheme.closeLiveMenu();
    }
}

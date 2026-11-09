/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import com.ss.android.ugc.aweme.common.widget.VerticalViewPager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Invisible controls button (#374): an eye at the top left of the main feed that draws the
 * controls the fade covers, the tabs included, at 0 while they stay where they are and keep
 * taking taps, and brings them back on the next tap. Gone with its switch, under Pause and off
 * the main feed, and the controls come back with it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w1080dp-h1920dp-mdpi")
public class InvisibleControlsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String[] NAMES = {"view_rootview", "47.7.3:lkj", "47.7.3:bs2",
            "videomusiccoverblock", "47.7.3:lk8", "47.7.3:v1f", "47.7.3:opn"};
    private static final int[] IDS = {0x7f0a1101, 0x7f0a1102, 0x7f0a1103,
            0x7f0a1104, 0x7f0a1105, 0x7f0a1106, 0x7f0a1107};
    private static final int HOME_ID = 0x7f0a1108;
    private static final String HOME_NAME = "47.7.3:opf";

    private boolean overlays;
    private ActivityController<Activity> controller;
    private Activity activity;
    private View column;
    private View frame;
    private View cover;
    private View search;
    private View topTabs;
    private FrameLayout bottomBar;
    private View home;
    private int columnClicks;
    private int tabClicks;

    @Before
    public void setUp() {
        overlays = SettingsStatus.videoOverlaysEnabled;
        SettingsStatus.videoOverlaysEnabled = true;
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
        Utils.setContext(activity);
        for (int i = 0; i < NAMES.length; i++) VideoOverlayHider.resolveForTests(NAMES[i], IDS[i]);
        FeedVisibility.resolveForTests(activity.getPackageName(), HOME_NAME, HOME_ID);
        feed();
    }

    @After
    public void tearDown() {
        InvisibleControls.resetForTests();
        PausedProcess.set(false);
        RememberClearDisplayPatch.rememberClearDisplayEvent(new VideoOverlayHiderTest.ClearEvent(false, 2));
        Settings.INVISIBLE_CONTROLS_BUTTON.save(false);
        Settings.INVISIBLE_CONTROLS_ON.save(false);
        Settings.INVISIBLE_CONTROLS_KEEP_CAPTION.save(false);
        Settings.FADE_CONTROLS_OPACITY.save(100);
        Settings.CLEAR_DISPLAY.save(false);
        SettingsStatus.videoOverlaysEnabled = overlays;
        for (String name : NAMES) VideoOverlayHider.resolveForTests(name, 0);
        FeedVisibility.resolveForTests(activity.getPackageName(), HOME_NAME, 0);
        BurnInGuard.resetForTests();
        ClearDisplayShownControls.resetForTests();
        controller.pause().stop().destroy();
    }

    /**
     * For You as 47.7.3 lays it out: the top tab row over a feed pager whose cell holds the rail's
     * column, the caption frame, the music disc and the search bar, and the bottom bar with Home
     * selected in it.
     */
    private void feed() {
        FrameLayout root = new FrameLayout(activity);
        VerticalViewPager pager = new VerticalViewPager(activity);
        FrameLayout cell = new FrameLayout(activity);
        cell.setId(IDS[0]);
        column = control(IDS[1]);
        column.setOnClickListener(v -> columnClicks++);
        cell.addView(column, new FrameLayout.LayoutParams(160, 480, Gravity.END | Gravity.CENTER_VERTICAL));
        frame = control(IDS[2]);
        cell.addView(frame, new FrameLayout.LayoutParams(640, 240, Gravity.START | Gravity.BOTTOM));
        cover = control(IDS[3]);
        cell.addView(cover, new FrameLayout.LayoutParams(96, 96, Gravity.END | Gravity.BOTTOM));
        search = control(IDS[4]);
        cell.addView(search, new FrameLayout.LayoutParams(640, 60, Gravity.START | Gravity.BOTTOM));
        pager.addView(cell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        topTabs = control(IDS[5]);
        root.addView(topTabs, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 48, Gravity.TOP));
        bottomBar = new FrameLayout(activity);
        bottomBar.setId(IDS[6]);
        home = new View(activity);
        home.setId(HOME_ID);
        home.setSelected(true);
        home.setOnClickListener(v -> tabClicks++);
        bottomBar.addView(home, new FrameLayout.LayoutParams(200, 100, Gravity.START));
        root.addView(bottomBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 100, Gravity.BOTTOM));
        activity.setContentView(root);
        idle();
        assertTrue("the window never laid out", home.getWidth() > 0 && pager.getWidth() > 0);
    }

    private View control(int id) {
        View view = new View(activity);
        view.setId(id);
        view.setClickable(true);
        return view;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void pass() {
        VideoOverlayHider.applyTo(activity);
    }

    private View[] controls() {
        return new View[]{column, frame, cover, search, topTabs, bottomBar};
    }

    private void assertGhosted(View... views) {
        for (View view : views) {
            assertEquals("drawn at 0", 0f, view.getAlpha(), 0f);
            assertEquals("still in its place", View.VISIBLE, view.getVisibility());
            // The bar is a plain container; the tab inside it is what takes the tap.
            View taker = view == bottomBar ? home : view;
            assertTrue("still takes taps", taker.isClickable() && taker.isShown());
        }
    }

    private void assertStock(View... views) {
        for (View view : views) {
            assertEquals("back at full opacity", 1f, view.getAlpha(), 0f);
            assertEquals(View.VISIBLE, view.getVisibility());
        }
    }

    /** A tap the way the system delivers it: to the window's decor view. */
    private void tap(View target) {
        int[] corner = new int[2];
        target.getLocationInWindow(corner);
        float x = corner[0] + target.getWidth() / 2f;
        float y = corner[1] + target.getHeight() / 2f;
        long now = SystemClock.uptimeMillis();
        send(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0));
        send(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, x, y, 0));
        idle();
    }

    private void send(MotionEvent event) {
        try {
            activity.getWindow().getDecorView().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    @Test
    public void theSwitchPutsTheEyeAtTheTopLeftOfTheMainFeed() {
        pass();
        assertNull("the switch is off", InvisibleControls.buttonForTests());

        Settings.INVISIBLE_CONTROLS_BUTTON.save(true);
        pass();
        View button = InvisibleControls.buttonForTests();
        assertNotNull(button);
        assertSame(activity.findViewById(android.R.id.content), button.getParent());
        assertEquals(View.VISIBLE, button.getVisibility());
        assertTrue(button.isClickable());
        assertEquals(1f, button.getAlpha(), 0f);
        assertEquals("Make the controls invisible", button.getContentDescription().toString());
        assertStock(controls());

        idle();
        View content = activity.findViewById(android.R.id.content);
        assertTrue("not at the left", button.getLeft() < content.getWidth() / 4);
        assertTrue("not near the top", button.getBottom() < content.getHeight() / 4);
        assertTrue("over TikTok's top bar row", button.getTop() >= 56);
        assertEquals("a smaller tap area than the other buttons over the feed",
                SettingsUi.dp(activity, 48), button.getWidth());
        assertEquals(button.getWidth(), button.getHeight());
    }

    @Test
    public void aTapMakesTheControlsInvisibleWhereTheyAreAndTheNextBringsThemBack() {
        Settings.INVISIBLE_CONTROLS_BUTTON.save(true);
        pass();
        View button = InvisibleControls.buttonForTests();
        assertNotNull(button);

        assertTrue(button.performClick());
        assertTrue("the tap wasn't kept", Settings.INVISIBLE_CONTROLS_ON.get());
        assertGhosted(controls());
        assertFalse("an invisible control is marked to let taps through", TapThroughControls.isMarked(column));
        assertSame("the eye came off the feed", button, InvisibleControls.buttonForTests());
        assertEquals(View.VISIBLE, button.getVisibility());
        assertTrue(button.isClickable());
        assertEquals("the eye stays findable", InvisibleControls.GHOSTED_ALPHA, button.getAlpha(), 0f);
        assertEquals("Show the controls", button.getContentDescription().toString());

        tap(column);
        assertEquals("the invisible rail didn't take the tap", 1, columnClicks);
        tap(home);
        assertEquals("the invisible tab didn't take the tap", 1, tabClicks);

        pass();
        assertGhosted(controls());

        assertTrue(button.performClick());
        assertFalse(Settings.INVISIBLE_CONTROLS_ON.get());
        assertStock(controls());
        assertEquals(1f, button.getAlpha(), 0f);
        assertEquals("Make the controls invisible", button.getContentDescription().toString());
    }

    /** It's where the reader left it, like the feed's mute, so a backup doesn't carry it. */
    @Test
    public void theChoiceIsKeptOnThePhoneAndNotInABackup() {
        assertFalse(Settings.INVISIBLE_CONTROLS_ON.includeWithImportExport);
        assertTrue(Settings.INVISIBLE_CONTROLS_BUTTON.includeWithImportExport);
        assertTrue(Settings.INVISIBLE_CONTROLS_KEEP_CAPTION.includeWithImportExport);

        Settings.INVISIBLE_CONTROLS_BUTTON.save(true);
        Settings.INVISIBLE_CONTROLS_ON.save(true);
        pass();
        assertGhosted(controls());
    }

    @Test
    public void theSwitchOffOrPauseTakesTheEyeAwayAndGivesTheControlsBack() {
        Settings.INVISIBLE_CONTROLS_BUTTON.save(true);
        Settings.INVISIBLE_CONTROLS_ON.save(true);
        pass();
        View button = InvisibleControls.buttonForTests();
        assertNotNull(button);
        assertGhosted(controls());

        PausedProcess.set(true);
        pass();
        assertNull("Pause left the eye", InvisibleControls.buttonForTests());
        assertNull(button.getParent());
        assertStock(controls());

        PausedProcess.set(false);
        pass();
        assertNotNull("the eye didn't come back after Pause", InvisibleControls.buttonForTests());
        assertGhosted(controls());

        Settings.INVISIBLE_CONTROLS_BUTTON.save(false);
        pass();
        assertNull("the switch off left the eye", InvisibleControls.buttonForTests());
        assertStock(controls());

        Settings.INVISIBLE_CONTROLS_BUTTON.save(true);
        SettingsStatus.videoOverlaysEnabled = false;
        pass();
        assertNull("a bundle without the overlay patch drew the eye", InvisibleControls.buttonForTests());
    }

    @Test
    public void offTheMainFeedTheEyeGoesAndTheControlsComeBack() {
        Settings.INVISIBLE_CONTROLS_BUTTON.save(true);
        Settings.INVISIBLE_CONTROLS_ON.save(true);
        pass();
        View button = InvisibleControls.buttonForTests();
        assertNotNull(button);

        // Profile, Inbox or another tab.
        home.setSelected(false);
        pass();
        assertEquals(View.GONE, button.getVisibility());
        assertStock(controls());

        home.setSelected(true);
        pass();
        assertEquals(View.VISIBLE, button.getVisibility());
        assertGhosted(controls());

        // A window with no Home tab, like a video opened from a profile or search.
        bottomBar.removeView(home);
        pass();
        assertEquals(View.GONE, button.getVisibility());
        assertStock(column, frame, cover, search, topTabs);
        assertTrue("going away doesn't change what the reader chose", Settings.INVISIBLE_CONTROLS_ON.get());
    }

    @Test
    public void clearDisplayTakesTheEyeAway() {
        Settings.INVISIBLE_CONTROLS_BUTTON.save(true);
        Settings.INVISIBLE_CONTROLS_ON.save(true);
        pass();
        View button = InvisibleControls.buttonForTests();
        assertNotNull(button);

        RememberClearDisplayPatch.rememberClearDisplayEvent(new VideoOverlayHiderTest.ClearEvent(true, 0));
        pass();
        assertEquals(View.GONE, button.getVisibility());
    }

    @Test
    public void keepTheCaptionLeavesTheCaptionFrameInSight() {
        Settings.INVISIBLE_CONTROLS_BUTTON.save(true);
        Settings.INVISIBLE_CONTROLS_ON.save(true);
        Settings.INVISIBLE_CONTROLS_KEEP_CAPTION.save(true);
        pass();
        assertGhosted(column, cover, search, topTabs, bottomBar);
        assertStock(frame);

        // With the fade set, the caption stays at the reader's level rather than full.
        Settings.FADE_CONTROLS_OPACITY.save(50);
        pass();
        assertEquals(0.5f, frame.getAlpha(), 0f);
        assertGhosted(column, topTabs, bottomBar);

        Settings.INVISIBLE_CONTROLS_KEEP_CAPTION.save(false);
        pass();
        assertGhosted(controls());
    }

    /** With the mode off, a fade of 0 still takes the rail away and holds the tabs at the floor. */
    @Test
    public void aFadeOfZeroWorksAsBeforeUntilTheEyeIsTapped() {
        Settings.FADE_CONTROLS_OPACITY.save(0);
        Settings.INVISIBLE_CONTROLS_BUTTON.save(true);
        pass();
        assertEquals("0 still hides the column", View.GONE, column.getVisibility());
        assertEquals(View.INVISIBLE, frame.getVisibility());
        assertEquals(VideoOverlayHider.NAVIGATION_FADE_FLOOR / 100f, topTabs.getAlpha(), 0.0001f);

        View button = InvisibleControls.buttonForTests();
        assertNotNull(button);
        assertTrue(button.performClick());
        assertGhosted(controls());

        assertTrue(button.performClick());
        assertEquals(View.GONE, column.getVisibility());
        assertEquals(VideoOverlayHider.NAVIGATION_FADE_FLOOR / 100f, topTabs.getAlpha(), 0.0001f);
    }
}

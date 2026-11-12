package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * The hold covers the feed. What it must not cover is the row of tabs along the bottom, because
 * the whole promise of holding the feed rather than emptying it is that messages, a profile and
 * search are still there. A panel over the tab bar is a panel that shuts the app.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
// Every other class that captures a view declares this; this one did not, and its published
// capture was a black rectangle. Without it a draw into a bitmap is a no-op that throws nothing.
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class SessionLockOverlayTest {
    private final AtomicLong now = new AtomicLong();

    public static class HostActivity extends Activity {
    }

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SessionBudget.awaitWritesForTests();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_PASSES_PER_DAY.resetToDefault();
        Settings.SESSION_BUDGET_NOTICE_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        now.set(at(2026, Calendar.SEPTEMBER, 7, 12, 0));
        SessionBudget.setClockForTests(now::get);
        SessionBudget.resetForTests();
        // With no hold this detaches and hands back the audio focus, which is what clears the
        // overlay's own static state between cases.
        SessionLockOverlay.sync();
    }

    @After public void tearDown() throws Exception {
        // Robolectric reuses its sandbox classloader across test classes, so a seeded tab view
        // would answer for every later test that asks whether there is an Inbox.
        seedInboxTab(null);
        seedHomeTab(null);
        // The override is a static Boolean on the shared library and Robolectric reuses its
        // sandbox classloader across test classes, so leaving it set answers for every later
        // test that expects the system configuration.
        org.robolectric.util.ReflectionHelpers.setStaticField(
                Utils.class, "isDarkModeEnabled", null);
        SessionBudget.setClockForTests(null);
        SessionBudget.awaitWritesForTests();
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_NOTICE_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        // Finish the timer before Robolectric clears its queue but keeps the static flag.
        Runnable tick = org.robolectric.util.ReflectionHelpers.getStaticField(SessionLockOverlay.class, "TICK");
        android.os.Handler handler = org.robolectric.util.ReflectionHelpers.getStaticField(SessionLockOverlay.class, "MAIN");
        handler.removeCallbacks(tick);
        tick.run();
        // That last tick asks the budget whether the hold is still on, and asking can queue a
        // write. It used to be the final statement here, so the write landed after the state had
        // been reset and the next class in the sandbox inherited a locked day: the feed controls
        // hide themselves while the budget is spent, so FeedOverlaySettingsTest found every one
        // of them gone. Everything this class changes is put back after the tick, not before.
        SessionBudget.awaitWritesForTests();
        SessionBudget.resetForTests();
        Settings.SESSION_BUDGET_STATE.resetToDefault();
        Settings.SESSION_BUDGET_VIDEOS.resetToDefault();
        Settings.SESSION_BUDGET_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK_MINUTES.resetToDefault();
        Settings.SESSION_BUDGET_LOCK.resetToDefault();
        Settings.SESSION_BUDGET_PASSES_PER_DAY.resetToDefault();
    }

    @Test public void theHoldStopsAboveTheTabBar() throws Exception {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            assertNotNull(root);

            // A tab bar the height of a real one, laid out across the bottom.
            FrameLayout bar = new FrameLayout(activity);
            View homeTab = new View(activity);
            bar.addView(homeTab);
            root.addView(bar);
            layout(root, 480, 960);
            bar.layout(0, 860, 480, 960);
            homeTab.layout(0, 0, 96, 100);
            // The id the Home tab is known by does not exist outside TikTok, so the view the
            // lookup would have found is put straight into its cache.
            seedHomeTab(homeTab);

            int height = (int) navigationHeight().invoke(null, activity, root);

            assertTrue("the panel would have covered the tab bar, height was " + height,
                    height > 0);
            assertEquals(100, height);
        }
    }

    @Test public void aBuildWithNoTabBarIsCoveredCompletely() throws Exception {
        // Better a hold that covers everything than one with a gap along the bottom that turns
        // out to be the feed.
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            layout(root, 480, 960);

            assertEquals(0, (int) navigationHeight().invoke(null, activity, root));
        }
    }

    // On S22, the tab row and system inset occupy a small bottom strip. Match the virtual
    // display to the fixture scale so automatic window layouts preserve that proportion.
    @Test @Config(qualifiers = "w480dp-h960dp-mdpi")
    public void aTabRowMeasuredAfterHoldAttachmentRemainsTappable() {
        assertProfileRemainsTappableAfterNavigationLayout(false, false);
    }

    @Test @Config(qualifiers = "w480dp-h960dp-mdpi")
    public void aRetainedHoldTracksLaterNavigationHeightChanges() {
        assertProfileRemainsTappableAfterNavigationLayout(true, false);
    }

    @Test @Config(qualifiers = "w480dp-h960dp-mdpi")
    public void aMeasuredTabRowAboveTheSystemInsetRemainsTappableFromAnOffsetRoot() {
        assertProfileRemainsTappableAfterNavigationLayout(true, true);
    }

    private void assertProfileRemainsTappableAfterNavigationLayout(
            boolean initiallyMeasured, boolean withSystemInset) {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("held-navigation-video");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            layoutHoldRoot(root, withSystemInset);

            FrameLayout bar = new FrameLayout(activity);
            FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(-1, 80, Gravity.BOTTOM);
            barParams.bottomMargin = withSystemInset ? 100 : 0;
            root.addView(bar, barParams);
            View home = new View(activity);
            home.setSelected(true);
            bar.addView(home, new FrameLayout.LayoutParams(96, -1, Gravity.LEFT));
            View profile = new View(activity);
            AtomicInteger profileTaps = new AtomicInteger();
            profile.setOnClickListener(view -> profileTaps.incrementAndGet());
            bar.addView(profile, new FrameLayout.LayoutParams(96, -1, Gravity.RIGHT));
            seedHomeTab(home);
            if (initiallyMeasured) layoutHoldRoot(root, withSystemInset);
            else assertEquals("the regression needs an unmeasured native row", 0, bar.getHeight());

            SessionLockOverlay.sync();
            View panel = root.getChildAt(root.getChildCount() - 1);
            AtomicInteger panelDowns = new AtomicInteger();
            panel.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) panelDowns.incrementAndGet();
                return false;
            });
            if (initiallyMeasured && !withSystemInset) {
                assertEquals(80, ((FrameLayout.LayoutParams) panel.getLayoutParams()).bottomMargin);
                // A navigation-mode or inset change gives the retained tab row a new height.
                ViewGroup.LayoutParams params = bar.getLayoutParams();
                params.height = 180;
                bar.setLayoutParams(params);
            }

            layoutHoldRoot(root, withSystemInset);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            layoutHoldRoot(root, withSystemInset);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            layoutHoldRoot(root, withSystemInset);
            assertTrue("the native tab was never laid out", profile.getHeight() > 0);
            assertTrue("the regression must reuse the existing hold", panel.getParent() == root);
            if (withSystemInset) {
                int[] rootPosition = new int[2];
                root.getLocationOnScreen(rootPosition);
                assertTrue("the regression needs a nonzero root origin",
                        rootPosition[0] != 0 && rootPosition[1] != 0);
                assertEquals(100, root.getHeight() - bar.getBottom());
            }

            // The top of the enlarged tab lies under the old margin. Dispatch through the root
            // so a stale hold must actually intercept the tap rather than just report bad bounds.
            tapRoot(root, bar.getLeft() + profile.getLeft() + profile.getWidth() / 2f,
                    bar.getTop() + profile.getTop() + 16);
            assertEquals("the retained hold intercepted the native Profile tab", 1, profileTaps.get());
            assertEquals("the native Profile tap reached the hold", 0, panelDowns.get());
            // Also protect the last pixel of feed above the tab. Mixing screen and root
            // coordinates can move the hold too high and expose this strip instead.
            tapRoot(root, 240, bar.getTop() - 1);
            assertEquals("repairing navigation uncovered the held feed", 1, panelDowns.get());
            assertTrue(SessionBudget.isLocked());
        } finally {
            seedHomeTab(null);
            Utils.setActivity(null);
            SessionBudget.releaseLock();
            SessionLockOverlay.sync();
        }
    }

    /**
     * TikTok's clear display sets its tab bar GONE (the S22, 2026-09-23). The hold read that as
     * "not the feed", took its panel down and gave the sound back, so with a spent budget the feed
     * went on playing in clear display, TikTok's own or the automatic one. The panel stays up and
     * keeps the sound now, and it still stops where the bar was: that strip is TikTok's own clear
     * display bar, whose X brings the tabs back. Covering it, as the first version of this fix
     * did, left the reader no way to the messages and profiles the panel says still work.
     */
    @Test public void clearDisplayKeepsTheHoldUpAndTikToksClearBarReachable() {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("cleared-video");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            FrameLayout bar = new FrameLayout(activity);
            root.addView(bar, new FrameLayout.LayoutParams(-1, 80, Gravity.BOTTOM));
            View home = new View(activity);
            home.setSelected(true);
            bar.addView(home, new FrameLayout.LayoutParams(96, -1, Gravity.LEFT));
            seedHomeTab(home);
            layoutHoldRoot(root, false);

            // Cleared when the hold goes up: the panel brings TikTok's controls back, since the
            // tabs its text points to are gone in clear display.
            org.robolectric.util.ReflectionHelpers.setStaticField(
                    app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch.class, "clearNow", true);
            SessionLockOverlay.sync();
            View panel = root.getChildAt(root.getChildCount() - 1);
            assertEquals(View.VISIBLE, panel.getVisibility());
            assertFalse("the hold went up and left the controls cleared",
                    app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch.isClearDisplayNow());
            assertEquals("the fixture's hold does not stop above the bar",
                    80, ((FrameLayout.LayoutParams) panel.getLayoutParams()).bottomMargin);

            // Clear display: the bar goes, the page stays.
            bar.setVisibility(View.GONE);
            SessionLockOverlay.sync();
            assertEquals("the hold came down in clear display", View.VISIBLE, panel.getVisibility());
            assertTrue("the hold gave the sound back in clear display", quietened());
            assertEquals("the panel covered TikTok's clear display bar and its way out",
                    80, ((FrameLayout.LayoutParams) panel.getLayoutParams()).bottomMargin);

            // The controls back: the row is reachable again.
            bar.setVisibility(View.VISIBLE);
            layoutHoldRoot(root, false);
            SessionLockOverlay.sync();
            assertEquals(80, ((FrameLayout.LayoutParams) panel.getLayoutParams()).bottomMargin);
        } finally {
            org.robolectric.util.ReflectionHelpers.callStaticMethod(
                    app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch.class, "resetForTests");
            seedHomeTab(null);
            Utils.setActivity(null);
            SessionBudget.releaseLock();
            SessionLockOverlay.sync();
        }
    }

    private static void layoutHoldRoot(View root, boolean withSystemInset) {
        root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        int left = withSystemInset ? 24 : 0;
        int top = withSystemInset ? 45 : 0;
        root.layout(left, top, left + 480, top + 960);
    }

    private static void tapRoot(ViewGroup root, float x, float y) {
        MotionEvent down = MotionEvent.obtain(10, 10, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(10, 30, MotionEvent.ACTION_UP, x, y, 0);
        try {
            assertTrue(root.dispatchTouchEvent(down));
            assertTrue(root.dispatchTouchEvent(up));
        } finally {
            down.recycle();
            up.recycle();
        }
        // View posts its click after ACTION_UP, as it does when a real tab receives a touch.
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    @Test public void aRowTallEnoughToBeTheFeedIsNotMistakenForNavigation() throws Exception {
        // A lookup that walked too far up would find the whole page and leave the panel with a
        // margin the height of the screen, which is a hold covering nothing at all.
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            ViewGroup root = activity.findViewById(android.R.id.content);

            FrameLayout tall = new FrameLayout(activity);
            View homeTab = new View(activity);
            tall.addView(homeTab);
            root.addView(tall);
            layout(root, 480, 960);
            tall.layout(0, 0, 480, 900);
            homeTab.layout(0, 800, 96, 900);
            seedHomeTab(homeTab);

            assertEquals(0, (int) navigationHeight().invoke(null, activity, root));
        }
    }

    @Test public void nothingIsDrawnWithoutAHold() {
        assertNull("a hold nobody set", holdOrNull());
    }

    @Test public void theCountdownStopsWhileTheAppIsAwayAndComesBackWithIt() throws Exception {
        // A stopped activity is neither finishing nor destroyed, so the tick used to run its
        // whole body once a second for as long as the hold lasted, up to the reset hour, while
        // TikTok sat in recents. The app's own foreground callbacks say when to stop and start.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        Settings.SESSION_BUDGET_LOCK.save(true);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            SessionLockOverlay.onForeground();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue("a hold on screen has no countdown", ticking());

            SessionLockOverlay.onBackground();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofSeconds(3));
            assertFalse("the countdown kept ticking with the app in the background", ticking());
            assertTrue("the hold was lifted by the app going away", SessionBudget.isLocked());

            SessionLockOverlay.onForeground();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue("the countdown did not come back with the app", ticking());
        } finally {
            SessionLockOverlay.onForeground();
        }
    }

    /**
     * A hold that ends while the app is away takes its panel down on return. The tick needs a
     * running hold to start and the player never syncs an ended one, so the panel used to stay,
     * still naming the time the feed opens again.
     */
    @Test public void aHoldThatEndedWhileAwayIsGoneOnReturn() throws Exception {
        java.util.concurrent.atomic.AtomicLong now =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        SessionBudget.setClockForTests(now::get);
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            int bare = root.getChildCount();
            SessionLockOverlay.sync();
            assertEquals("no panel went up", bare + 1, root.getChildCount());

            SessionLockOverlay.onBackground();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofSeconds(3));
            now.addAndGet(6 * 60_000L);
            assertFalse(SessionBudget.isLocked());

            SessionLockOverlay.onForeground();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("the ended hold's panel stayed on the feed", bare, root.getChildCount());
        } finally {
            SessionBudget.setClockForTests(null);
            SessionLockOverlay.onForeground();
        }
    }

    private static boolean ticking() {
        return org.robolectric.util.ReflectionHelpers.getStaticField(SessionLockOverlay.class, "ticking");
    }

    @Test public void aLockedDayLeavesThePanelWithNoWayOut() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        Settings.SESSION_BUDGET_LOCK.save(true);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SessionLockOverlay.sync();

            ViewGroup root = activity.findViewById(android.R.id.content);
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            View release = panel.getChildAt(3);
            assertEquals("Open the feed anyway is still on a locked panel",
                    View.GONE, release.getVisibility());

            // And tapping where it used to be does nothing, so a stale panel cannot be used
            // as a way out either.
            release.performClick();
            assertTrue("a tap lifted a locked hold", SessionBudget.isLocked());

            android.widget.TextView hint = (android.widget.TextView) panel.getChildAt(2);
            assertTrue("the panel does not say when the feed comes back: " + hint.getText(),
                    hint.getText().toString().contains(SessionLockOverlay.resetTimeLabel()));
        }
    }

    @Test public void aSpentCapCountsDownAndThenTakesTheWayOutAway() throws Exception {
        // Between the way out always being there and Lock today, which removes it. The control
        // says how many are left, and once they are gone it is as absent as it is on a locked
        // day. The hint below still says what does work, so the panel is not a dead end.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        Settings.SESSION_BUDGET_PASSES_PER_DAY.save(2);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SessionLockOverlay.sync();

            // Looked up again after every sync: a hold that ends detaches the panel, so the
            // next one is a different view and a held reference reads the old text for ever.
            assertEquals(View.VISIBLE, wayOut(activity).getVisibility());
            assertEquals("Open the feed anyway, 2 left today",
                    wayOut(activity).getText().toString());
            assertEquals("a screen reader would not hear the count",
                    wayOut(activity).getText().toString(),
                    wayOut(activity).getContentDescription().toString());

            // Spend the first. The next hold is a raised budget away, which is what a reader
            // who carries on scrolling does.
            wayOut(activity).performClick();
            assertTrue("the way out did not open the feed", !SessionBudget.isLocked());
            reachTheHoldAgain("b", 2);
            SessionLockOverlay.sync();
            assertEquals("Open the feed anyway, the last time today",
                    wayOut(activity).getText().toString());

            // Spend the last, and it is gone for the rest of the day.
            wayOut(activity).performClick();
            assertTrue(!SessionBudget.isLocked());
            reachTheHoldAgain("c", 3);
            SessionLockOverlay.sync();
            assertEquals("a spent cap left the way out on the panel",
                    View.GONE, wayOut(activity).getVisibility());

            wayOut(activity).performClick();
            assertTrue("a tap got through a spent cap", SessionBudget.isLocked());
        }
    }

    /** The way out on whichever panel is up now. */
    private static android.widget.TextView wayOut(Activity activity) {
        ViewGroup root = activity.findViewById(android.R.id.content);
        ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
        return (android.widget.TextView) panel.getChildAt(3);
    }

    /** Raises the budget past today's count so the notice arms and the next hold starts. */
    private static void reachTheHoldAgain(String awemeId, int budget) {
        Settings.SESSION_BUDGET_VIDEOS.save(budget);
        SessionBudget.claimNotice();
        SessionBudget.noteVideo(awemeId);
        assertTrue("the hold did not come back", SessionBudget.claimNotice());
    }

    @Test public void theHoldIsAnnouncedAndTakesTheFeedOutOfTheReadingOrder() throws Exception {
        // The panel swallows touches, which is the whole hold for anyone looking at the screen
        // and nothing at all for anyone swiping through it with a screen reader: the like,
        // comment and share controls behind it stayed reachable.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            View behind = new View(activity);
            root.addView(behind);
            int before = behind.getImportantForAccessibility();

            SessionLockOverlay.sync();
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            assertNotNull(panel);

            assertEquals("the hold goes up without saying so",
                    SessionBudgetNotice.spentMessage(),
                    String.valueOf(panel.getAccessibilityPaneTitle()));
            // The pane title is what announces the arrival. The panel itself must not be a live
            // region: a live region announces for anything that changes anywhere beneath it, and
            // the countdown under this one changes once a second for the length of the hold.
            assertEquals("the whole hold is a live region, so every tick re-reads all of it",
                    View.ACCESSIBILITY_LIVE_REGION_NONE, panel.getAccessibilityLiveRegion());
            assertEquals("the feed behind the hold can still be swiped through",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    behind.getImportantForAccessibility());

            View release = panel.getChildAt(3);
            android.view.accessibility.AccessibilityNodeInfo info =
                    android.view.accessibility.AccessibilityNodeInfo.obtain();
            release.onInitializeAccessibilityNodeInfo(info);
            assertEquals("the only way out of the hold is read as plain text",
                    android.widget.Button.class.getName(), String.valueOf(info.getClassName()));

            // The panel is clickable only to swallow touches. Handed that, TalkBack stopped on the
            // panel itself, read the whole hold again and said it could be activated.
            var panelInfo = android.view.accessibility.AccessibilityNodeInfo.obtain();
            panel.onInitializeAccessibilityNodeInfo(panelInfo);
            assertFalse("a reader is told the panel itself can be pressed", panelInfo.isClickable());
            assertFalse("a reader is told the panel itself can take focus", panelInfo.isFocusable());
            assertFalse("a reader is offered a click on the panel itself", panelInfo.getActionList().contains(
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
            assertFalse("a reader is offered focus on the panel itself", panelInfo.getActionList().contains(
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS));
            assertTrue("the panel stopped swallowing touches", panel.isClickable());

            // And the feed is handed back exactly as it was found.
            Method detach = SessionLockOverlay.class.getDeclaredMethod("detach");
            detach.setAccessible(true);
            detach.invoke(null);
            assertEquals("the feed was left out of the reading order after the hold ended",
                    before, behind.getImportantForAccessibility());
        }
    }

    @Test public void aKeyboardReachesTheHoldAndTheTabsButNothingBehindThem() throws Exception {
        // Tab on a keyboard or d-pad walked from the hold into the video and the like and
        // comment buttons underneath it, and the panel itself was a stop that did nothing. The
        // tab bar shares a parent with the video, and has to stay in reach all the same.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            FrameLayout page = new FrameLayout(activity);
            // TikTok's pager around the feed and the tabs is a stop of its own.
            page.setFocusable(true);
            FrameLayout video = new FrameLayout(activity);
            video.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            android.widget.Button like = new android.widget.Button(activity);
            like.setFocusableInTouchMode(true);
            video.addView(like);
            FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            videoParams.bottomMargin = 100;
            page.addView(video, videoParams);
            FrameLayout bar = new FrameLayout(activity);
            android.widget.Button homeTab = new android.widget.Button(activity);
            homeTab.setFocusableInTouchMode(true);
            // Selected is what tells the hold it is on the feed.
            homeTab.setSelected(true);
            bar.addView(homeTab, new FrameLayout.LayoutParams(96, ViewGroup.LayoutParams.MATCH_PARENT));
            page.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 100, Gravity.BOTTOM));
            root.addView(page);
            // The catcher PausePlayback leaves over the feed: a stop of its own, not a parent.
            FrameLayout catcher = new FrameLayout(activity);
            catcher.setClickable(true);
            catcher.setFocusable(true);
            catcher.setFocusableInTouchMode(true);
            root.addView(catcher, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            layout(root, 480, 960);
            seedHomeTab(homeTab);
            int pageBefore = page.getDescendantFocusability();
            assertTrue(like.requestFocus());

            SessionLockOverlay.sync();
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            // Open messages only shows with an Inbox tab to open, and this host has none.
            View release = panel.getChildAt(3);
            assertEquals(View.VISIBLE, release.getVisibility());

            assertFalse("the feed kept keyboard focus under the hold", like.hasFocus());
            java.util.ArrayList<View> stops = new java.util.ArrayList<>();
            root.addFocusables(stops, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
            assertFalse("Tab reaches the feed behind the hold", stops.contains(like));
            assertFalse("Tab reaches the catcher behind the hold", stops.contains(catcher));
            assertFalse("the panel itself is a Tab stop", stops.contains(panel));
            assertFalse("the page around the feed and the tabs is an empty Tab stop",
                    stops.contains(page));
            assertTrue("Tab can't reach the way out of the hold", stops.contains(release));
            assertTrue("Tab can't reach the tabs below the hold", stops.contains(homeTab));
            assertFalse("the feed can still take focus behind the hold", like.requestFocus());
            assertFalse("the catcher can still take focus behind the hold", catcher.requestFocus());
            assertTrue("a reader can't reach the tabs below the hold",
                    homeTab.isImportantForAccessibility());
            assertFalse("a reader still reaches the feed behind the hold",
                    like.isImportantForAccessibility());
            assertEquals("the page holding the tabs was blocked whole",
                    pageBefore, page.getDescendantFocusability());

            // TikTok adds to the page after the hold went up, as its pager loading a page.
            android.widget.Button late = new android.widget.Button(activity);
            late.setFocusableInTouchMode(true);
            page.addView(late, 0, new FrameLayout.LayoutParams(100, 100));
            layout(root, 480, 960);
            SessionLockOverlay.sync();
            stops.clear();
            root.addFocusables(stops, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
            assertFalse("Tab reaches what TikTok added behind the hold", stops.contains(late));
            assertFalse("a reader reaches what TikTok added behind the hold",
                    late.isImportantForAccessibility());
            assertTrue("Tab lost the tabs after TikTok added to the page", stops.contains(homeTab));

            // The catcher curtains again when the bar moves under it. The hold went up after
            // it and sits over it, so the hold has to stay in reach.
            SessionLockOverlay.hideBehind(root, catcher, true, bar);
            stops.clear();
            root.addFocusables(stops, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
            assertTrue("the catcher's curtain took the hold above it", stops.contains(release));
            SessionLockOverlay.hideBehind(root, catcher, false);

            // A catcher TikTok already took off the root still has a watcher on it. It covers
            // nothing there, so it must not curtain the hold either.
            View offTheRoot = new View(activity);
            SessionLockOverlay.hideBehind(root, offTheRoot, true, bar);
            stops.clear();
            root.addFocusables(stops, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
            assertTrue("a panel off the root curtained the hold", stops.contains(release));
            SessionLockOverlay.hideBehind(root, offTheRoot, false);

            // Off to messages: the panel is kept but hidden, and the page is handed back.
            homeTab.setSelected(false);
            SessionLockOverlay.sync();
            assertEquals(View.GONE, panel.getVisibility());
            assertEquals("the feed's own focus setting wasn't handed back off the feed",
                    ViewGroup.FOCUS_AFTER_DESCENDANTS, video.getDescendantFocusability());
            assertTrue("the feed can't take focus off the feed", like.requestFocus());
            assertTrue("the page around the tabs stayed no stop off the feed", page.isFocusable());

            Method detach = SessionLockOverlay.class.getDeclaredMethod("detach");
            detach.setAccessible(true);
            detach.invoke(null);
            assertEquals("the feed's own focus setting wasn't handed back",
                    ViewGroup.FOCUS_AFTER_DESCENDANTS, video.getDescendantFocusability());
            assertTrue("the page around the tabs stayed no stop after the hold", page.isFocusable());
            assertTrue("the catcher isn't focusable after the hold", catcher.isFocusable());
            assertTrue("the catcher lost focus in touch mode", catcher.isFocusableInTouchMode());
            assertTrue("the feed can't take focus after the hold", like.requestFocus());
        }
    }

    @Test public void arrowKeysOnTheTabsMoveFocusWithoutPagingThePageUnderTheHold() {
        // TikTok's pager around the feed and the tabs pages on an arrow key whatever its own focus
        // says. On the S22 DPAD_RIGHT on the last tab paged it over to the creator's profile
        // under the hold, and TalkBack's scroll actions on it did the same.
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            Pager page = new Pager(activity);
            page.setFocusable(true);
            page.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            FrameLayout bar = new FrameLayout(activity);
            android.widget.Button homeTab = new android.widget.Button(activity);
            homeTab.setFocusableInTouchMode(true);
            bar.addView(homeTab, new FrameLayout.LayoutParams(96, ViewGroup.LayoutParams.MATCH_PARENT));
            android.widget.Button lastTab = new android.widget.Button(activity);
            lastTab.setFocusableInTouchMode(true);
            bar.addView(lastTab, new FrameLayout.LayoutParams(
                    96, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
            page.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 100, Gravity.BOTTOM));
            root.addView(page);
            FrameLayout panel = new FrameLayout(activity);
            android.widget.Button release = new android.widget.Button(activity);
            release.setFocusableInTouchMode(true);
            panel.addView(release, new FrameLayout.LayoutParams(200, 100, Gravity.CENTER));
            FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            panelParams.bottomMargin = 100;
            root.addView(panel, panelParams);
            layout(root, 480, 960);
            SessionLockOverlay.hideBehind(root, panel, true, bar);
            assertTrue(lastTab.requestFocus());
            java.util.List<Integer> heard = new java.util.ArrayList<>();
            lastTab.setOnKeyListener((view, code, event) -> {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) heard.add(code);
                return false;
            });

            press(activity, android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            assertEquals("an arrow key on the last tab paged the page under the hold", 0, page.paged);
            assertTrue("the tab bar no longer sees the arrow keys",
                    heard.contains(android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
            press(activity, android.view.KeyEvent.KEYCODE_DPAD_LEFT);
            assertTrue("an arrow key no longer moves along the tabs", homeTab.isFocused());
            press(activity, android.view.KeyEvent.KEYCODE_DPAD_UP);
            assertTrue("an arrow key no longer reaches the hold from the tabs", release.isFocused());
            press(activity, android.view.KeyEvent.KEYCODE_DPAD_DOWN);
            assertTrue("an arrow key no longer gets back to the tabs from the hold",
                    homeTab.isFocused() || lastTab.isFocused());
            assertEquals("an arrow key paged the page under the hold", 0, page.paged);
            assertFalse("a reader can still scroll the page under the hold",
                    page.isImportantForAccessibility());
            assertTrue("a reader can't reach the tabs below the hold",
                    lastTab.isImportantForAccessibility());

            SessionLockOverlay.hideBehind(null, panel, false);
            assertEquals("the page's own place in the reading order wasn't handed back",
                    View.IMPORTANT_FOR_ACCESSIBILITY_YES, page.getImportantForAccessibility());
            assertTrue(lastTab.requestFocus());
            press(activity, android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            assertEquals("the page's own arrow keys weren't handed back after the hold", 1, page.paged);
        }
    }

    /** A key as the window hands it on: the screen first, then a move of focus if nothing took it. */
    private static void press(Activity activity, int code) {
        View decor = activity.getWindow().getDecorView();
        if (!decor.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))) {
            int direction = code == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT
                    : code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT
                    : code == android.view.KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN;
            View focused = decor.findFocus();
            View next = focused == null ? null : focused.focusSearch(direction);
            if (next != null) next.requestFocus(direction);
        }
        decor.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code));
    }

    /** Pages on a DPAD_RIGHT nothing inside it took, whatever its own focus says, as TikTok's pager does. */
    private static final class Pager extends FrameLayout {
        int paged;

        Pager(android.content.Context context) {
            super(context);
        }

        @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
            if (super.dispatchKeyEvent(event)) return true;
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN
                    || event.getKeyCode() != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            paged++;
            return true;
        }
    }

    @Test public void twoPanelsThatHidTheWholePageBothLetTheTabsBackIn() {
        // A cold start puts the hold and the catcher up before the tab bar has a size, so both
        // hide the page whole. Each one finding the bar later has to open the page up, even
        // while the other one still hides it.
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            FrameLayout page = new FrameLayout(activity);
            page.setFocusable(true);
            android.widget.Button like = new android.widget.Button(activity);
            like.setFocusableInTouchMode(true);
            page.addView(like, new FrameLayout.LayoutParams(100, 100));
            FrameLayout bar = new FrameLayout(activity);
            android.widget.Button homeTab = new android.widget.Button(activity);
            homeTab.setFocusableInTouchMode(true);
            bar.addView(homeTab, new FrameLayout.LayoutParams(96, ViewGroup.LayoutParams.MATCH_PARENT));
            page.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 100, Gravity.BOTTOM));
            root.addView(page);
            View hold = new View(activity);
            View catcher = new View(activity);
            root.addView(hold);
            root.addView(catcher);
            layout(root, 480, 960);
            int pageImportance = page.getImportantForAccessibility();
            int pageFocusability = page.getDescendantFocusability();

            SessionLockOverlay.hideBehind(root, hold, true, null);
            SessionLockOverlay.hideBehind(root, catcher, true, null);
            assertFalse(homeTab.isImportantForAccessibility());

            SessionLockOverlay.hideBehind(root, hold, true, bar);
            SessionLockOverlay.hideBehind(root, catcher, true, bar);
            java.util.ArrayList<View> stops = new java.util.ArrayList<>();
            root.addFocusables(stops, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
            assertTrue("the tabs stayed out of Tab's reach once both panels found them",
                    stops.contains(homeTab));
            assertTrue("the tabs stayed out of a reader's reach once both panels found them",
                    homeTab.isImportantForAccessibility());
            assertFalse("Tab reaches the feed", stops.contains(like));
            assertFalse("the page around the tabs is a Tab stop", stops.contains(page));

            SessionLockOverlay.hideBehind(root, catcher, false);
            stops.clear();
            root.addFocusables(stops, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
            assertTrue("the catcher lifting hid the tabs again", stops.contains(homeTab));
            assertFalse("the catcher lifting handed the feed back under the hold", stops.contains(like));

            SessionLockOverlay.hideBehind(root, hold, false);
            assertEquals(pageImportance, page.getImportantForAccessibility());
            assertEquals(pageFocusability, page.getDescendantFocusability());
            assertTrue("the page wasn't handed its own focus back", page.isFocusable());
            assertTrue(like.requestFocus());
        }
    }

    @Test public void focusTikTokGivesAfterTheLastPassStandsAfterTheLift() {
        // Read on a pass, set on a pass. A view TikTok made focusable after the last one was
        // handed the mode recorded before it, so TikTok's own change was lost on the lift.
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            View changed = new View(activity);
            View left = new View(activity);
            for (View view : new View[] {changed, left}) {
                view.setFocusable(View.FOCUSABLE_AUTO);
                view.setOnClickListener(clicked -> { });
                root.addView(view);
            }
            View hold = new View(activity);
            root.addView(hold);
            layout(root, 480, 960);

            SessionLockOverlay.hideBehind(root, hold, true, null);
            assertFalse(changed.isFocusable());
            changed.setFocusable(View.FOCUSABLE);
            SessionLockOverlay.hideBehind(root, hold, false);

            assertEquals("TikTok's own focus setting was undone by the lift",
                    View.FOCUSABLE, changed.getFocusable());
            assertEquals("a view TikTok left alone didn't get its own mode back",
                    View.FOCUSABLE_AUTO, left.getFocusable());
            assertTrue(left.isFocusable());
        }
    }

    @Test public void whatWasCurtainedInsideABarFoundHigherUpIsHandedBack() {
        // The bar is the highest row that spans the width under the home tab, so a later pass
        // can find it a level up. What sat beside the old bar inside the new one was curtained
        // whole and stayed that way, out of reach right in the tab bar, until the panel lifted.
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            FrameLayout page = new FrameLayout(activity);
            View video = new View(activity);
            page.addView(video, new FrameLayout.LayoutParams(100, 100));
            FrameLayout outer = new FrameLayout(activity);
            FrameLayout inner = new FrameLayout(activity);
            inner.addView(new android.widget.Button(activity));
            View badge = new View(activity);
            outer.addView(inner);
            outer.addView(badge);
            page.addView(outer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 100, Gravity.BOTTOM));
            root.addView(page);
            View hold = new View(activity);
            root.addView(hold);
            layout(root, 480, 960);

            SessionLockOverlay.hideBehind(root, hold, true, inner);
            assertEquals("the badge beside the first bar wasn't curtained",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, badge.getImportantForAccessibility());

            SessionLockOverlay.hideBehind(root, hold, true, outer);
            assertEquals("what's inside the bar now stayed curtained",
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, badge.getImportantForAccessibility());
            assertEquals("the video beside the bar came out from behind the panel",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, video.getImportantForAccessibility());

            SessionLockOverlay.hideBehind(root, hold, false);
        }
    }

    @Test public void aViewTikTokMakesFocusableUnderThePanelIsNoStopEither() {
        // A view left on FOCUSABLE_AUTO turns focusable the moment it gets a click listener. Its
        // focus was read once, as it was curtained, so one TikTok bound after that was an empty
        // Tab stop behind the panel for as long as the panel stayed.
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            ViewGroup root = activity.findViewById(android.R.id.content);
            FrameLayout page = new FrameLayout(activity);
            FrameLayout video = new FrameLayout(activity);
            page.addView(video, new FrameLayout.LayoutParams(100, 100));
            FrameLayout bar = new FrameLayout(activity);
            android.widget.Button homeTab = new android.widget.Button(activity);
            homeTab.setFocusableInTouchMode(true);
            bar.addView(homeTab, new FrameLayout.LayoutParams(96, ViewGroup.LayoutParams.MATCH_PARENT));
            page.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 100, Gravity.BOTTOM));
            root.addView(page);
            View hold = new View(activity);
            root.addView(hold);
            layout(root, 480, 960);

            SessionLockOverlay.hideBehind(root, hold, true, bar);
            page.setOnClickListener(view -> { });
            video.setOnClickListener(view -> { });
            assertTrue("a click listener didn't make the page focusable", page.isFocusable());
            SessionLockOverlay.hideBehind(root, hold, true, bar);

            java.util.ArrayList<View> stops = new java.util.ArrayList<>();
            root.addFocusables(stops, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
            assertFalse("the page TikTok made focusable under the panel is a Tab stop",
                    stops.contains(page));
            assertFalse("the video TikTok made focusable under the panel is a Tab stop",
                    stops.contains(video));
            assertTrue("Tab lost the tabs", stops.contains(homeTab));

            SessionLockOverlay.hideBehind(root, hold, false);
            assertTrue("the focus TikTok gave the page under the panel wasn't handed back",
                    page.isFocusable());
            assertTrue("the focus TikTok gave the video under the panel wasn't handed back",
                    video.isFocusable());
        }
    }

    @Test public void aHoldLeftOnAReplacedPageIsTakenDownWithItsCurtain() throws Exception {
        // TikTok rebuilt its activity while the hold was up. The old page kept a panel whose
        // curtain nothing would lift, and coming back to it found two.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var first = Robolectric.buildActivity(HostActivity.class).setup().visible();
             var second = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            ViewGroup oldRoot = first.get().findViewById(android.R.id.content);
            FrameLayout oldFeed = new FrameLayout(first.get());
            oldRoot.addView(oldFeed);
            Utils.setActivity(first.get());
            SessionLockOverlay.sync();
            assertEquals(2, oldRoot.getChildCount());
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, oldFeed.getDescendantFocusability());

            Utils.setActivity(second.get());
            SessionLockOverlay.sync();

            assertEquals("the old page kept its panel", 1, oldRoot.getChildCount());
            assertEquals("the old page's feed is still curtained",
                    ViewGroup.FOCUS_BEFORE_DESCENDANTS, oldFeed.getDescendantFocusability());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, oldFeed.getImportantForAccessibility());
        }
    }

    @Test public void aHoldCollectedBeforeTheReaderLeftTheFeedStillHandsItBack() throws Exception {
        // TikTok took the panel off and the collector took it before any pass lifted its curtain.
        // Going off to messages lifted only through the panel, so the feed stayed out of reach.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            FeedPage page = new FeedPage(activity, root);

            SessionLockOverlay.sync();
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, page.video.getDescendantFocusability());
            collect(takeThePanelOff(root));

            page.homeTab.setSelected(false);
            SessionLockOverlay.sync();
            assertEquals("the feed stayed curtained off the feed",
                    ViewGroup.FOCUS_AFTER_DESCENDANTS, page.video.getDescendantFocusability());
            assertTrue("a reader still can't reach the feed off the feed",
                    page.like.isImportantForAccessibility());
        }
    }

    @Test public void aHoldCollectedWithNoRootToGoBackOnStillHandsTheFeedBack() throws Exception {
        // A pass that finds no content root has no panel to lift through either.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            FeedPage page = new FeedPage(activity, root);

            SessionLockOverlay.sync();
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, page.video.getDescendantFocusability());
            collect(takeThePanelOff(root));

            root.setId(View.NO_ID);
            // No Home tab to read either, so the pass takes the page for the feed and goes on to
            // attach. With the tab kept, its climb past the missing root to the smaller window
            // reads it as scrolled away, and the off-the-feed branch hands everything back instead.
            seedHomeTab(null);
            SessionLockOverlay.sync();
            assertEquals("the feed stayed curtained with no root for the panel",
                    ViewGroup.FOCUS_AFTER_DESCENDANTS, page.video.getDescendantFocusability());
            assertTrue(page.like.isImportantForAccessibility());
        }
    }

    @Test public void aHoldCollectedOverTheCatcherLeavesTheCatchersCurtainUp() throws Exception {
        // The pass that hands back what a collected hold covered must not hand back what a panel
        // still up covers too: the catcher put up on coming back to the app, under the hold.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            FeedPage page = new FeedPage(activity, root);
            View catcher = new View(activity);
            root.addView(catcher);
            SessionLockOverlay.hideBehind(root, catcher, true, page.bar);

            SessionLockOverlay.sync();
            assertEquals("the hold didn't go over the catcher",
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, catcher.getImportantForAccessibility());
            collect(takeThePanelOff(root));

            page.homeTab.setSelected(false);
            SessionLockOverlay.sync();
            assertEquals("the catcher stayed curtained by a hold that's gone",
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, catcher.getImportantForAccessibility());
            assertEquals("the catcher's curtain came off the feed with the hold's",
                    ViewGroup.FOCUS_BLOCK_DESCENDANTS, page.video.getDescendantFocusability());
            assertFalse(page.like.isImportantForAccessibility());

            SessionLockOverlay.hideBehind(root, catcher, false);
            assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, page.video.getDescendantFocusability());
            assertTrue(page.like.isImportantForAccessibility());
        }
    }

    /** The feed under a hold: a video with a like button, and the tab bar with Home selected under it. */
    private static final class FeedPage {
        final FrameLayout video;
        final android.widget.Button like;
        final FrameLayout bar;
        final android.widget.Button homeTab;

        FeedPage(Activity activity, ViewGroup root) {
            FrameLayout page = new FrameLayout(activity);
            video = new FrameLayout(activity);
            video.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            like = new android.widget.Button(activity);
            video.addView(like);
            FrameLayout.LayoutParams videoParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            videoParams.bottomMargin = 100;
            page.addView(video, videoParams);
            bar = new FrameLayout(activity);
            homeTab = new android.widget.Button(activity);
            homeTab.setSelected(true);
            bar.addView(homeTab, new FrameLayout.LayoutParams(96, ViewGroup.LayoutParams.MATCH_PARENT));
            page.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 100, Gravity.BOTTOM));
            root.addView(page);
            layout(root, 480, 960);
            seedHomeTab(homeTab);
        }
    }

    /** Takes the hold off the root as TikTok does, keeping nothing that holds on to it. */
    private static java.lang.ref.WeakReference<View> takeThePanelOff(ViewGroup root) {
        View panel = root.getChildAt(root.getChildCount() - 1);
        root.removeView(panel);
        return new java.lang.ref.WeakReference<>(panel);
    }

    /** Waits for the collector to take {@code panel}, as it does with nothing left holding it. */
    private static void collect(java.lang.ref.WeakReference<View> panel) throws InterruptedException {
        for (int attempt = 0; attempt < 20 && panel.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull("the panel TikTok took off was never collected", panel.get());
    }

    @Test public void aTabBarDrawnAfterTheHoldComesBackIntoReach() throws Exception {
        assertTabBarDrawnLateComesBack(true);
    }

    @Test public void aTabBarRightUnderTheRootDrawnAfterTheHoldComesBackIntoReach() throws Exception {
        assertTabBarDrawnLateComesBack(false);
    }

    /**
     * On a cold start the hold goes up before the tab bar has a size, and covers the whole page.
     * Once the bar is there and the panel stops above it, the tabs are handed back, whether the
     * bar sits inside the feed's page, as on the S22, or is a child of the root of its own.
     */
    private static void assertTabBarDrawnLateComesBack(boolean nested) throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            FrameLayout page = new FrameLayout(activity);
            android.widget.Button like = new android.widget.Button(activity);
            like.setFocusableInTouchMode(true);
            page.addView(like);
            FrameLayout bar = new FrameLayout(activity);
            android.widget.Button homeTab = new android.widget.Button(activity);
            homeTab.setFocusableInTouchMode(true);
            bar.addView(homeTab, new FrameLayout.LayoutParams(96, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 100, Gravity.BOTTOM);
            root.addView(page);
            if (nested) page.addView(bar, barParams);
            else root.addView(bar, barParams);
            layout(root, 480, 960);

            SessionLockOverlay.sync();
            View panel = root.getChildAt(root.getChildCount() - 1);
            java.util.ArrayList<View> stops = new java.util.ArrayList<>();
            root.addFocusables(stops, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
            assertFalse("a bar the hold covers is still a Tab stop", stops.contains(homeTab));

            seedHomeTab(homeTab);
            Method update = SessionLockOverlay.class.getDeclaredMethod("updateNavigationMargin",
                    Activity.class, ViewGroup.class, View.class);
            update.setAccessible(true);
            update.invoke(null, activity, root, panel);

            stops.clear();
            root.addFocusables(stops, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
            assertTrue("the tabs stayed out of reach once the hold stopped above them",
                    stops.contains(homeTab));
            assertFalse("handing the tabs back handed the feed back", stops.contains(like));
            assertTrue(homeTab.isImportantForAccessibility());
            assertFalse(like.isImportantForAccessibility());
        }
    }

    /** Counts every write to a label, whether or not the words changed. */
    private static AtomicInteger countWrites(android.widget.TextView view) {
        AtomicInteger writes = new AtomicInteger();
        view.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) {
                writes.incrementAndGet();
            }
        });
        return writes;
    }

    /**
     * Two minutes of a hold is two announcements, not a hundred and twenty.
     *
     * <p>The panel used to be the live region and its three labels were rewritten on every tick,
     * so a reader using TalkBack heard the whole hold read out again once a second for as long as
     * it ran. The countdown is the only thing that changes, so it is the only live region, and
     * nothing is written unless the words moved.
     */
    /**
     * The two ways out of the hold answer a press and show their focus, like every other control
     * this bundle draws. They were flat, and unreachable by a d-pad below API 26. The pixel proof
     * that the shared overlay helper changes on focus lives in OverlayControlsTest; this checks
     * the hold's own two controls carry that background and can be focused at all.
     */
    @Test public void theHoldControlsAnswerAPressAndShowTheirFocus() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SessionLockOverlay.sync();

            ViewGroup root = activity.findViewById(android.R.id.content);
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            View release = panel.getChildAt(3);
            View messages = panel.getChildAt(4);

            for (View control : new View[]{release, messages}) {
                assertTrue("a hold control cannot be reached by a keyboard or d-pad",
                        control.isFocusable());
                android.graphics.drawable.Drawable background = control.getBackground();
                assertTrue("a hold control has no ripple: " + (background == null ? "null"
                                : background.getClass().getSimpleName()),
                        background instanceof android.graphics.drawable.RippleDrawable);
            }
        }
    }

    @Test public void theHoldIsNotReadOutAgainOnEveryTick() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SessionLockOverlay.sync();

            ViewGroup root = activity.findViewById(android.R.id.content);
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            android.widget.TextView remaining = (android.widget.TextView) panel.getChildAt(1);
            android.widget.TextView hint = (android.widget.TextView) panel.getChildAt(2);
            android.widget.TextView release = (android.widget.TextView) panel.getChildAt(3);

            assertEquals("the countdown, the one thing that moves, does not announce itself",
                    View.ACCESSIBILITY_LIVE_REGION_POLITE, remaining.getAccessibilityLiveRegion());
            assertEquals("the hint announces itself, and it never changes",
                    View.ACCESSIBILITY_LIVE_REGION_NONE, hint.getAccessibilityLiveRegion());

            AtomicInteger countdown = countWrites(remaining);
            AtomicInteger hints = countWrites(hint);
            AtomicInteger wayOut = countWrites(release);

            String first = remaining.getText().toString();
            for (int second = 0; second < 120; second++) {
                now.addAndGet(1_000L);
                SessionLockOverlay.sync();
            }

            assertEquals("the hint was rewritten while nothing about it had changed",
                    0, hints.get());
            assertEquals("the way out was rewritten while nothing about it had changed",
                    0, wayOut.get());
            // Five minutes left, so the label reads 5, then 4 at a minute gone and 3 at two.
            assertEquals("the countdown was written more often than the minute it shows changed",
                    2, countdown.get());
            assertFalse("the countdown never moved, so this proves nothing",
                    first.equals(remaining.getText().toString()));
        }
    }

    @Test public void anUnlockedDayKeepsItsWayOut() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SessionLockOverlay.sync();

            ViewGroup root = activity.findViewById(android.R.id.content);
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            assertEquals("the way out went missing on a day nobody locked",
                    View.VISIBLE, panel.getChildAt(3).getVisibility());
            panel.getChildAt(3).performClick();
            assertTrue("Open the feed anyway did not open the feed", !SessionBudget.isLocked());
        }
    }

    @Test public void theWayToMessagesIsOfferedOnlyWhileThereIsAnInboxTabToOpen() throws Exception {
        // The panel says messages still work and then covers everything, so without this the
        // reader has to already know the Inbox tab is under it.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);

            // No Inbox tab, which is what a build that renamed it and a reader who hid it in
            // Feed navigation both look like: the filter drops the tab and no view is built.
            seedInboxTab(null);
            SessionLockOverlay.sync();
            ViewGroup root = activity.findViewById(android.R.id.content);
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            android.widget.TextView messages = (android.widget.TextView) panel.getChildAt(4);
            assertEquals("a way to an Inbox that is not there", View.GONE,
                    messages.getVisibility());

            // With one, it is offered, reads as a button, and a tap is the tab's own click. The
            // view has to be in the window: the cache re-resolves anything detached by name, and
            // the resource this build looks for does not exist in a test application.
            var inbox = new FrameLayout(activity);
            var taps = new java.util.concurrent.atomic.AtomicInteger();
            inbox.setOnClickListener(view -> taps.incrementAndGet());
            root.addView(inbox, 0);
            seedInboxTab(inbox);

            // Asked as the panel goes up rather than on every tick, so the answer moves when
            // the hold does. Ending this one and taking the next is what a reader who changed
            // the setting and came back to the feed does.
            SessionBudget.releaseLock();
            SessionLockOverlay.sync();
            Settings.SESSION_BUDGET_VIDEOS.save(2);
            SessionBudget.claimNotice();
            SessionBudget.noteVideo("b");
            assertTrue(SessionBudget.claimNotice());
            SessionLockOverlay.sync();

            panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            messages = (android.widget.TextView) panel.getChildAt(4);
            assertEquals(View.VISIBLE, messages.getVisibility());
            assertEquals("Open messages", messages.getText().toString());
            assertEquals("a screen reader would not hear it as a button",
                    android.widget.Button.class.getName(), roleOf(messages));

            messages.performClick();
            assertEquals("the Inbox tab was not clicked", 1, taps.get());
            assertTrue("the hold ended when the reader went to messages",
                    SessionBudget.isLocked());
        }
    }

    @Test public void theQuietReminderIsABannerThatAnnouncesItselfAndGoesOnItsOwn() throws Exception {
        // A toast takes no focus and goes on its own too, but TalkBack does not read it as a
        // live region, and it is outside the decor tree so it cannot be captured. This is the
        // banner the block button's undo already uses, without anything to press.
        Settings.SESSION_BUDGET_NOTICE_MINUTES.save(5);
        SessionBudget.noteWatching();
        for (int tick = 0; tick < 5 * 60; tick++) {
            now.addAndGet(1_000L);
            SessionBudget.noteWatching();
        }

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            var looper = Shadows.shadowOf(android.os.Looper.getMainLooper());
            int before = root.getChildCount();

            app.morphe.extension.tiktok.wellbeing.SessionBudgetNotice.showIntervalNoticeIfDue();
            looper.idle();
            assertEquals("the reminder was not drawn", before + 1, root.getChildCount());

            View banner = root.getChildAt(root.getChildCount() - 1);
            assertEquals("a screen reader would never hear it",
                    View.ACCESSIBILITY_LIVE_REGION_POLITE, banner.getAccessibilityLiveRegion());
            assertTrue("the reminder took the focus", !banner.isFocusable());

            layout(root, 480, 960);

            // Measured and laid out here exactly as the capture is about to do it, so what is
            // asserted below is what will be drawn. Asserting against the parent's earlier pass
            // proves nothing: a child that has been hidden or sized to nothing keeps its last
            // measurement until something re-measures it, so a size assertion on stale numbers
            // passes happily while the picture comes out empty.
            banner.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(96, View.MeasureSpec.EXACTLY));
            banner.layout(0, 0, 480, 96);

            // What the picture is supposed to show, asserted rather than assumed. The capture
            // used to be taken without anyone checking that the banner had a label, a size or
            // any text in it, so a blank one looked exactly like a good one.
            assertTrue("the banner has no content to draw",
                    ((ViewGroup) banner).getChildCount() > 0);
            android.widget.TextView label = (android.widget.TextView) ((ViewGroup) banner).getChildAt(0);
            assertEquals("the words are not in the picture at all",
                    View.VISIBLE, label.getVisibility());
            String shown = label.getText().toString();
            assertTrue("the reminder is not one of the three wordings: " + shown,
                    shown.equals(SessionBudgetNotice.intervalMessage(0))
                            || shown.equals(SessionBudgetNotice.intervalMessage(1))
                            || shown.equals(SessionBudgetNotice.intervalMessage(2)));
            assertTrue("the banner measured to nothing, so the capture would be empty",
                    banner.getMeasuredWidth() > 0 && banner.getMeasuredHeight() > 0);
            assertTrue("the label measured to nothing, so the words would not be in the picture",
                    label.getMeasuredWidth() > 0 && label.getMeasuredHeight() > 0);

            app.morphe.extension.tiktok.UiCapture.save(banner, "session-reminder.png", 480, 96);

            // And it takes itself away rather than waiting to be dismissed.
            looper.idleFor(java.time.Duration.ofSeconds(7));
            assertEquals("the reminder stayed on the feed", before, root.getChildCount());
        }
    }

    /**
     * The largest font and display sizes on a small phone, or TikTok in split screen: the stack
     * is taller than the room above the tab bar. The panel used to squeeze its last two controls
     * into whatever was left, so neither way off it could be reached by a finger or a reader.
     */
    @Config(qualifiers = "w360dp-h240dp")
    @Test public void aHoldTallerThanItsRoomScrollsToBothWaysOff() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            FrameLayout inbox = new FrameLayout(activity);
            root.addView(inbox, 0);
            seedInboxTab(inbox);
            SessionLockOverlay.sync();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            View title = panel.getChildAt(0);
            View release = panel.getChildAt(3);
            View messages = panel.getChildAt(4);
            assertEquals(View.VISIBLE, release.getVisibility());
            assertEquals(View.VISIBLE, messages.getVisibility());
            assertTrue("the fixture's stack fits, so it proves nothing",
                    naturalHeight(panel) > panel.getHeight());

            int touchHeight = app.morphe.extension.tiktok.settings.preference.SettingsUi.dp(activity, 48);
            assertTrue("Open the feed anyway was squeezed: " + release.getHeight(),
                    release.getHeight() >= touchHeight);
            assertTrue("Open messages was squeezed: " + messages.getHeight(),
                    messages.getHeight() >= touchHeight);
            assertTrue("the panel did not open at its title", inView(panel, title));
            assertFalse("the fixture already shows the last control", inView(panel, messages));

            // A screen reader hears a scroll container with more below, and moving onto the
            // last control brings it on screen.
            var info = android.view.accessibility.AccessibilityNodeInfo.obtain();
            panel.onInitializeAccessibilityNodeInfo(info);
            assertEquals(android.widget.ScrollView.class.getName(), String.valueOf(info.getClassName()));
            assertTrue(info.isScrollable());
            assertTrue("no scroll forward for a reader",
                    info.getActionList().contains(
                            android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD));
            assertFalse("a scrolling panel is offered to a reader as a control",
                    info.isClickable() || info.isFocusable());
            assertTrue(messages.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId(),
                    null));
            assertTrue("a reader moving onto Open messages left it off screen", inView(panel, messages));

            // Back to the top a page at a time, the way a reader's scroll gesture goes.
            int pages = 0;
            while (panel.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null)) {
                assertTrue("scrolling back never ended", ++pages < 20);
            }
            assertTrue("scrolling back did not reach the title", inView(panel, title));

            // The stack grows under the reader, the way a longer hint or label does on another
            // day: what they were reading stays where it was.
            int titleOnScreen = title.getTop() - panel.getScrollY();
            messages.getLayoutParams().height = messages.getHeight() + 40;
            messages.requestLayout();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("growing the stack moved the title", titleOnScreen, title.getTop() - panel.getScrollY());

            // A finger drags up to the buttons. Wherever the drag starts, it is a scroll and not a
            // tap on whatever was under it.
            drag(panel, panel.getWidth() / 2f, panel.getHeight() - 10f, -600f);
            assertTrue("the drag did not reach Open messages", inView(panel, messages));
            assertTrue("the drag did not reach Open the feed anyway", inView(panel, release));
            assertTrue("a drag counted as a tap on the way out", SessionBudget.isLocked());

            // A short drag that starts and ends on Open the feed anyway is a scroll too: the panel
            // takes the gesture over and the button gets a cancel. Left to the button, letting go
            // still on it would be a tap.
            float onX = (release.getLeft() + release.getRight()) / 2f;
            float onY = (release.getTop() + release.getBottom()) / 2f - panel.getScrollY();
            int distance = release.getHeight() / 2 - 2;
            assertTrue("the fixture's drag is no longer than a touch slop",
                    distance > android.view.ViewConfiguration.get(activity).getScaledTouchSlop());
            int scrolledTo = panel.getScrollY();
            drag(panel, onX, onY, onY + distance);
            assertTrue("a drag that started on Open the feed anyway didn't scroll", panel.getScrollY() < scrolledTo);
            assertTrue("a drag on Open the feed anyway tapped it", SessionBudget.isLocked());
            assertTrue("the panel doesn't say it can scroll back down", panel.canScrollVertically(1));
            pages = 0;
            while (panel.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)) {
                assertTrue("scrolling forward never ended", ++pages < 20);
            }
            assertFalse("the panel says it scrolls past its end", panel.canScrollVertically(1));

            // And the way out, scrolled into view, takes a real tap.
            float x = (release.getLeft() + release.getRight()) / 2f;
            float y = (release.getTop() + release.getBottom()) / 2f - panel.getScrollY();
            touch(panel, MotionEvent.ACTION_DOWN, x, y, 500);
            touch(panel, MotionEvent.ACTION_UP, x, y, 560);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse("a tap on the scrolled-in way out did nothing", SessionBudget.isLocked());
        } finally {
            Utils.setActivity(null);
            SessionBudget.releaseLock();
            SessionLockOverlay.sync();
        }
    }

    /** A panel that fits stays centred, and a drag on it moves nothing. */
    @Test public void aHoldThatFitsStaysCentredAndStill() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SessionLockOverlay.sync();
            ViewGroup root = activity.findViewById(android.R.id.content);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);

            assertTrue("the fixture's stack overflows", naturalHeight(panel) < panel.getHeight());
            View title = panel.getChildAt(0);
            assertTrue("the stack is not centred", title.getTop() > panel.getPaddingTop());
            drag(panel, panel.getWidth() / 2f, panel.getHeight() - 10f, 10f);
            assertEquals("a panel that fits scrolled", 0, panel.getScrollY());
            var info = android.view.accessibility.AccessibilityNodeInfo.obtain();
            panel.onInitializeAccessibilityNodeInfo(info);
            assertFalse("a panel that fits told a reader it scrolls", info.isScrollable());
            assertTrue("a drag counted as a tap", SessionBudget.isLocked());
        } finally {
            Utils.setActivity(null);
            SessionBudget.releaseLock();
            SessionLockOverlay.sync();
        }
    }

    /**
     * A mouse wheel or a touchpad over the hold. Android hands those to whatever is under the
     * pointer, and the panel swallowed touches but not them, so a wheel scrolled the feed behind it.
     */
    @Test public void aWheelOverTheHoldNeverReachesTheFeed() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            View feed = new View(activity);
            root.addView(feed, 0, new FrameLayout.LayoutParams(-1, -1));
            int[] turns = app.morphe.extension.tiktok.Wheel.count(feed);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(app.morphe.extension.tiktok.Wheel.turn(root));
            assertEquals("the fixture's wheel never reaches the feed", 1, turns[0]);

            SessionLockOverlay.sync();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            assertTrue("nothing took the wheel", app.morphe.extension.tiktok.Wheel.turn(root));
            assertEquals("a wheel over the hold scrolled the feed", 1, turns[0]);
            assertEquals("a panel that fits moved", 0, panel.getScrollY());
        } finally {
            Utils.setActivity(null);
            SessionBudget.releaseLock();
            SessionLockOverlay.sync();
        }
    }

    /** A panel with more to show takes the wheel itself, a list item's 64dp a notch, as a list does. */
    @Config(qualifiers = "w360dp-h240dp")
    @Test public void aWheelScrollsAHoldTallerThanItsRoomAndNotTheFeed() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            FrameLayout inbox = new FrameLayout(activity);
            root.addView(inbox, 0);
            seedInboxTab(inbox);
            View feed = new View(activity);
            root.addView(feed, 0, new FrameLayout.LayoutParams(-1, -1));
            int[] turns = app.morphe.extension.tiktok.Wheel.count(feed);
            SessionLockOverlay.sync();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            assertTrue("the fixture's stack fits, so it proves nothing",
                    naturalHeight(panel) > panel.getHeight());
            float x = panel.getLeft() + panel.getWidth() / 2f;
            float y = panel.getTop() + panel.getHeight() / 2f;
            int top = panel.getScrollY();
            int notch = app.morphe.extension.tiktok.settings.preference.SettingsUi.dp(activity, 64);
            assertTrue("the panel has less than a notch to scroll, so the step goes unproven",
                    naturalHeight(panel) - panel.getHeight() > notch);

            assertTrue(app.morphe.extension.tiktok.Wheel.turn(root, x, y, -1f));
            assertEquals("a notch is not a list item's height", notch, panel.getScrollY() - top);
            assertTrue(app.morphe.extension.tiktok.Wheel.turn(root, x, y, 1f));
            assertEquals("a notch back did not return to the title", top, panel.getScrollY());
            assertEquals("a wheel over the hold scrolled the feed", 0, turns[0]);
        } finally {
            Utils.setActivity(null);
            SessionBudget.releaseLock();
            SessionLockOverlay.sync();
        }
    }

    /** The height the panel's visible children ask for, measured one by one with no limit. */
    private static int naturalHeight(ViewGroup panel) {
        int width = panel.getWidth() - panel.getPaddingLeft() - panel.getPaddingRight();
        int height = panel.getPaddingTop() + panel.getPaddingBottom();
        for (int index = 0; index < panel.getChildCount(); index++) {
            View child = panel.getChildAt(index);
            if (child.getVisibility() == View.GONE) continue;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
            int before = child.getHeight();
            child.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            height += Math.max(before, child.getMeasuredHeight()) + params.topMargin + params.bottomMargin;
        }
        // Measuring the children by hand left them out of step with the panel's last layout.
        panel.requestLayout();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return height;
    }

    /** Whether the whole child is inside the panel's visible area. */
    private static boolean inView(ViewGroup panel, View child) {
        int top = child.getTop() - panel.getScrollY();
        int bottom = child.getBottom() - panel.getScrollY();
        return top >= 0 && bottom <= panel.getHeight();
    }

    private static void drag(View view, float x, float fromY, float toY) {
        touch(view, MotionEvent.ACTION_DOWN, x, fromY, 100);
        for (int step = 1; step <= 12; step++) {
            touch(view, MotionEvent.ACTION_MOVE, x, fromY + (toY - fromY) * step / 12f, 100 + step * 16L);
        }
        touch(view, MotionEvent.ACTION_UP, x, toY, 400);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private static void touch(View view, int action, float x, float y, long time) {
        MotionEvent event = MotionEvent.obtain(100, time, action, x, y, 0);
        try {
            view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    /** What TalkBack would call the view. */
    private static String roleOf(View view) {
        var info = android.view.accessibility.AccessibilityNodeInfo.obtain();
        view.onInitializeAccessibilityNodeInfo(info);
        return String.valueOf(info.getClassName());
    }

    private static void seedInboxTab(View inboxTab) {
        org.robolectric.util.ReflectionHelpers.setStaticField(
                app.morphe.extension.tiktok.blockauthor.FeedVisibility.class,
                "inboxTabReference", new java.lang.ref.WeakReference<>(inboxTab));
    }

    private static Object holdOrNull() {
        return SessionBudget.isLocked() ? Boolean.TRUE : null;
    }

    private static void seedHomeTab(View homeTab) {
        org.robolectric.util.ReflectionHelpers.setStaticField(
                app.morphe.extension.tiktok.blockauthor.FeedVisibility.class,
                "homeTabReference", new java.lang.ref.WeakReference<>(homeTab));
    }

    private static Method navigationHeight() throws Exception {
        Method method = SessionLockOverlay.class.getDeclaredMethod(
                "navigationHeight", Activity.class, ViewGroup.class);
        method.setAccessible(true);
        return method;
    }

    private static void layout(View view, int width, int height) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    @Test public void theCountdownIsReadableOnTheScrimInEitherTheme() throws Exception {
        // The panel is always the same near-black scrim, so its colours cannot follow the
        // settings theme. The settings accent is a dark crimson in the light theme, which is
        // about 3:1 on black, and the flag it reads is a cached one the settings screen sets,
        // so away from that screen it answers for the system theme instead of for this panel.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        for (boolean darkSettings : new boolean[]{true, false}) {
            Utils.setIsDarkModeEnabled(darkSettings);
            try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
                Activity activity = owner.get();
                Utils.setActivity(activity);
                SessionLockOverlay.sync();

                ViewGroup root = activity.findViewById(android.R.id.content);
                View panel = root.getChildAt(root.getChildCount() - 1);
                android.widget.TextView countdown =
                        (android.widget.TextView) ((ViewGroup) panel).getChildAt(1);
                assertEquals("the countdown followed the settings theme onto a black panel",
                        app.morphe.extension.tiktok.settings.preference.SettingsUi.OVERLAY_ACCENT,
                        countdown.getCurrentTextColor());
            }
        }
    }

    @Test public void theHoldAsksTheFeedToStopPlaying() throws Exception {
        // Focus remains held alongside the native pause. This also covers a restored hold
        // before the first native player instance has reported progress.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        android.media.AudioManager audio = (android.media.AudioManager)
                RuntimeEnvironment.getApplication().getSystemService(
                        android.content.Context.AUDIO_SERVICE);
        var shadow = org.robolectric.Shadows.shadowOf(audio);
        assertNull("something already held the focus", shadow.getLastAudioFocusRequest());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Utils.setActivity(owner.get());
            SessionLockOverlay.sync();

            var request = shadow.getLastAudioFocusRequest();
            assertNotNull("the feed kept playing behind the hold", request);
            assertEquals("a permanent grab would leave the feed silent afterwards",
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, request.durationHint);

            // Handing it back is what lets the feed play again, so a hold that ends must not
            // leave the app quiet.
            SessionBudget.releaseLock();
            SessionLockOverlay.sync();
            assertNotNull("the focus was never handed back", shadow.getLastAbandonedAudioFocusListener());
        }
    }

    @Test public void theHoldDoesNotFightWhoeverTookTheSoundAway() throws Exception {
        // A call takes the focus for good. The flag has to follow, or nothing would ever ask
        // again for the rest of the hold. What must not happen is asking again a second later:
        // the tick runs every second, and that would be taking the sound off the call.
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            SessionLockOverlay.sync();
            assertTrue("the hold never took the focus", quietened());

            android.media.AudioManager.OnAudioFocusChangeListener listener =
                    org.robolectric.util.ReflectionHelpers.getStaticField(
                            SessionLockOverlay.class, "QUIET");
            listener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS);
            assertTrue("losing the focus left the hold thinking it still had it", !quietened());

            SessionLockOverlay.sync();
            assertTrue("the hold asked a call to give the sound back a second later", !quietened());

            // The panel going up again is a thing the reader did, so that is when it is fair to
            // ask. This is the state the off-feed branch leaves the panel in.
            ViewGroup root = activity.findViewById(android.R.id.content);
            root.getChildAt(root.getChildCount() - 1).setVisibility(View.GONE);
            SessionLockOverlay.sync();
            assertTrue("coming back to the feed did not quiet it again", quietened());
        }
    }

    private static boolean quietened() {
        return org.robolectric.util.ReflectionHelpers.getStaticField(
                SessionLockOverlay.class, "quietened");
    }

    @Test public void aHoldRestoredFromDiskReattachesOnTheFirstPlayerReport() throws Exception {
        // What makes a hold survive the app being killed: the player reports progress, that asks
        // whether a hold is running, and the answer comes off the persisted record. Every other
        // case here starts from a hold already in memory, so a break anywhere along this path
        // passed the suite. The fast path in isLocked() is exactly the shape that would.
        long until = now.get() + 5L * 60_000L;
        Settings.SESSION_BUDGET_STATE.save(SessionBudget.dayOf(now.get())
                + "|3|0|" + until + "|1");
        SessionBudget.resetForTests();
        assertTrue("the record did not carry the hold back", SessionBudget.isLocked());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            ViewGroup root = activity.findViewById(android.R.id.content);
            int before = root.getChildCount();

            SessionLockOverlay.ensureRunning();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            assertTrue("a hold that survived the process being killed never came back",
                    root.getChildCount() > before);
        }
    }

    /**
     * Android 17 replaced the queue behind the main Looper. The countdown is a runnable that
     * posts itself back a second ahead, so on a queue that delivered nothing it would run once
     * and never again, with nothing failing. The tick is started here and left to the queue: a
     * minute goes by on the budget's clock with nothing calling sync, and only a delivered tick
     * can move the label.
     */
    @Test @Config(sdk = 37)
    public void onAndroidSeventeenTheTickStillComesRoundOnItsOwn() throws Exception {
        Settings.SESSION_BUDGET_VIDEOS.save(1);
        Settings.SESSION_BUDGET_LOCK_MINUTES.save(5);
        SessionBudget.noteVideo("a");
        assertTrue(SessionBudget.claimNotice());

        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            var looper = Shadows.shadowOf(android.os.Looper.getMainLooper());
            SessionLockOverlay.ensureRunning();
            looper.idle();

            ViewGroup root = activity.findViewById(android.R.id.content);
            ViewGroup panel = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
            android.widget.TextView remaining = (android.widget.TextView) panel.getChildAt(1);
            AtomicInteger countdown = countWrites(remaining);
            String first = remaining.getText().toString();

            now.addAndGet(60_000L);
            assertEquals("the countdown moved before a tick was delivered",
                    first, remaining.getText().toString());

            looper.idleFor(java.time.Duration.ofSeconds(1));
            assertEquals("the tick did not come round", 1, countdown.get());
            assertFalse("the countdown never moved, so this proves nothing",
                    first.equals(remaining.getText().toString()));
        }
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.clear();
        calendar.set(year, month, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }
}

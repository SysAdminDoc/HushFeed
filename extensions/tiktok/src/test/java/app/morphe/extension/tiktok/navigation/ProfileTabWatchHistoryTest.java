package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.atomic.AtomicInteger;

/** A long press on the Profile tab opens TikTok's Watch history, and a tap still opens Profile (#199). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProfileTabWatchHistoryTest {
    private static final int PROFILE_ID = 0x7f0a4b8c;

    private ActivityController<Activity> owner;
    private Activity activity;
    private View profile;
    private boolean destroyed;
    private final AtomicInteger taps = new AtomicInteger();

    @Before public void setUp() {
        HomeTabSettingsShortcut.resetForTests();
        ReflectionHelpers.setStaticField(SettingsStatus.class, "feedNavigationEnabled", true);
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = owner.get();
        Utils.setContext(activity);
        PausedProcess.set(false);
        FrameLayout content = new FrameLayout(activity);
        FrameLayout bar = new FrameLayout(activity);
        profile = new View(activity);
        profile.setId(PROFILE_ID);
        profile.setOnClickListener(view -> taps.incrementAndGet());
        bar.addView(profile, new FrameLayout.LayoutParams(216, 138));
        content.addView(bar, new FrameLayout.LayoutParams(-1, 138, Gravity.BOTTOM));
        activity.setContentView(content);
        FeedVisibility.resolveForTests(activity.getPackageName(), "47.6.3:onx", PROFILE_ID);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        HomeTabSettingsShortcut.resetForTests();
        FeedVisibility.resolveForTests(activity.getPackageName(), "47.6.3:onx", 0);
        ReflectionHelpers.setStaticField(SettingsStatus.class, "feedNavigationEnabled", false);
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.resetToDefault();
        if (!destroyed) owner.pause().stop().destroy();
    }

    @Test public void aLongPressOnProfileOpensWatchHistory() {
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        installAndResume();

        assertTrue("the long press was not handled", profile.performLongClick());
        Intent opened = nextStarted();
        assertNotNull("nothing was opened", opened);
        assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertEquals(ProfileTabWatchHistory.WATCH_HISTORY_URI, String.valueOf(opened.getData()));
        assertTrue(opened.getDataString().contains("pns_product_poseidon%2Fwatch_history"));
        assertEquals("the page could open in another TikTok", activity.getPackageName(), opened.getPackage());
        assertEquals("a long press also counted as a tap", 0, taps.get());
        assertTrue("a tap on Profile stopped reaching TikTok", profile.performClick());
        assertEquals(1, taps.get());
        assertNull("a tap opened Watch history", nextStarted());
    }

    @Test public void offByDefaultTheTabIsLeftAsItWas() {
        installAndResume();

        assertFalse(profile.isLongClickable());
        assertFalse(profile.performLongClick());
        assertNull(nextStarted());
    }

    @Test public void tikToksOwnLongPressIsTakenWhileOnAndGivenBackWhenOff() {
        AtomicInteger own = new AtomicInteger();
        View.OnLongClickListener switcher = view -> {
            own.incrementAndGet();
            return true;
        };
        profile.setOnLongClickListener(switcher);
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        installAndResume();

        assertTrue(profile.performLongClick());
        assertNotNull(nextStarted());
        assertEquals("TikTok's long press also ran", 0, own.get());

        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(false);
        layoutPass();
        assertTrue(profile.isLongClickable());
        assertTrue(profile.performLongClick());
        assertEquals("switched off, TikTok's own long press didn't come back", 1, own.get());
        assertNull(nextStarted());
    }

    @Test public void aLongPressTikTokSetsAgainIsTakenBackAndKept() {
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        installAndResume();
        AtomicInteger own = new AtomicInteger();
        profile.setOnLongClickListener(view -> {
            own.incrementAndGet();
            return true;
        });
        layoutPass();

        assertTrue(profile.performLongClick());
        assertNotNull("TikTok's later listener kept the press", nextStarted());
        assertEquals(0, own.get());

        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(false);
        layoutPass();
        // performLongClick runs a listener on a view that isn't long-clickable; a finger doesn't.
        assertTrue("TikTok's later long press came back dead", profile.isLongClickable());
        assertTrue(profile.performLongClick());
        assertEquals("TikTok's later listener was lost", 1, own.get());
    }

    @Test public void aLongPressTikTokClearsWhileOnStaysGoneWhenOff() {
        AtomicInteger own = new AtomicInteger();
        profile.setOnLongClickListener(view -> {
            own.incrementAndGet();
            return true;
        });
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        installAndResume();
        profile.setOnLongClickListener(null);
        layoutPass();

        assertTrue("TikTok clearing its listener took ours too", profile.performLongClick());
        assertNotNull(nextStarted());

        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(false);
        layoutPass();
        assertFalse("a long press TikTok removed came back", profile.isLongClickable());
        assertFalse(profile.performLongClick());
        assertEquals(0, own.get());
    }

    @Test public void tikToksLongPressIsGivenBackWhenTheActivityCloses() {
        AtomicInteger own = new AtomicInteger();
        profile.setOnLongClickListener(view -> {
            own.incrementAndGet();
            return true;
        });
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        installAndResume();

        owner.pause().stop().destroy();
        destroyed = true;
        assertTrue(profile.isLongClickable());
        assertTrue(profile.performLongClick());
        assertEquals("the closed activity's tab kept Watch history", 1, own.get());
        assertNull(nextStarted());
    }

    @Test public void switchedOffAfterwardsTheTabHasNoLongPress() {
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        installAndResume();
        assertTrue(profile.isLongClickable());

        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(false);
        layoutPass();
        assertFalse("switched off, the tab kept a long press", profile.isLongClickable());
        assertFalse(profile.performLongClick());
        assertNull(nextStarted());
    }

    @Test public void aRebuiltTabGetsTheLongPressAndTheOldOneLetsItGo() {
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        installAndResume();
        FrameLayout bar = (FrameLayout) profile.getParent();
        View rebuilt = new View(activity);
        rebuilt.setId(PROFILE_ID);
        rebuilt.setOnClickListener(view -> taps.incrementAndGet());
        bar.removeView(profile);
        bar.addView(rebuilt, new FrameLayout.LayoutParams(216, 138));
        layoutPass();

        assertTrue(rebuilt.performLongClick());
        assertNotNull(nextStarted());
        assertFalse("the old tab kept the long press", profile.isLongClickable());
    }

    @Test public void pausedTheTabIsLeftToTikTok() {
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        PausedProcess.set(true);
        installAndResume();

        assertFalse(profile.isLongClickable());
        assertNull(nextStarted());
    }

    @Test public void aProfileViewThatDoesNotTakeTheTapIsLeftAlone() {
        profile.setOnClickListener(null);
        profile.setClickable(false);
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        installAndResume();

        assertFalse(profile.isLongClickable());
        assertFalse(profile.performLongClick());
        assertNull(nextStarted());
    }

    private void installAndResume() {
        HomeTabSettingsShortcut.install(activity);
        owner.pause().resume();
    }

    private void layoutPass() {
        activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
    }

    private static Intent nextStarted() {
        return Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
    }
}

package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.preference.PreferenceScreen;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.FeedFilterPreferenceCategory;

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
import org.robolectric.shadows.ShadowAlertDialog;
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
        FeedVisibility.resolveForTests(activity.getPackageName(), "47.7.3:oph", PROFILE_ID);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        HomeTabSettingsShortcut.resetForTests();
        FeedVisibility.resolveForTests(activity.getPackageName(), "47.7.3:oph", 0);
        ReflectionHelpers.setStaticField(SettingsStatus.class, "feedNavigationEnabled", false);
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.resetToDefault();
        Settings.LOCAL_WATCH_HISTORY.resetToDefault();
        Settings.PROFILE_HOLD_OPENS_LOCAL_WATCH_HISTORY.resetToDefault();
        AlertDialog shown = ShadowAlertDialog.getLatestAlertDialog();
        if (shown != null) shown.dismiss();
        if (!destroyed) owner.pause().stop().destroy();
    }

    private static void localHistoryOnHold() {
        Settings.LOCAL_WATCH_HISTORY.save(true);
        Settings.PROFILE_HOLD_OPENS_LOCAL_WATCH_HISTORY.save(true);
    }

    /** #172: holding Profile shows the watch history kept on the phone, so For You can stay on settings. */
    @Test public void withTheLocalSwitchALongPressShowsTheListKeptOnThePhone() {
        localHistoryOnHold();
        installAndResume();

        assertTrue("the long press was not handled", profile.performLongClick());
        assertNull("TikTok's Watch history opened instead of the list", nextStarted());
        AlertDialog history = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("the long press showed no watch history", history);
        assertTrue(history.isShowing());
        assertEquals("My watch history", String.valueOf(Shadows.shadowOf(history).getTitle()));
        assertEquals("a long press also counted as a tap", 0, taps.get());
    }

    /** It takes TikTok's account-switcher hold too, and wins over the switch for TikTok's page. */
    @Test public void theLocalSwitchTakesTikToksSwitcherPressAndWinsOverTikToksPage() {
        AtomicInteger sheets = new AtomicInteger();
        profile.setOnLongClickListener(switcher(sheets));
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        localHistoryOnHold();
        installAndResume();

        assertTrue(profile.performLongClick());
        assertEquals("the account sheet showed too", 0, sheets.get());
        assertNull("TikTok's Watch history opened over the list", nextStarted());
        AlertDialog history = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("TikTok's Profile hold didn't show the list", history);
        assertTrue(history.isShowing());
    }

    /** With no history kept there's nothing to show, so the tab stays as it was. */
    @Test public void theLocalSwitchNeedsTheHistoryKept() {
        Settings.PROFILE_HOLD_OPENS_LOCAL_WATCH_HISTORY.save(true);
        installAndResume();

        assertFalse(profile.isLongClickable());
        assertFalse(profile.performLongClick());
        assertFalse(ProfileTabWatchHistory.takesSwitcherPress(ProfileTabWatchHistory.PROFILE_TAG));
        assertNull(nextStarted());
        assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }

    /** The hold is the feed tab patch's, so a bundle without it doesn't offer a switch that does nothing. */
    @Test public void theLocalSwitchIsOfferedOnlyWithTheFeedTabPatch() {
        boolean oldSeen = SettingsStatus.seenVideoFilterEnabled;
        boolean oldBlock = SettingsStatus.blockAuthorEnabled;
        SettingsStatus.seenVideoFilterEnabled = true;
        SettingsStatus.blockAuthorEnabled = true;
        try (var host = Robolectric.buildActivity(
                app.morphe.extension.tiktok.interaction.GestureActionsTest.TestActivity.class).setup()) {
            PreferenceScreen with = host.get().getPreferenceManager().createPreferenceScreen(host.get());
            new FeedFilterPreferenceCategory(host.get(), with);
            assertNotNull(with.findPreference(Settings.PROFILE_HOLD_OPENS_LOCAL_WATCH_HISTORY.key));

            ReflectionHelpers.setStaticField(SettingsStatus.class, "feedNavigationEnabled", false);
            PreferenceScreen without = host.get().getPreferenceManager().createPreferenceScreen(host.get());
            new FeedFilterPreferenceCategory(host.get(), without);
            assertNull(without.findPreference(Settings.PROFILE_HOLD_OPENS_LOCAL_WATCH_HISTORY.key));
            assertNotNull("For You's hold is in every bundle",
                    without.findPreference(Settings.FOR_YOU_HOLD_OPENS_WATCH_HISTORY.key));
        } finally {
            SettingsStatus.seenVideoFilterEnabled = oldSeen;
            SettingsStatus.blockAuthorEnabled = oldBlock;
        }
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

    @Test public void tikToksSwitcherStaysOnTheTabAndOpensWatchHistoryWhileOn() {
        AtomicInteger sheets = new AtomicInteger();
        profile.setOnLongClickListener(switcher(sheets));
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        installAndResume();

        assertTrue(profile.performLongClick());
        Intent opened = nextStarted();
        assertNotNull("TikTok's Profile hold didn't open Watch history", opened);
        assertEquals(ProfileTabWatchHistory.WATCH_HISTORY_URI, String.valueOf(opened.getData()));
        assertEquals("the account sheet showed too", 0, sheets.get());
        assertFalse("another tab's hold opened Watch history", ProfileTabWatchHistory.takesSwitcherPress("HOME"));
        assertFalse(ProfileTabWatchHistory.takesSwitcherPress(null));
        assertNull(nextStarted());

        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(false);
        layoutPass();
        assertTrue(profile.isLongClickable());
        assertTrue(profile.performLongClick());
        assertEquals("switched off, TikTok's account sheet didn't come back", 1, sheets.get());
        assertNull(nextStarted());
    }

    /** #341: the switch going off left the tab with no long press where its listener couldn't be read. */
    @Test public void tikToksSwitcherSurvivesTheSwitchGoingOffWhereTheTabCantBeRead() {
        ReflectionHelpers.setStaticField(ProfileTabWatchHistory.class, "listenerUnreadable", true);
        AtomicInteger sheets = new AtomicInteger();
        profile.setOnLongClickListener(switcher(sheets));
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        installAndResume();
        assertTrue(profile.performLongClick());
        assertNotNull(nextStarted());

        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(false);
        layoutPass();
        assertTrue("TikTok's Profile hold came back dead", profile.isLongClickable());
        assertTrue(profile.performLongClick());
        assertEquals("TikTok's account sheet was lost", 1, sheets.get());
        assertNull(nextStarted());
    }

    @Test public void aLongPressLeftOnAnUnreadableTabDeclinesWhileOff() {
        ReflectionHelpers.setStaticField(ProfileTabWatchHistory.class, "listenerUnreadable", true);
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        installAndResume();
        assertTrue(profile.performLongClick());
        assertNotNull(nextStarted());

        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(false);
        layoutPass();
        // Not read back as Hushfeed's, so it stays, and a declined press ends as a tap.
        assertFalse("switched off, the hold still opened Watch history", profile.performLongClick());
        assertNull(nextStarted());
        assertTrue(profile.performClick());
        assertEquals(1, taps.get());
    }

    @Test public void aLongPressTikTokSetsLaterIsLeftOnTheTab() {
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        installAndResume();
        AtomicInteger sheets = new AtomicInteger();
        profile.setOnLongClickListener(switcher(sheets));
        layoutPass();

        assertTrue(profile.performLongClick());
        assertNotNull("TikTok's later switcher didn't ask first", nextStarted());
        assertEquals(0, sheets.get());

        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(false);
        layoutPass();
        // performLongClick runs a listener on a view that isn't long-clickable; a finger doesn't.
        assertTrue("TikTok's later long press came back dead", profile.isLongClickable());
        assertTrue(profile.performLongClick());
        assertEquals("TikTok's later listener was lost", 1, sheets.get());
    }

    @Test public void theTabGetsOursOnceTikTokTakesItsSwitcherOff() {
        AtomicInteger sheets = new AtomicInteger();
        profile.setOnLongClickListener(switcher(sheets));
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        installAndResume();
        // The second account is gone: TikTok drops its listener, and Android keeps the tab long-clickable.
        profile.setOnLongClickListener(null);
        layoutPass();

        assertTrue("the Profile hold stayed dead after TikTok's switcher went", profile.performLongClick());
        assertNotNull(nextStarted());
        assertEquals(0, sheets.get());
    }

    @Test public void aTabRebuiltWithoutTikToksLongPressGetsOurs() {
        AtomicInteger sheets = new AtomicInteger();
        profile.setOnLongClickListener(switcher(sheets));
        Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.save(true);
        installAndResume();
        FrameLayout bar = (FrameLayout) profile.getParent();
        View rebuilt = new View(activity);
        rebuilt.setId(PROFILE_ID);
        rebuilt.setOnClickListener(view -> taps.incrementAndGet());
        bar.removeView(profile);
        bar.addView(rebuilt, new FrameLayout.LayoutParams(216, 138));
        layoutPass();

        assertTrue(rebuilt.isLongClickable());
        assertTrue(rebuilt.performLongClick());
        assertNotNull(nextStarted());
        assertEquals(0, sheets.get());
    }

    @Test public void tikToksLongPressIsUntouchedWhenTheActivityCloses() {
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
        assertFalse("paused, TikTok's account switcher was skipped",
                ProfileTabWatchHistory.takesSwitcherPress(ProfileTabWatchHistory.PROFILE_TAG));
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

    /** TikTok's account-switcher long press: it posts the tab tag, and its handler asks Hushfeed first. */
    private static View.OnLongClickListener switcher(AtomicInteger sheets) {
        return view -> {
            if (!ProfileTabWatchHistory.takesSwitcherPress(ProfileTabWatchHistory.PROFILE_TAG)) {
                sheets.incrementAndGet();
            }
            return true;
        };
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

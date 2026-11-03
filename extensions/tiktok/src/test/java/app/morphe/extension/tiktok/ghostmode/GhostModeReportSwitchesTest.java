/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.preference.Preference;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Story views, profile visits and typing each have a switch under Ghost mode (Bahand's email,
 * point 4). A child switch turned off lets only its own report through, and the defaults keep
 * every report held back with Ghost mode on, as it was before the switches existed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class GhostModeReportSwitchesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final List<BooleanSetting> CHILDREN = List.of(
            Settings.GHOST_HIDE_STORY_VIEWS, Settings.GHOST_HIDE_PROFILE_VISITS, Settings.GHOST_HIDE_TYPING);
    private boolean installed;
    private boolean telemetryInstalled;

    @Before public void setUp() {
        installed = SettingsStatus.ghostModeEnabled;
        telemetryInstalled = SettingsStatus.disableTelemetryEnabled;
        resetSwitches();
        HookStatus.clear();
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        SettingsStatus.ghostModeEnabled = installed;
        SettingsStatus.disableTelemetryEnabled = telemetryInstalled;
        resetSwitches();
        HookStatus.clear();
        Utils.setActivity(null);
    }

    @Test public void everyChildIsOnByDefaultSoGhostModeStillHoldsBackAllThree() {
        for (BooleanSetting child : CHILDREN) {
            assertTrue(child.key + " must default on", child.defaultValue);
            assertTrue(child.key, child.savedValue());
        }
        Settings.GHOST_MODE.save(true);
        assertTrue(GhostMode.shouldBlockStoryView());
        assertTrue(GhostMode.shouldBlockStoryStats(story()));
        assertTrue(GhostMode.shouldBlockProfileView());
        assertTrue(GhostMode.shouldBlockTypingStatus());
    }

    @Test public void everyChildHangsOffGhostMode() {
        for (BooleanSetting child : CHILDREN) {
            assertEquals(child.key, List.of(Settings.GHOST_MODE), child.getParentSettings());
            assertFalse(child.key + " is offered with Ghost mode off", child.isAvailable());
        }
        Settings.GHOST_MODE.save(true);
        for (BooleanSetting child : CHILDREN) assertTrue(child.key, child.isAvailable());
    }

    @Test public void onlineStatusFollowsOnlyItsOwnSwitch() {
        Settings.GHOST_MODE.save(true);
        for (BooleanSetting child : CHILDREN) child.save(false);
        Settings.GHOST_HIDE_ONLINE_STATUS.save(true);
        assertTrue(GhostMode.shouldBlockOnlineStatus());
        for (BooleanSetting child : CHILDREN) child.save(true);
        Settings.GHOST_HIDE_ONLINE_STATUS.save(false);
        assertFalse(GhostMode.shouldBlockOnlineStatus());
    }

    @Test public void storyViewsOffSendsBothStoryReportsAndNothingElse() {
        Settings.GHOST_MODE.save(true);
        Settings.GHOST_HIDE_STORY_VIEWS.save(false);
        assertFalse(GhostMode.shouldBlockStoryView());
        assertFalse("the play report alone puts you on the viewer list", GhostMode.shouldBlockStoryStats(story()));
        assertTrue(GhostMode.shouldBlockProfileView());
        assertTrue(GhostMode.shouldBlockTypingStatus());
    }

    @Test public void profileVisitsOffSendsOnlyTheProfileView() {
        Settings.GHOST_MODE.save(true);
        Settings.GHOST_HIDE_PROFILE_VISITS.save(false);
        assertFalse(GhostMode.shouldBlockProfileView());
        assertTrue(GhostMode.shouldBlockStoryView());
        assertTrue(GhostMode.shouldBlockStoryStats(story()));
        assertTrue(GhostMode.shouldBlockTypingStatus());
    }

    @Test public void typingOffSendsOnlyTheTypingStatus() {
        Settings.GHOST_MODE.save(true);
        Settings.GHOST_HIDE_TYPING.save(false);
        assertFalse(GhostMode.shouldBlockTypingStatus());
        assertTrue(GhostMode.shouldBlockStoryView());
        assertTrue(GhostMode.shouldBlockStoryStats(story()));
        assertTrue(GhostMode.shouldBlockProfileView());
    }

    @Test public void theChildrenDoNothingWhileGhostModeIsOff() {
        assertFalse(GhostMode.shouldBlockStoryView());
        assertFalse(GhostMode.shouldBlockStoryStats(story()));
        assertFalse(GhostMode.shouldBlockProfileView());
        assertFalse(GhostMode.shouldBlockTypingStatus());
    }

    @Test public void aFeedVideosPlayReportStillGoesWhateverTheStorySwitchSays() {
        Settings.GHOST_MODE.save(true);
        assertFalse(GhostMode.shouldBlockStoryStats(feedVideo()));
    }

    @Test public void aPausedProcessSendsEveryReport() {
        Settings.GHOST_MODE.save(true);
        PausedProcess.set(true);
        assertFalse(GhostMode.shouldBlockStoryView());
        assertFalse(GhostMode.shouldBlockProfileView());
        assertFalse(GhostMode.shouldBlockTypingStatus());
        PausedProcess.set(false);
        assertTrue(GhostMode.shouldBlockStoryView());
    }

    @Test public void theRowsSitUnderGhostModeWhenItIsInstalled() {
        SettingsStatus.ghostModeEnabled = true;
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            TikTokPreferenceFragment page = privacy(owner.get());
            Preference ghostMode = page.findPreference(Settings.GHOST_MODE.key);
            assertNotNull(ghostMode);
            int previous = ghostMode.getOrder();
            List<String> titles = List.of("Hide story views", "Hide profile visits", "Hide typing");
            for (int i = 0; i < CHILDREN.size(); i++) {
                Preference row = page.findPreference(CHILDREN.get(i).key);
                assertNotNull("no row for " + CHILDREN.get(i).key, row);
                assertEquals(titles.get(i), row.getTitle().toString());
                assertTrue(row.getKey() + " sits after the row before it", row.getOrder() > previous);
                previous = row.getOrder();
            }
            assertTrue("Hide online status comes after the three",
                    page.findPreference(Settings.GHOST_HIDE_ONLINE_STATUS.key).getOrder() > previous);
        }
    }

    @Test public void theRowsAreAbsentWhenGhostModeIsNotInTheBuild() {
        SettingsStatus.ghostModeEnabled = false;
        SettingsStatus.disableTelemetryEnabled = true;
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            TikTokPreferenceFragment page = privacy(owner.get());
            for (BooleanSetting child : CHILDREN) assertNull(child.key, page.findPreference(child.key));
        }
    }

    private static void resetSwitches() {
        Settings.GHOST_MODE.save(false);
        Settings.GHOST_HIDE_ONLINE_STATUS.save(false);
        for (BooleanSetting child : CHILDREN) child.save(child.defaultValue);
    }

    private static Aweme story() {
        return new Aweme() {
            @Override public boolean getIsTikTokStory() { return true; }
            @Override public int getAwemeType() { return 40; }
        };
    }

    private static Aweme feedVideo() {
        return new Aweme() {
            @Override public boolean getIsTikTokStory() { return false; }
            @Override public int getAwemeType() { return 0; }
        };
    }

    private static TikTokPreferenceFragment privacy(Activity activity) {
        Utils.setContext(activity);
        Utils.setActivity(activity);
        TikTokPreferenceFragment page = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "PRIVACY");
        page.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, page).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return page;
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.FeedFilterPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import java.util.function.BiConsumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #48: TikTok Lite's Feed filter only filters the feed's items, so Lite's pages leave out the
 * rows for what only TikTok's Feed filter hooks: search, the Friends tab, Following's order,
 * offline videos, the filtered count, and what's drawn around a video. The item rules stay.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiteSettingsRowsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    private static final String SEARCH_TAB_CHECKLIST = "search_tab_checklist";

    private boolean feedFilter;
    private boolean full;

    @Before
    public void setUp() {
        feedFilter = SettingsStatus.feedFilterEnabled;
        full = SettingsStatus.fullFeedFilterEnabled;
    }

    @After
    public void tearDown() {
        SettingsStatus.feedFilterEnabled = feedFilter;
        SettingsStatus.fullFeedFilterEnabled = full;
    }

    private PreferenceScreen page(boolean fullFilter, BiConsumer<PreferenceActivity, PreferenceScreen> build) {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.feedFilterEnabled = true;
            SettingsStatus.fullFeedFilterEnabled = fullFilter;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            build.accept(activity, screen);
            return screen;
        }
    }

    private PreferenceScreen feedScreen(boolean fullFilter) {
        return page(fullFilter, InterfacePreferenceCategory::new);
    }

    private PreferenceScreen feedFilterPage(boolean fullFilter) {
        return page(fullFilter, FeedFilterPreferenceCategory::new);
    }

    @Test
    public void litesFeedScreenLeavesOutTheRowsItsFeedFilterNeverHooks() {
        PreferenceScreen lite = feedScreen(false);
        assertNull(lite.findPreference(Settings.HIDE_BOTTOM_SEARCH_BAR.key));
        assertNull(lite.findPreference(Settings.HIDE_PLAYLIST_BAR.key));
        assertNull(lite.findPreference(Settings.HIDE_EVENT_BADGE.key));
        assertNotNull("an item filter, which Lite's feed runs", lite.findPreference(Settings.HIDE_INSERTED_CARDS.key));
    }

    @Test
    public void tiktoksFeedScreenKeepsAllFour() {
        PreferenceScreen tiktok = feedScreen(true);
        assertNotNull(tiktok.findPreference(Settings.HIDE_BOTTOM_SEARCH_BAR.key));
        assertNotNull(tiktok.findPreference(Settings.HIDE_PLAYLIST_BAR.key));
        assertNotNull(tiktok.findPreference(Settings.HIDE_EVENT_BADGE.key));
        assertNotNull(tiktok.findPreference(Settings.HIDE_INSERTED_CARDS.key));
    }

    @Test
    public void litesFeedFilterPageKeepsOnlyWhatItsItemFilterRuns() {
        PreferenceScreen lite = feedFilterPage(false);
        assertNull(lite.findPreference(Settings.HIDE_SEARCH_SHOP.key));
        assertNull(lite.findPreference(SEARCH_TAB_CHECKLIST));
        assertNull(lite.findPreference(Settings.FRIENDS_MUTUALS_ONLY.key));
        assertNull(lite.findPreference(Settings.FOLLOWING_LATEST_FIRST.key));
        assertNull(lite.findPreference(Settings.HIDE_OFFLINE_VIDEOS.key));
        assertNull(lite.findPreference(Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.key));
        assertNull(lite.findPreference(Settings.FILTERED_COUNT_PILL.key));
        assertNotNull(lite.findPreference(Settings.HIDE_SHOP.key));
        assertNotNull(lite.findPreference(Settings.HIDE_LIVE.key));
        assertNotNull(lite.findPreference(Settings.HIDE_STORY.key));
        assertNotNull(lite.findPreference(Settings.REMOVE_ADS.key));
        assertNotNull(lite.findPreference(Settings.BLOCKED_CAPTION_WORDS.key));
    }

    @Test
    public void tiktoksFeedFilterPageKeepsThemAll() {
        PreferenceScreen tiktok = feedFilterPage(true);
        assertNotNull(tiktok.findPreference(Settings.HIDE_SEARCH_SHOP.key));
        assertNotNull(tiktok.findPreference(SEARCH_TAB_CHECKLIST));
        assertNotNull(tiktok.findPreference(Settings.FRIENDS_MUTUALS_ONLY.key));
        assertNotNull(tiktok.findPreference(Settings.FOLLOWING_LATEST_FIRST.key));
        assertNotNull(tiktok.findPreference(Settings.HIDE_OFFLINE_VIDEOS.key));
        assertNotNull(tiktok.findPreference(Settings.FILTER_OFFLINE_FALLBACK_VIDEOS.key));
        assertNotNull(tiktok.findPreference(Settings.FILTERED_COUNT_PILL.key));
    }
}

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
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #48: TikTok Lite's Feed filter only filters the feed's items, so Lite's Feed screen page
 * leaves out the rows for what TikTok draws around a video. Hide inserted cards is an item
 * filter and stays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiteFeedScreenRowsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    private boolean feedFilter;
    private boolean views;

    @Before
    public void setUp() {
        feedFilter = SettingsStatus.feedFilterEnabled;
        views = SettingsStatus.feedFilterViewsEnabled;
    }

    @After
    public void tearDown() {
        SettingsStatus.feedFilterEnabled = feedFilter;
        SettingsStatus.feedFilterViewsEnabled = views;
    }

    private PreferenceScreen page(boolean viewHooks) {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.feedFilterEnabled = true;
            SettingsStatus.feedFilterViewsEnabled = viewHooks;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, screen);
            return screen;
        }
    }

    @Test
    public void litesPageLeavesOutTheRowsItsFeedFilterNeverHooks() {
        PreferenceScreen lite = page(false);
        assertNull(lite.findPreference(Settings.HIDE_BOTTOM_SEARCH_BAR.key));
        assertNull(lite.findPreference(Settings.HIDE_PLAYLIST_BAR.key));
        assertNull(lite.findPreference(Settings.HIDE_EVENT_BADGE.key));
        assertNotNull("an item filter, which Lite's feed runs", lite.findPreference(Settings.HIDE_INSERTED_CARDS.key));
    }

    @Test
    public void tiktoksPageKeepsAllFour() {
        PreferenceScreen tiktok = page(true);
        assertNotNull(tiktok.findPreference(Settings.HIDE_BOTTOM_SEARCH_BAR.key));
        assertNotNull(tiktok.findPreference(Settings.HIDE_PLAYLIST_BAR.key));
        assertNotNull(tiktok.findPreference(Settings.HIDE_EVENT_BADGE.key));
        assertNotNull(tiktok.findPreference(Settings.HIDE_INSERTED_CARDS.key));
    }
}

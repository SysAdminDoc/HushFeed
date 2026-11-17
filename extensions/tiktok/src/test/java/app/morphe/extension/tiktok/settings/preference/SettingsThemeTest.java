/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Settings theme holds Hushfeed's own screens dark or light whatever mode TikTok is in (#434):
 * the reporter runs TikTok light and wanted these pages dark. Match TikTok keeps the old way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w480dp-h960dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class SettingsThemeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static class PageActivity extends Activity {
        int recreated;

        @Override public void onCreate(Bundle state) {
            setTheme(android.R.style.Theme_Material_Light_NoActionBar);
            super.onCreate(state);
        }

        @Override public void recreate() {
            recreated++;
        }
    }

    @After public void tearDown() {
        Settings.SETTINGS_THEME.resetToDefault();
        Utils.setIsDarkModeEnabled(false);
    }

    @Test public void theChoiceWinsOverTikToksModeAndMatchTikTokFollowsIt() {
        assertEquals("tiktok", Settings.SETTINGS_THEME.defaultValue);
        assertFalse(Settings.SETTINGS_THEME.rebootApp);

        Context light = RuntimeEnvironment.getApplication();
        Configuration nightConfig = new Configuration(light.getResources().getConfiguration());
        nightConfig.uiMode = Configuration.UI_MODE_NIGHT_YES
                | (nightConfig.uiMode & ~Configuration.UI_MODE_NIGHT_MASK);
        Context night = light.createConfigurationContext(nightConfig);

        SettingsUi.syncDarkMode(light);
        assertFalse("Match TikTok in a light TikTok", SettingsUi.isDarkMode());
        SettingsUi.syncDarkMode(night);
        assertTrue("Match TikTok in a dark TikTok", SettingsUi.isDarkMode());

        Settings.SETTINGS_THEME.save("dark");
        SettingsUi.syncDarkMode(light);
        assertTrue("Always dark in a light TikTok", SettingsUi.isDarkMode());
        assertEquals(Color.BLACK, SettingsUi.background());

        Settings.SETTINGS_THEME.save("light");
        SettingsUi.syncDarkMode(night);
        assertFalse("Always light in a dark TikTok", SettingsUi.isDarkMode());
        assertEquals(SettingsUi.LIGHT_BACKGROUND, SettingsUi.background());

        // A value no build wrote, from a hand-edited backup, falls back to TikTok's mode.
        assertTrue(SettingsUi.darkFor("sepia", true));
        assertFalse(SettingsUi.darkFor("sepia", false));
    }

    @Test public void theHomePageOffersTheChoiceAndRepaintsOnAPick() {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            PageActivity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = show(activity);
            assertEquals(SettingsUi.LIGHT_BACKGROUND, background(home));

            Preference row = home.findPreference(Settings.SETTINGS_THEME.key);
            assertNotNull("the home page has no Settings theme row", row);
            ChoicePreference theme = (ChoicePreference) row;
            assertEquals("Settings theme", String.valueOf(theme.getTitle()));
            assertArrayEquals(new CharSequence[]{"tiktok", "dark", "light"}, theme.getEntryValues());
            assertArrayEquals(new CharSequence[]{"Match TikTok", "Always dark", "Always light"},
                    theme.getEntries());
            assertNotNull("the row has no icon beside Pause Hushfeed's", theme.getIcon());

            assertTrue(theme.getOnPreferenceChangeListener().onPreferenceChange(theme, "dark"));
            assertEquals("dark", Settings.SETTINGS_THEME.get());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("the pick didn't rebuild the page to repaint it", 1, activity.recreated);

            // The rebuilt page, still in a light TikTok, comes up black.
            TikTokPreferenceFragment again = show(activity);
            assertEquals(Color.BLACK, background(again));
        }
    }

    private static TikTokPreferenceFragment show(Activity activity) {
        TikTokPreferenceFragment page = new TikTokPreferenceFragment();
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, page).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return page;
    }

    private static int background(TikTokPreferenceFragment page) {
        return ((ColorDrawable) page.getView().getBackground()).getColor();
    }
}

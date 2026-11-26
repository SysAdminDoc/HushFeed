/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlyApplication;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;

/**
 * #543: TikTok Lite reads its saved dark mode through {@link LiteDarkMode#mode}. With the switch
 * off it gets what it saved, whatever that was. With it on it gets dark, from the first read on,
 * including one made before the settings context is up. A paused start leaves Lite as it was.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class LiteDarkModeTest {
    /** What Lite saves: -1 follows the phone, 1 light (its default), 2 dark. */
    private static final int[] SAVED = {-1, 1, 2};

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        LiteDarkMode.resetForTests();
    }

    @After public void tearDown() throws Exception {
        Utils.setContext(context);
        Settings.LITE_DARK_MODE.save(false);
        saveInTheFile(false);
        EarlyApplication.reset();
        pause(HushfeedPause.Reason.NONE);
        LiteDarkMode.resetForTests();
    }

    private void saveInTheFile(boolean value) {
        context.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(LiteDarkMode.SWITCH_KEY, value).commit();
    }

    private static void pause(HushfeedPause.Reason why) throws Exception {
        Method pause = HushfeedPause.class.getDeclaredMethod("pauseForTests", HushfeedPause.Reason.class);
        pause.setAccessible(true);
        pause.invoke(null, why);
    }

    @Test public void theSwitchStartsOffAndKeyedForTheEarlyRead() {
        assertEquals(Boolean.FALSE, Settings.LITE_DARK_MODE.defaultValue);
        assertEquals(LiteDarkMode.SWITCH_KEY, Settings.LITE_DARK_MODE.key);
    }

    @Test public void offLeavesWhateverLiteSaved() {
        for (int saved : SAVED) assertEquals(saved, LiteDarkMode.mode(saved));
    }

    @Test public void onEveryReadIsDark() {
        Settings.LITE_DARK_MODE.save(true);
        for (int saved : SAVED) assertEquals("saved " + saved, LiteDarkMode.DARK, LiteDarkMode.mode(saved));
    }

    @Test public void aReadBeforeTheSettingsContextTakesTheSavedSwitch() {
        Settings.LITE_DARK_MODE.save(true);
        saveInTheFile(true);
        EarlyApplication.set(context);
        Utils.setContext(null);
        assertEquals(LiteDarkMode.DARK, LiteDarkMode.mode(1));
        saveInTheFile(false);
        assertEquals(1, LiteDarkMode.mode(1));
    }

    @Test public void aPausedStartKeepsLitesOwnMode() throws Exception {
        Settings.LITE_DARK_MODE.save(true);
        pause(HushfeedPause.Reason.SWITCH);
        for (int saved : SAVED) assertEquals(saved, LiteDarkMode.mode(saved));
    }

    /** The row is on App > Appearance only in a build the Lite patch went into. */
    @Test public void theAppPageShowsTheSwitchOnlyWhenThePatchIsIn() {
        try (var owner = Robolectric.buildActivity(
                app.morphe.extension.tiktok.interaction.GestureActionsTest.TestActivity.class).setup()) {
            var activity = owner.get();
            Utils.setContext(activity);
            try {
                SettingsStatus.liteDarkModeEnabled = false;
                var without = activity.getPreferenceManager().createPreferenceScreen(activity);
                new ExtensionPreferenceCategory(activity, without);
                assertNull(without.findPreference(LiteDarkMode.SWITCH_KEY));

                SettingsStatus.liteDarkModeEnabled = true;
                assertTrue("the App page has something on it", ExtensionPreferenceCategory.isAvailable());
                var with = activity.getPreferenceManager().createPreferenceScreen(activity);
                new ExtensionPreferenceCategory(activity, with);
                var row = with.findPreference(LiteDarkMode.SWITCH_KEY);
                assertNotNull(row);
                assertEquals("Dark mode", row.getTitle().toString());
                assertTrue(row.getSummary().toString().contains("Restart TikTok Lite"));
            } finally {
                SettingsStatus.liteDarkModeEnabled = false;
            }
        }
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlyApplication;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Freeze TikTok's experiments' switch. It starts off, a paused build and one without the patch's
 * flag let every store through, and a freeze turned on before TikTok ever stored anything lets the
 * whole of that start through once, so TikTok isn't left on its built-in defaults.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RemoteConfigFreezeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        EarlyApplication.reset();
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.FREEZE_REMOTE_CONFIG.save(false);
        Settings.REMOTE_CONFIG_STORED.save(false);
        saveInTheFile(Settings.FREEZE_REMOTE_CONFIG.key, false);
        saveInTheFile(Settings.REMOTE_CONFIG_STORED.key, false);
        SettingsStatus.remoteConfigFreezeEnabled = false;
        RemoteConfigFreeze.firstStart = null;
    }

    /** The early read opens the application's own preferences file, written here as well. */
    private static void saveInTheFile(String key, boolean value) {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).commit();
    }

    @Test public void theSwitchStartsOffAndAStoreWithItOffIsRemembered() {
        assertEquals(Boolean.FALSE, Settings.FREEZE_REMOTE_CONFIG.defaultValue);
        assertEquals(RemoteConfigFreeze.SWITCH_KEY, Settings.FREEZE_REMOTE_CONFIG.key);
        assertEquals(RemoteConfigFreeze.STORED_KEY, Settings.REMOTE_CONFIG_STORED.key);
        assertFalse("each store asks again, so no restart", Settings.FREEZE_REMOTE_CONFIG.rebootApp);
        assertFalse("the stored flag describes this phone", Settings.REMOTE_CONFIG_STORED.includeWithImportExport);

        SettingsStatus.remoteConfigFreezeEnabled = true;
        assertFalse("off, the store runs as TikTok ships it", RemoteConfigFreeze.holdsStore());
        assertTrue("a store that went through is remembered", Settings.REMOTE_CONFIG_STORED.get());
        assertNull("off decides nothing about the start", RemoteConfigFreeze.firstStart);
    }

    @Test public void onAfterAStoreEveryStoreIsHeld() {
        SettingsStatus.remoteConfigFreezeEnabled = true;
        Settings.REMOTE_CONFIG_STORED.save(true);
        Settings.FREEZE_REMOTE_CONFIG.save(true);

        assertTrue(RemoteConfigFreeze.holdsStore());
        assertTrue("the chunks of one response are held alike", RemoteConfigFreeze.holdsStore());

        Settings.FREEZE_REMOTE_CONFIG.save(false);
        assertFalse("turned off, the next store goes through", RemoteConfigFreeze.holdsStore());
    }

    @Test public void onBeforeAnyStoreTheWholeStartGoesThroughOnce() {
        SettingsStatus.remoteConfigFreezeEnabled = true;
        Settings.FREEZE_REMOTE_CONFIG.save(true);

        assertFalse("nothing stored yet, so this start stores", RemoteConfigFreeze.holdsStore());
        assertTrue(Settings.REMOTE_CONFIG_STORED.get());
        assertFalse("and every later store of the same start too", RemoteConfigFreeze.holdsStore());

        RemoteConfigFreeze.firstStart = null;
        assertTrue("the next start holds", RemoteConfigFreeze.holdsStore());
    }

    @Test public void pausedOrWithoutThePatchFlagEveryStoreGoesThrough() {
        Settings.REMOTE_CONFIG_STORED.save(true);
        Settings.FREEZE_REMOTE_CONFIG.save(true);
        assertFalse("without the patch's flag", RemoteConfigFreeze.holdsStore());

        // Stored already and on, so only the pause lets this one through.
        SettingsStatus.remoteConfigFreezeEnabled = true;
        PausedProcess.set(true);
        assertFalse("paused, the store runs as TikTok ships it", RemoteConfigFreeze.holdsStore());
        assertNull("paused decides nothing about the start", RemoteConfigFreeze.firstStart);

        PausedProcess.set(false);
        Settings.REMOTE_CONFIG_STORED.save(false);
        PausedProcess.set(true);
        assertFalse(RemoteConfigFreeze.holdsStore());
        assertTrue("paused, the store that went through is remembered", Settings.REMOTE_CONFIG_STORED.savedValue());
        assertNull(RemoteConfigFreeze.firstStart);
    }

    @Test public void beforeTheSettingsContextTheSavedFileIsRead() {
        Context application = RuntimeEnvironment.getApplication();
        SettingsStatus.remoteConfigFreezeEnabled = true;
        Settings.REMOTE_CONFIG_STORED.save(true);
        Settings.FREEZE_REMOTE_CONFIG.save(true);
        saveInTheFile(Settings.REMOTE_CONFIG_STORED.key, true);
        saveInTheFile(Settings.FREEZE_REMOTE_CONFIG.key, true);
        EarlyApplication.set(application);
        Utils.setContext(null);
        try {
            assertTrue(RemoteConfigFreeze.holdsStore());
            RemoteConfigFreeze.firstStart = null;
            EarlyApplication.set(null);
            assertFalse("no application yet answers off", RemoteConfigFreeze.holdsStore());
        } finally {
            Utils.setContext(application);
        }
    }

    @Test public void theRowIsOnTheAppPageOnlyWhenPatched() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            assertNull(build(activity).findPreference(Settings.FREEZE_REMOTE_CONFIG.key));

            SettingsStatus.remoteConfigFreezeEnabled = true;
            assertTrue(ExtensionPreferenceCategory.isAvailable());
            Preference row = build(activity).findPreference(Settings.FREEZE_REMOTE_CONFIG.key);
            assertNotNull(row);
            assertEquals("Freeze TikTok's experiments", String.valueOf(row.getTitle()));
            assertFalse("no restart note", String.valueOf(row.getSummary()).contains("Restart"));
        }
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new ExtensionPreferenceCategory(activity, screen);
        return screen;
    }
}

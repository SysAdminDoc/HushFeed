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
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlyApplication;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Freeze TikTok's experiments' switch. It starts off, a paused build and one without the patch's
 * flag let every store through, and a freeze turned on before TikTok stored anything for the
 * running TikTok and account lets the whole of that start through once, so TikTok isn't left on
 * its built-in defaults or on what an older build or another account was sent.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RemoteConfigFreezeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** TikTok 47.7.2's version code. */
    private static final String VERSION = "2024707020";
    /** 47.6.3's, the build before it. */
    private static final String OLDER_VERSION = "2024706030";

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Before public void setUp() {
        RemoteConfigFreeze.version = VERSION;
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        EarlyApplication.reset();
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.FREEZE_REMOTE_CONFIG.save(false);
        Settings.REMOTE_CONFIG_STAMP.save("");
        theFile().edit().remove(Settings.FREEZE_REMOTE_CONFIG.key).remove(Settings.REMOTE_CONFIG_STAMP.key).commit();
        SettingsStatus.remoteConfigFreezeEnabled = false;
        SignedInUser.idForTests = null;
        RemoteConfigFreeze.firstStart = null;
        RemoteConfigFreeze.version = null;
    }

    /** The preferences file the early reads and saves open, through the application. */
    private static SharedPreferences theFile() {
        return RuntimeEnvironment.getApplication().getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** Starts the next TikTok process: nothing about the last one's answer is left. */
    private static void nextStart() {
        RemoteConfigFreeze.firstStart = null;
    }

    @Test public void theSwitchStartsOffAndAStoreWithItOffIsRemembered() {
        assertEquals(Boolean.FALSE, Settings.FREEZE_REMOTE_CONFIG.defaultValue);
        assertEquals(RemoteConfigFreeze.SWITCH_KEY, Settings.FREEZE_REMOTE_CONFIG.key);
        assertEquals(RemoteConfigFreeze.STAMP_KEY, Settings.REMOTE_CONFIG_STAMP.key);
        assertEquals("", Settings.REMOTE_CONFIG_STAMP.defaultValue);
        assertFalse("each store asks again, so no restart", Settings.FREEZE_REMOTE_CONFIG.rebootApp);
        assertFalse("the stamp describes this phone", Settings.REMOTE_CONFIG_STAMP.includeWithImportExport);

        SettingsStatus.remoteConfigFreezeEnabled = true;
        assertFalse("off, the store runs as TikTok ships it", RemoteConfigFreeze.holdsStore());
        assertEquals("a store that went through is remembered with its TikTok",
                VERSION + "/", Settings.REMOTE_CONFIG_STAMP.get());
        assertNull("off decides nothing about the start", RemoteConfigFreeze.firstStart);
    }

    @Test public void onAfterAStoreEveryStoreIsHeld() {
        SettingsStatus.remoteConfigFreezeEnabled = true;
        Settings.REMOTE_CONFIG_STAMP.save(VERSION + "/");
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
        assertEquals(VERSION + "/", Settings.REMOTE_CONFIG_STAMP.get());
        assertFalse("and every later store of the same start too", RemoteConfigFreeze.holdsStore());

        nextStart();
        assertTrue("the next start holds", RemoteConfigFreeze.holdsStore());
    }

    @Test public void aTikTokUpdateLetsOneStartThrough() {
        SettingsStatus.remoteConfigFreezeEnabled = true;
        SignedInUser.idForTests = "101";
        Settings.REMOTE_CONFIG_STAMP.save(OLDER_VERSION + "/101");
        Settings.FREEZE_REMOTE_CONFIG.save(true);

        assertFalse("values the older TikTok was sent aren't kept", RemoteConfigFreeze.holdsStore());
        assertEquals(VERSION + "/101", Settings.REMOTE_CONFIG_STAMP.get());
        assertFalse("the whole start goes through", RemoteConfigFreeze.holdsStore());

        nextStart();
        assertTrue("the next start on the same TikTok holds", RemoteConfigFreeze.holdsStore());
    }

    @Test public void anAccountSwitchLetsTheNewAccountsValuesThrough() {
        SettingsStatus.remoteConfigFreezeEnabled = true;
        SignedInUser.idForTests = "101";
        Settings.REMOTE_CONFIG_STAMP.save(VERSION + "/101");
        Settings.FREEZE_REMOTE_CONFIG.save(true);
        assertTrue(RemoteConfigFreeze.holdsStore());

        SignedInUser.idForTests = "202";
        assertFalse("switched in the same start, the other account's response goes through",
                RemoteConfigFreeze.holdsStore());
        assertEquals(VERSION + "/202", Settings.REMOTE_CONFIG_STAMP.get());
        assertFalse("its chunks too", RemoteConfigFreeze.holdsStore());

        nextStart();
        assertTrue("the next start on that account holds", RemoteConfigFreeze.holdsStore());
        SignedInUser.idForTests = "101";
        assertFalse("and switching back takes the first account's values again", RemoteConfigFreeze.holdsStore());
        assertEquals(VERSION + "/101", Settings.REMOTE_CONFIG_STAMP.get());
    }

    @Test public void anAccountNotKnownHoldsAndKeepsTheStampsAccount() {
        SettingsStatus.remoteConfigFreezeEnabled = true;
        SignedInUser.idForTests = "";
        Settings.REMOTE_CONFIG_STAMP.save(VERSION + "/101");
        Settings.FREEZE_REMOTE_CONFIG.save(true);

        assertTrue("an account not known yet is no switch", RemoteConfigFreeze.holdsStore());
        assertEquals(VERSION + "/101", Settings.REMOTE_CONFIG_STAMP.get());
    }

    @Test public void anUpdateWithTheAccountNotKnownKeepsTheStampsAccount() {
        SettingsStatus.remoteConfigFreezeEnabled = true;
        SignedInUser.idForTests = "";
        Settings.REMOTE_CONFIG_STAMP.save(OLDER_VERSION + "/101");
        Settings.FREEZE_REMOTE_CONFIG.save(true);

        assertFalse("the update lets the start through", RemoteConfigFreeze.holdsStore());
        assertEquals(VERSION + "/101", Settings.REMOTE_CONFIG_STAMP.get());

        nextStart();
        assertTrue(RemoteConfigFreeze.holdsStore());
        SignedInUser.idForTests = "101";
        assertTrue("the account the stamp kept turns up, nothing to re-decide", RemoteConfigFreeze.holdsStore());
        SignedInUser.idForTests = "202";
        assertFalse("another one does", RemoteConfigFreeze.holdsStore());
    }

    @Test public void aStoreWithTheSwitchOffNamesNoAccount() {
        SettingsStatus.remoteConfigFreezeEnabled = true;
        SignedInUser.idForTests = "101";
        Settings.REMOTE_CONFIG_STAMP.save(VERSION + "/101");
        Settings.FREEZE_REMOTE_CONFIG.save(true);
        assertTrue(RemoteConfigFreeze.holdsStore());

        // Turned off, signed into another account that stores, then back with no store between
        // the switch back and turning the freeze on: what's stored is the other account's.
        Settings.FREEZE_REMOTE_CONFIG.save(false);
        nextStart();
        SignedInUser.idForTests = "202";
        assertFalse(RemoteConfigFreeze.holdsStore());
        assertEquals("off, the account isn't asked, so the stamp names none",
                VERSION + "/", Settings.REMOTE_CONFIG_STAMP.get());

        SignedInUser.idForTests = "101";
        Settings.FREEZE_REMOTE_CONFIG.save(true);
        nextStart();
        assertFalse("turned on, the first start takes the signed-in account's values",
                RemoteConfigFreeze.holdsStore());
        assertEquals(VERSION + "/101", Settings.REMOTE_CONFIG_STAMP.get());
        nextStart();
        assertTrue(RemoteConfigFreeze.holdsStore());
    }

    @Test public void anAccountKnownAfterAnEarlyHoldReDecidesTheStart() {
        Context application = RuntimeEnvironment.getApplication();
        SettingsStatus.remoteConfigFreezeEnabled = true;
        Settings.REMOTE_CONFIG_STAMP.save(VERSION + "/101");
        Settings.FREEZE_REMOTE_CONFIG.save(true);
        SignedInUser.idForTests = "202";
        EarlyApplication.set(application);
        Utils.setContext(null);
        try {
            assertTrue("before the settings context the account isn't asked", RemoteConfigFreeze.holdsStore());
        } finally {
            Utils.setContext(application);
        }
        assertFalse("asked once the settings context is up, another account re-decides the start",
                RemoteConfigFreeze.holdsStore());
        assertEquals(VERSION + "/202", Settings.REMOTE_CONFIG_STAMP.get());
    }

    @Test public void aStampWithoutItsSlashCountsAsNothingStored() {
        SettingsStatus.remoteConfigFreezeEnabled = true;
        SignedInUser.idForTests = "101";
        Settings.REMOTE_CONFIG_STAMP.save(VERSION);
        Settings.FREEZE_REMOTE_CONFIG.save(true);

        assertFalse(RemoteConfigFreeze.holdsStore());
        assertEquals(VERSION + "/101", Settings.REMOTE_CONFIG_STAMP.get());
    }

    @Test public void signingInAfterAStoreWithNoAccountLetsTheAccountsValuesThrough() {
        SettingsStatus.remoteConfigFreezeEnabled = true;
        SignedInUser.idForTests = "";
        Settings.REMOTE_CONFIG_STAMP.save(VERSION + "/");
        Settings.FREEZE_REMOTE_CONFIG.save(true);
        assertTrue(RemoteConfigFreeze.holdsStore());

        SignedInUser.idForTests = "303";
        assertFalse("values stored with no account known aren't kept for one", RemoteConfigFreeze.holdsStore());
        assertEquals(VERSION + "/303", Settings.REMOTE_CONFIG_STAMP.get());

        nextStart();
        assertTrue(RemoteConfigFreeze.holdsStore());
    }

    @Test public void aStampMatchesOnlyItsTikTokAndAccount() {
        assertTrue(RemoteConfigFreeze.matches(VERSION + "/101", VERSION, "101"));
        assertTrue("an account not known matches", RemoteConfigFreeze.matches(VERSION + "/101", VERSION, null));
        assertTrue(RemoteConfigFreeze.matches(VERSION + "/", VERSION, null));
        assertFalse(RemoteConfigFreeze.matches(VERSION + "/101", VERSION, "202"));
        assertFalse(RemoteConfigFreeze.matches(VERSION + "/", VERSION, "202"));
        assertFalse(RemoteConfigFreeze.matches(OLDER_VERSION + "/101", VERSION, "101"));
        assertFalse(RemoteConfigFreeze.matches(OLDER_VERSION + "/101", VERSION, null));
        assertFalse("never stored", RemoteConfigFreeze.matches("", VERSION, null));
        assertFalse("no slash", RemoteConfigFreeze.matches(VERSION, VERSION, null));
    }

    @Test public void theVersionIsThePackagesVersionCode() {
        Context application = RuntimeEnvironment.getApplication();
        shadowOf(application.getPackageManager())
                .getInternalMutablePackageInfo(application.getPackageName())
                .setLongVersionCode(2024706030L);
        RemoteConfigFreeze.version = null;
        assertEquals("2024706030", RemoteConfigFreeze.versionOf(application));
    }

    @Test public void pausedOrWithoutThePatchFlagEveryStoreGoesThrough() {
        Settings.REMOTE_CONFIG_STAMP.save(VERSION + "/");
        Settings.FREEZE_REMOTE_CONFIG.save(true);
        assertFalse("without the patch's flag", RemoteConfigFreeze.holdsStore());

        // Stored already and on, so only the pause lets this one through.
        SettingsStatus.remoteConfigFreezeEnabled = true;
        PausedProcess.set(true);
        assertFalse("paused, the store runs as TikTok ships it", RemoteConfigFreeze.holdsStore());
        assertNull("paused decides nothing about the start", RemoteConfigFreeze.firstStart);

        PausedProcess.set(false);
        Settings.REMOTE_CONFIG_STAMP.save("");
        PausedProcess.set(true);
        assertFalse(RemoteConfigFreeze.holdsStore());
        assertEquals("paused, the store that went through is remembered",
                VERSION + "/", Settings.REMOTE_CONFIG_STAMP.savedValue());
        assertNull(RemoteConfigFreeze.firstStart);
    }

    @Test public void beforeTheSettingsContextTheSavedFileIsRead() {
        Context application = RuntimeEnvironment.getApplication();
        SettingsStatus.remoteConfigFreezeEnabled = true;
        theFile().edit().putString(Settings.REMOTE_CONFIG_STAMP.key, VERSION + "/")
                .putBoolean(Settings.FREEZE_REMOTE_CONFIG.key, true).commit();
        EarlyApplication.set(application);
        Utils.setContext(null);
        try {
            assertTrue(RemoteConfigFreeze.holdsStore());
            nextStart();
            EarlyApplication.set(null);
            assertFalse("no application yet answers off", RemoteConfigFreeze.holdsStore());
        } finally {
            Utils.setContext(application);
        }
    }

    @Test public void aStoreBeforeTheSettingsContextIsSavedInTheFile() {
        Context application = RuntimeEnvironment.getApplication();
        SettingsStatus.remoteConfigFreezeEnabled = true;
        theFile().edit().putBoolean(Settings.FREEZE_REMOTE_CONFIG.key, true).commit();
        EarlyApplication.set(application);
        Utils.setContext(null);
        try {
            assertFalse("nothing stored yet, so this start stores", RemoteConfigFreeze.holdsStore());
            assertEquals("a start that ends before the settings context still counts",
                    VERSION + "/", theFile().getString(Settings.REMOTE_CONFIG_STAMP.key, ""));
            nextStart();
            assertTrue("so the next start holds", RemoteConfigFreeze.holdsStore());

            theFile().edit().putBoolean(Settings.FREEZE_REMOTE_CONFIG.key, false)
                    .putString(Settings.REMOTE_CONFIG_STAMP.key, OLDER_VERSION + "/101").commit();
            assertFalse(RemoteConfigFreeze.holdsStore());
            assertEquals("off, an early store saves this TikTok and no account",
                    VERSION + "/", theFile().getString(Settings.REMOTE_CONFIG_STAMP.key, ""));
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

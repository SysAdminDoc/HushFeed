/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.privacy.DeviceIdentity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The New device identity row: a tap arms it and owes a restart, a second tap takes both back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class DeviceIdentityPreferenceTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Activity activity;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        assertTrue(DeviceIdentity.setArmed(activity, false));
    }

    @After public void tearDown() {
        saved().edit().clear().commit();
        AbstractPreferenceFragment.restartPending.clear();
    }

    private SharedPreferences saved() {
        return activity.getSharedPreferences(DeviceIdentity.PREFERENCES, Context.MODE_PRIVATE);
    }

    private void holdAnIdentity() {
        saved().edit().putString("android_id", "fedcba9876543210").commit();
    }

    private static void tap(DeviceIdentityPreference row) {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
    }

    @Test public void aTapArmsTheIdentityAndOwesARestartAndASecondTapTakesItBack() {
        DeviceIdentityPreference row = new DeviceIdentityPreference(activity);
        assertEquals(DeviceIdentityPreference.TITLE, String.valueOf(row.getTitle()));
        assertEquals(DeviceIdentityPreference.SUMMARY, String.valueOf(row.getSummary()));
        assertFalse(row.isPersistent());

        tap(row);
        assertTrue(DeviceIdentity.isArmed(activity));
        assertTrue("arming shows the restart row",
                AbstractPreferenceFragment.restartPending.contains(DeviceIdentityPreference.KEY));
        assertEquals(DeviceIdentityPreference.ARMED_SUMMARY, String.valueOf(row.getSummary()));

        tap(row);
        assertFalse(DeviceIdentity.isArmed(activity));
        assertFalse("disarming takes the restart back",
                AbstractPreferenceFragment.restartPending.contains(DeviceIdentityPreference.KEY));
        assertEquals(DeviceIdentityPreference.SUMMARY, String.valueOf(row.getSummary()));
    }

    @Test public void aRowOpenedLaterShowsWhatIsArmed() {
        assertTrue(DeviceIdentity.setArmed(activity, true));
        assertEquals(DeviceIdentityPreference.ARMED_SUMMARY,
                String.valueOf(new DeviceIdentityPreference(activity).getSummary()));
    }

    @Test public void theRowSaysWhenAnIdentityIsInUse() {
        holdAnIdentity();
        DeviceIdentityPreference row = new DeviceIdentityPreference(activity);
        assertEquals(DeviceIdentityPreference.IN_USE_SUMMARY, String.valueOf(row.getSummary()));

        tap(row);
        assertEquals("armed outranks in use", DeviceIdentityPreference.ARMED_SUMMARY, String.valueOf(row.getSummary()));
        tap(row);
        assertEquals(DeviceIdentityPreference.IN_USE_SUMMARY, String.valueOf(row.getSummary()));
    }

    @Test public void theRowSaysWhenAWipeGaveUp() {
        saved().edit().putBoolean("gave_up", true).commit();
        DeviceIdentityPreference row = new DeviceIdentityPreference(activity);
        assertEquals(DeviceIdentityPreference.GAVE_UP_SUMMARY, String.valueOf(row.getSummary()));

        tap(row);
        assertFalse("a new tap clears it", DeviceIdentity.gaveUp(activity));
        assertEquals(DeviceIdentityPreference.ARMED_SUMMARY, String.valueOf(row.getSummary()));
    }

    @Test public void theRowBackArmsItsOwnWipeAndOwesTheSameRestart() {
        holdAnIdentity();
        DeviceIdentityPreference row = DeviceIdentityPreference.ownIds(activity);
        assertEquals(DeviceIdentityPreference.OWN_IDS_KEY, row.getKey());
        assertEquals(DeviceIdentityPreference.OWN_IDS_TITLE, String.valueOf(row.getTitle()));
        assertEquals(DeviceIdentityPreference.OWN_IDS_SUMMARY, String.valueOf(row.getSummary()));
        assertFalse(row.isPersistent());

        tap(row);
        assertTrue(DeviceIdentity.isOwnIdsArmed(activity));
        assertFalse(DeviceIdentity.isArmed(activity));
        assertTrue(AbstractPreferenceFragment.restartPending.contains(DeviceIdentityPreference.KEY));
        assertEquals(DeviceIdentityPreference.OWN_IDS_ARMED_SUMMARY, String.valueOf(row.getSummary()));

        tap(row);
        assertFalse(DeviceIdentity.isOwnIdsArmed(activity));
        assertFalse(AbstractPreferenceFragment.restartPending.contains(DeviceIdentityPreference.KEY));
        assertEquals(DeviceIdentityPreference.OWN_IDS_SUMMARY, String.valueOf(row.getSummary()));
    }

    @Test public void tappingOneRowTakesOverFromTheOther() {
        holdAnIdentity();
        DeviceIdentityPreference newIdentity = new DeviceIdentityPreference(activity);
        DeviceIdentityPreference ownIds = DeviceIdentityPreference.ownIds(activity);

        tap(newIdentity);
        tap(ownIds);
        assertTrue(DeviceIdentity.isOwnIdsArmed(activity));
        assertFalse(DeviceIdentity.isArmed(activity));
        assertTrue("one wipe is still owed",
                AbstractPreferenceFragment.restartPending.contains(DeviceIdentityPreference.KEY));

        tap(newIdentity);
        assertTrue(DeviceIdentity.isArmed(activity));
        assertFalse(DeviceIdentity.isOwnIdsArmed(activity));
    }
}

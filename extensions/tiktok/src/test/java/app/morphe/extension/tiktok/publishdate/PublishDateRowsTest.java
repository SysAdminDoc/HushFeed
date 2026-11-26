/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.publishdate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The publish date options sit under its switch on Feed screen, and only when it's patched. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PublishDateRowsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void tearDown() {
        SettingsStatus.alwaysShowPublishDateEnabled = false;
        SettingsStatus.publishDateGridsEnabled = false;
        SettingsStatus.publishDateEverywhereEnabled = false;
        Settings.ALWAYS_SHOW_PUBLISH_DATE.resetToDefault();
        Settings.PUBLISH_DATE_DAY_FIRST.resetToDefault();
        Settings.PUBLISH_DATE_MONTH_NAME.resetToDefault();
        Settings.PUBLISH_DATE_INSTEAD_OF_AGE.resetToDefault();
        Settings.PUBLISH_DATE_EVERYWHERE.resetToDefault();
    }

    /**
     * Use this date format everywhere (#536) shows only where comments, chats and search cards
     * were hooked, off until picked, and it has nothing to carry until one of the date rows is on.
     */
    @Test public void theEverywhereRowShowsOnlyWhereThoseDatesWereHooked() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.alwaysShowPublishDateEnabled = true;

            SettingsStatus.publishDateEverywhereEnabled = false;
            PreferenceScreen lite = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, lite);
            assertNotNull(lite.findPreference(Settings.PUBLISH_DATE_DAY_FIRST.key));
            assertNull(lite.findPreference(Settings.PUBLISH_DATE_EVERYWHERE.key));

            SettingsStatus.publishDateEverywhereEnabled = true;
            PreferenceScreen tiktok = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, tiktok);
            assertNotNull(tiktok.findPreference(Settings.PUBLISH_DATE_EVERYWHERE.key));
            assertFalse(Settings.PUBLISH_DATE_EVERYWHERE.get());
            assertFalse(Settings.PUBLISH_DATE_EVERYWHERE.rebootApp);

            assertFalse(Settings.PUBLISH_DATE_EVERYWHERE.isAvailable());
            Settings.PUBLISH_DATE_DAY_FIRST.save(true);
            assertTrue(Settings.PUBLISH_DATE_EVERYWHERE.isAvailable());
            Settings.PUBLISH_DATE_DAY_FIRST.save(false);
            Settings.PUBLISH_DATE_MONTH_NAME.save(true);
            assertTrue(Settings.PUBLISH_DATE_EVERYWHERE.isAvailable());
            Settings.PUBLISH_DATE_MONTH_NAME.save(false);
            Settings.PUBLISH_DATE_INSTEAD_OF_AGE.save(true);
            assertTrue(Settings.PUBLISH_DATE_EVERYWHERE.isAvailable());
        }
    }

    /**
     * TikTok Lite (#502) patches only the date by the creator's name. Its grid rows would change
     * nothing there, so they're offered only where the grids were hooked.
     */
    @Test public void theGridRowsShowOnlyWhereTheGridsWereHooked() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.alwaysShowPublishDateEnabled = true;

            SettingsStatus.publishDateGridsEnabled = false;
            PreferenceScreen lite = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, lite);
            assertNotNull(lite.findPreference(Settings.ALWAYS_SHOW_PUBLISH_DATE.key));
            assertNotNull(lite.findPreference(Settings.PUBLISH_DATE_EXACT_TIME.key));
            assertNotNull(lite.findPreference(Settings.PUBLISH_DATE_INSTEAD_OF_AGE.key));
            assertNull(lite.findPreference(Settings.PUBLISH_DATE_ON_GRID.key));
            assertNull(lite.findPreference(Settings.VIDEO_LENGTH_ON_GRID.key));
            assertNull(lite.findPreference(Settings.HIDE_GRID_VIEW_COUNT.key));
            assertNull(lite.findPreference(Settings.ROUNDED_GRID_CORNERS.key));

            SettingsStatus.publishDateGridsEnabled = true;
            PreferenceScreen tiktok = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, tiktok);
            assertNotNull(tiktok.findPreference(Settings.PUBLISH_DATE_ON_GRID.key));
            assertNotNull(tiktok.findPreference(Settings.ROUNDED_GRID_CORNERS.key));
        }
    }

    @Test public void theOptionsAreOnTheFeedScreenPageOnlyWhenPatched() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            SettingsStatus.alwaysShowPublishDateEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, without);
            assertNull(without.findPreference(Settings.PUBLISH_DATE_EXACT_TIME.key));
            assertNull(without.findPreference(Settings.PUBLISH_DATE_ON_GRID.key));
            assertNull(without.findPreference(Settings.PUBLISH_DATE_TIME_ZONE.key));
            assertNull(without.findPreference(Settings.PUBLISH_DATE_SHORT_DATE.key));
            assertNull(without.findPreference(Settings.PUBLISH_DATE_DAY_FIRST.key));
            assertNull(without.findPreference(Settings.PUBLISH_DATE_MONTH_NAME.key));
            assertNull(without.findPreference(Settings.PUBLISH_DATE_INSTEAD_OF_AGE.key));
            assertNull(without.findPreference(Settings.VIDEO_LENGTH_ON_GRID.key));

            SettingsStatus.alwaysShowPublishDateEnabled = true;
            SettingsStatus.publishDateGridsEnabled = true;
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, with);
            assertNotNull(with.findPreference(Settings.ALWAYS_SHOW_PUBLISH_DATE.key));
            assertNotNull(with.findPreference(Settings.PUBLISH_DATE_EXACT_TIME.key));
            assertNotNull(with.findPreference(Settings.PUBLISH_DATE_ON_GRID.key));
            assertNotNull(with.findPreference(Settings.PUBLISH_DATE_TIME_ZONE.key));
            assertNotNull(with.findPreference(Settings.PUBLISH_DATE_SHORT_DATE.key));
            assertNotNull(with.findPreference(Settings.PUBLISH_DATE_DAY_FIRST.key));
            assertNotNull(with.findPreference(Settings.PUBLISH_DATE_MONTH_NAME.key));
            assertNotNull(with.findPreference(Settings.PUBLISH_DATE_INSTEAD_OF_AGE.key));
            assertNotNull(with.findPreference(Settings.VIDEO_LENGTH_ON_GRID.key));
            // They apply to the next video or grid shown, so none asks for a restart.
            assertFalse(Settings.PUBLISH_DATE_EXACT_TIME.rebootApp);
            assertFalse(Settings.PUBLISH_DATE_ON_GRID.rebootApp);
            assertFalse(Settings.VIDEO_LENGTH_ON_GRID.rebootApp);
            assertFalse(Settings.PUBLISH_DATE_TIME_ZONE.rebootApp);
            assertFalse(Settings.PUBLISH_DATE_SHORT_DATE.rebootApp);
            assertFalse(Settings.PUBLISH_DATE_DAY_FIRST.rebootApp);
            assertFalse(Settings.PUBLISH_DATE_MONTH_NAME.rebootApp);
            assertFalse(Settings.PUBLISH_DATE_INSTEAD_OF_AGE.rebootApp);
            // Both only shape the time to the second, so they follow its switch.
            try {
                Settings.PUBLISH_DATE_EXACT_TIME.save(false);
                assertFalse(Settings.PUBLISH_DATE_TIME_ZONE.isAvailable());
                assertFalse(Settings.PUBLISH_DATE_SHORT_DATE.isAvailable());
                Settings.PUBLISH_DATE_EXACT_TIME.save(true);
                assertTrue(Settings.PUBLISH_DATE_TIME_ZONE.isAvailable());
                assertTrue(Settings.PUBLISH_DATE_SHORT_DATE.isAvailable());
            } finally {
                Settings.PUBLISH_DATE_EXACT_TIME.resetToDefault();
            }
        }
    }
}

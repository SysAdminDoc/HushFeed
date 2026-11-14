/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.CheckBox;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The Settings and privacy checklist offers the rows this phone listed and keeps a pick it doesn't. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class SettingsRowChecklistTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static class Host extends Activity {}

    @After public void reset() {
        PausedProcess.set(false);
        Settings.SETTINGS_ROW_PICKS.save("");
        Settings.SETTINGS_ROW_CATALOG.save("");
    }

    private static CheckBox row(View root, String key) {
        return (CheckBox) root.findViewWithTag("settings_row_" + key);
    }

    @Test public void theListedRowsShowAndAPickNoLongerListedIsKept() {
        Settings.SETTINGS_ROW_CATALOG.save("ads_settings,shake,logout");
        Settings.SETTINGS_ROW_PICKS.save("Shake, gone_row");
        try (var owner = Robolectric.buildActivity(Host.class).setup()) {
            Utils.setContext(owner.get());
            SettingsRowChecklistPreference checklist = new SettingsRowChecklistPreference(owner.get());
            View root = checklist.onCreateDialogView();

            assertEquals("Ads", row(root, "ads_settings").getText().toString());
            assertEquals("Shake to report", row(root, "shake").getText().toString());
            assertEquals("Log out", row(root, "logout").getText().toString());
            assertNull("a row this phone never listed isn't offered", row(root, "qrcode"));
            assertTrue("a saved pick shows ticked", row(root, "shake").isChecked());
            assertFalse(row(root, "logout").isChecked());

            row(root, "logout").setChecked(true);
            row(root, "shake").setChecked(false);
            assertEquals("gone_row, logout", checklist.buildPicks());
        }
    }

    @Test public void pausedTheChecklistStillShowsWhatWasPicked() {
        Settings.SETTINGS_ROW_PICKS.save("feedback");
        PausedProcess.set(true);
        try (var owner = Robolectric.buildActivity(Host.class).setup()) {
            Utils.setContext(owner.get());
            SettingsRowChecklistPreference checklist = new SettingsRowChecklistPreference(owner.get());
            View root = checklist.onCreateDialogView();

            assertTrue(row(root, "feedback").isChecked());
            assertEquals("saving while paused keeps the pick", "feedback", checklist.buildPicks());
        }
    }
}

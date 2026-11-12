/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.CheckBox;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.profile.ProfileTabs;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The profile tab checklist saves its picks and keeps one it no longer offers. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ProfileTabChecklistTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static class Host extends Activity {}

    @After public void reset() {
        Settings.PROFILE_TAB_PICKS.save("");
    }

    private static CheckBox row(View root, String key) {
        return (CheckBox) root.findViewWithTag("profile_tab_" + key);
    }

    @Test public void everyRowShowsAndAPickNoRowOffersIsKept() {
        Settings.PROFILE_TAB_PICKS.save("like, gone tab");
        try (var owner = Robolectric.buildActivity(Host.class).setup()) {
            Utils.setContext(owner.get());
            ProfileTabChecklistPreference checklist = new ProfileTabChecklistPreference(owner.get());
            View root = checklist.onCreateDialogView();

            for (ProfileTabs.Row tab : ProfileTabs.ROWS) {
                assertEquals(tab.label, row(root, tab.key).getText().toString());
            }
            assertTrue("a saved pick shows ticked", row(root, "like").isChecked());
            assertFalse(row(root, "repost").isChecked());

            row(root, "repost").setChecked(true);
            row(root, "like").setChecked(false);
            assertEquals("gone tab, repost", checklist.buildPicks());
        }
    }

    @Test public void pausedTheChecklistStillShowsWhatWasPicked() {
        Settings.PROFILE_TAB_PICKS.save("favorite");
        PausedProcess.set(true);
        try (var owner = Robolectric.buildActivity(Host.class).setup()) {
            Utils.setContext(owner.get());
            ProfileTabChecklistPreference checklist = new ProfileTabChecklistPreference(owner.get());
            View root = checklist.onCreateDialogView();

            assertTrue(row(root, "favorite").isChecked());
            assertEquals("saving while paused keeps the pick", "favorite", checklist.buildPicks());
        } finally {
            PausedProcess.set(false);
        }
    }
}

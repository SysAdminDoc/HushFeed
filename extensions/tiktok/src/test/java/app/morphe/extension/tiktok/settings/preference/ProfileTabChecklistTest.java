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
import android.view.ViewGroup;
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
        Settings.PROFILE_TAB_ORDER.save("");
    }

    private static CheckBox row(View root, String key) {
        return (CheckBox) root.findViewWithTag("profile_tab_" + key);
    }

    /** The key of each row, top to bottom, as the dialog shows them. */
    private static String shown(View root) {
        ViewGroup rows = (ViewGroup) row(root, "videos").getParent().getParent();
        StringBuilder keys = new StringBuilder();
        for (int index = 0; index < rows.getChildCount(); index++) {
            View check = ((ViewGroup) rows.getChildAt(index)).getChildAt(0);
            if (keys.length() > 0) keys.append(", ");
            keys.append(String.valueOf(check.getTag()).substring("profile_tab_".length()));
        }
        return keys.toString();
    }

    @Test public void theArrowsMoveARowAndTheOrderSaves() {
        Settings.PROFILE_TAB_ORDER.save("like");
        try (var owner = Robolectric.buildActivity(Host.class).setup()) {
            Utils.setContext(owner.get());
            ProfileTabChecklistPreference checklist = new ProfileTabChecklistPreference(owner.get());
            View root = checklist.onCreateDialogView();

            assertEquals("like, videos, sticker, cast, private, repost, favorite, shop, music, effect, series",
                    shown(root));
            assertFalse("the top row can't go up", root.findViewWithTag("profile_tab_like_up").isEnabled());
            assertFalse("the last row can't go down", root.findViewWithTag("profile_tab_series_down").isEnabled());
            assertEquals("Move up: Reposts",
                    root.findViewWithTag("profile_tab_repost_up").getContentDescription().toString());

            row(root, "repost").setChecked(true);
            assertTrue(root.findViewWithTag("profile_tab_repost_up").performClick());
            assertTrue(root.findViewWithTag("profile_tab_like_down").performClick());
            assertEquals("videos, like, sticker, cast, repost, private, favorite, shop, music, effect, series",
                    shown(root));
            assertEquals(shown(root), checklist.buildOrder());
            assertTrue("a pick stays ticked when its row moves", row(root, "repost").isChecked());
            assertEquals("repost", checklist.buildPicks());

            checklist.resetOrder();
            assertEquals("the list's own order saves as TikTok's", "", checklist.buildOrder());
            assertEquals(String.join(", ", ProfileTabs.shownOrder("")), shown(root));
        }
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

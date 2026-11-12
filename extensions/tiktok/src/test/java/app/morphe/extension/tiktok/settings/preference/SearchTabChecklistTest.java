/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The search tab checklist offers the tabs seen by name and saves their keys. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class SearchTabChecklistTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static class Host extends Activity {}

    @Before public void seenTabs() {
        Settings.SEARCH_TAB_CATALOG.save("user\tUsers\nshop\tTienda\nlive\tLIVE\nplace\tPlaces");
    }

    @After public void reset() {
        Settings.SEARCH_TAB_CATALOG.save("");
        Settings.SEARCH_TAB_PICKS.save("");
    }

    private static CheckBox row(View root, String key) {
        return (CheckBox) root.findViewWithTag("search_tab_" + key);
    }

    @Test public void theRowsShowTheNamesAndSaveTheKeysKeepingALostPick() {
        Settings.SEARCH_TAB_PICKS.save("shop, music");
        try (var owner = Robolectric.buildActivity(Host.class).setup()) {
            Utils.setContext(owner.get());
            SearchTabChecklistPreference checklist = new SearchTabChecklistPreference(owner.get());
            View root = checklist.onCreateDialogView();

            assertEquals("the name TikTok gave it", "Tienda", row(root, "shop").getText().toString());
            assertTrue("a saved pick shows ticked", row(root, "shop").isChecked());
            assertFalse(row(root, "live").isChecked());
            assertNull("Top is never offered", row(root, "general"));

            row(root, "live").setChecked(true);
            row(root, "place").setChecked(true);
            assertEquals("music, shop, live, place", checklist.buildPicks());
        }
    }

    @Test public void pausedTheChecklistStillShowsWhatWasPicked() {
        Settings.SEARCH_TAB_PICKS.save("live");
        PausedProcess.set(true);
        try (var owner = Robolectric.buildActivity(Host.class).setup()) {
            Utils.setContext(owner.get());
            SearchTabChecklistPreference checklist = new SearchTabChecklistPreference(owner.get());
            View root = checklist.onCreateDialogView();

            assertNotNull(row(root, "user"));
            assertTrue(row(root, "live").isChecked());
            assertEquals("saving while paused keeps the pick", "live", checklist.buildPicks());
        } finally {
            PausedProcess.set(false);
        }
    }
}

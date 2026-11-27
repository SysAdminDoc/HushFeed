/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
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
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Hide Balance in the profile menu (#194). The patch asks {@link ProfileMenuCells#hide} about each
 * cell of the menu your profile's top right button opens, by id. The switch starts off, hides the
 * Balance cell and nothing else, and leaves TikTok's menu alone while Hushfeed is paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProfileMenuCellsTest {
    private static final String TITLE = "Hide Balance in the profile menu";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.HIDE_PROFILE_MENU_BALANCE.resetToDefault();
        Settings.HIDE_PROFILE_MENU_MINIS_ICONS.resetToDefault();
        SettingsStatus.profileShortcutsEnabled = false;
        SettingsStatus.profileMenuCellsEnabled = false;
        HookStatus.clear();
    }

    @Test public void theSwitchStartsOffAndHidesOnlyBalance() {
        assertEquals("hide_profile_menu_balance", Settings.HIDE_PROFILE_MENU_BALANCE.key);
        assertEquals(Boolean.FALSE, Settings.HIDE_PROFILE_MENU_BALANCE.defaultValue);
        assertFalse("the menu is built each time it opens", Settings.HIDE_PROFILE_MENU_BALANCE.rebootApp);

        assertFalse("off, Balance stays", ProfileMenuCells.hide(ProfileMenuCells.BALANCE));
        Settings.HIDE_PROFILE_MENU_BALANCE.save(true);
        assertTrue(ProfileMenuCells.hide("content_section_cell_balance"));
        assertFalse("another cell stays", ProfileMenuCells.hide("content_section_cell_wallet"));
        assertFalse("a cell with no id stays", ProfileMenuCells.hide(null));
        Settings.HIDE_PROFILE_MENU_BALANCE.save(false);
        assertFalse("turned back off, Balance is back", ProfileMenuCells.hide(ProfileMenuCells.BALANCE));

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("profile menu balance"));
    }

    /**
     * #230: the icons under TikTok Minis are a cell of their own, so the Minis entry stays while
     * its card goes. Each switch moves only its own cell.
     */
    @Test public void theMinisIconsSwitchHidesOnlyTheIconCard() {
        assertEquals("hide_profile_menu_minis_icons", Settings.HIDE_PROFILE_MENU_MINIS_ICONS.key);
        assertEquals(Boolean.FALSE, Settings.HIDE_PROFILE_MENU_MINIS_ICONS.defaultValue);
        assertFalse("the menu is built each time it opens", Settings.HIDE_PROFILE_MENU_MINIS_ICONS.rebootApp);
        assertEquals("content_section_cell_tiktok_minis_rich_card", ProfileMenuCells.MINIS_ICONS);

        assertFalse("off, the icons stay", ProfileMenuCells.hide(ProfileMenuCells.MINIS_ICONS));
        Settings.HIDE_PROFILE_MENU_MINIS_ICONS.save(true);
        assertTrue(ProfileMenuCells.hide("content_section_cell_tiktok_minis_rich_card"));
        assertFalse("the Minis entry itself stays", ProfileMenuCells.hide("content_section_cell_tiktok_minis"));
        assertFalse("Balance is its own switch", ProfileMenuCells.hide(ProfileMenuCells.BALANCE));

        Settings.HIDE_PROFILE_MENU_BALANCE.save(true);
        assertTrue(ProfileMenuCells.hide(ProfileMenuCells.BALANCE));
        Settings.HIDE_PROFILE_MENU_MINIS_ICONS.save(false);
        assertFalse("turned back off, the icons are back", ProfileMenuCells.hide(ProfileMenuCells.MINIS_ICONS));
        assertTrue("Balance is unaffected", ProfileMenuCells.hide(ProfileMenuCells.BALANCE));

        Settings.HIDE_PROFILE_MENU_MINIS_ICONS.save(true);
        PausedProcess.set(true);
        assertFalse("paused, TikTok's own menu", ProfileMenuCells.hide(ProfileMenuCells.MINIS_ICONS));
        String report = String.join(" ", HookStatus.report());
        assertTrue(report, report.contains("profile menu minis icons"));
    }

    @Test public void theMinisIconsRowSitsBesideBalanceAndNamesTheEntryItKeeps() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.profileShortcutsEnabled = true;
            SettingsStatus.profileMenuCellsEnabled = true;
            Preference row = build(activity).findPreference(Settings.HIDE_PROFILE_MENU_MINIS_ICONS.key);
            assertNotNull(row);
            assertEquals("Hide the TikTok Minis icons in the profile menu", String.valueOf(row.getTitle()));
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("TikTok Minis entry") && summary.contains("icons"));
            assertFalse("no restart is asked for", summary.contains("Restart TikTok"));

            SettingsStatus.profileMenuCellsEnabled = false;
            assertNull("the row showed on a build where the menu wasn't hooked",
                    build(activity).findPreference(Settings.HIDE_PROFILE_MENU_MINIS_ICONS.key));
        }
    }

    /**
     * #194: the menu as TikTok lays it out. A slot (with the section's margins) holds the
     * section's card (its background and padding), the card holds a slot for its cell list, and
     * the list holds the cells. When TikTok fills a list with nothing because its only cell was
     * hidden here, the whole section collapses, so no empty sliver is left where Balance was. It
     * comes back once its list keeps a cell again.
     */
    @Test public void aSectionWhoseListLostItsOnlyCellCollapses() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup()) {
            var activity = controller.get();
            android.widget.LinearLayout menu = new android.widget.LinearLayout(activity);
            android.widget.FrameLayout balanceSlot = new android.widget.FrameLayout(activity);
            android.widget.LinearLayout balanceCard = new android.widget.LinearLayout(activity);
            android.widget.FrameLayout balanceListSlot = new android.widget.FrameLayout(activity);
            android.widget.FrameLayout balanceList = new android.widget.FrameLayout(activity);
            balanceListSlot.addView(balanceList);
            balanceCard.addView(balanceListSlot);
            balanceSlot.addView(balanceCard);
            android.widget.FrameLayout otherSlot = new android.widget.FrameLayout(activity);
            android.widget.LinearLayout otherCard = new android.widget.LinearLayout(activity);
            android.widget.FrameLayout otherListSlot = new android.widget.FrameLayout(activity);
            android.widget.FrameLayout otherList = new android.widget.FrameLayout(activity);
            android.widget.FrameLayout loading = new android.widget.FrameLayout(activity);
            otherListSlot.addView(otherList);
            otherCard.addView(otherListSlot);
            otherCard.addView(loading);
            otherSlot.addView(otherCard);
            menu.addView(balanceSlot);
            menu.addView(otherSlot);
            activity.setContentView(menu);

            ProfileMenuCells.section(balanceCard);
            ProfileMenuCells.section(otherCard);
            ProfileMenuCells.section(otherCard);
            assertEquals("the card's slot", balanceSlot, ProfileMenuCells.slotOf(balanceCard));
            assertEquals("a section that isn't alone in its parent is its own slot", menu, ProfileMenuCells.slotOf(menu.getChildAt(0)).getParent());

            Settings.HIDE_PROFILE_MENU_BALANCE.save(true);
            fill(balanceList, ProfileMenuCells.BALANCE);
            assertEquals("the emptied section is gone, margins and all", GONE, balanceSlot.getVisibility());
            fill(otherList, "content_section_cell_activity_center");
            assertEquals("a section with a cell stays", VISIBLE, otherSlot.getVisibility());

            fill(balanceList, ProfileMenuCells.BALANCE, "content_section_cell_wallet");
            assertEquals("a list that keeps another cell keeps its section", VISIBLE, balanceSlot.getVisibility());
            fill(balanceList, ProfileMenuCells.BALANCE);
            assertEquals(GONE, balanceSlot.getVisibility());
            Settings.HIDE_PROFILE_MENU_BALANCE.save(false);
            fill(balanceList, ProfileMenuCells.BALANCE);
            assertEquals("both switches off, TikTok's own menu", VISIBLE, balanceSlot.getVisibility());

            fill(otherList, "!content_section_cell_wallet");
            assertEquals("a list TikTok left empty itself is TikTok's to show", VISIBLE, otherSlot.getVisibility());
            Settings.HIDE_PROFILE_MENU_MINIS_ICONS.save(true);
            fill(otherList, ProfileMenuCells.MINIS_ICONS);
            assertEquals("a section with something else showing stays", VISIBLE, otherSlot.getVisibility());

            otherSlot.setVisibility(GONE);
            fill(otherList, "content_section_cell_activity_center");
            assertEquals("a slot TikTok hid itself stays hidden", GONE, otherSlot.getVisibility());

            String report = String.join(" ", HookStatus.report());
            assertTrue(report, report.contains("profile menu sections"));
        }
    }

    /** TikTok can fill a section's list before the section is in the menu. It collapses once it is. */
    @Test public void aListFilledBeforeItsSectionIsShownCollapsesOnceShown() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup()) {
            var activity = controller.get();
            android.widget.LinearLayout menu = new android.widget.LinearLayout(activity);
            menu.addView(new android.widget.FrameLayout(activity));
            activity.setContentView(menu);
            android.widget.FrameLayout slot = new android.widget.FrameLayout(activity);
            android.widget.LinearLayout card = new android.widget.LinearLayout(activity);
            android.widget.FrameLayout list = new android.widget.FrameLayout(activity);
            card.addView(list);
            slot.addView(card);
            ProfileMenuCells.section(card);

            Settings.HIDE_PROFILE_MENU_BALANCE.save(true);
            android.widget.FrameLayout detached = new android.widget.FrameLayout(activity);
            fill(detached, ProfileMenuCells.BALANCE);
            card.removeView(list);
            card.addView(detached);
            menu.addView(slot);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(GONE, slot.getVisibility());
        }
    }

    /**
     * #194, the black menu on 48.0.2: with Balance hidden, TikTok lays the sections out once while
     * their cell lists are still hidden, before the cells arrive. A section that never lost a cell
     * must not collapse then. A collapsed slot is never laid out again, so its cells never showed
     * and the whole menu stayed black.
     */
    @Test public void aSectionThatLostNoCellStaysWhileItsCellsLoad() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup()) {
            var activity = controller.get();
            android.widget.LinearLayout menu = new android.widget.LinearLayout(activity);
            android.widget.FrameLayout[] slots = new android.widget.FrameLayout[3];
            android.widget.FrameLayout[] lists = new android.widget.FrameLayout[3];
            for (int i = 0; i < slots.length; i++) {
                slots[i] = new android.widget.FrameLayout(activity);
                android.widget.LinearLayout card = new android.widget.LinearLayout(activity);
                lists[i] = new android.widget.FrameLayout(activity);
                lists[i].addView(new android.widget.FrameLayout(activity));
                card.addView(lists[i]);
                slots[i].addView(card);
                menu.addView(slots[i]);
            }
            activity.setContentView(menu);
            for (android.widget.FrameLayout slot : slots) ProfileMenuCells.section(slot);

            Settings.HIDE_PROFILE_MENU_BALANCE.save(true);
            for (android.widget.FrameLayout list : lists) list.setVisibility(GONE);
            menu.getViewTreeObserver().dispatchOnGlobalLayout();
            for (int i = 0; i < slots.length; i++) {
                assertEquals("section " + i + " lost no cell", VISIBLE, slots[i].getVisibility());
            }

            fill(((android.view.ViewGroup) lists[0]).getChildAt(0), ProfileMenuCells.BALANCE);
            fill(((android.view.ViewGroup) lists[1]).getChildAt(0), "content_section_cell_activity_center");
            fill(((android.view.ViewGroup) lists[2]).getChildAt(0), "content_section_cell_settings");
            for (android.widget.FrameLayout list : lists) list.setVisibility(VISIBLE);
            menu.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals("Balance's section goes", GONE, slots[0].getVisibility());
            assertEquals(VISIBLE, slots[1].getVisibility());
            assertEquals(VISIBLE, slots[2].getVisibility());
        }
    }

    /**
     * TikTok filling one list: it asks about each cell (the patch asks {@link ProfileMenuCells#hide}
     * first), then answers. A cell id starting with "!" is one TikTok leaves out itself.
     */
    private static void fill(android.view.View list, String... cells) {
        for (String cell : cells) {
            boolean tiktokDrops = cell.startsWith("!");
            String id = tiktokDrops ? cell.substring(1) : cell;
            boolean hidden = ProfileMenuCells.hide(id);
            ProfileMenuCells.cellAnswered(!hidden && !tiktokDrops);
        }
        ProfileMenuCells.listFilled(list);
    }

    @Test public void aPausedHushfeedLeavesTheMenuAlone() {
        Settings.HIDE_PROFILE_MENU_BALANCE.save(true);
        PausedProcess.set(true);
        assertFalse(ProfileMenuCells.hide(ProfileMenuCells.BALANCE));
        PausedProcess.set(false);
        assertTrue(ProfileMenuCells.hide(ProfileMenuCells.BALANCE));
    }

    @Test public void theRowIsOnTheAppPageOnlyWhereTheMenuWasHooked() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            SettingsStatus.profileShortcutsEnabled = true;
            assertNull("the row showed on a build where the menu wasn't hooked",
                    build(activity).findPreference(Settings.HIDE_PROFILE_MENU_BALANCE.key));

            SettingsStatus.profileMenuCellsEnabled = true;
            assertTrue(ExtensionPreferenceCategory.isAvailable());
            Preference row = build(activity).findPreference(Settings.HIDE_PROFILE_MENU_BALANCE.key);
            assertNotNull(row);
            assertEquals(TITLE, String.valueOf(row.getTitle()));
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("Balance") && summary.contains("coins"));
            assertFalse("no restart is asked for", summary.contains("Restart TikTok"));
        }
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new ExtensionPreferenceCategory(activity, screen);
        return screen;
    }
}

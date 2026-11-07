/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * #358: the On this day memory at the front of the Inbox's stories tray. The patched bind hands
 * the cell over first; with the switch on its view gives up all its room in the tray, and with the
 * switch off it gets back exactly what it had.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class InboxOnThisDayTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int CELL_WIDTH = 100;
    private static final int CELL_HEIGHT = 120;
    private static final int MARGIN = 8;

    /** TikTok's PowerCell is a RecyclerView ViewHolder, read through the same field name. */
    public static final class Cell {
        public final View itemView;

        Cell(View itemView) {
            this.itemView = itemView;
        }
    }

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    private ActivityController<Activity> owner;
    private LinearLayout tray;

    @Before public void setUp() {
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = owner.get();
        tray = new LinearLayout(activity);
        tray.setOrientation(LinearLayout.HORIZONTAL);
        for (int index = 0; index < 3; index++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(CELL_WIDTH, CELL_HEIGHT);
            params.setMarginStart(MARGIN);
            params.setMarginEnd(MARGIN);
            tray.addView(new View(activity), params);
        }
        activity.setContentView(tray);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        HookStatus.clear();
        Settings.HIDE_INBOX_ON_THIS_DAY.resetToDefault();
        owner.close();
    }

    @Test public void withTheSwitchOffTheCellKeepsItsPlace() {
        assertFalse(Settings.HIDE_INBOX_ON_THIS_DAY.defaultValue);
        View memory = tray.getChildAt(0);
        InboxFilter.onOnThisDayBound(new Cell(memory));
        layOut();
        assertEquals(View.VISIBLE, memory.getVisibility());
        assertEquals(CELL_WIDTH, memory.getWidth());
        assertEquals(MARGIN + CELL_WIDTH + MARGIN + MARGIN, tray.getChildAt(1).getLeft());
    }

    @Test public void withTheSwitchOnTheNextStoryTakesTheMemorysPlace() {
        Settings.HIDE_INBOX_ON_THIS_DAY.save(true);
        View memory = tray.getChildAt(0);
        InboxFilter.onOnThisDayBound(new Cell(memory));
        layOut();
        assertEquals(View.GONE, memory.getVisibility());
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) memory.getLayoutParams();
        assertEquals(0, params.width);
        assertEquals(0, params.height);
        assertEquals(0, params.getMarginStart());
        assertEquals(0, params.getMarginEnd());
        assertEquals("a gap was left where the memory was", MARGIN, tray.getChildAt(1).getLeft());
        assertTrue(String.join(" ", HookStatus.report()).contains("on this day"));
    }

    @Test public void turningTheSwitchOffGivesTheCellBackExactlyWhatItHad() {
        Settings.HIDE_INBOX_ON_THIS_DAY.save(true);
        View memory = tray.getChildAt(0);
        Cell cell = new Cell(memory);
        InboxFilter.onOnThisDayBound(cell);
        // Bound again while hidden, as a scroll back to the front of the tray does.
        InboxFilter.onOnThisDayBound(cell);
        Settings.HIDE_INBOX_ON_THIS_DAY.save(false);
        InboxFilter.onOnThisDayBound(cell);
        layOut();
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) memory.getLayoutParams();
        assertEquals(View.VISIBLE, memory.getVisibility());
        assertEquals(CELL_WIDTH, params.width);
        assertEquals(CELL_HEIGHT, params.height);
        assertEquals(MARGIN, params.getMarginStart());
        assertEquals(MARGIN, params.getMarginEnd());
        assertEquals(MARGIN + CELL_WIDTH + MARGIN + MARGIN, tray.getChildAt(1).getLeft());
    }

    @Test public void pausedTheMemoryIsTikToks() {
        Settings.HIDE_INBOX_ON_THIS_DAY.save(true);
        PausedProcess.set(true);
        View memory = tray.getChildAt(0);
        InboxFilter.onOnThisDayBound(new Cell(memory));
        assertEquals(View.VISIBLE, memory.getVisibility());
        assertEquals(CELL_WIDTH, memory.getLayoutParams().width);
    }

    @Test public void aCellWithNoViewIsLeftAlone() {
        Settings.HIDE_INBOX_ON_THIS_DAY.save(true);
        InboxFilter.onOnThisDayBound(new Object());
        InboxFilter.onOnThisDayBound(null);
        assertEquals(View.VISIBLE, tray.getChildAt(0).getVisibility());
    }

    @Test public void theInboxPageCarriesTheRowOnlyWhereTheBindWasHooked() {
        boolean inbox = SettingsStatus.inboxFilterEnabled;
        boolean hooked = SettingsStatus.inboxOnThisDayEnabled;
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.inboxFilterEnabled = true;
            SettingsStatus.inboxOnThisDayEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, without);
            assertNull(byTitle(without, "Hide On this day"));

            SettingsStatus.inboxOnThisDayEnabled = true;
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, with);
            Preference row = byTitle(with, "Hide On this day");
            assertNotNull(row);
            assertEquals(Settings.HIDE_INBOX_ON_THIS_DAY.key, row.getKey());
            String summary = row.getSummary().toString();
            assertFalse(summary, summary.indexOf((char) 0x2014) >= 0
                    || summary.indexOf((char) 0x2013) >= 0 || summary.contains(" - "));
        } finally {
            SettingsStatus.inboxFilterEnabled = inbox;
            SettingsStatus.inboxOnThisDayEnabled = hooked;
        }
    }

    private void layOut() {
        tray.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST));
        tray.layout(0, 0, tray.getMeasuredWidth(), tray.getMeasuredHeight());
    }

    /** The one row with this title, or null when there's none. */
    private static Preference byTitle(PreferenceGroup group, String title) {
        List<Preference> found = new ArrayList<>();
        collect(group, title, found);
        assertTrue("rows titled " + title + ": " + found.size(), found.size() <= 1);
        return found.isEmpty() ? null : found.get(0);
    }

    private static void collect(PreferenceGroup group, String title, List<Preference> found) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child.getTitle() != null && title.contentEquals(child.getTitle())) found.add(child);
            if (child instanceof PreferenceGroup) collect((PreferenceGroup) child, title, found);
        }
    }
}

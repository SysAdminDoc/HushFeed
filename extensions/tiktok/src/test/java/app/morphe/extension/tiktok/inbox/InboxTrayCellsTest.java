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
 * #449: the LIVE circles TikTok mixes into the Inbox's stories tray from accounts the viewer
 * doesn't follow, and the Widget circle at the tray's end. The patched binds hand each cell over
 * first; a hidden cell gives up all its room, and the next bind of the same view decides again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class InboxTrayCellsTest {
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

    /**
     * TikTok's LIVE item: the answer is declared on the shared base the single and the stacked
     * items extend, and the tray binds the subclass.
     */
    public static class LiveItemBase {
        private final boolean unfollow;

        LiveItemBase(boolean unfollow) {
            this.unfollow = unfollow;
        }

        public boolean isUnfollow() {
            return unfollow;
        }
    }

    public static final class LiveItem extends LiveItemBase {
        LiveItem(boolean unfollow) {
            super(unfollow);
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
        HookStatus.clear();
        Settings.HIDE_INBOX_SUGGESTED_LIVES.resetToDefault();
        Settings.HIDE_INBOX_WIDGET_GUIDE.resetToDefault();
        owner.close();
    }

    @Test public void bothSwitchesStartOff() {
        assertFalse(Settings.HIDE_INBOX_SUGGESTED_LIVES.defaultValue);
        assertFalse(Settings.HIDE_INBOX_WIDGET_GUIDE.defaultValue);
        View live = tray.getChildAt(0);
        InboxFilter.onLiveCellBound(new Cell(live), new LiveItem(true));
        View widget = tray.getChildAt(2);
        InboxFilter.onWidgetGuideBound(new Cell(widget));
        layOut();
        assertEquals(View.VISIBLE, live.getVisibility());
        assertEquals(CELL_WIDTH, live.getWidth());
        assertEquals(View.VISIBLE, widget.getVisibility());
        assertEquals(CELL_WIDTH, widget.getWidth());
    }

    @Test public void aLiveFromAnAccountYouDontFollowGivesUpItsPlace() {
        Settings.HIDE_INBOX_SUGGESTED_LIVES.save(true);
        View live = tray.getChildAt(0);
        InboxFilter.onLiveCellBound(new Cell(live), new LiveItem(true));
        layOut();
        assertCollapsed(live);
        assertEquals("a gap was left where the LIVE was", MARGIN, tray.getChildAt(1).getLeft());
        assertTrue(String.join(" ", HookStatus.report()).contains("suggested LIVEs"));
    }

    @Test public void aLiveFromAnAccountYouFollowStays() {
        Settings.HIDE_INBOX_SUGGESTED_LIVES.save(true);
        View live = tray.getChildAt(0);
        InboxFilter.onLiveCellBound(new Cell(live), new LiveItem(false));
        layOut();
        assertEquals(View.VISIBLE, live.getVisibility());
        assertEquals(CELL_WIDTH, live.getWidth());
        assertEquals(MARGIN + CELL_WIDTH + MARGIN + MARGIN, tray.getChildAt(1).getLeft());
    }

    @Test public void aRecycledCellThatNowShowsAFollowedLiveGetsItsSizeBack() {
        Settings.HIDE_INBOX_SUGGESTED_LIVES.save(true);
        View live = tray.getChildAt(0);
        Cell cell = new Cell(live);
        InboxFilter.onLiveCellBound(cell, new LiveItem(true));
        InboxFilter.onLiveCellBound(cell, new LiveItem(false));
        layOut();
        assertRestored(live);
    }

    @Test public void anItemWithoutTikToksAnswerIsLeftShowing() {
        Settings.HIDE_INBOX_SUGGESTED_LIVES.save(true);
        View live = tray.getChildAt(0);
        InboxFilter.onLiveCellBound(new Cell(live), new Object());
        InboxFilter.onLiveCellBound(new Cell(live), null);
        InboxFilter.onLiveCellBound(new Object(), new LiveItem(true));
        InboxFilter.onLiveCellBound(null, new LiveItem(true));
        layOut();
        assertEquals(View.VISIBLE, live.getVisibility());
        assertEquals(CELL_WIDTH, live.getWidth());
    }

    @Test public void theWidgetCircleGoesAndComesBack() {
        Settings.HIDE_INBOX_WIDGET_GUIDE.save(true);
        View widget = tray.getChildAt(2);
        Cell cell = new Cell(widget);
        InboxFilter.onWidgetGuideBound(cell);
        layOut();
        assertCollapsed(widget);
        assertTrue(String.join(" ", HookStatus.report()).contains("widget circle"));

        Settings.HIDE_INBOX_WIDGET_GUIDE.save(false);
        InboxFilter.onWidgetGuideBound(cell);
        layOut();
        assertRestored(widget);
    }

    @Test public void theInboxPageCarriesEachRowOnlyWhereItsBindWasHooked() {
        boolean inbox = SettingsStatus.inboxFilterEnabled;
        boolean lives = SettingsStatus.inboxSuggestedLivesEnabled;
        boolean widget = SettingsStatus.inboxWidgetGuideEnabled;
        String livesTitle = "Hide LIVEs from accounts you don't follow";
        String widgetTitle = "Hide the Widget circle";
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.inboxFilterEnabled = true;
            SettingsStatus.inboxSuggestedLivesEnabled = false;
            SettingsStatus.inboxWidgetGuideEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, without);
            assertNull(byTitle(without, livesTitle));
            assertNull(byTitle(without, widgetTitle));

            SettingsStatus.inboxSuggestedLivesEnabled = true;
            SettingsStatus.inboxWidgetGuideEnabled = true;
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, with);
            Preference livesRow = byTitle(with, livesTitle);
            assertNotNull(livesRow);
            assertEquals(Settings.HIDE_INBOX_SUGGESTED_LIVES.key, livesRow.getKey());
            Preference widgetRow = byTitle(with, widgetTitle);
            assertNotNull(widgetRow);
            assertEquals(Settings.HIDE_INBOX_WIDGET_GUIDE.key, widgetRow.getKey());
        } finally {
            SettingsStatus.inboxFilterEnabled = inbox;
            SettingsStatus.inboxSuggestedLivesEnabled = lives;
            SettingsStatus.inboxWidgetGuideEnabled = widget;
        }
    }

    private static void assertCollapsed(View cell) {
        assertEquals(View.GONE, cell.getVisibility());
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) cell.getLayoutParams();
        assertEquals(0, params.width);
        assertEquals(0, params.height);
        assertEquals(0, params.getMarginStart());
        assertEquals(0, params.getMarginEnd());
    }

    private static void assertRestored(View cell) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) cell.getLayoutParams();
        assertEquals(View.VISIBLE, cell.getVisibility());
        assertEquals(CELL_WIDTH, params.width);
        assertEquals(CELL_HEIGHT, params.height);
        assertEquals(MARGIN, params.getMarginStart());
        assertEquals(MARGIN, params.getMarginEnd());
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

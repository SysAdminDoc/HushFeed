/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.TextView;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Jump to a date on Liked grids (#350): the Liked list's first request starts at the chosen date
 * for the profile it first went to and nowhere else, later pages and a switched-off or paused
 * Hushfeed go as TikTok asked, the dates and names the picker uses, and the chip that picks a year
 * and a month, names the grid's jump and goes back to the newest likes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class LikedJumpTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private TimeZone zone;
    private boolean gridSortEnabled;
    private boolean likedJumpEnabled;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        zone = TimeZone.getDefault();
        gridSortEnabled = SettingsStatus.gridSortEnabled;
        likedJumpEnabled = SettingsStatus.likedJumpEnabled;
    }

    @After public void tearDown() {
        TimeZone.setDefault(zone);
        setPaused(false);
        Settings.LIKED_JUMP_TO_DATE.resetToDefault();
        Settings.PROFILE_GRID_SORT.resetToDefault();
        Settings.LIKED_GRID_SORT.resetToDefault();
        SettingsStatus.gridSortEnabled = gridSortEnabled;
        SettingsStatus.likedJumpEnabled = likedJumpEnabled;
        SettingsStatus.alwaysShowPublishDateEnabled = false;
        HookStatus.clear();
        LikedJump.resetForTests();
        GridSort.resetForTests();
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }

    private static void idle(long millis) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private static long localMidnight(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Test public void theSwitchIsOffByDefault() {
        assertEquals("liked_jump_to_date", Settings.LIKED_JUMP_TO_DATE.key);
        assertFalse(Settings.LIKED_JUMP_TO_DATE.defaultValue);
    }

    @Test public void tiktoksCursorGoesOutWhileTheSwitchIsOff() {
        LikedJump.choose(1_650_000_000_000L, "Apr 2022");
        assertEquals(0L, LikedJump.cursor("1", "MS4", 0, 0L));
        assertEquals(1_600_000_000_000L, LikedJump.cursor("1", "MS4", 0, 1_600_000_000_000L));
    }

    @Test public void aFreshListStartsAtTheJumpAndLaterPagesGoOnFromTikToksCursor() {
        Settings.LIKED_JUMP_TO_DATE.save(true);
        assertEquals("no jump chosen, TikTok's own start", 0L, LikedJump.cursor("1", "MS4", 0, 0L));

        LikedJump.choose(1_650_000_000_000L, "Apr 2022");
        assertTrue(LikedJump.chosen());
        assertEquals(1_650_000_000_000L, LikedJump.cursor("1", "MS4", 0, 0L));
        assertEquals("a later page goes on from the cursor the server sent",
                1_640_000_000_000L, LikedJump.cursor("1", "MS4", 0, 1_640_000_000_000L));
        assertEquals("a pull to refresh stays at the jump", 1_650_000_000_000L, LikedJump.cursor("1", "MS4", 0, 0L));

        LikedJump.clear();
        assertFalse(LikedJump.chosen());
        assertEquals(0L, LikedJump.cursor("1", "MS4", 0, 0L));
    }

    @Test public void theJumpStaysWithTheProfileItFirstWentTo() {
        Settings.LIKED_JUMP_TO_DATE.save(true);
        LikedJump.choose(1_650_000_000_000L, "Apr 2022");
        assertEquals(1_650_000_000_000L, LikedJump.cursor("", "MS4mine", 0, 0L));
        assertEquals("another profile's Liked tab starts at its newest", 0L, LikedJump.cursor("2", "MS4other", 0, 0L));
        assertEquals(1_650_000_000_000L, LikedJump.cursor(null, "MS4mine", 0, 0L));

        LikedJump.choose(1_500_000_000_000L, "Jul 2017");
        assertEquals("a new jump goes to whichever profile asks first",
                1_500_000_000_000L, LikedJump.cursor("2", "MS4other", 0, 0L));
        assertEquals(0L, LikedJump.cursor("", "MS4mine", 0, 0L));
    }

    @Test public void pausedHushfeedSendsTikToksCursor() {
        Settings.LIKED_JUMP_TO_DATE.save(true);
        LikedJump.choose(1_650_000_000_000L, "Apr 2022");
        setPaused(true);
        assertEquals(0L, LikedJump.cursor("1", "MS4", 0, 0L));
    }

    @Test public void aMonthStartsAtTheFirstMomentOfTheNextOneInThePhonesTimeZone() {
        for (String id : new String[] {"America/New_York", "Asia/Kolkata", "UTC"}) {
            TimeZone.setDefault(TimeZone.getTimeZone(id));
            assertEquals(id, localMidnight(2022, 4, 1), LikedJump.startOf(2022, Calendar.MARCH));
            assertEquals(id + ": December runs into January", localMidnight(2023, 1, 1),
                    LikedJump.startOf(2022, Calendar.DECEMBER));
            assertEquals(id + ": the whole year", localMidnight(2023, 1, 1), LikedJump.startOf(2022, -1));
            assertEquals(id + ": February in a leap year", localMidnight(2024, 3, 1),
                    LikedJump.startOf(2024, Calendar.FEBRUARY));
        }
    }

    @Test public void theChipNamesAMonthShortAndAYearAlone() {
        assertEquals("Mar 2022", LikedJump.nameOf(2022, Calendar.MARCH, Locale.US));
        assertEquals("2022", LikedJump.nameOf(2022, -1, Locale.US));
        assertTrue(LikedJump.nameOf(2019, Calendar.DECEMBER, Locale.GERMANY).endsWith(" 2019"));
    }

    @Test public void thePickerListsTheYearsNewestFirstAndOnlyTheMonthsBegun() {
        List<Integer> years = LikedJump.years(2026);
        assertEquals(Integer.valueOf(2026), years.get(0));
        assertEquals(Integer.valueOf(LikedJump.FIRST_YEAR), years.get(years.size() - 1));
        assertEquals(2026 - LikedJump.FIRST_YEAR + 1, years.size());
        assertEquals(12, LikedJump.monthsOf(2025, 2026, Calendar.NOVEMBER));
        assertEquals(11, LikedJump.monthsOf(2026, 2026, Calendar.NOVEMBER));
        assertEquals(1, LikedJump.monthsOf(2026, 2026, Calendar.JANUARY));
    }

    /** A grid on screen in its own FrameLayout, as TikTok's profile page holds it. */
    private static GridSortTest.GridAdapter gridOnScreen(Activity activity, int tab) {
        FrameLayout frame = new FrameLayout(activity);
        View grid = new View(activity);
        frame.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activity.setContentView(frame);
        idle(50);
        GridSortTest.GridAdapter adapter = new GridSortTest.GridAdapter();
        adapter.mmRecyclerView = grid;
        GridSort.tab(adapter, tab);
        return adapter;
    }

    private static List<Aweme> likes(int count) {
        List<Aweme> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(new GridSortTest.Post("v" + i, 1_000 + i, 1_000, 1, 1, false));
        return out;
    }

    private static TextView chip(GridSortTest.GridAdapter adapter) {
        FrameLayout frame = (FrameLayout) adapter.mmRecyclerView.getParent();
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (LikedJump.CHIP_TAG.equals(child.getTag())) return (TextView) child;
        }
        return null;
    }

    private static List<String> items(AlertDialog dialog) {
        ListAdapter list = dialog.getListView().getAdapter();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.getCount(); i++) out.add(String.valueOf(list.getItem(i)));
        return out;
    }

    private static AlertDialog open(TextView chip) {
        assertTrue(chip.performClick());
        idle(0);
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    @Test public void theChipPicksAYearAndAMonthAndNamesTheGridsJump() {
        Settings.LIKED_JUMP_TO_DATE.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            adapter.mmItems = GridSort.data(adapter, likes(9));
            idle(0);
            TextView control = chip(adapter);
            assertNotNull("a Liked grid gets the chip", control);
            assertEquals("Jump to a date", String.valueOf(control.getText()));
            FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) control.getLayoutParams();
            assertEquals("the start corner, the sort chip has the end", Gravity.TOP | Gravity.START, layout.gravity);

            int thisYear = Calendar.getInstance().get(Calendar.YEAR);
            AlertDialog years = open(control);
            List<String> yearItems = items(years);
            assertEquals("no Newest likes before a jump", String.valueOf(thisYear), yearItems.get(0));
            assertEquals(String.valueOf(LikedJump.FIRST_YEAR), yearItems.get(yearItems.size() - 1));
            Shadows.shadowOf(years).clickOnItem(thisYear - 2022);
            idle(0);
            assertFalse(years.isShowing());

            AlertDialog months = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(months.isShowing());
            List<String> monthItems = items(months);
            assertEquals(13, monthItems.size());
            assertEquals("Whole year", monthItems.get(0));
            assertEquals("January", monthItems.get(1));
            assertEquals("December", monthItems.get(12));
            Shadows.shadowOf(months).clickOnItem(1 + Calendar.MARCH);
            idle(0);

            assertTrue(LikedJump.chosen());
            assertEquals("the unpatched stub can't refresh, so the grid asks for a pull",
                    "Pull down on the grid to load them", ShadowToast.getTextOfLatestToast());
            assertEquals(LikedJump.startOf(2022, Calendar.MARCH), LikedJump.cursor("1", "MS4", 0, 0L));
            adapter.mmItems = GridSort.data(adapter, likes(4));
            idle(0);
            assertEquals("Up to Mar 2022", String.valueOf(chip(adapter).getText()));
            assertEquals("Jump to a date", String.valueOf(chip(adapter).getContentDescription()));
            adapter.mmItems = GridSort.page(adapter, likes(8));
            idle(0);
            assertEquals("a later page keeps the name", "Up to Mar 2022", String.valueOf(chip(adapter).getText()));

            AlertDialog back = open(chip(adapter));
            assertEquals("Newest likes", items(back).get(0));
            assertEquals(String.valueOf(thisYear), items(back).get(1));
            Shadows.shadowOf(back).clickOnItem(0);
            idle(0);
            assertFalse(LikedJump.chosen());
            assertEquals(0L, LikedJump.cursor("1", "MS4", 0, 0L));
            adapter.mmItems = GridSort.data(adapter, likes(3));
            idle(0);
            assertEquals("Jump to a date", String.valueOf(chip(adapter).getText()));
        }
    }

    @Test public void theWholeYearStartsAtTheNextJanuary() {
        Settings.LIKED_JUMP_TO_DATE.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            adapter.mmItems = GridSort.data(adapter, likes(9));
            idle(0);
            int thisYear = Calendar.getInstance().get(Calendar.YEAR);
            AlertDialog years = open(chip(adapter));
            Shadows.shadowOf(years).clickOnItem(thisYear - 2019);
            idle(0);
            AlertDialog months = (AlertDialog) ShadowDialog.getLatestDialog();
            Shadows.shadowOf(months).clickOnItem(0);
            idle(0);
            assertEquals(localMidnight(2020, 1, 1), LikedJump.cursor("1", "MS4", 0, 0L));
            adapter.mmItems = GridSort.data(adapter, likes(2));
            idle(0);
            assertEquals("Up to 2019", String.valueOf(chip(adapter).getText()));
        }
    }

    @Test public void onlyALikedGridGetsTheChipAndOnlyWhileTheSwitchIsOn() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter liked = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            liked.mmItems = GridSort.data(liked, likes(9));
            idle(0);
            assertNull("off by default", chip(liked));

            Settings.LIKED_JUMP_TO_DATE.save(true);
            liked.mmItems = GridSort.data(liked, likes(5));
            idle(0);
            assertNotNull(chip(liked));
            Settings.LIKED_JUMP_TO_DATE.save(false);
            liked.mmItems = GridSort.data(liked, likes(4));
            idle(0);
            assertNull("switched off, the chip goes", chip(liked));

            Settings.LIKED_JUMP_TO_DATE.save(true);
            GridSortTest.GridAdapter posts = gridOnScreen(controller.get(), GridSort.TAB_POSTS);
            posts.mmItems = GridSort.data(posts, likes(9));
            idle(0);
            assertNull("Posts has no like times to jump through", chip(posts));
        }
    }

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Test public void theRowShowsOnlyWhereTheRequestWasHooked() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.alwaysShowPublishDateEnabled = true;
            SettingsStatus.gridSortEnabled = true;

            SettingsStatus.likedJumpEnabled = false;
            assertNull(build(activity).findPreference(Settings.LIKED_JUMP_TO_DATE.key));

            SettingsStatus.likedJumpEnabled = true;
            Preference row = build(activity).findPreference(Settings.LIKED_JUMP_TO_DATE.key);
            assertNotNull(row);
            assertEquals("Jump to a date on Liked grids", String.valueOf(row.getTitle()));
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("year or month") && summary.contains("Newest likes"));
        }
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new InterfacePreferenceCategory(activity, screen);
        return screen;
    }
}

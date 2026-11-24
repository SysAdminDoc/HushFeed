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
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.TextView;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

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
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Jump to a date on Favorites grids (#508): only a fresh Favorites list (tab 4) starts at the
 * chosen date, for the profile it first went to, while every other tab, a later page and a
 * switched-off or paused Hushfeed go as TikTok asked. The Favorites jump and the Liked one are
 * kept apart, and the chip and the settings row are the Favorites grid's own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class FavoritesJumpTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long APRIL_2022 = 1_650_000_000_000L;
    /** The Favorites list's cursor is a save time in seconds. */
    private static final long APRIL_2022_SENT = 1_650_000_000L;

    private boolean gridSortEnabled;
    private boolean favoritesJumpEnabled;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        gridSortEnabled = SettingsStatus.gridSortEnabled;
        favoritesJumpEnabled = SettingsStatus.favoritesJumpEnabled;
    }

    @After public void tearDown() {
        setPaused(false);
        Settings.FAVORITES_JUMP_TO_DATE.resetToDefault();
        Settings.LIKED_JUMP_TO_DATE.resetToDefault();
        SettingsStatus.gridSortEnabled = gridSortEnabled;
        SettingsStatus.favoritesJumpEnabled = favoritesJumpEnabled;
        SettingsStatus.alwaysShowPublishDateEnabled = false;
        SettingsStatus.publishDateGridsEnabled = false;
        HookStatus.clear();
        FavoritesJump.resetForTests();
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

    @Test public void theSwitchIsOffByDefault() {
        assertEquals("favorites_jump_to_date", Settings.FAVORITES_JUMP_TO_DATE.key);
        assertFalse(Settings.FAVORITES_JUMP_TO_DATE.defaultValue);
    }

    @Test public void onlyAFreshFavoritesListStartsAtTheJump() {
        FavoritesJump.JUMP.choose(APRIL_2022, "Apr 2022");
        assertEquals("switched off, TikTok's own start", 0L, FavoritesJump.cursor("1", "MS4", 4, 0L, 0L));

        Settings.FAVORITES_JUMP_TO_DATE.save(true);
        assertEquals(APRIL_2022_SENT, FavoritesJump.cursor("1", "MS4", GridSort.TAB_FAVORITES, 0L, 0L));
        assertEquals("a later page goes on from the cursor the server sent",
                1_640_000_000L, FavoritesJump.cursor("1", "MS4", 4, 0L, 1_640_000_000L));
        assertEquals("a pull to refresh stays at the jump", APRIL_2022_SENT, FavoritesJump.cursor("1", "MS4", 4, -1L, 0L));
        for (int tab : new int[] {GridSort.TAB_POSTS, GridSort.TAB_LIKED, GridSort.TAB_PRIVATE,
                GridSort.TAB_FAVORITE_VIDEOS, 11, 24}) {
            assertEquals("tab " + tab + " goes as TikTok asked", 0L, FavoritesJump.cursor("1", "MS4", tab, 0L, 0L));
        }

        FavoritesJump.JUMP.clear();
        assertEquals(0L, FavoritesJump.cursor("1", "MS4", 4, 0L, 0L));
    }

    @Test public void theJumpStaysWithTheProfileItFirstWentTo() {
        Settings.FAVORITES_JUMP_TO_DATE.save(true);
        FavoritesJump.JUMP.choose(APRIL_2022, "Apr 2022");
        assertEquals(APRIL_2022_SENT, FavoritesJump.cursor("7", "MS4mine", 4, 0L, 0L));
        assertEquals("another profile's Favorites start at its newest", 0L, FavoritesJump.cursor("8", "MS4other", 4, 0L, 0L));
        assertEquals("an empty user id falls back to the sec id", APRIL_2022_SENT, FavoritesJump.cursor("", "7", 4, 0L, 0L));
    }

    @Test public void pausedHushfeedSendsTikToksCursor() {
        Settings.FAVORITES_JUMP_TO_DATE.save(true);
        FavoritesJump.JUMP.choose(APRIL_2022, "Apr 2022");
        setPaused(true);
        assertEquals(0L, FavoritesJump.cursor("1", "MS4", 4, 0L, 0L));
    }

    @Test public void theFavoritesAndLikedJumpsAreKeptApart() {
        Settings.FAVORITES_JUMP_TO_DATE.save(true);
        Settings.LIKED_JUMP_TO_DATE.save(true);
        FavoritesJump.JUMP.choose(APRIL_2022, "Apr 2022");
        assertFalse(LikedJump.chosen());
        assertEquals("Liked starts at its newest", 0L, LikedJump.cursor("1", "MS4", 0, 0L));
        assertEquals(APRIL_2022_SENT, FavoritesJump.cursor("1", "MS4", 4, 0L, 0L));

        LikedJump.choose(1_500_000_000_000L, "Jul 2017");
        assertEquals("Liked keeps milliseconds", 1_500_000_000_000L, LikedJump.cursor("1", "MS4", 0, 0L));
        assertEquals(APRIL_2022_SENT, FavoritesJump.cursor("1", "MS4", 4, 0L, 0L));
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

    private static List<Aweme> saves(int count) {
        List<Aweme> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(new GridSortTest.Post("v" + i, 1_000 + i, 1_000, 1, 1, false));
        return out;
    }

    private static TextView chip(GridSortTest.GridAdapter adapter, String tag) {
        FrameLayout frame = (FrameLayout) adapter.mmRecyclerView.getParent();
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (tag.equals(child.getTag())) return (TextView) child;
        }
        return null;
    }

    private static List<String> items(AlertDialog dialog) {
        ListAdapter list = dialog.getListView().getAdapter();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < list.getCount(); i++) out.add(String.valueOf(list.getItem(i)));
        return out;
    }

    @Test public void aFavoritesGridGetsItsOwnChipAndGoesBackToTheNewestSaves() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter favorites = gridOnScreen(controller.get(), GridSort.TAB_FAVORITES);
            favorites.mmItems = GridSort.data(favorites, saves(9));
            idle(0);
            assertNull("off by default", chip(favorites, FavoritesJump.CHIP_TAG));

            Settings.FAVORITES_JUMP_TO_DATE.save(true);
            favorites.mmItems = GridSort.data(favorites, saves(6));
            idle(0);
            TextView control = chip(favorites, FavoritesJump.CHIP_TAG);
            assertNotNull(control);
            assertNull("the Liked chip stays off a Favorites grid", chip(favorites, LikedJump.CHIP_TAG));
            assertEquals("Jump to a date", String.valueOf(control.getText()));

            int thisYear = Calendar.getInstance().get(Calendar.YEAR);
            assertTrue(control.performClick());
            idle(0);
            AlertDialog years = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals(String.valueOf(thisYear), items(years).get(0));
            Shadows.shadowOf(years).clickOnItem(thisYear - 2022);
            idle(0);
            AlertDialog months = (AlertDialog) ShadowDialog.getLatestDialog();
            Shadows.shadowOf(months).clickOnItem(1 + Calendar.MARCH);
            idle(0);
            assertEquals(DateJump.startOf(2022, Calendar.MARCH) / 1000, FavoritesJump.cursor("1", "MS4", 4, 0L, 0L));
            assertFalse("the Liked jump stays where it was", LikedJump.chosen());
            favorites.mmItems = GridSort.data(favorites, saves(4));
            idle(0);
            assertEquals("Up to Mar 2022", String.valueOf(chip(favorites, FavoritesJump.CHIP_TAG).getText()));

            assertTrue(chip(favorites, FavoritesJump.CHIP_TAG).performClick());
            idle(0);
            AlertDialog back = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals("Newest saves", items(back).get(0));
            Shadows.shadowOf(back).clickOnItem(0);
            idle(0);
            assertEquals(0L, FavoritesJump.cursor("1", "MS4", 4, 0L, 0L));

            Settings.LIKED_JUMP_TO_DATE.save(true);
            GridSortTest.GridAdapter liked = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            liked.mmItems = GridSort.data(liked, saves(9));
            idle(0);
            assertNull("a Liked grid keeps its own chip", chip(liked, FavoritesJump.CHIP_TAG));
            assertNotNull(chip(liked, LikedJump.CHIP_TAG));
        }
    }

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Test public void theRowShowsOnlyWhereTheAddressWasHooked() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.alwaysShowPublishDateEnabled = true;
            SettingsStatus.publishDateGridsEnabled = true;
            SettingsStatus.gridSortEnabled = true;

            SettingsStatus.favoritesJumpEnabled = false;
            assertNull(build(activity).findPreference(Settings.FAVORITES_JUMP_TO_DATE.key));

            SettingsStatus.favoritesJumpEnabled = true;
            Preference row = build(activity).findPreference(Settings.FAVORITES_JUMP_TO_DATE.key);
            assertNotNull(row);
            assertEquals("Jump to a date on Favorites grids", String.valueOf(row.getTitle()));
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("year or month") && summary.contains("Newest saves"));
        }
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new InterfacePreferenceCategory(activity, screen);
        return screen;
    }
}

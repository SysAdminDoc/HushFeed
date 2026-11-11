/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;
import com.ss.android.ugc.aweme.feed.model.PhotoModeImageInfo;
import com.ss.android.ugc.aweme.feed.model.PhotoModeTextInfo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
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
 * Search on Liked and Favorites grids: the loaded videos narrow to the ones whose caption, creator
 * or sound holds every word, in any case; TikTok keeps its whole list for paging and the video
 * page; a cleared search, a switch turned off or a pause gives the sorted grid back; and the chip
 * and its box only show on those tabs while the switch is on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class GridSearchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private boolean gridSortEnabled;
    private boolean likedGridSortEnabled;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        gridSortEnabled = SettingsStatus.gridSortEnabled;
        likedGridSortEnabled = SettingsStatus.likedGridSortEnabled;
    }

    @After public void tearDown() {
        setPaused(false);
        Settings.GRID_SEARCH.resetToDefault();
        Settings.PROFILE_GRID_SORT.resetToDefault();
        Settings.LIKED_GRID_SORT.resetToDefault();
        SettingsStatus.gridSortEnabled = gridSortEnabled;
        SettingsStatus.likedGridSortEnabled = likedGridSortEnabled;
        SettingsStatus.alwaysShowPublishDateEnabled = false;
        HookStatus.clear();
        GridSearch.resetForTests();
        GridSort.resetForTests();
    }

    public static final class Creator {
        final String nickname;
        final String handle;
        Creator(String nickname, String handle) { this.nickname = nickname; this.handle = handle; }
        public String getNickname() { return nickname; }
        public String getUniqueId() { return handle; }
    }

    public static final class Sound {
        final String name;
        final String author;
        Sound(String name, String author) { this.name = name; this.author = author; }
        public String getMusicName() { return name; }
        public String getAuthorName() { return author; }
    }

    /** A liked video with a caption, a creator and a sound, posted at {@code created}. */
    public static final class Liked extends Aweme {
        final String id;
        final long created;
        String desc;
        final Creator creator;
        final Sound sound;
        int descReads;

        Liked(String id, long created, String desc, Creator creator, Sound sound) {
            this.id = id;
            this.created = created;
            this.desc = desc;
            this.creator = creator;
            this.sound = sound;
        }

        public String getDesc() {
            descReads++;
            return desc;
        }
        public Creator getAuthor() { return creator; }
        public Sound getMusic() { return sound; }
        public Object getVideo() { return new GridSortTest.Clip(1_000); }
        public int getIsTop() { return 0; }
        @Override public String getAid() { return id; }
        @Override public long getCreateTime() { return created; }
        @Override public AwemeStatistics getStatistics() { return new GridSortTest.Counts(1, 1); }
        @Override public List getImageInfos() { return null; }
        @Override public PhotoModeImageInfo getPhotoModeImageInfo() { return null; }
        @Override public PhotoModeTextInfo getPhotoModeTextInfo() { return null; }
        @Override public String toString() { return id; }
    }

    /** a and d have cats, b is a cat creator's, c has a cat sound, e and f have none. */
    private static List<Aweme> library() {
        return new ArrayList<>(Arrays.asList(
                new Liked("a", 100, "My CAT dancing #fyp", new Creator("Sam", "sam1"), new Sound("original sound", "Sam")),
                new Liked("b", 200, "morning walk", new Creator("Cat Lady", "felinefan"), new Sound("Lo-fi Beats", "DJ Ro")),
                new Liked("c", 300, "rain on the window", new Creator("Rae", "rae.w"), new Sound("Stray Cat Strut", "Stray Cats")),
                new Liked("d", 400, "two cats, one box", new Creator("Ola", "ola_k"), new Sound("box song", "Ola")),
                new Liked("e", 500, "pasta night", new Creator("Ben", "benny"), new Sound("Dinner Jazz", "Trio")),
                new Liked("f", 600, null, null, null)));
    }

    private static String ids(List<?> list) {
        StringBuilder out = new StringBuilder();
        for (Object item : list) out.append(item);
        return out.toString();
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }

    private static void idle(long millis) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
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

    private static TextView chip(GridSortTest.GridAdapter adapter) {
        FrameLayout frame = (FrameLayout) adapter.mmRecyclerView.getParent();
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (GridSearch.CHIP_TAG.equals(child.getTag())) return (TextView) child;
        }
        return null;
    }

    private static <T extends View> T find(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            T found = find(group.getChildAt(i), type);
            if (found != null) return found;
        }
        return null;
    }

    @Test public void theSwitchIsOffByDefault() {
        assertEquals("grid_search", Settings.GRID_SEARCH.key);
        assertFalse(Settings.GRID_SEARCH.defaultValue);
    }

    @Test public void everyWordHasToBeInTheCaptionCreatorOrSoundInAnyCase() {
        List<Aweme> all = library();
        assertEquals("caption, creator name and sound name", "abcd", ids(GridSearch.matching(all, "cat")));
        assertEquals("abcd", ids(GridSearch.matching(all, "  CAT ")));
        assertEquals("every word, wherever it is", "c", ids(GridSearch.matching(all, "cat rain")));
        assertEquals("a handle", "b", ids(GridSearch.matching(all, "FELINEFAN")));
        assertEquals("a sound's author", "e", ids(GridSearch.matching(all, "trio")));
        assertEquals("a hashtag", "a", ids(GridSearch.matching(all, "#fyp")));
        assertEquals("", ids(GridSearch.matching(all, "dog")));
        assertSame("a blank search leaves the list alone", all, GridSearch.matching(all, " \t "));
        assertSame(all, GridSearch.matching(all, null));
    }

    @Test public void aSearchFoldsCaseLikeTheWordListsAndSplitsOnAnySpace() {
        String dottedCapitalI = String.valueOf((char) 0x130);
        List<Aweme> trip = new ArrayList<>(Arrays.asList(
                new Liked("t", 1, dottedCapitalI + "stanbul gezisi", null, null),
                new Liked("u", 2, "istanbul trip", null, null)));
        assertEquals("a Turkish capital dotted I", "tu", ids(GridSearch.matching(trip, "istanbul")));
        assertEquals("tu", ids(GridSearch.matching(trip, dottedCapitalI + "STANBUL")));
        char dotlessI = (char) 0x131;
        List<Aweme> water = new ArrayList<>(Arrays.asList(
                new Liked("w", 1, "" + dotlessI + "l" + dotlessI + "k su", null, null)));
        assertEquals("a dotless i", "w", ids(GridSearch.matching(water, "ILIK")));
        String finalSigma = "" + (char) 0x3B8 + (char) 0x3B5 + (char) 0x3BF + (char) 0x3C2;
        String capitals = "" + (char) 0x398 + (char) 0x395 + (char) 0x39F + (char) 0x3A3;
        List<Aweme> greek = new ArrayList<>(Arrays.asList(new Liked("g", 1, capitals + "THS", null, null)));
        assertEquals("a final sigma typed against a capital one inside a word", "g",
                ids(GridSearch.matching(greek, finalSigma)));

        List<Aweme> all = library();
        assertEquals("an ideographic space", "d", ids(GridSearch.matching(all, "cat" + (char) 0x3000 + "box")));
        assertEquals("a no-break space", "d", ids(GridSearch.matching(all, "box" + (char) 0xA0 + "cat")));
        assertSame("spaces alone are still blank", all, GridSearch.matching(all, (char) 0x3000 + " "));
    }

    @Test public void aNarrowedGridPlaysItsMatchesFirstThenTheRest() {
        List<Aweme> all = library();
        List<?> matches = GridSearch.matching(all, "cat");
        assertEquals("abcdef", ids(GridSearch.withRest(matches, all)));
        assertEquals("dbacef", ids(GridSearch.withRest(Arrays.asList(all.get(3), all.get(1), all.get(0)), all)));
    }

    @Test public void fiveHundredLikesAnswerInUnder200Milliseconds() {
        List<Aweme> many = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            many.add(new Liked("v" + i, i, "clip number " + i + (i % 7 == 0 ? " with a cat" : ""),
                    new Creator("Creator " + i, "handle" + i), new Sound("sound " + i, "artist " + (i % 13))));
        }
        GridSearch.matching(many.subList(0, 1), "warm");
        long start = System.nanoTime();
        List<?> found = GridSearch.matching(many, "cat clip");
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(86, found.size());
        assertTrue(millis + " ms", millis < 200);
    }

    @Test public void onlyLikedAndFavoritesGridsTakeASearch() {
        Object liked = new Object();
        Object favorites = new Object();
        Object favoriteVideos = new Object();
        Object posts = new Object();
        Object sound = new Object();
        GridSort.tab(liked, GridSort.TAB_LIKED);
        GridSort.tab(favorites, GridSort.TAB_FAVORITES);
        GridSort.tab(favoriteVideos, GridSort.TAB_FAVORITE_VIDEOS);
        GridSort.tab(posts, GridSort.TAB_POSTS);
        GridSort.soundGrid(sound, GridSort.SOUND_LABEL);
        assertTrue(GridSearch.searchable(liked));
        assertTrue(GridSearch.searchable(favorites));
        assertTrue(GridSearch.searchable(favoriteVideos));
        assertFalse(GridSearch.searchable(posts));
        assertFalse(GridSearch.searchable(sound));
        assertFalse("a tab that never came in", GridSearch.searchable(new Object()));
    }

    @Test public void aSearchNarrowsTheGridAndClearingItGivesBackTheSortedList() {
        Settings.GRID_SEARCH.save(true);
        Settings.LIKED_GRID_SORT.save("newest");
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            List<Aweme> model = library();
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            assertEquals("fedcba", ids(adapter.mmItems));
            int refreshes = adapter.refreshes;

            GridSearch.search(adapter, "Cat");
            assertTrue(GridSearch.searching(adapter));
            assertEquals("in the grid's order", "dcba", ids(adapter.mmItems));
            assertTrue("the grid is told", adapter.refreshes > refreshes);
            assertEquals("TikTok's list is left whole", "abcdef", ids(model));

            GridSearch.search(adapter, "cat box");
            assertEquals("d", ids(adapter.mmItems));

            GridSearch.search(adapter, "  ");
            assertFalse(GridSearch.searching(adapter));
            assertEquals("fedcba", ids(adapter.mmItems));

            GridSearch.search(adapter, "cat");
            GridSearch.search(adapter, "" + (char) 0x3000 + (char) 0xA0);
            assertFalse("only ideographic and no-break spaces are blank too", GridSearch.searching(adapter));
            assertNull(GridSearch.queryOf(adapter));
            assertEquals("fedcba", ids(adapter.mmItems));
        }
    }

    @Test public void aVideoReadBeforeTikTokFilledItInIsReadAgain() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            List<Aweme> model = library();
            // Its creator and sound are known before the caption is.
            Liked late = new Liked("g", 700, null, new Creator("Gus", "gus"), new Sound("drums", "Gus"));
            model.add(late);
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            GridSearch.search(adapter, "cat");
            assertEquals("abcd", ids(adapter.mmItems));

            late.desc = "cat nap";
            model.add(new Liked("h", 800, "bread", new Creator("Ivy", "ivy"), new Sound("hum", "Ivy")));
            adapter.mmItems = GridSort.page(adapter, model);
            idle(0);
            assertEquals("abcdg", ids(adapter.mmItems));
        }
    }

    @Test public void aListTikTokChangedSinceTheLastOpenStillPlaysTheMatchesFirst() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            List<Aweme> model = library();
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            GridSearch.search(adapter, "box");
            GridSort.playInGridOrder(adapter, model);
            assertEquals("dabcef", ids(model));

            // TikTok puts a video at the front of its own list without a page coming through.
            model.add(0, new Liked("g", 700, "bread", new Creator("Ivy", "ivy"), new Sound("hum", "Ivy")));
            GridSort.playInGridOrder(adapter, model);
            assertEquals("the cell still opens its own video", "dgabcef", ids(model));
        }
    }

    @Test public void aListThatLostAMatchTheGridStillShowsPlaysTheOtherMatchesFirst() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            List<Aweme> model = library();
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            GridSearch.search(adapter, "cat");
            assertEquals("abcd", ids(adapter.mmItems));

            // TikTok drops a match and puts a new video at the front without a page coming
            // through, so the grid still shows the one it dropped.
            model.remove(1);
            model.add(0, new Liked("g", 700, "bread", new Creator("Ivy", "ivy"), new Sound("hum", "Ivy")));
            GridSort.playInGridOrder(adapter, model);
            assertEquals("acdgef", ids(model));
        }
    }

    @Test public void aChangedListAsLongAsTheMatchesStillPlaysThemFirst() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            List<Aweme> model = library();
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            GridSearch.search(adapter, "cat");
            assertEquals("abcd", ids(adapter.mmItems));

            // TikTok drops a match and both videos that didn't match, and gains one, so its list
            // holds as many videos as the grid shows without holding the same ones.
            model.remove(5);
            model.remove(4);
            model.remove(1);
            model.add(0, new Liked("g", 700, "bread", new Creator("Ivy", "ivy"), new Sound("hum", "Ivy")));
            assertEquals("gacd", ids(model));
            GridSort.playInGridOrder(adapter, model);
            assertEquals("acdg", ids(model));
        }
    }

    @Test public void aPageWhileSearchingGoesToTikTokWholeThenNarrows() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_FAVORITES);
            List<Aweme> model = library();
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            GridSearch.search(adapter, "cat");
            assertEquals("abcd", ids(adapter.mmItems));

            model.add(new Liked("g", 700, "cat nap", new Creator("Ivy", "ivy"), new Sound("hum", "Ivy")));
            model.add(new Liked("h", 800, "bread", new Creator("Ivy", "ivy"), new Sound("hum", "Ivy")));
            List<?> handed = GridSort.page(adapter, model);
            assertEquals("TikTok counts the new cells from the whole list", 8, handed.size());
            adapter.mmItems = handed;
            idle(0);
            assertEquals("abcdg", ids(adapter.mmItems));
        }
    }

    @Test public void aSearchKeepsLoadingWithNoOrderChosen() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            adapter.mmItems = GridSort.data(adapter, library());
            idle(GridSort.NEXT_PAGE_DELAY_MS + 100);
            assertEquals("TikTok's order with no search loads nothing", 0, adapter.listener.calls);

            GridSearch.search(adapter, "cat");
            idle(GridSort.NEXT_PAGE_DELAY_MS + 100);
            assertEquals("a search looks past the first screen", 1, adapter.listener.calls);
            assertTrue(GridSort.wantsNextPage(GridSort.OFF, true, 0, 6, true));
            assertFalse("not past the cap", GridSort.wantsNextPage(GridSort.OFF, true, 0, GridSort.LOAD_CAP, true));
            assertFalse("not off screen", GridSort.wantsNextPage(GridSort.OFF, true, 0, 6, false));
            assertFalse("not once pages stop adding", GridSort.wantsNextPage(GridSort.OFF, true, 6, 6, true));
        }
    }

    @Test public void theVideoPagePlaysTheMatchesFirstSoACellOpensItsOwnVideo() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            List<Aweme> model = library();
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            GridSearch.search(adapter, "box");
            assertEquals("d", ids(adapter.mmItems));

            GridSort.playInGridOrder(adapter, model);
            assertEquals("dabcef", ids(model));
        }
    }

    @Test public void theRestOfANarrowedSortedGridPlaysInTheGridsOrder() {
        Settings.GRID_SEARCH.save(true);
        Settings.LIKED_GRID_SORT.save("newest");
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            List<Aweme> model = library();
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            GridSearch.search(adapter, "cat");
            assertEquals("dcba", ids(adapter.mmItems));

            GridSort.playInGridOrder(adapter, model);
            assertEquals("newest first after the matches too", "dcbafe", ids(model));
        }
    }

    @Test public void aPageWhileSearchingReadsOnlyTheVideosItBrought() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            List<Aweme> model = library();
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            GridSearch.search(adapter, "cat");
            Liked first = (Liked) model.get(0);
            int reads = first.descReads;
            assertTrue(reads > 0);

            Liked added = new Liked("g", 700, "cat nap", new Creator("Ivy", "ivy"), new Sound("hum", "Ivy"));
            model.add(added);
            adapter.mmItems = GridSort.page(adapter, model);
            idle(0);
            assertEquals("abcdg", ids(adapter.mmItems));
            assertEquals("a loaded video isn't read again", reads, first.descReads);
            assertEquals(1, added.descReads);

            GridSearch.search(adapter, "cat nap");
            assertEquals("g", ids(adapter.mmItems));
            assertEquals("nor for new words", reads, first.descReads);

            GridSearch.search(adapter, "");
            GridSearch.search(adapter, "cat");
            assertEquals("a search taken off lets them go", reads + 1, first.descReads);
        }
    }

    @Test public void aPageAfterTheSwitchGoesOffRedrawsTheWholeGrid() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            List<Aweme> model = library();
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            GridSearch.search(adapter, "cat");
            assertEquals("abcd", ids(adapter.mmItems));

            Settings.GRID_SEARCH.save(false);
            model.add(new Liked("g", 700, "bread", new Creator("Ivy", "ivy"), new Sound("hum", "Ivy")));
            adapter.mmItems = GridSort.page(adapter, model);
            int refreshes = adapter.refreshes;
            idle(0);
            assertFalse(GridSearch.searching(adapter));
            assertEquals("abcdefg", ids(adapter.mmItems));
            // TikTok counted the page's cells from the narrowed grid, so the grid is told again.
            assertTrue(adapter.refreshes > refreshes);
        }
    }

    @Test public void theChipFollowsTheGridWhenTikTokMovesIt() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            GridSortTest.GridAdapter adapter = gridOnScreen(activity, GridSort.TAB_LIKED);
            adapter.mmItems = GridSort.data(adapter, library());
            idle(0);
            GridSearch.search(adapter, "cat");
            TextView before = chip(adapter);
            assertNotNull(before);

            FrameLayout first = (FrameLayout) adapter.mmRecyclerView.getParent();
            FrameLayout second = new FrameLayout(activity);
            first.removeView(adapter.mmRecyclerView);
            second.addView(adapter.mmRecyclerView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            first.addView(second, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            before.getViewTreeObserver().dispatchOnGlobalLayout();
            idle(0);

            TextView after = chip(adapter);
            assertNotNull("the chip came along", after);
            assertSame(second, after.getParent());
            assertNull("and left the old frame", before.getParent());
            assertEquals("cat", String.valueOf(after.getText()));
            assertEquals(View.VISIBLE, after.getVisibility());
        }
    }

    @Test public void theChipFollowsTheGridOutOfAFrameTikTokThrewAway() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            GridSortTest.GridAdapter adapter = gridOnScreen(activity, GridSort.TAB_LIKED);
            adapter.mmItems = GridSort.data(adapter, library());
            idle(0);
            GridSearch.search(adapter, "cat");
            TextView before = chip(adapter);
            assertNotNull(before);

            FrameLayout first = (FrameLayout) adapter.mmRecyclerView.getParent();
            FrameLayout second = new FrameLayout(activity);
            first.removeView(adapter.mmRecyclerView);
            second.addView(adapter.mmRecyclerView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(second);
            idle(50);

            assertFalse("the old frame left the window", first.isAttachedToWindow());
            TextView after = chip(adapter);
            assertNotNull("the chip came along", after);
            assertSame(second, after.getParent());
            assertNull(before.getParent());
            assertEquals("cat", String.valueOf(after.getText()));
        }
    }

    @Test public void theChipComesBackAfterAStopWhereItCouldNotBeShown() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            GridSortTest.GridAdapter adapter = gridOnScreen(activity, GridSort.TAB_LIKED);
            adapter.mmItems = GridSort.data(adapter, library());
            idle(0);
            GridSearch.search(adapter, "cat");
            assertNotNull(chip(adapter));

            // First into a parent that can't hold a chip, then into a frame that can.
            FrameLayout first = (FrameLayout) adapter.mmRecyclerView.getParent();
            LinearLayout stop = new LinearLayout(activity);
            first.removeView(adapter.mmRecyclerView);
            stop.addView(adapter.mmRecyclerView);
            activity.setContentView(stop);
            idle(50);
            assertNull(stop.findViewWithTag(GridSearch.CHIP_TAG));

            FrameLayout last = new FrameLayout(activity);
            stop.removeView(adapter.mmRecyclerView);
            last.addView(adapter.mmRecyclerView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(last);
            idle(50);
            TextView after = chip(adapter);
            assertNotNull("the chip came back", after);
            assertSame(last, after.getParent());
            assertEquals("cat", String.valueOf(after.getText()));
        }
    }

    @Test public void theSwitchTurnedOffWhileTheChipWaitsOutsideAFrameGivesTheWholeListBack() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            GridSortTest.GridAdapter adapter = gridOnScreen(activity, GridSort.TAB_LIKED);
            adapter.mmItems = GridSort.data(adapter, library());
            idle(0);
            GridSearch.search(adapter, "cat");
            assertEquals("abcd", ids(adapter.mmItems));

            // The grid in a parent that can't hold a chip, so the chip waits out of any window.
            FrameLayout first = (FrameLayout) adapter.mmRecyclerView.getParent();
            LinearLayout stop = new LinearLayout(activity);
            first.removeView(adapter.mmRecyclerView);
            stop.addView(adapter.mmRecyclerView);
            activity.setContentView(stop);
            idle(50);
            assertNull(stop.findViewWithTag(GridSearch.CHIP_TAG));

            Settings.GRID_SEARCH.save(false);
            FrameLayout last = new FrameLayout(activity);
            stop.removeView(adapter.mmRecyclerView);
            last.addView(adapter.mmRecyclerView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(last);
            idle(50);
            assertFalse(GridSearch.searching(adapter));
            assertEquals("the grid stayed narrowed", "abcdef", ids(adapter.mmItems));
        }
    }

    @Test public void theOpenBoxCountsAPageThatComesIn() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            List<Aweme> model = library();
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            assertTrue(chip(adapter).performClick());
            idle(0);
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            EditText box = find(dialog.getWindow().getDecorView(), EditText.class);
            TextView count = (TextView) ((ViewGroup) box.getParent()).getChildAt(1);
            box.setText("cat");
            assertEquals("4 of 6", String.valueOf(count.getText()));

            model.add(new Liked("g", 700, "cat nap", new Creator("Ivy", "ivy"), new Sound("hum", "Ivy")));
            adapter.mmItems = GridSort.page(adapter, model);
            idle(0);
            assertEquals("5 of 7", String.valueOf(count.getText()));
        }
    }

    @Test public void theOrderChipShortensALongNameBesideTheSearchChip() {
        Settings.GRID_SEARCH.save(true);
        Settings.LIKED_GRID_SORT.save("newest");
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            GridSortTest.GridAdapter adapter = gridOnScreen(activity, GridSort.TAB_LIKED);
            adapter.mmItems = GridSort.data(adapter, library());
            idle(0);
            FrameLayout frame = (FrameLayout) adapter.mmRecyclerView.getParent();
            TextView order = frame.findViewWithTag(GridSort.CHIP_TAG);
            TextView search = chip(adapter);
            assertNotNull(order);
            assertNotNull(search);
            assertEquals(1, order.getMaxLines());
            assertEquals(TextUtils.TruncateAt.END, order.getEllipsize());

            GridSort.placeChip(order, adapter.mmRecyclerView);
            assertTrue("laid out", frame.getWidth() > 0 && search.getWidth() > 0);
            int gap = SettingsUi.dp(activity, 12);
            assertEquals("the room the search chip leaves, not a fixed width",
                    Math.max(SettingsUi.dp(activity, 48), frame.getWidth() / 2 - search.getWidth() / 2 - gap),
                    order.getMaxWidth());

            search.setVisibility(View.GONE);
            GridSort.placeChip(order, adapter.mmRecyclerView);
            assertEquals("with no search chip the whole name shows", Integer.MAX_VALUE, order.getMaxWidth());
        }
    }

    @Test public void theChipSitsTopMiddleOnLikedAndFavoritesOnlyWhileTheSwitchIsOn() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter liked = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            liked.mmItems = GridSort.data(liked, library());
            idle(0);
            assertNull("off by default, the tab is stock", chip(liked));
            GridSearch.search(liked, "cat");
            assertEquals("off, nothing narrows", "abcdef", ids(liked.mmItems));
            GridSearch.search(liked, "");

            Settings.GRID_SEARCH.save(true);
            liked.mmItems = GridSort.data(liked, library());
            idle(0);
            TextView control = chip(liked);
            assertNotNull(control);
            assertEquals(View.VISIBLE, control.getVisibility());
            assertEquals(Gravity.TOP | Gravity.CENTER_HORIZONTAL,
                    ((FrameLayout.LayoutParams) control.getLayoutParams()).gravity);
            assertEquals("Search", String.valueOf(control.getText()));

            GridSortTest.GridAdapter posts = gridOnScreen(controller.get(), GridSort.TAB_POSTS);
            posts.mmItems = GridSort.data(posts, library());
            idle(0);
            assertNull("Posts keeps its stock grid", chip(posts));

            GridSortTest.GridAdapter favorites = gridOnScreen(controller.get(), GridSort.TAB_FAVORITE_VIDEOS);
            favorites.mmItems = GridSort.data(favorites, library());
            idle(0);
            assertNotNull(chip(favorites));
        }
    }

    @Test public void theBoxNarrowsAsItsTypedInAndClearPutsTheGridBack() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            adapter.mmItems = GridSort.data(adapter, library());
            idle(0);
            TextView control = chip(adapter);
            assertTrue(control.performClick());
            idle(0);
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            EditText box = find(dialog.getWindow().getDecorView(), EditText.class);
            assertNotNull(box);
            assertEquals("Caption, creator or sound", String.valueOf(box.getHint()));
            TextView count = (TextView) ((ViewGroup) box.getParent()).getChildAt(1);
            assertEquals("no search, no count", "", String.valueOf(count.getText()));

            box.setText("cat");
            assertEquals("abcd", ids(adapter.mmItems));
            assertEquals("cat", String.valueOf(control.getText()));
            assertEquals("Search: cat", String.valueOf(control.getContentDescription()));
            assertEquals("4 of 6", String.valueOf(count.getText()));

            box.setText("cat dog");
            assertEquals("", ids(adapter.mmItems));
            assertEquals("No videos match", String.valueOf(count.getText()));

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            idle(0);
            assertFalse(dialog.isShowing());
            assertFalse(GridSearch.searching(adapter));
            assertEquals("abcdef", ids(adapter.mmItems));
            assertEquals("Search", String.valueOf(control.getText()));

            assertTrue("the box opens with nothing in it", control.performClick());
            idle(0);
            AlertDialog again = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals("", String.valueOf(find(again.getWindow().getDecorView(), EditText.class).getText()));
        }
    }

    @Test public void turningTheSwitchOffOrPausingGivesTheWholeGridBack() {
        Settings.GRID_SEARCH.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridSortTest.GridAdapter adapter = gridOnScreen(controller.get(), GridSort.TAB_LIKED);
            adapter.mmItems = GridSort.data(adapter, library());
            idle(0);
            TextView control = chip(adapter);
            GridSearch.search(adapter, "cat");
            GridSearch.showChip(adapter);
            assertEquals("abcd", ids(adapter.mmItems));
            assertEquals("cat", String.valueOf(control.getText()));

            Settings.GRID_SEARCH.save(false);
            control.getViewTreeObserver().dispatchOnGlobalLayout();
            idle(0);
            assertEquals("abcdef", ids(adapter.mmItems));
            assertEquals(View.GONE, control.getVisibility());
            Settings.GRID_SEARCH.save(true);
            assertFalse("the old search doesn't come back with the switch", GridSearch.searching(adapter));
            control.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals(View.VISIBLE, control.getVisibility());
            assertEquals("nor does its label", "Search", String.valueOf(control.getText()));

            adapter.mmItems = GridSort.data(adapter, library());
            idle(0);
            GridSearch.search(adapter, "cat");
            assertEquals("abcd", ids(adapter.mmItems));
            setPaused(true);
            assertFalse(GridSearch.searching(adapter));
            adapter.mmItems = GridSort.data(adapter, library());
            idle(0);
            assertEquals("paused, TikTok's grid", "abcdef", ids(adapter.mmItems));
            assertNull("and no chip", chip(adapter));
        }
    }

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Test public void theRowShowsOnlyWhereTheLikedGridWasHooked() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.alwaysShowPublishDateEnabled = true;
            SettingsStatus.gridSortEnabled = true;

            SettingsStatus.likedGridSortEnabled = false;
            assertNull(build(activity).findPreference(Settings.GRID_SEARCH.key));

            SettingsStatus.likedGridSortEnabled = true;
            Preference row = build(activity).findPreference(Settings.GRID_SEARCH.key);
            assertNotNull(row);
            assertEquals("Search Liked and Favorites grids", String.valueOf(row.getTitle()));
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("caption") && summary.contains("loaded videos"));
        }
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new InterfacePreferenceCategory(activity, screen);
        return screen;
    }
}

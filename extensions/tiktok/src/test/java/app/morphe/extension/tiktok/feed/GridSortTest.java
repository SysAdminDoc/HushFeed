/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;
import com.ss.android.ugc.aweme.feed.model.PhotoModeImageInfo;
import com.ss.android.ugc.aweme.feed.model.PhotoModeTextInfo;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;

import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

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
 * Sorting the videos a profile or Liked grid has loaded (#180): every order, pinned videos kept
 * in front, videos with no value last, ties and missing values in TikTok's order, new pages
 * sorted in without repeats, TikTok's own list left alone, a sorted grid loading its next pages
 * by itself up to the cap, and the chip that changes the order in place.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class GridSortTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class Counts extends AwemeStatistics {
        final long plays;
        final long likes;
        Counts(long plays, long likes) { this.plays = plays; this.likes = likes; }
        @Override public long getPlayCount() { return plays; }
        @Override public long getDiggCount() { return likes; }
    }

    public static final class Clip {
        final long duration;
        Clip(long duration) { this.duration = duration; }
        public Object getDuration() { return duration; }
    }

    /** A grid cell's post: id, posted time, length in ms, views, likes and whether it is pinned. */
    public static final class Post extends Aweme {
        final String id;
        final long created;
        final Clip clip;
        final Counts counts;
        final int top;

        Post(String id, long created, long length, long views, long likes, boolean pinned) {
            this.id = id;
            this.created = created;
            this.clip = new Clip(length);
            this.counts = new Counts(views, likes);
            this.top = pinned ? 1 : 0;
        }

        public Object getVideo() { return clip; }
        public int getIsTop() { return top; }
        @Override public String getAid() { return id; }
        @Override public long getCreateTime() { return created; }
        @Override public AwemeStatistics getStatistics() { return counts; }
        @Override public List getImageInfos() { return null; }
        @Override public PhotoModeImageInfo getPhotoModeImageInfo() { return null; }
        @Override public PhotoModeTextInfo getPhotoModeTextInfo() { return null; }
        @Override public String toString() { return id; }
    }

    /** The adapter, as far as the extension knows it: something with notifyDataSetChanged. */
    public static final class Adapter {
        int refreshes;
        public void notifyDataSetChanged() { refreshes++; }
    }

    private boolean gridSortEnabled;
    private boolean likedGridSortEnabled;
    private boolean soundGridSortEnabled;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        gridSortEnabled = SettingsStatus.gridSortEnabled;
        likedGridSortEnabled = SettingsStatus.likedGridSortEnabled;
        soundGridSortEnabled = SettingsStatus.soundGridSortEnabled;
    }

    @After public void tearDown() {
        setPaused(false);
        Settings.PROFILE_GRID_SORT.resetToDefault();
        Settings.LIKED_GRID_SORT.resetToDefault();
        Settings.SOUND_GRID_SORT.resetToDefault();
        SettingsStatus.gridSortEnabled = gridSortEnabled;
        SettingsStatus.likedGridSortEnabled = likedGridSortEnabled;
        SettingsStatus.soundGridSortEnabled = soundGridSortEnabled;
        SettingsStatus.alwaysShowPublishDateEnabled = false;
        HookStatus.clear();
        GridSort.resetForTests();
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }

    /** a is pinned; b has no length or counts; c and d tie on every number; e is the biggest. */
    private static List<Aweme> grid() {
        return new ArrayList<>(Arrays.asList(
                new Post("a", 100, 30_000, 10, 1, true),
                new Post("b", 0, 0, 0, 0, false),
                new Post("c", 300, 20_000, 500, 50, false),
                new Post("d", 300, 20_000, 500, 50, false),
                new Post("e", 900, 90_000, 9_000, 800, false),
                new Post("f", 200, 5_000, 70, 90, false),
                new Post("p", 50, 1_000, 5, 5, true)));
    }

    private static String ids(List<?> list) {
        StringBuilder out = new StringBuilder();
        for (Object item : list) out.append(item);
        return out.toString();
    }

    private static String order(String choice, List<Aweme> grid) {
        Settings.PROFILE_GRID_SORT.save(choice);
        return ids(GridSort.data(null,grid));
    }

    @Test public void theSortStartsOffAndLeavesTheListAlone() {
        assertEquals("profile_grid_sort", Settings.PROFILE_GRID_SORT.key);
        assertEquals("off", Settings.PROFILE_GRID_SORT.defaultValue);
        List<Aweme> grid = grid();
        assertSame("off, TikTok's own list", grid, GridSort.data(null,grid));
        Settings.PROFILE_GRID_SORT.save("off");
        assertSame(grid, GridSort.data(null,grid));
    }

    @Test public void newestAndOldest() {
        // Pinned a and p stay first as TikTok has them, then the rest by time, 0 (no time) last.
        assertEquals("apecdfb", order("newest", grid()));
        assertEquals("apfcdeb", order("oldest", grid()));
    }

    @Test public void longestAndShortest() {
        assertEquals("apecdfb", order("longest", grid()));
        assertEquals("apfcdeb", order("shortest", grid()));
    }

    @Test public void mostViewedAndMostLiked() {
        assertEquals("apecdfb", order("most_viewed", grid()));
        assertEquals("apefcdb", order("most_liked", grid()));
    }

    @Test public void leastViewedAndLeastLikedPutTheQuietVideosFirstAndUncountedOnesLast() {
        // Views: f 70, c and d 500, e 9000. b has no counts at all, so it goes last, not first.
        assertEquals("apfcdeb", order("least_viewed", grid()));
        // Likes: c and d 50 (TikTok's order between them), f 90, e 800, b last again.
        assertEquals("apcdfeb", order("least_liked", grid()));
    }

    @Test public void everyOrderHasALabelAndAComparator() {
        assertEquals(GridSort.ORDERS.length, GridSort.LABELS.length);
        assertEquals("off", GridSort.ORDERS[0]);
        assertEquals("Original order", GridSort.LABELS[0]);
        for (int i = 1; i < GridSort.ORDERS.length; i++) {
            assertTrue(GridSort.ORDERS[i], GridSort.comparator(GridSort.ORDERS[i]) != null);
            assertEquals(i, GridSort.indexOf(GridSort.ORDERS[i]));
        }
        assertEquals(-1, GridSort.indexOf("sideways"));
    }

    @Test public void theChipClearsFullWidthRowsAtTheTopOfTheGridButNotCellsOrAFooter() {
        int[] none = {};
        assertEquals("an empty grid", 0, GridSort.chipOffset(1080, none, none, none));
        assertEquals("cells only, as on a creator's grid", 0, GridSort.chipOffset(1080,
                new int[] {0, 0, 0, 480}, new int[] {480, 480, 480, 960}, new int[] {358, 358, 358, 358}));
        assertEquals("the Liked tab's privacy note", 113, GridSort.chipOffset(1080,
                new int[] {0, 113, 113, 113}, new int[] {113, 593, 593, 593}, new int[] {1080, 358, 358, 358}));
        assertEquals("two notes stacked", 160, GridSort.chipOffset(1080,
                new int[] {0, 100, 160}, new int[] {100, 160, 640}, new int[] {1080, 1080, 358}));
        assertEquals("a note scrolled partly away", 63, GridSort.chipOffset(1080,
                new int[] {-50, 63}, new int[] {63, 543}, new int[] {1080, 358}));
        assertEquals("a note scrolled out of sight", 0, GridSort.chipOffset(1080,
                new int[] {-113, 0}, new int[] {0, 480}, new int[] {1080, 358}));
        assertEquals("the loading footer under the cells", 0, GridSort.chipOffset(1080,
                new int[] {0, 0, 480}, new int[] {480, 480, 560}, new int[] {358, 358, 1080}));
        assertEquals("a grid not laid out yet", 0, GridSort.chipOffset(0,
                new int[] {0}, new int[] {0}, new int[] {0}));
    }

    @Test public void theNextPageIsWantedOnlyForARealOrderOnScreenUnderTheCapWhilePagesAddVideos() {
        assertTrue(GridSort.wantsNextPage("newest", 0, 30, true));
        assertTrue(GridSort.wantsNextPage("least_liked", 30, 60, true));
        assertTrue("one under the cap", GridSort.wantsNextPage("oldest", 270, 299, true));

        assertFalse("Original order is TikTok's own loading", GridSort.wantsNextPage("off", 0, 30, true));
        assertFalse("a choice this build doesn't know", GridSort.wantsNextPage("sideways", 0, 30, true));
        assertFalse(GridSort.wantsNextPage(null, 0, 30, true));
        assertFalse("a grid off screen doesn't load", GridSort.wantsNextPage("newest", 0, 30, false));
        assertFalse("the cap", GridSort.wantsNextPage("newest", 270, GridSort.LOAD_CAP, true));
        assertFalse("past the cap", GridSort.wantsNextPage("newest", 300, 330, true));
        assertFalse("a page that added nothing ends the chain", GridSort.wantsNextPage("newest", 60, 60, true));
        assertFalse("a list that shrank", GridSort.wantsNextPage("newest", 60, 40, true));
        assertFalse("an empty grid", GridSort.wantsNextPage("newest", 0, 0, true));
        assertEquals(300, GridSort.LOAD_CAP);
    }

    /** The grid's own load more listener, as the adapter's footer calls it. */
    public static final class Listener {
        int calls;
        public void loadMore() { calls++; }
    }

    /** TikTok's grid adapter, with the members the base adapter class names in the clear. */
    public static final class GridAdapter {
        public List<?> mmItems;
        public View mmRecyclerView;
        Listener listener = new Listener();
        int refreshes;
        public Listener getLoadMoreListener() { return listener; }
        public void notifyDataSetChanged() { refreshes++; }
    }

    /** RecyclerView's owner changes, while the former adapter keeps mmRecyclerView on 47.5.3. */
    public static final class OwnedGrid extends View {
        private GridAdapter currentAdapter;
        OwnedGrid(Activity activity) { super(activity); }
        public GridAdapter getAdapter() { return currentAdapter; }
        void setAdapter(GridAdapter adapter) {
            currentAdapter = adapter;
            if (adapter != null) adapter.mmRecyclerView = this;
        }
    }

    private static List<Aweme> posts(int count) {
        List<Aweme> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(new Post("v" + i, 1_000 + i, 1_000, 1 + i, 1 + i, false));
        return out;
    }

    private static void idle(long millis) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** A grid on screen in its own FrameLayout, as TikTok's profile page holds it. */
    private static GridAdapter gridOnScreen(Activity activity) {
        FrameLayout frame = new FrameLayout(activity);
        View grid = new View(activity);
        frame.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activity.setContentView(frame);
        idle(50);
        GridAdapter adapter = new GridAdapter();
        adapter.mmRecyclerView = grid;
        return adapter;
    }

    private static TextView chip(GridAdapter adapter) {
        FrameLayout frame = (FrameLayout) adapter.mmRecyclerView.getParent();
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (GridSort.CHIP_TAG.equals(child.getTag())) return (TextView) child;
        }
        return null;
    }

    @Test public void aSortedGridOnScreenLoadsItsNextPagesUntilAPageAddsNothing() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            Settings.PROFILE_GRID_SORT.save("newest");

            List<Aweme> model = posts(30);
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            assertEquals("not before the pause", 0, adapter.listener.calls);
            // A little past the pause: Robolectric's frame callbacks move its clock on by a frame.
            idle(GridSort.NEXT_PAGE_DELAY_MS + 100);
            assertEquals("the first page asks for the second", 1, adapter.listener.calls);

            model.addAll(posts(30));
            adapter.mmItems = GridSort.page(adapter, model);
            idle(GridSort.NEXT_PAGE_DELAY_MS + 100);
            assertEquals("a page that added videos asks for the next", 2, adapter.listener.calls);

            adapter.mmItems = GridSort.page(adapter, model);
            idle(GridSort.NEXT_PAGE_DELAY_MS + 100);
            assertEquals("a page that added nothing stops", 2, adapter.listener.calls);
            idle(5_000);
            assertEquals(2, adapter.listener.calls);
        }
    }

    private static View holdOn(GridAdapter adapter) {
        FrameLayout frame = (FrameLayout) adapter.mmRecyclerView.getParent();
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (GridSort.HOLD_TAG.equals(child.getTag())) return child;
        }
        return null;
    }

    private static void assertHeld(GridAdapter adapter) {
        assertEquals("out of sight", 0f, adapter.mmRecyclerView.getAlpha(), 0f);
        assertTrue("a cell nobody can see can't be tapped", holdOn(adapter).isClickable());
    }

    private static TextView holdCount(GridAdapter adapter) {
        ViewGroup box = (ViewGroup) ((ViewGroup) holdOn(adapter)).getChildAt(0);
        return (TextView) box.getChildAt(1);
    }

    private static void assertInSight(GridAdapter adapter) {
        assertEquals(1f, adapter.mmRecyclerView.getAlpha(), 0f);
        assertNull(holdOn(adapter));
    }

    /** #180: the chain read as a slow sort, and on a pull to refresh as the order being lost. */
    @Test public void aFreshGridStaysOutOfSightUntilItsLastPageThenRefreshesOnce() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            Settings.PROFILE_GRID_SORT.save("newest");
            List<Aweme> model = posts(30);
            adapter.mmItems = GridSort.data(adapter, model);
            assertHeld(adapter);
            assertEquals("30", holdCount(adapter).getText().toString());
            assertEquals("a screen reader hears a number and not what it counts",
                    "30 videos", String.valueOf(holdCount(adapter).getContentDescription()));

            idle(GridSort.NEXT_PAGE_DELAY_MS + 100);
            model.addAll(0, posts(30));
            adapter.mmItems = GridSort.page(adapter, model);
            idle(GridSort.NEXT_PAGE_DELAY_MS + 100);
            assertHeld(adapter);
            assertEquals("60", holdCount(adapter).getText().toString());
            assertEquals("60 videos", String.valueOf(holdCount(adapter).getContentDescription()));
            assertEquals("no refresh while it's out of sight", 0, adapter.refreshes);
            TextView count = holdCount(adapter);
            count.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            assertEquals("right to left, the gap still faces the spinner",
                    SettingsUi.dp(count.getContext(), 8), count.getPaddingRight());
            assertEquals(0, count.getPaddingLeft());

            adapter.mmItems = GridSort.page(adapter, model);
            idle(0);
            assertInSight(adapter);
            assertEquals("one refresh as it comes back", 1, adapter.refreshes);
        }
    }

    @Test public void aHoldEndsAtTheCap() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter capped = gridOnScreen(controller.get());
            Settings.PROFILE_GRID_SORT.save("newest");
            List<Aweme> model = posts(30);
            capped.mmItems = GridSort.data(capped, model);
            assertHeld(capped);
            model.addAll(posts(GridSort.LOAD_CAP));
            capped.mmItems = GridSort.page(capped, model);
            idle(0);
            assertInSight(capped);
        }
    }

    /** The Liked tab asked past its last video got no answer at all, and stayed hidden until the limit. */
    @Test public void aHoldEndsWhenAPageGoesUnanswered() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter unanswered = gridOnScreen(controller.get());
            Settings.PROFILE_GRID_SORT.save("newest");
            unanswered.mmItems = GridSort.data(unanswered, posts(30));
            assertHeld(unanswered);
            idle(GridSort.HOLD_QUIET_MS - 100);
            assertHeld(unanswered);
            idle(200);
            assertInSight(unanswered);
        }
    }

    @Test public void aRefreshDuringAHoldStartsItsLimitOver() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            Settings.PROFILE_GRID_SORT.save("newest");
            List<Aweme> model = posts(30);
            adapter.mmItems = GridSort.data(adapter, model);
            long gap = GridSort.HOLD_QUIET_MS - 1000;
            long passed = 0;
            while (passed + gap < GridSort.HOLD_LIMIT_MS) {
                idle(gap);
                passed += gap;
                model.addAll(posts(5));
                adapter.mmItems = GridSort.page(adapter, model);
            }
            adapter.mmItems = GridSort.data(adapter, posts(10));
            idle(GridSort.HOLD_LIMIT_MS - passed + 100);
            assertHeld(adapter);
        }
    }

    @Test public void pagesKeepAHoldGoingUpToItsLimit() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter slow = gridOnScreen(controller.get());
            Settings.PROFILE_GRID_SORT.save("newest");
            List<Aweme> model = posts(10);
            slow.mmItems = GridSort.data(slow, model);
            long gap = GridSort.HOLD_QUIET_MS - 1000;
            long passed = 0;
            while (passed + gap < GridSort.HOLD_LIMIT_MS) {
                idle(gap);
                passed += gap;
                assertHeld(slow);
                model.addAll(posts(10));
                slow.mmItems = GridSort.page(slow, model);
            }
            idle(GridSort.HOLD_LIMIT_MS - passed + 100);
            assertInSight(slow);
        }
    }

    private static boolean coverTakes(FrameLayout frame) {
        int size = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY);
        frame.measure(size, size);
        frame.layout(0, 0, 400, 400);
        android.view.MotionEvent down = android.view.MotionEvent.obtain(0, 0,
                android.view.MotionEvent.ACTION_DOWN, 200, 200, 0);
        try {
            return frame.dispatchTouchEvent(down);
        } finally {
            down.recycle();
        }
    }

    /** With the grid gone nothing would answer the pages, and the taps belong to whatever replaced it. */
    @Test public void aHoldLetsGoAsSoonAsItsGridLeaves() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            GridAdapter detached = gridOnScreen(controller.get());
            View grid = detached.mmRecyclerView;
            FrameLayout frame = (FrameLayout) grid.getParent();
            Settings.PROFILE_GRID_SORT.save("newest");
            detached.mmItems = GridSort.data(detached, posts(30));
            assertHeld(detached);
            assertTrue("a cover in place takes the tap", coverTakes(frame));

            frame.removeView(grid);
            assertFalse("gone as the grid goes, before any queued work", coverTakes(frame));
            idle(0);
            assertEquals(1f, grid.getAlpha(), 0f);
            for (int i = 0; i < frame.getChildCount(); i++) {
                assertFalse(GridSort.HOLD_TAG.equals(frame.getChildAt(i).getTag()));
            }

            GridAdapter hidden = gridOnScreen(controller.get());
            hidden.mmItems = GridSort.data(hidden, posts(30));
            assertHeld(hidden);
            FrameLayout hiddenFrame = (FrameLayout) hidden.mmRecyclerView.getParent();
            hidden.mmRecyclerView.setVisibility(View.GONE);
            assertFalse("a hidden grid's cover lets the first tap through", coverTakes(hiddenFrame));
            idle(0);
            assertEquals(1f, hidden.mmRecyclerView.getAlpha(), 0f);
            assertNull(holdOn(hidden));
        }
    }

    /** TikTok clears the listener right after handing over a profile's last videos. */
    @Test public void aProfileWithNoMorePagesComesBackAtOnce() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            Settings.PROFILE_GRID_SORT.save("newest");
            Listener listener = adapter.listener;
            adapter.listener = null;
            adapter.mmItems = GridSort.data(adapter, posts(5));
            idle(0);
            assertInSight(adapter);
            idle(GridSort.NEXT_PAGE_DELAY_MS * 2);
            assertEquals(0, listener.calls);
        }
    }

    @Test public void originalOrderAndAnOffScreenGridAndTheSameListAgainStayAsTheyAre() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter stock = gridOnScreen(controller.get());
            Settings.PROFILE_GRID_SORT.save("off");
            stock.mmItems = GridSort.data(stock, posts(30));
            assertInSight(stock);

            Settings.PROFILE_GRID_SORT.save("newest");
            GridAdapter hidden = gridOnScreen(controller.get());
            hidden.mmRecyclerView.setVisibility(View.GONE);
            hidden.mmItems = GridSort.data(hidden, posts(30));
            assertInSight(hidden);

            GridAdapter adapter = gridOnScreen(controller.get());
            List<Aweme> model = posts(30);
            adapter.mmItems = GridSort.data(adapter, model);
            adapter.mmItems = GridSort.page(adapter, model);
            idle(0);
            assertInSight(adapter);
            adapter.mmItems = GridSort.data(adapter, model);
            assertInSight(adapter);
            adapter.mmItems = GridSort.data(adapter, model.subList(0, 9));
            assertHeld(adapter);
        }
    }

    @Test public void anOrderPickedFromTheChipHoldsAndOriginalOrderLetsGo() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            Settings.PROFILE_GRID_SORT.save("off");
            adapter.mmItems = GridSort.data(adapter, posts(30));
            idle(0);
            assertInSight(adapter);

            GridSort.apply(adapter, "newest");
            assertHeld(adapter);
            assertTrue("the chip stays above the cover to change the order",
                    chip(adapter).getElevation() > holdOn(adapter).getElevation());
            GridSort.apply(adapter, "off");
            assertInSight(adapter);
        }
    }

    @Test public void oneAskAtATimeAndNoneOffScreenOrAtTheCapOrInOriginalOrder() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            Settings.PROFILE_GRID_SORT.save("most_viewed");
            List<Aweme> model = posts(30);
            GridSort.data(adapter, model);
            GridSort.data(adapter, model);
            idle(GridSort.NEXT_PAGE_DELAY_MS * 2);
            assertEquals("two lists before the answer still ask once", 1, adapter.listener.calls);

            GridAdapter full = gridOnScreen(controller.get());
            GridSort.data(full, posts(GridSort.LOAD_CAP));
            idle(GridSort.NEXT_PAGE_DELAY_MS * 2);
            assertEquals("the cap", 0, full.listener.calls);

            GridAdapter hidden = gridOnScreen(controller.get());
            hidden.mmRecyclerView.setVisibility(View.GONE);
            GridSort.data(hidden, posts(30));
            idle(GridSort.NEXT_PAGE_DELAY_MS * 2);
            assertEquals("off screen", 0, hidden.listener.calls);

            GridAdapter stock = gridOnScreen(controller.get());
            Settings.PROFILE_GRID_SORT.save("off");
            GridSort.data(stock, posts(30));
            idle(GridSort.NEXT_PAGE_DELAY_MS * 2);
            assertEquals("Original order", 0, stock.listener.calls);
            assertNull("no chip on a stock grid", chip(stock));

            GridAdapter switched = gridOnScreen(controller.get());
            Settings.PROFILE_GRID_SORT.save("newest");
            GridSort.data(switched, posts(30));
            idle(0);
            Settings.PROFILE_GRID_SORT.save("off");
            idle(GridSort.NEXT_PAGE_DELAY_MS);
            assertEquals("switched off while the ask waited", 0, switched.listener.calls);
        }
    }

    @Test public void theChipNamesTheOrderAndChangesItInPlace() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            Settings.PROFILE_GRID_SORT.save("newest");
            List<Aweme> model = grid();
            String tikToks = ids(model);
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            assertEquals("apecdfb", ids(adapter.mmItems));
            TextView chip = chip(adapter);
            assertNotNull("a chip over the grid", chip);
            assertEquals("Newest first", String.valueOf(chip.getText()));
            FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) chip.getLayoutParams();
            assertEquals(Gravity.TOP | Gravity.END, layout.gravity);

            chip.performClick();
            idle(0);
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull("the chip opens the choices", dialog);
            assertTrue(dialog.isShowing());
            assertEquals(GridSort.ORDERS.length, dialog.getListView().getCount());
            assertEquals("Least liked first", String.valueOf(dialog.getListView().getItemAtPosition(8)));
            Shadows.shadowOf(dialog).clickOnItem(GridSort.indexOf("least_liked"));
            idle(0);

            assertEquals("least_liked", Settings.PROFILE_GRID_SORT.get());
            assertEquals("the grid re-sorted from TikTok's list", "apcdfeb", ids(adapter.mmItems));
            assertEquals("TikTok's list keeps its order", tikToks, ids(model));
            assertTrue(adapter.refreshes >= 1);
            assertEquals("Least liked first", String.valueOf(chip(adapter).getText()));
            assertSame("one chip, updated", chip, chip(adapter));

            GridSort.apply(adapter, "off");
            idle(0);
            assertSame("Original order hands the grid TikTok's own list", model, adapter.mmItems);
            assertNull("and takes the chip away", chip(adapter));
        }
    }

    @Test public void aGridInAnythingButAFrameLayoutGetsNoChipButStillSorts() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            android.widget.LinearLayout column = new android.widget.LinearLayout(activity);
            View grid = new View(activity);
            column.addView(grid, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            activity.setContentView(column);
            idle(50);
            GridAdapter adapter = new GridAdapter();
            adapter.mmRecyclerView = grid;
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, grid());
            idle(0);
            assertEquals("apecdfb", ids(adapter.mmItems));
            assertEquals(1, column.getChildCount());
        }
    }

    @Test public void theLikedGridFollowsTheProfileOrderUntilItHasItsOwn() {
        assertEquals("liked_grid_sort", Settings.LIKED_GRID_SORT.key);
        assertEquals("profile", Settings.LIKED_GRID_SORT.defaultValue);
        Object liked = new GridAdapter();
        Object posts = new GridAdapter();
        Object favorites = new GridAdapter();
        Object other = new GridAdapter();
        GridSort.tab(liked, GridSort.TAB_LIKED);
        GridSort.tab(posts, GridSort.TAB_POSTS);
        GridSort.tab(favorites, GridSort.TAB_FAVORITE_VIDEOS);
        GridSort.tab(other, 7);
        Settings.PROFILE_GRID_SORT.save("newest");

        assertEquals("the default follows the profile grids", "newest", GridSort.orderFor(liked));
        Settings.LIKED_GRID_SORT.save("oldest");
        assertEquals("oldest", GridSort.orderFor(liked));
        assertEquals("newest", GridSort.orderFor(posts));
        assertEquals("newest", GridSort.orderFor(favorites));
        assertEquals("a tab TikTok numbers some other way keeps its order", "off", GridSort.orderFor(other));
        assertEquals("an adapter whose tab never came in follows the profile grids",
                "newest", GridSort.orderFor(new GridAdapter()));
        Settings.LIKED_GRID_SORT.save("off");
        assertEquals("Original order on Liked stays original", "off", GridSort.orderFor(liked));
        Settings.LIKED_GRID_SORT.save("sideways");
        assertEquals("an unknown value follows the profile grids", "newest", GridSort.orderFor(liked));
    }

    @Test public void eachGridSortsByItsOwnTabsOrder() {
        Settings.PROFILE_GRID_SORT.save("newest");
        Settings.LIKED_GRID_SORT.save("oldest");
        GridAdapter liked = new GridAdapter();
        GridAdapter posts = new GridAdapter();
        GridAdapter other = new GridAdapter();
        GridSort.tab(liked, GridSort.TAB_LIKED);
        GridSort.tab(posts, GridSort.TAB_POSTS);
        GridSort.tab(other, 7);

        assertEquals("pinned first, then oldest", "apfcdeb", ids(GridSort.data(liked, grid())));
        assertEquals("pinned first, then newest", "apecdfb", ids(GridSort.data(posts, grid())));
        List<Aweme> list = grid();
        assertSame("TikTok's own list", list, GridSort.data(other, list));
        idle(0);
    }

    @Test public void theChipOnALikedGridChangesOnlyTheLikedOrder() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            GridSort.tab(adapter, GridSort.TAB_LIKED);
            Settings.PROFILE_GRID_SORT.save("newest");
            List<Aweme> model = grid();
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            assertEquals("apecdfb", ids(adapter.mmItems));
            assertEquals("Newest first", String.valueOf(chip(adapter).getText()));

            selectOrder(adapter, "least_liked");

            assertEquals("least_liked", Settings.LIKED_GRID_SORT.get());
            assertEquals("the profile grids keep theirs", "newest", Settings.PROFILE_GRID_SORT.get());
            assertEquals("apcdfeb", ids(adapter.mmItems));
            assertEquals("Least liked first", String.valueOf(chip(adapter).getText()));

            GridSort.apply(adapter, "off");
            idle(0);
            assertEquals("off", Settings.LIKED_GRID_SORT.get());
            assertEquals("newest", Settings.PROFILE_GRID_SORT.get());
            assertSame("Original order hands the Liked grid TikTok's list", model, adapter.mmItems);
        }
    }

    /** A sound's page (#180) has its own order; a hashtag's or an effect's page keeps TikTok's. */
    @Test public void aSoundPageSortsByItsOwnOrderAndOtherDetailPagesKeepTikToks() {
        assertEquals("sound_grid_sort", Settings.SOUND_GRID_SORT.key);
        assertEquals("off", Settings.SOUND_GRID_SORT.defaultValue);
        GridAdapter sound = new GridAdapter();
        GridAdapter hashtag = new GridAdapter();
        GridSort.soundGrid(sound, "single_song");
        GridSort.soundGrid(hashtag, "challenge");
        Settings.PROFILE_GRID_SORT.save("newest");

        List<Aweme> untouched = grid();
        assertSame("off until an order is chosen for sound pages", untouched, GridSort.soundData(sound, untouched));
        Settings.SOUND_GRID_SORT.save("oldest");
        assertEquals("pinned first, then oldest", "apfcdeb", ids(GridSort.soundData(sound, grid())));
        assertEquals("apfcdeb", ids(GridSort.soundPage(sound, grid())));
        assertSame(Settings.SOUND_GRID_SORT, GridSort.settingFor(sound));

        List<Aweme> first = grid();
        List<Aweme> more = grid();
        assertSame("a hashtag page keeps TikTok's list", first, GridSort.soundData(hashtag, first));
        assertSame(more, GridSort.soundPage(hashtag, more));
        assertEquals("off", GridSort.orderFor(hashtag));
        assertSame(first, GridSort.soundData(null, first));
        idle(0);
    }

    @Test public void theChipOnASoundPageChangesOnlyTheSoundOrder() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            GridSort.soundGrid(adapter, "single_song");
            Settings.PROFILE_GRID_SORT.save("newest");
            Settings.SOUND_GRID_SORT.save("oldest");
            adapter.mmItems = GridSort.soundData(adapter, grid());
            idle(0);
            assertEquals("apfcdeb", ids(adapter.mmItems));
            assertEquals("Oldest first", String.valueOf(chip(adapter).getText()));

            selectOrder(adapter, "least_liked");

            assertEquals("least_liked", Settings.SOUND_GRID_SORT.get());
            assertEquals("the profile grids keep theirs", "newest", Settings.PROFILE_GRID_SORT.get());
            assertEquals("apcdfeb", ids(adapter.mmItems));
        }
    }

    /**
     * A sound's page hands its grid a copy and its video page plays the page model's list, so
     * that list is the one put in the grid's order, and the copy stays TikTok's order.
     */
    @Test public void openingAVideoFromASortedSoundPageSwipesInTheGridsOrder() {
        GridAdapter adapter = new GridAdapter();
        GridSort.soundGrid(adapter, "single_song");
        Settings.SOUND_GRID_SORT.save("newest");
        List<Aweme> model = grid();
        List<Aweme> copy = new ArrayList<>(model);
        adapter.mmItems = GridSort.soundData(adapter, copy);
        assertEquals("apecdfb", ids(adapter.mmItems));

        GridSort.beforeOpen(new GridFragment(adapter));
        assertEquals("with no page model found the video page keeps TikTok's order", "abcdefp", ids(model));
        assertEquals("abcdefp", ids(copy));

        GridSort.playInGridOrder(adapter, model);
        assertEquals("the video page plays the grid's order", "apecdfb", ids(model));
        assertEquals("the grid's copy stays TikTok's order", "abcdefp", ids(copy));

        // TikTok appends the next page to the model and hands the grid a fresh copy of it.
        model.add(new Post("g", 250, 1_000, 1, 1, false));
        adapter.mmItems = GridSort.soundPage(adapter, new ArrayList<>(model));
        assertEquals("apecdgfb", ids(adapter.mmItems));
        GridSort.apply(adapter, "off");
        assertEquals("Original order is TikTok's, the new video where TikTok put it",
                "abcdefpg", ids(adapter.mmItems));
        idle(0);
    }

    private static void selectOrder(GridAdapter adapter, String order) {
        TextView control = chip(adapter);
        assertNotNull("the current grid has a sort control", control);
        assertTrue("the control remains clickable", control.performClick());
        idle(0);
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertEquals("the menu marks the current order", GridSort.indexOf(GridSort.orderFor(adapter)),
                dialog.getListView().getCheckedItemPosition());
        Shadows.shadowOf(dialog).clickOnItem(GridSort.indexOf(order));
        idle(0);
        assertFalse("a choice closes the menu", dialog.isShowing());
    }

    @Test public void originalChosenFromTheMenuKeepsThatControlAndTheSameVideos() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            GridFragment fragment = new GridFragment(adapter);
            List<Aweme> model = grid();
            List<Aweme> identities = new ArrayList<>(model);
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            TextView originalControl = chip(adapter);
            GridSort.beforeOpen(fragment);
            assertEquals("the viewer had the sorted order", "apecdfb", ids(model));

            selectOrder(adapter, "off");
            int asked = adapter.listener.calls;
            assertEquals("off", Settings.PROFILE_GRID_SORT.get());
            assertEquals("native order includes pinned and missing-value posts", "abcdefp", ids(adapter.mmItems));
            assertSame("Original keeps the active control", originalControl, chip(adapter));
            assertEquals("Original order", String.valueOf(chip(adapter).getText()));
            assertEquals("a screen reader hears the order the grid is in",
                    "Sort this grid: Original order", String.valueOf(chip(adapter).getContentDescription()));
            assertEquals("android.widget.Button",
                    String.valueOf(chip(adapter).createAccessibilityNodeInfo().getClassName()));
            assertTrue(chip(adapter).isShown());
            GridSort.beforeOpen(fragment);
            for (int i = 0; i < identities.size(); i++) {
                assertSame("the original post at " + i, identities.get(i), model.get(i));
                assertSame("the grid and viewer own the same post at " + i, model.get(i), adapter.mmItems.get(i));
            }
            idle(GridSort.NEXT_PAGE_DELAY_MS * 2);
            assertEquals("Original leaves paging to TikTok", asked, adapter.listener.calls);

            selectOrder(adapter, "off");
            assertSame("selecting Original again keeps the route", originalControl, chip(adapter));
            selectOrder(adapter, "most_liked");
            assertEquals("apefcdb", ids(adapter.mmItems));
            assertSame(originalControl, chip(adapter));
            GridSort.apply(adapter, "off");
            assertNull("an explicit disable still removes the control", chip(adapter));
        }
    }

    @Test public void anOriginalControlSurvivesPagesButExpiresWhenItsGridIsHiddenOrDetached() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            List<Aweme> model = grid();
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            selectOrder(adapter, "off");
            int asked = adapter.listener.calls;
            TextView originalControl = chip(adapter);
            assertNotNull("Original leaves its control on the current grid", originalControl);

            model.add(new Post("g", 250, 1_000, 1, 1, false));
            adapter.mmItems = GridSort.page(adapter, model);
            idle(GridSort.NEXT_PAGE_DELAY_MS * 2);
            assertSame(model, adapter.mmItems);
            assertEquals("abcdefpg", ids(adapter.mmItems));
            assertSame("the same grid's next page keeps its menu", originalControl, chip(adapter));
            assertEquals(asked, adapter.listener.calls);

            adapter.mmRecyclerView.setVisibility(View.GONE);
            idle(50);
            assertNull("navigation away expires the retained control", chip(adapter));
            adapter.mmRecyclerView.setVisibility(View.VISIBLE);
            adapter.mmItems = GridSort.data(adapter, model);
            idle(50);
            assertNull("returning to Original is a native grid again", chip(adapter));

            GridSort.apply(adapter, "newest");
            selectOrder(adapter, "off");
            FrameLayout frame = (FrameLayout) adapter.mmRecyclerView.getParent();
            frame.removeView(adapter.mmRecyclerView);
            idle(0);
            assertNull("detaching the grid removes its retained control", frame.findViewWithTag(GridSort.CHIP_TAG));
            frame.addView(adapter.mmRecyclerView);
            adapter.mmItems = GridSort.data(adapter, model);
            idle(50);
            assertNull("reattachment doesn't revive an expired control", chip(adapter));

            GridSort.apply(adapter, "newest");
            selectOrder(adapter, "off");
            frame.dispatchWindowVisibilityChanged(View.INVISIBLE);
            idle(0);
            assertNull("a hidden window expires Original retention", chip(adapter));
            frame.dispatchWindowVisibilityChanged(View.VISIBLE);
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            assertNull("returning to the window doesn't revive the control", chip(adapter));
        }
    }

    @Test public void pauseClearsAnOriginalControlWithoutChangingNativeOffGrids() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            List<Aweme> model = grid();
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            selectOrder(adapter, "off");
            assertNotNull("Original is reachable before Pause", chip(adapter));
            setPaused(true);
            adapter.mmItems = GridSort.data(adapter, model);
            idle(50);
            assertSame(model, adapter.mmItems);
            assertNull("Pause leaves no retained overlay", chip(adapter));
            setPaused(false);
            adapter.mmItems = GridSort.data(adapter, model);
            idle(50);
            assertSame(model, adapter.mmItems);
            assertNull("resuming Original doesn't enable a control", chip(adapter));

            GridAdapter stock = gridOnScreen(controller.get());
            stock.mmItems = GridSort.data(stock, model);
            idle(50);
            assertSame(model, stock.mmItems);
            assertNull("a grid with no active sort stays native", chip(stock));
            GridSort.apply(stock, "off");
            assertNull("applying off doesn't create a control", chip(stock));

            GridSort.apply(stock, "newest");
            selectOrder(stock, "off");
            Settings.PROFILE_GRID_SORT.save("oldest");
            stock.mmItems = GridSort.data(stock, model);
            idle(0);
            Settings.PROFILE_GRID_SORT.save("off");
            stock.mmItems = GridSort.data(stock, model);
            idle(0);
            assertNull("a later settings disable doesn't inherit an old menu choice", chip(stock));
        }
    }

    @Test public void aReusedGridDoesNotGiveANewAdapterThePreviousOriginalControl() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter first = gridOnScreen(controller.get());
            List<Aweme> firstModel = grid();
            Settings.PROFILE_GRID_SORT.save("newest");
            first.mmItems = GridSort.data(first, firstModel);
            idle(0);
            selectOrder(first, "off");
            TextView oldControl = chip(first);
            assertNotNull("Original remains reachable before recycling", oldControl);
            oldControl.performClick();
            idle(0);
            AlertDialog staleMenu = (AlertDialog) ShadowDialog.getLatestDialog();

            GridAdapter next = new GridAdapter();
            next.mmRecyclerView = first.mmRecyclerView;
            List<Aweme> nextModel = posts(3);
            next.mmItems = GridSort.data(next, nextModel);
            idle(0);
            assertNull("retention belongs to the previous adapter", chip(next));
            Shadows.shadowOf(staleMenu).clickOnItem(GridSort.indexOf("oldest"));
            idle(0);
            assertEquals("a menu for a removed control can't change the next grid", "off", Settings.PROFILE_GRID_SORT.get());
            assertSame(nextModel, next.mmItems);
            assertSame(firstModel, first.mmItems);

            Settings.PROFILE_GRID_SORT.save("newest");
            next.mmItems = GridSort.data(next, nextModel);
            idle(0);
            assertEquals("v2v1v0", ids(next.mmItems));
            selectOrder(next, "oldest");
            assertEquals("the new control targets the current adapter", "v0v1v2", ids(next.mmItems));
            assertSame("the old adapter is untouched", firstModel, first.mmItems);

            FrameLayout frame = (FrameLayout) next.mmRecyclerView.getParent();
            GridAdapter replacement = new GridAdapter();
            replacement.mmRecyclerView = new View(controller.get());
            frame.addView(replacement.mmRecyclerView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            replacement.mmItems = GridSort.data(replacement, nextModel);
            idle(50);
            selectOrder(replacement, "off");
            TextView replacementControl = chip(replacement);
            next.mmRecyclerView.setVisibility(View.GONE);
            idle(50);
            assertSame("the previous view's callbacks can't remove the replacement control",
                    replacementControl, chip(replacement));
            assertNotNull(replacementControl);
            next.mmItems = GridSort.page(next, nextModel);
            idle(0);
            assertSame("a late hidden-grid page cannot remove the visible grid control",
                    replacementControl, chip(replacement));
        }
    }

    @Test public void aNestedGridControlCannotInterceptItsParentsSortChoice() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter outer = gridOnScreen(controller.get());
            List<Aweme> outerModel = grid();
            Settings.PROFILE_GRID_SORT.save("newest");
            outer.mmItems = GridSort.data(outer, outerModel);
            idle(0);
            TextView outerControl = chip(outer);

            FrameLayout outerFrame = (FrameLayout) outer.mmRecyclerView.getParent();
            FrameLayout nestedFrame = new FrameLayout(controller.get());
            GridAdapter nested = new GridAdapter();
            nested.mmRecyclerView = new View(controller.get());
            nestedFrame.addView(nested.mmRecyclerView, new FrameLayout.LayoutParams(100, 100));
            outerFrame.addView(nestedFrame, 0, new FrameLayout.LayoutParams(100, 100));
            List<Aweme> nestedModel = posts(3);
            nested.mmItems = GridSort.data(nested, nestedModel);
            idle(50);
            TextView nestedControl = chip(nested);
            assertNotNull(nestedControl);

            selectOrder(outer, "off");
            assertEquals("the parent's menu must target its own control", "off", Settings.PROFILE_GRID_SORT.get());
            assertSame(outerControl, chip(outer));
            assertEquals("abcdefp", ids(outer.mmItems));
            assertSame("the nested control is untouched", nestedControl, chip(nested));
            assertEquals("the nested grid is untouched", "v2v1v0", ids(nested.mmItems));
        }
    }

    @Test public void anExternalDisableBeforeTheNextBindExpiresOriginalRetention() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            List<Aweme> model = grid();
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            selectOrder(adapter, "off");
            assertNotNull(chip(adapter));
            Settings.PROFILE_GRID_SORT.save("oldest");
            Settings.PROFILE_GRID_SORT.save("off");
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            assertNull("an external disable cannot reuse an old Original choice", chip(adapter));
        }
    }

    @Test public void aLatePageCannotTakeTheCurrentAdaptersControlOnTheSameGridView() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter first = gridOnScreen(controller.get());
            FrameLayout frame = (FrameLayout) first.mmRecyclerView.getParent();
            frame.removeView(first.mmRecyclerView);
            OwnedGrid grid = new OwnedGrid(controller.get());
            frame.addView(grid, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            grid.setAdapter(first);
            idle(50);
            List<Aweme> firstModel = grid();
            Settings.PROFILE_GRID_SORT.save("newest");
            first.mmItems = GridSort.data(first, firstModel);
            idle(0);
            GridAdapter next = new GridAdapter();
            grid.setAdapter(next);
            assertSame("the old adapter retains its native view reference", grid, first.mmRecyclerView);
            assertSame(grid, next.mmRecyclerView);
            List<Aweme> nextModel = posts(3);
            next.mmItems = GridSort.data(next, nextModel);
            idle(0);
            selectOrder(next, "off");
            TextView currentControl = chip(next);
            assertNotNull(currentControl);

            first.mmItems = GridSort.page(first, firstModel);
            idle(0);
            assertSame("a late page for the previous adapter can't remove the current route",
                    currentControl, chip(next));
            assertSame(nextModel, next.mmItems);
            selectOrder(next, "oldest");
            assertEquals("the current control still targets its own adapter", "v0v1v2", ids(next.mmItems));
            assertSame("the late page leaves the former adapter in native order", firstModel, first.mmItems);
        }
    }

    @Test public void aNativeAdapterReplacementInvalidatesAnOpenMenuBeforeItsFirstData() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter first = gridOnScreen(controller.get());
            FrameLayout frame = (FrameLayout) first.mmRecyclerView.getParent();
            frame.removeView(first.mmRecyclerView);
            OwnedGrid grid = new OwnedGrid(controller.get());
            frame.addView(grid, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            grid.setAdapter(first);
            idle(50);
            Settings.PROFILE_GRID_SORT.save("newest");
            first.mmItems = GridSort.data(first, grid());
            idle(0);
            List<?> previousItems = first.mmItems;
            assertTrue(chip(first).performClick());
            idle(0);
            AlertDialog menu = (AlertDialog) ShadowDialog.getLatestDialog();
            GridAdapter next = new GridAdapter();
            grid.setAdapter(next);
            Shadows.shadowOf(menu).clickOnItem(GridSort.indexOf("off"));
            idle(0);
            assertEquals("a menu for a detached native adapter cannot change the order",
                    "newest", Settings.PROFILE_GRID_SORT.get());
            assertSame(previousItems, first.mmItems);
            List<Aweme> nextModel = posts(3);
            next.mmItems = GridSort.data(next, nextModel);
            idle(0);
            selectOrder(next, "off");
            assertNotNull("the new native owner's menu still works", chip(next));
        }
    }

    @FunctionalInterface private interface WorkerAction {
        void run() throws Exception;
    }

    private static void onWorker(WorkerAction action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { action.run(); } catch (Throwable ex) { failure.set(ex); }
        }, "grid-sort-settings-test");
        worker.setDaemon(true);
        worker.start();
        worker.join(5_000);
        assertFalse("settings callbacks don't wait for the UI while holding Setting.class", worker.isAlive());
        if (failure.get() != null) throw new AssertionError("background setting write failed", failure.get());
    }

    /** Real preference writes, with optional delivery of notifications captured before unregister. */
    private static final class PreferenceChanges {
        final SharedPreferences delegate = Setting.preferences.preferences;
        SharedPreferences store;
        final Map<SharedPreferences.OnSharedPreferenceChangeListener,
                SharedPreferences.OnSharedPreferenceChangeListener> active = new IdentityHashMap<>();
        final List<Runnable> pending = new ArrayList<>();
        volatile boolean defer;
        volatile boolean registrationsLocked = true;
        volatile boolean failNextCommit;
        int commits;

        PreferenceChanges() {
            store = (SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(), new Class<?>[] {SharedPreferences.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("edit")) {
                            SharedPreferences.Editor editor = delegate.edit();
                            return java.lang.reflect.Proxy.newProxyInstance(
                                    SharedPreferences.Editor.class.getClassLoader(),
                                    new Class<?>[] {SharedPreferences.Editor.class}, (editorProxy, call, values) -> {
                                        boolean fail = false;
                                        if (call.getName().equals("commit")) {
                                            commits++;
                                            fail = failNextCommit;
                                            failNextCommit = false;
                                            if (fail) return false;
                                        }
                                        Object result;
                                        try { result = call.invoke(editor, values); }
                                        catch (java.lang.reflect.InvocationTargetException ex) { throw ex.getCause(); }
                                        return result instanceof SharedPreferences.Editor ? editorProxy : result;
                                    });
                        }
                        if (method.getName().equals("registerOnSharedPreferenceChangeListener")) {
                            registrationsLocked &= Thread.holdsLock(Setting.class);
                            SharedPreferences.OnSharedPreferenceChangeListener listener =
                                    (SharedPreferences.OnSharedPreferenceChangeListener) arguments[0];
                            SharedPreferences.OnSharedPreferenceChangeListener forwarded = (ignored, key) -> {
                                Runnable delivery = () -> listener.onSharedPreferenceChanged(store, key);
                                synchronized (pending) {
                                    if (defer) { pending.add(delivery); return; }
                                }
                                delivery.run();
                            };
                            synchronized (active) { active.put(listener, forwarded); }
                            delegate.registerOnSharedPreferenceChangeListener(forwarded);
                            return null;
                        }
                        if (method.getName().equals("unregisterOnSharedPreferenceChangeListener")) {
                            SharedPreferences.OnSharedPreferenceChangeListener forwarded;
                            synchronized (active) { forwarded = active.remove(arguments[0]); }
                            if (forwarded != null) delegate.unregisterOnSharedPreferenceChangeListener(forwarded);
                            return null;
                        }
                        try { return method.invoke(delegate, arguments); }
                        catch (java.lang.reflect.InvocationTargetException ex) { throw ex.getCause(); }
                    });
        }

        int activeCount() { synchronized (active) { return active.size(); } }
        int pendingCount() { synchronized (pending) { return pending.size(); } }

        void deliverPending() {
            List<Runnable> deliveries;
            synchronized (pending) { deliveries = new ArrayList<>(pending); pending.clear(); }
            for (Runnable delivery : deliveries) delivery.run();
        }

        void notifyNow(String key) {
            List<SharedPreferences.OnSharedPreferenceChangeListener> listeners;
            synchronized (active) { listeners = new ArrayList<>(active.keySet()); }
            for (SharedPreferences.OnSharedPreferenceChangeListener listener : listeners) {
                listener.onSharedPreferenceChanged(store, key);
            }
        }
    }

    @Test public void aBackgroundBatchRestoreBeforeTheNextBindExpiresOriginalRetention() throws Exception {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            List<Aweme> model = grid();
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            selectOrder(adapter, "off");
            assertNotNull(chip(adapter));
            onWorker(() -> {
                Setting.saveAll(Collections.singletonMap(Settings.PROFILE_GRID_SORT, "oldest"));
                Setting.saveAll(Collections.singletonMap(Settings.PROFILE_GRID_SORT, "off"));
            });
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            assertSame(model, adapter.mmItems);
            assertNull("a restored disable doesn't inherit the earlier menu choice", chip(adapter));
        }
    }

    @Test public void latePreferenceCallbacksCannotExpireANewerOriginalMenuChoice() throws Exception {
        PreferenceChanges changes = new PreferenceChanges();
        ReflectionHelpers.setField(Setting.preferences, "preferences", changes.store);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            List<Aweme> model = grid();
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            selectOrder(adapter, "off");
            TextView control = chip(adapter);
            assertEquals(1, changes.activeCount());
            changes.defer = true;
            onWorker(() -> {
                Settings.PROFILE_GRID_SORT.save("oldest");
                Settings.PROFILE_GRID_SORT.save("off");
            });
            idle(0);
            assertTrue("old notifications were captured", changes.pendingCount() >= 2);
            selectOrder(adapter, "off");
            assertEquals("repicking Original replaces its listener", 1, changes.activeCount());
            changes.deliverPending();
            idle(0);
            assertSame("old callbacks can't remove a newer menu choice", control, chip(adapter));
            assertEquals("Original order", chip(adapter).getText().toString());
            assertEquals(1, changes.activeCount());

            changes.defer = false;
            control.performClick();
            idle(0);
            AlertDialog currentMenu = (AlertDialog) ShadowDialog.getLatestDialog();
            Settings.PROFILE_GRID_SORT.save("oldest");
            Settings.PROFILE_GRID_SORT.save("off");
            Shadows.shadowOf(currentMenu).clickOnItem(0);
            idle(0);
            assertSame("an old UI cleanup can't cancel a newer genuine menu choice", control, chip(adapter));
            assertEquals(1, changes.activeCount());
            Settings.PROFILE_GRID_SORT.save("oldest");
            Settings.PROFILE_GRID_SORT.save("off");
            idle(0);
            assertNull("the current listener still expires on a later external change", chip(adapter));
            assertEquals(0, changes.activeCount());
        } finally {
            ReflectionHelpers.setField(Setting.preferences, "preferences", changes.delegate);
        }
        assertEquals("closing the window leaves no listener", 0, changes.activeCount());
    }

    @Test public void originalPreferenceWatchersFollowTheControlLifetimeAndKeepUiOnMain() throws Exception {
        PreferenceChanges changes = new PreferenceChanges();
        ReflectionHelpers.setField(Setting.preferences, "preferences", changes.store);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            List<Aweme> model = grid();
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            assertEquals("ordinary sorted controls don't watch preferences", 0, changes.activeCount());
            selectOrder(adapter, "off");
            assertEquals(1, changes.activeCount());
            changes.store.edit().putString("grid_sort_test_unrelated", "changed").commit();
            assertEquals("unrelated preferences leave retention alone", 1, changes.activeCount());
            selectOrder(adapter, "off");
            assertEquals("only one listener belongs to this retention", 1, changes.activeCount());
            assertTrue("save and listener registration share the existing settings lock", changes.registrationsLocked);
            selectOrder(adapter, "oldest");
            assertEquals("a real sort unregisters the retention listener", 0, changes.activeCount());
            selectOrder(adapter, "off");
            GridSort.apply(adapter, "off");
            assertEquals("explicit disable unregisters the retention listener", 0, changes.activeCount());
            GridSort.apply(adapter, "newest");
            selectOrder(adapter, "off");

            TextView control = chip(adapter);
            changes.defer = true;
            onWorker(() -> {
                synchronized (Setting.class) {
                    Settings.PROFILE_GRID_SORT.save("oldest");
                    Settings.PROFILE_GRID_SORT.save("off");
                    changes.notifyNow(Settings.PROFILE_GRID_SORT.key);
                }
            });
            assertSame("a worker callback doesn't remove Android views", control, chip(adapter));
            assertEquals(View.VISIBLE, control.getVisibility());
            assertEquals("the worker immediately unregisters the expired listener", 0, changes.activeCount());
            idle(0);
            assertNull("main handles the expired control", chip(adapter));
            changes.deliverPending();
            changes.defer = false;

            GridSort.apply(adapter, "newest");
            selectOrder(adapter, "off");
            assertEquals(1, changes.activeCount());
            setPaused(true);
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            assertEquals("Pause unregisters the retention listener", 0, changes.activeCount());
            setPaused(false);

            GridSort.apply(adapter, "newest");
            selectOrder(adapter, "off");
            FrameLayout frame = (FrameLayout) adapter.mmRecyclerView.getParent();
            frame.removeView(chip(adapter));
            assertEquals("direct chip detachment unregisters its listener", 0, changes.activeCount());
            idle(0);
            GridSort.apply(adapter, "newest");
            selectOrder(adapter, "off");
            assertEquals(1, changes.activeCount());
            frame.removeView(adapter.mmRecyclerView);
            idle(0);
            assertEquals("grid detachment unregisters its listener", 0, changes.activeCount());
        } finally {
            ReflectionHelpers.setField(Setting.preferences, "preferences", changes.delegate);
        }
        assertEquals(0, changes.activeCount());
    }

    @Test public void aGridBindWaitsForAWholeSettingsWriteBeforeChoosingItsControl() throws Exception {
        PreferenceChanges changes = new PreferenceChanges();
        ReflectionHelpers.setField(Setting.preferences, "preferences", changes.store);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            List<Aweme> model = grid();
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            selectOrder(adapter, "off");
            TextView control = chip(adapter);
            assertNotNull(control);
            changes.defer = true;
            // Queue afterTikTok before the worker can queue either preference notification.
            adapter.mmItems = GridSort.data(adapter, model);
            Thread main = Thread.currentThread();
            CountDownLatch firstWrite = new CountDownLatch(1);
            AtomicBoolean mainBlocked = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    synchronized (Setting.class) {
                        Settings.PROFILE_GRID_SORT.save("oldest");
                        firstWrite.countDown();
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (main.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                            Thread.sleep(1);
                        }
                        if (main.getState() != Thread.State.BLOCKED) {
                            throw new AssertionError("main never waited on the settings write lock");
                        }
                        mainBlocked.set(true);
                        Settings.PROFILE_GRID_SORT.save("off");
                    }
                } catch (Throwable ex) {
                    failure.set(ex);
                } finally {
                    firstWrite.countDown();
                }
            }, "grid-choice-write-test");
            worker.setDaemon(true);
            worker.start();
            assertTrue(firstWrite.await(5, TimeUnit.SECONDS));
            idle(0);
            worker.join(5_000);
            assertFalse(worker.isAlive());
            if (failure.get() != null) throw new AssertionError("controlled settings write failed", failure.get());
            assertTrue("the queued bind crossed the held settings lock", mainBlocked.get());
            assertEquals("off", Settings.PROFILE_GRID_SORT.get());
            assertTrue("both real preference notifications remain queued", changes.pendingCount() >= 2);
            assertSame(control, chip(adapter));
            assertEquals("render only the completed settings write", "Original order", chip(adapter).getText().toString());
            assertEquals("the bind hasn't dropped the pending retention notifications", 1, changes.activeCount());

            changes.deliverPending();
            adapter.mmRecyclerView.getViewTreeObserver().dispatchOnGlobalLayout();
            idle(0);
            assertNull("external off leaves no stale sorted control", chip(adapter));
            assertEquals(0, changes.activeCount());
            assertSame(model, adapter.mmItems);
        } finally {
            ReflectionHelpers.setField(Setting.preferences, "preferences", changes.delegate);
        }
    }

    private static int observerListeners(ViewTreeObserver observer, String field) {
        Object listeners = ReflectionHelpers.getField(observer, field);
        return listeners == null ? 0 : ((Number) ReflectionHelpers.callInstanceMethod(listeners, "size")).intValue();
    }

    private static int attachListeners(View view) {
        Object info = ReflectionHelpers.getField(view, "mListenerInfo");
        if (info == null) return 0;
        List<?> listeners = ReflectionHelpers.getField(info, "mOnAttachStateChangeListeners");
        return listeners == null ? 0 : listeners.size();
    }

    @Test @Config(sdk = {23, 28})
    public void originalDetachCleansTheLiveWindowAndSortedControlsReattachWithoutABind() {
        PreferenceChanges changes = new PreferenceChanges();
        ReflectionHelpers.setField(Setting.preferences, "preferences", changes.store);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            View grid = adapter.mmRecyclerView;
            FrameLayout frame = (FrameLayout) grid.getParent();
            ViewTreeObserver window = frame.getViewTreeObserver();
            int scrolls = observerListeners(window, "mOnScrollChangedListeners");
            int layouts = observerListeners(window, "mOnGlobalLayoutListeners");
            int attached = attachListeners(grid);
            List<Aweme> model = grid();
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            selectOrder(adapter, "off");
            assertEquals(scrolls + 1, observerListeners(window, "mOnScrollChangedListeners"));
            assertEquals(layouts + 1, observerListeners(window, "mOnGlobalLayoutListeners"));
            assertEquals(attached + 1, attachListeners(grid));
            assertEquals(1, changes.activeCount());

            frame.removeView(grid);
            idle(0);
            assertTrue("the former window is still live", window.isAlive());
            assertTrue("the activity still owns that window", window == frame.getViewTreeObserver());
            assertFalse("the detached view now exposes a floating observer", window == grid.getViewTreeObserver());
            assertEquals("remove from the captured live window", scrolls, observerListeners(window, "mOnScrollChangedListeners"));
            assertEquals(layouts, observerListeners(window, "mOnGlobalLayoutListeners"));
            assertEquals(attached, attachListeners(grid));
            assertEquals(0, changes.activeCount());
            assertNull(frame.findViewWithTag(GridSort.CHIP_TAG));

            frame.addView(grid);
            idle(50);
            assertNull("Original doesn't return when the cached view returns", chip(adapter));
            GridSort.apply(adapter, "newest");
            idle(0);
            TextView sortedControl = chip(adapter);
            for (int i = 0; i < 2; i++) {
                frame.removeView(grid);
                idle(0);
                assertEquals("a detached sorted tree doesn't retain the window", scrolls,
                        observerListeners(window, "mOnScrollChangedListeners"));
                assertEquals(layouts, observerListeners(window, "mOnGlobalLayoutListeners"));
                assertEquals("the cached sorted tree keeps one reattach listener", attached + 1, attachListeners(grid));
                frame.addView(grid);
                idle(50);
                assertSame("reattach needs no native list bind", sortedControl, chip(adapter));
                assertEquals(scrolls + 1, observerListeners(window, "mOnScrollChangedListeners"));
                assertEquals(layouts + 1, observerListeners(window, "mOnGlobalLayoutListeners"));
                assertEquals(attached + 1, attachListeners(grid));
            }
            selectOrder(adapter, "off");
            frame.removeView(grid);
            idle(0);
            assertEquals(scrolls, observerListeners(window, "mOnScrollChangedListeners"));
            assertEquals(layouts, observerListeners(window, "mOnGlobalLayoutListeners"));
            assertEquals(attached, attachListeners(grid));
            assertEquals(0, changes.activeCount());
        } finally {
            ReflectionHelpers.setField(Setting.preferences, "preferences", changes.delegate);
        }
    }

    @Test @Config(sdk = {23, 28}, qualifiers = "w360dp-h640dp-port-mdpi")
    public void gridSortTargetsCoverFortyEightDpAtDefaultText() {
        exerciseGridSortTargets(false);
    }

    @Test @Config(sdk = {23, 28}, qualifiers = "w360dp-h640dp-port-mdpi", fontScale = 2f)
    public void gridSortTargetsCoverFortyEightDpAtDoubleText() {
        exerciseGridSortTargets(false);
    }

    @Test @Config(sdk = {23, 28}, qualifiers = "de-rDE-w320dp-h640dp-port-mdpi", fontScale = 2f)
    public void gridSortTargetsFitNarrowGermanAtDoubleText() {
        exerciseGridSortTargets(true);
    }

    private static boolean tap(FrameLayout frame, float x, float y) {
        android.view.MotionEvent down = android.view.MotionEvent.obtain(0, 0,
                android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(0, 1,
                android.view.MotionEvent.ACTION_UP, x, y, 0);
        try {
            boolean handled = frame.dispatchTouchEvent(down);
            return frame.dispatchTouchEvent(up) || handled;
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static void exerciseGridSortTargets(boolean german) {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            if (german) assertEquals("de", L10n.tableTag(activity));
            FrameLayout frame = new FrameLayout(activity);
            FrameLayout grid = new FrameLayout(activity);
            frame.addView(grid, new FrameLayout.LayoutParams(-1, -1));
            int[] nativeClicks = {0};
            View cell = new View(activity);
            cell.setOnClickListener(ignored -> nativeClicks[0]++);
            FrameLayout.LayoutParams cells = new FrameLayout.LayoutParams(-1, -1);
            cells.topMargin = SettingsUi.dp(activity, 72);
            grid.addView(cell, cells);
            TextView note = new TextView(activity);
            note.setText("Only you can see your liked videos");
            grid.addView(note, new FrameLayout.LayoutParams(-1, SettingsUi.dp(activity, 72)));
            activity.setContentView(frame);
            idle(50);
            GridAdapter adapter = new GridAdapter();
            adapter.mmRecyclerView = grid;
            adapter.listener = null;
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, grid());
            idle(50);
            for (String order : GridSort.ORDERS) {
                selectOrder(adapter, order);
                idle(50);
                TextView control = chip(adapter);
                int target = SettingsUi.dp(activity, 48);
                assertTrue(order + " touch width", control.getWidth() >= target);
                assertTrue(order + " touch height", control.getHeight() >= target);
                android.view.accessibility.AccessibilityNodeInfo node = control.createAccessibilityNodeInfo();
                android.graphics.Rect bounds = new android.graphics.Rect();
                node.getBoundsInScreen(bounds);
                assertTrue(order + " accessibility width", bounds.width() >= target);
                assertTrue(order + " accessibility height", bounds.height() >= target);
                assertTrue(node.isClickable());
                node.recycle();
                assertTrue(control.getLeft() >= grid.getLeft());
                assertTrue(control.getRight() <= grid.getRight());
                assertTrue("the note stays uncovered", control.getY() >= note.getBottom());
            }
            TextView control = chip(adapter);
            assertTrue("the bottom of the target opens the menu", tap(frame,
                    control.getX() + control.getWidth() / 2f, control.getY() + control.getHeight() - 2));
            idle(50);
            AlertDialog menu = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(menu.isShowing());
            menu.dismiss();
            idle(50);
            float nativeY = grid.getHeight() - SettingsUi.dp(activity, 24);
            assertTrue(tap(frame, SettingsUi.dp(activity, 16), nativeY));
            idle(50);
            assertEquals("a cell outside the target keeps its tap", 1, nativeClicks[0]);
            GridSort.apply(adapter, "off");
            idle(50);
            assertNull(chip(adapter));
            assertTrue(tap(frame, SettingsUi.dp(activity, 16), nativeY));
            idle(50);
            assertEquals(2, nativeClicks[0]);
            GridSort.apply(adapter, "newest");
            idle(50);
            assertNotNull(chip(adapter));
            setPaused(true);
            grid.getViewTreeObserver().dispatchOnGlobalLayout();
            idle(50);
            assertNull("Pause removes the expanded control", chip(adapter));
        }
    }

    @Test @Config(sdk = {23, 28})
    public void aCachedSortedControlHidesImmediatelyAndReturnsOnceWithoutRebinding() {
        PreferenceChanges changes = new PreferenceChanges();
        ReflectionHelpers.setField(Setting.preferences, "preferences", changes.store);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            View grid = adapter.mmRecyclerView;
            FrameLayout frame = (FrameLayout) grid.getParent();
            ViewTreeObserver window = frame.getViewTreeObserver();
            int scrolls = observerListeners(window, "mOnScrollChangedListeners");
            int layouts = observerListeners(window, "mOnGlobalLayoutListeners");
            int attached = attachListeners(grid);
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, grid());
            idle(50);
            TextView control = chip(adapter);
            assertNotNull(control);
            List<?> displayed = adapter.mmItems;
            int refreshes = adapter.refreshes;
            for (int cycle = 0; cycle < 3; cycle++) {
                assertTrue(control.isShown());
                frame.removeView(grid);
                assertEquals("hide during detach, before queued work", View.GONE, control.getVisibility());
                assertFalse(control.isShown());
                float x = control.getLeft() + control.getWidth() / 2f;
                float y = control.getTop() + control.getHeight() / 2f;
                android.view.MotionEvent down = android.view.MotionEvent.obtain(0, 0,
                        android.view.MotionEvent.ACTION_DOWN, x, y, 0);
                android.view.MotionEvent up = android.view.MotionEvent.obtain(0, 1,
                        android.view.MotionEvent.ACTION_UP, x, y, 0);
                try {
                    assertFalse("the hidden control cannot receive a touch", frame.dispatchTouchEvent(down));
                    assertFalse(frame.dispatchTouchEvent(up));
                } finally {
                    down.recycle();
                    up.recycle();
                }
                idle(0);
                assertSame("keep the sorted control for the cached tree", frame, control.getParent());
                assertEquals(scrolls, observerListeners(window, "mOnScrollChangedListeners"));
                assertEquals(layouts, observerListeners(window, "mOnGlobalLayoutListeners"));
                assertEquals(attached + 1, attachListeners(grid));
                assertEquals(0, changes.activeCount());

                frame.addView(grid);
                idle(50);
                assertSame(control, chip(adapter));
                assertTrue(control.isShown());
                assertEquals("Newest first", control.getText().toString());
                assertSame("no list rebind", displayed, adapter.mmItems);
                assertEquals(refreshes, adapter.refreshes);
                assertEquals(scrolls + 1, observerListeners(window, "mOnScrollChangedListeners"));
                assertEquals(layouts + 1, observerListeners(window, "mOnGlobalLayoutListeners"));
                assertEquals(attached + 1, attachListeners(grid));
                assertEquals(0, changes.activeCount());
                control.performClick();
                idle(0);
                AlertDialog menu = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue("the returned control opens its owned menu", menu.isShowing());
                assertEquals(1, menu.getListView().getCheckedItemPosition());
                menu.dismiss();
            }
            selectOrder(adapter, "off");
            frame.removeView(grid);
            frame.addView(grid);
            idle(50);
            assertNull("Original retention expires even on immediate reattach", chip(adapter));
            assertEquals(scrolls, observerListeners(window, "mOnScrollChangedListeners"));
            assertEquals(layouts, observerListeners(window, "mOnGlobalLayoutListeners"));
            assertEquals(attached, attachListeners(grid));
            assertEquals(0, changes.activeCount());
        } finally {
            ReflectionHelpers.setField(Setting.preferences, "preferences", changes.delegate);
        }
    }

    @Test @Config(sdk = {23, 28})
    public void aCachedSortedGridMovesItsControlToItsNewParentWithoutRebinding() {
        PreferenceChanges changes = new PreferenceChanges();
        ReflectionHelpers.setField(Setting.preferences, "preferences", changes.store);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            FrameLayout first = new FrameLayout(activity);
            FrameLayout second = new FrameLayout(activity);
            root.addView(first, new FrameLayout.LayoutParams(-1, -1));
            root.addView(second, new FrameLayout.LayoutParams(-1, -1));
            OwnedGrid grid = new OwnedGrid(activity);
            first.addView(grid, new FrameLayout.LayoutParams(-1, -1));
            GridAdapter adapter = new GridAdapter();
            grid.setAdapter(adapter);
            activity.setContentView(root);
            idle(50);
            ViewTreeObserver window = root.getViewTreeObserver();
            int scrolls = observerListeners(window, "mOnScrollChangedListeners");
            int layouts = observerListeners(window, "mOnGlobalLayoutListeners");
            int attached = attachListeners(grid);
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, grid());
            idle(50);
            List<?> displayed = adapter.mmItems;
            FrameLayout from = first;
            FrameLayout to = second;
            for (int cycle = 0; cycle < 3; cycle++) {
                TextView oldControl = chip(adapter);
                from.removeView(grid);
                assertEquals(View.GONE, oldControl.getVisibility());
                to.addView(grid, new FrameLayout.LayoutParams(-1, -1));
                idle(50);
                assertNull("no orphan control in the previous parent", from.findViewWithTag(GridSort.CHIP_TAG));
                assertNull(oldControl.getParent());
                TextView moved = chip(adapter);
                assertNotNull("the new parent owns a control without a list bind", moved);
                assertTrue(moved.isShown());
                assertSame(to, moved.getParent());
                assertSame(displayed, adapter.mmItems);
                assertEquals(0, changes.activeCount());
                assertEquals(scrolls + 1, observerListeners(window, "mOnScrollChangedListeners"));
                assertEquals(layouts + 1, observerListeners(window, "mOnGlobalLayoutListeners"));
                assertEquals(attached + 1, attachListeners(grid));
                moved.performClick();
                idle(0);
                AlertDialog menu = (AlertDialog) ShadowDialog.getLatestDialog();
                assertTrue(menu.isShowing());
                assertEquals(1, menu.getListView().getCheckedItemPosition());
                menu.dismiss();
                FrameLayout swap = from;
                from = to;
                to = swap;
            }
            selectOrder(adapter, "oldest");
            assertEquals("oldest", Settings.PROFILE_GRID_SORT.get());
            assertEquals("apfcdeb", ids(adapter.mmItems));
            assertEquals("Oldest first", chip(adapter).getText().toString());
        } finally {
            ReflectionHelpers.setField(Setting.preferences, "preferences", changes.delegate);
        }
    }

    @Test @Config(sdk = {23, 28})
    public void aFloatingGridObserverMergesOnceAndCleansItsActualWindow() {
        PreferenceChanges changes = new PreferenceChanges();
        ReflectionHelpers.setField(Setting.preferences, "preferences", changes.store);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            ViewTreeObserver window = activity.getWindow().getDecorView().getViewTreeObserver();
            int scrolls = observerListeners(window, "mOnScrollChangedListeners");
            int layouts = observerListeners(window, "mOnGlobalLayoutListeners");
            FrameLayout frame = new FrameLayout(activity);
            OwnedGrid grid = new OwnedGrid(activity);
            frame.addView(grid, new FrameLayout.LayoutParams(-1, -1));
            GridAdapter adapter = new GridAdapter();
            grid.setAdapter(adapter);
            ViewTreeObserver floating = grid.getViewTreeObserver();
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, grid());
            idle(0);
            assertFalse(grid.isAttachedToWindow());
            assertEquals(1, observerListeners(floating, "mOnScrollChangedListeners"));
            assertEquals(1, observerListeners(floating, "mOnGlobalLayoutListeners"));
            assertEquals(1, attachListeners(grid));

            activity.setContentView(frame);
            idle(50);
            assertFalse("Android merged and killed the floating observer", floating.isAlive());
            assertTrue("the grid now uses the live window observer", window == grid.getViewTreeObserver());
            assertEquals("merged scroll registration appears exactly once", scrolls + 1,
                    observerListeners(window, "mOnScrollChangedListeners"));
            assertEquals(layouts + 1, observerListeners(window, "mOnGlobalLayoutListeners"));
            selectOrder(adapter, "off");
            assertEquals(1, changes.activeCount());
            frame.removeView(grid);
            idle(0);
            assertTrue(window.isAlive());
            assertEquals(scrolls, observerListeners(window, "mOnScrollChangedListeners"));
            assertEquals(layouts, observerListeners(window, "mOnGlobalLayoutListeners"));
            assertEquals(0, attachListeners(grid));
            assertEquals(0, changes.activeCount());
            assertNull(frame.findViewWithTag(GridSort.CHIP_TAG));
        } finally {
            ReflectionHelpers.setField(Setting.preferences, "preferences", changes.delegate);
        }
    }

    @Test @Config(sdk = {23, 28})
    public void aNativeOwnerChangeExpiresTheOldControlOnLayoutWithoutANewBind() {
        PreferenceChanges changes = new PreferenceChanges();
        ReflectionHelpers.setField(Setting.preferences, "preferences", changes.store);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            GridAdapter first = gridOnScreen(controller.get());
            FrameLayout frame = (FrameLayout) first.mmRecyclerView.getParent();
            frame.removeView(first.mmRecyclerView);
            OwnedGrid grid = new OwnedGrid(controller.get());
            frame.addView(grid, new FrameLayout.LayoutParams(-1, -1));
            grid.setAdapter(first);
            idle(50);
            ViewTreeObserver window = grid.getViewTreeObserver();
            int scrolls = observerListeners(window, "mOnScrollChangedListeners");
            int layouts = observerListeners(window, "mOnGlobalLayoutListeners");
            int attached = attachListeners(grid);
            Settings.PROFILE_GRID_SORT.save("newest");
            first.mmItems = GridSort.data(first, grid());
            idle(0);
            selectOrder(first, "off");
            assertNotNull(chip(first));
            assertEquals(1, changes.activeCount());
            GridAdapter next = new GridAdapter();
            grid.setAdapter(next);
            window.dispatchOnGlobalLayout();
            idle(0);
            assertNull("layout expires a control for a known former native owner", chip(next));
            assertEquals(0, changes.activeCount());
            assertEquals(scrolls, observerListeners(window, "mOnScrollChangedListeners"));
            assertEquals(layouts, observerListeners(window, "mOnGlobalLayoutListeners"));
            assertEquals(attached, attachListeners(grid));
            assertNull("the replacement adapter hasn't bound a list", next.mmItems);
        } finally {
            ReflectionHelpers.setField(Setting.preferences, "preferences", changes.delegate);
        }
    }

    private static void assertSamePosts(List<?> expected, List<?> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertSame("post at " + i, expected.get(i), actual.get(i));
    }

    @Test public void aFailedOriginalSaveKeepsTheSortedGridAndViewerUntilOneSuccessfulRetry() {
        PreferenceChanges changes = new PreferenceChanges();
        ReflectionHelpers.setField(Setting.preferences, "preferences", changes.store);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            GridFragment fragment = new GridFragment(adapter);
            List<Aweme> model = grid();
            List<Aweme> nativeOrder = new ArrayList<>(model);
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, model);
            idle(GridSort.NEXT_PAGE_DELAY_MS + 100);
            GridSort.beforeOpen(fragment);
            List<?> displayed = adapter.mmItems;
            List<?> viewerOrder = new ArrayList<>(model);
            TextView control = chip(adapter);
            int refreshes = adapter.refreshes;
            int loads = adapter.listener.calls;
            int commits = changes.commits;
            changes.failNextCommit = true;
            ShadowToast.reset();

            selectOrder(adapter, "off");
            assertSame("a failed save cannot replace the displayed list", displayed, adapter.mmItems);
            assertSamePosts(viewerOrder, model);
            assertSamePosts(displayed, adapter.mmItems);
            assertEquals("newest", Settings.PROFILE_GRID_SORT.get());
            assertEquals("newest", changes.delegate.getString(Settings.PROFILE_GRID_SORT.key, "off"));
            assertEquals("one refused commit and its rollback", commits + 2, changes.commits);
            assertEquals(refreshes, adapter.refreshes);
            assertSame(control, chip(adapter));
            assertEquals("Newest first", control.getText().toString());
            assertEquals(0, changes.activeCount());
            assertEquals("Couldn't save the change. Try again.", ShadowToast.getTextOfLatestToast());
            assertEquals(1, ShadowToast.shownToastCount());
            idle(GridSort.NEXT_PAGE_DELAY_MS + 100);
            assertEquals("a failed selection starts no next page", loads, adapter.listener.calls);

            selectOrder(adapter, "off");
            assertEquals("off", Settings.PROFILE_GRID_SORT.get());
            assertEquals(commits + 3, changes.commits);
            assertEquals("only the successful retry refreshes", refreshes + 1, adapter.refreshes);
            assertSamePosts(nativeOrder, adapter.mmItems);
            GridSort.beforeOpen(fragment);
            assertSamePosts(nativeOrder, model);
            assertSame(control, chip(adapter));
            assertEquals("Original order", control.getText().toString());
            assertEquals(1, changes.activeCount());
            assertEquals("retry adds no failure notice", 1, ShadowToast.shownToastCount());
            idle(GridSort.NEXT_PAGE_DELAY_MS + 100);
            assertEquals(loads, adapter.listener.calls);
        } finally {
            ReflectionHelpers.setField(Setting.preferences, "preferences", changes.delegate);
        }
        assertEquals(0, changes.activeCount());
    }

    @Test public void aFailedNewSortSaveRestoresOriginalRetentionAndIgnoresItsOldCallbacks() throws Exception {
        PreferenceChanges changes = new PreferenceChanges();
        ReflectionHelpers.setField(Setting.preferences, "preferences", changes.store);
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            GridAdapter adapter = gridOnScreen(controller.get());
            List<Aweme> model = grid();
            Settings.PROFILE_GRID_SORT.save("newest");
            adapter.mmItems = GridSort.data(adapter, model);
            idle(0);
            selectOrder(adapter, "off");
            adapter.mmItems = GridSort.page(adapter, model);
            idle(GridSort.NEXT_PAGE_DELAY_MS + 100);
            TextView control = chip(adapter);
            List<?> displayed = adapter.mmItems;
            List<?> nativeOrder = new ArrayList<>(model);
            int refreshes = adapter.refreshes;
            int loads = adapter.listener.calls;
            changes.defer = true;
            onWorker(() -> {
                Settings.PROFILE_GRID_SORT.save("oldest");
                Settings.PROFILE_GRID_SORT.save("off");
            });
            idle(0);
            assertTrue("old listener notifications are queued", changes.pendingCount() >= 2);
            int commits = changes.commits;
            changes.failNextCommit = true;
            ShadowToast.reset();

            selectOrder(adapter, "oldest");
            assertSame("the failed save keeps Original's current route", control, chip(adapter));
            assertSame(displayed, adapter.mmItems);
            assertSamePosts(nativeOrder, model);
            assertSamePosts(nativeOrder, adapter.mmItems);
            assertEquals("off", Settings.PROFILE_GRID_SORT.get());
            assertEquals("off", changes.delegate.getString(Settings.PROFILE_GRID_SORT.key, "off"));
            assertEquals(commits + 2, changes.commits);
            assertEquals(refreshes, adapter.refreshes);
            assertEquals("Original order", control.getText().toString());
            assertEquals("the failed save restores exactly one retention listener", 1, changes.activeCount());
            assertEquals("Couldn't save the change. Try again.", ShadowToast.getTextOfLatestToast());
            Map<?, ?> asked = ReflectionHelpers.getStaticField(GridSort.class, "ASKED");
            assertFalse("a refused save cannot queue a next-page request", asked.containsKey(adapter));
            changes.deliverPending();
            idle(GridSort.NEXT_PAGE_DELAY_MS + 100);
            assertSame("old callbacks cannot expire the restored retention", control, chip(adapter));
            assertEquals(1, changes.activeCount());
            assertEquals(loads, adapter.listener.calls);

            changes.defer = false;
            selectOrder(adapter, "oldest");
            assertEquals("oldest", Settings.PROFILE_GRID_SORT.get());
            assertEquals(commits + 3, changes.commits);
            assertEquals("only one successful retry refreshes", refreshes + 1, adapter.refreshes);
            assertEquals("apfcdeb", ids(adapter.mmItems));
            assertSamePosts(nativeOrder, model);
            assertSame(control, chip(adapter));
            assertEquals("Oldest first", control.getText().toString());
            assertEquals("a real sort removes the restored listener", 0, changes.activeCount());
            idle(GridSort.NEXT_PAGE_DELAY_MS + 100);
            assertEquals("only the successful retry asks for a page", loads + 1, adapter.listener.calls);
            assertEquals(1, ShadowToast.shownToastCount());
        } finally {
            ReflectionHelpers.setField(Setting.preferences, "preferences", changes.delegate);
        }
        assertEquals(0, changes.activeCount());
    }

    @Test public void tiesAndMissingValuesKeepTikToksOrder() {
        List<Aweme> grid = new ArrayList<>(Arrays.asList(
                new Post("x", 0, 0, 0, 0, false),
                new Post("y", 5, 5_000, 5, 5, false),
                new Post("z", 0, 0, 0, 0, false),
                new Post("w", 5, 5_000, 5, 5, false)));
        assertEquals("ywxz", order("newest", grid));
        assertEquals("ywxz", order("longest", grid));
        assertEquals("ywxz", order("most_viewed", grid));
        // The same call twice gives the same grid.
        assertEquals(ids(GridSort.data(null,grid)), ids(GridSort.data(null,grid)));
    }

    @Test public void sortingLeavesTheListTikTokOwnsAloneAndNothingIsLostOrRepeated() {
        List<Aweme> grid = grid();
        String before = ids(grid);
        Settings.PROFILE_GRID_SORT.save("newest");
        List<?> sorted = GridSort.data(null,grid);
        assertNotSame(grid, sorted);
        assertEquals("TikTok's list keeps its order", before, ids(grid));
        assertEquals(grid.size(), sorted.size());
        for (Aweme post : grid) assertTrue("same post objects", sorted.contains(post));
    }

    /** TikTok's grid fragment: the adapter sits in an obfuscated field among others. */
    public static final class GridFragment {
        final String title = "Videos";
        final List<Aweme> recent = new ArrayList<>();
        final GridAdapter adapter;

        GridFragment(GridAdapter adapter) {
            this.adapter = adapter;
        }
    }

    @Test public void openingAVideoFromASortedGridSwipesInTheGridsOrder() {
        GridAdapter adapter = new GridAdapter();
        GridFragment fragment = new GridFragment(adapter);
        Settings.PROFILE_GRID_SORT.save("newest");
        List<Aweme> model = grid();
        List<Aweme> posts = new ArrayList<>(model);
        adapter.mmItems = GridSort.data(adapter, model);
        assertEquals("abcdefp", ids(model));

        GridSort.beforeOpen(fragment);
        assertEquals("the video page plays the grid's order", "apecdfb", ids(model));
        assertEquals(posts.size(), model.size());
        for (Aweme post : posts) assertTrue("the same post objects", model.contains(post));
        GridSort.beforeOpen(fragment);
        assertEquals("a second open changes nothing", "apecdfb", ids(model));

        // TikTok appends the next page to the list it now holds in the grid's order.
        model.add(new Post("g", 250, 1_000, 1, 1, false));
        adapter.mmItems = GridSort.page(adapter, model);
        assertEquals("apecdgfb", ids(adapter.mmItems));
        GridSort.apply(adapter, "oldest");
        assertEquals("a new order sorts TikTok's own order, not the written one",
                "apfgcdeb", ids(adapter.mmItems));
        GridSort.beforeOpen(fragment);
        assertEquals("apfgcdeb", ids(model));

        GridSort.apply(adapter, "off");
        assertEquals("Original order is TikTok's, the new video where TikTok put it",
                "abcdefpg", ids(adapter.mmItems));
        GridSort.apply(adapter, "newest");
        GridSort.beforeOpen(fragment);
        assertEquals("apecdgfb", ids(model));
        Settings.PROFILE_GRID_SORT.save("off");
        assertSame("with no order TikTok's list gets its own order back and the grid shares it",
                model, GridSort.data(adapter, model));
        assertEquals("abcdefpg", ids(model));
    }

    @Test public void aFreshLoadAfterAnOpenIsTikToksOrderAgain() {
        GridAdapter adapter = new GridAdapter();
        GridFragment fragment = new GridFragment(adapter);
        Settings.PROFILE_GRID_SORT.save("newest");
        List<Aweme> model = grid();
        adapter.mmItems = GridSort.data(adapter, model);
        GridSort.beforeOpen(fragment);

        List<Aweme> refreshed = grid();
        adapter.mmItems = GridSort.data(adapter, refreshed);
        assertEquals("apecdfb", ids(adapter.mmItems));
        GridSort.apply(adapter, "off");
        assertEquals("abcdefp", ids(adapter.mmItems));
    }

    @Test public void onlyTheSameVideosAreEverWrittenIntoTikToksList() {
        Post a = new Post("a", 1, 1, 1, 1, false);
        Post b = new Post("b", 2, 1, 1, 1, false);
        Post c = new Post("c", 3, 1, 1, 1, false);
        List<Object> target = new ArrayList<>(Arrays.asList(a, b, c));
        assertTrue(GridSort.follow(target, Arrays.asList(c, a, b)));
        assertEquals("cab", ids(target));
        assertFalse("a video the list doesn't hold", GridSort.follow(target, Arrays.asList(c, a, new Post("x", 1, 1, 1, 1, false))));
        assertFalse("one video twice", GridSort.follow(target, Arrays.asList(c, c, b)));
        assertFalse("another size", GridSort.follow(target, Arrays.asList(c, a)));
        assertEquals("cab", ids(target));
        List<Object> fixed = List.of(a, b, c);
        assertFalse("a list that can't change", GridSort.follow(fixed, Arrays.asList(c, b, a)));
        assertTrue("already in that order", GridSort.follow(fixed, Arrays.asList(a, b, c)));

        assertEquals("ba then the new one", "bac", ids(GridSort.rebuilt(Arrays.asList(b, a), Arrays.asList(a, c, b))));
        assertEquals("a video that left is dropped", "bc", ids(GridSort.rebuilt(Arrays.asList(b, a), Arrays.asList(c, b))));
        assertNull("nothing in common is a fresh list", GridSort.rebuilt(Arrays.asList(a), Arrays.asList(b, c)));
        assertNull("a video twice", GridSort.rebuilt(Arrays.asList(a, b), Arrays.asList(a, b, a)));

        GridSort.beforeOpen(null);
        GridSort.beforeOpen(new GridFragment(new GridAdapter()));
    }

    @Test public void aNewPageIsSortedInWithTheRestWithoutRepeats() {
        List<Aweme> model = new ArrayList<>(Arrays.asList(
                new Post("a", 10, 1_000, 1, 1, false),
                new Post("b", 30, 1_000, 1, 1, false)));
        Settings.PROFILE_GRID_SORT.save("newest");
        assertEquals("ba", ids(GridSort.data(null,model)));
        // TikTok appends the next page to its own list and hands the whole list over again.
        model.add(new Post("c", 20, 1_000, 1, 1, false));
        model.add(new Post("d", 40, 1_000, 1, 1, false));
        Adapter adapter = new Adapter();
        List<?> kept = GridSort.page(adapter, model);
        assertEquals("dbca", ids(kept));
        assertEquals("a refresh is queued behind TikTok's own call", 0, adapter.refreshes);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(1, adapter.refreshes);
    }

    @Test public void aPageThatMovesNothingIsNotRefreshedAndOffLeavesTikToksAnnouncement() {
        List<Aweme> model = new ArrayList<>(Arrays.asList(
                new Post("b", 30, 1_000, 1, 1, false),
                new Post("a", 10, 1_000, 1, 1, false)));
        Settings.PROFILE_GRID_SORT.save("newest");
        Adapter adapter = new Adapter();
        GridSort.page(adapter, model);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("already in order, nothing moved", 0, adapter.refreshes);

        Settings.PROFILE_GRID_SORT.save("off");
        Adapter quiet = new Adapter();
        assertSame(model, GridSort.page(quiet, model));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("off, TikTok's own announcement stands", 0, quiet.refreshes);
    }

    @Test public void pausedHushfeedAndUnknownChoicesLeaveTikToksOrder() {
        List<Aweme> grid = grid();
        Settings.PROFILE_GRID_SORT.save("newest");
        setPaused(true);
        assertSame("paused", grid, GridSort.data(null,grid));
        setPaused(false);
        assertNotSame(grid, GridSort.data(null,grid));

        Settings.PROFILE_GRID_SORT.save("sideways");
        assertSame("a choice this build doesn't know", grid, GridSort.data(null,grid));
        assertSame(null, GridSort.data(null,null));
        List<Aweme> one = new ArrayList<>(List.of(new Post("a", 1, 1, 1, 1, false)));
        Settings.PROFILE_GRID_SORT.save("newest");
        assertSame("one video has no order", one, GridSort.data(null,one));
    }

    @Test public void thingsThatAreNotPostsStayWhereTheyAre() {
        List<Object> mixed = new ArrayList<>(Arrays.asList("note", new Post("a", 5, 1, 1, 1, false), 7,
                new Post("b", 9, 1, 1, 1, false)));
        Settings.PROFILE_GRID_SORT.save("newest");
        assertEquals("banote7", ids(GridSort.data(null,mixed)));
    }

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Test public void theRowSaysLoadedVideosAndShowsOnlyWhereTheAdapterWasHooked() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.alwaysShowPublishDateEnabled = true;

            SettingsStatus.gridSortEnabled = false;
            assertEquals(null, build(activity).findPreference(Settings.PROFILE_GRID_SORT.key));

            SettingsStatus.gridSortEnabled = true;
            Preference row = build(activity).findPreference(Settings.PROFILE_GRID_SORT.key);
            assertTrue(row instanceof ListPreference);
            assertEquals("Sort loaded videos on profile grids", String.valueOf(row.getTitle()));
            ListPreference list = (ListPreference) row;
            assertEquals("Original order", String.valueOf(list.getEntries()[0]));
            assertEquals("off", String.valueOf(list.getEntryValues()[0]));
            assertEquals(9, list.getEntryValues().length);
            for (int i = 0; i < GridSort.ORDERS.length; i++) {
                assertEquals("the row and the grid's chip list the same orders",
                        GridSort.ORDERS[i], String.valueOf(list.getEntryValues()[i]));
                assertEquals(GridSort.LABELS[i], String.valueOf(list.getEntries()[i]));
            }
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("pinned") && summary.contains("up to 300 videos")
                    && summary.contains("top corner"));
        }
    }

    @Test public void theLikedRowShowsOnlyWhereTheTabNumberWasHooked() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.alwaysShowPublishDateEnabled = true;
            SettingsStatus.gridSortEnabled = true;

            SettingsStatus.likedGridSortEnabled = false;
            assertEquals(null, build(activity).findPreference(Settings.LIKED_GRID_SORT.key));

            SettingsStatus.likedGridSortEnabled = true;
            Preference row = build(activity).findPreference(Settings.LIKED_GRID_SORT.key);
            assertTrue(row instanceof ListPreference);
            assertEquals("Sort loaded videos on Liked grids", String.valueOf(row.getTitle()));
            ListPreference list = (ListPreference) row;
            assertEquals("Same as profile grids", String.valueOf(list.getEntries()[0]));
            assertEquals("profile", String.valueOf(list.getEntryValues()[0]));
            assertEquals(GridSort.ORDERS.length + 1, list.getEntryValues().length);
            for (int i = 0; i < GridSort.ORDERS.length; i++) {
                assertEquals(GridSort.ORDERS[i], String.valueOf(list.getEntryValues()[i + 1]));
                assertEquals(GridSort.LABELS[i], String.valueOf(list.getEntries()[i + 1]));
            }
        }
    }

    @Test public void theSoundPageRowShowsOnlyWhereTheDetailAdapterWasHooked() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.alwaysShowPublishDateEnabled = true;
            SettingsStatus.gridSortEnabled = true;

            SettingsStatus.soundGridSortEnabled = false;
            assertEquals(null, build(activity).findPreference(Settings.SOUND_GRID_SORT.key));

            SettingsStatus.soundGridSortEnabled = true;
            Preference row = build(activity).findPreference(Settings.SOUND_GRID_SORT.key);
            assertTrue(row instanceof ListPreference);
            assertEquals("Sort loaded videos on sound pages", String.valueOf(row.getTitle()));
            ListPreference list = (ListPreference) row;
            assertEquals(GridSort.ORDERS.length, list.getEntryValues().length);
            for (int i = 0; i < GridSort.ORDERS.length; i++) {
                assertEquals(GridSort.ORDERS[i], String.valueOf(list.getEntryValues()[i]));
                assertEquals(GridSort.LABELS[i], String.valueOf(list.getEntries()[i]));
            }
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("sound's page") && summary.contains("up to 300"));
        }
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new InterfacePreferenceCategory(activity, screen);
        return screen;
    }
}

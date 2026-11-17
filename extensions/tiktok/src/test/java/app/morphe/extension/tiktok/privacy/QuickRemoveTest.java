package app.morphe.extension.tiktok.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.view.View;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.feed.GridSort;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsMenuPreference;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

/**
 * Tap a video to remove it (#401): with the switch on, a tap in your own Liked videos, Favorites
 * or Reposts sends the one-video removal and takes the video off the grid, and every other grid
 * opens videos as usual. Reached by reflection, so it compiles against code without the switch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h800dp-night-mdpi")
public class QuickRemoveTest {
    private static final String QUICK_REMOVE = "app.morphe.extension.tiktok.privacy.QuickRemove";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Activity activity;
    private ShadowLooper looper;
    private FakeTikTok.Account account;
    private final List<Runnable> queued = new ArrayList<>();

    @Before public void setUp() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        Utils.setContext(activity);
        looper = Shadows.shadowOf(Looper.getMainLooper());
        SignedInUser.idForTests = "7001";
        SignedInUser.secIdForTests = "MS4wLjABAAAAtest";
        account = new FakeTikTok.Account().withLikes(3).withFavorites(3).withReposts(3);
        set("servicesForTests", account);
        set("executorForTests", (Executor) Runnable::run);
        tapToRemove().save(true);
    }

    @After public void tearDown() throws Exception {
        set("servicesForTests", null);
        set("executorForTests", null);
        pause(false);
        SignedInUser.idForTests = null;
        SignedInUser.secIdForTests = null;
        SettingsStatus.accountCleanupEnabled = false;
        SettingsStatus.ghostModeEnabled = false;
        SettingsStatus.class.getField("quickRemoveEnabled").setBoolean(null, false);
        activity.finish();
    }

    // The grids.

    @Test public void aTapInYourOwnLikedVideosUnlikesItAndTakesItOffTheGrid() throws Exception {
        Grid grid = grid(true, 1, "l0", "l1", "l2");
        View cell = grid.cells.get(1);

        assertTrue("the tap is taken, so the video doesn't open", tap(grid.fragment, new FakeTikTok.Video("l1"), cell));
        looper.idle();

        assertEquals(List.of("likeFeed {aweme_id=l1, type=0}"), account.requests);
        assertEquals(List.of("l0", "l2"), account.likes);
        assertEquals(List.of("l0", "l2"), ids(grid.adapter.getData()));
        assertEquals(1, grid.adapter.refreshes);
        assertEquals(1f, cell.getAlpha(), 0f);
        assertEquals("Removed from Liked videos", ShadowToast.getTextOfLatestToast());
    }

    @Test public void bothFavoritesTabsTakeTheVideoOutOfFavorites() throws Exception {
        for (int type : new int[]{4, 25}) {
            account.requests.clear();
            Grid grid = grid(true, type, "f" + type, "x");
            account.favorites.add("f" + type);

            assertTrue(tap(grid.fragment, new FakeTikTok.Video("f" + type), grid.cells.get(0)));
            looper.idle();

            assertEquals("tab " + type, 1, account.requests.size());
            assertTrue(account.requests.get(0), account.requests.get(0).startsWith("collect {aweme_id=f" + type));
            assertFalse(account.favorites.contains("f" + type));
            assertEquals(List.of("x"), ids(grid.adapter.getData()));
            assertEquals("Removed from Favorites", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void otherGridsAndOtherTabsOpenVideosAsUsual() throws Exception {
        assertFalse("someone else's Liked videos", tap(grid(false, 1, "l0").fragment, new FakeTikTok.Video("l0"), null));
        assertFalse("your own videos", tap(grid(true, 0, "l0").fragment, new FakeTikTok.Video("l0"), null));
        assertFalse("a fragment without arguments", tap(new Object(), new FakeTikTok.Video("l0"), null));

        SignedInUser.idForTests = null;
        SignedInUser.secIdForTests = null;
        assertFalse("signed out", tap(grid(true, 1, "l0").fragment, new FakeTikTok.Video("l0"), null));
        assertEquals(List.of(), account.requests);
    }

    @Test public void withTheSwitchOffOrHushfeedPausedATapOpensTheVideo() throws Exception {
        Grid grid = grid(true, 1, "l0");
        tapToRemove().save(false);
        assertFalse(tap(grid.fragment, new FakeTikTok.Video("l0"), grid.cells.get(0)));

        tapToRemove().save(true);
        pause(true);
        assertFalse(tap(grid.fragment, new FakeTikTok.Video("l0"), grid.cells.get(0)));
        assertEquals(List.of(), account.requests);
        assertEquals(List.of("l0"), ids(grid.adapter.getData()));
    }

    @Test public void aRefusalKeepsTheVideoAndSaysSo() throws Exception {
        Grid grid = grid(true, 1, "l0", "l1");
        account.refuse = "l1";

        assertTrue(tap(grid.fragment, new FakeTikTok.Video("l1"), grid.cells.get(1)));
        looper.idle();

        assertEquals(List.of("l0", "l1"), ids(grid.adapter.getData()));
        assertEquals(0, grid.adapter.refreshes);
        assertEquals(1f, grid.cells.get(1).getAlpha(), 0f);
        assertEquals("Couldn't remove that video. Try again.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void theCellDimsWhileTheRequestIsOutAndASecondTapSendsNothing() throws Exception {
        set("executorForTests", (Executor) queued::add);
        Grid grid = grid(true, 1, "l0", "l1");
        View cell = grid.cells.get(0);

        assertTrue(tap(grid.fragment, new FakeTikTok.Video("l0"), cell));
        assertEquals(0.4f, cell.getAlpha(), 0.001f);
        assertTrue("a second tap is still taken", tap(grid.fragment, new FakeTikTok.Video("l0"), cell));
        assertEquals("only the first tap's request is waiting", 1, queued.size());

        queued.remove(0).run();
        looper.idle();
        assertEquals(List.of("likeFeed {aweme_id=l0, type=0}"), account.requests);
        assertEquals(1f, cell.getAlpha(), 0f);
        assertEquals(List.of("l1"), ids(grid.adapter.getData()));
    }

    @Test public void aGridThatCantChangeGetsACopyWithoutTheVideo() throws Exception {
        Grid grid = grid(true, 1, "l0", "l1");
        grid.adapter.mmItems = List.copyOf(grid.adapter.mmItems);

        assertTrue(tap(grid.fragment, new FakeTikTok.Video("l0"), grid.cells.get(0)));
        looper.idle();

        assertEquals(List.of("l1"), ids(grid.adapter.getData()));
        assertEquals(1, grid.adapter.refreshes);
    }

    @Test public void aSortedGridForgetsTheVideoInBothItsLists() throws Exception {
        Grid grid = grid(true, 1, "l0", "l1", "l2");
        List<Object> original = new ArrayList<>(grid.adapter.mmItems);
        sortMap("ORIGINALS").put(grid.adapter, original);
        sortMap("HANDED").put(grid.adapter, grid.adapter.mmItems);
        try {
            assertTrue(tap(grid.fragment, new FakeTikTok.Video("l2"), grid.cells.get(2)));
            looper.idle();

            assertEquals(List.of("l0", "l1"), ids(sortMap("ORIGINALS").get(grid.adapter)));
            assertEquals(List.of("l0", "l1"), ids(sortMap("HANDED").get(grid.adapter)));
            assertEquals(List.of("l0", "l1"), ids(grid.adapter.getData()));
        } finally {
            sortMap("ORIGINALS").remove(grid.adapter);
            sortMap("HANDED").remove(grid.adapter);
        }
    }

    // Reposts.

    @Test public void aTapInYourOwnRepostsTakesTheRepostBack() throws Exception {
        RepostTab tab = new RepostTab("r0", "r1", "r2");
        View cell = new View(activity);
        Object row = tab.rows.get(1);

        assertTrue(repostTap(tab, row, new FakeTikTok.Video("r1"), "7001", cell));
        looper.idle();

        assertEquals(List.of("deleteUpvote {item_id=r1, carrier_type=0}"), account.requests);
        assertEquals(List.of("r0", "r2"), account.reposts);
        assertEquals(List.of(1), tab.removedAt);
        assertEquals(1f, cell.getAlpha(), 0f);
        assertEquals("Removed from Reposts", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aRepostRowIsFoundByItsVideoWhenTikTokRebuiltTheRow() throws Exception {
        RepostTab tab = new RepostTab("r0", "r1", "r2");

        assertTrue(repostTap(tab, new Row("r2"), new FakeTikTok.Video("r2"), "7001", null));
        looper.idle();

        assertEquals(List.of(2), tab.removedAt);
    }

    @Test public void someoneElsesRepostsOpenAsUsual() throws Exception {
        RepostTab tab = new RepostTab("r0");
        assertFalse(repostTap(tab, tab.rows.get(0), new FakeTikTok.Video("r0"), "8002", null));
        assertFalse(repostTap(tab, tab.rows.get(0), new FakeTikTok.Video("r0"), null, null));
        assertEquals(List.of(), account.requests);
        assertEquals(List.of(), tab.removedAt);
    }

    // The switch.

    @Test public void theSwitchShowsUnderAccountCleanupOnlyWhenItsHooksWentIn() throws Exception {
        SettingsStatus.ghostModeEnabled = true;
        SettingsStatus.accountCleanupEnabled = true;
        assertNull(row(privacyPage(), "tap_to_remove"));

        SettingsStatus.class.getField("quickRemoveEnabled").setBoolean(null, true);
        SettingsMenuPreference privacy = privacyPage();
        Preference toggle = row(privacy, "tap_to_remove");
        assertNotNull(toggle);
        assertEquals("Tap a video to remove it", toggle.getTitle().toString());
        assertEquals("In your own Liked videos, Favorites and Reposts, a tap takes the video off that list "
                + "instead of opening it.", toggle.getSummary().toString());
        Preference favorites = row(privacy, "action_clear_favorites");
        assertNotNull(favorites);
        assertEquals("right under Clear Favorites", index(privacy, favorites) + 1, index(privacy, toggle));
    }

    @Test public void theSwitchIsOffByDefault() throws Exception {
        BooleanSetting setting = tapToRemove();
        assertEquals("tap_to_remove", setting.key);
        assertEquals(Boolean.FALSE, setting.defaultValue);
    }

    // Fakes and reflection.

    /** A profile grid fragment: TikTok's arguments say whose profile and which tab. */
    public static final class GridFragment {
        private final Bundle arguments;

        GridFragment(Bundle arguments) {
            this.arguments = arguments;
        }

        public Bundle getArguments() {
            return arguments;
        }
    }

    /** A grid list as GridPicker finds it: a RecyclerView whose adapter hands its posts out. */
    public static final class RecyclerView extends FrameLayout {
        private final Adapter adapter;

        RecyclerView(android.content.Context context, Adapter adapter) {
            super(context);
            this.adapter = adapter;
        }

        public Adapter getAdapter() {
            return adapter;
        }
    }

    public static final class Adapter {
        List<Object> mmItems = new ArrayList<>();
        int refreshes;

        public List<Object> getData() {
            return mmItems;
        }

        public void notifyDataSetChanged() {
            refreshes++;
        }
    }

    private static final class Grid {
        GridFragment fragment;
        Adapter adapter;
        final List<View> cells = new ArrayList<>();
    }

    private Grid grid(boolean mine, int type, String... ids) {
        Bundle arguments = new Bundle();
        arguments.putBoolean("is_my_profile", mine);
        arguments.putInt("type", type);
        Grid grid = new Grid();
        grid.fragment = new GridFragment(arguments);
        grid.adapter = new Adapter();
        RecyclerView list = new RecyclerView(activity, grid.adapter);
        for (String id : ids) {
            grid.adapter.mmItems.add(new FakeTikTok.Video(id));
            View cell = new FrameLayout(activity);
            list.addView(cell);
            grid.cells.add(cell);
        }
        return grid;
    }

    /** TikTok's video class as a Reposts row holds it, under a renamed field. */
    public static final class Aweme {
        public final String aid;

        Aweme(String aid) {
            this.aid = aid;
        }
    }

    public static final class Row {
        private final Aweme LL;

        Row(String aid) {
            LL = new Aweme(aid);
        }
    }

    /** The Reposts tab's view model: its rows, and TikTok's removal of one by position. */
    public static final class RepostTab {
        final List<Object> rows = new ArrayList<>();
        final List<Integer> removedAt = new ArrayList<>();

        RepostTab(String... ids) {
            for (String id : ids) rows.add(new Row(id));
        }

        public List<Object> listGetAll() {
            return rows;
        }

        public void listRemoveItemAt(int index) {
            removedAt.add(index);
            rows.remove(index);
        }
    }

    private static List<String> ids(List<?> list) {
        List<String> ids = new ArrayList<>();
        for (Object entry : list) ids.add(((FakeTikTok.Video) entry).aid);
        return ids;
    }

    private static Class<?> quickRemove() throws ClassNotFoundException {
        return Class.forName(QUICK_REMOVE);
    }

    private static void set(String name, Object value) throws Exception {
        Field field;
        try {
            field = quickRemove().getDeclaredField(name);
        } catch (ClassNotFoundException missing) {
            if (value == null) return;
            throw missing;
        }
        field.setAccessible(true);
        field.set(null, value);
    }

    private static BooleanSetting tapToRemove() throws Exception {
        return (BooleanSetting) Settings.class.getField("TAP_TO_REMOVE").get(null);
    }

    private static void pause(boolean paused) throws Exception {
        Method method = Setting.class.getDeclaredMethod("setPausedForProcess", boolean.class);
        method.setAccessible(true);
        method.invoke(null, paused);
    }

    private static boolean tap(Object fragment, Object video, View cell) throws Exception {
        return (Boolean) quickRemove().getMethod("tap", Object.class, Object.class, View.class)
                .invoke(null, fragment, video, cell);
    }

    private static boolean repostTap(Object viewModel, Object row, Object video, String owner, View view)
            throws Exception {
        return (Boolean) quickRemove().getMethod("repostTap", Object.class, Object.class, Object.class,
                String.class, View.class).invoke(null, viewModel, row, video, owner, view);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, List<?>> sortMap(String name) throws Exception {
        Field field = GridSort.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<Object, List<?>>) field.get(null);
    }

    private SettingsMenuPreference privacyPage() {
        FrameLayout content = new FrameLayout(activity);
        activity.setContentView(content);
        FrameLayout container = new FrameLayout(activity);
        container.setId(View.generateViewId());
        content.addView(container);
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "PRIVACY");
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(container.getId(), fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        looper.idle();
        SettingsMenuPreference group = (SettingsMenuPreference) fragment.findPreference("section_privacy");
        assertNotNull("the main list has no Privacy category", group);
        return group;
    }

    private static Preference row(SettingsMenuPreference group, String key) {
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            if (key.equals(group.getPreference(index).getKey())) return group.getPreference(index);
        }
        return null;
    }

    private static int index(SettingsMenuPreference group, Preference row) {
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            if (group.getPreference(index) == row) return index;
        }
        return -1;
    }
}

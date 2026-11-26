/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.*;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Hide profile tabs takes the picked tabs out of the pager's list by TikTok's names for them. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ProfileTabsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** What the patch writes for 48.0.2, cut to the tabs a profile usually shows. */
    private static final Map<Integer, String> NAMES = ProfileTabs.parseTabNames(
            "0=profile_tab_public_post,2=profile_tab_like,6=profile_tab_favorite,10=profile_tab_private,"
                    + "12=profile_tab_ecommerce_showcase,15=profile_tab_repost,16=profile_tab_sticker_store,"
                    + "21=profile_tab_cast");

    @After public void reset() {
        PausedProcess.set(false);
        Settings.PROFILE_TAB_PICKS.save("");
        Settings.PROFILE_TAB_ORDER.save("");
    }

    @Test public void theTabsFollowTheSavedOrderAndTheRestKeepTheirPlaces() {
        // Your own profile's strip on 47.9.3: Videos, Stickers, AI theater, Private, Reposts,
        // Favorites, Liked.
        List<Integer> types = Arrays.asList(0, 16, 21, 10, 15, 6, 2);
        List<String> order = ProfileTabs.order("like, Favorite, gone tab, like, videos");
        assertEquals(List.of("like", "favorite", "videos"), order);
        List<?> arranged = ProfileTabs.arrange(types, NAMES, order);
        assertEquals("Liked and Favorites go in front of Videos, the rest stay", List.of(2, 6, 0, 16, 21, 10, 15),
                arranged);
        assertNotSame("a new list, since the pager swaps entries in the one it gets", types, arranged);

        List<String> full = ProfileTabs.shownOrder("like, repost");
        assertEquals(List.of("like", "repost", "videos", "sticker", "cast", "private", "favorite", "shop",
                "music", "effect", "series"), full);
        assertEquals("the checklist's whole order", List.of(2, 15, 0, 16, 21, 10, 6),
                ProfileTabs.arrange(types, NAMES, full));
        assertEquals("a short saved order reads the way the checklist shows it", List.of(2, 15, 0, 16, 21, 10, 6),
                ProfileTabs.arrange(types, NAMES, ProfileTabs.order("like, repost")));
    }

    @Test public void onlyTheTabsYouMoveChangePlace() {
        // Liked moved to the top of the checklist and nothing else touched, so nothing else moves.
        List<String> order = ProfileTabs.order(
                "like, videos, sticker, cast, private, repost, favorite, shop, music, effect, series");
        assertEquals(Set.of("like"), ProfileTabs.moved(order));
        assertEquals(List.of(2, 0, 16, 21, 10, 15, 6),
                ProfileTabs.arrange(Arrays.asList(0, 16, 21, 10, 15, 6, 2), NAMES, order));
        assertEquals("a creator's Shop keeps TikTok's place, ahead of Reposts", List.of(2, 0, 12, 15),
                ProfileTabs.arrange(Arrays.asList(0, 12, 15, 2), NAMES, order));
        assertEquals("a tab with no row stays put", List.of(2, 0, 99, 16),
                ProfileTabs.arrange(Arrays.asList(0, 99, 16, 2), NAMES, order));
    }

    @Test public void anOrderTheStripAlreadyHasKeepsThePagersOwnList() {
        List<Integer> types = Arrays.asList(0, 16, 21, 10, 15, 6, 2);
        assertSame(types, ProfileTabs.arrange(types, NAMES, ProfileTabs.order("videos, sticker, cast, private")));
        assertSame("Music moved, but the strip has none", types,
                ProfileTabs.arrange(types, NAMES, ProfileTabs.order("music, videos")));
        assertSame("no order saved", types, ProfileTabs.arrange(types, NAMES, ProfileTabs.order("")));
        assertSame("no map, TikTok's tabs", types, ProfileTabs.arrange(types, null, ProfileTabs.order("like")));
        assertEquals(ProfileTabs.ROWS.size(), ProfileTabs.shownOrder(null).size());
        assertTrue(ProfileTabs.moved(ProfileTabs.shownOrder("")).isEmpty());
    }

    @Test public void tabsOfOneRowKeepTikToksOrderBetweenThem() {
        Map<Integer, String> names = Map.of(0, ProfileTabs.VIDEOS, 12, "profile_tab_ecommerce_showcase",
                13, "profile_tab_ecommerce_store", 15, "profile_tab_repost");
        assertEquals("Shop's two tabs move together, store still ahead of showcase", List.of(13, 12, 15, 0),
                ProfileTabs.arrange(Arrays.asList(0, 15, 13, 12), names, ProfileTabs.order("shop, repost")));
    }

    @Test public void hiddenTabsLeaveTheRestToBeOrdered() {
        List<Integer> types = Arrays.asList(0, 16, 21, 10, 15, 6, 2);
        List<?> kept = ProfileTabs.filter(types, NAMES, ProfileTabs.namesFor("sticker, cast, private"));
        assertEquals(List.of(2, 6, 15, 0),
                ProfileTabs.arrange(kept, NAMES, ProfileTabs.order("like, favorite, repost, videos")));
    }

    @Test public void thePickedTabsGoAndTheRestKeepTheirOrder() {
        // Your own profile's strip on 47.9.3: Videos, Stickers, AI theater, Private, Reposts,
        // Favorites, Liked.
        List<Integer> types = Arrays.asList(0, 16, 21, 10, 15, 6, 2);
        List<?> kept = ProfileTabs.filter(types, NAMES, ProfileTabs.namesFor("repost, Like, favorite"));
        assertEquals(List.of(0, 16, 21, 10), kept);
        assertNotSame("a new list, since the pager swaps entries in the one it gets", types, kept);
        assertEquals(List.of(0, 15, 6), ProfileTabs.filter(Arrays.asList(0, 12, 15, 6), NAMES,
                ProfileTabs.namesFor("shop")));
    }

    @Test public void videosAndTheAiTabGoWhenPicked() {
        // #399 swipes only between Favorites and Liked: Videos, Stickers, AI theater, Private and
        // Reposts all picked.
        List<Integer> types = Arrays.asList(0, 16, 21, 10, 15, 6, 2);
        assertEquals(List.of(6, 2), ProfileTabs.filter(types, NAMES,
                ProfileTabs.namesFor("videos, sticker, cast, private, repost")));
        assertEquals(Set.of(ProfileTabs.VIDEOS, "profile_tab_cast"), ProfileTabs.namesFor("Videos\ncast"));
    }

    @Test public void theStripIsNeverLeftEmpty() {
        List<Integer> types = Arrays.asList(15, 2);
        assertSame(types, ProfileTabs.filter(types, NAMES, ProfileTabs.namesFor("repost, like")));
        List<Integer> all = Arrays.asList(0, 10);
        assertSame("every tab picked, so TikTok's strip", all,
                ProfileTabs.filter(all, NAMES, ProfileTabs.namesFor("videos, private")));
    }

    @Test public void nothingPickedOnTheStripKeepsThePagersOwnList() {
        List<Integer> types = Arrays.asList(0, 10, 99);
        assertSame("a tab with no name and one not picked both stay", types,
                ProfileTabs.filter(types, NAMES, ProfileTabs.namesFor("repost")));
        assertSame("no map, TikTok's tabs", types, ProfileTabs.filter(types, null, ProfileTabs.namesFor("repost")));
    }

    @Test public void thePairsThePatchWroteReadBack() {
        assertEquals(Map.of(0, "profile_tab_public_post", 15, "profile_tab_repost"),
                ProfileTabs.parseTabNames(" 0=profile_tab_public_post, 15 = profile_tab_repost ,x=profile_tab_like,7=,=8,9"));
        assertTrue(ProfileTabs.parseTabNames(null).isEmpty());
        assertTrue("unpatched, so no names and nothing hidden", ProfileTabs.parseTabNames(ProfileTabs.tabNamesNative()).isEmpty());
    }

    @Test public void oneRowCoversEveryTabOfItsKind() {
        Set<String> names = ProfileTabs.namesFor(" Shop ,series\nsomething else");
        assertEquals(Set.of("profile_tab_ecommerce_showcase", "profile_tab_ecommerce_store",
                "profile_tab_short_drama", "profile_tab_drama_download",
                "profile_tab_pine_drama_watch_history"), names);
    }

    @Test public void pausedHidesNothing() {
        Settings.PROFILE_TAB_PICKS.save("repost");
        Settings.PROFILE_TAB_ORDER.save("like");
        assertEquals(Set.of("profile_tab_repost"), ProfileTabs.hiddenNames());
        assertEquals("profile_tab_order", Settings.PROFILE_TAB_ORDER.key);
        PausedProcess.set(true);
        assertTrue(ProfileTabs.hiddenNames().isEmpty());
        assertTrue("paused, TikTok's order", ProfileTabs.order(Settings.PROFILE_TAB_ORDER.get()).isEmpty());
        List<Integer> types = Arrays.asList(0, 15);
        assertSame(types, ProfileTabs.filter(types));
    }
}

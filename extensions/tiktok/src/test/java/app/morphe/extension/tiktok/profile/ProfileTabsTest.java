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
import java.util.HashMap;
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

    /** 47.7.3's map, cut to the tabs a profile usually shows. */
    private static final Map<Object, Object> NAMES = new HashMap<>();
    static {
        NAMES.put(0, "profile_tab_public_post");
        NAMES.put(14, "profile_tab_private");
        NAMES.put(18, "profile_tab_repost");
        NAMES.put(8, "profile_tab_favorite");
        NAMES.put(1, "profile_tab_like");
        NAMES.put(16, "profile_tab_ecommerce_showcase");
    }

    @After public void reset() {
        PausedProcess.set(false);
        Settings.PROFILE_TAB_PICKS.save("");
    }

    @Test public void thePickedTabsGoAndTheRestKeepTheirOrder() {
        List<Integer> types = Arrays.asList(0, 14, 18, 8, 1, 16);
        List<?> kept = ProfileTabs.filter(types, NAMES, ProfileTabs.namesFor("repost, Like, shop"));
        assertEquals(List.of(0, 14, 8), kept);
        assertNotSame("a new list, since the pager swaps entries in the one it gets", types, kept);
    }

    @Test public void videosStaysWhateverTheNamesSay() {
        List<Integer> types = Arrays.asList(0, 18);
        List<?> kept = ProfileTabs.filter(types, NAMES, Set.of(ProfileTabs.VIDEOS, "profile_tab_repost"));
        assertEquals(List.of(0), kept);
    }

    @Test public void theStripIsNeverLeftEmpty() {
        List<Integer> types = Arrays.asList(18, 1);
        assertSame(types, ProfileTabs.filter(types, NAMES, ProfileTabs.namesFor("repost, like")));
    }

    @Test public void nothingPickedOnTheStripKeepsThePagersOwnList() {
        List<Integer> types = Arrays.asList(0, 14, 99);
        assertSame("a tab with no name and one not picked both stay", types,
                ProfileTabs.filter(types, NAMES, ProfileTabs.namesFor("repost")));
        assertSame("no map, TikTok's tabs", types, ProfileTabs.filter(types, null, ProfileTabs.namesFor("repost")));
    }

    @Test public void oneRowCoversEveryTabOfItsKind() {
        Set<String> names = ProfileTabs.namesFor(" Shop ,series\nsomething else");
        assertEquals(Set.of("profile_tab_ecommerce_showcase", "profile_tab_ecommerce_store",
                "profile_tab_short_drama", "profile_tab_drama_download",
                "profile_tab_pine_drama_watch_history"), names);
    }

    @Test public void pausedHidesNothing() {
        Settings.PROFILE_TAB_PICKS.save("repost");
        assertEquals(Set.of("profile_tab_repost"), ProfileTabs.hiddenNames());
        PausedProcess.set(true);
        assertTrue(ProfileTabs.hiddenNames().isEmpty());
        List<Integer> types = Arrays.asList(0, 18);
        assertSame(types, ProfileTabs.filter(types));
    }
}

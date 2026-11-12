/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Hide search result tabs: the picked tabs leave the served strip, Top never does, and the tabs
 * served on this phone are kept by key with the name TikTok gives them, for the checklist.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SearchTabsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A served tab info with the key and the name TikTok's model has. */
    public static final class Tab {
        private final String tabKey;
        private final String tabName;
        public Tab(String tabKey, String tabName) { this.tabKey = tabKey; this.tabName = tabName; }
        public String getTabKey() { return tabKey; }
        public String getTabName() { return tabName; }
    }

    @Before public void setUp() {
        HookStatus.clear();
        Settings.HIDE_TAKO_AI.save(false);
    }

    @After public void tearDown() {
        Settings.SEARCH_TAB_PICKS.save("");
        Settings.SEARCH_TAB_CATALOG.save("");
        Settings.HIDE_TAKO_AI.resetToDefault();
        HookStatus.clear();
    }

    /** The strip as 47.0.3 serves it in English. */
    private static List served() {
        return new ArrayList(Arrays.asList(
                new Tab("general", "Top"), new Tab("user", "Users"), new Tab("video", "Videos"),
                new Tab("shop", "Shop"), new Tab("live", "LIVE"), new Tab("place", "Places")));
    }

    private static List<String> keys(List tabs) {
        List<String> keys = new ArrayList<>();
        for (Object tab : tabs) keys.add(((Tab) tab).getTabKey());
        return keys;
    }

    private static String catalog() {
        StringBuilder result = new StringBuilder();
        for (SearchTabs.Entry entry : SearchTabs.entries()) {
            if (result.length() > 0) result.append(", ");
            result.append(entry.key).append('=').append(entry.label);
        }
        return result.toString();
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    @Test public void pickedTabsLeaveTheStripAndTheRestKeepTheirOrder() {
        Settings.SEARCH_TAB_PICKS.save("shop, LIVE\nplace");
        List strip = served();
        List shown = TakoAiFilter.filterSearchTabs(strip);
        assertNotSame("a new list, the served one untouched", strip, shown);
        assertEquals(Arrays.asList("general", "user", "video"), keys(shown));
        assertEquals(6, strip.size());
        assertTrue(report(), report().contains("search tabs: 1 found, 0 missing"));
    }

    @Test public void topStaysEvenWhenPicked() {
        Settings.SEARCH_TAB_PICKS.save("general, shop");
        assertEquals(Arrays.asList("general", "user", "video", "live", "place"),
                keys(TakoAiFilter.filterSearchTabs(served())));
    }

    @Test public void withNoPicksTheStripGoesThroughAsServedAndNothingIsMarked() {
        List strip = served();
        assertSame(strip, TakoAiFilter.filterSearchTabs(strip));
        assertFalse(report(), report().contains("search tabs:"));
    }

    @Test public void aPickTheStripDoesNotServeIsNamedInTheExport() {
        Settings.SEARCH_TAB_PICKS.save("music");
        List strip = served();
        assertSame(strip, TakoAiFilter.filterSearchTabs(strip));
        assertTrue(report(), report().contains("search tabs: 1 found, 0 missing"));
    }

    @Test public void pausedNothingIsHidden() {
        Settings.SEARCH_TAB_PICKS.save("shop, live");
        PausedProcess.set(true);
        try {
            List strip = served();
            assertSame(strip, TakoAiFilter.filterSearchTabs(strip));
        } finally {
            PausedProcess.set(false);
        }
    }

    @Test public void theTakoTabFollowsItsOwnSwitchNotThePicks() {
        Settings.SEARCH_TAB_PICKS.save("tako, shop");
        List strip = served();
        strip.add(0, new Tab("tako", "Ask Tako"));
        assertEquals(Arrays.asList("tako", "general", "user", "video", "live", "place"),
                keys(TakoAiFilter.filterSearchTabs(strip)));
        Settings.HIDE_TAKO_AI.save(true);
        assertEquals(Arrays.asList("general", "user", "video", "live", "place"),
                keys(TakoAiFilter.filterSearchTabs(strip)));
    }

    @Test public void theCatalogKeepsTheServedTabsByKeyWithoutTopOrTako() {
        List strip = served();
        strip.add(new Tab("tako", "Ask Tako"));
        strip.add(new Tab("hashtag", null));
        TakoAiFilter.filterSearchTabs(strip);
        assertEquals("user=Users, video=Videos, shop=Shop, live=LIVE, place=Places, hashtag=hashtag", catalog());
    }

    @Test public void aNameInAnotherLanguageReplacesTheOldOneAndAMissingNameKeepsIt() {
        TakoAiFilter.filterSearchTabs(served());
        String before = Settings.SEARCH_TAB_CATALOG.savedValue();
        TakoAiFilter.filterSearchTabs(served());
        assertEquals("the same strip writes nothing new", before, Settings.SEARCH_TAB_CATALOG.savedValue());

        TakoAiFilter.filterSearchTabs(new ArrayList(Arrays.asList(
                new Tab("shop", "Tienda"), new Tab("place", ""), new Tab("live", "LIVE"))));
        assertEquals("user=Users, video=Videos, shop=Tienda, live=LIVE, place=Places", catalog());
    }

    @Test public void pausedTheCatalogStillReadsWhatWasSeen() {
        TakoAiFilter.filterSearchTabs(served());
        PausedProcess.set(true);
        try {
            assertEquals("user=Users, video=Videos, shop=Shop, live=LIVE, place=Places", catalog());
        } finally {
            PausedProcess.set(false);
        }
    }
}

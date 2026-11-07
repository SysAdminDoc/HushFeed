/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #271: the shop's "Find <product>" bar along the bottom of a video goes with Hide TikTok Shop,
 * and the export says how often TikTok asked, how often it wanted the bar and how often it was hidden.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ShopFindBarTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private boolean shop;

    @Before public void start() {
        shop = Settings.HIDE_SHOP.get();
        FeedFilterCounters.clear();
    }

    @After public void restore() {
        Settings.HIDE_SHOP.save(shop);
        FeedFilterCounters.clear();
    }

    private static String exportLine() {
        return exportLine(CardFilters.SHOP_FIND_BAR_SOURCE);
    }

    private static String exportLine(String source) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(source + ":")) return line;
        }
        return null;
    }

    /** The "Find <product>" button above the bottom bar on videos with no product cart. */
    @Test public void hideTikTokShopTakesTheFindButtonAwayAndTheExportCountsIt() {
        Settings.HIDE_SHOP.save(true);

        assertFalse("a video with the button loses it", CardFilters.keepShopFindButton(1));
        assertFalse("a video without the button stays without it", CardFilters.keepShopFindButton(0));
        String line = exportLine(CardFilters.SHOP_FIND_BUTTON_SOURCE);
        assertTrue(line, line != null && line.contains("2 lists") && line.contains("1 items")
                && line.contains("1 removed") && line.contains("shopFindButton"));

        Settings.HIDE_SHOP.save(false);
        assertTrue("with the switch off TikTok decides", CardFilters.keepShopFindButton(1));
        assertFalse(CardFilters.keepShopFindButton(0));
    }

    @Test public void hideTikTokShopTakesTheFindBarAwayAndTheExportCountsIt() {
        Settings.HIDE_SHOP.save(true);

        assertFalse("a video with the bar loses it", CardFilters.keepShopFindBar(true));
        assertFalse("a video without the bar stays without it", CardFilters.keepShopFindBar(false));

        String line = exportLine();
        assertTrue(line, line != null && line.contains("2 lists") && line.contains("1 items")
                && line.contains("1 removed") && line.contains("shopFindBar"));
    }

    @Test public void withTheSwitchOffTikTokDecides() {
        Settings.HIDE_SHOP.save(false);

        assertTrue(CardFilters.keepShopFindBar(true));
        assertFalse(CardFilters.keepShopFindBar(false));
        String line = exportLine();
        assertTrue(line, line != null && line.contains("2 lists") && !line.contains("shopFindBar"));
    }
}

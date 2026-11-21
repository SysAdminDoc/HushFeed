/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
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
 * #478: the full-screen "people you may know" card between videos goes with Hide inserted cards,
 * both where TikTok asks for it and in the list if one arrives anyway.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RecUserBigCardTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final CardFilters.InsertedCardFilter filter = new CardFilters.InsertedCardFilter();
    private boolean before;

    @Before public void setUp() {
        before = Settings.HIDE_INSERTED_CARDS.get();
        FeedFilterCounters.clear();
    }

    @After public void tearDown() {
        Settings.HIDE_INSERTED_CARDS.save(before);
        FeedFilterCounters.clear();
    }

    private static Lemon8PromoCardTest.Insert card(int awemeType, Integer cardType) {
        Lemon8PromoCardTest.Insert item = new Lemon8PromoCardTest.Insert();
        item.awemeType = awemeType;
        item.cardInsertInfo = cardType == null ? null : new Lemon8PromoCardTest.CardInsert(cardType, 0);
        return item;
    }

    private static String exportLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(CardFilters.REC_USER_BIG_CARD_SOURCE + ":")) return line;
        }
        return null;
    }

    @Test public void theCardIsNeverAskedForAndTheExportCountsEachAsk() {
        Settings.HIDE_INSERTED_CARDS.save(true);
        assertTrue(CardFilters.shouldSkipRecUserBigCardRequest());
        assertTrue(CardFilters.shouldSkipRecUserBigCardRequest());
        String line = exportLine();
        assertTrue(line, line != null && line.contains("2 lists") && line.contains("2 removed")
                && line.contains("recUserBigCardRequest"));
    }

    @Test public void withTheSwitchOffTikTokAsksAsUsual() {
        Settings.HIDE_INSERTED_CARDS.save(false);
        assertFalse(CardFilters.shouldSkipRecUserBigCardRequest());
        assertFalse(filter.getEnabled());
        String line = exportLine();
        assertTrue("the ask still shows in the export", line != null && line.contains("1 lists"));
        assertFalse(line, line.contains("recUserBigCardRequest"));
    }

    @Test public void aCardThatArrivesAnywayLeavesTheListWhateverItsAwemeType() {
        Settings.HIDE_INSERTED_CARDS.save(true);
        assertEquals(49, CardFilters.REC_USER_BIG_CARD_TYPE);
        assertTrue(filter.getEnabled());
        assertTrue("type 49 on an ordinary item", filter.getFiltered(card(0, 49)));
        assertTrue("type 49 as an insert", filter.getFiltered(card(105, 49)));
        assertTrue("any other insert still goes", filter.getFiltered(card(105, 92)));
    }

    @Test public void videosAndOtherCardTypesStay() {
        Settings.HIDE_INSERTED_CARDS.save(true);
        assertFalse("a video with no card info", filter.getFiltered(card(0, null)));
        assertFalse("the Lemon8 card is Remove feed ads' to take", filter.getFiltered(card(0, 9)));
        assertFalse("another card type on a video", filter.getFiltered(card(0, 48)));
    }
}

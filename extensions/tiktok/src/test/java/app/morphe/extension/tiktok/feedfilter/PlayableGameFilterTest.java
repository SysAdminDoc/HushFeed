/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Hide playable games (#486). For You serves TikTok's mini games as posts of aweme type 260, the
 * type TikTok's own playable check compares against. Each case goes in through
 * {@link FeedItemsFilter#filter(FeedItemList)}, the way a For You response reaches the filter,
 * so a filter left off the list fails here too.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PlayableGameFilterTest {
    private static final String FEED = "FeedItemList:response";
    private static final int PLAYABLE = 260;

    private boolean before;
    private boolean debug;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        before = Settings.HIDE_PLAYABLE_GAMES.get();
        debug = BaseSettings.DEBUG.get();
        BaseSettings.DEBUG.save(false);
        FeedItemsFilter.resetDiagnosticsForTests();
        FeedFilterFeedback.resetForTests();
        FeedFilterCounters.clear();
    }

    @After public void tearDown() {
        Settings.HIDE_PLAYABLE_GAMES.save(before);
        BaseSettings.DEBUG.save(debug);
        FeedItemsFilter.resetDiagnosticsForTests();
        FeedFilterFeedback.resetForTests();
        FeedFilterCounters.clear();
    }

    @Test public void theSwitchStartsOffAndNeedsARestart() {
        assertFalse(Settings.HIDE_PLAYABLE_GAMES.defaultValue);
        assertTrue(Settings.HIDE_PLAYABLE_GAMES.rebootApp);
        assertEquals("hide_playable_games", Settings.HIDE_PLAYABLE_GAMES.key);
    }

    @Test public void aGameStaysUntilTheSwitchIsOn() {
        Settings.HIDE_PLAYABLE_GAMES.save(false);
        assertEquals(Arrays.asList("game", "video"), survivors(page(item("game", PLAYABLE), item("video", 0))));
    }

    @Test public void withTheSwitchOnOnlyTypeTwoHundredSixtyLeaves() {
        Settings.HIDE_PLAYABLE_GAMES.save(true);
        assertEquals(Arrays.asList("video", "below", "above"), survivors(page(
                item("game", PLAYABLE), item("video", 0), item("below", PLAYABLE - 1),
                item("above", PLAYABLE + 1), item("second game", PLAYABLE))));
    }

    @Test public void theExportNamesTheFilterThatTookIt() {
        Settings.HIDE_PLAYABLE_GAMES.save(true);
        survivors(page(item("game", PLAYABLE), item("video", 0)));
        String line = lineFor(FEED);
        assertNotNull(FeedFilterCounters.report().toString(), line);
        assertTrue(line, line.contains("1 removed") && line.contains("Last reason: PlayableGameFilter"));
    }

    @Test public void aRunOfWipedListsNamesItsSwitchAndItsRow() {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        reasons.put("PlayableGameFilter", 10);
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, 1_700_000_000_000L));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, 1_700_000_000_001L));
        FeedFilterFeedback.Notice notice = FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, 1_700_000_000_002L);
        assertNotNull(notice);
        assertTrue(notice.message, notice.message.startsWith("Hide playable games hid everything TikTok sent"));
        assertEquals("hide_playable_games", notice.settingKey());
    }

    @Test public void theGenericNoticeCallsThemPlayableGames() {
        // Two kinds, neither more than half, so no switch is named and the summary lists both.
        Map<String, Integer> reasons = new LinkedHashMap<>();
        reasons.put("PlayableGameFilter", 3);
        reasons.put("AdsFilter", 3);
        FeedFilterFeedback.Notice notice = null;
        for (int i = 0; i < 3 && notice == null; i++) {
            notice = FeedFilterFeedback.recordBatch(FEED, 6, 0, reasons, 1_700_000_000_000L + i);
        }
        assertNotNull(notice);
        assertTrue(notice.message, notice.message.contains("Playable games (9)"));
        assertNull(notice.settingKey());
    }

    private static Aweme item(String aid, int awemeType) {
        CreatorExceptionsTest.Item item = new CreatorExceptionsTest.Item(aid);
        item.awemeType = awemeType;
        return item;
    }

    private static FeedItemList page(Aweme... items) {
        FeedItemList list = new FeedItemList();
        list.items = new ArrayList<>(Arrays.asList(items));
        return list;
    }

    /** The aids left on a page after the filter, in order, with no filter having thrown. */
    private static List<String> survivors(FeedItemList list) {
        FeedItemsFilter.filter(list);
        try {
            java.lang.reflect.Field errors = FeedItemsFilter.class.getDeclaredField("filterExceptionLogCount");
            errors.setAccessible(true);
            assertEquals("a filter swallowed an unstubbed getter or another runtime failure", 0,
                    ((AtomicInteger) errors.get(null)).get());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
        List<String> aids = new ArrayList<>();
        for (Object item : list.items) aids.add(((Aweme) item).getAid());
        return aids;
    }

    private static String lineFor(String source) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(source + ":")) return line;
        }
        return null;
    }
}

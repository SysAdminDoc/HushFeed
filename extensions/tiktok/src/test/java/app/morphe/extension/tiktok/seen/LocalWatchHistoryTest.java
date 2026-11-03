/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.seen;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;

/** The store behind the list: the route from the player sits in the block author tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LocalWatchHistoryTest {
    private static final String ME = "me";

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SignedInUser.idForTests = ME;
        Settings.LOCAL_WATCH_HISTORY.save(true);
        LocalWatchHistory.resetForTests();
    }

    @After public void tearDown() throws Exception {
        LocalWatchHistory.resetForTests();
        Settings.LOCAL_WATCH_HISTORY.save(false);
        SignedInUser.idForTests = null;
    }

    private static List<String> aids(String account) throws Exception {
        LocalWatchHistory.drainForTests();
        List<String> aids = new ArrayList<>();
        for (LocalWatchHistory.Entry row : LocalWatchHistory.read(account)) aids.add(row.aid);
        return aids;
    }

    @Test public void newestComesFirstAndAWatchAgainMovesBackToTheTop() throws Exception {
        LocalWatchHistory.record(ME, "1", "Ann", "first", 100);
        LocalWatchHistory.record(ME, "2", "Bob", "second", 200);
        assertEquals(Arrays.asList("2", "1"), aids(ME));

        LocalWatchHistory.record(ME, "1", "Ann", "first", 300);

        assertEquals(Arrays.asList("1", "2"), aids(ME));
    }

    @Test public void eachAccountHasItsOwnList() throws Exception {
        LocalWatchHistory.record(ME, "1", "Ann", "", 100);
        LocalWatchHistory.record("other", "2", "Bob", "", 100);

        assertEquals(Arrays.asList("1"), aids(ME));
        assertEquals(Arrays.asList("2"), aids("other"));
    }

    @Test public void theOldestGoPastTheCapAndOnlyForThatAccount() throws Exception {
        LocalWatchHistory.cap = 2;
        LocalWatchHistory.record("other", "9", "Zed", "", 1);
        LocalWatchHistory.record(ME, "1", "Ann", "", 100);
        LocalWatchHistory.record(ME, "2", "Bob", "", 200);
        LocalWatchHistory.record(ME, "3", "Cam", "", 300);

        assertEquals(Arrays.asList("3", "2"), aids(ME));
        assertEquals(Arrays.asList("9"), aids("other"));
    }

    @Test public void aMissingCreatorOrCaptionStillListsTheVideo() throws Exception {
        LocalWatchHistory.record(ME, "1", null, null, 100);
        LocalWatchHistory.drainForTests();

        LocalWatchHistory.Entry row = LocalWatchHistory.read(ME).get(0);
        assertEquals("", row.creator);
        assertEquals("", row.caption);
    }

    @Test public void aLongCaptionIsCutToOneLineWithoutSplittingAnEmoji() {
        assertEquals("two words", LocalWatchHistory.shorten(" two \n  words "));

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < LocalWatchHistory.CAPTION_CHARS - 1; i++) text.append('a');
        String cut = LocalWatchHistory.shorten(text + "😀 and on");

        assertEquals(LocalWatchHistory.CAPTION_CHARS - 1, cut.length());
        assertTrue(cut.endsWith("a"));
    }

    @Test public void searchLooksThroughCreatorAndCaptionInAnyCase() {
        List<LocalWatchHistory.Entry> rows = Arrays.asList(
                new LocalWatchHistory.Entry("1", "Milesabovetech", "Everything new", 300),
                new LocalWatchHistory.Entry("2", "Nat Geo", "A great WHITE shark", 200),
                new LocalWatchHistory.Entry("3", "", "", 100));

        assertEquals(1, LocalWatchHistory.filter(rows, "miles").size());
        assertEquals("2", LocalWatchHistory.filter(rows, "white").get(0).aid);
        assertEquals(0, LocalWatchHistory.filter(rows, "nothing like it").size());
        assertEquals(3, LocalWatchHistory.filter(rows, "  ").size());
    }

    @Test public void clearTakesOnlyThisAccountsRowsAndUndoBringsThemBack() throws Exception {
        LocalWatchHistory.record(ME, "1", "Ann", "", 100);
        LocalWatchHistory.record("other", "2", "Bob", "", 100);
        List<List<LocalWatchHistory.Entry>> removed = new ArrayList<>();

        LocalWatchHistory.clear(removed::add);
        LocalWatchHistory.drainForTests();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        assertEquals(new ArrayList<String>(), aids(ME));
        assertEquals(Arrays.asList("2"), aids("other"));
        assertEquals(1, removed.get(0).size());

        LocalWatchHistory.record(ME, "1", "Ann", "newer", 500);
        LocalWatchHistory.restore(removed.get(0));
        LocalWatchHistory.drainForTests();

        LocalWatchHistory.Entry back = LocalWatchHistory.read(ME).get(0);
        assertEquals("newer", back.caption);
        assertEquals(500, back.watchedMs);
    }
}

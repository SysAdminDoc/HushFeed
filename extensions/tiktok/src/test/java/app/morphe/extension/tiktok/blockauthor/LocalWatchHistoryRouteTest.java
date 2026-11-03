/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.seen.LocalWatchHistory;
import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.settings.Settings;

/** From the player's progress to a row, which is the path a video really takes onto the list. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LocalWatchHistoryRouteTest {
    public static final class Author {
        public String getUid() { return "42"; }
        public String getSecUid() { return "sec42"; }
        public String getUniqueId() { return "ann_handle"; }
        public String getNickname() { return "Ann"; }
    }

    public static final class Aweme {
        private final String aid;
        Aweme(String aid) { this.aid = aid; }
        public String getAid() { return aid; }
        public String getDesc() { return "A caption for " + aid; }
        public Author getAuthor() { return new Author(); }
    }

    public static final class Params {
        private final Aweme aweme;
        Params(String aid) { aweme = new Aweme(aid); }
        public Aweme getAweme() { return aweme; }
    }

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SignedInUser.idForTests = "me";
        Settings.HIDE_SEEN_VIDEOS.save(false);
        Settings.SEEN_VIDEO_MARK_PERCENT.save(0);
        Settings.LOCAL_WATCH_HISTORY.save(true);
        CurrentVideoAuthor.resetForTests();
        clearList();
    }

    @After public void tearDown() throws Exception {
        clearList();
        Settings.LOCAL_WATCH_HISTORY.save(false);
        CurrentVideoAuthor.resetForTests();
        SignedInUser.idForTests = null;
    }

    private static void clearList() throws Exception {
        AtomicReference<List<LocalWatchHistory.Entry>> done = new AtomicReference<>();
        LocalWatchHistory.clear(done::set);
        settle(done);
    }

    private static List<LocalWatchHistory.Entry> list() throws Exception {
        AtomicReference<List<LocalWatchHistory.Entry>> done = new AtomicReference<>();
        LocalWatchHistory.load(done::set);
        settle(done);
        return done.get();
    }

    private static void settle(AtomicReference<List<LocalWatchHistory.Entry>> done) throws Exception {
        long giveUpAt = System.currentTimeMillis() + 15_000;
        while (done.get() == null && System.currentTimeMillis() < giveUpAt) {
            ShadowLooper.idleMainLooper();
            Thread.sleep(5);
        }
        assertNotNull("the list never answered", done.get());
    }

    private static void playing(String aid) {
        CurrentVideoAuthor.update(new Params(aid));
        CurrentVideoAuthor.onPlaying(aid);
    }

    @Test public void aVideoPlayedPastTheMarkIsListedWithItsCreatorAndCaptionWithTheFilterOff() throws Exception {
        playing("1001");

        SeenVideoHistory.onPlayProgressChange("1001", 300, 30_000);
        assertTrue("not at the mark yet", list().isEmpty());

        SeenVideoHistory.onPlayProgressChange("1001", 4_000, 30_000);
        List<LocalWatchHistory.Entry> rows = list();

        assertEquals(1, rows.size());
        assertEquals("1001", rows.get(0).aid);
        assertEquals("Ann", rows.get(0).creator);
        assertEquals("A caption for 1001", rows.get(0).caption);
    }

    @Test public void withTheSwitchOffNothingIsListed() throws Exception {
        Settings.LOCAL_WATCH_HISTORY.save(false);
        playing("1002");

        SeenVideoHistory.onPlayProgressChange("1002", 4_000, 30_000);

        assertTrue(list().isEmpty());
    }

    @Test public void aVideoThePlayerNamesBeforeTheFeedHasItWaitsForItsDetails() throws Exception {
        playing("1003");

        SeenVideoHistory.onPlayProgressChange("1004", 4_000, 30_000);
        assertTrue("the current video is another one", list().isEmpty());

        playing("1004");
        SeenVideoHistory.onPlayProgressChange("1004", 4_500, 30_000);

        List<String> aids = new ArrayList<>();
        for (LocalWatchHistory.Entry row : list()) aids.add(row.aid);
        assertEquals(List.of("1004"), aids);
    }
}

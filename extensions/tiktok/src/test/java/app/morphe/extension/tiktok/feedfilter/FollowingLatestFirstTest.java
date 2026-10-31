package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.feedfilter.CreatorExceptionsTest.Item;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.follow.presenter.FollowFeed;
import com.ss.android.ugc.aweme.follow.presenter.FollowFeedList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/**
 * Latest first on Following (#226), through the same getItems hook TikTok's Following list runs:
 * everything below the video on screen plays newest first, earlier reads' posts included, the
 * video on screen and everything above it never move, and ads, LIVEs and cards that aren't posts
 * hold their slots.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FollowingLatestFirstTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long NOW_SECONDS = System.currentTimeMillis() / 1000;
    private static final long HOUR = 3600;

    /** Stands in for VideoItemParams, the way the feed binds a video. */
    public static final class Params {
        public final Object aweme;

        Params(Object aweme) {
            this.aweme = aweme;
        }
    }

    @Before
    public void setUp() {
        FollowingLatestFirst.resetForTests();
    }

    @After
    public void tearDown() {
        Settings.FOLLOWING_LATEST_FIRST.resetToDefault();
        Settings.REMOVE_ADS.resetToDefault();
        FollowingLatestFirst.resetForTests();
        ReflectionHelpers.callStaticMethod(CurrentVideoAuthor.class, "resetForTests");
    }

    @Test
    public void withNothingOnScreenYetEveryReadSortsTheWholeList() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        FollowFeedList list = emptyList();

        assertEquals(List.of("b", "c", "a"),
                read(list, post("a", 3 * HOUR), post("b", HOUR), post("c", 2 * HOUR)));
        // A read with nothing new leaves it alone.
        assertEquals(List.of("b", "c", "a"), read(list));
        // The next page sorts in with the first: d and f are newer than everything before them.
        assertEquals(List.of("d", "f", "b", "c", "a", "e"),
                read(list, post("d", 600), post("e", 5 * HOUR), post("f", 1800)));
    }

    @Test
    public void aNewerPostFromALaterReadMovesUpToJustBelowTheVideoOnScreen() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        FollowFeedList list = emptyList();

        assertEquals(List.of("old", "older"),
                read(list, post("old", 2 * HOUR), post("older", 3 * HOUR)));
        FollowingLatestFirst.onCurrentVideo("old");
        // Slot 0 is on screen and stays. The newer post goes ahead of the earlier read's older.
        assertEquals(List.of("old", "newer", "older"), read(list, post("newer", 600)));
    }

    @Test
    public void theVideoOnScreenNeverMovesEvenWhenItIsTheOldest() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        FollowFeedList list = emptyList();

        assertEquals(List.of("a", "b", "c"),
                read(list, post("a", HOUR), post("b", 2 * HOUR), post("c", 3 * HOUR)));
        FollowingLatestFirst.onCurrentVideo("c");
        // Everything above the reader stays as it was read too.
        assertEquals(List.of("a", "b", "c", "e", "d"),
                read(list, post("d", 4 * HOUR), post("e", 600)));
    }

    @Test
    public void aSecondListForTheSameFeedKeepsTheHeadTheReaderPassed() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        Item a = post("a", 2 * HOUR);
        Item b = post("b", 3 * HOUR);

        assertEquals(List.of("a", "b"), read(emptyList(), a, b));
        FollowingLatestFirst.onCurrentVideo("a");
        // TikTok builds a new FollowFeedList for the same feed: a stays on top though c is newer.
        FollowFeedList second = emptyList();
        Item c = post("c", HOUR);
        assertEquals(List.of("a", "c", "b"), read(second, a, b, c));
        // The reader moves on to c, and the next page sorts in below it.
        FollowingLatestFirst.onCurrentVideo("c");
        assertEquals(List.of("a", "c", "d", "b"), read(second, post("d", 1800)));
    }

    @Test
    public void aVideoTheListDoesntHoldFreezesNothing() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        // A For You video is on screen, then the Following list loads.
        FollowingLatestFirst.onCurrentVideo("for_you");

        assertEquals(List.of("b", "a"), read(emptyList(), post("a", 3 * HOUR), post("b", HOUR)));
    }

    @Test
    public void theVideoThePlayerReportsIsWhereTheSortStops() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        FollowFeedList list = emptyList();
        Item old = post("old", 2 * HOUR);
        Item newer = post("newer", 600);

        assertEquals(List.of("old", "older"), read(list, old, post("older", 3 * HOUR)));
        // The first bind of a fresh feed is what's on screen.
        bind(old);
        assertEquals(List.of("old", "newer", "older"), read(list, newer));
        // The feed binds the next video before the reader gets there. That isn't reaching it,
        // so a newer post can still go ahead of it.
        bind(newer);
        Item newest = post("newest", 300);
        assertEquals(List.of("old", "newest", "newer", "older"), read(list, newest));
        // The reader swipes on and the player reports the video now in slot 1.
        bind(newest);
        playing("newest");
        assertEquals(List.of("old", "newest", "latest", "newer", "older"),
                read(list, post("latest", 60)));
    }

    private static void bind(Item item) {
        ReflectionHelpers.callStaticMethod(CurrentVideoAuthor.class, "update",
                ReflectionHelpers.ClassParameter.from(Object.class, new Params(item)));
    }

    private static void playing(String aid) {
        ReflectionHelpers.callStaticMethod(CurrentVideoAuthor.class, "onPlaying",
                ReflectionHelpers.ClassParameter.from(String.class, aid));
    }

    @Test
    public void adsLivesAndCardsThatArentPostsKeepTheirSlots() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        Settings.REMOVE_ADS.save(false);
        Item ad = post("ad", 60);
        ad.ad = true;
        Item live = post("live", 60);
        live.liveId = 7;

        assertEquals(List.of("y", "ad", "x", "live", "z"),
                read(emptyList(), post("x", HOUR), ad, post("y", 1800), live, post("z", 2 * HOUR)));

        // A divider splits the batch: s is the newest but stays below it.
        FollowFeedList list = emptyList();
        add(list, post("p", 3 * HOUR), post("q", HOUR));
        list.mItems.add(new FollowFeed());
        add(list, post("r", 4 * HOUR), post("s", 600));
        assertEquals(List.of("q", "p", "-", "s", "r"), read(list));
    }

    @Test
    public void postsWithNoDateOrNoIdAndSoftAdsKeepTheirSlots() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        Settings.REMOVE_ADS.save(false);
        // No createTime and an id that carries no date either.
        Item undated = post("n", 0);
        undated.createTime = 0;
        Item noId = post("", 60);
        Item softAd = new Item("soft") {
            @Override public boolean isSoftAd() { return true; }
        };
        softAd.createTime = NOW_SECONDS - 60;

        assertEquals(List.of("y", "n", "", "soft", "x"),
                read(emptyList(), post("x", 2 * HOUR), undated, noId, softAd, post("y", 1800)));
    }

    @Test
    public void postsFromTheSameSecondKeepTikToksOrder() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);

        assertEquals(List.of("c", "a", "b"),
                read(emptyList(), post("a", HOUR), post("b", HOUR), post("c", 600)));
    }

    @Test
    public void bothHooksReadingTheSameListSortItOnce() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        FollowFeedList list = emptyList();

        assertEquals(List.of("b", "a"), read(list, post("a", 2 * HOUR), post("b", HOUR)));
        // The presenter's post-process reads the list the getItems hook already sorted.
        FeedItemsFilter.filterLateFinal(list);
        assertEquals(List.of("b", "a"), aids(list));
        FollowingLatestFirst.onCurrentVideo("b");
        assertEquals(List.of("b", "d", "a", "c"), read(list, post("c", 3 * HOUR), post("d", 600)));
        FeedItemsFilter.filterLateFinal(list);
        assertEquals(List.of("b", "d", "a", "c"), aids(list));
    }

    @Test
    public void aListPastTheCapKeepsTikToksOrderFromThenOn() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        FollowFeedList list = emptyList();
        Item[] page = new Item[4097];
        for (int i = 0; i < page.length; i++) page[i] = post("p" + i, i);
        read(list, page);

        List<String> after = read(list, post("old", 5 * HOUR), post("new", 60));
        assertEquals(List.of("old", "new"), after.subList(after.size() - 2, after.size()));
    }

    @Test
    public void aReadOnlyListIsSwappedForASortedCopy() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        FollowFeedList list = emptyList();
        add(list, post("a", 3 * HOUR), post("b", HOUR));
        list.mItems = Collections.unmodifiableList(list.mItems);

        assertEquals(List.of("b", "a"), read(list));
    }

    @Test
    public void offKeepsTikToksOrderUntilTheAppRestarts() {
        FollowFeedList list = emptyList();
        assertEquals(List.of("a", "b"), read(list, post("a", 3 * HOUR), post("b", HOUR)));

        // Turned on with the list open: it waits for the restart the row asks for.
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        assertEquals(List.of("a", "b", "c", "d"), read(list, post("c", 2 * HOUR), post("d", HOUR)));
    }

    @Test
    public void everyReadIsAskedForMorePostsAndTheFirstForTheMost() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);

        assertEquals(FollowingLatestFirst.FIRST_READ_COUNT, FollowingLatestFirst.requestCount(0, 0, 20));
        assertEquals(FollowingLatestFirst.LATER_READ_COUNT,
                FollowingLatestFirst.requestCount(1760000000L, 0, 20));
        // A count already past ours is left alone.
        assertEquals(60, FollowingLatestFirst.requestCount(0, 0, 60));
        assertEquals(60, FollowingLatestFirst.requestCount(1760000000L, 0, 60));
        assertEquals(0, FollowingLatestFirst.requestCount(0, 0, 0));
    }

    @Test
    public void severalLaterBatchesEndUpNewestFirstBelowTheReader() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        FollowFeedList list = emptyList();

        read(list, post("a", 5 * HOUR), post("b", 9 * HOUR));
        FollowingLatestFirst.onCurrentVideo("a");
        read(list, post("c", 7 * HOUR), post("d", HOUR));
        assertEquals(List.of("a", "d", "c", "b"), aids(list));
        // Batch three has the newest and an old one; the reader is still on "a".
        assertEquals(List.of("a", "e", "d", "f", "c", "b"),
                read(list, post("f", 3 * HOUR), post("e", 600)));
        // The reader moves on to "d": "e" above it and "a" stay where they are.
        FollowingLatestFirst.onCurrentVideo("d");
        assertEquals(List.of("a", "e", "d", "g", "f", "c", "b"), read(list, post("g", 2 * HOUR)));
    }

    @Test
    public void aPostTheListAlreadyHoldsIsKeptAndNeverLost() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        FollowFeedList list = emptyList();

        // No video on screen yet, so the first read sorts the whole list.
        assertEquals(List.of("b", "a"), read(list, post("a", 5 * HOUR), post("b", 3 * HOUR)));
        FollowingLatestFirst.onCurrentVideo("a");
        // TikTok hands "b" again in the next batch: both copies stay, the one above "a" untouched.
        assertEquals(List.of("b", "a", "c", "b"), read(list, post("b", 3 * HOUR), post("c", 600)));
    }

    @Test
    public void offLeavesEveryBatchInTikToksOrder() {
        FollowFeedList list = emptyList();

        read(list, post("a", 5 * HOUR), post("b", 9 * HOUR));
        FollowingLatestFirst.onCurrentVideo("a");
        assertEquals(List.of("a", "b", "c", "d"), read(list, post("c", 7 * HOUR), post("d", HOUR)));
        assertEquals(List.of("a", "b", "c", "d", "e"), read(list, post("e", 60)));
    }

    @Test
    public void offAsksForTikToksOwnCount() {
        assertEquals(20, FollowingLatestFirst.requestCount(0, 0, 20));
    }

    @Test
    public void theSavedFirstVideoIsLeftOutOnlyWithTheSwitchOn() {
        assertFalse(FollowingLatestFirst.skipSavedFirstVideo());

        FollowingLatestFirst.resetForTests();
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        assertTrue(FollowingLatestFirst.skipSavedFirstVideo());
    }

    @Test
    public void pausedKeepsTheSavedFirstVideo() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        setPaused(true);
        try {
            assertFalse(FollowingLatestFirst.skipSavedFirstVideo());
        } finally {
            setPaused(false);
        }
    }

    private static void setPaused(boolean paused) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ReflectionHelpers.ClassParameter.from(boolean.class, paused));
    }

    private static Item post(String aid, long ageSeconds) {
        Item item = new Item(aid);
        item.createTime = NOW_SECONDS - ageSeconds;
        return item;
    }

    private static FollowFeedList emptyList() {
        FollowFeedList list = new FollowFeedList();
        list.mItems = new ArrayList<>();
        return list;
    }

    private static void add(FollowFeedList list, Item... items) {
        for (Item item : items) {
            FollowFeed entry = new FollowFeed();
            entry.aweme = item;
            list.mItems.add(entry);
        }
    }

    /** {@code items} appended the way TikTok grows the list, then the list read through getItems. */
    private static List<String> read(FollowFeedList list, Item... items) {
        add(list, items);
        FeedItemsFilter.filterLate(list);
        return aids(list);
    }

    private static List<String> aids(FollowFeedList list) {
        List<String> aids = new ArrayList<>();
        for (Object entry : list.mItems) {
            Item item = (Item) ((FollowFeed) entry).aweme;
            aids.add(item == null ? "-" : item.getAid());
        }
        return aids;
    }
}

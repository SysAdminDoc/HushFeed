package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;
import com.ss.android.ugc.aweme.follow.presenter.FollowFeed;
import com.ss.android.ugc.aweme.follow.presenter.FollowFeedList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Only show videos with these words (D#548): a For You video stays when its caption, hashtags
 * included, matches an entry, a batch with no match keeps its first video so the feed can move
 * on, and an empty list or another feed changes nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class KeptCaptionWordsTest {
    public static final class Author {
        final String handle;
        Author(String handle) { this.handle = handle; }
        public String getUniqueId() { return handle; }
        public String getUid() { return "uid-" + handle; }
        public String getSecUid() { return "sec-" + handle; }
    }

    public static final class Item extends Aweme {
        final String aid;
        final String desc;
        Author author = new Author("poster");

        Item(String aid, String desc) {
            this.aid = aid;
            this.desc = desc;
        }

        @Override public String getAid() { return aid; }
        @Override public String getShareUrl() { return null; }
        public Object getAuthor() { return author; }
        public String getDesc() { return desc; }
    }

    @Before public void reset() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.REMOVE_ADS.save(false);
        Settings.BLOCKED_CAPTION_WORDS.save("");
        Settings.BLOCKED_CREATORS.save("");
        Settings.LOCAL_HIDDEN_CREATORS.save("");
        Settings.CREATOR_FILTER_EXCEPTIONS.save("");
        Settings.MAX_VIDEO_SECONDS.save(0);
        Settings.MAX_PUBLICATION_AGE_DAYS.save(0);
        Settings.MAX_VIEWS_PER_LIKE.save(0);
        Settings.HIDE_UNPERSONALIZED_FOR_YOU.save(false);
        Settings.KEPT_CAPTION_WORDS.save("#recipe, cooking");
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    @After public void restore() {
        Settings.REMOVE_ADS.resetToDefault();
        Settings.BLOCKED_CAPTION_WORDS.resetToDefault();
        Settings.BLOCKED_CREATORS.resetToDefault();
        Settings.CREATOR_FILTER_EXCEPTIONS.resetToDefault();
        Settings.HIDE_UNPERSONALIZED_FOR_YOU.resetToDefault();
        Settings.KEPT_CAPTION_WORDS.resetToDefault();
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    private static List<String> aids(List<?> items) {
        List<String> aids = new ArrayList<>();
        for (Object item : items) aids.add(((Aweme) item).getAid());
        return aids;
    }

    private static List<String> forYou(Item... items) {
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(Arrays.asList(items));
        FeedItemsFilter.filter(page);
        return aids(page.items);
    }

    @Test public void onlyVideosWhoseCaptionMatchesStayOnForYou() {
        assertEquals(List.of("pasta", "stew"), forYou(
                new Item("dance", "new dance #fyp"),
                new Item("pasta", "Weeknight pasta #Recipe"),
                new Item("prank", "gotcha"),
                new Item("stew", "Slow COOKING a stew")));
    }

    @Test public void aBatchWithNoMatchKeepsItsFirstVideoSoTheFeedMovesOn() {
        assertEquals(List.of("a"), forYou(
                new Item("a", "dance"), new Item("b", "prank"), new Item("c", "")));
    }

    @Test public void theBatchFallbackNeverPutsBackWhatAnotherRuleTookOut() {
        Settings.BLOCKED_CAPTION_WORDS.save("prank");
        assertEquals(List.of("b"), forYou(
                new Item("a", "prank"), new Item("b", "dance"), new Item("c", "vlog")));
    }

    @Test public void anExceptedCreatorStaysWithoutAMatchingWord() {
        Settings.CREATOR_FILTER_EXCEPTIONS.save("friend");
        Item friend = new Item("friend", "my cat");
        friend.author = new Author("friend");
        assertEquals(List.of("friend", "pasta"), forYou(
                new Item("dance", "dance"), friend, new Item("pasta", "#recipe")));
    }

    @Test public void theRulesSyntaxIsTheBlockedListsOwn() {
        Settings.KEPT_CAPTION_WORDS.save("\"pasta\" & \"easy\"");
        assertEquals(List.of("easy"), forYou(
                new Item("hard", "pasta, the hard way"),
                new Item("easy", "Easy pasta"),
                new Item("salad", "easy salad")));
    }

    @Test public void anEmptyListChangesNothing() {
        Settings.KEPT_CAPTION_WORDS.save("  ");
        assertEquals(List.of("a", "b"), forYou(new Item("a", "dance"), new Item("b", "prank")));
    }

    @Test public void theFollowingTabIsLeftAlone() {
        FollowFeedList following = new FollowFeedList();
        following.mItems = new ArrayList<>();
        for (Item item : new Item[] {new Item("a", "dance"), new Item("b", "prank")}) {
            FollowFeed entry = new FollowFeed();
            entry.aweme = item;
            following.mItems.add(entry);
        }
        FeedItemsFilter.filter(following);
        FeedItemsFilter.filterLate(following);
        List<String> aids = new ArrayList<>();
        for (Object entry : following.mItems) aids.add(((FollowFeed) entry).aweme.getAid());
        assertEquals(List.of("a", "b"), aids);
    }
}

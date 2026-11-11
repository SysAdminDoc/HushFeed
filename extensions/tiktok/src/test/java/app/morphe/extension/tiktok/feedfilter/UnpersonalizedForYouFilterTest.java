package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;
import com.ss.android.ugc.aweme.feed.model.RecReasonsStruct;
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
 * The For You fill-in rule: TikTok's for_you_page_999 videos with no recommendation reason leave
 * a For You batch, a batch that is nothing but fill-in keeps only its first video, and the filter
 * report says how much of each batch came from which pool.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class UnpersonalizedForYouFilterTest {
    private static final String FILL_IN = "for_you_page_999";

    public static final class Author {
        final String handle;
        Author(String handle) { this.handle = handle; }
        public String getUniqueId() { return handle; }
        public String getUid() { return "uid-" + handle; }
        public String getSecUid() { return "sec-" + handle; }
    }

    public static class Item extends Aweme {
        final String aid;
        final String source;
        final RecReasonsStruct reasons;
        Author author = new Author("poster");

        Item(String aid, String source, RecReasonsStruct reasons) {
            this.aid = aid;
            this.source = source;
            this.reasons = reasons;
        }

        @Override public String getAid() { return aid; }
        @Override public String getItemDistributeSource() { return source; }
        @Override public RecReasonsStruct getRecReasonsStruct() { return reasons; }
        @Override public String getShareUrl() { return null; }
        public Object getAuthor() { return author; }
        public String getDesc() { return ""; }
    }

    /** A video whose distribution must never be read: the switch is off. */
    public static final class Untouchable extends Item {
        Untouchable(String aid) { super(aid, null, null); }
        @Override public String getItemDistributeSource() { throw new AssertionError("read with the switch off"); }
        @Override public RecReasonsStruct getRecReasonsStruct() { throw new AssertionError("read with the switch off"); }
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
        Settings.HIDE_UNPERSONALIZED_FOR_YOU.save(true);
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    @After public void restore() {
        Settings.REMOVE_ADS.resetToDefault();
        Settings.BLOCKED_CREATORS.resetToDefault();
        Settings.HIDE_UNPERSONALIZED_FOR_YOU.resetToDefault();
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    private static Item fill(String aid) { return new Item(aid, FILL_IN, null); }

    private static List<String> survivors(Item... items) {
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(Arrays.asList(items));
        FeedItemsFilter.filter(page);
        List<String> aids = new ArrayList<>();
        for (Object item : page.items) aids.add(((Aweme) item).getAid());
        return aids;
    }

    private static String reportLine(String source) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(source + ":")) return line;
        }
        return null;
    }

    @Test public void fillInLeavesForYouAndEverythingTikTokPickedStays() {
        Item picked = new Item("picked", FILL_IN, new RecReasonsStruct());
        Item personal = new Item("personal", "for_you_page_1", null);
        Item unlabelled = new Item("unlabelled", null, null);
        assertEquals(List.of("picked", "personal", "unlabelled"),
                survivors(fill("fill"), picked, personal, unlabelled));
    }

    @Test public void aBatchThatIsAllFillInKeepsOnlyItsFirstVideo() {
        assertEquals(List.of("a"), survivors(fill("a"), fill("b"), fill("c")));
        String line = reportLine(FeedItemsFilter.FOR_YOU_DISTRIBUTION_SOURCE);
        assertTrue(FeedFilterCounters.report().toString(), line != null && line.contains(FeedItemsFilter.KEPT_ONE_KIND + " 1"));
        assertTrue(line, line.contains("2 removed"));
        String feed = reportLine("FeedItemList:response");
        assertFalse("a batch cut to one was counted as emptied: " + feed, feed != null && feed.contains("left empty"));
    }

    @Test public void keepingTheBatchNeverPutsBackWhatAnotherRuleTookOut() {
        Settings.BLOCKED_CREATORS.save("blocked");
        Item blocked = fill("blocked");
        blocked.author = new Author("blocked");
        assertEquals(List.of("a"), survivors(blocked, fill("a"), fill("b")));
    }

    @Test public void anExceptedCreatorsFillInStays() {
        Settings.CREATOR_FILTER_EXCEPTIONS.save("friend");
        Item friend = fill("friend");
        friend.author = new Author("friend");
        assertEquals(List.of("friend", "personal"),
                survivors(fill("fill"), friend, new Item("personal", "for_you_page_1", null)));
    }

    @Test public void theReportCountsEachPoolNextToWhatTheRuleTookOut() {
        FeedFilterCounters.resetSessionForTests();
        survivors(fill("a"), fill("b"), new Item("c", "for_you_page_1", null));
        String line = reportLine(FeedItemsFilter.FOR_YOU_DISTRIBUTION_SOURCE);
        assertEquals("ForYouDistribution: 1 lists, 3 items, 2 removed. Last reason: UnpersonalizedForYouFilter."
                + " Kinds: for_you_page_999 2, for_you_page_1 1", line);
        // The pool line is a share of the list's own count, so the label counts the two once.
        assertEquals(2, FeedFilterCounters.sessionRemoved());
    }

    @Test public void switchedOffItReadsNothingAndCountsNothing() {
        Settings.HIDE_UNPERSONALIZED_FOR_YOU.save(false);
        Settings.BLOCKED_CREATORS.save("someone_else");
        assertEquals(List.of("a", "b"), survivors(new Untouchable("a"), new Untouchable("b")));
        assertEquals(null, reportLine(FeedItemsFilter.FOR_YOU_DISTRIBUTION_SOURCE));
    }
}

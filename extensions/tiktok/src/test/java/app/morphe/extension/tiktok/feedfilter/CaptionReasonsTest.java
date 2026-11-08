package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;
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
 * The export's Captions line (#217): each For You video counted once, as carrying caption tracks
 * or as TikTok's reason for sending none, with every filter switch off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class CaptionReasonsTest {
    /** TikTok's CaptionModel as 47.7.3 has it: the tracks and the reason an empty list is empty. */
    public static final class Model {
        public final List<Object> captionList;
        public final Integer noOriginalCaptionReasonV2;
        Model(List<Object> captionList, Integer reason) {
            this.captionList = captionList;
            this.noOriginalCaptionReasonV2 = reason;
        }
    }

    public static final class Video {
        final Model model;
        Video(Model model) { this.model = model; }
        public Object getCaptionModel() { return model; }
    }

    public static final class Item extends Aweme {
        final String aid;
        final Video video;
        Item(String aid, Model model) {
            this.aid = aid;
            this.video = new Video(model);
        }
        @Override public String getAid() { return aid; }
        @Override public String getShareUrl() { return null; }
        public Object getVideo() { return video; }
        public String getDesc() { return ""; }
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.REMOVE_ADS.save(false);
        Settings.BLOCKED_CAPTION_WORDS.save("");
        Settings.BLOCKED_CREATORS.save("");
        Settings.HIDE_UNPERSONALIZED_FOR_YOU.save(false);
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    @After public void tearDown() {
        Settings.REMOVE_ADS.resetToDefault();
        Settings.BLOCKED_CAPTION_WORDS.resetToDefault();
        Settings.BLOCKED_CREATORS.resetToDefault();
        Settings.HIDE_UNPERSONALIZED_FOR_YOU.resetToDefault();
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    private static void page(Item... items) {
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(Arrays.asList(items));
        FeedItemsFilter.filter(page);
    }

    private static String captionsLine() {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(CaptionReasons.SOURCE + ":")) return line;
        }
        return null;
    }

    @Test public void eachVideoIsCountedOnceByWhatTikTokSent() {
        Item tracked = new Item("tracked", new Model(List.of(new Object()), null));
        Item locked = new Item("locked", new Model(new ArrayList<>(), 2));
        Item locked2 = new Item("locked2", new Model(null, 2));
        Item quiet = new Item("quiet", new Model(new ArrayList<>(), 3));
        Item unset = new Item("unset", new Model(new ArrayList<>(), null));
        Item newer = new Item("newer", new Model(new ArrayList<>(), 9));
        Item bare = new Item("bare", null);

        page(tracked, locked, locked2, quiet, unset, newer, bare);
        // TikTok reads a page again before it hands it over; the same videos count once.
        page(tracked, locked, locked2, quiet, unset, newer, bare);

        assertEquals("Captions: 1 lists, 7 items, 0 removed. Kinds: none:notAuthorized 2, "
                + "noCaptionData 1, none:code9 1, none:speechUnrecognized 1, none:unset 1, tracks 1",
                captionsLine());
    }

    @Test public void theReasonNamesFollowTikToksEnum() {
        assertEquals("noOp", CaptionReasons.reasonName(0));
        assertEquals("langNotSupported", CaptionReasons.reasonName(4));
        assertEquals("beingGenerated", CaptionReasons.reasonName(5));
        assertEquals("code-1", CaptionReasons.reasonName(-1));
        assertEquals("unset", CaptionReasons.reasonName("2"));
    }

    @Test public void aListThatIsNotForYouIsNotCounted() {
        FeedItemsFilter.filterProfileAds(new ArrayList<>(List.of(new Item("profile", new Model(null, 2)))));
        assertNull(FeedFilterCounters.report().toString(), captionsLine());
    }
}

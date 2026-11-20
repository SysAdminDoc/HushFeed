/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A short drama's episode list (#444), read from the reply TikTok's own player pages through:
 * paid_videos with each episode's post and place, page_info saying whether more follow.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class DramaEpisodesTest {
    private final List<Map<String, String>> asked = new ArrayList<>();
    private final Map<String, String> replies = new HashMap<>();

    @Before public void setUp() {
        DramaEpisodes.readerForTests = json -> "post " + new JSONObject(json).getString("aweme_id");
        DramaEpisodes.sourceForTests = (path, query) -> {
            assertEquals("/tiktok/v1/paid_content/collection/video", path);
            asked.add(query);
            String reply = replies.get(query.get("requested_page"));
            if (reply == null) throw new IOException("no page " + query.get("requested_page"));
            return reply;
        };
    }

    @After public void tearDown() {
        DramaEpisodes.readerForTests = null;
        DramaEpisodes.sourceForTests = null;
        DramaEpisodes.resetForTests();
    }

    /** One episode as TikTok lists it: the post with its paid struct, and its place in the drama. */
    private static JSONObject item(String id, int seq, boolean preview) throws Exception {
        JSONObject paid = new JSONObject().put("paid_collection_id", 7431100L)
                .put("is_partial_preview", preview).put("episode_num", String.valueOf(seq));
        return new JSONObject().put("seq_id", seq)
                .put("aweme", new JSONObject().put("aweme_id", id).put("paid_content_info", paid));
    }

    private static String reply(int page, boolean hasNext, JSONObject... items) throws Exception {
        JSONArray videos = new JSONArray();
        for (JSONObject item : items) videos.put(item);
        return new JSONObject().put("status_code", 0).put("paid_videos", videos).put("num_videos", 42)
                .put("page_info", new JSONObject().put("curr_page", page).put("has_next", hasNext)
                        .put("has_before", page > 0))
                .toString();
    }

    @Test public void aPageReadsEachEpisodesPostNumberAndPreviewFlag() throws Exception {
        JSONObject intro = item("100", 0, false).put("is_intro", true);
        JSONObject locked = item("103", 3, true);
        // A locked episode can come with the whole episode next to its preview.
        JSONObject unlockedCopy = item("104", 4, true);
        unlockedCopy.put("full_paid_videos_for_partial_preview", new JSONObject().put("aweme_id", "104")
                .put("paid_content_info", new JSONObject().put("is_partial_preview", false)));
        JSONObject unnumbered = new JSONObject().put("aweme", new JSONObject().put("aweme_id", "105")
                .put("paid_content_info", new JSONObject().put("episode_num", "9")));
        JSONObject rowOnly = new JSONObject().put("aweme", new JSONObject().put("aweme_id", "106"));
        JSONObject noPost = new JSONObject().put("seq_id", 7);

        DramaEpisodes.Page page = DramaEpisodes.page(reply(2, true, intro, item("101", 1, false), locked,
                unlockedCopy, unnumbered, rowOnly, noPost), DramaEpisodes.readerForTests);

        assertTrue(page.hasNext);
        assertEquals(2, page.page);
        assertEquals(42, page.total);
        assertEquals("the intro is skipped and an item with no post is left out", 5, page.episodes.size());
        DramaEpisodes.Episode first = page.episodes.get(0);
        assertEquals("101", first.id);
        assertEquals("post 101", first.post);
        assertEquals(1, first.number);
        assertFalse(first.preview);
        assertTrue("a locked episode is marked a preview", page.episodes.get(1).preview);
        assertFalse("the whole episode is used when TikTok sends it", page.episodes.get(2).preview);
        assertEquals(4, page.episodes.get(2).number);
        assertEquals("episode_num stands in for a missing seq_id", 9, page.episodes.get(3).number);
        assertEquals("the row stands in for both", 6, page.episodes.get(4).number);
    }

    @Test public void aLockedEpisodeWithAnEmptyWholeEpisodeSlotIsListedFromItsPost() throws Exception {
        // #463: on a drama the account hasn't bought, episodes past the free ones came with a
        // whole-episode slot holding no post, and the picker stopped at the last free episode.
        JSONObject emptySlot = item("113", 13, true).put("full_paid_videos_for_partial_preview", new JSONObject());
        JSONObject slotWithoutId = item("114", 14, true).put("full_paid_videos_for_partial_preview",
                new JSONObject().put("paid_content_info", new JSONObject().put("is_partial_preview", false)));

        DramaEpisodes.Page page = DramaEpisodes.page(reply(1, true, item("112", 12, false), emptySlot,
                slotWithoutId), DramaEpisodes.readerForTests);

        List<String> ids = new ArrayList<>();
        for (DramaEpisodes.Episode episode : page.episodes) ids.add(episode.id);
        assertEquals(List.of("112", "113", "114"), ids);
        assertTrue("a locked episode read from its listed post is a preview", page.episodes.get(1).preview);
        assertEquals(14, page.episodes.get(2).number);
        assertTrue(page.episodes.get(2).preview);
    }

    @Test public void aRefusalOrNoReplyThrows() throws Exception {
        String refused = new JSONObject().put("status_code", 2483).put("status_msg", "not available").toString();
        try {
            DramaEpisodes.page(refused, DramaEpisodes.readerForTests);
            fail("a refusal read as an empty drama");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("2483"));
        }
        try {
            DramaEpisodes.page(null, DramaEpisodes.readerForTests);
            fail("no reply read as an empty drama");
        } catch (IOException expected) {
            // The picker says it couldn't load.
        }
        DramaEpisodes.Page last = DramaEpisodes.page(reply(0, false), DramaEpisodes.readerForTests);
        assertTrue(last.episodes.isEmpty());
        assertFalse(last.hasNext);
    }

    @Test public void everyPageIsAskedForInTurnAndTheListComesBackInEpisodeOrder() throws Exception {
        replies.put("0", reply(0, true, item("12", 2, false), item("11", 1, false)));
        replies.put("1", reply(1, true, item("13", 3, true), item("12", 2, false)));
        replies.put("2", reply(2, false, item("14", 4, true)));

        List<DramaEpisodes.Episode> episodes = DramaEpisodes.load(7431100L);

        assertEquals(3, asked.size());
        List<String> ids = new ArrayList<>();
        for (DramaEpisodes.Episode episode : episodes) ids.add(episode.id);
        assertEquals("an episode two pages send is listed once", List.of("11", "12", "13", "14"), ids);
        Map<String, String> query = asked.get(0);
        assertEquals("7431100", query.get("collection_id"));
        assertEquals("0", query.get("requested_page"));
        assertEquals("0", query.get("video_list_type"));
        assertEquals(String.valueOf(DramaEpisodes.PAGE_SIZE), query.get("page_size"));
        assertEquals("2", asked.get(2).get("requested_page"));
    }

    @Test public void aServerThatCountsPagesFromOneIsFollowedToo() throws Exception {
        // Asked for page 0, TikTok answers that it sent page 1; the next asked for is 2.
        replies.put("0", reply(1, true, item("21", 1, false)));
        replies.put("2", reply(2, false, item("22", 2, false)));
        assertEquals(2, DramaEpisodes.load(1L).size());
        assertEquals("2", asked.get(1).get("requested_page"));
    }

    /** #463: TikTok's list now starts at page 1, and page 0 is empty but says more follow. */
    @Test public void anEmptyPageZeroThatSaysMoreFollowIsSteppedOver() throws Exception {
        replies.put("0", reply(0, true));
        replies.put("1", reply(1, true, item("41", 1, false)));
        replies.put("2", reply(2, false, item("42", 2, false)));

        List<DramaEpisodes.Episode> episodes = DramaEpisodes.load(1L);

        assertEquals(2, episodes.size());
        assertEquals("41", episodes.get(0).id);
        assertEquals(3, asked.size());
        assertEquals("1", asked.get(1).get("requested_page"));
        assertEquals("2", asked.get(2).get("requested_page"));
    }

    @Test public void aSecondEmptyPageEndsTheList() throws Exception {
        replies.put("0", reply(0, true));
        replies.put("1", reply(1, true));
        replies.put("2", reply(2, false, item("51", 1, false)));
        assertTrue(DramaEpisodes.load(1L).isEmpty());
        assertEquals("an empty drama isn't asked thirty times", 2, asked.size());
    }

    @Test public void anEmptyFirstPageWithNothingMoreIsAnEmptyList() throws Exception {
        replies.put("0", reply(0, false));
        assertTrue(DramaEpisodes.load(1L).isEmpty());
        assertEquals(1, asked.size());
    }

    @Test public void aPageWithNothingNewEndsTheList() throws Exception {
        replies.put("0", reply(0, true, item("31", 1, false)));
        replies.put("1", reply(1, true, item("31", 1, false)));
        replies.put("2", reply(2, true, item("32", 2, false)));
        assertEquals(1, DramaEpisodes.load(1L).size());
        assertEquals("a server stuck on one page isn't asked thirty times", 2, asked.size());
    }

    @Test public void aFailedFirstPageThrowsAndALaterOneKeepsWhatCameBefore() throws Exception {
        try {
            DramaEpisodes.load(1L);
            fail("an unreachable drama read as empty");
        } catch (IOException expected) {
            // The picker shows its couldn't-load toast.
        }
        replies.put("0", reply(0, true, item("41", 1, false)));
        assertEquals(1, DramaEpisodes.load(1L).size());
    }

    /** A post's video with one address in its play address and one in each rendition given. */
    private static JSONObject video(int renditions) throws Exception {
        JSONArray rates = new JSONArray();
        for (int i = 0; i < renditions; i++) {
            rates.put(new JSONObject().put("play_addr", new JSONObject()
                    .put("url_list", new JSONArray().put("https://v16m.example/" + i + ".mp4"))));
        }
        return new JSONObject().put("bit_rate", rates).put("play_addr", new JSONObject()
                .put("url_list", new JSONArray().put("https://v16m.example/play.mp4")));
    }

    @Test public void anEpisodeListedWithoutItsVideoIsAskedForOnItsOwn() throws Exception {
        Post episode = new Post(new Paid(7431100L, "Night Shift"));
        DramaEpisodes.sourceForTests = (path, query) -> {
            assertEquals("/aweme/v1/aweme/detail/", path);
            asked.add(query);
            return new JSONObject().put("status_code", 0).put("aweme_detail", new JSONObject()
                    .put("aweme_id", query.get("aweme_id")).put("video", video(2))).toString();
        };
        assertEquals("post 13", DramaEpisodes.whole(episode, "13"));
        assertEquals("13", asked.get(0).get("aweme_id"));
        assertNull("a post that isn't a drama's isn't asked for", DramaEpisodes.whole(new Post(null), "14"));
        assertNull(DramaEpisodes.whole(episode, ""));
        assertEquals(1, asked.size());

        DramaEpisodes.sourceForTests = (path, query) -> new JSONObject().put("status_code", 0)
                .put("aweme_detail", new JSONObject().put("aweme_id", "15")
                        .put("video", new JSONObject().put("play_addr", new JSONObject()))).toString();
        assertNull("a reply with no video either", DramaEpisodes.whole(episode, "15"));
        DramaEpisodes.sourceForTests = (path, query) -> new JSONObject().put("status_code", 2053).toString();
        assertNull("a refusal", DramaEpisodes.whole(episode, "16"));
        DramaEpisodes.sourceForTests = (path, query) -> {
            throw new IOException("offline");
        };
        assertNull(DramaEpisodes.whole(episode, "17"));
    }

    /** Any post by id, for a data export's likes: a missing post is null, a failed ask throws. */
    @Test public void anyPostIsAskedForByIdAndAMissingOneIsTellableFromAFailedAsk() throws Exception {
        List<Map<String, String>> asked = new ArrayList<>();
        DramaEpisodes.sourceForTests = (path, query) -> {
            assertEquals(DramaEpisodes.DETAIL_PATH, path);
            asked.add(query);
            return new JSONObject().put("status_code", 0)
                    .put("aweme_detail", new JSONObject().put("aweme_id", query.get("aweme_id"))).toString();
        };
        assertEquals("a photo post has no video and still comes back", "post 21", DramaEpisodes.post("21"));
        assertEquals("21", asked.get(0).get("aweme_id"));

        DramaEpisodes.sourceForTests = (path, query) -> new JSONObject().put("status_code", 0)
                .put("filter_detail", new JSONObject().put("aweme_id", "22")).toString();
        assertNull("TikTok sent no post: deleted or private", DramaEpisodes.post("22"));

        DramaEpisodes.sourceForTests = (path, query) -> new JSONObject().put("status_code", 2483).toString();
        assertThrows(IOException.class, () -> DramaEpisodes.post("23"));
        DramaEpisodes.sourceForTests = (path, query) -> null;
        assertThrows(IOException.class, () -> DramaEpisodes.post("24"));
    }

    @Test public void aPostsAddressesAreCountedAcrossItsPlayAddressAndRenditions() throws Exception {
        assertEquals(3, DramaEpisodes.addresses(new JSONObject().put("video", video(2))));
        assertEquals(1, DramaEpisodes.addresses(new JSONObject().put("video", video(0))));
        assertEquals("the list's copy of an episode past the free ones", 0,
                DramaEpisodes.addresses(new JSONObject().put("video", new JSONObject().put("cover", "x"))));
        assertEquals(0, DramaEpisodes.addresses(new JSONObject().put("aweme_id", "1")));
        assertEquals(0, DramaEpisodes.addresses(null));
    }

    @Test public void theDramaIdAndNameComeFromThePostsPaidStruct() {
        assertEquals(7431100L, DramaEpisodes.collectionId(new Post(new Paid(7431100L, "  Night Shift "))));
        assertEquals("Night Shift", DramaEpisodes.name(new Post(new Paid(7431100L, "  Night Shift "))));
        assertEquals(0L, DramaEpisodes.collectionId(new Post(new Paid(0L, ""))));
        assertNull(DramaEpisodes.name(new Post(new Paid(0L, " "))));
        assertEquals(0L, DramaEpisodes.collectionId(new Post(null)));
        assertEquals(0L, DramaEpisodes.collectionId(null));
    }

    @Test public void gsonsFromJsonIsFoundByItsShapeAndOnlyWhenThereIsOne() throws Exception {
        assertEquals("a", DramaEpisodes.fromJson(OneShape.class).getName());
        try {
            DramaEpisodes.fromJson(TwoShapes.class);
            fail("picked one of two candidates");
        } catch (NoSuchMethodException expected) {
            // Reading a post with the wrong one would fail later and say less.
        }
        try {
            DramaEpisodes.fromJson(Object.class);
            fail("found a fromJson on a class with none");
        } catch (NoSuchMethodException expected) {
            // So the picker says it couldn't load.
        }
    }

    public static final class Paid {
        public long paidCollectionId;
        public String collectionName;

        Paid(long paidCollectionId, String collectionName) {
            this.paidCollectionId = paidCollectionId;
            this.collectionName = collectionName;
        }
    }

    public static final class Post {
        public Object mPaidContentInfo;

        Post(Object paid) { mPaidContentInfo = paid; }
    }

    /** Gson's shape, its name shortened the way R8 leaves it. */
    public static final class OneShape {
        public Object a(String json, Class<?> type) { return null; }
        public Object b(String json, java.lang.reflect.Type type) { return null; }
        public String c(Object value) { return null; }
    }

    public static final class TwoShapes {
        public Object a(String json, Class<?> type) { return null; }
        public Object b(String json, Class<?> type) { return null; }
    }
}

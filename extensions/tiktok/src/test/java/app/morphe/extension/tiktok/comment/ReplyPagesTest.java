package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** How an export pages through the replies a sheet didn't show, and how TikTok's call is made. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ReplyPagesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A reply as the page brings it: only the id matters to the paging. */
    public static final class Reply {
        public final String cid;

        Reply(String id) {
            cid = id;
        }
    }

    private static List<Object> replyList(String thread, int from, int to) {
        List<Object> list = new ArrayList<>();
        for (int index = from; index <= to; index++) list.add(new Reply(thread + "-" + index));
        return list;
    }

    private static ReplyPages.Missing missing(String thread, long expected, String... known) {
        return new ReplyPages.Missing(thread, expected, new HashSet<>(Arrays.asList(known)));
    }

    /** Answers from a script of pages per thread, and keeps every ask. */
    private static class Scripted implements ReplyPages.Fetcher {
        final Map<String, Deque<ReplyPages.Page>> pages = new HashMap<>();
        final List<String> asked = new ArrayList<>();

        Scripted page(String thread, List<Object> items, long cursor, boolean more) {
            pages.computeIfAbsent(thread, key -> new ArrayDeque<>()).add(new ReplyPages.Page(items, cursor, more));
            return this;
        }

        @Override
        public ReplyPages.Page page(String threadId, String itemId, long cursor, int count, ReplyPages.Load load)
                throws InterruptedException {
            asked.add(threadId + "@" + cursor + "/" + count + "/" + itemId);
            Deque<ReplyPages.Page> left = pages.get(threadId);
            return left == null || left.isEmpty() ? null : left.removeFirst();
        }
    }

    private static List<String> ids(List<Object> replies) {
        List<String> ids = new ArrayList<>();
        for (Object reply : replies) ids.add(((Reply) reply).cid);
        return ids;
    }

    private static ReplyPages.Outcome run(ReplyPages.Fetcher fetcher, ReplyPages.Load load, List<int[]> progress,
            ReplyPages.Missing... threads) throws InterruptedException {
        ReplyPages.Outcome outcome = new ReplyPages.Outcome();
        ReplyPages.run(fetcher, "7300", Arrays.asList(threads), load, outcome,
                (fetched, wanted) -> progress.add(new int[]{fetched, wanted}));
        return outcome;
    }

    // ---- the paging ---------------------------------------------------------------------

    @Test public void everyThreadIsPagedToItsEndAndRepliesTheSheetHasAreSkipped() throws Exception {
        Scripted fetcher = new Scripted()
                .page("10", replyList("10", 1, 20), 20, true)
                .page("10", replyList("10", 21, 40), 40, true)
                .page("10", replyList("10", 41, 42), 42, false)
                .page("20", replyList("20", 1, 3), 3, false);
        List<int[]> progress = new ArrayList<>();

        ReplyPages.Outcome outcome = run(fetcher, new ReplyPages.Load(), progress,
                missing("10", 42, "10-1"), missing("20", 3));

        assertEquals(Arrays.asList("10@0/20/7300", "10@20/20/7300", "10@40/20/7300", "20@0/20/7300"), fetcher.asked);
        assertEquals(Arrays.asList("10", "20"), new ArrayList<>(outcome.byThread.keySet()));
        List<String> first = ids(outcome.byThread.get("10"));
        assertEquals(41, first.size());
        assertFalse("the reply the sheet had came back twice", first.contains("10-1"));
        assertEquals("10-2", first.get(0));
        assertEquals("10-42", first.get(40));
        assertEquals(Arrays.asList("20-1", "20-2", "20-3"), ids(outcome.byThread.get("20")));
        assertEquals(44, outcome.fetched);
        assertTrue(outcome.complete);
        assertEquals(4, progress.size());
        assertArrayEquals(new int[]{19, 44}, progress.get(0));
        assertArrayEquals(new int[]{44, 44}, progress.get(3));
    }

    @Test public void aFailedPageLeavesItsThreadShortAndTheNextThreadStillLoads() throws Exception {
        Scripted fetcher = new Scripted().page("20", replyList("20", 1, 2), 2, false);

        ReplyPages.Outcome outcome = run(fetcher, new ReplyPages.Load(), new ArrayList<>(),
                missing("10", 5), missing("20", 2));

        assertFalse(outcome.complete);
        assertTrue(outcome.byThread.get("10").isEmpty());
        assertEquals(Arrays.asList("20-1", "20-2"), ids(outcome.byThread.get("20")));
    }

    @Test public void aPageThatDoesNotMoveOnEndsItsThreadShort() throws Exception {
        Scripted stuck = new Scripted()
                .page("10", replyList("10", 1, 20), 0, true)
                .page("10", replyList("10", 21, 40), 40, false);
        ReplyPages.Outcome outcome = run(stuck, new ReplyPages.Load(), new ArrayList<>(), missing("10", 40));
        assertFalse(outcome.complete);
        assertEquals(1, stuck.asked.size());
        assertEquals(20, outcome.byThread.get("10").size());

        Scripted empty = new Scripted()
                .page("10", Collections.emptyList(), 60, true)
                .page("10", replyList("10", 1, 20), 80, false);
        outcome = run(empty, new ReplyPages.Load(), new ArrayList<>(), missing("10", 20));
        assertFalse(outcome.complete);
        assertEquals(1, empty.asked.size());
    }

    @Test public void aStopEndsTheLoadAfterThePageInFlight() throws Exception {
        ReplyPages.Load load = new ReplyPages.Load();
        Scripted fetcher = new Scripted() {
            @Override
            public ReplyPages.Page page(String threadId, String itemId, long cursor, int count, ReplyPages.Load l)
                    throws InterruptedException {
                load.stop();
                return super.page(threadId, itemId, cursor, count, l);
            }
        }
                .page("10", replyList("10", 1, 20), 20, true)
                .page("10", replyList("10", 21, 30), 30, false)
                .page("20", replyList("20", 1, 2), 2, false);

        ReplyPages.Outcome outcome = run(fetcher, load, new ArrayList<>(), missing("10", 30), missing("20", 2));

        assertFalse(outcome.complete);
        assertEquals(Collections.singletonList("10@0/20/7300"), fetcher.asked);
        // What came before the stop is kept.
        assertEquals(20, outcome.byThread.get("10").size());
        assertNull(outcome.byThread.get("20"));
    }

    @Test public void theCapEndsTheLoadAndKeepsWhatCame() throws Exception {
        // A thread that never ends, a thousand new replies a page.
        ReplyPages.Fetcher endless = new ReplyPages.Fetcher() {
            int pages;

            @Override
            public ReplyPages.Page page(String threadId, String itemId, long cursor, int count, ReplyPages.Load load) {
                pages++;
                return new ReplyPages.Page(replyList(threadId + "p" + pages, 1, 1000), cursor + 1000, true);
            }
        };
        Scripted after = new Scripted().page("20", replyList("20", 1, 2), 2, false);
        ReplyPages.Fetcher both = (threadId, itemId, cursor, count, load) -> "20".equals(threadId)
                ? after.page(threadId, itemId, cursor, count, load)
                : endless.page(threadId, itemId, cursor, count, load);

        ReplyPages.Outcome outcome = run(both, new ReplyPages.Load(), new ArrayList<>(),
                missing("10", 9000), missing("20", 2));

        assertFalse(outcome.complete);
        assertEquals(ReplyPages.MAX_FETCHED_REPLIES, outcome.fetched);
        assertEquals(ReplyPages.MAX_FETCHED_REPLIES, outcome.byThread.get("10").size());
        assertTrue("a thread past the cap was still asked for", after.asked.isEmpty());
    }

    @Test public void anEmptyLastPageOnAThreadStillShortOfItsCountIsIncomplete() throws Exception {
        Scripted withheld = new Scripted().page("10", Collections.emptyList(), 0, false);
        assertFalse(run(withheld, new ReplyPages.Load(), new ArrayList<>(), missing("10", 5, "10-1")).complete);

        // An empty last page after the count is met is just the end.
        Scripted ended = new Scripted()
                .page("10", replyList("10", 1, 2), 2, true)
                .page("10", Collections.emptyList(), 2, false);
        assertTrue(run(ended, new ReplyPages.Load(), new ArrayList<>(), missing("10", 2)).complete);
    }

    @Test public void theCapIsExactWhenPagesDoNotDivideIt() throws Exception {
        // Two big pages cross the cap partway through the second, so it has to cut mid-page.
        int perPage = ReplyPages.MAX_FETCHED_REPLIES * 2 / 3 - 1;
        ReplyPages.Fetcher big = new ReplyPages.Fetcher() {
            int pages;

            @Override
            public ReplyPages.Page page(String threadId, String itemId, long cursor, int count, ReplyPages.Load load) {
                pages++;
                return new ReplyPages.Page(replyList(threadId + "p" + pages, 1, perPage), cursor + perPage, true);
            }
        };
        ReplyPages.Outcome outcome = run(big, new ReplyPages.Load(), new ArrayList<>(), missing("10", 9000));
        assertFalse(outcome.complete);
        assertEquals(ReplyPages.MAX_FETCHED_REPLIES, outcome.fetched);
        assertEquals(ReplyPages.MAX_FETCHED_REPLIES, outcome.byThread.get("10").size());
    }

    @Test public void aStopDuringTheGapBetweenPagesSendsNothingMore() throws Exception {
        ReplyPages.Load load = new ReplyPages.Load();
        Scripted fetcher = new Scripted()
                .page("10", replyList("10", 1, 2), 2, false)
                .page("20", replyList("20", 1, 2), 2, false);
        ReplyPages.Outcome outcome = new ReplyPages.Outcome();
        ReplyPages.run(fetcher, "7300", Arrays.asList(missing("10", 2), missing("20", 2)), load, outcome,
                (fetched, wanted) -> new Thread(() -> {
                    try {
                        Thread.sleep(ReplyPages.PAGE_GAP_MS / 5);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    load.stop();
                }).start());
        assertFalse(outcome.complete);
        assertEquals(Collections.singletonList("10@0/20/7300"), fetcher.asked);
    }

    @Test public void pagesFailingInARowEndTheLoad() throws Exception {
        Scripted refusing = new Scripted();
        ReplyPages.Outcome outcome = run(refusing, new ReplyPages.Load(), new ArrayList<>(),
                missing("10", 2), missing("20", 2), missing("30", 2), missing("40", 2), missing("50", 2));
        assertFalse(outcome.complete);
        assertEquals(ReplyPages.MAX_FAILED_PAGES_IN_A_ROW, refusing.asked.size());
    }

    @Test public void theWantedCountIsOnlyWhatTheSheetIsMissing() {
        assertEquals(3, ReplyPages.wanted(Arrays.asList(missing("10", 5, "a", "b"), missing("20", 1, "c", "d"))));
        assertEquals(0, ReplyPages.wanted(Collections.emptyList()));
    }

    // ---- TikTok's reply list ------------------------------------------------------------

    /** Stands in for CommentItemList, with the getters it has on the declared builds. */
    public static final class ItemList {
        private final List<Object> items;
        private final long cursor;
        private final boolean hasMore;

        ItemList(List<Object> items, long cursor, boolean hasMore) {
            this.items = items;
            this.cursor = cursor;
            this.hasMore = hasMore;
        }

        public List<Object> getItems() { return items; }
        public long getCursor() { return cursor; }
        public boolean isHasMore() { return hasMore; }
    }

    /** A list that only has fields, the way an older build kept them. */
    public static final class FieldList {
        public List<Object> items = replyList("f", 1, 2);
        public long cursor = 9;
        public boolean hasMore = true;
    }

    @Test public void aReplyListIsReadByItsGettersOrItsFields() {
        ReplyPages.Page page = ReplyPages.asPage(new ItemList(replyList("10", 1, 3), 3, true));
        assertNotNull(page);
        assertEquals(3, page.items.size());
        assertEquals(3, page.cursor);
        assertTrue(page.hasMore);

        page = ReplyPages.asPage(new FieldList());
        assertNotNull(page);
        assertEquals(2, page.items.size());
        assertEquals(9, page.cursor);
        assertTrue(page.hasMore);

        // COROUTINE_SUSPENDED, a failure or nothing at all: no page yet.
        assertNull(ReplyPages.asPage(Thread.State.NEW));
        assertNull(ReplyPages.asPage("failure"));
        assertNull(ReplyPages.asPage(null));
        // A refusal: an empty list carrying TikTok's error status.
        assertNull(ReplyPages.asPage(new Refused()));
    }

    /** What TikTok answers when it turns the call down: a reply list with status_code set. */
    public static final class Refused {
        public List<Object> items = Collections.emptyList();
        public long cursor;
        public boolean hasMore;
        public int status_code = 8;
    }

    // ---- TikTok's call, made the way a suspend call is ----------------------------------

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface Get { String value(); }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
    public @interface Query { String value(); }

    /** An annotation with no value, like the encoding marker TikTok puts beside some queries. */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
    public @interface Encoded {}

    public interface Key {}

    public interface Element {}

    /** Kotlin's CoroutineContext, as the obfuscated build declares it. */
    public interface CoroutineContext {
        Object fold(Object initial, Object operation);
        Element get(Key key);
        CoroutineContext minusKey(Key key);
        CoroutineContext plus(CoroutineContext other);
    }

    /** Kotlin's Continuation, as the obfuscated build declares it. */
    public interface Continuation {
        CoroutineContext getContext();
        void resumeWith(Object result);
    }

    public interface Api {
        @Get("/aweme/v1/comment/list/")
        Object topLevel(@Query("aweme_id") String id, @Query("cursor") long cursor, @Query("count") int count,
                @Query("insert_ids") String insert, Continuation continuation);

        /** The photo call shares the path but takes no cursor, so it can't page. */
        @Get(ReplyPages.REPLY_PATH)
        Object photo(@Query("comment_id") String id, @Query("item_id") String item, @Query("count") int count,
                @Query("top_ids") String top, Continuation continuation);

        @Get(ReplyPages.REPLY_PATH)
        Object replies(@Query("comment_id") String id, @Query("cursor") long cursor, @Query("count") int count,
                @Query("top_ids") String top, @Query("item_id") String item, @Query("insert_ids") String insert,
                @Query("need_translation") boolean translate, @Query("preload") Integer preload,
                @Encoded @Query("session_id") String session, Continuation continuation);
    }

    /** A Result.Failure: Kotlin's wrapper around why a suspend call has no answer. */
    public static final class Failure {
        public final Throwable exception;

        Failure(Throwable exception) {
            this.exception = exception;
        }
    }

    private static final Object SUSPENDED = Thread.State.WAITING;
    private static volatile String mode = "now";
    private static volatile Object[] lastArgs;
    private static volatile String contextAnswers;

    /** Stands in for CommentApiV2: a shared instance in a static field, the call on its interface. */
    public static final class ApiImpl implements Api {
        public static final ApiImpl SHARED = new ApiImpl();

        @Override
        public Object topLevel(String id, long cursor, int count, String insert, Continuation continuation) {
            throw new AssertionError("the top-level list was called for replies");
        }

        @Override
        public Object photo(String id, String item, int count, String top, Continuation continuation) {
            throw new AssertionError("the photo call can't page");
        }

        @Override
        public Object replies(String id, long cursor, int count, String top, String item, String insert,
                boolean translate, Integer preload, String session, Continuation continuation) {
            lastArgs = new Object[]{id, cursor, count, top, item, insert, translate, preload, session};
            ItemList page = new ItemList(replyList(id, (int) cursor + 1, (int) cursor + 2), cursor + 2, true);
            switch (mode) {
                case "now":
                    return page;
                case "later":
                    new Thread(() -> {
                        contextAnswers = askContext(continuation.getContext());
                        continuation.resumeWith(page);
                    }).start();
                    return SUSPENDED;
                case "failure":
                    new Thread(() -> continuation.resumeWith(new Failure(new IOException("network down")))).start();
                    return SUSPENDED;
                case "throws":
                    throw new IllegalStateException("no network module");
                case "direct":
                    return "not a reply list";
                case "strict":
                    // A Kotlin override checks its non-null Strings on the way in.
                    if (top == null || session == null) {
                        throw new NullPointerException("Parameter specified as non-null is null");
                    }
                    return page;
                default:
                    return SUSPENDED;
            }
        }
    }

    /** What the coroutine machinery asks a context while it resumes a call. */
    private static String askContext(CoroutineContext context) {
        Key key = new Key() {};
        CoroutineContext other = new CoroutineContext() {
            @Override public Object fold(Object initial, Object operation) { return initial; }
            @Override public Element get(Key k) { return null; }
            @Override public CoroutineContext minusKey(Key k) { return this; }
            @Override public CoroutineContext plus(CoroutineContext c) { return c; }
        };
        return "get=" + context.get(key)
                + " fold=" + context.fold("start", new Object())
                + " plus=" + (context.plus(other) == other ? "other" : "wrong")
                + " minus=" + (context.minusKey(key) == context ? "self" : "wrong")
                + " equals=" + context.equals(context)
                + " hash=" + (context.hashCode() == System.identityHashCode(context))
                + " text=" + (context.toString() != null);
    }

    /** A class with the call but no shared instance to make it on. */
    public static final class Unshared implements Api {
        @Override public Object topLevel(String i, long c, int n, String s, Continuation k) { return null; }
        @Override public Object photo(String i, String t, int n, String s, Continuation k) { return null; }
        @Override public Object replies(String i, long c, int n, String t, String m, String s, boolean b,
                Integer p, String x, Continuation k) { return null; }
    }

    public interface Other {
        @Get("/aweme/v1/comment/list/")
        Object topLevel(@Query("aweme_id") String id, Continuation continuation);
    }

    /** A shared instance whose interface has no reply call. */
    public static final class NoReplies implements Other {
        public static final NoReplies SHARED = new NoReplies();
        @Override public Object topLevel(String id, Continuation continuation) { return null; }
    }

    private static ReplyPages.Page ask(ReplyPages.Fetcher call, ReplyPages.Load load) throws Exception {
        return call.page("10", "7300", 4, ReplyPages.PAGE_SIZE, load);
    }

    @Test public void theCallIsFoundByItsPathAndQueriesAndAskedWithTheThreadAndCursor() throws Exception {
        mode = "now";
        ReplyPages.Fetcher call = ReplyPages.TikTokCall.find(ApiImpl.class);
        ReplyPages.Page page = ask(call, new ReplyPages.Load());
        assertNotNull(page);
        assertEquals(Arrays.asList("10-5", "10-6"), ids(new ArrayList<>(page.items)));
        assertEquals(6, page.cursor);
        assertTrue(page.hasMore);
        // comment_id, cursor, count, top_ids, item_id, insert_ids, need_translation, preload, session_id
        assertArrayEquals(new Object[]{"10", 4L, 20, null, "7300", "", false, null, null}, lastArgs);
    }

    @Test public void aCallThatAnswersLaterIsWaitedForOnAnEmptyContext() throws Exception {
        mode = "later";
        contextAnswers = null;
        ReplyPages.Page page = ask(ReplyPages.TikTokCall.find(ApiImpl.class), new ReplyPages.Load());
        assertNotNull(page);
        assertEquals(Arrays.asList("10-5", "10-6"), ids(new ArrayList<>(page.items)));
        assertEquals("get=null fold=start plus=other minus=self equals=true hash=true text=true", contextAnswers);
    }

    @Test public void aFailedOrThrowingCallIsNoPage() throws Exception {
        ReplyPages.Fetcher call = ReplyPages.TikTokCall.find(ApiImpl.class);
        mode = "failure";
        assertNull(ask(call, new ReplyPages.Load()));
        mode = "throws";
        assertNull(ask(call, new ReplyPages.Load()));
    }

    @Test public void anAnswerThatIsNotAReplyListIsNoPageWithoutWaiting() throws Exception {
        mode = "direct";
        long started = System.nanoTime();
        assertNull(ask(ReplyPages.TikTokCall.find(ApiImpl.class), new ReplyPages.Load()));
        long waited = (System.nanoTime() - started) / 1_000_000L;
        assertTrue("waited " + waited + " ms for an answer that had come", waited < ReplyPages.PAGE_TIMEOUT_MS / 3);
    }

    @Test public void aRefusedNullIsAskedAgainWithBlanks() throws Exception {
        mode = "strict";
        ReplyPages.Fetcher call = ReplyPages.TikTokCall.find(ApiImpl.class);
        ReplyPages.Page page = ask(call, new ReplyPages.Load());
        assertNotNull(page);
        assertArrayEquals(new Object[]{"10", 4L, 20, "", "7300", "", false, null, ""}, lastArgs);
        // And straight away from then on.
        assertNotNull(ask(call, new ReplyPages.Load()));
    }

    @Test public void aStopEndsTheWaitForACallThatNeverAnswers() throws Exception {
        mode = "never";
        ReplyPages.Load load = new ReplyPages.Load();
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            load.stop();
        }).start();
        long started = System.nanoTime();
        assertNull(ask(ReplyPages.TikTokCall.find(ApiImpl.class), load));
        long waited = (System.nanoTime() - started) / 1_000_000L;
        assertTrue("waited " + waited + " ms after the stop", waited < ReplyPages.PAGE_TIMEOUT_MS / 3);
    }

    @Test public void aBuildWithoutTheCallInThisShapeIsRefusedWithAReason() {
        try {
            ReplyPages.TikTokCall.find(Unshared.class);
            fail("a class with no shared instance was accepted");
        } catch (ReflectiveOperationException expected) {
            assertTrue(expected instanceof NoSuchFieldException);
        }
        try {
            ReplyPages.TikTokCall.find(NoReplies.class);
            fail("an API with no reply call was accepted");
        } catch (ReflectiveOperationException expected) {
            assertTrue(expected instanceof NoSuchMethodException);
        }
    }

    @Test public void offTikTokTheExportHasNoReplyCallRatherThanFailing() {
        ReplyPages.setFetcherForTests(null);
        assertNull(ReplyPages.tiktok());
        ReplyPages.Fetcher fake = new Scripted();
        ReplyPages.setFetcherForTests(fake);
        try {
            assertSame(fake, ReplyPages.tiktok());
        } finally {
            ReplyPages.setFetcherForTests(null);
        }
    }
}

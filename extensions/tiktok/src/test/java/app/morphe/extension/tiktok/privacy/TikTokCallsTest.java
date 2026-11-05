package app.morphe.extension.tiktok.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.tiktok.privacy.CleanupRunner.End;
import app.morphe.extension.tiktok.privacy.CleanupRunner.Failure;
import app.morphe.extension.tiktok.privacy.FakeTikTok.UpvoteApi;

import org.junit.Test;

/**
 * How Account cleanup (#332) talks to TikTok's own request services: a method found by the path
 * its annotation names, arguments put in by their request names, a call executed or an Rx source
 * waited on, and every kind of thrown or answered refusal read as the right way for a run to end.
 */
public class TikTokCallsTest {
    private static final String LIST = "tiktok/v1/upvote/item/list";
    private static final String DELETE = "tiktok/v1/upvote/delete";

    static final class CaptchaRequired extends RuntimeException {
    }

    static final class NeedVerifyException extends RuntimeException {
    }

    static final class ApiServerException extends Exception {
        private final int code;

        ApiServerException(int code) {
            this.code = code;
        }

        public int getErrorCode() {
            return code;
        }
    }

    static final class HttpException extends Exception {
        private final int status;

        HttpException(int status) {
            this.status = status;
        }

        public int getStatusCode() {
            return status;
        }
    }

    private final List<String> log = new ArrayList<>();

    private UpvoteApi echo() {
        return FakeTikTok.service(UpvoteApi.class, log, (method, arguments) -> arguments);
    }

    @Test public void aMethodIsFoundByItsPathAndThePlainestOneWins() {
        UpvoteApi api = echo();
        assertEquals("getRepostVideoList", TikTokCalls.endpoint(api, LIST, null).getName());
        assertEquals("a leading slash on either side doesn't matter",
                "getRepostVideoList", TikTokCalls.endpoint(api, "/" + LIST, null).getName());
        assertEquals("getRepostVideoListForCreator",
                TikTokCalls.endpoint(api, LIST, "getRepostVideoListForCreator").getName());
        assertEquals("a renamed method is still found by its path",
                "deleteUpvote", TikTokCalls.endpoint(api, DELETE, "renamedInThisBuild").getName());
        assertNull(TikTokCalls.endpoint(api, "tiktok/v1/upvote/gone", null));
        assertNull(TikTokCalls.endpoint(null, LIST, null));
        assertEquals(LIST, TikTokCalls.pathOf(TikTokCalls.endpoint(api, LIST, null)));
    }

    @Test public void argumentsGoInByTheirRequestNames() throws Exception {
        Map<String, Object> query = new HashMap<>();
        query.put("user_id", "42");
        query.put("offset", 20);
        query.put("count", 20);
        query.put("scene", 0);
        query.put("not_a_parameter", "ignored");
        Map<?, ?> sent = (Map<?, ?>) TikTokCalls.call(echo(), LIST, "getRepostVideoList", query);
        assertEquals("42", sent.get("user_id"));
        assertEquals("an int offset goes in as the long the method takes", 20L, sent.get("offset"));
        assertEquals(20, sent.get("count"));
        assertEquals(List.of("getRepostVideoList {user_id=42, offset=20, count=20, scene=0}"), log);
    }

    @Test public void aParameterNotGivenGetsWhatTikTokPassesWhenItHasNothing() throws Exception {
        Map<?, ?> sent = (Map<?, ?>) TikTokCalls.call(echo(), LIST, null, Map.of("user_id", 7, "count", "x"));
        assertEquals("7", sent.get("user_id"));
        assertEquals(0L, sent.get("offset"));
        assertEquals("a value of the wrong type isn't forced in", 0, sent.get("count"));

        Map<String, Object> form = new HashMap<>();
        form.put("item_id", "v1");
        form.put("carrier_type", 0);
        sent = (Map<?, ?>) TikTokCalls.call(echo(), DELETE, "deleteUpvote", form);
        assertEquals(Map.of("item_id", "v1", "carrier_type", 0), sent);
    }

    @Test public void coercionKeepsTypesAndDefaults() {
        assertEquals(5L, TikTokCalls.coerce(5, long.class));
        assertEquals(5, TikTokCalls.coerce(5L, Integer.class));
        assertNull(TikTokCalls.coerce(null, Integer.class));
        assertEquals(Boolean.FALSE, TikTokCalls.coerce(null, boolean.class));
        assertEquals(Boolean.TRUE, TikTokCalls.coerce(true, Boolean.class));
        assertEquals("5", TikTokCalls.coerce(5, String.class));
        assertEquals(0d, TikTokCalls.coerce("x", double.class));
        assertNull(TikTokCalls.coerce("x", List.class));
        List<String> list = List.of("a");
        assertSame(list, TikTokCalls.coerce(list, List.class));
    }

    @Test public void aPathThisBuildDoesNotHaveIsUnsupported() {
        Failure failure = assertThrows(Failure.class,
                () -> TikTokCalls.call(echo(), "tiktok/v1/upvote/gone", null, Map.of()));
        assertEquals(End.UNSUPPORTED, failure.end());
        assertEquals(List.of(), log);
    }

    @Test public void whatTheServiceThrowsDecidesHowTheRunEnds() {
        UpvoteApi captcha = FakeTikTok.service(UpvoteApi.class, log, (method, arguments) -> {
            throw new CaptchaRequired();
        });
        assertEquals(End.REFUSED, assertThrows(Failure.class,
                () -> TikTokCalls.call(captcha, DELETE, null, Map.of())).end());
        UpvoteApi offline = FakeTikTok.service(UpvoteApi.class, log, (method, arguments) -> {
            throw new IOException("unreachable");
        });
        assertEquals(End.NETWORK, assertThrows(Failure.class,
                () -> TikTokCalls.call(offline, DELETE, null, Map.of())).end());
    }

    @Test public void aCallIsExecutedAndItsBodyTaken() throws Exception {
        FakeTikTok.Status body = new FakeTikTok.Status(0);
        assertSame(body, TikTokCalls.await(FakeTikTok.call(body)));
    }

    /** A response whose body getter is renamed: the body is the field held as a plain Object. */
    static final class BareResponse {
        private final int code = 200;
        private final String message = "OK";
        private final Object raw;

        BareResponse(Object raw) {
            this.raw = raw;
        }

        @Override public String toString() {
            return code + message;
        }
    }

    public static final class BareCall {
        private final Object response;

        BareCall(Object response) {
            this.response = response;
        }

        public Object execute() {
            return response;
        }
    }

    public static final class ThrowingCall {
        private final Exception thrown;

        ThrowingCall(Exception thrown) {
            this.thrown = thrown;
        }

        public Object execute() throws Exception {
            throw thrown;
        }
    }

    @Test public void aResponseWithoutABodyGetterGivesUpItsObjectField() throws Exception {
        Object body = new FakeTikTok.Status(0);
        assertSame(body, TikTokCalls.await(new BareCall(new BareResponse(body))));
        assertEquals(End.NETWORK, assertThrows(Failure.class,
                () -> TikTokCalls.await(new BareCall(new BareResponse(null)))).end());
        assertEquals(End.NETWORK, assertThrows(Failure.class,
                () -> TikTokCalls.await(new BareCall(null))).end());
    }

    @Test public void aCallThatThrowsIsReadLikeAnyOtherFailure() {
        assertEquals(End.NETWORK, assertThrows(Failure.class,
                () -> TikTokCalls.await(new ThrowingCall(new IOException("reset")))).end());
        assertEquals(End.REFUSED, assertThrows(Failure.class,
                () -> TikTokCalls.await(new ThrowingCall(new HttpException(429)))).end());
    }

    @Test public void anRxAnswerIsSubscribedToAndWaitedOn() throws Exception {
        Object body = new FakeTikTok.Status(0);
        assertSame(body, TikTokCalls.await(FakeTikTok.single(body)));
        assertSame("an answer from another thread is waited for",
                body, TikTokCalls.await(new FakeTikTok.Single(body, null, 50)));
        assertEquals(End.REFUSED, assertThrows(Failure.class,
                () -> TikTokCalls.await(new FakeTikTok.Single(null, new CaptchaRequired(), 0))).end());
        assertEquals(End.NETWORK, assertThrows(Failure.class,
                () -> TikTokCalls.await(new FakeTikTok.Single(null, new IOException("reset"), 20))).end());
        Failure empty = assertThrows(Failure.class, () -> TikTokCalls.await(new FakeTikTok.Single(null, null, 0)));
        assertEquals(End.NETWORK, empty.end());
        assertEquals("an empty answer", empty.getMessage());
    }

    @Test public void anAnswerOfNoKnownShapeIsUnsupported() {
        assertEquals(End.UNSUPPORTED, assertThrows(Failure.class, () -> TikTokCalls.await(null)).end());
        assertEquals(End.UNSUPPORTED, assertThrows(Failure.class, () -> TikTokCalls.await("text")).end());
        assertNull(TikTokCalls.subscribeMethod(String.class));
        assertEquals("subscribe", TikTokCalls.subscribeMethod(FakeTikTok.Single.class).getName());
    }

    @Test public void thrownRefusalsAreToldFromNetworkErrors() {
        assertEquals(End.REFUSED, TikTokCalls.failureOf(new CaptchaRequired()).end());
        assertEquals(End.REFUSED, TikTokCalls.failureOf(new NeedVerifyException()).end());
        assertEquals("error code 2483", TikTokCalls.failureOf(new ApiServerException(2483)).getMessage());
        assertEquals(End.REFUSED, TikTokCalls.failureOf(new ApiServerException(2483)).end());
        assertEquals("code zero is no refusal", End.NETWORK, TikTokCalls.failureOf(new ApiServerException(0)).end());
        for (int status : new int[]{401, 403, 429}) {
            assertEquals("HTTP " + status, End.REFUSED, TikTokCalls.failureOf(new HttpException(status)).end());
        }
        assertEquals(End.NETWORK, TikTokCalls.failureOf(new HttpException(500)).end());
        assertEquals(End.NETWORK, TikTokCalls.failureOf(new IOException("timeout")).end());
    }

    @Test public void aRefusalUnderWrappersIsStillARefusal() {
        assertEquals(End.REFUSED, TikTokCalls.failureOf(
                new RuntimeException("wrapped", new IllegalStateException(new CaptchaRequired()))).end());
        assertEquals(End.REFUSED, TikTokCalls.failureOf(
                new InvocationTargetException(new HttpException(429))).end());
        Failure own = new Failure(End.STOPPED, "the account changed");
        assertSame(own, TikTokCalls.failureOf(new RuntimeException(own)));
    }

    @Test public void aMissingMemberIsABuildThisDoesNotFit() {
        assertEquals(End.UNSUPPORTED, TikTokCalls.failureOf(new ClassNotFoundException("X")).end());
        assertEquals(End.UNSUPPORTED, TikTokCalls.failureOf(new NoSuchMethodError("x")).end());
        assertEquals(End.UNSUPPORTED, TikTokCalls.failureOf(new ClassCastException("x")).end());
    }

    public static final class StatusGetter {
        public Integer getStatusCode() {
            return 8;
        }
    }

    public static final class CamelStatus {
        public Long statusCode;

        CamelStatus(long code) {
            statusCode = code;
        }
    }

    @Test public void aNonZeroStatusCodeInTheBodyIsARefusal() throws Exception {
        TikTokCalls.checkStatus(new FakeTikTok.Status(0));
        TikTokCalls.checkStatus(new CamelStatus(0));
        TikTokCalls.checkStatus(new Object());
        Failure refused = assertThrows(Failure.class, () -> TikTokCalls.checkStatus(new FakeTikTok.Status(2149)));
        assertEquals(End.REFUSED, refused.end());
        assertEquals("status code 2149", refused.getMessage());
        assertEquals(End.REFUSED, assertThrows(Failure.class,
                () -> TikTokCalls.checkStatus(new StatusGetter())).end());
        assertEquals(End.REFUSED, assertThrows(Failure.class,
                () -> TikTokCalls.checkStatus(new CamelStatus(5))).end());
    }

    @Test public void listsFlagsAndCursorsAreReadFromTheBody() throws Exception {
        FakeTikTok.VideoList body = new FakeTikTok.VideoList();
        assertEquals("a list left out is an empty page", List.of(), TikTokCalls.listIn(body, "items"));
        body.items = new ArrayList<>(List.of(new FakeTikTok.Video("a"), new FakeTikTok.Entry("b"), "junk"));
        body.maxCursor = 1_700_000_000L;
        body.hasMore = 1;
        assertEquals(3, TikTokCalls.listIn(body, "items").size());
        assertEquals(List.of("a", "b"), AccountCleanupSources.ids(TikTokCalls.listIn(body, "items")));
        assertEquals(1_700_000_000L, TikTokCalls.longIn(body, "maxCursor", 0));
        assertEquals(9, TikTokCalls.longIn(body, "max_cursor", 9));
        assertTrue(TikTokCalls.flagIn(body, "hasMore"));
        assertFalse(TikTokCalls.flagIn(body, "has_more"));
        FakeTikTok.RepostList reposts = new FakeTikTok.RepostList();
        reposts.hasMore = true;
        assertTrue(TikTokCalls.flagIn(reposts, "hasMore"));
    }

    @Test public void aBodyWithoutTheListIsUnsupportedNotEmpty() {
        assertEquals(End.UNSUPPORTED, assertThrows(Failure.class,
                () -> TikTokCalls.listIn(new FakeTikTok.Status(0), "items")).end());
        assertEquals(End.UNSUPPORTED, assertThrows(Failure.class,
                () -> TikTokCalls.listIn(null, "items")).end());
    }

    @Test public void aVideoIdIsReadOffTheVideoOrTheEntryHoldingIt() {
        assertEquals("a", TikTokCalls.idOf(new FakeTikTok.Video("a")));
        assertEquals("b", TikTokCalls.idOf(new FakeTikTok.Entry("b")));
        assertNull(TikTokCalls.idOf(new FakeTikTok.Video(" ")));
        assertNull(TikTokCalls.idOf(null));
    }

    public static final class SelfHolding {
        static final SelfHolding INSTANCE = new SelfHolding();
    }

    public static final class NoInstanceYet {
        static NoInstanceYet later;
    }

    @Test public void anApiSingletonIsTheInstanceItKeepsOfItself() throws Exception {
        assertSame(SelfHolding.INSTANCE, TikTokCalls.singleton(SelfHolding.class.getName()));
        assertEquals(End.UNSUPPORTED, assertThrows(Failure.class,
                () -> TikTokCalls.singleton(NoInstanceYet.class.getName())).end());
        assertEquals(End.UNSUPPORTED, assertThrows(Failure.class,
                () -> TikTokCalls.singleton("com.ss.android.ugc.aweme.upvote.api.Gone")).end());
    }
}

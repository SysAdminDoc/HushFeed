/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.seen.LocalWatchHistory;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.bytedance.retrofit2.mime.TypedByteArray;
import com.bytedance.retrofit2.mime.TypedInput;
import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Keep likes on this phone: a like is answered where TikTok would send it, with the response
 * TikTok's server gives a like it took, and the video reads liked from then on, on this phone and
 * for this account only. Everything else goes out.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LocalLikesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final long NOW = 1_800_000_000_000L;
    private static final String VIDEO = "7570000000000000001";
    private static final String OTHER = "7570000000000000002";
    private static final String HOST = "https://api16-normal-useast5.tiktokv.us";
    private boolean installed;

    /** Stands in for TikTok's Request: a path without the query, the whole URL and a body. */
    public static final class Request {
        private final String url;
        private final Object body;

        Request(String url, Object body) {
            this.url = url;
            this.body = body;
        }

        public String getPath() {
            int query = url.indexOf('?');
            String noQuery = query < 0 ? url : url.substring(0, query);
            return noQuery.substring(noQuery.indexOf('/', "https://".length()));
        }

        public String getUrl() {
            return url;
        }

        public Object getBody() {
            return body;
        }
    }

    /** Stands in for TikTok's form body, by the name the request reader looks for. */
    public static final class FormUrlEncodedTypedOutput {
        private final String encoded;

        FormUrlEncodedTypedOutput(String encoded) {
            this.encoded = encoded;
        }

        public void writeTo(OutputStream out) throws IOException {
            out.write(encoded.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Stands in for TikTok's raw response, with the constructor its send's type has. */
    public static final class Raw {
        final String url;
        final int code;
        final String reason;
        final List<?> headers;
        final TypedInput body;

        public Raw(String url, int code, String reason, List<?> headers, TypedInput body) {
            this.url = url;
            this.code = code;
            this.reason = reason;
            this.headers = headers;
            this.body = body;
        }
    }

    /** Stands in for CallServerInterceptor: the request and its own send, by its real name. */
    public static final class Interceptor {
        final Request mOriginalRequest;
        final List<Object> sentWith = new ArrayList<>();

        Interceptor(Request request) {
            mOriginalRequest = request;
        }

        @SuppressWarnings("unused")
        private Raw com_bytedance_retrofit2_CallServerInterceptor__executeCall$___twin___(Object chain) {
            sentWith.add(chain);
            return new Raw(mOriginalRequest.getUrl(), 200, "OK", new ArrayList<>(),
                    new TypedByteArray("application/json", "{}".getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** An interceptor whose send this build no longer has, so there's no response type to build. */
    public static final class Moved {
        final Request mOriginalRequest;

        Moved(Request request) {
            mOriginalRequest = request;
        }
    }

    /** A video, with the author and caption TikTok's model carries. */
    public static class Video extends Aweme {
        private final String aid;
        private final Author author;
        private final String desc;

        Video(String aid) {
            this(aid, null, null);
        }

        Video(String aid, String nickname, String desc) {
            this.aid = aid;
            this.author = nickname == null ? null : new Author(nickname);
            this.desc = desc;
        }

        @Override public String getAid() {
            return aid;
        }

        public Author getAuthor() {
            return author;
        }

        public String getDesc() {
            return desc;
        }
    }

    public static final class Author {
        private final String nickname;

        Author(String nickname) {
            this.nickname = nickname;
        }

        public String getNickname() {
            return nickname;
        }
    }

    @Before public void setUp() {
        installed = SettingsStatus.ghostModeEnabled;
        SettingsStatus.ghostModeEnabled = true;
        Settings.GHOST_MODE.save(true);
        Settings.GHOST_LOCAL_LIKES.save(true);
        SignedInUser.idForTests = "111";
        LocalLikes.clearForTests();
        LocalLikes.nowForTests = NOW;
        HookStatus.clear();
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        SettingsStatus.ghostModeEnabled = installed;
        Settings.GHOST_MODE.save(false);
        Settings.GHOST_LOCAL_LIKES.save(false);
        LocalLikes.clearForTests();
        LocalLikes.nowForTests = null;
        SignedInUser.idForTests = null;
        HookStatus.clear();
    }

    @Test public void theSwitchStartsOff() {
        assertEquals(Boolean.FALSE, Settings.GHOST_LOCAL_LIKES.defaultValue);
    }

    @Test public void aLikeIsAnsweredOnThePhoneAndStaysLikedAfterARestart() {
        Interceptor like = like(VIDEO, 1);
        Object answer = LocalLikes.answer(like, "chain");

        assertTrue("the like gets TikTok's own response type: " + answer, answer instanceof Raw);
        Raw raw = (Raw) answer;
        assertEquals(200, raw.code);
        assertEquals("OK", raw.reason);
        assertEquals(like.mOriginalRequest.getUrl(), raw.url);
        assertTrue(raw.headers.isEmpty());
        assertEquals("application/json; charset=utf-8", raw.body.mimeType());
        assertArrayEquals("{\"status_code\":0,\"status_msg\":\"\"}".getBytes(StandardCharsets.UTF_8),
                ((TypedByteArray) raw.body).bytes);
        assertTrue("nothing was sent", like.sentWith.isEmpty());

        assertEquals(1, LocalLikes.userDigg(new Video(VIDEO), 0));
        assertEquals(0, LocalLikes.userDigg(new Video(OTHER), 0));
        restart();
        assertEquals("a restart keeps it", 1, LocalLikes.userDigg(new Video(VIDEO), 0));
        assertEquals("TikTok's own likes read as they are", 1, LocalLikes.userDigg(new Video(OTHER), 1));
    }

    @Test public void withTheSwitchOffALikeGoesToTikTok() {
        Settings.GHOST_LOCAL_LIKES.save(false);
        assertNull(LocalLikes.answer(like(VIDEO, 1), "chain"));
        Settings.GHOST_LOCAL_LIKES.save(true);
        assertEquals(0, LocalLikes.userDigg(new Video(VIDEO), 0));
    }

    @Test public void withGhostModeOffOrHushfeedPausedALikeGoesToTikTok() {
        Settings.GHOST_MODE.save(false);
        assertNull(LocalLikes.answer(like(VIDEO, 1), "chain"));
        Settings.GHOST_MODE.save(true);
        PausedProcess.set(true);
        assertNull(LocalLikes.answer(like(VIDEO, 1), "chain"));
        PausedProcess.set(false);
        assertEquals(0, LocalLikes.userDigg(new Video(VIDEO), 0));
    }

    @Test public void takingAKeptLikeBackUnlikesItOnThePhone() {
        LocalLikes.answer(like(VIDEO, 1), "chain");
        LocalLikes.setUserDigg(new Video(VIDEO), 0);
        assertEquals("the heart stays empty while the unlike waits", 0, LocalLikes.userDigg(new Video(VIDEO), 0));

        Interceptor unlike = like(VIDEO, 0);
        assertTrue(LocalLikes.answer(unlike, "chain") instanceof Raw);
        assertTrue("nothing was sent", unlike.sentWith.isEmpty());
        restart();
        assertEquals(0, LocalLikes.userDigg(new Video(VIDEO), 0));
    }

    @Test public void anUnlikeTikTokNeverSawFirstIsAnsweredToo() {
        LocalLikes.answer(like(VIDEO, 1), "chain");
        assertTrue(LocalLikes.answer(like(VIDEO, 0), "chain") instanceof Raw);
        assertEquals(0, LocalLikes.userDigg(new Video(VIDEO), 0));
    }

    @Test public void anUnlikeOfALikeTikTokHasGoesOut() {
        assertNull(LocalLikes.answer(like(OTHER, 0), "chain"));
    }

    @Test public void aLikeTikTokGetsTakesTheVideoOffThePhone() {
        LocalLikes.answer(like(VIDEO, 1), "chain");
        Settings.GHOST_LOCAL_LIKES.save(false);
        restart();
        assertNull("off, the like goes out", LocalLikes.answer(like(VIDEO, 1), "chain"));
        Settings.GHOST_LOCAL_LIKES.save(true);
        assertEquals("TikTok has that like now", 0, LocalLikes.userDigg(new Video(VIDEO), 0));
        assertNull("so its unlike goes out too", LocalLikes.answer(like(VIDEO, 0), "chain"));
    }

    @Test public void anotherAccountDoesntGetTheKeptLike() {
        LocalLikes.answer(like(VIDEO, 1), "chain");
        SignedInUser.idForTests = "222";
        assertEquals(0, LocalLikes.userDigg(new Video(VIDEO), 0));
        assertTrue(LocalLikes.entries().isEmpty());
        assertNull("its unlike is its own", LocalLikes.answer(like(VIDEO, 0), "chain"));
        SignedInUser.idForTests = "111";
        assertEquals(1, LocalLikes.userDigg(new Video(VIDEO), 0));
    }

    @Test public void aLikeInTheFormBodyIsReadToo() {
        Interceptor like = new Interceptor(new Request(HOST + LocalLikes.LIKE_PATH + "?aid=1233",
                new FormUrlEncodedTypedOutput("aweme_id=" + VIDEO + "&type=1&channel_id=0")));
        assertTrue(LocalLikes.answer(like, "chain") instanceof Raw);
        assertEquals(1, LocalLikes.userDigg(new Video(VIDEO), 0));
    }

    @Test public void everyOtherRequestGoesOut() {
        assertNull(LocalLikes.answer(new Interceptor(new Request(HOST + "/aweme/v1/feed/?count=6", null)), "chain"));
        assertNull(LocalLikes.answer(new Interceptor(new Request(
                HOST + "/aweme/v1/comment/digg/?cid=1&aweme_id=" + VIDEO + "&digg_type=1", null)), "chain"));
        assertNull("a like with no type", LocalLikes.answer(new Interceptor(new Request(
                HOST + LocalLikes.LIKE_PATH + "?aweme_id=" + VIDEO, null)), "chain"));
        SignedInUser.idForTests = "";
        assertNull("signed out", LocalLikes.answer(like(VIDEO, 1), "chain"));
    }

    @Test public void aLikeWithNoResponseToBuildGoesOutAndIsntKept() {
        Moved moved = new Moved(new Request(HOST + LocalLikes.LIKE_PATH + "?aweme_id=" + VIDEO + "&type=1", null));
        assertNull(LocalLikes.answer(moved, "chain"));
        assertEquals(0, LocalLikes.userDigg(new Video(VIDEO), 0));
    }

    @Test public void theListHasTheKeptLikesNewestFirstWithCreatorAndCaption() {
        LocalLikes.setUserDigg(new Video(VIDEO, "Ana", "first   one\nhere"), 1);
        LocalLikes.answer(like(VIDEO, 1), "chain");
        LocalLikes.nowForTests = NOW + 60_000;
        LocalLikes.answer(like(OTHER, 1), "chain");
        restart();

        List<LocalWatchHistory.Entry> rows = LocalLikes.entries();
        assertEquals(2, rows.size());
        assertEquals(OTHER, rows.get(0).aid);
        assertEquals("", rows.get(0).creator);
        assertEquals(VIDEO, rows.get(1).aid);
        assertEquals("Ana", rows.get(1).creator);
        assertEquals("first one here", rows.get(1).caption);
        assertEquals(NOW, rows.get(1).watchedMs);
    }

    @Test public void clearTakesTheLikesBackAndUndoPutsThemBack() {
        LocalLikes.answer(like(VIDEO, 1), "chain");
        LocalLikes.nowForTests = NOW + 60_000;
        LocalLikes.answer(like(OTHER, 1), "chain");

        List<List<LocalWatchHistory.Entry>> handed = new ArrayList<>();
        LocalLikes.clear(handed::add);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, handed.size());
        assertEquals(2, handed.get(0).size());
        assertEquals(0, LocalLikes.userDigg(new Video(VIDEO), 0));
        assertTrue(LocalLikes.entries().isEmpty());

        LocalLikes.restore(handed.get(0));
        restart();
        assertEquals(1, LocalLikes.userDigg(new Video(VIDEO), 0));
        List<LocalWatchHistory.Entry> rows = LocalLikes.entries();
        assertEquals(OTHER, rows.get(0).aid);
        assertEquals(VIDEO, rows.get(1).aid);
    }

    /** A like (1) or unlike (0) the way VideoDiggApi sends it, the fields in the query. */
    private static Interceptor like(String aid, int type) {
        return new Interceptor(new Request(
                HOST + LocalLikes.LIKE_PATH + "?aweme_id=" + aid + "&type=" + type + "&channel_id=0&aid=1233", null));
    }

    /** A new process: the likes come back from the saved file. */
    private static void restart() {
        LocalLikes.forgetLoadedForTests();
    }
}

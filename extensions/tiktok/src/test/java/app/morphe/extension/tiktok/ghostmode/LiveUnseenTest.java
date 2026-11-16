/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import android.os.SystemClock;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.bytedance.android.livesdkapi.depend.model.live.Room;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * Watch LIVEs unseen (#420). A room enter is what puts you on a LIVE host's viewer list, and with
 * Ghost mode and the switch on the hook in front of TikTok's network send fails it on the phone,
 * as a connection TikTok's own code already handles. The eye in the room has the page enter the
 * room again through TikTok's own enter (or sends the held enter when it can't), and that room's
 * enters go out from then on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LiveUnseenTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private boolean installed;

    /** Stands in for TikTok's Request: a path without the query, and the whole URL. */
    public static final class Request {
        private final String url;

        Request(String url) {
            this.url = url;
        }

        public String getPath() {
            int query = url.indexOf('?');
            String noQuery = query < 0 ? url : url.substring(0, query);
            return noQuery.substring(noQuery.indexOf('/', "https://".length()));
        }

        public String getUrl() {
            return url;
        }
    }

    /** Stands in for CallServerInterceptor: the request it sends and its own send, by its real name. */
    public static final class Interceptor {
        final Request mOriginalRequest;
        final List<Object> sentWith = new ArrayList<>();
        boolean refuse;

        Interceptor(String url) {
            mOriginalRequest = new Request(url);
        }

        @SuppressWarnings("unused")
        private Object com_bytedance_retrofit2_CallServerInterceptor__executeCall$___twin___(Object chain)
                throws IOException {
            if (refuse) throw new IOException("Canceled");
            sentWith.add(chain);
            return "response";
        }
    }

    private static final String ENTER = "https://webcast16-normal-useast5.tiktokv.us/webcast/room/enter/?room_id=7571234567890123456&aid=1233";
    private static final String ROOM = "7571234567890123456";

    @Before public void setUp() {
        installed = SettingsStatus.ghostModeEnabled;
        SettingsStatus.ghostModeEnabled = true;
        Settings.GHOST_MODE.save(false);
        Settings.GHOST_HIDE_LIVE_VIEWS.save(false);
        LiveUnseen.resetForTests();
        HookStatus.clear();
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        SettingsStatus.ghostModeEnabled = installed;
        Settings.GHOST_MODE.save(false);
        Settings.GHOST_HIDE_LIVE_VIEWS.save(false);
        LiveUnseen.resetForTests();
        HookStatus.clear();
    }

    private static void on() {
        Settings.GHOST_MODE.save(true);
        Settings.GHOST_HIDE_LIVE_VIEWS.save(true);
    }

    @Test public void theSwitchStartsOffAndAnEnterGoesOutWithGhostModeAlone() throws IOException {
        assertFalse(Settings.GHOST_HIDE_LIVE_VIEWS.defaultValue);
        Settings.GHOST_MODE.save(true);
        LiveUnseen.holdEnter(new Interceptor(ENTER), "chain");
        assertEquals(0, LiveUnseen.heldForTests());
    }

    @Test public void withBothOnARoomEnterFailsOnThePhoneAndIsKeptForLater() {
        on();
        Interceptor interceptor = new Interceptor(ENTER);
        IOException held = assertThrows(IOException.class, () -> LiveUnseen.holdEnter(interceptor, "chain"));
        assertEquals(LiveUnseen.HELD_MESSAGE, held.getMessage());
        assertEquals(1, LiveUnseen.heldForTests());
        assertTrue(interceptor.sentWith.isEmpty());
        assertEquals("a held enter counts as a blocked call", GhostMode.Status.BLOCKED, GhostMode.status());
    }

    @Test public void everyFormOfTheEnterIsHeldAndNothingElseIs() throws IOException {
        on();
        for (String path : new String[] {
                "/webcast/room/enter/", "/webcast/room/enter/pre_cache/", "/webcast/room/enter/v2/"}) {
            Interceptor enter = new Interceptor("https://webcast.tiktokv.com" + path + "?room_id=" + path.length());
            assertThrows(path, IOException.class, () -> LiveUnseen.holdEnter(enter, "chain"));
        }
        for (String path : new String[] {
                "/webcast/room/info/", "/webcast/feed/", "/aweme/v1/feed/", "/webcast/room/leave/"}) {
            LiveUnseen.holdEnter(new Interceptor("https://webcast.tiktokv.com" + path + "?room_id=1"), "chain");
        }
        assertEquals(3, LiveUnseen.heldForTests());
    }

    @Test public void pausedOrNotInstalledLetsTheEnterGo() throws IOException {
        on();
        PausedProcess.set(true);
        LiveUnseen.holdEnter(new Interceptor(ENTER), "chain");
        PausedProcess.set(false);
        SettingsStatus.ghostModeEnabled = false;
        LiveUnseen.holdEnter(new Interceptor(ENTER), "chain");
        assertEquals(0, LiveUnseen.heldForTests());
    }

    @Test public void aRoomIsHiddenFromWhileBothSwitchesAreOnAndUntilTheEye() throws Exception {
        assertFalse("off by default", LiveUnseen.hiding(ROOM));
        on();
        assertTrue(LiveUnseen.hiding(ROOM));
        assertFalse(LiveUnseen.hiding(null));

        reveal(ROOM);

        assertFalse(LiveUnseen.hiding(ROOM));
        assertTrue("another room is still hidden from", LiveUnseen.hiding("7400000000000000999"));
    }

    @Test public void anUnreadableRequestGoesOutAsItAlwaysDid() throws IOException {
        on();
        LiveUnseen.holdEnter(new Object(), "chain");
        LiveUnseen.holdEnter(null, "chain");
        assertEquals(0, LiveUnseen.heldForTests());
    }

    @Test public void theEyeSendsTheHeldEnterThroughTikToksOwnSendAndTheRoomGoesOutAfter() throws Exception {
        on();
        Interceptor interceptor = new Interceptor(ENTER);
        Object chain = new Object();
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(interceptor, chain));

        assertEquals(LiveUnseen.Reveal.SENT, reveal(ROOM));
        assertEquals(1, interceptor.sentWith.size());
        assertSame(chain, interceptor.sentWith.get(0));
        assertTrue(LiveUnseen.revealed(ROOM));
        assertEquals(0, LiveUnseen.heldForTests());

        // The room's next enter, a swipe back to it, goes out; another room's is still held.
        LiveUnseen.holdEnter(new Interceptor(ENTER), "chain");
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(
                new Interceptor(ENTER.replace(ROOM, "7570000000000000001")), "chain"));
    }

    @Test public void theEyeEntersTheRoomAgainOnItsPageSoTheChatAndHostBarComeIn() throws Exception {
        on();
        Interceptor interceptor = new Interceptor(ENTER);
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(interceptor, "chain"));
        Page page = new Page(ROOM);
        List<Object> entered = new ArrayList<>();
        LiveUnseen.reentry = (onPage, room) -> {
            assertSame("on the main thread", Looper.getMainLooper(), Looper.myLooper());
            entered.add(onPage);
            entered.add(room);
            return true;
        };

        assertEquals(LiveUnseen.Reveal.SENT, reveal(page, ROOM));
        assertEquals(Arrays.asList(page, Long.parseLong(ROOM)), entered);
        assertTrue("the held enter isn't sent as well", interceptor.sentWith.isEmpty());
        assertEquals(0, LiveUnseen.heldForTests());
        assertTrue(LiveUnseen.revealed(ROOM));
        // The page's own enter that follows goes out.
        LiveUnseen.holdEnter(new Interceptor(ENTER), "chain");
    }

    @Test public void aPageThatCantEnterAgainSendsTheHeldEnterInstead() throws Exception {
        LiveUnseen.Reentry[] bridges = {
                LiveUnseen::enterAgain,
                (page, room) -> false,
                (page, room) -> {
                    throw new IllegalStateException("moved");
                },
        };
        for (LiveUnseen.Reentry bridge : bridges) {
            LiveUnseen.resetForTests();
            on();
            Interceptor interceptor = new Interceptor(ENTER);
            Object chain = new Object();
            assertThrows(IOException.class, () -> LiveUnseen.holdEnter(interceptor, chain));
            LiveUnseen.reentry = bridge;
            assertEquals(LiveUnseen.Reveal.SENT, reveal(new Page(ROOM), ROOM));
            assertEquals(1, interceptor.sentWith.size());
            assertSame(chain, interceptor.sentWith.get(0));
        }
    }

    @Test public void aRoomWithNothingHeldIsNotEnteredAgain() throws Exception {
        on();
        List<Long> entered = new ArrayList<>();
        LiveUnseen.reentry = (page, room) -> entered.add(room);
        assertEquals(LiveUnseen.Reveal.NEXT_TIME, reveal(new Page(ROOM), ROOM));
        assertTrue("a room already entered isn't entered twice", entered.isEmpty());
        assertTrue(LiveUnseen.revealed(ROOM));
    }

    @Test public void aRoomWithNothingHeldIsSeenNextTime() throws Exception {
        on();
        assertEquals(LiveUnseen.Reveal.NEXT_TIME, reveal(ROOM));
        LiveUnseen.holdEnter(new Interceptor(ENTER), "chain");
        assertEquals(LiveUnseen.Reveal.NEXT_TIME, reveal(null));
    }

    @Test public void aSendThatFailsSaysSoAndTheNextEnterGoesOut() throws Exception {
        on();
        Interceptor interceptor = new Interceptor(ENTER);
        interceptor.refuse = true;
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(interceptor, "chain"));
        assertEquals(LiveUnseen.Reveal.FAILED, reveal(ROOM));
        LiveUnseen.holdEnter(new Interceptor(ENTER), "chain");
        assertEquals(LiveUnseen.Reveal.FAILED, LiveUnseen.send(new LiveUnseen.HeldEnter(new Object(), "chain")));
    }

    @Test public void onlyTheLatestEnterOfTheNewestRoomsIsKept() {
        on();
        for (int i = 0; i < LiveUnseen.HELD_LIMIT + 5; i++) {
            Interceptor enter = new Interceptor(ENTER.replace(ROOM, String.valueOf(1000 + i)));
            assertThrows(IOException.class, () -> LiveUnseen.holdEnter(enter, "chain"));
            assertThrows(IOException.class, () -> LiveUnseen.holdEnter(enter, "again"));
        }
        assertEquals(LiveUnseen.HELD_LIMIT, LiveUnseen.heldForTests());
    }

    @Test public void theEyeSaysWhatHappened() {
        assertEquals("The host can see you now", toldFor(LiveUnseen.Reveal.SENT));
        assertEquals("The host sees you next time you open it", toldFor(LiveUnseen.Reveal.NEXT_TIME));
        assertEquals("Couldn't reach TikTok. Open this LIVE again.", toldFor(LiveUnseen.Reveal.FAILED));
    }

    private static String toldFor(LiveUnseen.Reveal outcome) {
        ShadowToast.reset();
        LiveUnseenButton.tell(outcome);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return String.valueOf(ShadowToast.getTextOfLatestToast());
    }

    /** Taps the eye for {@code room} with no page known and waits for the outcome. */
    private static LiveUnseen.Reveal reveal(String room) throws InterruptedException {
        return reveal(null, room);
    }

    /** Taps the eye on {@code page} for {@code room} and waits for the outcome the main thread gets. */
    private static LiveUnseen.Reveal reveal(Object page, String room) throws InterruptedException {
        AtomicReference<LiveUnseen.Reveal> outcome = new AtomicReference<>();
        LiveUnseen.reveal(page, room, outcome::set);
        for (int i = 0; i < 200 && outcome.get() == null; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (outcome.get() == null) Thread.sleep(10);
        }
        assertFalse("the outcome reached the main thread", outcome.get() == null);
        return outcome.get();
    }

    /** Stands in for TikTok's reasons a LIVE room page leaves, by their real names. */
    enum Leave { ENTER_FAILED, USER_CLOSE_CLICK, PLAYER_ERROR }

    /** Stands in for TikTok's LIVE room page: the room it shows. */
    public static final class Page {
        final Room room = new Room();

        Page(String id) {
            room.idStr = id;
        }
    }

    @Test public void aRoomWhoseEnterWasHeldStaysOpenInsteadOfMovingOnToTheNextLive() {
        on();
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
        assertTrue(LiveUnseen.keepRoom(new Page(ROOM), Leave.ENTER_FAILED));

        // Every other leave goes ahead, and so does a failed enter of a room nothing was held for.
        assertFalse(LiveUnseen.keepRoom(new Page(ROOM), Leave.USER_CLOSE_CLICK));
        assertFalse(LiveUnseen.keepRoom(new Page(ROOM), Leave.PLAYER_ERROR));
        assertFalse(LiveUnseen.keepRoom(new Page("7570000000000000001"), Leave.ENTER_FAILED));
        assertFalse(LiveUnseen.keepRoom(new Page(ROOM), "ENTER_FAILED"));
        assertFalse(LiveUnseen.keepRoom(new Page(ROOM), null));
    }

    @Test public void aFailedEnterLeavesAsAlwaysWithTheSwitchOffOrAfterTheEye() throws Exception {
        on();
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
        Settings.GHOST_HIDE_LIVE_VIEWS.save(false);
        assertFalse(LiveUnseen.keepRoom(new Page(ROOM), Leave.ENTER_FAILED));

        on();
        assertEquals(LiveUnseen.Reveal.SENT, reveal(ROOM));
        assertFalse("a real failed enter after the eye still moves on",
                LiveUnseen.keepRoom(new Page(ROOM), Leave.ENTER_FAILED));
    }

    @Test public void anEnterHeldWithoutItsRoomKeepsAFailedRoomForAWhile() {
        on();
        assertFalse(LiveUnseen.keepRoom(new Page(ROOM), Leave.ENTER_FAILED));
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(
                new Interceptor("https://webcast.tiktokv.com/webcast/room/enter/?aid=1233"), "chain"));
        long heldAt = SystemClock.elapsedRealtime();
        assertTrue(LiveUnseen.keepRoom(new Page(ROOM), Leave.ENTER_FAILED));
        assertTrue(LiveUnseen.heldFor(ROOM, heldAt + LiveUnseen.UNKNOWN_ROOM_WINDOW_MS));
        assertFalse(LiveUnseen.heldFor(ROOM, heldAt + LiveUnseen.UNKNOWN_ROOM_WINDOW_MS + 1));
    }

    @Test public void aPageWithNoReadableRoomCountsARecentHold() {
        on();
        assertFalse(LiveUnseen.keepRoom(new Object(), Leave.ENTER_FAILED));
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
        assertTrue(LiveUnseen.keepRoom(new Object(), Leave.ENTER_FAILED));
        assertTrue(LiveUnseen.keepRoom(null, Leave.ENTER_FAILED));
    }

    @Test public void noRoomReadsAsNoRoom() {
        assertNull(app.morphe.extension.tiktok.follow.FollowDiagnostics.liveRoomIdOf(null));
        assertEquals(ROOM, app.morphe.extension.tiktok.follow.FollowDiagnostics.liveRoomIdOf(new Request(ENTER)));
    }
}

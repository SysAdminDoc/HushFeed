/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.ghostmode.LiveUnseenTest.Interceptor;
import app.morphe.extension.tiktok.ghostmode.LiveUnseenTest.Page;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * The chat of a LIVE watched unseen (#513). A hidden room's page starts its own chat, host bar
 * and close button over TikTok's LIVE preview message channel, the one the feed's LIVE previews
 * read without entering. Nothing that would show the host you're there leaves the phone while
 * the room is hidden: the room's own message channel never enters it, and the requests a viewer
 * sends from the room (likes, comments, gifts, shares, votes, the chat's own fetch) fail on the
 * phone the way the room enter does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LiveUnseenChatTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private boolean installed;

    private static final String ROOM = "7571234567890123456";
    private static final String OTHER_ROOM = "7570000000000000001";
    private static final String HOST = "https://webcast16-normal-useast5.tiktokv.us";
    private static final String ENTER = HOST + "/webcast/room/enter/?room_id=" + ROOM + "&aid=1233";

    /** TikTok's message channel kinds, by their real names. */
    enum Channel { LIVE_ROOM, PREVIEW }

    /** A channel kind with no preview to fall back to. */
    enum RoomOnly { LIVE_ROOM }

    /** Stands in for TikTok's ImEnterRoom, the websocket frame that enters a room's chat. */
    public static final class ImEnterRoom {
        public long roomId;

        ImEnterRoom(String room) {
            roomId = Long.parseLong(room);
        }
    }

    /** Stands in for TikTok's reasons a room page leaves, by their real names. */
    enum Leave { ENTER_FAILED, USER_CLOSE_CLICK }

    @Before public void setUp() {
        installed = SettingsStatus.ghostModeEnabled;
        SettingsStatus.ghostModeEnabled = true;
        Settings.GHOST_MODE.save(false);
        Settings.GHOST_HIDE_LIVE_VIEWS.save(false);
        LiveUnseen.resetForTests();
        HookStatus.clear();
    }

    @After public void tearDown() {
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

    private static long id(String room) {
        return Long.parseLong(room);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void aHiddenRoomReadsItsChatOverTikToksPreviewChannel() {
        on();
        assertSame(Channel.PREVIEW, LiveUnseen.messageMode(id(ROOM), 0, Channel.LIVE_ROOM));
        assertSame("already a preview", Channel.PREVIEW, LiveUnseen.messageMode(id(ROOM), 0, Channel.PREVIEW));
        assertSame("your own LIVE keeps its channel", Channel.LIVE_ROOM,
                LiveUnseen.messageMode(id(ROOM), 1, Channel.LIVE_ROOM));
        assertSame("no room to judge", Channel.LIVE_ROOM, LiveUnseen.messageMode(0, 0, Channel.LIVE_ROOM));
    }

    @Test public void aRoomTheHostSeesYouInKeepsItsOwnChannel() throws Exception {
        assertSame("switch off", Channel.LIVE_ROOM, LiveUnseen.messageMode(id(ROOM), 0, Channel.LIVE_ROOM));
        on();
        LiveUnseen.reveal(null, ROOM, outcome -> { });
        idle();
        assertSame(Channel.LIVE_ROOM, LiveUnseen.messageMode(id(ROOM), 0, Channel.LIVE_ROOM));
        assertSame(Channel.PREVIEW, LiveUnseen.messageMode(id(OTHER_ROOM), 0, Channel.LIVE_ROOM));
    }

    @Test public void aChannelKindItCantReadIsLeftAlone() {
        on();
        assertSame(RoomOnly.LIVE_ROOM, LiveUnseen.messageMode(id(ROOM), 0, RoomOnly.LIVE_ROOM));
        assertEquals("LIVE_ROOM", LiveUnseen.messageMode(id(ROOM), 0, "LIVE_ROOM"));
        assertNull(LiveUnseen.messageMode(id(ROOM), 0, null));
    }

    @Test public void aChannelLeftOverFromAnotherRoomIsNotCarriedIntoAHiddenOne() throws Exception {
        Object channel = new Object();
        assertSame("switch off", channel, LiveUnseen.reuseFor(channel, new Page(ROOM)));
        on();
        assertNull("a hidden room makes its own", LiveUnseen.reuseFor(channel, new Page(ROOM)));
        assertNull(LiveUnseen.reuseFor(null, new Page(ROOM)));
        assertSame("no room to judge", channel, LiveUnseen.reuseFor(channel, new Object()));
        assertSame(channel, LiveUnseen.reuseFor(channel, null));
        LiveUnseen.reveal(null, ROOM, outcome -> { });
        idle();
        assertSame(channel, LiveUnseen.reuseFor(channel, new Page(ROOM)));
    }

    @Test public void theRoomsOwnChannelNeverEntersAHiddenRoom() throws Exception {
        byte[] frame = {1, 2, 3};
        assertArrayEquals("switch off", frame, LiveUnseen.imEnter(new ImEnterRoom(ROOM), frame));
        on();
        assertNull(LiveUnseen.imEnter(new ImEnterRoom(ROOM), frame));
        assertNull(LiveUnseen.imEnter(new ImEnterRoom(ROOM), null));
        LiveUnseen.reveal(null, ROOM, outcome -> { });
        idle();
        assertArrayEquals(frame, LiveUnseen.imEnter(new ImEnterRoom(ROOM), frame));
        assertNull(LiveUnseen.imEnter(new ImEnterRoom(OTHER_ROOM), frame));
        assertTrue(HookStatus.missing("ghost mode").isEmpty());
    }

    @Test public void aFrameWithNoReadableRoomIsHeldWhileEveryRoomIsHidden() throws Exception {
        on();
        byte[] frame = {1};
        assertNull(LiveUnseen.imEnter(new Object(), frame));
        LiveUnseen.reveal(null, ROOM, outcome -> { });
        idle();
        assertArrayEquals("a room you let see you may be the one", frame, LiveUnseen.imEnter(new Object(), frame));
    }

    @Test public void whileHiddenWhatTheRoomWouldShowTheHostFailsOnThePhone() throws IOException {
        on();
        String[] revealing = {
                "/webcast/im/fetch/", "/webcast/room/like/", "/webcast/room/chat/", "/webcast/room/chat/event/",
                "/webcast/room/emote_chat/", "/webcast/room/share/", "/webcast/gift/send/",
                "/webcast/room/poll/vote", "/webcast/room/event_report/",
        };
        for (String path : revealing) {
            Interceptor request = new Interceptor(HOST + path + "?room_id=" + ROOM + "&aid=1233");
            IOException held = assertThrows(path, IOException.class, () -> LiveUnseen.holdEnter(request, "chain"));
            assertEquals(LiveUnseen.HELD_MESSAGE, held.getMessage());
            assertTrue(path, request.sentWith.isEmpty());
        }
        assertEquals("nothing is kept to send later", 0, LiveUnseen.heldForTests());
        // What a hidden room reads goes out: its preview chat, its history, the like icons.
        for (String path : new String[] {
                "/webcast/im/fetch/preview/", "/webcast/im/fetch/history/", "/webcast/room/like/icon/",
                "/webcast/ranklist/online_audience/", "/webcast/room/info/"}) {
            LiveUnseen.holdEnter(new Interceptor(HOST + path + "?room_id=" + ROOM), "chain");
        }
    }

    @Test public void theRoomsRequestsGoOutWithTheSwitchOffOrOnceTheHostCanSeeYou() throws Exception {
        String like = HOST + "/webcast/room/like/?room_id=" + ROOM;
        Settings.GHOST_MODE.save(true);
        LiveUnseen.holdEnter(new Interceptor(like), "chain");
        on();
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
        LiveUnseen.reentry = (page, room) -> true;
        LiveUnseen.reveal(new Page(ROOM), ROOM, outcome -> { });
        idle();
        LiveUnseen.holdEnter(new Interceptor(like), "chain");
        LiveUnseen.holdEnter(new Interceptor(HOST + "/webcast/im/fetch/?room_id=" + ROOM), "chain");
        assertThrows("another room is still hidden", IOException.class, () -> LiveUnseen.holdEnter(
                new Interceptor(like.replace(ROOM, OTHER_ROOM)), "chain"));
    }

    @Test public void aKeptRoomStartsItsChatAndHostBarThroughItsPage() {
        on();
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
        Page page = new Page(ROOM);
        List<Object> started = new ArrayList<>();
        LiveUnseen.interaction = (onPage, room) -> {
            assertSame("on the main thread", Looper.getMainLooper(), Looper.myLooper());
            started.add(onPage);
            started.add(room);
            return true;
        };

        assertTrue(LiveUnseen.keepRoom(page, Leave.ENTER_FAILED));
        assertTrue("not from inside the page's leave", started.isEmpty());
        idle();
        assertEquals(Arrays.asList(page, page.room), started);
        assertTrue(HookStatus.missing("ghost mode").toString(), HookStatus.missing("ghost mode").isEmpty());
    }

    @Test public void aRequestWithNoReadableRoomIsHeldWhileAHiddenRoomShowsItsChat() throws Exception {
        on();
        String chat = HOST + "/webcast/room/chat/?aid=1233";
        LiveUnseen.holdEnter(new Interceptor(chat), "chain");

        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
        LiveUnseen.interaction = (page, room) -> true;
        assertTrue(LiveUnseen.keepRoom(new Page(ROOM), Leave.ENTER_FAILED));
        idle();
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(chat), "chain"));

        // Leaving the room ends it.
        assertFalse(LiveUnseen.keepRoom(new Page(ROOM), Leave.USER_CLOSE_CLICK));
        LiveUnseen.holdEnter(new Interceptor(chat), "chain");
    }

    @Test public void theEyeOpeningTheRoomEndsItsHiddenChat() throws Exception {
        on();
        String chat = HOST + "/webcast/room/chat/?aid=1233";
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
        LiveUnseen.interaction = (page, room) -> true;
        assertTrue(LiveUnseen.keepRoom(new Page(ROOM), Leave.ENTER_FAILED));
        idle();
        LiveUnseen.reentry = (page, room) -> true;
        LiveUnseen.reveal(new Page(ROOM), ROOM, outcome -> { });
        idle();
        LiveUnseen.holdEnter(new Interceptor(chat), "chain");
    }

    @Test public void aPageThatCantStartLeavesTheRoomAsItWas() throws Exception {
        LiveUnseen.Interaction[] bridges = {
                LiveUnseen::startInteraction,
                (page, room) -> false,
                (page, room) -> {
                    throw new IllegalStateException("moved");
                },
        };
        String chat = HOST + "/webcast/room/chat/?aid=1233";
        for (LiveUnseen.Interaction bridge : bridges) {
            LiveUnseen.resetForTests();
            on();
            assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
            LiveUnseen.interaction = bridge;
            assertTrue("the room stays either way", LiveUnseen.keepRoom(new Page(ROOM), Leave.ENTER_FAILED));
            idle();
            LiveUnseen.holdEnter(new Interceptor(chat), "chain");
        }
        assertTrue(HookStatus.missing("ghost mode").toString(),
                HookStatus.missing("ghost mode").toString().contains(LiveUnseen.ROOM_SHOWN));
    }

    @Test public void aRoomTheEyeOpenedBeforeItsStartIsNotStartedAgain() throws Exception {
        on();
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
        List<Object> started = new ArrayList<>();
        LiveUnseen.interaction = (page, room) -> started.add(room);
        assertTrue(LiveUnseen.keepRoom(new Page(ROOM), Leave.ENTER_FAILED));
        LiveUnseen.reentry = (page, room) -> true;
        LiveUnseen.reveal(new Page(ROOM), ROOM, outcome -> { });
        idle();
        assertTrue("its own enter builds it now", started.isEmpty());

        Settings.GHOST_HIDE_LIVE_VIEWS.save(false);
        assertFalse(LiveUnseen.keepRoom(new Page(OTHER_ROOM), Leave.ENTER_FAILED));
        idle();
        assertTrue(started.isEmpty());
    }
}

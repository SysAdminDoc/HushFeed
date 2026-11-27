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
    enum Leave { ENTER_FAILED, USER_CLOSE_CLICK, PLAYER_ERROR, JUMP_TO_OTHER }

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

    @Test public void leavingARoomTheEyeOpenedHidesYouFromItAgainNextTime() throws Exception {
        on();
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
        assertEquals(LiveUnseen.Reveal.SENT, reveal(ROOM));
        LiveUnseen.holdEnter(new Interceptor(ENTER), "chain");

        assertFalse(LiveUnseen.keepRoom(new Page(ROOM), Leave.USER_CLOSE_CLICK));
        assertFalse("the visit is over", LiveUnseen.revealed(ROOM));
        assertTrue(LiveUnseen.hiding(ROOM));
        assertThrows("opening it again is hidden (#491)", IOException.class,
                () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
    }

    @Test public void aRealFailedEnterLeavingTheRoomEndsTheVisitToo() throws Exception {
        on();
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
        assertEquals(LiveUnseen.Reveal.SENT, reveal(ROOM));
        assertFalse(LiveUnseen.keepRoom(new Page(ROOM), Leave.ENTER_FAILED));
        assertFalse(LiveUnseen.revealed(ROOM));
    }

    @Test public void aRevealWaitingForTheNextOpenOutlastsTheLeaveBeforeIt() throws Exception {
        on();
        assertEquals(LiveUnseen.Reveal.NEXT_TIME, reveal(ROOM));
        assertFalse(LiveUnseen.keepRoom(new Page(ROOM), Leave.USER_CLOSE_CLICK));
        assertTrue("seen next time, as the eye said", LiveUnseen.revealed(ROOM));
        LiveUnseen.holdEnter(new Interceptor(ENTER), "chain");

        assertFalse(LiveUnseen.keepRoom(new Page(ROOM), Leave.JUMP_TO_OTHER));
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
    }

    @Test public void leavingAnotherRoomOrAnUnreadablePageChangesNothing() throws Exception {
        on();
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
        assertEquals(LiveUnseen.Reveal.SENT, reveal(ROOM));
        assertFalse(LiveUnseen.keepRoom(new Page("7570000000000000001"), Leave.USER_CLOSE_CLICK));
        assertFalse(LiveUnseen.keepRoom(new Object(), Leave.USER_CLOSE_CLICK));
        assertFalse(LiveUnseen.keepRoom(null, Leave.USER_CLOSE_CLICK));
        assertFalse("not a reason TikTok gives", LiveUnseen.keepRoom(new Page(ROOM), "USER_CLOSE_CLICK"));
        assertTrue(LiveUnseen.revealed(ROOM));
    }

    @Test public void theEyeTappedAgainHidesTheNextVisit() throws Exception {
        on();
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
        assertEquals(LiveUnseen.Reveal.SENT, reveal(ROOM));
        LiveUnseen.hideAgain(ROOM);
        assertTrue(LiveUnseen.hiding(ROOM));
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
        LiveUnseen.hideAgain(null);
    }

    /** D#359: in the long-press menu, the eye's row says what a tap on it will do in that room. */
    @Test public void theEyesMenuRowSaysWhatATapWillDo() throws Exception {
        on();
        assertEquals("Let the host see you", LiveUnseenButton.menuLabel(ROOM));
        reveal(ROOM);
        assertEquals("Hide from the host next time", LiveUnseenButton.menuLabel(ROOM));
        LiveUnseen.hideAgain(ROOM);
        assertEquals("Let the host see you", LiveUnseenButton.menuLabel(ROOM));
    }

    /** A LIVE room's page as the eye reads it: its activity, its room, and resumed. */
    public static final class RoomPage {
        final Room room = new Room();
        private final android.app.Activity activity;

        RoomPage(android.app.Activity activity, String id) {
            this.activity = activity;
            room.idStr = id;
        }

        public android.app.Activity getActivity() {
            return activity;
        }

        public boolean isResumed() {
            return true;
        }
    }

    /**
     * D#359: with LIVE buttons in the long-press menu on, the eye leaves the video for that menu,
     * but only once TikTok has entered the room. A room whose enter is held opens with none of
     * TikTok's widgets, and holding its middle opens nothing, so there the eye stays on the video.
     * It's the only way out of the hold. Record and Auto tap stay with it.
     */
    @Test public void theEyeStaysOnAHeldRoomUntilTikTokEntersIt() throws Exception {
        on();
        Settings.LIVE_BUTTONS_IN_MENU.save(true);
        android.app.Activity activity = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        android.view.ViewGroup content = activity.findViewById(android.R.id.content);
        try {
            int before = content.getChildCount();
            assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
            LiveUnseenButton.noteRoomFragment(new RoomPage(activity, ROOM));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(LiveUnseenButton.roomUnbuilt());
            assertFalse(LiveUnseenButton.inMenu());
            assertFalse("Record and Auto tap stay on the video too",
                    app.morphe.extension.tiktok.live.LiveRecordChip.buttonsInMenu());
            assertEquals("held, the eye is on the video", before + 1, content.getChildCount());

            // Sent as it was, the held enter builds nothing, so the room still has no menu.
            assertEquals(LiveUnseen.Reveal.SENT, reveal(ROOM));
            LiveUnseenButton.refresh(activity);
            assertFalse(LiveUnseenButton.inMenu());
            assertEquals(before + 1, content.getChildCount());

            // TikTok's own enter for the room goes out, and the room gets its widgets.
            LiveUnseen.holdEnter(new Interceptor(ENTER), "chain");
            assertFalse(LiveUnseenButton.roomUnbuilt());
            assertTrue(LiveUnseenButton.inMenu());
            LiveUnseenButton.refresh(activity);
            assertEquals("the eye is in the menu instead", before, content.getChildCount());

            Settings.LIVE_BUTTONS_IN_MENU.save(false);
            assertFalse(LiveUnseenButton.inMenu());
            LiveUnseenButton.refresh(activity);
            assertEquals("the switch off puts it back", before + 1, content.getChildCount());

            // Leaving ends the visit, and the next one is held again.
            assertFalse(LiveUnseen.keepRoom(new Page(ROOM), Leave.USER_CLOSE_CLICK));
            assertTrue(LiveUnseen.unbuilt(ROOM));
            Settings.GHOST_HIDE_LIVE_VIEWS.save(false);
            assertFalse("no hold without Watch LIVEs unseen", LiveUnseen.unbuilt(ROOM));
            Settings.LIVE_BUTTONS_IN_MENU.save(true);
            assertFalse("no eye without Watch LIVEs unseen", LiveUnseenButton.inMenu());
        } finally {
            Settings.LIVE_BUTTONS_IN_MENU.resetToDefault();
            LiveUnseenButton.resetForTests();
            activity.finish();
        }
    }

    @Test public void aRoomEnteredWithTheSwitchOffStaysBuiltWhenTheSwitchGoesOnInsideIt() throws Exception {
        Settings.GHOST_MODE.save(true);
        // Watch LIVEs unseen off: TikTok's own enter goes out and builds the room's menu.
        LiveUnseen.holdEnter(new Interceptor(ENTER), "chain");
        assertEquals(0, LiveUnseen.heldForTests());
        Settings.GHOST_HIDE_LIVE_VIEWS.save(true);
        assertFalse("turned on inside the room, it still has its menu", LiveUnseen.unbuilt(ROOM));

        // Leaving ends the visit, and the next one is held as usual.
        assertFalse(LiveUnseen.keepRoom(new Page(ROOM), Leave.USER_CLOSE_CLICK));
        assertTrue(LiveUnseen.unbuilt(ROOM));
        assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
        assertTrue(LiveUnseen.unbuilt(ROOM));
    }

    /** Sends a tap at ({@code x}, {@code y}) through the window's callback, as a finger's would arrive. */
    private static void tapWindow(android.app.Activity activity, float x, float y, long at) {
        android.view.Window.Callback callback = activity.getWindow().getCallback();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(at, at, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(at, at + 50, android.view.MotionEvent.ACTION_UP, x, y, 0);
        callback.dispatchTouchEvent(down);
        callback.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    /** Idles the main thread until a reveal has reported back, which it does with a toast. */
    private static void waitForRevealToast() throws InterruptedException {
        for (int i = 0; i < 200 && ShadowToast.getTextOfLatestToast() == null; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (ShadowToast.getTextOfLatestToast() == null) Thread.sleep(10);
        }
    }

    /**
     * D#359: with `Double tap to let the host see you` on, no eye covers the LIVE, and a double tap
     * in the room lets its host see you, as the eye's tap did. Once they can, a double tap is
     * TikTok's own like again. A double tap on one of Hushfeed's buttons is a tap on that button,
     * and with the switch off the eye is back.
     */
    @Test public void aDoubleTapLetsTheHostSeeYouWithNoEyeOnTheVideo() throws Exception {
        on();
        Settings.GHOST_LIVE_DOUBLE_TAP.save(true);
        android.app.Activity activity = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        android.view.ViewGroup content = activity.findViewById(android.R.id.content);
        RoomPage page = new RoomPage(activity, ROOM);
        try {
            int before = content.getChildCount();
            assertThrows(IOException.class, () -> LiveUnseen.holdEnter(new Interceptor(ENTER), "chain"));
            LiveUnseenButton.noteRoomFragment(page);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the room is held", LiveUnseenButton.roomUnbuilt());
            assertEquals("no eye on the video", before, content.getChildCount());

            // A tap on Hushfeed's own button in the room, twice, is that button's.
            android.view.View record = new android.view.View(activity);
            app.morphe.extension.tiktok.live.LiveRecordChip.markStacked(record, 1, 0, 0);
            content.addView(record, new android.widget.FrameLayout.LayoutParams(200, 200, android.view.Gravity.TOP | android.view.Gravity.START));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.graphics.Rect area = new android.graphics.Rect();
            assertTrue("the stand-in button is on screen", record.getGlobalVisibleRect(area));
            long at = SystemClock.uptimeMillis();
            tapWindow(activity, area.centerX(), area.centerY(), at);
            tapWindow(activity, area.centerX(), area.centerY(), at + 150);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse("a double tap on a button lets no one see you", LiveUnseen.revealed(ROOM));
            content.removeView(record);

            // One tap is a tap.
            float x = content.getWidth() / 2f;
            float y = content.getHeight() / 2f;
            tapWindow(activity, x, y, at + 2000);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse("one tap lets no one see you", LiveUnseen.revealed(ROOM));

            // Two are the eye's tap.
            ShadowToast.reset();
            tapWindow(activity, x, y, at + 2150);
            waitForRevealToast();
            assertTrue("a double tap lets the host see you", LiveUnseen.revealed(ROOM));
            assertEquals("The host can see you now", ShadowToast.getTextOfLatestToast());

            // Now it's TikTok's double tap, a like, and the host still sees you.
            ShadowToast.reset();
            tapWindow(activity, x, y, at + 4000);
            tapWindow(activity, x, y, at + 4150);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(LiveUnseen.revealed(ROOM));
            assertNull("nothing to say about a like", ShadowToast.getTextOfLatestToast());

            Settings.GHOST_LIVE_DOUBLE_TAP.save(false);
            LiveUnseenButton.refresh(activity);
            assertEquals("the switch off puts the eye back", before + 1, content.getChildCount());
        } finally {
            Settings.GHOST_LIVE_DOUBLE_TAP.resetToDefault();
            LiveUnseen.hideAgain(ROOM);
            LiveUnseenButton.resetForTests();
            activity.finish();
        }
    }

    @Test public void noRoomReadsAsNoRoom() {
        assertNull(app.morphe.extension.tiktok.follow.FollowDiagnostics.liveRoomIdOf(null));
        assertEquals(ROOM, app.morphe.extension.tiktok.follow.FollowDiagnostics.liveRoomIdOf(new Request(ENTER)));
    }
}

package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.blockauthor.LiveRoomBlockTest;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.bytedance.android.livesdkapi.depend.model.live.Room;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;
import java.util.Locale;

/** The room ID and connection line in a LIVE room (#487). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveRoomInfoTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private boolean liveControls;
    private Locale locale;
    private ActivityController<LiveRoomBlockTest.RoomActivity> room;
    private Page front;
    private Page next;

    @Before public void setUp() {
        liveControls = SettingsStatus.liveControlsEnabled;
        SettingsStatus.liveControlsEnabled = true;
        Settings.LIVE_ROOM_INFO.save(true);
        locale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        LiveRoomInfo.resetForTests();
        room = Robolectric.buildActivity(LiveRoomBlockTest.RoomActivity.class).setup().visible().windowFocusChanged(true);
        Utils.setContext(room.get());
        LiveRoomBlockTest.Pages pages = new LiveRoomBlockTest.Pages(room.get());
        room.get().setContentView(pages);
        idle();
        int width = pages.getWidth() > 0 ? pages.getWidth() : 400;
        int height = pages.getHeight() > 0 ? pages.getHeight() : 800;
        front = new Page(room.get(), pages, "7301234567890123456");
        next = new Page(room.get(), pages, "7309876543210987654");
        front.view.layout(0, 0, width, height);
        // The pager keeps the next room one page down, resumed but off screen.
        next.view.layout(0, height, width, height * 2);
    }

    @After public void tearDown() {
        LiveRoomInfo.resetForTests();
        Settings.LIVE_ROOM_INFO.resetToDefault();
        Settings.LIVE_RUNNING_TIME.resetToDefault();
        SettingsStatus.liveControlsEnabled = liveControls;
        Locale.setDefault(locale);
        room.close();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Both rooms resume, the one being watched first, as TikTok's pager does. */
    private void openRoom() {
        LiveRoomInfo.noteRoomFragment(front);
        LiveRoomInfo.noteRoomFragment(next);
        idle();
    }

    private TextView line() {
        return lineStarting("Room ID ");
    }

    private TextView lineStarting(String start) {
        ViewGroup content = room.get().findViewById(android.R.id.content);
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).getText().toString().startsWith(start)) {
                return (TextView) child;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- how long the room has run (#494)

    @Test public void theRunningTimeCountsFromTheRoomsStart() {
        Settings.LIVE_ROOM_INFO.save(false);
        Settings.LIVE_RUNNING_TIME.save(true);
        front.room.createTime = System.currentTimeMillis() / 1000 - (3600 + 2 * 60 + 3);
        openRoom();
        TextView line = lineStarting("LIVE for ");
        assertNotNull("no running time", line);
        String text = line.getText().toString();
        assertTrue(text, text.equals("LIVE for 1:02:03") || text.equals("LIVE for 1:02:04"));
        assertEquals(View.VISIBLE, line.getVisibility());
        // The room ID stays off with its own switch off.
        assertFalse(text, text.contains("Room ID"));
    }

    @Test public void theRunningTimeGoesUnderTheRoomIdWithBothSwitchesOn() {
        Settings.LIVE_RUNNING_TIME.save(true);
        front.room.createTime = System.currentTimeMillis() / 1000 - 125;
        openRoom();
        String text = line().getText().toString();
        assertTrue(text, text.startsWith("Room ID 7301234567890123456\nLIVE for 2:0"));
    }

    @Test public void theRunningTimeTicksWithTheLine() {
        Settings.LIVE_ROOM_INFO.save(false);
        Settings.LIVE_RUNNING_TIME.save(true);
        front.room.createTime = System.currentTimeMillis() / 1000 - 10;
        openRoom();
        String before = lineStarting("LIVE for ").getText().toString();
        front.room.createTime -= 60;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        String after = lineStarting("LIVE for ").getText().toString();
        assertTrue(before + " then " + after, after.startsWith("LIVE for 1:1"));
    }

    @Test public void aRoomWithNoStartTimeYetShowsNoRunningTime() {
        // A room from the LIVE feed has no start time until TikTok's enter answer fills it in.
        Settings.LIVE_ROOM_INFO.save(false);
        Settings.LIVE_RUNNING_TIME.save(true);
        openRoom();
        assertNull(lineStarting("LIVE for "));
        assertNull(line());
        front.room.startTime = System.currentTimeMillis() / 1000 - 30;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        assertNotNull("the start time came in and no line followed", lineStarting("LIVE for 0:3"));
    }

    @Test public void theRunningTimeHasNoLineWithItsSwitchOff() {
        Settings.LIVE_ROOM_INFO.save(false);
        front.room.createTime = System.currentTimeMillis() / 1000 - 125;
        openRoom();
        assertNull(lineStarting("LIVE for "));
        Settings.LIVE_ROOM_INFO.save(true);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        assertEquals("Room ID 7301234567890123456", line().getText().toString());
    }

    @Test public void theRunningTimeReadsHoursMinutesAndSeconds() {
        assertEquals("0:00", LiveRoomInfo.runningTime(0));
        assertEquals("0:00", LiveRoomInfo.runningTime(-5_000));
        assertEquals("0:59", LiveRoomInfo.runningTime(59_999));
        assertEquals("12:05", LiveRoomInfo.runningTime((12 * 60 + 5) * 1000L));
        assertEquals("1:00:00", LiveRoomInfo.runningTime(3_600_000L));
        assertEquals("27:03:09", LiveRoomInfo.runningTime(((27 * 60 + 3) * 60 + 9) * 1000L));
    }

    @Test public void theStartIsReadInSecondsOrMilliseconds() {
        Room seconds = new Room();
        seconds.createTime = 1_763_800_000L;
        assertEquals(1_763_800_000_000L, LiveRoomInfo.startedAtMs(seconds));
        Room millis = new Room();
        millis.startTime = 1_763_800_000_123L;
        assertEquals(1_763_800_000_123L, LiveRoomInfo.startedAtMs(millis));
        assertEquals(0L, LiveRoomInfo.startedAtMs(new Room()));
        assertEquals(0L, LiveRoomInfo.startedAtMs(null));
        assertEquals(0L, LiveRoomInfo.startedAtMs(new Object()));
    }

    @Test public void theRunningTimeLineNeedsLiveControlsAndNoPause() {
        Settings.LIVE_ROOM_INFO.save(false);
        Settings.LIVE_RUNNING_TIME.save(true);
        SettingsStatus.liveControlsEnabled = false;
        front.room.createTime = System.currentTimeMillis() / 1000 - 125;
        openRoom();
        assertNull(lineStarting("LIVE for "));
    }

    @Test public void theLineShowsTheRoomInFrontWhileTheSwitchIsOn() {
        openRoom();
        TextView line = line();
        assertNotNull("no room line", line);
        // Unpatched there are no player stats, so only the room shows.
        assertEquals("Room ID 7301234567890123456", line.getText().toString());
        assertEquals(View.VISIBLE, line.getVisibility());
    }

    @Test public void theRoomHasNoLineWithTheSwitchOff() {
        Settings.LIVE_ROOM_INFO.save(false);
        openRoom();
        assertNull(line());
    }

    @Test public void theRoomHasNoLineWithoutLiveControls() {
        SettingsStatus.liveControlsEnabled = false;
        openRoom();
        assertNull(line());
    }

    @Test public void turningTheSwitchOffTakesTheLineAwayWithinASecond() {
        openRoom();
        assertNotNull(line());
        Settings.LIVE_ROOM_INFO.save(false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        assertNull(line());
    }

    @Test public void aSwipeToTheNextRoomShowsThatRoom() {
        openRoom();
        int height = front.view.getHeight();
        front.view.offsetTopAndBottom(-height);
        next.view.offsetTopAndBottom(-height);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        assertEquals("Room ID 7309876543210987654", line().getText().toString());
    }

    @Test public void aTapCopiesTheRoomId() {
        openRoom();
        line().performClick();
        idle();
        ClipboardManager clipboard = (ClipboardManager) room.get().getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("7301234567890123456", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals("Room ID copied", ShadowToast.getTextOfLatestToast());
    }

    @Test public void theConnectionComesFromTikToksOwnKeysAndNothingAboutTheHost() throws Exception {
        JSONObject stats = new JSONObject()
                .put("rtt:", 85)
                .put("delay:", 2340L)
                .put("download_Speed:", 2400L)
                .put("jitter_ms:", 12)
                .put("push_client_model:", "SM-S938U")
                .put("push_client_os_version:", "15")
                .put("push_client_platform:", "android");
        String text = LiveRoomInfo.text("7301234567890123456", stats);
        assertEquals("Room ID 7301234567890123456\nPing 85 ms · Latency 2.3 s · 2.4 Mb/s", text);
        assertFalse(text, text.contains("SM-S938U"));
        assertFalse(text, text.contains("android"));
    }

    @Test public void aNumberThePlayerDoesntHaveIsLeftOut() throws Exception {
        // A plain stream has no round trip of its own, which the player keeps as 0.
        JSONObject stats = new JSONObject().put("rtt:", 0).put("download_Speed:", 900L);
        assertEquals("0.9 Mb/s", LiveRoomInfo.connection(stats));
        assertEquals("", LiveRoomInfo.connection(new JSONObject().put("delay:", -1L)));
        assertEquals("", LiveRoomInfo.connection(null));
        assertEquals("Room ID 1", LiveRoomInfo.text("1", new JSONObject()));
    }

    @Test public void unpatchedThereAreNoStats() {
        assertNull(LiveRoomInfo.nativeStats());
        assertNull(LiveRoomInfo.stats());
        assertTrue(LiveRoomInfo.connection(LiveRoomInfo.stats()).isEmpty());
    }

    /** A LIVE room page: the parts the line reads from TikTok's room fragment. */
    public static final class Page {
        private final Activity activity;
        final FrameLayout view;
        public Room room;

        Page(Activity activity, ViewGroup pager, String id) {
            this.activity = activity;
            view = new FrameLayout(activity);
            pager.addView(view);
            room = new Room();
            room.idStr = id;
        }

        public Activity getActivity() { return activity; }
        public boolean isResumed() { return true; }
        public View getView() { return view; }
    }
}

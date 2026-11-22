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
        ViewGroup content = room.get().findViewById(android.R.id.content);
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).getText().toString().startsWith("Room ID ")) {
                return (TextView) child;
            }
        }
        return null;
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

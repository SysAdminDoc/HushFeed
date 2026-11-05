package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.download.LiveRecorder;
import app.morphe.extension.tiktok.live.LiveRecordChip;
import app.morphe.extension.tiktok.settings.Settings;
import com.bytedance.android.livesdkapi.depend.model.live.Room;
import java.time.Duration;
import java.util.List;
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
import org.robolectric.util.ReflectionHelpers;

/** The block button in an open LIVE room (#138): it blocks the host of the room in front and closes it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveRoomBlockTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private final BlockFeedAdvanceTest.FakeService service = new BlockFeedAdvanceTest.FakeService();
    private ActivityController<Activity> under;
    private ActivityController<RoomActivity> room;
    private Page front;
    private Page next;

    @Before public void setup() throws Exception {
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedService", service);
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedBlockMethod",
                BlockFeedAdvanceTest.FakeService.class.getMethod(
                        "block", String.class, String.class, int.class, int.class));
        ReflectionHelpers.setStaticField(BlockAuthorOverlay.class, "requestInFlight", false);
        Settings.BLOCK_AUTHOR_BUTTON.save(true);
        LiveRoomBlock.resetForTests();
        // The screen the room was opened from, which takes the Unblock chip once the room closes.
        under = Robolectric.buildActivity(Activity.class).setup().visible().windowFocusChanged(true);
        Utils.setContext(under.get());
        Utils.setActivity(under.get());
        room = Robolectric.buildActivity(RoomActivity.class).setup().visible().windowFocusChanged(true);
        Pages pages = new Pages(room.get());
        room.get().setContentView(pages);
        idle();
        int width = pages.getWidth() > 0 ? pages.getWidth() : 400;
        int height = pages.getHeight() > 0 ? pages.getHeight() : 800;
        front = new Page(room.get(), pages, "host1", "scammer");
        next = new Page(room.get(), pages, "host2", "other");
        front.view.layout(0, 0, width, height);
        // The pager keeps the next room one page down, resumed but off screen.
        next.view.layout(0, height, width, height * 2);
    }

    @After public void cleanup() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        idle();
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedService", null);
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedBlockMethod", null);
        ReflectionHelpers.setStaticField(BlockAuthorOverlay.class, "requestInFlight", false);
        java.lang.reflect.Method dismiss = BlockAuthorOverlay.class.getDeclaredMethod("dismissUndo");
        dismiss.setAccessible(true);
        dismiss.invoke(null);
        LiveRoomBlock.resetForTests();
        Settings.BLOCK_AUTHOR_BUTTON.save(false);
        Utils.setActivity(null);
        room.close();
        under.close();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Both rooms resume, the one being watched first, as TikTok's pager does. */
    private void openRoom() {
        LiveRoomBlock.noteRoomFragment(front);
        LiveRoomBlock.noteRoomFragment(next);
        idle();
    }

    private TextView blockButton() {
        ViewGroup content = room.get().findViewById(android.R.id.content);
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child instanceof TextView && "Block this creator".contentEquals(child.getContentDescription())) {
                return (TextView) child;
            }
        }
        return null;
    }

    private void tapBlock() throws Exception {
        TextView button = blockButton();
        assertNotNull(button);
        button.performClick();
        Utils.awaitBackgroundTasksForTests();
        idle();
    }

    @Test public void theRoomShowsTheBlockButtonWhileTheSwitchIsOn() {
        openRoom();
        TextView button = blockButton();
        assertNotNull(button);
        assertEquals(View.VISIBLE, button.getVisibility());
    }

    @Test public void theRoomHasNoBlockButtonWithTheSwitchOff() {
        Settings.BLOCK_AUTHOR_BUTTON.save(false);
        openRoom();
        assertNull(blockButton());
    }

    @Test public void aScreenRecordingOfTheRoomHidesTheButtonUntilItEnds() throws Exception {
        openRoom();
        java.lang.reflect.Constructor<?> make = null;
        for (java.lang.reflect.Constructor<?> constructor : LiveRecorder.class.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 5) make = constructor;
        }
        assertNotNull(make);
        make.setAccessible(true);
        LiveRecorder filming = (LiveRecorder) make.newInstance(
                new LiveRecorder.Target(null, LiveRecorder.Transport.SCREEN, "host1", "1", null),
                null, null, null, null);
        try {
            LiveRecordChip.holdForTests(filming, false);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
            assertEquals(View.INVISIBLE, blockButton().getVisibility());
        } finally {
            LiveRecordChip.holdForTests(null, false);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        assertEquals(View.VISIBLE, blockButton().getVisibility());
    }

    @Test public void thePictureInPictureWindowShowsNoButtonAndItComesBackAfter() {
        openRoom();
        assertNotNull(blockButton());
        room.get().pictureInPicture = true;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        assertNull(blockButton());
        room.get().pictureInPicture = false;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        assertNotNull(blockButton());
    }

    @Test public void turningTheSwitchOffTakesTheButtonAwayWithinASecond() {
        openRoom();
        Settings.BLOCK_AUTHOR_BUTTON.save(false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        assertNull(blockButton());
    }

    @Test public void aTapBlocksTheHostOfTheRoomInFrontClosesItAndOffersUnblock() throws Exception {
        openRoom();
        tapBlock();
        assertEquals(List.of("host1:1"), service.calls);
        assertTrue(room.get().isFinishing());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(LiveRoomBlock.UNDO_WAIT_MS));
        ViewGroup content = under.get().findViewById(android.R.id.content);
        TextView chip = (TextView) content.getChildAt(content.getChildCount() - 1);
        assertEquals("Unblock", chip.getText().toString());
    }

    @Test public void aRefusedBlockLeavesTheRoomOpen() throws Exception {
        service.status = 7;
        openRoom();
        tapBlock();
        assertEquals(List.of("host1:1"), service.calls);
        assertFalse(room.get().isFinishing());
        assertNotNull(blockButton());
    }

    @Test public void aRoomSwipedPastBlocksTheRoomNowInFront() throws Exception {
        openRoom();
        int height = front.view.getHeight();
        front.view.offsetTopAndBottom(-height);
        next.view.offsetTopAndBottom(-height);
        tapBlock();
        assertEquals(List.of("host2:1"), service.calls);
    }

    /** The room's activity, which can say it sits in Android's picture-in-picture window. */
    public static final class RoomActivity extends Activity {
        boolean pictureInPicture;
        @Override public boolean isInPictureInPictureMode() { return pictureInPicture; }
    }

    /** A pager that leaves its pages where they were put, as TikTok's does between scrolls. */
    public static final class Pages extends FrameLayout {
        public Pages(Context context) { super(context); }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) { }
    }

    /** A LIVE room page: the parts the block button reads from TikTok's room fragment. */
    public static final class Page {
        private final Activity activity;
        final FrameLayout view;
        public Room room;
        public boolean resumed = true;

        Page(Activity activity, ViewGroup pager, String uid, String handle) {
            this.activity = activity;
            view = new FrameLayout(activity);
            pager.addView(view);
            LiveFeedCellsTest.Owner owner = new LiveFeedCellsTest.Owner();
            owner.idStr = uid;
            owner.username = handle;
            room = new Room();
            room.owner = owner;
        }

        public Activity getActivity() { return activity; }
        public boolean isResumed() { return resumed; }
        public View getView() { return view; }
    }
}

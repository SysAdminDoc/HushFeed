/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;

/**
 * #413: TikTok's LIVE window had Record but no way to the next LIVE, while the feed's window has
 * arrows. With Keep watching in a small window on, previous and next sit either side of Record
 * (or of TikTok's own button), move TikTok's pager of rooms the way a swipe does, and dim at the
 * ends. A LIVE outside that pager gets none.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
public class LivePipArrowsTest {
    /** 47.9.3's pager of LIVE rooms, by the methods the window looks for. Its class name is renamed. */
    public static class RoomPager extends ViewGroup {
        int current;
        int count;
        final List<Integer> asked = new ArrayList<>();

        public RoomPager(Context context) {
            super(context);
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        }

        public int getCurrentItem() { return current; }
        public void setCurrentItem(int item) { asked.add(item); current = item; }
        public void setCurrentItemWithDefaultVelocity(int item) { setCurrentItem(item); }
        public void setIsCanChangePage(boolean can) { }
        public Pages getAdapter() { return new Pages(count); }
    }

    /** Stands in for the pager's adapter: only its count is read. */
    public static class Pages {
        private final int count;
        Pages(int count) { this.count = count; }
        public int getCount() { return count; }
    }

    /** A LIVE activity holding the pager, keeping the params it was handed as Android's window would. */
    public static class Room extends Activity {
        RoomPager pager;
        PictureInPictureParams last;
        int sent;

        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            FrameLayout root = new FrameLayout(this);
            pager = new RoomPager(this);
            pager.count = 3;
            pager.current = 1;
            root.addView(pager);
            setContentView(root);
        }

        @Override public void setPictureInPictureParams(PictureInPictureParams params) {
            last = params;
            sent++;
        }

        @Override public int getMaxNumPictureInPictureActions() { return 3; }
    }

    private Room room;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.liveControlsEnabled = true;
        SettingsStatus.pictureInPictureEnabled = true;
        Settings.RECORD_LIVE.save(true);
        Settings.PICTURE_IN_PICTURE.save(true);
        room = Robolectric.buildActivity(Room.class).setup().get();
        ReflectionHelpers.setStaticField(LiveRecordChip.class, "host", new WeakReference<>(room));
    }

    @After public void tearDown() {
        LiveRecordChip.holdForTests(null, false);
        ReflectionHelpers.setStaticField(LiveRecordChip.class, "host", new WeakReference<>(null));
        ReflectionHelpers.setStaticField(LivePipRecord.class, "theirs", Collections.emptyList());
        ReflectionHelpers.setStaticField(LivePipRecord.class, "theirsOn", new WeakReference<>(null));
        ReflectionHelpers.setStaticField(LivePipRecord.class, "shown", LivePipRecord.HIDDEN);
        ReflectionHelpers.setStaticField(LivePipRecord.class, "shownRooms", LivePipRecord.NO_ARROWS);
        ReflectionHelpers.setStaticField(LivePipRecord.class, "receiver", null);
        SettingsStatus.liveControlsEnabled = false;
        SettingsStatus.pictureInPictureEnabled = false;
        Settings.RECORD_LIVE.save(Settings.RECORD_LIVE.defaultValue);
        Settings.PICTURE_IN_PICTURE.save(Settings.PICTURE_IN_PICTURE.defaultValue);
    }

    @Test public void recordSitsBetweenTheArrowsInPlaceOfTikToksButton() {
        RemoteAction theirs = LivePipRecord.button(room, LivePipRecord.STOP);
        List<Object> actions = new ArrayList<>(Collections.singletonList(theirs));
        LivePipRecord.addTo(actions);
        assertEquals(List.of("Previous LIVE", "Record", "Next LIVE"), titles(actions));
        assertTrue(((RemoteAction) actions.get(0)).isEnabled());
        assertTrue(((RemoteAction) actions.get(2)).isEnabled());
    }

    @Test public void withoutRecordTikToksButtonStaysInTheMiddleAndTheFirstRoomDimsPrevious() {
        Settings.RECORD_LIVE.save(false);
        room.pager.current = 0;
        RemoteAction theirs = LivePipRecord.button(room, LivePipRecord.STOP);
        List<Object> actions = new ArrayList<>(Collections.singletonList(theirs));
        LivePipRecord.addTo(actions);
        assertEquals(3, actions.size());
        assertSame(theirs, actions.get(1));
        assertFalse("nothing above the first LIVE", ((RemoteAction) actions.get(0)).isEnabled());
        assertTrue(((RemoteAction) actions.get(2)).isEnabled());
    }

    @Test public void withTheSmallWindowSwitchOffOrNoPagerTheWindowKeepsItsOldButtons() {
        Settings.PICTURE_IN_PICTURE.save(false);
        RemoteAction theirs = LivePipRecord.button(room, LivePipRecord.STOP);
        List<Object> actions = new ArrayList<>(Collections.singletonList(theirs));
        LivePipRecord.addTo(actions);
        assertEquals(List.of("Stop", "Record"), titles(actions));

        Settings.PICTURE_IN_PICTURE.save(true);
        ((ViewGroup) room.pager.getParent()).removeView(room.pager);
        assertNull(LivePipRecord.pagerIn(room));
        assertEquals(LivePipRecord.NO_ARROWS, LivePipRecord.rooms(room));
    }

    @Test public void onlyTikToksLivePagerCountsAsOne() {
        assertTrue(LivePipRecord.isPager(RoomPager.class));
        assertFalse(LivePipRecord.isPager(FrameLayout.class));
        assertFalse(LivePipRecord.isPager(android.widget.ListView.class));
    }

    @Test public void nextMovesThePagerAndTheArrowsFollowTheNewRoom() {
        LivePipRecord.addTo(new ArrayList<>());
        LivePipRecord.onMove(1);
        assertEquals(List.of(2), room.pager.asked);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        List<RemoteAction> sent = ReflectionHelpers.callInstanceMethod(room.last, "getActions");
        assertEquals(List.of("Previous LIVE", "Record", "Next LIVE"), titlesOf(sent));
        assertTrue(sent.get(0).isEnabled());
        assertFalse("the last LIVE has none below it", sent.get(2).isEnabled());

        // Nothing below the last room: next does nothing.
        LivePipRecord.onMove(1);
        assertEquals(List.of(2), room.pager.asked);
        LivePipRecord.onMove(-1);
        assertEquals(List.of(2, 1), room.pager.asked);
    }

    @Test public void onlyTheArrowsThemselvesCanChangeTheRoom() {
        Intent next = Shadows.shadowOf(LivePipRecord.arrow(room, LivePipRecord.ACTION_NEXT, true)
                .getActionIntent()).getSavedIntent();
        Intent previous = Shadows.shadowOf(LivePipRecord.arrow(room, LivePipRecord.ACTION_PREVIOUS, true)
                .getActionIntent()).getSavedIntent();
        assertTrue(LivePipRecord.fromTheButton(next));
        assertTrue(LivePipRecord.fromTheButton(previous));
        assertEquals(LivePipRecord.ACTION_NEXT, next.getAction());
        assertFalse(LivePipRecord.fromTheButton(new Intent(LivePipRecord.ACTION_NEXT)));
        assertFalse(LivePipRecord.fromTheButton(new Intent(LivePipRecord.ACTION_NEXT)
                .putExtra(LivePipRecord.EXTRA_TOKEN, "guessed")));
    }

    private static List<String> titles(List<Object> actions) {
        List<String> titles = new ArrayList<>();
        for (Object action : actions) titles.add(((RemoteAction) action).getTitle().toString());
        return titles;
    }

    private static List<String> titlesOf(List<RemoteAction> actions) {
        return titles(new ArrayList<>(actions));
    }
}

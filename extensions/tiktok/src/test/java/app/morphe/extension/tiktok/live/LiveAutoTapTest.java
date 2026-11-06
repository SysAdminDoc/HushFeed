package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.bytedance.android.livesdkapi.depend.model.live.Room;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class LiveAutoTapTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Runs the loop's steps by hand and keeps a clock from the delays it asks for. */
    static final class FakeHost implements LiveAutoTap.Loop.Host {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final ArrayDeque<Long> due = new ArrayDeque<>();
        final List<Long> delays = new ArrayList<>();
        final List<Long> tapTimes = new ArrayList<>();
        long now;
        LiveAutoTap.End reason;
        boolean holding;
        boolean tapLands = true;
        int tapCalls;
        int stops;
        LiveAutoTap.End stoppedWhy;
        int stoppedTaps = -1;

        @Override public LiveAutoTap.End stopReason() { return reason; }
        @Override public boolean holding() { return holding; }
        @Override public boolean tap() {
            tapCalls++;
            if (tapLands) tapTimes.add(now);
            return tapLands;
        }
        @Override public void post(Runnable step, long delayMs) {
            delays.add(delayMs);
            queue.add(step);
            due.add(now + delayMs);
        }
        @Override public void stopped(LiveAutoTap.End why, int taps) {
            stops++;
            stoppedWhy = why;
            stoppedTaps = taps;
        }

        boolean runNext() {
            Runnable step = queue.poll();
            if (step == null) return false;
            now = due.poll();
            step.run();
            return true;
        }

        void run(int steps) {
            for (int i = 0; i < steps && runNext(); i++) { }
        }
    }

    @Test public void theRateIsHeldBetweenOneAndEight() {
        assertEquals(4, LiveAutoTap.rate("4"));
        assertEquals(2, LiveAutoTap.rate(" 2 "));
        assertEquals(8, LiveAutoTap.rate("12"));
        assertEquals(1, LiveAutoTap.rate("0"));
        assertEquals(1, LiveAutoTap.rate("-3"));
        assertEquals(4, LiveAutoTap.rate("fast"));
        assertEquals(4, LiveAutoTap.rate(null));
        assertEquals(8, new LiveAutoTap.Loop(new FakeHost(), 50, new Random(1)).rate());
        assertEquals(1, new LiveAutoTap.Loop(new FakeHost(), 0, new Random(1)).rate());
    }

    @Test public void theChoicesAreTheRatesTheyName() {
        assertEquals(String.valueOf(LiveAutoTap.DEFAULT_RATE), Settings.LIVE_AUTO_TAP_RATE.defaultValue);
        assertTrue(Arrays.asList(LiveAutoTap.RATE_VALUES).contains(Settings.LIVE_AUTO_TAP_RATE.defaultValue));
        assertEquals(LiveAutoTap.RATE_LABELS.length, LiveAutoTap.RATE_VALUES.length);
        for (int i = 0; i < LiveAutoTap.RATE_VALUES.length; i++) {
            int rate = LiveAutoTap.rate(LiveAutoTap.RATE_VALUES[i]);
            assertEquals(LiveAutoTap.RATE_VALUES[i], String.valueOf(rate));
            assertTrue(LiveAutoTap.RATE_LABELS[i].startsWith(rate + " taps"));
        }
        assertFalse(Settings.LIVE_AUTO_TAP.defaultValue);
    }

    @Test public void eightASecondNeverPutsNineTapsInOneSecond() {
        FakeHost host = new FakeHost();
        LiveAutoTap.Loop loop = new LiveAutoTap.Loop(host, 8, new Random(7));
        loop.start();
        host.run(2000);

        assertEquals("each step taps once", 2000, loop.taps());
        for (int i = 1; i < host.delays.size(); i++) {
            assertTrue("gap " + host.delays.get(i), host.delays.get(i) >= LiveAutoTap.MIN_GAP_MS);
        }
        for (int i = 8; i < host.tapTimes.size(); i++) {
            assertTrue("nine taps inside a second at " + i, host.tapTimes.get(i) - host.tapTimes.get(i - 8) >= 1000);
        }
    }

    @Test public void theAveragePaceMatchesTheRateWithJitter() {
        FakeHost host = new FakeHost();
        LiveAutoTap.Loop loop = new LiveAutoTap.Loop(host, 4, new Random(11));
        loop.start();
        host.run(1001);

        List<Long> gaps = host.delays.subList(1, host.delays.size());
        long sum = 0;
        long shortest = Long.MAX_VALUE;
        long longest = 0;
        for (long gap : gaps) {
            sum += gap;
            shortest = Math.min(shortest, gap);
            longest = Math.max(longest, gap);
        }
        double mean = (double) sum / gaps.size();
        assertTrue("mean " + mean, mean > 240 && mean < 260);
        // A quarter each way: the pace isn't a fixed metronome.
        assertTrue("shortest " + shortest, shortest >= 187 && shortest < 200);
        assertTrue("longest " + longest, longest > 300 && longest <= 313);
        assertEquals(LiveAutoTap.FIRST_TAP_MS, (long) host.delays.get(0));
    }

    @Test public void aStopReasonEndsTheLoopWithItsCount() {
        FakeHost host = new FakeHost();
        LiveAutoTap.Loop loop = new LiveAutoTap.Loop(host, 4, new Random(3));
        loop.start();
        host.run(3);
        assertEquals(3, loop.taps());

        host.reason = LiveAutoTap.End.OTHER_ROOM;
        host.run(5);

        assertFalse(loop.running());
        assertEquals(1, host.stops);
        assertEquals(LiveAutoTap.End.OTHER_ROOM, host.stoppedWhy);
        assertEquals(3, host.stoppedTaps);
        assertEquals(3, host.tapCalls);
        assertTrue("nothing left to run", host.queue.isEmpty());
    }

    @Test public void eachStopConditionEndsTheLoop() {
        for (LiveAutoTap.End why : new LiveAutoTap.End[]{LiveAutoTap.End.LEFT_ROOM, LiveAutoTap.End.BACKGROUND,
                LiveAutoTap.End.SCREEN_OFF, LiveAutoTap.End.SWITCHED_OFF, LiveAutoTap.End.PANEL}) {
            FakeHost host = new FakeHost();
            LiveAutoTap.Loop loop = new LiveAutoTap.Loop(host, 6, new Random(5));
            loop.start();
            host.run(2);
            host.reason = why;
            host.run(1);
            assertFalse(why.toString(), loop.running());
            assertEquals(why, host.stoppedWhy);
            assertEquals("the two steps before it tapped", 2, host.stoppedTaps);
        }
    }

    @Test public void holdingWaitsWithoutTappingOrStopping() {
        FakeHost host = new FakeHost();
        LiveAutoTap.Loop loop = new LiveAutoTap.Loop(host, 8, new Random(9));
        loop.start();
        host.run(2);
        host.holding = true;
        int before = host.delays.size();
        host.run(10);

        assertEquals(2, loop.taps());
        assertTrue(loop.running());
        for (long delay : host.delays.subList(before, host.delays.size())) {
            assertEquals(LiveAutoTap.HOLD_CHECK_MS, delay);
        }
        assertTrue(LiveAutoTap.HOLD_CHECK_MS >= LiveAutoTap.MIN_GAP_MS);

        host.holding = false;
        host.run(3);
        assertEquals(5, loop.taps());
    }

    @Test public void stopTurnsTheQueuedStepIntoNothing() {
        FakeHost host = new FakeHost();
        LiveAutoTap.Loop loop = new LiveAutoTap.Loop(host, 4, new Random(2));
        loop.start();
        host.run(2);
        loop.stop(LiveAutoTap.End.STOPPED);
        loop.stop(LiveAutoTap.End.STOPPED);
        host.run(5);

        assertEquals(1, host.stops);
        assertEquals(LiveAutoTap.End.STOPPED, host.stoppedWhy);
        assertEquals(2, host.stoppedTaps);
        assertEquals(2, host.tapCalls);
    }

    @Test public void aRestartLeavesTheOldStepDead() {
        FakeHost host = new FakeHost();
        LiveAutoTap.Loop loop = new LiveAutoTap.Loop(host, 4, new Random(2));
        loop.start();
        loop.start();
        assertEquals("a second start is ignored", 1, host.queue.size());
        loop.stop(LiveAutoTap.End.STOPPED);
        loop.start();
        assertEquals(2, host.queue.size());

        host.run(2);

        assertEquals("only the new loop's step tapped", 1, host.tapCalls);
        assertEquals(1, loop.taps());
    }

    @Test public void aTapWithNowhereToLandIsNotCounted() {
        FakeHost host = new FakeHost();
        host.tapLands = false;
        LiveAutoTap.Loop loop = new LiveAutoTap.Loop(host, 4, new Random(2));
        loop.start();
        host.run(4);

        assertEquals(0, loop.taps());
        assertEquals(4, host.tapCalls);
        assertTrue(loop.running());
    }

    @Test public void focusLostRightAfterATapMeansTheTapOpenedSomething() {
        assertTrue(LiveAutoTap.openedSomething(false, 1000, 0, 1500));
        assertFalse("still focused", LiveAutoTap.openedSomething(true, 1000, 0, 1500));
        assertFalse("long after the tap", LiveAutoTap.openedSomething(false, 1000, 0, 1000 + LiveAutoTap.PANEL_AFTER_TAP_MS));
        assertFalse("the finger came later", LiveAutoTap.openedSomething(false, 1000, 1200, 1500));
        assertFalse("no tap yet", LiveAutoTap.openedSomething(false, 0, 0, 100));
    }

    @Test public void aFingerHoldsTheTapsUntilAMomentAfterItLifts() {
        assertTrue(LiveAutoTap.fingerHolds(true, 0, 5000));
        assertTrue(LiveAutoTap.fingerHolds(false, 5000, 5000 + LiveAutoTap.FINGER_GRACE_MS - 1));
        assertFalse(LiveAutoTap.fingerHolds(false, 5000, 5000 + LiveAutoTap.FINGER_GRACE_MS));
        assertFalse(LiveAutoTap.fingerHolds(false, 0, 100));
    }

    @Test public void onlyAnotherRoomCountsAsARoomChange() {
        assertTrue(LiveAutoTap.sameRoom("7", "7"));
        assertFalse("unreadable ids never match", LiveAutoTap.sameRoom(null, null));
        assertFalse(LiveAutoTap.sameRoom("7", "8"));
        assertFalse(LiveAutoTap.sameRoom("7", null));
        assertFalse(LiveAutoTap.sameRoom(null, "8"));
    }

    @Test public void tapsLandOnTheHostsSideAndStayClearOfTargets() {
        float[] point = LiveAutoTap.tapPoint(1000, 2000, List.of(), 20, 0, new Random(4));
        assertNotNull(point);
        assertTrue(point[0] >= 220 && point[0] <= 280);
        assertTrue(point[1] >= 700 && point[1] <= 820);
        assertEquals(0, (int) point[2]);

        // Covers every place the first spot can move to; the next one sits above it.
        List<Rect> firstSpotTaken = List.of(new Rect(210, 690, 290, 830));
        for (int seed = 0; seed < 50; seed++) {
            float[] moved = LiveAutoTap.tapPoint(1000, 2000, firstSpotTaken, 20, 0, new Random(seed));
            assertNotNull("seed " + seed, moved);
            Rect clear = new Rect(190, 670, 310, 850);
            assertFalse("seed " + seed, clear.contains((int) moved[0], (int) moved[1]));
        }

        assertNull(LiveAutoTap.tapPoint(1000, 2000, List.of(new Rect(0, 0, 1000, 2000)), 20, 0, new Random(4)));
        assertNull(LiveAutoTap.tapPoint(0, 2000, List.of(), 20, 0, new Random(4)));
    }

    @Test public void noTapEverReachesTheOtherCreatorsHalf() {
        // In a battle the right half is the other creator, and a tap there opens their card (#335).
        for (int skip = 0; skip < 31; skip++) {
            for (int seed = 0; seed < 40; seed++) {
                float[] point = LiveAutoTap.tapPoint(1080, 2316, List.of(), 16, skip, new Random(seed));
                assertNotNull("skip " + skip, point);
                assertTrue("skip " + skip + " seed " + seed + " x " + point[0], point[0] < 1080 * 0.45f);
                assertTrue(point[1] > 2316 * 0.25f && point[1] < 2316 * 0.5f);
                assertEquals("a skipped spot is never used", 0, skip & (1 << (int) point[2]));
            }
        }
    }

    @Test public void aSpotThatOpenedACardIsSkippedUntilEveryOneHas() {
        assertEquals(1, LiveAutoTap.skipAfterCard(0, 0));
        assertEquals(0b101, LiveAutoTap.skipAfterCard(0b001, 2));
        assertEquals("all five opened something: start over", 0, LiveAutoTap.skipAfterCard(0b01111, 4));
        assertEquals("no tap yet", 0b10, LiveAutoTap.skipAfterCard(0b10, -1));
    }

    // ---- in a room ----------------------------------------------------------------------------

    /** A room page as LiveRecordChip reads one: by its fragment methods' names. */
    public static final class FakePage {
        final Activity activity;
        final View view;
        boolean resumed = true;
        /** The room it shows; null reads as a room whose id can't be read. */
        Room live;

        FakePage(Activity activity, View view) {
            this.activity = activity;
            this.view = view;
        }

        public Activity getActivity() { return activity; }
        public boolean isResumed() { return resumed; }
        public boolean isRemoving() { return false; }
        public boolean isDetached() { return false; }
        public boolean isHidden() { return false; }
        public View getView() { return view; }
    }

    private ActivityController<Activity> controller;
    /**
     * The open room's page. LiveRecordChip holds pages weakly, as TikTok's fragment manager is
     * what keeps a real one alive; a page only the test made could be collected mid-run and read
     * as the room closing.
     */
    private FakePage page;
    private Activity activity;
    private FrameLayout room;
    private View avatar;
    private final List<int[]> touches = new ArrayList<>();

    @Before public void setUp() {
        SettingsStatus.liveControlsEnabled = true;
        Settings.LIVE_AUTO_TAP.save(true);
        Settings.LIVE_AUTO_TAP_RATE.save("8");
    }

    @After public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
            shadowOf(Looper.getMainLooper()).idle();
        }
        Settings.LIVE_AUTO_TAP.save(Settings.LIVE_AUTO_TAP.defaultValue);
        Settings.LIVE_AUTO_TAP_RATE.save(Settings.LIVE_AUTO_TAP_RATE.defaultValue);
        SettingsStatus.liveControlsEnabled = false;
        LiveAutoTap.forgetSkippedSpotsForTests();
    }

    private FakePage openRoom() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        activity = controller.get();
        room = new FrameLayout(activity);
        room.setOnTouchListener((view, event) -> {
            touches.add(new int[]{event.getActionMasked(), Math.round(event.getX()), Math.round(event.getY())});
            return true;
        });
        avatar = new View(activity);
        avatar.setOnClickListener(clicked -> { });
        room.addView(avatar, new FrameLayout.LayoutParams(60, 60));
        activity.setContentView(room, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        controller.windowFocusChanged(true);
        shadowOf(Looper.getMainLooper()).idle();
        page = new FakePage(activity, room);
        LiveRecordChip.noteRoomFragment(page);
        shadowOf(Looper.getMainLooper()).idle();
        return page;
    }

    private TextView chip() {
        ViewGroup content = activity.findViewById(android.R.id.content);
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child instanceof TextView) return (TextView) child;
        }
        return null;
    }

    private int downs() {
        int count = 0;
        for (int[] touch : touches) if (touch[0] == MotionEvent.ACTION_DOWN) count++;
        return count;
    }

    @Test public void theChipTapsTheRoomThroughItsOwnTouchPathAndStopsOnASecondTap() {
        openRoom();
        TextView chip = chip();
        assertNotNull("the Auto tap chip is in the room", chip);
        assertEquals("Auto tap", chip.getText().toString());
        Window.Callback own = activity.getWindow().getCallback();

        chip.performClick();
        assertTrue("the finger watch is on while tapping",
                java.lang.reflect.Proxy.isProxyClass(activity.getWindow().getCallback().getClass()));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2300));

        int sent = downs();
        assertTrue("taps " + sent, sent >= 12 && sent <= 17);
        for (int i = 0; i + 1 < touches.size(); i += 2) {
            assertEquals(MotionEvent.ACTION_DOWN, touches.get(i)[0]);
            assertEquals(MotionEvent.ACTION_UP, touches.get(i + 1)[0]);
            assertEquals("the up lands where the down did", touches.get(i)[1], touches.get(i + 1)[1]);
            assertEquals(touches.get(i)[2], touches.get(i + 1)[2]);
            assertFalse("never on the avatar", touches.get(i)[1] < 80 && touches.get(i)[2] < 80);
        }
        assertFalse("its own taps don't hold it", LiveAutoTap.fingerHoldsForTests());
        assertTrue(chip().getText().toString().startsWith("● "));
        assertTrue(chip().getText().toString().endsWith("Stop"));

        chip().performClick();
        int afterStop = downs();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));

        assertEquals("no taps after Stop", afterStop, downs());
        assertSame("the window's own callback is back", own, activity.getWindow().getCallback());
        assertEquals("Auto tap", chip().getText().toString());
    }

    @Test public void aRealFingerHoldsTheTaps() {
        openRoom();
        chip().performClick();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        int before = downs();

        long now = SystemClock.uptimeMillis();
        MotionEvent finger = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 100, 300, 0);
        activity.getWindow().getCallback().dispatchTouchEvent(finger);
        assertTrue(LiveAutoTap.fingerHoldsForTests());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        int whileHeld = downs();

        assertEquals("only the finger's own down while it rests", before + 1, whileHeld);

        MotionEvent lift = MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 100, 300, 0);
        activity.getWindow().getCallback().dispatchTouchEvent(lift);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));

        assertTrue("taps come back after the finger lifts", downs() > whileHeld + 3);
    }

    @Test public void aTapThatOpensACardStopsAndTheNextStartInThatRoomTapsElsewhere() {
        FakePage first = openRoom();
        first.live = new Room();
        first.live.idStr = "7";
        int height = room.getHeight();
        chip().performClick();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertEquals(1, downs());
        int openedAt = touches.get(0)[2];
        assertTrue("the first spot", openedAt > height * 0.34f);

        // The tap opened a card: the window loses focus right after it.
        controller.windowFocusChanged(false);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertEquals("Auto tap", chip().getText().toString());
        assertEquals(1, downs());

        controller.windowFocusChanged(true);
        touches.clear();
        chip().performClick();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertEquals(1, downs());
        assertTrue("the next spot, above the one that opened the card", touches.get(0)[2] < height * 0.34f);

        // Another room starts from the first spot again.
        chip().performClick();
        first.live.idStr = "8";
        touches.clear();
        chip().performClick();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
        assertEquals(1, downs());
        assertTrue(touches.get(0)[2] > height * 0.34f);
    }

    @Test public void swipingToAnotherRoomStopsTheTaps() {
        FakePage first = openRoom();
        chip().performClick();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertTrue(downs() > 0);
        Window.Callback watched = activity.getWindow().getCallback();

        first.resumed = false;
        page = new FakePage(activity, room);
        LiveRecordChip.noteRoomFragment(page);
        shadowOf(Looper.getMainLooper()).idle();
        int stoppedAt = downs();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));

        assertEquals(stoppedAt, downs());
        assertFalse(java.lang.reflect.Proxy.isProxyClass(activity.getWindow().getCallback().getClass()));
        assertTrue(java.lang.reflect.Proxy.isProxyClass(watched.getClass()));
        assertEquals("the next room gets an idle chip", "Auto tap", chip().getText().toString());
    }

    @Test public void closingTheRoomStopsTheTapsAndTakesTheChip() {
        FakePage first = openRoom();
        chip().performClick();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertTrue(downs() > 0);

        first.resumed = false;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        int stoppedAt = downs();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));

        assertEquals(stoppedAt, downs());
        assertNull(chip());
    }

    @Test public void leavingTikTokStopsTheTaps() {
        openRoom();
        chip().performClick();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertTrue(downs() > 0);

        controller.pause();
        int stoppedAt = downs();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));

        assertEquals(stoppedAt, downs());
        assertNull("the chip goes with the room", chip());
        controller.resume();
    }

    @Test public void lockingTheScreenStopsTheTaps() {
        openRoom();
        chip().performClick();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertTrue(downs() > 0);

        PowerManager power = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
        shadowOf(power).setIsInteractive(false);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        int stoppedAt = downs();
        shadowOf(power).setIsInteractive(true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));

        assertEquals(stoppedAt, downs());
        assertEquals("Auto tap", chip().getText().toString());
    }

    @Test public void turningTheSwitchOffStopsTheTapsAndTakesTheChip() {
        openRoom();
        chip().performClick();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
        assertTrue(downs() > 0);

        Settings.LIVE_AUTO_TAP.save(false);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100));
        int stoppedAt = downs();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));

        assertEquals(stoppedAt, downs());
        assertNull(chip());
    }

    @Test public void theSwitchOffShowsNoChip() {
        Settings.LIVE_AUTO_TAP.save(false);
        openRoom();
        assertNull(chip());
    }
}

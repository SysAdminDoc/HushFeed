package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.tiktok.download.LiveRecorder;
import com.bytedance.android.livesdkapi.depend.model.live.Room;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * #37: the Record button follows the room page that is on screen. On 47.1.4 the pager resumes the
 * next room right after the one being watched, so following the last page to resume put the button
 * on a room nobody could see, and it went away a second after it showed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class LiveRecordChipTest {
    @Test public void aPageFullyOnScreenIsInFront() {
        assertTrue(LiveRecordChip.mostlyShown(2340, 2340));
    }

    @Test public void halfwayThroughASwipeThePageStillCounts() {
        assertTrue(LiveRecordChip.mostlyShown(1170, 2340));
        assertFalse(LiveRecordChip.mostlyShown(1169, 2340));
    }

    private static final class OneState {
        private final boolean cleared;
        OneState(boolean cleared) { this.cleared = cleared; }
        boolean current() { return cleared; }
        boolean moved(float x) { return x > 0; }
        void set() { }
    }

    private static final class TwoStates {
        boolean first() { return true; }
        boolean second() { return true; }
    }

    /** #265: the LIVE controller's state is read by shape, since its name moves every build. */
    @Test public void theClearStateIsTheControllersOnlyNoArgumentBoolean() throws Exception {
        assertEquals(Boolean.TRUE, LiveRecordChip.clearStateOf(new OneState(true)));
        assertEquals(Boolean.FALSE, LiveRecordChip.clearStateOf(new OneState(false)));
    }

    @Test public void aControllerWithNoClearAnswerReadsAsUnknown() throws Exception {
        assertNull(LiveRecordChip.clearStateOf(null));
        assertNull(LiveRecordChip.clearStateOf(new Object()));
        assertNull(LiveRecordChip.clearStateOf(new TwoStates()));
    }

    /** #265: a screen recording filmed the chip, so it's hidden for those and only those. */
    @Test public void aScreenRecordingHidesTheChipAndAStreamRecordingDoesNot() {
        assertTrue(LiveRecordChip.hidesChip(new LiveRecorder.Target(null, LiveRecorder.Transport.SCREEN, "c", "1", null)));
        assertFalse(LiveRecordChip.hidesChip(new LiveRecorder.Target("u", LiveRecorder.Transport.FLV, "c", "1", "hd")));
        assertFalse(LiveRecordChip.hidesChip(new LiveRecorder.Target("u", LiveRecorder.Transport.HLS, "c", "1", "hd")));
        assertFalse(LiveRecordChip.hidesChip(null));
    }

    @Test public void liveClearScreenHidesTheChipRegardlessOfRecordingSource() {
        LiveRecorder.Target flv = new LiveRecorder.Target("u", LiveRecorder.Transport.FLV, "c", "1", "hd");
        LiveRecorder.Target hls = new LiveRecorder.Target("u", LiveRecorder.Transport.HLS, "c", "1", "hd");
        assertTrue(LiveRecordChip.hidesChip(flv, true));
        assertTrue(LiveRecordChip.hidesChip(hls, true));
        assertTrue(LiveRecordChip.hidesChip(null, true));
        assertFalse(LiveRecordChip.hidesChip(flv, false));
        assertFalse(LiveRecordChip.hidesChip(hls, false));
    }

    private static final class RoomIdentity {
        public String idStr;
        public long id;

        RoomIdentity(String text, long numeric) {
            idStr = text;
            id = numeric;
        }
    }

    private static final class PipRoom extends Room {
        public String idStr;

        PipRoom(String id) { idStr = id; }
    }

    private static final class PipRoomPage {
        public Room room;
        private Activity activity;
        Object parent;

        PipRoomPage(Activity activity, String id) {
            this.activity = activity;
            room = new PipRoom(id);
        }

        public Activity getActivity() { return activity; }
        public Object getParentFragment() { return parent; }
        void detach() { activity = null; }
    }

    public static final class PipLiveActivity extends Activity {
        Object current;
        public Object getCurrentFragment() { return current; }
    }

    private static final class PipPager {
        Object current;
        public Object getCurrentFragment() { return current; }
    }

    private static final class PipTab {
        public final PipPager pager = new PipPager();
        private final Activity activity;
        PipTab(Activity activity) { this.activity = activity; }
        public Activity getActivity() { return activity; }
    }

    private static final class PipRoomPager {
        private final Activity activity;
        Object current;
        PipRoomPager(Activity activity) { this.activity = activity; }
        public Activity getActivity() { return activity; }
        public Object getCurrentFragment() { return current; }
    }

    public static final class PipNullGetterActivity extends Activity {
        public Object getCurrentFragment() { return null; }
    }

    private static final class PipRoomContainer {
        Object current;
        public Object getCurrentFragment() { return current; }
    }

    @Test public void pipFindsThePlayingRoomWhenTheActivityGetterAlwaysReturnsNull() {
        PipNullGetterActivity activity = Robolectric.buildActivity(PipNullGetterActivity.class).setup().get();
        PipRoomContainer pager = new PipRoomContainer();
        PipRoomPage first = new PipRoomPage(activity, "first");
        PipRoomPage preloaded = new PipRoomPage(activity, "preloaded");
        PipRoomPage second = new PipRoomPage(activity, "second");
        first.parent = pager;
        preloaded.parent = pager;
        second.parent = pager;
        List<PipRoomPage> resumed = Arrays.asList(first, preloaded, second);
        pager.current = first;
        assertEquals(first, LiveRecordChip.currentPipPage(activity, first, resumed));
        pager.current = second;
        assertEquals(second, LiveRecordChip.currentPipPage(activity, first, resumed));
        assertFalse(preloaded == LiveRecordChip.currentPipPage(activity, first, resumed));
        pager.current = null;
        assertNull(LiveRecordChip.currentPipPage(activity, first, resumed));
        pager.current = new PipRoomPage(Robolectric.buildActivity(Activity.class).setup().get(), "wrong activity");
        assertNull(LiveRecordChip.currentPipPage(activity, first, resumed));
    }

    @Test public void pipFindsTheNextRoomAfterTheRememberedFragmentDetaches() {
        PipNullGetterActivity activity = Robolectric.buildActivity(PipNullGetterActivity.class).setup().get();
        PipRoomContainer pager = new PipRoomContainer();
        PipRoomPage old = new PipRoomPage(activity, "old");
        PipRoomPage next = new PipRoomPage(activity, "next");
        old.parent = pager;
        next.parent = pager;
        pager.current = next;
        old.detach();
        assertEquals(next, LiveRecordChip.currentPipPage(activity, old, Arrays.asList(old, next)));
    }

    @Test public void pipNeverGuessesBetweenTwoIndependentActiveRoomPagers() {
        PipNullGetterActivity activity = Robolectric.buildActivity(PipNullGetterActivity.class).setup().get();
        PipRoomContainer firstPager = new PipRoomContainer();
        PipRoomContainer secondPager = new PipRoomContainer();
        PipRoomPage first = new PipRoomPage(activity, "first");
        PipRoomPage second = new PipRoomPage(activity, "second");
        first.parent = firstPager;
        second.parent = secondPager;
        firstPager.current = first;
        secondPager.current = second;
        assertNull(LiveRecordChip.currentPipPage(activity, null, Arrays.asList(first, second)));
        assertNull(LiveRecordChip.currentPipPage(activity, first, Arrays.asList(first, second)));
    }

    @Test public void pipPrefersTheActivitySelectedRoomOverAnOldParentPager() {
        PipLiveActivity activity = Robolectric.buildActivity(PipLiveActivity.class).setup().get();
        PipRoomContainer oldPager = new PipRoomContainer();
        PipRoomPage old = new PipRoomPage(activity, "old");
        PipRoomPage active = new PipRoomPage(activity, "active");
        old.parent = oldPager;
        oldPager.current = old;
        activity.current = active;
        assertEquals(active, LiveRecordChip.currentPipPage(activity, old, Arrays.asList(old)));
    }

    @Test public void pipSelectsTheCurrentPlayingRoomRatherThanAPreloadedNeighbor() {
        PipLiveActivity activity = Robolectric.buildActivity(PipLiveActivity.class).setup().get();
        PipRoomPage first = new PipRoomPage(activity, "first");
        PipRoomPage prefetched = new PipRoomPage(activity, "prefetched");
        PipRoomPage next = new PipRoomPage(activity, "next");
        activity.current = first;
        assertEquals(first, LiveRecordChip.currentPipPage(activity));
        // A neighboring room exists but does not replace the player's current room.
        assertEquals(first, LiveRecordChip.currentPipPage(activity));
        activity.current = next;
        assertEquals(next, LiveRecordChip.currentPipPage(activity));
        assertFalse(prefetched == LiveRecordChip.currentPipPage(activity));
    }

    @Test public void pipDoesNotSelectAnUnattachedOrUnknownRoom() {
        PipLiveActivity activity = Robolectric.buildActivity(PipLiveActivity.class).setup().get();
        PipLiveActivity other = Robolectric.buildActivity(PipLiveActivity.class).setup().get();
        activity.current = new PipRoomPage(other, "another");
        assertNull(LiveRecordChip.currentPipPage(activity));
        activity.current = new PipRoomPage(activity, null);
        assertNull(LiveRecordChip.currentPipPage(activity));
        activity.current = null;
        assertNull(LiveRecordChip.currentPipPage(activity));
    }

    @Test public void pipResolvesTheCurrentRoomInsideTheSelectedLiveTab() {
        PipLiveActivity activity = Robolectric.buildActivity(PipLiveActivity.class).setup().get();
        PipTab tab = new PipTab(activity);
        activity.current = tab;
        PipRoomPage old = new PipRoomPage(activity, "old");
        PipRoomPage next = new PipRoomPage(activity, "next");
        tab.pager.current = old;
        assertEquals(old, LiveRecordChip.currentPipPage(activity));
        tab.pager.current = next;
        assertEquals(next, LiveRecordChip.currentPipPage(activity));
        tab.pager.current = null;
        assertNull(LiveRecordChip.currentPipPage(activity));
    }

    @Test public void pipResolvesTheCurrentRoomInADirectRoomPager() {
        PipLiveActivity activity = Robolectric.buildActivity(PipLiveActivity.class).setup().get();
        PipRoomPager pager = new PipRoomPager(activity);
        activity.current = pager;
        PipRoomPage first = new PipRoomPage(activity, "first");
        PipRoomPage next = new PipRoomPage(activity, "next");
        pager.current = first;
        assertEquals(first, LiveRecordChip.currentPipPage(activity));
        pager.current = next;
        assertEquals(next, LiveRecordChip.currentPipPage(activity));
    }

    @Test public void clearScreenEventsUseTheRoomsStableId() {
        assertEquals("123", LiveRecordChip.roomKey(new RoomIdentity("123", 456)));
        assertEquals("456", LiveRecordChip.roomKey(new RoomIdentity(null, 456)));
        assertNull(LiveRecordChip.roomKey(null));
    }

    @Test public void aRoomBesideTheOneInFrontDoesNot() {
        assertFalse(LiveRecordChip.mostlyShown(0, 2340));
        assertFalse(LiveRecordChip.mostlyShown(0, 0));
    }

    @Test public void streamRecordingContinuesInNativePipAfterTransitionEnds() {
        assertTrue(LiveRecordChip.keepWhileHidden(true, true, 20_000, 0));
        assertTrue(LiveRecordChip.keepWhileHidden(true, true, 20_000, 10_000));
    }

    @Test public void enteringPipHasOnlyABriefGracePeriod() {
        assertTrue(LiveRecordChip.keepWhileHidden(true, false, 9_999, 10_000));
        assertFalse(LiveRecordChip.keepWhileHidden(true, false, 10_000, 10_000));
        assertFalse(LiveRecordChip.keepWhileHidden(true, false, 15_000, 10_000));
        assertFalse(LiveRecordChip.keepWhileHidden(true, false, 100, 0));
    }

    @Test public void screenRecordingNeverContinuesIntoPipOrTheLauncher() {
        assertFalse(LiveRecordChip.keepWhileHidden(false, true, 1, 10_000));
        assertFalse(LiveRecordChip.keepWhileHidden(false, false, 1, 10_000));
    }

    @Test public void destroyingTheLiveActivityDoesNotClaimTikTokClosed() {
        assertEquals(LiveRecorder.End.LEFT_ROOM, LiveRecordChip.LIVE_ACTIVITY_DESTROYED_END);
    }

    @Test public void aRecreatedFragmentForTheSameLiveDoesNotMeanLeavingTheRoom() {
        assertTrue(LiveRecordChip.sameRoomId("1234", "1234"));
        assertFalse(LiveRecordChip.sameRoomId("1234", "5678"));
        assertFalse(LiveRecordChip.sameRoomId("1234", null));
        assertFalse(LiveRecordChip.sameRoomId(null, "1234"));
        assertFalse(LiveRecordChip.sameRoomId(null, null));
    }

    @Test public void pipExitGetsGraceEvenIfTheTickRunsBeforeOnResume() {
        assertEquals(6_500, LiveRecordChip.pipReturnDeadline(true, true, 0, 5_000));
        assertEquals(6_700, LiveRecordChip.pipReturnDeadline(true, false, 6_500, 5_200));
        assertEquals(0, LiveRecordChip.pipReturnDeadline(true, false, 6_500, 6_500));
        assertEquals(0, LiveRecordChip.pipReturnDeadline(false, true, 6_500, 5_000));
    }

    @Test public void automaticRecordingIsOffUnlessEnabledAndTheRoomIsVisible() {
        LiveRecordChip.AutoRecordVisit visit = new LiveRecordChip.AutoRecordVisit();
        assertFalse(visit.observe(null, true, false));
        assertFalse(visit.observe("adjacent", false, false));
        assertFalse(visit.observe("visible", true, false));
        assertTrue(visit.observe("visible", true, false));
        assertFalse(visit.observe("visible", true, false));
    }

    @Test public void automaticRecordingRequiresOptInForegroundAndStreamSource() {
        assertFalse(LiveRecordChip.autoStreamAllowed(true, true, false, false, false));
        assertFalse(LiveRecordChip.autoStreamAllowed(true, false, true, false, false));
        assertFalse(LiveRecordChip.autoStreamAllowed(false, true, true, false, false));
        assertFalse(LiveRecordChip.autoStreamAllowed(true, true, true, true, false));
        assertFalse(LiveRecordChip.autoStreamAllowed(false, true, true, true, true));
        assertTrue(LiveRecordChip.autoStreamAllowed(false, true, true, false, true));
        assertTrue(LiveRecordChip.autoStreamAllowed(true, true, true, false, false));
    }

    @Test public void pipAutoRecordStartsOnlyAfterTheNewRoomIsConfirmed() {
        LiveRecordChip.AutoRecordVisit visit = new LiveRecordChip.AutoRecordVisit();
        assertFalse(visit.observe("first", true, false));
        assertTrue(visit.observe("first", true, false));
        assertFalse("do not restart the current room", visit.observe("first", true, false));
        assertFalse("first observation of a new PiP room", visit.observe("next", true, true));
        assertFalse("old recording is closing", visit.observe("next", true, true));
        assertTrue("start once its source closes", visit.observe("next", true, false));
        assertFalse("only once per PiP room", visit.observe("next", true, false));
    }

    @Test public void automaticRecordingWaitsForThePreviousRoomToFinishSaving() {
        LiveRecordChip.AutoRecordVisit visit = new LiveRecordChip.AutoRecordVisit();
        assertFalse(visit.observe("first", true, false));
        assertTrue(visit.observe("first", true, false));
        assertFalse(visit.observe("second", true, true));
        assertFalse(visit.observe("second", true, true));
        assertTrue(visit.observe("second", true, false));
        assertFalse(visit.observe("second", true, false));
    }

    @Test public void aFullSaveQueueRetriesAutomaticRecordingOnlyAfterAFileFinishes() {
        LiveRecordChip.AutoRecordVisit visit = new LiveRecordChip.AutoRecordVisit();
        assertFalse(visit.observe("room", true, false));
        assertTrue(visit.observe("room", true, false));
        visit.waitForSave();
        assertFalse("do not retry the full queue every tick", visit.observe("room", true, false));
        visit.saveFinished();
        assertTrue("the same visible room gets another chance", visit.observe("room", true, false));
        assertFalse(visit.observe("room", true, false));
    }

    @Test public void automaticRecordingDoesNotRestartAfterManualStopInTheSameRoom() {
        LiveRecordChip.AutoRecordVisit visit = new LiveRecordChip.AutoRecordVisit();
        visit.startedManually("room");
        assertFalse(visit.observe("room", true, false));
        assertFalse(visit.observe("room", true, false));
        visit.reset();
        assertFalse(visit.observe("room", true, false));
        assertTrue(visit.observe("room", true, false));
    }

    @Test public void disconnectedStreamRestartsInTheSamePipRoomAfterBackoff() {
        LiveRecordChip.AutoRecordVisit visit = new LiveRecordChip.AutoRecordVisit();
        assertFalse(visit.observe("room", true, false, 1000));
        assertTrue(visit.observe("room", true, false, 2000));
        visit.streamEnded("room", LiveRecorder.End.DISCONNECTED, 180000, 3000);
        assertFalse("do not immediately reconnect", visit.observe("room", true, false, 7999));
        assertTrue("reconnect while the same LIVE remains active", visit.observe("room", true, false, 8000));
        assertFalse("only one attempt per failure", visit.observe("room", true, false, 8001));
    }

    @Test public void repeatedEarlyDisconnectsBackOffWithoutPermanentlyDisablingAutoRecord() {
        LiveRecordChip.AutoRecordVisit visit = new LiveRecordChip.AutoRecordVisit();
        assertFalse(visit.observe("room", true, false, 0));
        assertTrue(visit.observe("room", true, false, 1000));
        visit.streamEnded("room", LiveRecorder.End.DISCONNECTED, 1000, 2000);
        assertTrue(visit.observe("room", true, false, 7000));
        visit.streamEnded("room", LiveRecorder.End.DISCONNECTED, 1000, 8000);
        assertFalse(visit.observe("room", true, false, 17999));
        assertTrue(visit.observe("room", true, false, 18000));
        visit.streamEnded("room", LiveRecorder.End.DISCONNECTED, 1000, 19000);
        assertFalse(visit.observe("room", true, false, 38999));
        assertTrue(visit.observe("room", true, false, 39000));
    }

    @Test public void manualStopAndSafetyLimitsNeverTriggerSameRoomRetry() {
        for (LiveRecorder.End end : LiveRecorder.End.values()) {
            if (end == LiveRecorder.End.DISCONNECTED) continue;
            LiveRecordChip.AutoRecordVisit visit = new LiveRecordChip.AutoRecordVisit();
            visit.startedManually("room");
            visit.streamEnded("room", end, 5000, 1000);
            assertFalse("must not retry after " + end, visit.observe("room", true, false, 120000));
        }
    }

    @Test public void aStaleFailureCannotRestartTheWrongRoomOrBlockANewOne() {
        LiveRecordChip.AutoRecordVisit visit = new LiveRecordChip.AutoRecordVisit();
        assertFalse(visit.observe("first", true, false, 100));
        assertTrue(visit.observe("first", true, false, 200));
        visit.streamEnded("neighbor", LiveRecorder.End.DISCONNECTED, 1000, 300);
        assertFalse(visit.observe("first", true, false, 120000));
        visit.streamEnded("first", LiveRecorder.End.DISCONNECTED, 1000, 120100);
        assertFalse(visit.observe("second", true, false, 120101));
        assertTrue("new LIVE should not inherit old backoff", visit.observe("second", true, false, 120102));
    }

    @Test public void retryRespectsOptOutAndExplicitlyLeavingTheRoom() {
        LiveRecordChip.AutoRecordVisit visit = new LiveRecordChip.AutoRecordVisit();
        assertFalse(visit.observe("room", true, false, 1000));
        assertTrue(visit.observe("room", true, false, 2000));
        visit.streamEnded("room", LiveRecorder.End.DISCONNECTED, 2000, 3000);
        assertFalse(visit.observe("room", false, false, 10000));
        visit.reset();
        assertFalse(visit.observe("room", true, false, 10001));
        assertTrue(visit.observe("room", true, false, 10002));
    }

    @Test public void automaticRecordingIgnoresBriefSwipesToAnAdjacentRoom() {
        LiveRecordChip.AutoRecordVisit visit = new LiveRecordChip.AutoRecordVisit();
        assertFalse(visit.observe("first", true, false));
        assertTrue(visit.observe("first", true, false));
        assertFalse(visit.observe("second", true, false));
        assertFalse(visit.observe("first", true, false));
        assertFalse(visit.observe("first", true, false));
        assertFalse(visit.observe("second", true, false));
        assertTrue(visit.observe("second", true, false));
    }

    @Test public void automaticRecordingRequiresAStableRoomIdentity() {
        LiveRecordChip.AutoRecordVisit visit = new LiveRecordChip.AutoRecordVisit();
        assertFalse(visit.observe("", true, false));
        assertFalse(visit.observe(null, true, false));
        assertFalse(visit.observe("room", true, false));
        assertFalse(visit.observe(null, true, false));
        assertFalse(visit.observe("room", true, false));
        assertTrue(visit.observe("room", true, false));
    }

    // The chip is 200 wide at the right edge (880 to 1080), 120 tall, wanted at 330.

    @Test public void theChipStaysPutWhenNothingIsUnderIt() {
        List<Rect> taken = List.of(new Rect(0, 330, 600, 500), new Rect(880, 0, 1080, 200));
        assertEquals(330, LiveRecordChip.freeTop(330, 120, 880, 1080, taken, 20, 1500));
    }

    @Test public void theChipMovesBelowTheDiscoverPanelsAvatarsAndBackWhenTheyGo() {
        // The panel's row of avatars, one of them under the chip's spot, then a second row.
        List<Rect> panel = List.of(new Rect(700, 300, 900, 500), new Rect(900, 300, 1080, 500),
                new Rect(860, 600, 1080, 800));
        assertEquals("below both rows", 820, LiveRecordChip.freeTop(330, 120, 880, 1080, panel, 20, 1500));
        assertEquals("back once they're gone", 330,
                LiveRecordChip.freeTop(330, 120, 880, 1080, List.of(), 20, 1500));
    }

    @Test public void aColumnOfControlsDownTheSideLeavesTheChipWhereItWas() {
        List<Rect> side = List.of(new Rect(900, 250, 1080, 1700));
        assertEquals(330, LiveRecordChip.freeTop(330, 120, 880, 1080, side, 20, 1500));
    }

    @Test public void onlyShownTapTargetsSmallerThanTheLimitCount() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout content = new FrameLayout(activity);
        View avatar = new View(activity);
        avatar.setOnClickListener(v -> { });
        View hidden = new View(activity);
        hidden.setOnClickListener(v -> { });
        hidden.setVisibility(View.GONE);
        View surface = new View(activity);
        surface.setOnClickListener(v -> { });
        View chip = new View(activity);
        chip.setOnClickListener(v -> { });
        content.addView(avatar, new FrameLayout.LayoutParams(100, 100));
        content.addView(hidden, new FrameLayout.LayoutParams(100, 100));
        content.addView(surface, new FrameLayout.LayoutParams(1000, 1000));
        content.addView(chip, new FrameLayout.LayoutParams(100, 100));
        activity.setContentView(content, new ViewGroup.LayoutParams(1000, 1000));
        content.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, 1000, 1000);
        Rect area = new Rect();
        assertTrue(content.getGlobalVisibleRect(area));

        List<Rect> found = new ArrayList<>();
        LiveRecordChip.tapTargets(content, chip, area, (long) area.width() * area.height() / 4, found);

        assertEquals(List.of(new Rect(0, 0, 100, 100)), found);
    }

    private static View stackedButton(Activity activity, FrameLayout content, int rank, int width, int wantedTop) {
        View view = new View(activity);
        view.setOnClickListener(v -> { });
        LiveRecordChip.markStacked(view, rank, wantedTop, 10);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, 40, Gravity.TOP | Gravity.END);
        params.topMargin = wantedTop;
        params.rightMargin = 10;
        content.addView(view, params);
        return view;
    }

    private static int top(View view) {
        return ((FrameLayout.LayoutParams) view.getLayoutParams()).topMargin;
    }

    /**
     * A room opening or the Discover panel sliding in puts something to tap under the Record chip.
     * One pass moves the whole stack below it, each button under the new spot of the one above, with
     * no layout in between. Moving each button against the others' last drawn spots put Auto tap and
     * the block button on top of the Record chip until later ticks sorted them out.
     */
    @Test public void oneRestackMovesTheWholeStackBelowWhatTikTokDrawsWithoutOverlaps() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout content = new FrameLayout(activity);
        // Robolectric's window is 320 by 470; the panel's avatar row sits over the Record chip's spot.
        View avatars = new View(activity);
        avatars.setOnClickListener(v -> { });
        FrameLayout.LayoutParams row = new FrameLayout.LayoutParams(120, 50, Gravity.TOP | Gravity.END);
        row.topMargin = 30;
        content.addView(avatars, row);
        View record = stackedButton(activity, content, LiveRecordChip.STACK_RECORD, 60, 40);
        View autoTap = stackedButton(activity, content, LiveRecordChip.STACK_AUTO_TAP, 60, 90);
        View block = stackedButton(activity, content, LiveRecordChip.STACK_BLOCK, 40, 90);
        activity.setContentView(content, new ViewGroup.LayoutParams(320, 470));
        content.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(470, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, 320, 470);
        Rect area = new Rect();
        assertTrue(content.getGlobalVisibleRect(area));
        assertEquals(320, area.width());

        LiveRecordChip.restack(content);

        assertEquals("Record goes below the avatars", 90, top(record));
        assertEquals("Auto tap goes below Record's new spot", 140, top(autoTap));
        assertEquals("the block button goes below both", 190, top(block));

        // Auto tap hidden by a screen recording keeps no room, so the block button takes its spot.
        autoTap.setVisibility(View.INVISIBLE);
        LiveRecordChip.restack(content);
        assertEquals(90, top(record));
        assertEquals(140, top(block));

        // The panel closes and everything goes back up.
        avatars.setVisibility(View.GONE);
        autoTap.setVisibility(View.VISIBLE);
        LiveRecordChip.restack(content);
        assertEquals(40, top(record));
        assertEquals(90, top(autoTap));
        assertEquals(140, top(block));

        // Auto tap's fingers still steer clear of every Hushfeed button.
        List<Rect> forAutoTapFingers = new ArrayList<>();
        LiveRecordChip.tapTargets(content, null, area, (long) area.width() * area.height() / 4, forAutoTapFingers);
        assertEquals(3, forAutoTapFingers.size());
    }
}

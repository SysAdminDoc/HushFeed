package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.*;
import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.common.widget.VerticalViewPager;
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

/** A LIVE preview in the For You pager: its host is current while it is on screen, and only then. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveFeedCellsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private ActivityController<Activity> owner;
    private VerticalViewPager pager;
    private FrameLayout liveCell;
    private final BlockFeedAdvanceTest.FakeService service = new BlockFeedAdvanceTest.FakeService();

    @Before public void setup() throws Exception {
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedService", service);
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedBlockMethod",
                BlockFeedAdvanceTest.FakeService.class.getMethod(
                        "block", String.class, String.class, int.class, int.class));
        ReflectionHelpers.setStaticField(BlockAuthorOverlay.class, "requestInFlight", false);
        Settings.BLOCK_AUTHOR_BUTTON.save(false);
        Settings.NOT_INTERESTED_BUTTON.save(false);
        CurrentVideoAuthor.resetForTests();
        owner = Robolectric.buildActivity(Activity.class).setup().visible().windowFocusChanged(true);
        Utils.setContext(owner.get());
        Utils.setActivity(owner.get());
        pager = new VerticalViewPager(owner.get());
        owner.get().setContentView(pager);
        liveCell = new FrameLayout(owner.get());
        pager.addView(liveCell);
        CurrentVideoAuthor.update(new CurrentVideoAuthorTest.Params("clip", "creator"));
        CurrentVideoAuthor.onPlaying("clip");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        // The window's own size, from the pass Robolectric ran: a hand-sized root left the cell
        // clipped by a smaller pager and read as half off screen.
        placeLiveCell(0);
    }

    @After public void cleanup() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedService", null);
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedBlockMethod", null);
        ReflectionHelpers.setStaticField(BlockAuthorOverlay.class, "requestInFlight", false);
        java.lang.reflect.Method dismiss = BlockAuthorOverlay.class.getDeclaredMethod("dismissUndo");
        dismiss.setAccessible(true);
        dismiss.invoke(null);
        CurrentVideoAuthor.resetForTests();
        Utils.setActivity(null);
        owner.close();
    }

    /** Lays the LIVE page, one full pager page tall, at a vertical offset. */
    private void placeLiveCell(int top) {
        int width = pager.getWidth() > 0 ? pager.getWidth() : 400;
        int height = pager.getHeight() > 0 ? pager.getHeight() : 800;
        liveCell.layout(0, top, width, top + height);
    }

    private void bind(String aid, String roomId, String uid, String handle) {
        Owner host = new Owner();
        host.idStr = uid;
        host.username = handle;
        Room room = new Room();
        room.id = Long.parseLong(roomId);
        room.owner = host;
        LiveAweme aweme = new LiveAweme();
        aweme.aid = aid;
        aweme.newLiveRoomData = room;
        Holder holder = new Holder();
        holder.itemView = liveCell;
        LiveFeedCells.onBind(holder, aweme);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void tapBlock() throws Exception {
        java.lang.reflect.Method tap = BlockAuthorOverlay.class.getDeclaredMethod("onBlockTapped");
        tap.setAccessible(true);
        tap.invoke(null);
    }

    @Test public void liveHostReplacesThePreviousVideosCreatorWhileItIsOnScreen() {
        assertEquals("creator", CurrentVideoAuthor.get().uid);
        bind("live1", "555", "host1", "scammer");
        assertTrue(CurrentVideoAuthor.isLive());
        assertEquals("host1", CurrentVideoAuthor.get().uid);
        assertEquals("live1", CurrentVideoAuthor.get().awemeId);
    }

    @Test public void swipingTheLiveAwayHandsBackTheVideoCreator() {
        bind("live1", "555", "host1", "scammer");
        placeLiveCell(-pager.getHeight());
        LiveFeedCells.evaluate();
        assertFalse(CurrentVideoAuthor.isLive());
        assertEquals("creator", CurrentVideoAuthor.get().uid);
    }

    @Test public void aLiveBarelyOnScreenIsNotCurrent() {
        placeLiveCell(pager.getHeight() * 5 / 8);
        bind("live1", "555", "host1", "scammer");
        assertFalse(CurrentVideoAuthor.isLive());
        assertEquals("creator", CurrentVideoAuthor.get().uid);
    }

    @Test public void aHostWithNoAccountIdsHidesTheButtonInsteadOfArmingTheVideoBefore() {
        bind("live1", "555", null, null);
        assertTrue(CurrentVideoAuthor.isLive());
        assertNull(CurrentVideoAuthor.get());
    }

    @Test public void theNextVideoTheReaderPlaysEndsTheLiveSelection() {
        bind("live1", "555", "host1", "scammer");
        CurrentVideoAuthor.update(new CurrentVideoAuthorTest.Params("next", "other"));
        CurrentVideoAuthor.onPlaying("next");
        assertFalse(CurrentVideoAuthor.isLive());
        assertEquals("other", CurrentVideoAuthor.get().uid);
    }

    @Test public void theLivesOwnIdReportedByThePlayerDoesNotEndIt() {
        bind("live1", "555", "host1", "scammer");
        CurrentVideoAuthor.onPlaying("live1");
        assertTrue(CurrentVideoAuthor.isLive());
        assertEquals("host1", CurrentVideoAuthor.get().uid);
    }

    @Test public void aLiveCardOutsideTheFeedPagerIsNeverCurrent() {
        FrameLayout card = new FrameLayout(owner.get());
        ViewGroup content = owner.get().findViewById(android.R.id.content);
        content.addView(card);
        card.layout(0, 0, pager.getWidth(), pager.getHeight());
        placeLiveCell(-pager.getHeight());
        Owner host = new Owner();
        host.idStr = "host2";
        Room room = new Room();
        room.id = 9;
        room.owner = host;
        LiveAweme aweme = new LiveAweme();
        aweme.aid = "search";
        aweme.newLiveRoomData = room;
        Holder holder = new Holder();
        holder.itemView = card;
        LiveFeedCells.onBind(holder, aweme);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(CurrentVideoAuthor.isLive());
        assertEquals("creator", CurrentVideoAuthor.get().uid);
    }

    @Test public void tappingBlockOnALiveBlocksTheHostAndMovesTheFeedOn() throws Exception {
        bind("live1", "555", "host1", "scammer");
        tapBlock();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(java.util.List.of("host1:1"), service.calls);
        assertEquals(1, pager.advances);
        ViewGroup root = owner.get().findViewById(android.R.id.content);
        TextView chip = (TextView) root.getChildAt(root.getChildCount() - 1);
        assertEquals("Unblock", chip.getText().toString());
    }

    @Test public void aTapAfterTheLiveWasSwipedPastBlocksTheVideoNotTheHost() throws Exception {
        bind("live1", "555", "host1", "scammer");
        // The page moves without a layout callback reaching the cell, as a scroll can.
        liveCell.offsetTopAndBottom(-pager.getHeight());
        tapBlock();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(java.util.List.of("creator:1"), service.calls);
    }

    public static final class Owner {
        public String idStr;
        public String username;
    }

    public static final class Room {
        public long id;
        public Owner owner;
    }

    public static final class LiveAweme {
        public String aid;
        public Room newLiveRoomData;
    }

    public static final class Holder {
        public View itemView;
    }
}

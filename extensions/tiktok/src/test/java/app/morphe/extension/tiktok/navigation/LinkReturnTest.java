package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.ActivityManager;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Back from a handed-over link's page goes back to the app the link came from (#276). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LinkReturnTest {
    /** The chat's task in Telegram. */
    private static final int SENDER_TASK = 41;

    private int taskSize;
    /** What happened, in order: "front 41" for the sender's task, "back" for TikTok's. */
    private static final List<String> moves = new ArrayList<>();

    /** Stands in for TikTok's MainActivity: the handed-over instance and the one in TikTok's task. */
    public static class Main extends Activity {
        boolean movedBack;

        @Override public boolean moveTaskToBack(boolean nonRoot) {
            movedBack = true;
            moves.add("back");
            return true;
        }
    }

    /** A page over the main screen, a profile or a video. */
    public static class Page extends Main {
    }

    @Before public void setUp() {
        taskSize = 2;
        moves.clear();
        LinkReturn.taskSizeForTests = activity -> taskSize < 0 ? null : taskSize;
        LinkReturn.taskFrontForTests = (activity, taskId) -> moves.add("front " + taskId);
    }

    @After public void tearDown() {
        LinkReturn.resetForTests();
    }

    private static Main handOver() {
        return handOver(SENDER_TASK);
    }

    private static Main handOver(int senderTask) {
        Main handed = Robolectric.buildActivity(Main.class).create().get();
        handed.finish();
        LinkReturn.arm(handed, senderTask);
        return handed;
    }

    private static ActivityController<Main> main() {
        return Robolectric.buildActivity(Main.class).create().start().resume();
    }

    private static ActivityController<Page> page() {
        return Robolectric.buildActivity(Page.class).create().start().resume();
    }

    private static void close(ActivityController<? extends Activity> controller) {
        controller.get().finish();
        controller.pause();
    }

    @Test public void backFromTheLinksPageLeavesTikTok() {
        handOver();
        ActivityController<Main> main = main();
        main.pause();
        ActivityController<Page> page = page();
        main.stop();

        close(page);

        assertTrue(page.get().movedBack);
        assertFalse(main.get().movedBack);
        assertFalse(LinkReturn.armed());
    }

    /**
     * Picked from Recents, TikTok's task sits right over the home screen, so moving it back alone
     * showed the home screen (S22, 2026-10-31). The chat's task comes to the front first, while
     * TikTok is still in front and allowed to ask.
     */
    @Test public void theSendersTaskComesBackBeforeTikTokMovesBack() {
        handOver();
        ActivityController<Page> page = page();
        close(page);

        assertEquals(List.of("front " + SENDER_TASK, "back"), moves);
    }

    @Test public void noSenderTaskStillMovesTikTokBack() {
        handOver(-1);
        ActivityController<Page> page = page();
        close(page);

        assertEquals(List.of("back"), moves);
    }

    @Test public void aPinnedScreenLeavesTheSendersTaskAlone() {
        ActivityManager manager = RuntimeEnvironment.getApplication()
                .getSystemService(ActivityManager.class);
        Shadows.shadowOf(manager).setLockTaskModeState(ActivityManager.LOCK_TASK_MODE_PINNED);
        handOver();
        ActivityController<Page> page = page();
        close(page);

        assertEquals(List.of("back"), moves);
    }

    @Test public void aMoveThatThrowsStillMovesTikTokBack() {
        LinkReturn.taskFrontForTests = (activity, taskId) -> {
            throw new SecurityException("REORDER_TASKS");
        };
        handOver();
        ActivityController<Page> page = page();
        close(page);

        assertTrue(page.get().movedBack);
    }

    @Test public void theNextLinkDoesNotReuseAnOldSender() {
        handOver();
        ActivityController<Page> first = page();
        close(first);
        moves.clear();

        handOver(-1);
        ActivityController<Page> second = page();
        close(second);

        assertEquals(List.of("back"), moves);
    }

    @Test public void aLoadingPageHandsOverToTheRealOne() {
        handOver();
        ActivityController<Page> loading = page();
        taskSize = 3;
        close(loading);
        assertFalse(loading.get().movedBack);

        ActivityController<Page> profile = page();
        taskSize = 2;
        close(profile);
        assertTrue(profile.get().movedBack);
    }

    @Test public void anUnreadableTaskWaitsForTheMainScreen() {
        handOver();
        ActivityController<Page> page = page();
        taskSize = -1;
        close(page);
        assertFalse(page.get().movedBack);
        assertTrue(LinkReturn.armed());

        Main main = main().get();
        assertTrue(main.movedBack);
        assertFalse(LinkReturn.armed());
    }

    @Test public void pagesOpenedFromTheLinksPageCloseAsUsual() {
        handOver();
        ActivityController<Page> profile = page();
        profile.pause();
        ActivityController<Page> video = page();
        profile.stop();

        taskSize = 3;
        close(video);
        assertFalse(video.get().movedBack);

        profile.start().resume();
        taskSize = 2;
        close(profile);
        assertTrue(profile.get().movedBack);
    }

    /**
     * Adam39k's steps: a profile from a Telegram link, a video from it, the video shared to
     * Telegram and TikTok switched back to. Back from the video, then from the profile, leaves
     * TikTok instead of landing on For You.
     */
    @Test public void sharingTheVideoAndComingBackKeepsTheWatch() {
        handOver();
        ActivityController<Page> profile = page();
        profile.pause();
        ActivityController<Page> video = page();
        profile.stop();

        video.pause().stop();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(40));
        video.start().resume();
        assertTrue(LinkReturn.armed());

        taskSize = 3;
        close(video);
        assertFalse(video.get().movedBack);
        profile.start().resume();
        taskSize = 2;
        close(profile);
        assertTrue(profile.get().movedBack);
    }

    @Test public void aLongTripOutDropsTheWatch() {
        handOver();
        ActivityController<Page> page = page();
        page.pause().stop();
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(LinkReturn.AWAY_MS + 1_000));
        page.start().resume();
        assertFalse(LinkReturn.armed());

        close(page);
        assertFalse(page.get().movedBack);
    }

    /** TikTok's icon can bring the feed back over the link's page: Back is TikTok's own from there. */
    @Test public void comingBackToTheMainScreenDropsTheWatch() {
        handOver();
        ActivityController<Page> page = page();
        page.pause().stop();
        Main main = main().get();
        assertFalse(main.movedBack);
        assertFalse(LinkReturn.armed());
    }

    @Test public void aPageAfterTheWindowIsNotTheLinks() {
        handOver();
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(Duration.ofMillis(LinkReturn.PAGE_WINDOW_MS + 1_000));
        ActivityController<Page> page = page();
        assertFalse(LinkReturn.armed());

        close(page);
        assertFalse(page.get().movedBack);
    }

    @Test public void theHandedOverInstanceTakesNoPart() {
        ActivityController<Main> handed = Robolectric.buildActivity(Main.class).create();
        handed.get().finish();
        LinkReturn.arm(handed.get(), SENDER_TASK);
        ActivityController<Page> page = page();
        taskSize = -1;
        close(page);

        handed.start().resume();
        assertFalse(handed.get().movedBack);
        assertTrue(LinkReturn.armed());

        Main main = main().get();
        assertTrue(main.movedBack);
    }

    @Test public void nothingHappensUnarmed() {
        ActivityController<Page> page = page();
        close(page);
        assertFalse(page.get().movedBack);
    }
}

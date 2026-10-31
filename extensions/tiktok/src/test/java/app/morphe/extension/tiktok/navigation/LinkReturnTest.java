package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;

/** Back from a handed-over link's page goes back to the app the link came from (#276). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LinkReturnTest {
    private int taskSize;

    /** Stands in for TikTok's MainActivity: the handed-over instance and the one in TikTok's task. */
    public static class Main extends Activity {
        boolean movedBack;

        @Override public boolean moveTaskToBack(boolean nonRoot) {
            movedBack = true;
            return true;
        }
    }

    /** A page over the main screen, a profile or a video. */
    public static class Page extends Main {
    }

    @Before public void setUp() {
        taskSize = 2;
        LinkReturn.taskSizeForTests = activity -> taskSize < 0 ? null : taskSize;
    }

    @After public void tearDown() {
        LinkReturn.resetForTests();
    }

    private static Main handOver() {
        Main handed = Robolectric.buildActivity(Main.class).create().get();
        handed.finish();
        LinkReturn.arm(handed);
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

    @Test public void leavingTheAppDropsTheWatch() {
        handOver();
        ActivityController<Page> page = page();
        page.pause().stop();
        assertFalse(LinkReturn.armed());

        page.start().resume();
        close(page);
        assertFalse(page.get().movedBack);
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
        LinkReturn.arm(handed.get());
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

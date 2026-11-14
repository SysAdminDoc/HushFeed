package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * A video that stops at its end lets the screen time out, and the next one to play keeps it on
 * again (#414). TikTok holds the screen on for the whole feed, paused or not, so without this a
 * phone left on a finished video never turns its screen off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StoppedVideoScreenTest {
    private static final int KEEP = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** A feed the way TikTok leaves it: the window flag set, and a player view asking too. */
    public static class FeedActivity extends Activity {
        View player;

        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            getWindow().addFlags(KEEP);
            FrameLayout root = new FrameLayout(this);
            player = new View(this);
            player.setKeepScreenOn(true);
            root.addView(player);
            setContentView(root);
        }
    }

    /** A window that never asked to stay on, so nothing may be added to it. */
    public static class PlainActivity extends Activity {
    }

    @After public void tearDown() {
        StoppedVideoScreen.resetForTests();
        Settings.STOP_VIDEO_LOOPING.resetToDefault();
    }

    @Test public void withLoopingOnAFinishedVideoLeavesTheScreenAlone() {
        try (ActivityController<FeedActivity> feed = open(FeedActivity.class)) {
            StoppedVideoScreen.onPlayCompleted("first");
            idle();
            assertTrue("a looping video plays again, so TikTok's hold stays", held(feed.get()));
            assertTrue(feed.get().player.getKeepScreenOn());
        }
    }

    @Test public void aStoppedVideoLetsTheScreenSleepAndTheNextOneHoldsItAgain() {
        Settings.STOP_VIDEO_LOOPING.save(true);
        try (ActivityController<FeedActivity> feed = open(FeedActivity.class)) {
            StoppedVideoScreen.onPlayCompleted("first");
            idle();
            assertFalse("the window still keeps the screen on", held(feed.get()));
            assertFalse("the player view still keeps the screen on", feed.get().player.getKeepScreenOn());

            StoppedVideoScreen.onPlayProgress("first", 15_000, 15_000);
            idle();
            assertFalse("the last position again is not the video playing", held(feed.get()));

            StoppedVideoScreen.onPlayProgress("second", 250, 20_000);
            idle();
            assertTrue("the next video plays with the screen free to sleep", held(feed.get()));
            assertTrue(feed.get().player.getKeepScreenOn());
        }
    }

    @Test public void playingTheFinishedVideoAgainHoldsTheScreenAgain() {
        Settings.STOP_VIDEO_LOOPING.save(true);
        try (ActivityController<FeedActivity> feed = open(FeedActivity.class)) {
            StoppedVideoScreen.onPlayCompleted("first");
            idle();
            assertFalse(held(feed.get()));

            StoppedVideoScreen.onPlayProgress("first", 300, 15_000);
            idle();
            assertTrue("a replay from the start is the video playing", held(feed.get()));
            assertTrue(feed.get().player.getKeepScreenOn());
        }
    }

    @Test public void aVideoThatStartsBeforeTheReleaseRunsKeepsItsHold() {
        Settings.STOP_VIDEO_LOOPING.save(true);
        try (ActivityController<FeedActivity> feed = open(FeedActivity.class)) {
            StoppedVideoScreen.onPlayCompleted("first");
            StoppedVideoScreen.onPlayProgress("second", 100, 20_000);
            idle();
            assertTrue("auto advance moved on before the main thread caught up", held(feed.get()));
            assertTrue(feed.get().player.getKeepScreenOn());
        }
    }

    @Test public void aWindowThatNeverHeldTheScreenIsNotMadeTo() {
        Settings.STOP_VIDEO_LOOPING.save(true);
        try (ActivityController<PlainActivity> plain = open(PlainActivity.class)) {
            StoppedVideoScreen.onPlayCompleted("first");
            idle();
            StoppedVideoScreen.onPlayProgress("second", 100, 20_000);
            idle();
            assertEquals(0, plain.get().getWindow().getAttributes().flags & KEEP);
        }
    }

    @Test public void onlyAPositionWellBeforeTheEndCountsAsTheSameVideoPlaying() {
        assertTrue(StoppedVideoScreen.playingAgain("a", "b", 15_000, 15_000));
        assertTrue(StoppedVideoScreen.playingAgain("a", "a", 0, 15_000));
        assertFalse(StoppedVideoScreen.playingAgain("a", "a", 14_500, 15_000));
        assertFalse(StoppedVideoScreen.playingAgain("a", "a", 15_000, 15_000));
        assertFalse("no length known yet", StoppedVideoScreen.playingAgain("a", "a", 0, 0));
        assertFalse(StoppedVideoScreen.playingAgain("a", "a", -1, 15_000));
    }

    private static <T extends Activity> ActivityController<T> open(Class<T> type) {
        ActivityController<T> controller = Robolectric.buildActivity(type).setup().visible();
        Utils.setActivity(controller.get());
        return controller;
    }

    private static boolean held(Activity activity) {
        return (activity.getWindow().getAttributes().flags & KEEP) != 0;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}

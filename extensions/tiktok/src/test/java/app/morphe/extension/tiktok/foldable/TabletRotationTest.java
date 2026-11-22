/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.foldable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Activity;
import android.content.pm.ActivityInfo;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.HushfeedPause;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** A profile and a comment's picture turn with a tablet TikTok lets turn, and nowhere else (#490). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TabletRotationTest {
    private static final int PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    private static final int FOLLOWS = ActivityInfo.SCREEN_ORIENTATION_FULL_USER;

    /** Counts the orientation requests that reach it, and can refuse them like Android 8.0 does. */
    public static class Screen extends Activity {
        final AtomicInteger requests = new AtomicInteger();
        boolean refuses;

        @Override public void setRequestedOrientation(int requestedOrientation) {
            requests.incrementAndGet();
            if (refuses) throw new IllegalStateException("Only fullscreen opaque activities can request orientation");
            super.setRequestedOrientation(requestedOrientation);
        }
    }

    private Screen screen;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        TabletRotation.resetForTests();
        screen = Robolectric.buildActivity(Screen.class).create().get();
        screen.setRequestedOrientation(PORTRAIT);
        screen.requests.set(0);
    }

    @After public void tearDown() throws Exception {
        TabletRotation.resetForTests();
        pause(HushfeedPause.Reason.NONE);
    }

    private static void pause(HushfeedPause.Reason why) throws Exception {
        Method pause = HushfeedPause.class.getDeclaredMethod("pauseForTests", HushfeedPause.Reason.class);
        pause.setAccessible(true);
        pause.invoke(null, why);
    }

    private static void tabletTurns(boolean turns) {
        TabletRotation.landscape = activity -> turns;
    }

    @Test public void onATabletTikTokLetsTurnTheCommentPictureFollowsTheDevice() {
        tabletTurns(true);
        assertEquals(FOLLOWS, TabletRotation.orientation(screen, PORTRAIT));
    }

    @Test public void onATabletTikTokLetsTurnAProfileAsksToFollowTheDeviceOnce() {
        tabletTurns(true);
        TabletRotation.follow(screen);
        assertEquals(FOLLOWS, screen.getRequestedOrientation());
        TabletRotation.follow(screen);
        assertEquals("asked again when it already follows the device", 1, screen.requests.get());
    }

    @Test public void aPhoneOrAnUprightTabletKeepsWhatTikTokAsks() {
        tabletTurns(false);
        assertEquals(PORTRAIT, TabletRotation.orientation(screen, PORTRAIT));
        TabletRotation.follow(screen);
        assertEquals(PORTRAIT, screen.getRequestedOrientation());
        assertEquals(0, screen.requests.get());
    }

    @Test public void unpatchedTheBridgeSaysTheDeviceStaysUpright() {
        assertFalse(TabletRotation.turns(screen));
        assertEquals(PORTRAIT, TabletRotation.orientation(screen, PORTRAIT));
    }

    @Test public void pauseLeavesEveryScreenAsTikTokAsks() throws Exception {
        tabletTurns(true);
        pause(HushfeedPause.Reason.SWITCH);
        assertEquals(PORTRAIT, TabletRotation.orientation(screen, PORTRAIT));
        TabletRotation.follow(screen);
        assertEquals(PORTRAIT, screen.getRequestedOrientation());
        assertEquals(0, screen.requests.get());
    }

    @Test public void aCheckThatFailsLeavesTikToksOrientation() {
        TabletRotation.landscape = activity -> {
            throw new NoSuchMethodError("07A4.LIZ");
        };
        assertEquals(PORTRAIT, TabletRotation.orientation(screen, PORTRAIT));
        TabletRotation.follow(screen);
        assertEquals(PORTRAIT, TabletRotation.orientation(screen, PORTRAIT));
        assertEquals(PORTRAIT, screen.getRequestedOrientation());
    }

    @Test public void noScreenAsksForWhatItWasHanded() {
        tabletTurns(true);
        assertEquals(PORTRAIT, TabletRotation.orientation(null, PORTRAIT));
        TabletRotation.follow(null);
    }

    @Test public void aScreenThatRefusesKeepsTikToksOrientation() {
        tabletTurns(true);
        screen.refuses = true;
        TabletRotation.follow(screen);
        assertEquals(1, screen.requests.get());
        assertEquals(PORTRAIT, screen.getRequestedOrientation());
    }
}

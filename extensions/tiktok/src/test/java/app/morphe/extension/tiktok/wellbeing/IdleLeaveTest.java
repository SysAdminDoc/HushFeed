package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

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
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowToast;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Leave after no touch (discussion #266): once a TikTok screen goes the set minutes without a
 * touch, TikTok goes to the background with a toast saying why. Any touch starts the wait over,
 * and it is off until a number of minutes is set.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class IdleLeaveTest {
    public static final class HostActivity extends Activity {
        final List<Boolean> movedBack = new ArrayList<>();
        boolean canGoBack = true;

        @Override public boolean moveTaskToBack(boolean nonRoot) {
            movedBack.add(nonRoot);
            return canGoBack;
        }
    }

    private final List<Activity> left = new ArrayList<>();

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.blockAuthorEnabled = true;
        Settings.IDLE_LEAVE_MINUTES.resetToDefault();
        IdleLeave.resetForTests();
        IdleLeave.setLeaverForTests(left::add);
    }

    @After public void tearDown() {
        IdleLeave.resetForTests();
        Settings.IDLE_LEAVE_MINUTES.resetToDefault();
        SettingsStatus.blockAuthorEnabled = false;
        Utils.setActivity(null);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** A screen built, shown and holding the window focus, as TikTok's feed is. */
    private static ActivityController<HostActivity> shown() {
        ActivityController<HostActivity> controller =
                Robolectric.buildActivity(HostActivity.class).setup().windowFocusChanged(true);
        assertTrue("the test screen needs the window focus", controller.get().hasWindowFocus());
        return controller;
    }

    /** A screen in front, the way the lifecycle callback hands it in. */
    private static HostActivity front() {
        HostActivity activity = shown().get();
        IdleLeave.resumed(activity);
        return activity;
    }

    private static void idleFor(Duration time) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(time);
    }

    /** A finger on the screen, through the window callback the way the system delivers it. */
    private static void touch(Activity activity, int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 10f, 10f, 0);
        try {
            activity.getWindow().getCallback().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static void tap(Activity activity) {
        touch(activity, MotionEvent.ACTION_DOWN);
        touch(activity, MotionEvent.ACTION_UP);
    }

    @Test public void offByDefault() {
        HostActivity activity = front();
        idleFor(Duration.ofHours(5));
        assertEquals(List.of(), left);
        assertSame("off still wrapped the screen's window", activity, activity.getWindow().getCallback());
    }

    @Test public void tenMinutesWithNoTouchLeaves() {
        Settings.IDLE_LEAVE_MINUTES.save(10);
        HostActivity activity = front();
        idleFor(Duration.ofMinutes(10).minusSeconds(1));
        assertEquals("left before the wait ran out", List.of(), left);
        idleFor(Duration.ofSeconds(2));
        assertEquals(List.of(activity), left);
        idleFor(Duration.ofHours(1));
        assertEquals("a screen already sent away is left alone", List.of(activity), left);
    }

    @Test public void aTouchStartsTheWaitOver() {
        Settings.IDLE_LEAVE_MINUTES.save(10);
        HostActivity activity = front();
        idleFor(Duration.ofMinutes(9));
        tap(activity);
        idleFor(Duration.ofMinutes(9));
        assertEquals("the touch at 9 minutes didn't move the wait", List.of(), left);
        idleFor(Duration.ofMinutes(1).plusSeconds(1));
        assertEquals(List.of(activity), left);
    }

    @Test public void aHeldFingerNeverRunsTheWaitOut() {
        Settings.IDLE_LEAVE_MINUTES.save(10);
        HostActivity activity = front();
        idleFor(Duration.ofMinutes(1));
        touch(activity, MotionEvent.ACTION_DOWN);
        idleFor(Duration.ofMinutes(30));
        assertEquals("a press held for speed counted as no touch", List.of(), left);
        touch(activity, MotionEvent.ACTION_UP);
        idleFor(Duration.ofMinutes(10).minusSeconds(1));
        assertEquals("the lift starts a fresh wait", List.of(), left);
        idleFor(Duration.ofSeconds(2));
        assertEquals(List.of(activity), left);
    }

    /** A panel taking the focus mid-press doesn't lose the press: the lift still comes here. */
    @Test public void aHeldFingerStaysHeldAcrossAFocusChange() {
        Settings.IDLE_LEAVE_MINUTES.save(10);
        ActivityController<HostActivity> controller = shown();
        HostActivity activity = controller.get();
        IdleLeave.resumed(activity);
        touch(activity, MotionEvent.ACTION_DOWN);
        controller.windowFocusChanged(false);
        idleFor(Duration.ofMinutes(1));
        controller.windowFocusChanged(true);
        idleFor(Duration.ofMinutes(30));
        assertEquals("the press counted as lifted once the focus came back", List.of(), left);
        touch(activity, MotionEvent.ACTION_UP);
        idleFor(Duration.ofMinutes(10).plusSeconds(1));
        assertEquals(List.of(activity), left);
    }

    /** A keyboard, a page turner or a mouse wheel (DeX) is someone there as much as a finger. */
    @Test public void aKeyOrAWheelStartsTheWaitOver() {
        Settings.IDLE_LEAVE_MINUTES.save(10);
        HostActivity activity = front();
        idleFor(Duration.ofMinutes(9));
        activity.getWindow().getCallback().dispatchKeyEvent(
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN));
        idleFor(Duration.ofMinutes(9));
        assertEquals("the key at 9 minutes didn't move the wait", List.of(), left);
        long now = SystemClock.uptimeMillis();
        MotionEvent wheel = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 10f, 10f, 0);
        try {
            activity.getWindow().getCallback().dispatchGenericMotionEvent(wheel);
        } finally {
            wheel.recycle();
        }
        idleFor(Duration.ofMinutes(9));
        assertEquals("the wheel at 18 minutes didn't move the wait", List.of(), left);
        idleFor(Duration.ofMinutes(1).plusSeconds(1));
        assertEquals(List.of(activity), left);
    }

    /** The share sheet and the comment menu have windows of their own, out of this one's sight. */
    @Test public void aPanelWithItsOwnWindowHoldsTheWait() {
        Settings.IDLE_LEAVE_MINUTES.save(10);
        ActivityController<HostActivity> controller = shown();
        HostActivity activity = controller.get();
        IdleLeave.resumed(activity);
        idleFor(Duration.ofMinutes(5));
        controller.windowFocusChanged(false);
        idleFor(Duration.ofMinutes(45));
        assertEquals("someone reading in a sheet was sent away", List.of(), left);
        controller.windowFocusChanged(true);
        idleFor(Duration.ofMinutes(9));
        assertEquals("closing the sheet starts the wait over", List.of(), left);
        idleFor(Duration.ofMinutes(1).plusSeconds(1));
        assertEquals(List.of(activity), left);
    }

    @Test public void aScreenThatGoesBehindStopsTheWait() {
        Settings.IDLE_LEAVE_MINUTES.save(10);
        HostActivity activity = front();
        idleFor(Duration.ofMinutes(5));
        IdleLeave.paused(activity);
        idleFor(Duration.ofHours(1));
        assertEquals(List.of(), left);
    }

    @Test public void turningItOffMidWaitCancelsIt() {
        Settings.IDLE_LEAVE_MINUTES.save(10);
        front();
        idleFor(Duration.ofMinutes(5));
        Settings.IDLE_LEAVE_MINUTES.save(0);
        idleFor(Duration.ofHours(1));
        assertEquals(List.of(), left);
    }

    /** Set in the settings, which are a TikTok screen too: the wait starts with the screen after. */
    @Test public void turningItOnStartsWithTheNextScreen() {
        HostActivity settings = front();
        idleFor(Duration.ofMinutes(3));
        Settings.IDLE_LEAVE_MINUTES.save(10);
        tap(settings);
        idleFor(Duration.ofMinutes(30));
        assertEquals("the settings screen, opened while it was off, ran a wait", List.of(), left);
        IdleLeave.paused(settings);
        HostActivity back = front();
        idleFor(Duration.ofMinutes(10).minusSeconds(1));
        assertEquals(List.of(), left);
        idleFor(Duration.ofSeconds(2));
        assertEquals(List.of(back), left);
    }

    @Test public void withoutTheScreenTimePatchNothingRuns() {
        SettingsStatus.blockAuthorEnabled = false;
        Settings.IDLE_LEAVE_MINUTES.save(10);
        front();
        idleFor(Duration.ofHours(1));
        assertEquals(List.of(), left);
    }

    /** The patched MainActivity.onCreate hands in its activity, and every screen after it counts. */
    @Test public void theMainActivityFollowsEveryScreen() {
        Settings.IDLE_LEAVE_MINUTES.save(10);
        ActivityController<HostActivity> main = Robolectric.buildActivity(HostActivity.class).create();
        IdleLeave.onMainActivityCreate(main.get());
        main.start().resume().visible().windowFocusChanged(true);
        idleFor(Duration.ofMinutes(5));
        main.pause();
        HostActivity video = shown().get();
        idleFor(Duration.ofMinutes(9));
        assertEquals("a screen coming to the front starts the wait", List.of(), left);
        idleFor(Duration.ofMinutes(1).plusSeconds(1));
        assertEquals(List.of(video), left);
    }

    /** The real leave: a toast saying why, background play told to stop, and the task sent back. */
    @Test public void leavingToastsStopsThePlayerAndGoesBack() {
        IdleLeave.resetForTests();
        Settings.IDLE_LEAVE_MINUTES.save(1);
        HostActivity activity = front();
        idleFor(Duration.ofMinutes(1).plusSeconds(1));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(List.of(true), activity.movedBack);
        assertEquals("Left TikTok after no touch", ShadowToast.getTextOfLatestToast());
        ShadowAudioManager audio = Shadows.shadowOf(
                (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE));
        ShadowAudioManager.AudioFocusRequest request = audio.getLastAudioFocusRequest();
        assertNotNull("background play was never told to stop", request);
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, request.durationHint);
        assertEquals("the focus wasn't handed straight back",
                request.listener, audio.getLastAbandonedAudioFocusListener());
    }

    /**
     * A task that can't go back leaves TikTok in front: no toast, no silenced player, and the
     * screen is watched still, so the next wait tries again.
     */
    @Test public void aScreenThatCannotGoBackIsWatchedStill() {
        IdleLeave.resetForTests();
        Settings.IDLE_LEAVE_MINUTES.save(1);
        HostActivity activity = front();
        activity.canGoBack = false;
        idleFor(Duration.ofMinutes(1).plusSeconds(1));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(List.of(true), activity.movedBack);
        assertNull("a toast said TikTok left while it stayed", ShadowToast.getLatestToast());
        assertNull("the player was told to stop while TikTok stayed in front", Shadows.shadowOf(
                (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE)).getLastAudioFocusRequest());
        idleFor(Duration.ofMinutes(1));
        assertEquals("the wait didn't start again", List.of(true, true), activity.movedBack);
    }
}

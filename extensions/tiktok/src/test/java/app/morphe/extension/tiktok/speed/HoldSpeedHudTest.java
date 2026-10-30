package app.morphe.extension.tiktok.speed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/**
 * Slide to change the hold speed (#248): the steps a slide lands on and how far apart they sit,
 * the pull toward TikTok's lock, the one bubble and everything that takes it down, the speed the
 * next hold starts at, and that the slid speed is what the lock and TikTok's words get.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HoldSpeedHudTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final float[] GRID = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.5f, 2.75f, 3f};

    private final List<Float> applied = new ArrayList<>();
    private final Object component = new Object();
    private ActivityController<Activity> owner;
    private float density;

    @Before
    public void setUp() {
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        Utils.setActivity(owner.get());
        density = owner.get().getResources().getDisplayMetrics().density;
        SettingsStatus.holdSpeedSlideEnabled = true;
        Settings.HOLD_SPEED_SLIDE.save(true);
        Settings.HOLD_SPEED.save("2");
        HoldSpeedHud.applierForTests = (gesture, speed) -> {
            assertSame(component, gesture);
            applied.add(speed);
        };
        forget();
    }

    @After
    public void tearDown() {
        forget();
        HoldSpeedHud.applierForTests = null;
        SettingsStatus.holdSpeedSlideEnabled = false;
        Settings.HOLD_SPEED_SLIDE.resetToDefault();
        Settings.HOLD_SPEED.resetToDefault();
        Settings.CUSTOM_SPEEDS.resetToDefault();
        Settings.DEFAULT_SPEED_ENABLED.resetToDefault();
        Settings.DEFAULT_SPEED.resetToDefault();
        Utils.setActivity(null);
        owner.pause().stop().destroy();
    }

    private static void forget() {
        HoldSpeedHud.onHoldRelease();
        ReflectionHelpers.setStaticField(HoldSpeedHud.class, "slidSpeed", Float.NaN);
        ReflectionHelpers.setStaticField(HoldSpeedHud.class, "slidFrom", null);
        ReflectionHelpers.setStaticField(HoldSpeedHud.class, "heldVideo", "");
        ReflectionHelpers.setStaticField(PlaybackSpeedPatch.class, "onScreen", null);
    }

    /** Moves the finger to {@code dx} dp right of 100 px and {@code dy} dp below 500 px. */
    private void move(float dx, float dy) {
        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE,
                100 + dx * density, 500 + dy * density, 0);
        HoldSpeedHud.onHoldTouch(event);
        event.recycle();
    }

    private void move(float dx) {
        move(dx, 0);
    }

    /** Holds, marks the finger at 100 px, and slides {@code dp} sideways. */
    private void holdAndSlide(float dp) {
        HoldSpeedHud.onHoldPress(component);
        move(0);
        move(dp);
    }

    private int bubbles() {
        ViewGroup content = owner.get().findViewById(android.R.id.content);
        int count = 0;
        for (int i = 0; i < content.getChildCount(); i++) {
            if (content.getChildAt(i) instanceof HoldSpeedHud.Bubble) count++;
        }
        return count;
    }

    private static HoldSpeedHud.Bubble shown() {
        return (HoldSpeedHud.Bubble) HoldSpeedHud.bubble();
    }

    private static String label() {
        return shown().label();
    }

    @Test
    public void theStepsAreTheSpeedMenusWithTheStartAmongThem() {
        assertArrayEquals(GRID, HoldSpeedHud.stepsFor(2f, ""), 0f);
        assertArrayEquals(new float[] {1f, 1.5f, 2f, 3f}, HoldSpeedHud.stepsFor(2f, "1, 1.5, 3"), 0f);
        assertArrayEquals(new float[] {1f, 1.5f, 3f}, HoldSpeedHud.stepsFor(1.5f, "3,1,1.5"), 0f);
        float[] offGrid = HoldSpeedHud.stepsFor(1.3f, "");
        assertEquals(GRID.length + 1, offGrid.length);
        assertEquals(1.3f, offGrid[4], 0f);
        // A list the speed menu refuses leaves the menu on TikTok's speeds; the slide takes the grid.
        assertArrayEquals(GRID, HoldSpeedHud.stepsFor(2f, "fast"), 0f);
        assertArrayEquals(GRID, HoldSpeedHud.stepsFor(2f, "1,9"), 0f);
    }

    @Test
    public void aHoldShowsOneBubbleAtTheHoldSpeed() {
        HoldSpeedHud.onHoldPress(component);
        HoldSpeedHud.onHoldPress(component);
        assertEquals(1, bubbles());
        assertEquals("2x", label());
        assertFalse(shown().locked());
        Settings.HOLD_SPEED.save("1.5");
        HoldSpeedHud.onHoldPress(component);
        assertEquals(1, bubbles());
        assertEquals("1.5x", label());
    }

    @Test
    public void aSlideStepsOnceItClearsTheDeadZoneAndEachStepHolds() {
        holdAndSlide(DEAD_ZONE + 1);
        assertTrue("inside the dead zone and the first step nothing plays", applied.isEmpty());
        move(DEAD_ZONE + STEP + 2);
        assertEquals(List.of(2.25f), applied);
        assertEquals("2.25x", label());
        move(DEAD_ZONE + 2 * STEP + 4);
        assertEquals(List.of(2.25f, 2.5f), applied);
        // Back a little from the step just taken changes nothing; a whole step back does.
        move(DEAD_ZONE + STEP + 10);
        assertEquals(List.of(2.25f, 2.5f), applied);
        move(DEAD_ZONE + STEP - 2);
        assertEquals(List.of(2.25f, 2.5f, 2.25f), applied);
        assertEquals("2.25x", label());
        // The end of the steps holds the speed, and coming back from past it steps at once.
        move(2000);
        assertEquals(3f, applied.get(applied.size() - 1), 0f);
        move(2000 - STEP - 1);
        assertEquals(2.75f, applied.get(applied.size() - 1), 0f);
    }

    @Test
    public void slidingLeftSlowsItDown() {
        holdAndSlide(-(DEAD_ZONE + STEP + 2));
        assertEquals(List.of(1.75f), applied);
        assertEquals("1.75x", label());
    }

    @Test
    public void slidingPlaysTheNewSpeedAndTheLockWordsAndNextHoldGetIt() {
        holdAndSlide(DEAD_ZONE + STEP + 2);
        assertEquals(List.of(2.25f), applied);
        assertEquals(2.25f, PlaybackSpeedPatch.holdSpeed(2f), 0f);
        assertEquals("Speed: 2.25x", PlaybackSpeedPatch.holdSpeedText("Speed: 2x"));
        // Letting go takes the bubble down; a pull-down lock on that release still gets the slid speed.
        HoldSpeedHud.onHoldRelease();
        assertEquals(0, bubbles());
        assertEquals(2.25f, PlaybackSpeedPatch.holdSpeed(2f), 0f);
        // The next hold starts where the last one was slid to, and steps from there.
        HoldSpeedHud.onHoldPress(component);
        assertEquals("2.25x", label());
        assertEquals(2.25f, PlaybackSpeedPatch.holdSpeed(2f), 0f);
        move(0);
        move(-(DEAD_ZONE + STEP + 2));
        assertEquals(List.of(2.25f, 2f), applied);
        // Changing the hold speed row starts over at it.
        HoldSpeedHud.onHoldRelease();
        Settings.HOLD_SPEED.save("1.5");
        assertEquals(1.5f, PlaybackSpeedPatch.holdSpeed(2f), 0f);
        HoldSpeedHud.onHoldPress(component);
        assertEquals("1.5x", label());
    }

    @Test
    public void aCustomSpeedMenuIsWhatTheSlideStepsThrough() {
        Settings.CUSTOM_SPEEDS.save("1,1.5,3");
        holdAndSlide(DEAD_ZONE + STEP + 2);
        assertEquals(List.of(3f), applied);
        move(DEAD_ZONE + 3 * STEP + 4);
        assertEquals(List.of(3f), applied);
        move(-(DEAD_ZONE + 2 * STEP + 4));
        assertEquals(1f, applied.get(applied.size() - 1), 0f);
    }

    @Test
    public void aHoldThatGoesDownFirstIsAPullAndDoesNotSlide() {
        HoldSpeedHud.onHoldPress(component);
        move(0, 0);
        move(5, AXIS_LOCK + 2);
        move(DEAD_ZONE + 3 * STEP, AXIS_LOCK + 2);
        assertTrue(applied.isEmpty());
        // A finger that goes sideways first slides even when it drifts down after.
        HoldSpeedHud.onHoldRelease();
        HoldSpeedHud.onHoldPress(component);
        move(0, 0);
        move(DEAD_ZONE + 1, 4);
        move(DEAD_ZONE + STEP + 2, AXIS_LOCK + 10);
        assertEquals(List.of(2.25f), applied);
    }

    @Test
    public void pulledDownToLockTheSpeedStaysAndThePadlockLights() {
        holdAndSlide(DEAD_ZONE + STEP + 2);
        assertEquals(List.of(2.25f), applied);
        HoldSpeedHud.onPullDown(1);
        assertTrue(shown().locked());
        move(DEAD_ZONE + 4 * STEP);
        assertEquals(List.of(2.25f), applied);
        // Back up, the slide goes on from where the finger is now.
        HoldSpeedHud.onPullDown(0);
        assertFalse(shown().locked());
        move(DEAD_ZONE + 4 * STEP);
        assertEquals(List.of(2.25f), applied);
        move(2 * DEAD_ZONE + 5 * STEP + 2);
        assertEquals(List.of(2.25f, 2.5f), applied);
        // A pull that ended with the lock leaves the next hold unlocked.
        HoldSpeedHud.onPullDown(1);
        HoldSpeedHud.onHoldRelease();
        HoldSpeedHud.onHoldPress(component);
        assertFalse(shown().locked());
        move(0);
        move(DEAD_ZONE + STEP + 2);
        assertEquals(List.of(2.25f, 2.5f, 2.75f), applied);
    }

    @Test
    public void movesAfterLettingGoDoNothing() {
        holdAndSlide(DEAD_ZONE + STEP + 2);
        HoldSpeedHud.onHoldRelease();
        move(DEAD_ZONE + 4 * STEP);
        assertEquals(List.of(2.25f), applied);
        assertNull(HoldSpeedHud.bubble());
    }

    @Test
    public void anotherVideoOnScreenEndsTheHold() {
        PlaybackSpeedPatch.onPlayerProgress(new Object(), "a");
        holdAndSlide(DEAD_ZONE + STEP + 2);
        PlaybackSpeedPatch.onPlayerProgress(new Object(), "a");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, bubbles());
        PlaybackSpeedPatch.onPlayerProgress(new Object(), "b");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, bubbles());
        move(DEAD_ZONE + 4 * STEP);
        assertEquals(List.of(2.25f), applied);
        // The speed slid to stays for the next hold, on this video or another.
        assertEquals(2.25f, PlaybackSpeedPatch.holdSpeed(2f), 0f);
    }

    @Test
    public void losingWindowFocusTakesTheBubbleDown() {
        holdAndSlide(DEAD_ZONE + STEP + 2);
        View shown = HoldSpeedHud.bubble();
        assertNotNull(shown);
        shown.onWindowFocusChanged(false);
        assertEquals(0, bubbles());
        move(DEAD_ZONE + 4 * STEP);
        assertEquals(List.of(2.25f), applied);
    }

    @Test
    public void pauseEndsTheHoldAndHandsBackTikToksSpeed() {
        holdAndSlide(DEAD_ZONE + STEP + 2);
        PausedProcess.set(true);
        try {
            move(DEAD_ZONE + 4 * STEP);
            assertEquals(0, bubbles());
            assertEquals(List.of(2.25f), applied);
            assertTrue(Float.isNaN(HoldSpeedHud.slidSpeed()));
        } finally {
            PausedProcess.set(false);
        }
    }

    @Test
    public void switchingItOffMidHoldEndsIt() {
        holdAndSlide(DEAD_ZONE + STEP + 2);
        Settings.HOLD_SPEED_SLIDE.save(false);
        move(DEAD_ZONE + 4 * STEP);
        assertEquals(0, bubbles());
        assertEquals(2f, PlaybackSpeedPatch.holdSpeed(2f), 0f);
    }

    @Test
    public void offThereIsNoBubbleAndTikToksBannersShow() {
        Settings.HOLD_SPEED_SLIDE.save(false);
        holdAndSlide(DEAD_ZONE + 4 * STEP);
        assertEquals(0, bubbles());
        assertTrue(applied.isEmpty());
        assertEquals(2f, PlaybackSpeedPatch.holdSpeed(2f), 0f);
        // TikTok's banner shows while the switch is off; on, its parts are hidden for the bubble,
        // and a part TikTok itself left hidden stays hidden when the switch goes off again.
        android.widget.LinearLayout banner = new android.widget.LinearLayout(owner.get());
        android.widget.TextView label = new android.widget.TextView(owner.get());
        android.widget.ImageView icon = new android.widget.ImageView(owner.get());
        icon.setVisibility(View.GONE);
        banner.addView(label);
        banner.addView(icon);
        PlaybackSpeedPatch.holdSpeedBanner(banner);
        assertEquals(View.VISIBLE, label.getVisibility());
        Settings.HOLD_SPEED_SLIDE.save(true);
        PlaybackSpeedPatch.holdSpeedBanner(banner);
        assertEquals(View.INVISIBLE, label.getVisibility());
        assertEquals(View.GONE, icon.getVisibility());
        Settings.HOLD_SPEED_SLIDE.save(false);
        PlaybackSpeedPatch.holdSpeedBanner(banner);
        assertEquals(View.VISIBLE, label.getVisibility());
        assertEquals(View.GONE, icon.getVisibility());
    }

    @Test
    public void theLockBannerGivesWayToTheBubble() {
        // TikTok's show method makes the lock banner visible; the hook at its end has the last word.
        android.widget.LinearLayout lockBanner = new android.widget.LinearLayout(owner.get());
        lockBanner.setVisibility(View.VISIBLE);
        PlaybackSpeedPatch.holdLockBanner(lockBanner);
        assertEquals(View.INVISIBLE, lockBanner.getVisibility());
        Settings.HOLD_SPEED_SLIDE.save(false);
        lockBanner.setVisibility(View.VISIBLE);
        PlaybackSpeedPatch.holdLockBanner(lockBanner);
        assertEquals(View.VISIBLE, lockBanner.getVisibility());
        PlaybackSpeedPatch.holdLockBanner(null);
    }

    @Test
    public void undoingTheLockPlaysTheDefaultSpeedWhenOneIsOn() {
        assertEquals(1f, PlaybackSpeedPatch.holdUnlockSpeed(1f), 0f);
        Settings.DEFAULT_SPEED_ENABLED.save(true);
        Settings.DEFAULT_SPEED.save("1.25");
        assertEquals(1.25f, PlaybackSpeedPatch.holdUnlockSpeed(1f), 0f);
        PausedProcess.set(true);
        try {
            assertEquals(1f, PlaybackSpeedPatch.holdUnlockSpeed(1f), 0f);
        } finally {
            PausedProcess.set(false);
        }
        Settings.DEFAULT_SPEED.save("quick");
        assertEquals(1f, PlaybackSpeedPatch.holdUnlockSpeed(1f), 0f);
        Settings.DEFAULT_SPEED.save("9");
        assertEquals(1f, PlaybackSpeedPatch.holdUnlockSpeed(1f), 0f);
    }

    @Test
    public void anUnpatchedBuildHasNoSlide() {
        SettingsStatus.holdSpeedSlideEnabled = false;
        holdAndSlide(DEAD_ZONE + 4 * STEP);
        assertEquals(0, bubbles());
        assertFalse(HoldSpeedHud.enabled());
        assertTrue(applied.isEmpty());
    }

    private static final float DEAD_ZONE = HoldSpeedHud.DEAD_ZONE_DP;
    private static final float STEP = HoldSpeedHud.STEP_DP;
    private static final float AXIS_LOCK = HoldSpeedHud.AXIS_LOCK_DP;
}

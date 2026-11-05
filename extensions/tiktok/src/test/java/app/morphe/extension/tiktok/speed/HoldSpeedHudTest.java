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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.PlaybackPreferenceCategory;
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
import org.robolectric.annotation.GraphicsMode;
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
    private final List<Float> armed = new ArrayList<>();
    private final List<Boolean> pulls = new ArrayList<>();
    private boolean tikTokPulled;
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
        HoldSpeedHud.lockArmerForTests = (gesture, speed) -> {
            assertSame(component, gesture);
            armed.add(speed);
        };
        HoldSpeedHud.pullerForTests = (gesture, down) -> {
            assertSame(component, gesture);
            pulls.add(down);
            tikTokPull(down);
        };
        forget();
    }

    /**
     * TikTok's pull-down lock state as the touch listener and the extension's bridge both reach
     * it: the hook at its top answers first, and the signal goes out only when the state changes.
     */
    private void tikTokPull(boolean down) {
        boolean answer = HoldSpeedHud.pullState(down ? 1 : 0);
        if (answer == tikTokPulled) return;
        tikTokPulled = answer;
        HoldSpeedHud.onPullDown(answer ? 1 : 0);
    }

    @After
    public void tearDown() {
        forget();
        HoldSpeedHud.applierForTests = null;
        HoldSpeedHud.lockArmerForTests = null;
        HoldSpeedHud.pullerForTests = null;
        SettingsStatus.holdSpeedSlideEnabled = false;
        Settings.HOLD_SPEED_SLIDE.resetToDefault();
        Settings.HOLD_SPEED_HUD_STYLE.resetToDefault();
        Settings.HOLD_SPEED_SLIDE_AXIS.resetToDefault();
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

    /**
     * #248, a later report: a video opened from search, a profile or a chat plays in an activity
     * of its own over the main one, and the bubble went on the main one's window behind it.
     */
    @Test
    public void theBubbleGoesOnTheActivityInFrontOfTheMainOne() {
        try (ActivityController<Activity> detail = Robolectric.buildActivity(Activity.class).setup().visible()) {
            HoldSpeedHud.onHoldPress(component);
            assertEquals("nothing on the main activity behind", 0, bubbles());
            ViewGroup front = detail.get().findViewById(android.R.id.content);
            assertSame(front, HoldSpeedHud.bubble().getParent());
            HoldSpeedHud.onHoldRelease();
            assertEquals("the release takes it off the front one", 0, front.getChildCount());
        }
        // Back on the feed once that one is gone.
        HoldSpeedHud.onHoldPress(component);
        assertEquals(1, bubbles());
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

    /** Holds a video that plays at the hold speed already, which TikTok counts as locked, and slides. */
    private void holdLockedAndSlide(float dp) {
        HoldSpeedHud.onHoldPress(component);
        HoldSpeedHud.onHoldFrom(2f);
        move(0);
        move(dp);
    }

    @Test
    public void aLockedVideoShowsThePadlockClosedAndAPullOpensIt() {
        HoldSpeedHud.onHoldPress(component);
        assertFalse(shown().locked());
        HoldSpeedHud.onHoldFrom(2f);
        assertTrue(shown().locked());
        HoldSpeedHud.onPullDown(1);
        assertFalse(shown().locked());
        HoldSpeedHud.onPullDown(0);
        assertTrue(shown().locked());
    }

    @Test
    public void anUnlockedVideoShowsThePadlockOpenUntilAPull() {
        HoldSpeedHud.onHoldPress(component);
        HoldSpeedHud.onHoldFrom(1f);
        assertFalse(shown().locked());
        HoldSpeedHud.onPullDown(1);
        assertTrue(shown().locked());
    }

    @Test
    public void lettingGoOfALockedHoldOnANewSpeedLocksThatSpeed() {
        holdLockedAndSlide(DEAD_ZONE + STEP + 2);
        assertEquals(List.of(2.25f), applied);
        assertEquals(List.of(), armed);
        HoldSpeedHud.onHoldRelease();
        assertEquals(List.of(2.25f), armed);
    }

    @Test
    public void lettingGoOfAnUnlockedHoldLocksNothing() {
        HoldSpeedHud.onHoldPress(component);
        HoldSpeedHud.onHoldFrom(1f);
        move(0);
        move(DEAD_ZONE + STEP + 2);
        HoldSpeedHud.onHoldRelease();
        assertEquals(List.of(), armed);
    }

    @Test
    public void aPullOnALockedHoldStaysTikToksUnlock() {
        holdLockedAndSlide(DEAD_ZONE + STEP + 2);
        HoldSpeedHud.onPullDown(1);
        HoldSpeedHud.onHoldRelease();
        assertEquals(List.of(), armed);
    }

    @Test
    public void aLockedHoldLetGoOnTheSpeedItStartedAtChangesNothing() {
        holdLockedAndSlide(DEAD_ZONE + STEP + 2);
        move(DEAD_ZONE - 2);
        assertEquals(List.of(2.25f, 2f), applied);
        HoldSpeedHud.onHoldRelease();
        assertEquals(List.of(), armed);
    }

    @Test
    public void aLockedHoldThatNeverSlidChangesNothing() {
        HoldSpeedHud.onHoldPress(component);
        HoldSpeedHud.onHoldFrom(2f);
        HoldSpeedHud.onHoldRelease();
        assertEquals(List.of(), armed);
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

    @Test
    public void theCircleStyleFollowsTheSameHoldInASmallDial() {
        assertEquals(HoldSpeedHud.PILL, Settings.HOLD_SPEED_HUD_STYLE.defaultValue);
        HoldSpeedHud.onHoldPress(component);
        assertFalse("the pill stays the default", shown() instanceof HoldSpeedHud.Circle);

        Settings.HOLD_SPEED_HUD_STYLE.save(HoldSpeedHud.CIRCLE);
        HoldSpeedHud.onHoldPress(component);
        assertEquals(1, bubbles());
        assertTrue(shown() instanceof HoldSpeedHud.Circle);
        assertEquals("2x", label());
        shown().measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int diameter = Math.round(HoldSpeedHud.Circle.DIAMETER_DP * density);
        assertEquals(diameter, shown().getMeasuredWidth());
        assertEquals(diameter, shown().getMeasuredHeight());
        assertFalse(shown().locked());
        HoldSpeedHud.onHoldFrom(2f);
        assertTrue("a locked video closes the dial's padlock", shown().locked());

        move(0);
        move(DEAD_ZONE + STEP + 2);
        assertEquals(List.of(2.25f), applied);
        assertEquals("2.25x", label());
        HoldSpeedHud.onHoldRelease();
        assertEquals(0, bubbles());

        // A style the row doesn't offer, as from an old backup, draws the pill.
        Settings.HOLD_SPEED_HUD_STYLE.save("square");
        HoldSpeedHud.onHoldPress(component);
        assertFalse(shown() instanceof HoldSpeedHud.Circle);
    }

    @Test
    public void upAndDownStepsTheSpeedAndASlideToTheSideArmsTikToksLock() {
        assertEquals(HoldSpeedHud.SIDEWAYS, Settings.HOLD_SPEED_SLIDE_AXIS.defaultValue);
        Settings.HOLD_SPEED_SLIDE_AXIS.save(HoldSpeedHud.UP_DOWN);
        HoldSpeedHud.onHoldPress(component);
        move(0, 0);
        // Up is faster, by the same dead zone and steps as the sideways slide.
        move(0, -(DEAD_ZONE + 1));
        assertTrue(applied.isEmpty());
        move(0, -(DEAD_ZONE + STEP + 2));
        assertEquals(List.of(2.25f), applied);
        move(0, -(DEAD_ZONE + 2 * STEP + 4));
        assertEquals(List.of(2.25f, 2.5f), applied);
        move(0, -(DEAD_ZONE + STEP - 2));
        assertEquals(List.of(2.25f, 2.5f, 2.25f), applied);
        // A finger going down is slowing the video, so TikTok's own pull doesn't arm anything.
        tikTokPull(true);
        assertFalse(tikTokPulled);
        assertFalse(shown().locked());
        move(0, STEP);
        assertEquals(1.75f, applied.get(applied.size() - 1), 0f);
        int slid = applied.size();
        // Sideways from where the hold began arms it, through TikTok's own state, either way.
        move(SIDE_LOCK - 2, STEP);
        assertTrue(pulls.isEmpty());
        move(-(SIDE_LOCK + 2), STEP);
        assertEquals(List.of(true), pulls);
        assertTrue(tikTokPulled);
        assertTrue("the padlock closes", shown().locked());
        // Armed, the speed stays put, and resting between the two distances keeps it armed.
        move(-(SIDE_LOCK - 4), -(DEAD_ZONE + 6 * STEP));
        assertEquals(slid, applied.size());
        assertEquals(List.of(true), pulls);
        tikTokPull(false);
        assertTrue("TikTok coming back up doesn't let go of it", tikTokPulled);
        // Back near the middle lets go, and the slide goes on from where the finger is.
        move(-(SIDE_UNLOCK - 2), 0);
        assertEquals(List.of(true, false), pulls);
        assertFalse(tikTokPulled);
        assertFalse(shown().locked());
        move(-(SIDE_UNLOCK - 2), -(DEAD_ZONE + STEP + 2));
        assertEquals(2f, applied.get(applied.size() - 1), 0f);
        // The release hands the arm back, and the next hold starts unarmed.
        move(SIDE_LOCK + 10, 0);
        assertEquals(List.of(true, false, true), pulls);
        HoldSpeedHud.onHoldRelease();
        tikTokPulled = false;
        HoldSpeedHud.onHoldPress(component);
        move(0, 0);
        move(SIDE_LOCK - 2, 0);
        assertEquals(List.of(true, false, true), pulls);
    }

    @Test
    public void upAndDownOnALockedVideoUnlocksWithTheSideSlideAsAPullWould() {
        Settings.HOLD_SPEED_SLIDE_AXIS.save(HoldSpeedHud.UP_DOWN);
        HoldSpeedHud.onHoldPress(component);
        HoldSpeedHud.onHoldFrom(2f);
        assertTrue(shown().locked());
        move(0, 0);
        move(SIDE_LOCK + 2, 0);
        assertEquals(List.of(true), pulls);
        assertFalse("the slide opens the padlock, TikTok's unlock", shown().locked());
        HoldSpeedHud.onHoldRelease();
        assertTrue("an unlock locks no new speed", armed.isEmpty());
    }

    @Test
    public void sidewaysLeavesTikToksPullAloneAndTakesNoSideLock() {
        HoldSpeedHud.onHoldPress(component);
        move(0, 0);
        move(SIDE_LOCK + STEP, 0);
        assertFalse(applied.isEmpty());
        assertTrue("a sideways slide is the speed here", pulls.isEmpty());
        assertTrue(HoldSpeedHud.pullState(1));
        assertFalse(HoldSpeedHud.pullState(0));
        // With up and down chosen, a finger that isn't held keeps TikTok's answer too.
        HoldSpeedHud.onHoldRelease();
        Settings.HOLD_SPEED_SLIDE_AXIS.save(HoldSpeedHud.UP_DOWN);
        assertTrue(HoldSpeedHud.pullState(1));
        assertFalse(HoldSpeedHud.pullState(0));
    }

    @Test
    @Config(sdk = 28, qualifiers = "xxhdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theDialLightsTheNotchOfTheSpeedItIsOnAndOpensAtTheBottom() {
        HoldSpeedHud.Circle circle = new HoldSpeedHud.Circle(owner.get(), GRID);
        circle.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int size = circle.getMeasuredWidth();
        circle.layout(0, 0, size, size);
        circle.setSpeed(2.25f, false);

        Bitmap shot = draw(circle, size);
        assertEquals("outside the disc is clear", 0, Color.alpha(shot.getPixel(0, 0)));
        assertBright("the notch for 2.25x", notch(shot, circle, 7));
        assertDim("the notch for 2.75x", notch(shot, circle, 9));
        int gap = shot.getPixel(size / 2, Math.round(size / 2f + circle.ringRadius(size)));
        assertTrue("the ring leaves the bottom open: " + Integer.toHexString(gap),
                Color.alpha(gap) > 150 && Color.red(gap) < 60);
        int shackle = Math.round(size / 2f - 12.5f * density);
        assertDim("no padlock while open", shot.getPixel(size / 2, shackle));

        circle.setSpeed(1f, false);
        circle.setLocked(true);
        shot = draw(circle, size);
        assertBright("the notch for 1x", notch(shot, circle, 2));
        assertDim("the notch for 2.25x once left", notch(shot, circle, 7));
        assertTrue("the padlock shows when closed",
                Color.red(shot.getPixel(size / 2, shackle)) > 180);
    }

    @Test
    public void theStyleRowSitsUnderTheSlideSwitchAndNeedsIt() {
        boolean speed = SettingsStatus.playbackSpeedEnabled;
        SettingsStatus.playbackSpeedEnabled = true;
        try (ActivityController<Host> controller = Robolectric.buildActivity(Host.class).setup()) {
            Host host = controller.get();
            Utils.setContext(host);
            SettingsStatus.holdSpeedSlideEnabled = false;
            assertNull(playbackPage(host).findPreference(Settings.HOLD_SPEED_HUD_STYLE.key));

            SettingsStatus.holdSpeedSlideEnabled = true;
            PreferenceScreen screen = playbackPage(host);
            Preference row = screen.findPreference(Settings.HOLD_SPEED_HUD_STYLE.key);
            assertTrue(row instanceof ListPreference);
            assertEquals("Hold speed bubble style", String.valueOf(row.getTitle()));
            ListPreference list = (ListPreference) row;
            assertArrayEquals(new CharSequence[] {"Pill", "Circle"}, list.getEntries());
            assertArrayEquals(new CharSequence[] {HoldSpeedHud.PILL, HoldSpeedHud.CIRCLE}, list.getEntryValues());
            assertTrue(String.valueOf(row.getSummary()).contains("round dial"));
            List<String> keys = new ArrayList<>();
            collectKeys(screen, keys);
            assertEquals("right under the slide switch",
                    keys.indexOf(Settings.HOLD_SPEED_SLIDE.key) + 1, keys.indexOf(Settings.HOLD_SPEED_HUD_STYLE.key));

            Settings.HOLD_SPEED_SLIDE.save(false);
            assertFalse("the style waits for the slide", Settings.HOLD_SPEED_HUD_STYLE.isAvailable());
            Settings.HOLD_SPEED_SLIDE.save(true);
            assertTrue(Settings.HOLD_SPEED_HUD_STYLE.isAvailable());
        } finally {
            SettingsStatus.playbackSpeedEnabled = speed;
            Utils.setContext(owner.get());
        }
    }

    @Test
    public void theDirectionRowSitsUnderTheStyleRowAndNeedsTheSlide() {
        boolean speed = SettingsStatus.playbackSpeedEnabled;
        SettingsStatus.playbackSpeedEnabled = true;
        try (ActivityController<Host> controller = Robolectric.buildActivity(Host.class).setup()) {
            Host host = controller.get();
            Utils.setContext(host);
            SettingsStatus.holdSpeedSlideEnabled = false;
            assertNull(playbackPage(host).findPreference(Settings.HOLD_SPEED_SLIDE_AXIS.key));

            SettingsStatus.holdSpeedSlideEnabled = true;
            PreferenceScreen screen = playbackPage(host);
            Preference row = screen.findPreference(Settings.HOLD_SPEED_SLIDE_AXIS.key);
            assertTrue(row instanceof ListPreference);
            assertEquals("Hold speed slide direction", String.valueOf(row.getTitle()));
            ListPreference list = (ListPreference) row;
            assertArrayEquals(new CharSequence[] {"Sideways", "Up and down"}, list.getEntries());
            assertArrayEquals(new CharSequence[] {HoldSpeedHud.SIDEWAYS, HoldSpeedHud.UP_DOWN}, list.getEntryValues());
            assertEquals(HoldSpeedHud.SIDEWAYS, list.getValue());
            assertTrue(String.valueOf(row.getSummary()).contains("near the edge"));
            List<String> keys = new ArrayList<>();
            collectKeys(screen, keys);
            assertEquals("right under the style row",
                    keys.indexOf(Settings.HOLD_SPEED_HUD_STYLE.key) + 1, keys.indexOf(Settings.HOLD_SPEED_SLIDE_AXIS.key));

            Settings.HOLD_SPEED_SLIDE.save(false);
            assertFalse("the direction waits for the slide", Settings.HOLD_SPEED_SLIDE_AXIS.isAvailable());
            Settings.HOLD_SPEED_SLIDE.save(true);
            assertTrue(Settings.HOLD_SPEED_SLIDE_AXIS.isAvailable());
        } finally {
            SettingsStatus.playbackSpeedEnabled = speed;
            Utils.setContext(owner.get());
        }
    }

    /** A host for the settings page, which builds its rows on a PreferenceActivity. */
    public static final class Host extends PreferenceActivity {}

    private static PreferenceScreen playbackPage(PreferenceActivity host) {
        PreferenceScreen screen = host.getPreferenceManager().createPreferenceScreen(host);
        host.setPreferenceScreen(screen);
        new PlaybackPreferenceCategory(host, screen);
        return screen;
    }

    private static void collectKeys(Preference preference, List<String> into) {
        if (preference.getKey() != null) into.add(preference.getKey());
        if (preference instanceof PreferenceGroup) {
            PreferenceGroup group = (PreferenceGroup) preference;
            for (int i = 0; i < group.getPreferenceCount(); i++) collectKeys(group.getPreference(i), into);
        }
    }

    private static Bitmap draw(View view, int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    /** The pixel under step {@code i}'s notch. */
    private static int notch(Bitmap shot, HoldSpeedHud.Circle circle, int i) {
        float size = shot.getWidth();
        double angle = Math.toRadians(circle.angleOf(i));
        float radius = circle.ringRadius(size);
        return shot.getPixel(Math.round(size / 2f + (float) (radius * Math.cos(angle))),
                Math.round(size / 2f + (float) (radius * Math.sin(angle))));
    }

    private static void assertBright(String what, int pixel) {
        assertTrue(what + ": " + Integer.toHexString(pixel), Color.alpha(pixel) >= 225
                && Color.red(pixel) >= 225 && Color.green(pixel) >= 225 && Color.blue(pixel) >= 225);
    }

    private static void assertDim(String what, int pixel) {
        assertTrue(what + ": " + Integer.toHexString(pixel), Color.red(pixel) < 150);
    }

    private static final float DEAD_ZONE = HoldSpeedHud.DEAD_ZONE_DP;
    private static final float STEP = HoldSpeedHud.STEP_DP;
    private static final float AXIS_LOCK = HoldSpeedHud.AXIS_LOCK_DP;
    private static final float SIDE_LOCK = HoldSpeedHud.SIDE_LOCK_DP;
    private static final float SIDE_UNLOCK = HoldSpeedHud.SIDE_UNLOCK_DP;
}

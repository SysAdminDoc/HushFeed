/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.cleardisplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.feed.ClearDisplayFadeOut;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

/**
 * Fade the controls out (#501). TikTok's Clear display takes the controls away between two
 * frames; with the switch on, the automatic clear first takes them to nothing over
 * {@link ClearDisplayFadeOut#FADE_MS} and only then asks TikTok for the clear, and every view gets
 * its own opacity back once the clear has landed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AutomaticClearFadeTest {
    private ActivityController<Activity> owner;
    private Activity activity;
    private View layer;
    private View strip;
    private View tabs;
    /** Each clear asked for, and the views' opacity at the moment a clear was asked for. */
    private final List<Boolean> events = new ArrayList<>();
    private float[] atClear;
    private final RememberClearDisplayPatch.ClearEvent recording = clear -> {
        events.add(clear);
        if (clear) atClear = new float[]{layer.getAlpha(), strip.getAlpha(), tabs.getAlpha()};
        return true;
    };

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        RememberClearDisplayPatch.resetForTests();
        Settings.AUTOMATIC_CLEAR_DISPLAY.save(false);
        Settings.CLEAR_DISPLAY.save(false);
        RememberClearDisplayPatch.firstFrame("reset", () -> true, value -> true);
        Settings.AUTOMATIC_CLEAR_DISPLAY.save(true);
        Settings.AUTOMATIC_CLEAR_DISPLAY_DELAY.save(1000);
        Settings.CLEAR_DISPLAY_FADE_OUT.save(true);

        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = owner.get();
        FrameLayout root = new FrameLayout(activity);
        layer = new View(activity);
        strip = new View(activity);
        tabs = new View(activity);
        // A level the overlay pass gave the tabs, which has to come back exactly.
        tabs.setAlpha(0.8f);
        root.addView(layer);
        root.addView(strip);
        root.addView(tabs);
        activity.setContentView(root);
        idle(Duration.ZERO);
        animatorScale(1f);
        // Paused, Robolectric's choreographer gives a frame only as the clock reaches it. Left
        // running, it moves the clock a frame on with every frame asked for, and a whole fade
        // plays out inside one idle.
        ShadowChoreographer.setPaused(true);
        RememberClearDisplayPatch.fadeWindow = new WeakReference<>(activity);
        RememberClearDisplayPatch.fadeTargets = window -> List.of(layer, strip, tabs);
        events.clear();
        atClear = null;
    }

    @After
    public void tearDown() {
        RememberClearDisplayPatch.resetForTests();
        Settings.CLEAR_DISPLAY_FADE_OUT.resetToDefault();
        Settings.AUTOMATIC_CLEAR_DISPLAY_DELAY.resetToDefault();
        Settings.AUTOMATIC_CLEAR_DISPLAY.resetToDefault();
        Settings.FADE_CONTROLS_OPACITY.resetToDefault();
        animatorScale(1f);
        ShadowChoreographer.setPaused(false);
        owner.close();
    }

    @Test
    public void theControlsFadeOutBeforeTheClearAndComeBackAfterIt() {
        assertEquals(Boolean.FALSE, Settings.CLEAR_DISPLAY_FADE_OUT.defaultValue);
        RememberClearDisplayPatch.firstFrame("faded", () -> true, recording);
        assertEquals("a new video shows its controls first", List.of(false), events);
        idle(Duration.ofMillis(1000));
        assertEquals("the clear went out before the fade", List.of(false), events);

        idle(Duration.ofMillis(ClearDisplayFadeOut.FADE_MS / 2));
        assertBetween("the layer halfway", layer.getAlpha(), 0f, 1f);
        assertBetween("the strip halfway", strip.getAlpha(), 0f, 1f);
        assertBetween("the tabs halfway, from their own level", tabs.getAlpha(), 0f, 0.8f);
        assertEquals("the clear went out halfway through the fade", List.of(false), events);
        assertFalse(RememberClearDisplayPatch.isClearDisplayNow());

        idle(Duration.ofMillis(ClearDisplayFadeOut.FADE_MS));
        assertEquals(List.of(false, true), events);
        assertNotNull(atClear);
        assertEquals("the layer wasn't gone as the clear went out", 0f, atClear[0], 0f);
        assertEquals("the strip wasn't gone as the clear went out", 0f, atClear[1], 0f);
        assertEquals("the tabs weren't gone as the clear went out", 0f, atClear[2], 0f);
        assertTrue(RememberClearDisplayPatch.isClearDisplayNow());

        idle(Duration.ofMillis(100));
        assertEquals("TikTok's own show was left at 0", 1f, layer.getAlpha(), 0f);
        assertEquals(1f, strip.getAlpha(), 0f);
        assertEquals("the tabs lost the level they had", 0.8f, tabs.getAlpha(), 0f);
        assertFalse(ClearDisplayFadeOut.isRunning());
    }

    @Test
    public void aTouchDuringTheFadeKeepsTheControlsAndClearsNothing() {
        RememberClearDisplayPatch.firstFrame("touched", () -> true, recording);
        idle(Duration.ofMillis(1000 + ClearDisplayFadeOut.FADE_MS / 2));
        assertBetween("the fixture never started fading", layer.getAlpha(), 0f, 1f);

        long at = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, 10f, 10f, 0);
        activity.getWindow().getCallback().dispatchTouchEvent(down);
        down.recycle();
        assertEquals("the touch left the layer faded", 1f, layer.getAlpha(), 0f);
        assertEquals(1f, strip.getAlpha(), 0f);
        assertEquals(0.8f, tabs.getAlpha(), 0f);
        assertFalse(ClearDisplayFadeOut.isRunning());

        idle(Duration.ofMillis(ClearDisplayFadeOut.FADE_MS * 2));
        assertEquals("the clear went out after the touch", List.of(false), events);
        assertFalse(RememberClearDisplayPatch.isClearDisplayNow());
        assertEquals(1f, layer.getAlpha(), 0f);

        // The wait starts again from the touch, at least TAP_SHOW_MS, as after a tap that brings
        // the controls back, and then the fade and the clear run as before.
        idle(Duration.ofMillis(RememberClearDisplayPatch.TAP_SHOW_MS - ClearDisplayFadeOut.FADE_MS * 3));
        assertEquals(List.of(false), events);
        idle(Duration.ofMillis(ClearDisplayFadeOut.FADE_MS * 4));
        assertEquals(List.of(false, true), events);
        assertEquals(0f, atClear[0], 0f);
    }

    @Test
    public void withTheSwitchOffTheClearLandsAtOnceAsBefore() {
        Settings.CLEAR_DISPLAY_FADE_OUT.save(false);
        RememberClearDisplayPatch.firstFrame("instant", () -> true, recording);
        idle(Duration.ofMillis(999));
        assertEquals(List.of(false), events);
        idle(Duration.ofMillis(1));
        assertEquals(List.of(false, true), events);
        assertEquals("the layer was touched with the switch off", 1f, atClear[0], 0f);
        assertEquals(1f, atClear[1], 0f);
        assertEquals(0.8f, atClear[2], 0f);
        assertFalse(ClearDisplayFadeOut.isRunning());
    }

    @Test
    public void aClearThatNoLongerHoldsGivesTheControlsBackRightAway() {
        // The next video, mid-fade: the fade stops there and the clear never goes out.
        RememberClearDisplayPatch.firstFrame("first", () -> true, recording);
        idle(Duration.ofMillis(1000 + ClearDisplayFadeOut.FADE_MS / 2));
        assertBetween("the fixture never started fading", layer.getAlpha(), 0f, 1f);
        RememberClearDisplayPatch.firstFrame("second", () -> true, recording);
        assertEquals(1f, layer.getAlpha(), 0f);
        assertEquals(0.8f, tabs.getAlpha(), 0f);
        assertFalse(ClearDisplayFadeOut.isRunning());
        assertEquals(List.of(false, false), events);

        // The video stops being current while it fades (its window lost focus, say): the end of
        // the fade checks again and gives the controls back instead of clearing.
        events.clear();
        RememberClearDisplayPatch.resetForTests();
        RememberClearDisplayPatch.fadeWindow = new WeakReference<>(activity);
        RememberClearDisplayPatch.fadeTargets = window -> List.of(layer, strip, tabs);
        AtomicBoolean current = new AtomicBoolean(true);
        RememberClearDisplayPatch.firstFrame("third", current::get, recording);
        idle(Duration.ofMillis(1000 + ClearDisplayFadeOut.FADE_MS / 2));
        assertBetween("the fixture never started fading", layer.getAlpha(), 0f, 1f);
        current.set(false);
        idle(Duration.ofMillis(ClearDisplayFadeOut.FADE_MS));
        assertEquals(List.of(false), events);
        assertEquals(1f, layer.getAlpha(), 0f);
        assertEquals(1f, strip.getAlpha(), 0f);
        assertEquals(0.8f, tabs.getAlpha(), 0f);

        // Automatic Clear display switched off mid-fade.
        events.clear();
        RememberClearDisplayPatch.resetForTests();
        RememberClearDisplayPatch.fadeWindow = new WeakReference<>(activity);
        RememberClearDisplayPatch.fadeTargets = window -> List.of(layer, strip, tabs);
        RememberClearDisplayPatch.firstFrame("fourth", () -> true, recording);
        idle(Duration.ofMillis(1000 + ClearDisplayFadeOut.FADE_MS / 2));
        assertBetween("the fixture never started fading", layer.getAlpha(), 0f, 1f);
        Settings.AUTOMATIC_CLEAR_DISPLAY.save(false);
        idle(Duration.ZERO);
        assertEquals(1f, layer.getAlpha(), 0f);
        idle(Duration.ofMillis(ClearDisplayFadeOut.FADE_MS));
        assertEquals(List.of(false), events);
    }

    /** With Android's animations off the fade takes no time, so the clear lands as it always did. */
    @Test
    public void animationsOffClearAtOnce() {
        animatorScale(0f);
        RememberClearDisplayPatch.firstFrame("still", () -> true, recording);
        idle(Duration.ofMillis(1000));
        idle(Duration.ofMillis(50));
        assertEquals(List.of(false, true), events);
        assertEquals(0f, atClear[0], 0f);
        idle(Duration.ofMillis(50));
        assertEquals(1f, layer.getAlpha(), 0f);
        assertEquals(0.8f, tabs.getAlpha(), 0f);
    }

    /**
     * Keep faded controls in Clear display keeps them in sight at the fade's level, so there's
     * nothing to fade out: a fade to nothing and back would only blink them.
     */
    @Test
    public void theFadedClearDisplayKeepsItsLevelWithoutAFade() {
        boolean overlays = SettingsStatus.videoOverlaysEnabled;
        try {
            SettingsStatus.videoOverlaysEnabled = true;
            Settings.FADE_CONTROLS_OPACITY.save(50);
            RememberClearDisplayPatch.firstFrame("kept", () -> true, recording);
            idle(Duration.ofMillis(1000));
            assertEquals(List.of(false, true), events);
            assertEquals(1f, atClear[0], 0f);
            assertFalse(ClearDisplayFadeOut.isRunning());
        } finally {
            SettingsStatus.videoOverlaysEnabled = overlays;
        }
    }

    /** A window with nothing to fade clears at once rather than waiting out a fade of nothing. */
    @Test
    public void nothingToFadeClearsAtOnce() {
        RememberClearDisplayPatch.fadeTargets = window -> List.of();
        RememberClearDisplayPatch.firstFrame("bare", () -> true, recording);
        idle(Duration.ofMillis(1000));
        assertEquals(List.of(false, true), events);
        assertFalse(ClearDisplayFadeOut.isRunning());
    }

    /**
     * Moves the clock {@code duration} on a millisecond at a time, so each frame of the paused
     * choreographer comes as the clock reaches it, the way a phone's do, and a fade can be caught
     * halfway. One jump of the whole duration brings a single frame, at its end.
     */
    private static void idle(Duration duration) {
        ShadowLooper looper = Shadows.shadowOf(Looper.getMainLooper());
        looper.idle();
        for (long step = 0; step < duration.toMillis(); step++) looper.idleFor(Duration.ofMillis(1));
    }

    private static void assertBetween(String what, float value, float low, float high) {
        assertTrue(what + ": " + value, value > low && value < high);
    }

    private static void animatorScale(float scale) {
        ReflectionHelpers.callStaticMethod(ValueAnimator.class, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(float.class, scale));
    }
}

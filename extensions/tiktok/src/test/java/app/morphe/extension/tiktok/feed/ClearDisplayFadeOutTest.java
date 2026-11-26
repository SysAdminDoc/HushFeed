/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * What Fade the controls out (#501) takes down, and how it lives beside the overlay pass's own
 * fade, which writes the same views' opacity on every frame.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ClearDisplayFadeOutTest {
    private static final String[] NAMES = {"view_rootview", "widget_container", "48.0.2:v9j", "48.0.2:oxr",
            "48.0.2:qzd", "48.0.2:l5i", "48.0.2:ohu"};
    /** Long enough for a frame or two, so new content is laid out. */
    private static final Duration LAYOUT = Duration.ofMillis(50);

    private ActivityController<Activity> owner;
    private Activity activity;
    private final List<String> heard = new ArrayList<>();
    private final ClearDisplayFadeOut.Listener listener = new ClearDisplayFadeOut.Listener() {
        @Override public void ended() { heard.add("ended"); }
        @Override public void touched() { heard.add("touched"); }
    };

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = owner.get();
        idle(Duration.ZERO);
        animatorScale(1f);
        // Paused, Robolectric's choreographer gives a frame only as the clock reaches it. Left
        // running, it moves the clock a frame on with every frame asked for, and a whole fade
        // plays out inside one idle.
        ShadowChoreographer.setPaused(true);
    }

    @After
    public void tearDown() {
        ClearDisplayFadeOut.stop();
        for (String name : NAMES) VideoOverlayHider.resolveForTests(name, 0);
        ShadowChoreographer.setPaused(false);
        owner.close();
    }

    /**
     * The widget layer of the cell the pager has settled on (TikTok's interact area, which stays
     * VISIBLE in Clear display while TikTok hides what's in it), the tab strip, the bottom tabs and
     * an opened video's comment or message bar, each only while it's shown. The cell waiting a
     * page below and a bar that's away are left alone, and so is the opened video's top bar,
     * which Clear display leaves up: faded to nothing it would only pop back.
     */
    @Test
    public void theFadeTakesTheSettledCellsLayerAndTheShownBarsOnly() {
        int[] ids = new int[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            ids[i] = View.generateViewId();
            VideoOverlayHider.resolveForTests(NAMES[i], ids[i]);
        }
        FrameLayout root = new FrameLayout(activity);
        FrameLayout pager = new FrameLayout(activity);
        root.addView(pager, new FrameLayout.LayoutParams(-1, -1));
        View settledLayer = cellWithLayer(pager, ids[0], ids[1], 0f);
        cellWithLayer(pager, ids[0], ids[1], 4000f);
        View strip = bar(root, ids[2], Gravity.TOP, View.VISIBLE);
        View tabs = bar(root, ids[3], Gravity.BOTTOM, View.VISIBLE);
        bar(root, ids[4], Gravity.BOTTOM, View.GONE);
        View message = bar(root, ids[5], Gravity.BOTTOM, View.VISIBLE);
        bar(root, ids[6], Gravity.TOP, View.VISIBLE);
        bar(root, ids[2], Gravity.CENTER, View.INVISIBLE);
        activity.setContentView(root);
        idle(LAYOUT);

        Set<View> taken = new HashSet<>(VideoOverlayHider.clearFadeTargets(activity));
        assertEquals(Set.of(settledLayer, strip, tabs, message), taken);
        assertEquals("a view was taken twice", taken.size(), VideoOverlayHider.clearFadeTargets(activity).size());
        assertTrue(VideoOverlayHider.clearFadeTargets(null).isEmpty());
    }

    /**
     * The pre-draw pass and the overlay pass read a value they didn't write as TikTok's and fade
     * it again. While the clear fade runs they leave its views alone, or the two compound; given
     * back, the view is at the level the pass set and stays there.
     */
    @Test
    public void theFramePassLeavesAFadingViewAlone() {
        FrameLayout root = new FrameLayout(activity);
        View control = new View(activity);
        root.addView(control, new FrameLayout.LayoutParams(100, 100));
        activity.setContentView(root);
        idle(LAYOUT);
        try {
            VideoOverlayHider.setFaded(control, 50);
            assertEquals(0.5f, control.getAlpha(), 0f);

            assertTrue(ClearDisplayFadeOut.start(null, List.of(control), listener));
            assertTrue(ClearDisplayFadeOut.isRunning());
            idle(Duration.ofMillis(ClearDisplayFadeOut.FADE_MS / 2));
            float halfway = control.getAlpha();
            assertTrue("halfway: " + halfway, halfway > 0f && halfway < 0.5f);
            VideoOverlayHider.reapplyFade();
            assertEquals("the frame pass faded the fade", halfway, control.getAlpha(), 0f);
            VideoOverlayHider.setFaded(control, 50);
            assertEquals("the overlay pass faded the fade", halfway, control.getAlpha(), 0f);

            ClearDisplayFadeOut.stop();
            assertEquals(0.5f, control.getAlpha(), 0f);
            VideoOverlayHider.reapplyFade();
            assertEquals("the level compounded after the fade", 0.5f, control.getAlpha(), 0f);
            VideoOverlayHider.setFaded(control, 50);
            assertEquals(0.5f, control.getAlpha(), 0f);
            VideoOverlayHider.setFaded(control, 100);
            assertEquals("TikTok's own opacity didn't come back", 1f, control.getAlpha(), 0f);
            assertEquals("stopping is no end and no touch", List.of(), heard);
        } finally {
            VideoOverlayHider.setFaded(control, 100);
        }
    }

    /**
     * A finger on the window stops a fade, gives the controls back and says so; the touch still
     * reaches the screen, the window is wrapped once however many fades run, and a touch with no
     * fade running says nothing.
     */
    @Test
    public void aTouchOnTheWindowStopsTheFade() {
        FrameLayout root = new FrameLayout(activity);
        View control = new View(activity);
        List<Integer> reached = new ArrayList<>();
        root.setOnTouchListener((view, event) -> reached.add(event.getActionMasked()));
        root.addView(control, new FrameLayout.LayoutParams(100, 100));
        activity.setContentView(root);
        idle(LAYOUT);

        assertTrue(ClearDisplayFadeOut.start(activity, List.of(control), listener));
        Window.Callback wrapped = activity.getWindow().getCallback();
        assertTrue(Proxy.isProxyClass(wrapped.getClass()));
        idle(Duration.ofMillis(ClearDisplayFadeOut.FADE_MS / 2));
        assertTrue(control.getAlpha() < 1f);

        touch(root, MotionEvent.ACTION_DOWN);
        assertEquals(List.of("touched"), heard);
        assertEquals(1f, control.getAlpha(), 0f);
        assertFalse(ClearDisplayFadeOut.isRunning());
        assertEquals("the touch never reached the screen", List.of(MotionEvent.ACTION_DOWN), reached);
        touch(root, MotionEvent.ACTION_UP);
        assertEquals(List.of("touched"), heard);

        assertTrue(ClearDisplayFadeOut.start(activity, List.of(control), listener));
        assertSame("the window was wrapped again", wrapped, activity.getWindow().getCallback());
        idle(Duration.ofMillis(ClearDisplayFadeOut.FADE_MS * 2));
        assertEquals(List.of("touched", "ended"), heard);
        assertEquals("the view waits at 0 for the clear", 0f, control.getAlpha(), 0f);
        ClearDisplayFadeOut.restoreAfterClear();
        idle(Duration.ofMillis(50));
        assertEquals(1f, control.getAlpha(), 0f);
    }

    /** A fade with nothing to take down doesn't start, so the clear isn't held up for nothing. */
    @Test
    public void nothingToFadeStartsNothing() {
        List<View> none = new ArrayList<>();
        none.add(null);
        assertFalse(ClearDisplayFadeOut.start(activity, none, listener));
        assertFalse(ClearDisplayFadeOut.isRunning());
        assertEquals(List.of(), heard);
    }

    /** A touch on {@code target}, which sits below the activity's action bar in its window. */
    private void touch(View target, int action) {
        int[] spot = new int[2];
        target.getLocationInWindow(spot);
        long at = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(at, at, action, spot[0] + 10f, spot[1] + 10f, 0);
        activity.getWindow().getCallback().dispatchTouchEvent(event);
        event.recycle();
    }

    private View cellWithLayer(FrameLayout pager, int cellId, int layerId, float offset) {
        FrameLayout cell = new FrameLayout(activity);
        cell.setId(cellId);
        cell.setTranslationY(offset);
        FrameLayout layer = new FrameLayout(activity);
        layer.setId(layerId);
        layer.addView(new View(activity), new FrameLayout.LayoutParams(40, 40));
        cell.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        pager.addView(cell, new FrameLayout.LayoutParams(-1, -1));
        return layer;
    }

    private View bar(FrameLayout root, int id, int gravity, int visibility) {
        View bar = new View(activity);
        bar.setId(id);
        bar.setVisibility(visibility);
        root.addView(bar, new FrameLayout.LayoutParams(-1, 40, gravity));
        return bar;
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

    private static void animatorScale(float scale) {
        ReflectionHelpers.callStaticMethod(ValueAnimator.class, "setDurationScale",
                ReflectionHelpers.ClassParameter.from(float.class, scale));
    }
}

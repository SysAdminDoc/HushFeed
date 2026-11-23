/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Fade the controls out (#501). TikTok's Clear display takes the controls away between two
 * frames, so the automatic one, which Hushfeed asks for after a delay, was a hard cut. With the
 * switch on, the automatic clear first takes the controls to nothing over {@link #FADE_MS} with a
 * ValueAnimator, so Android's animation speed applies (off, the fade takes no time), and asks for
 * the clear only once they're gone.
 *
 * <p>The fade covers what the clear is about to take away, as {@link VideoOverlayHider#clearFadeTargets}
 * finds it, each view from the opacity it had. When the clear lands, every view gets that opacity
 * back on the next frame, by which time TikTok has hidden what it hides, so TikTok's own show
 * later isn't left at 0. A view TikTok wrote an opacity of its own to in between keeps TikTok's.
 * A clear that doesn't happen gives the opacity back at once, and a finger on the window during the
 * fade stops it there.
 */
public final class ClearDisplayFadeOut {
    /** How long the controls take to go, before Android's animation speed. */
    public static final long FADE_MS = 250;

    /** What the automatic clear hears back from a fade. */
    public interface Listener {
        /** The controls are at 0: the clear can go out. */
        void ended();

        /** A finger came down on the window: the fade stopped and the controls are back. */
        void touched();
    }

    /** One fade: its views, the opacity each had, and the last one this class gave it. */
    private static final class Fade {
        final View[] views;
        final float[] from;
        final float[] wrote;
        final Listener listener;
        final long startedAt = SystemClock.uptimeMillis();
        ValueAnimator animator;

        Fade(List<View> kept, Listener listener) {
            views = kept.toArray(new View[0]);
            from = new float[views.length];
            for (int i = 0; i < views.length; i++) from[i] = views[i].getAlpha();
            wrote = from.clone();
            this.listener = listener;
        }

        /** Every view at {@code share} of the opacity it started from. */
        void set(float share) {
            for (int i = 0; i < views.length; i++) {
                views[i].setAlpha(from[i] * share);
                wrote[i] = views[i].getAlpha();
            }
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The fade going now. */
    private static Fade running;
    /** A fade that reached 0, its views waiting at 0 for the clear to land and the next frame. */
    private static Fade landed;
    /** Windows whose callback already reports touches here. */
    private static final Map<Window, Boolean> WATCHED = new WeakHashMap<>();

    private ClearDisplayFadeOut() {
    }

    /**
     * Fades {@code views} out and tells {@code listener} when they're at 0, or that a finger on
     * {@code watched}'s window stopped it. False, with nothing started, when there's nothing to
     * fade; the clear then goes out as it would without the switch. A fade or restore still
     * waiting from before is finished first.
     */
    public static boolean start(@Nullable Activity watched, List<View> views, Listener listener) {
        stop();
        List<View> kept = new ArrayList<>();
        for (View view : views) {
            if (view != null && !kept.contains(view)) kept.add(view);
        }
        if (kept.isEmpty()) return false;
        Fade fade = new Fade(kept, listener);
        running = fade;
        for (View view : fade.views) VideoOverlayHider.holdForClearFade(view);
        if (watched != null) watch(watched);
        ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
        animator.setDuration(FADE_MS);
        animator.addUpdateListener(update -> {
            if (running == fade) fade.set((Float) update.getAnimatedValue());
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Stopped or touched: those gave the views back already and told whoever needed it.
                if (running != fade) return;
                fade.set(0f);
                running = null;
                landed = fade;
                long took = SystemClock.uptimeMillis() - fade.startedAt;
                Logger.printDebug(() -> "Clear display fade: " + fade.views.length + " views at 0 after " + took + " ms");
                fade.listener.ended();
            }
        });
        fade.animator = animator;
        Logger.printDebug(() -> "Clear display fade: fading " + fade.views.length + " views over " + FADE_MS + " ms");
        animator.start();
        return true;
    }

    /** Whether a fade is going now. */
    public static boolean isRunning() {
        return running != null;
    }

    /**
     * Stops a fade going now and gives back a finished one still waiting for its frame: every view
     * gets its opacity back at once. Nobody is told; the caller is the one who stopped it.
     */
    public static void stop() {
        Fade fade = running;
        if (fade != null) {
            running = null;
            fade.animator.cancel();
            restore(fade, "stopped");
        }
        Fade done = landed;
        if (done != null) {
            landed = null;
            restore(done, "given back early");
        }
    }

    /**
     * The clear landed. Each view gets its opacity back on the next frame, by which time TikTok has
     * put away what Clear display hides and the overlay pass posted with the clear has run.
     */
    public static void restoreAfterClear() {
        Fade fade = landed;
        if (fade == null) return;
        MAIN.post(() -> {
            if (landed != fade) return;
            Choreographer.getInstance().postFrameCallback(frameTime -> {
                if (landed != fade) return;
                landed = null;
                restore(fade, "after the clear");
            });
        });
    }

    /** Each view back at the opacity it had, unless TikTok wrote one of its own since. */
    private static void restore(Fade fade, String when) {
        int given = 0;
        for (int i = 0; i < fade.views.length; i++) {
            View view = fade.views[i];
            if (view.getAlpha() == fade.wrote[i]) {
                view.setAlpha(fade.from[i]);
                given++;
            }
            VideoOverlayHider.releaseFromClearFade(view);
        }
        final int count = given;
        Logger.printDebug(() -> "Clear display fade: opacity given back to " + count + " of "
                + fade.views.length + " views, " + when);
    }

    /** A finger on a watched window: a fade going now stops, its views come back, and it says so. */
    private static void touched() {
        Fade fade = running;
        if (fade == null) return;
        running = null;
        fade.animator.cancel();
        restore(fade, "for a touch");
        Logger.printDebug(() -> "Clear display fade: a touch stopped it");
        fade.listener.touched();
    }

    /** Wraps {@code activity}'s window callback once, as {@link BurnInGuard} does, to see touches. */
    private static void watch(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (window == null || WATCHED.containsKey(window)) return;
            Window.Callback inner = window.getCallback();
            if (inner == null) return;
            InvocationHandler watcher = (proxy, method, args) -> {
                try {
                    if (running != null && args != null && args.length == 1 && args[0] instanceof MotionEvent
                            && "dispatchTouchEvent".equals(method.getName())) {
                        touched();
                    }
                    return method.invoke(inner, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getCause() != null ? ex.getCause() : ex;
                }
            };
            window.setCallback((Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class}, watcher));
            WATCHED.put(window, Boolean.TRUE);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not watch the screen for touches during the fade", ex);
        }
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.Window;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Sends TikTok to the background once its screen has gone the set number of minutes without a
 * touch, for someone who falls asleep with the feed playing (discussion #266). Turning the
 * screen off needs device admin, so it does what Leave when TikTok says time is up does, plus a
 * toast saying why.
 *
 * <p>The wait runs only while a TikTok screen is in front. Any screen counts, not only the feed,
 * and any touch on it starts the wait over, as does a screen coming to the front or a key or
 * mouse wheel (a keyboard, a page turner, DeX). A finger held still is still a touch, so a press
 * and hold for speed never runs it out, even when a panel takes the focus meanwhile. The clock
 * is the looper's, which stands still while the phone sleeps, and a phone with TikTok in front
 * sleeps only when the screen goes off, which pauses the screen and stops the wait anyway.
 *
 * <p>The share sheet, the comment menu and the LIVE panels have windows of their own, and their
 * touches never pass through the screen's window. While one of those has the focus the wait
 * keeps starting over, and it starts over again when the screen gets the focus back, so someone
 * reading in a panel is never sent away.
 *
 * <p>With background play on, TikTok's player keeps going once the app is behind, which is the
 * last thing a sleeper wants. Taking the audio focus is how that player is told to stop, as the
 * sleep timer does, and the focus is handed straight back so nothing else stays silenced.
 */
public final class IdleLeave {
    interface Leaver {
        /** Answers whether TikTok went to the back. */
        boolean leave(Activity activity);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable CHECK = IdleLeave::check;
    private static final Map<Window, Boolean> WRAPPED = new WeakHashMap<>();
    private static final AudioManager.OnAudioFocusChangeListener HUSH = change -> {
    };

    private static Leaver leaver = IdleLeave::leaveNow;
    private static WeakReference<Application> followed = new WeakReference<>(null);
    /** The TikTok screen in front, or empty while none is. */
    private static WeakReference<Activity> front = new WeakReference<>(null);
    /** When a finger last touched a TikTok screen, or one came to the front. Uptime clock. */
    private static long touchedAt;
    private static boolean fingerDown;
    /** A look at the wait is posted. */
    private static boolean checking;

    private IdleLeave() {
    }

    /** The wait in milliseconds, or 0 while this is off. */
    static long waitMs() {
        if (!SettingsStatus.blockAuthorEnabled) return 0L;
        return Math.max(0, Settings.IDLE_LEAVE_MINUTES.get()) * 60_000L;
    }

    /**
     * Called on each start of the main activity, with the extension's context set. Follows every
     * TikTok screen from here on, since a video opened from a profile or search plays in an
     * activity of its own. Touches no window: views wait for the first resume.
     */
    public static void onMainActivityCreate(Activity activity) {
        try {
            Application application = activity == null ? null : activity.getApplication();
            if (application == null || followed.get() == application) return;
            followed = new WeakReference<>(application);
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity resumed) { resumed(resumed); }
                @Override public void onActivityPaused(Activity paused) { paused(paused); }
                @Override public void onActivityCreated(Activity created, Bundle state) { }
                @Override public void onActivityStarted(Activity started) { }
                @Override public void onActivityStopped(Activity stopped) { }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
                @Override public void onActivityDestroyed(Activity destroyed) { }
            });
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not follow TikTok's screens for the idle wait", ex);
        }
    }

    static void resumed(Activity activity) {
        front = new WeakReference<>(activity);
        touchedAt = SystemClock.uptimeMillis();
        fingerDown = false;
        // Off leaves TikTok's windows as they are. Turned on in the settings, the wait starts
        // with the next screen to come to the front.
        if (waitMs() > 0) watch(activity);
        schedule();
    }

    static void paused(Activity activity) {
        if (front.get() != activity) return;
        front = new WeakReference<>(null);
        MAIN.removeCallbacks(CHECK);
        checking = false;
    }

    /** A finger on a TikTok screen; {@code action} is its MotionEvent action. */
    static void touched(int action) {
        touchedAt = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) {
            fingerDown = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            fingerDown = false;
            // A look that found the finger down waits for this lift to start the wait again.
            if (!checking) schedule();
        }
    }

    /** A key or a mouse wheel on a TikTok screen: someone is there, as with a touch. */
    static void stirred() {
        touchedAt = SystemClock.uptimeMillis();
    }

    /** The screen's window lost the focus to a panel of its own, or got it back. */
    static void focusChanged() {
        touchedAt = SystemClock.uptimeMillis();
        // A finger already down stays down: its touches stay with this window, which still gets
        // the lift, or a cancel when a panel takes the press over.
        if (!checking) schedule();
    }

    private static void schedule() {
        long wait = waitMs();
        Activity activity = front.get();
        MAIN.removeCallbacks(CHECK);
        checking = wait > 0 && activity != null;
        if (checking) MAIN.postAtTime(CHECK, touchedAt + wait);
    }

    /** The wait came due, or a touch since moved it on. */
    static void check() {
        checking = false;
        long wait = waitMs();
        Activity activity = front.get();
        if (wait <= 0 || activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (!activity.hasWindowFocus()) {
            // A panel with a window of its own is in front, and its touches can't be seen.
            touchedAt = SystemClock.uptimeMillis();
            schedule();
            return;
        }
        if (fingerDown || SystemClock.uptimeMillis() < touchedAt + wait) {
            // Held down, the lift starts the wait again.
            if (!fingerDown) schedule();
            return;
        }
        front = new WeakReference<>(null);
        Logger.printInfo(() -> "No touch for " + wait / 60_000L + " minutes: leaving TikTok");
        if (leaver.leave(activity)) return;
        // The task couldn't go back, so the screen is still in front and the wait starts over.
        Logger.printInfo(() -> "Idle wait: TikTok's screen could not go to the back");
        front = new WeakReference<>(activity);
        touchedAt = SystemClock.uptimeMillis();
        schedule();
    }

    private static boolean leaveNow(Activity activity) {
        // False means the task cannot go back (screen pinning, say), and TikTok stays in front.
        if (!activity.moveTaskToBack(true)) return false;
        Utils.showToastShort(L10n.t("Left TikTok after no touch"));
        try {
            AudioManager audio = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
            if (audio != null) {
                // The older calls: the request-object form is API 26 and this runs from 23.
                audio.requestAudioFocus(HUSH, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                audio.abandonAudioFocus(HUSH);
            }
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Idle wait: could not stop the player", failure);
        }
        return true;
    }

    /** Wraps {@code activity}'s window callback once, as the burn-in guard does, to see touches. */
    private static void watch(Activity activity) {
        try {
            Window window = activity.getWindow();
            if (window == null || WRAPPED.containsKey(window)) return;
            Window.Callback inner = window.getCallback();
            if (inner == null) return;
            InvocationHandler watcher = (proxy, method, args) -> {
                try {
                    String name = method.getName();
                    if (args != null && args.length == 1 && args[0] instanceof MotionEvent
                            && "dispatchTouchEvent".equals(name)) {
                        touched(((MotionEvent) args[0]).getActionMasked());
                    } else if ("dispatchKeyEvent".equals(name) || "dispatchGenericMotionEvent".equals(name)) {
                        stirred();
                    } else if ("onWindowFocusChanged".equals(name)) {
                        focusChanged();
                    }
                    return method.invoke(inner, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getCause() != null ? ex.getCause() : ex;
                }
            };
            window.setCallback((Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class}, watcher));
            WRAPPED.put(window, Boolean.TRUE);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not watch the screen for the idle wait", ex);
        }
    }

    static void setLeaverForTests(Leaver testLeaver) {
        leaver = testLeaver;
    }

    static void resetForTests() {
        MAIN.removeCallbacks(CHECK);
        checking = false;
        fingerDown = false;
        touchedAt = 0L;
        front = new WeakReference<>(null);
        leaver = IdleLeave::leaveNow;
    }
}

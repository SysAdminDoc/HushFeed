/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Matrix;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * What the Turn off haptics patch asks each time TikTok plays a vibration of its own.
 *
 * <p>TikTok 47 plays its taps and gestures three ways: about sixty calls to
 * {@code View.performHapticFeedback}, about thirty that hand the vibrator an effect, and about
 * fifty timed ones, {@code vibrate(long)}. Most timed ones run 6 to 100 ms and the rest up to half
 * a second: the older-Android branch beside an effect call, a comment like, a dislike, a shake
 * ad, a LIVE long press. The patch sends every one of those calls here. While the switch is on
 * they don't play, and the View call answers false, as Android does for a view whose haptics are
 * off. A patterned vibration, {@code vibrate(long[], int)}, is what an alert uses, so the patch
 * leaves those alone. A notification Android shows buzzes through its channel, and the keyboard
 * and the phone play their own haptics, so none of those pass through here.
 *
 * <p>The buzz Android plays after a long click listener answers comes from framework code, so the
 * windows are followed instead: while the switch is on, the long-clickable views under a finger
 * have their haptics off from the touch down to the lift. A view that takes text keeps its
 * selection buzz.
 *
 * <p>Off, paused, or a failure in here, and they play as TikTok asked.
 */
public final class Haptics {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Windows whose callback is wrapped, so none is wrapped twice. Main thread only. */
    private static final Map<Window, Boolean> WRAPPED = new WeakHashMap<>();
    /** The views quieted for the finger that's down, turned back on at its lift. */
    private static final List<WeakReference<View>> QUIETED = new ArrayList<>();
    /** The field that holds a window root's Window, per root class; null when it has none. */
    private static final Map<Class<?>, Field> WINDOW_FIELDS = new HashMap<>();
    private static boolean installed;

    private static final Application.ActivityLifecycleCallbacks CALLBACKS =
            new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) {
                    resumed(activity);
                }

                @Override public void onActivityCreated(Activity activity, Bundle state) {
                }

                @Override public void onActivityStarted(Activity activity) {
                }

                @Override public void onActivityPaused(Activity activity) {
                }

                @Override public void onActivityStopped(Activity activity) {
                }

                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {
                }

                @Override public void onActivityDestroyed(Activity activity) {
                }
            };

    private Haptics() {
    }

    /** Injection point, in place of each of TikTok's {@code View.performHapticFeedback(int)} calls. */
    public static boolean performHapticFeedback(View view, int feedbackConstant) {
        if (holdsBack("view haptic")) return false;
        return view.performHapticFeedback(feedbackConstant);
    }

    /** Injection point, in place of each of TikTok's {@code View.performHapticFeedback(int, int)} calls. */
    public static boolean performHapticFeedback(View view, int feedbackConstant, int flags) {
        if (holdsBack("view haptic")) return false;
        return view.performHapticFeedback(feedbackConstant, flags);
    }

    /** Injection point, in place of each of TikTok's {@code Vibrator.vibrate(long)} calls. */
    @SuppressWarnings("deprecation")
    public static void vibrate(Vibrator vibrator, long milliseconds) {
        if (holdsBack("timed vibration")) return;
        vibrator.vibrate(milliseconds);
    }

    /** Injection point, in place of each of TikTok's {@code Vibrator.vibrate(VibrationEffect)} calls. */
    @RequiresApi(26)
    public static void vibrate(Vibrator vibrator, VibrationEffect effect) {
        if (holdsBack("vibration")) return;
        vibrator.vibrate(effect);
    }

    /** Injection point, in place of each {@code Vibrator.vibrate(VibrationEffect, AudioAttributes)} call. */
    @RequiresApi(26)
    @SuppressWarnings("deprecation")
    public static void vibrate(Vibrator vibrator, VibrationEffect effect, AudioAttributes attributes) {
        if (holdsBack("vibration")) return;
        vibrator.vibrate(effect, attributes);
    }

    /**
     * Called from the host application's attachBaseContext with the application itself, before
     * any screen exists. Reads no setting: the extension's context isn't set yet.
     */
    public static void install(Context context) {
        try {
            if (installed || !(context instanceof Application)) return;
            installed = true;
            ((Application) context).registerActivityLifecycleCallbacks(CALLBACKS);
        } catch (Throwable error) {
            Logger.printInfo(() -> "Turn off haptics could not follow TikTok's screens: " + error);
        }
    }

    /**
     * A screen came to the front. Off leaves its window as it is, and a switch turned on in the
     * settings takes hold from the next screen to come to the front.
     */
    static void resumed(Activity activity) {
        if (!on()) return;
        watch(activity.getWindow());
        MAIN.post(Haptics::watchOtherWindows);
    }

    /** Wraps {@code window}'s callback once, as the idle wait does, to see where a finger lands. */
    private static void watch(Window window) {
        try {
            if (window == null || WRAPPED.containsKey(window)) return;
            Window.Callback inner = window.getCallback();
            if (inner == null) return;
            InvocationHandler watcher = (proxy, method, args) -> {
                String name = method.getName();
                MotionEvent touch = args != null && args.length == 1 && args[0] instanceof MotionEvent
                        && "dispatchTouchEvent".equals(name) ? (MotionEvent) args[0] : null;
                if (touch != null) pressed(window, touch);
                try {
                    return method.invoke(inner, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getCause() != null ? ex.getCause() : ex;
                } finally {
                    if (touch != null) lifted(touch);
                    // A panel with a window of its own, like a dialog, takes the focus as it opens.
                    if ("onWindowFocusChanged".equals(name)) MAIN.post(Haptics::watchOtherWindows);
                }
            };
            window.setCallback((Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class}, watcher));
            WRAPPED.put(window, Boolean.TRUE);
        } catch (Throwable ex) {
            Logger.printException(() -> "Turn off haptics: could not follow a window's touches", ex);
        }
    }

    /** A finger came down on {@code window}: the long-clickable views under it go quiet. */
    static void pressed(Window window, MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
        try {
            // A lift that never reached this window, as when a panel took the press over.
            giveBack();
            if (!on()) return;
            View decor = window.peekDecorView();
            if (decor != null) quietUnder(decor, event.getX(), event.getY());
            if (!QUIETED.isEmpty()) Logger.printDebug(() -> "Turn off haptics: quieted "
                    + QUIETED.size() + " long press views under a finger");
        } catch (Throwable failure) {
            Logger.printException(() -> "Turn off haptics: could not quiet a long press", failure);
        }
    }

    /** The finger went up, or the press was taken away: the quieted views get their haptics back. */
    static void lifted(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) giveBack();
    }

    /**
     * Turns off the haptics of each long-clickable view at {@code x, y} in {@code view}'s own
     * coordinates, that view included. A view whose haptics are already off is left as it is.
     */
    private static void quietUnder(View view, float x, float y) {
        if (view.getVisibility() != View.VISIBLE
                || x < 0 || y < 0 || x >= view.getWidth() || y >= view.getHeight()) {
            return;
        }
        if (view.isLongClickable() && view.isHapticFeedbackEnabled() && !takesText(view)) {
            view.setHapticFeedbackEnabled(false);
            QUIETED.add(new WeakReference<>(view));
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = group.getChildCount() - 1; index >= 0; index--) {
            View child = group.getChildAt(index);
            if (child == null) continue;
            // As ViewGroup hands a touch to a child: its scroll and position, then its transform.
            float[] point = {x + group.getScrollX() - child.getLeft(), y + group.getScrollY() - child.getTop()};
            Matrix matrix = child.getMatrix();
            if (!matrix.isIdentity()) {
                Matrix inverse = new Matrix();
                if (!matrix.invert(inverse)) continue;
                inverse.mapPoints(point);
            }
            quietUnder(child, point[0], point[1]);
        }
    }

    /** A text field or selectable text: its long press selects text, and keeps its buzz. */
    private static boolean takesText(View view) {
        return view.onCheckIsTextEditor()
                || (view instanceof TextView && ((TextView) view).isTextSelectable());
    }

    private static void giveBack() {
        for (WeakReference<View> quieted : QUIETED) {
            View view = quieted.get();
            if (view != null) view.setHapticFeedbackEnabled(true);
        }
        QUIETED.clear();
    }

    /** Wraps the windows a screen's own doesn't cover, like a dialog's. */
    static void watchOtherWindows() {
        try {
            if (!on()) return;
            for (View root : windowRoots()) {
                Window window = windowOf(root);
                if (window != null) watch(window);
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Turn off haptics: could not follow TikTok's other windows", failure);
        }
    }

    /** Every window root view in the process, or none when the platform won't say. */
    @SuppressWarnings("unchecked")
    private static List<View> windowRoots() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return new ArrayList<>(android.view.inspector.WindowInspector.getGlobalWindowViews());
            }
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            Method instance = global.getMethod("getInstance");
            Field views = global.getDeclaredField("mViews");
            views.setAccessible(true);
            Object held = views.get(instance.invoke(null));
            if (held instanceof List) return new ArrayList<>((List<View>) held);
        } catch (Throwable unavailable) {
            // The screens' own windows are still followed.
        }
        return Collections.emptyList();
    }

    /**
     * The Window a root view belongs to: a DecorView keeps it in {@code mWindow}, and Android 6's
     * inner DecorView in {@code this$0}. A popup's root has none, and neither does a view a
     * window manager was handed directly.
     */
    private static Window windowOf(View root) {
        Class<?> type = root.getClass();
        Field field;
        if (WINDOW_FIELDS.containsKey(type)) {
            field = WINDOW_FIELDS.get(type);
        } else {
            field = windowField(type);
            WINDOW_FIELDS.put(type, field);
        }
        if (field == null) return null;
        try {
            return (Window) field.get(root);
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private static Field windowField(Class<?> type) {
        for (Class<?> at = type; at != null && at != View.class; at = at.getSuperclass()) {
            for (String name : new String[]{"mWindow", "this$0"}) {
                try {
                    Field field = at.getDeclaredField(name);
                    if (!Window.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    return field;
                } catch (Throwable missing) {
                    // Not this class's, or not readable here.
                }
            }
        }
        return null;
    }

    private static boolean on() {
        try {
            return Settings.TURN_OFF_HAPTICS.get();
        } catch (Throwable failure) {
            return false;
        }
    }

    /** True when the switch holds back the haptic TikTok asked for. Never throws. */
    static boolean holdsBack(String what) {
        try {
            if (!Settings.TURN_OFF_HAPTICS.get()) return false;
            Logger.printDebug(() -> "Turn off haptics: held back a " + what);
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    static void resetForTests() {
        installed = false;
        giveBack();
    }
}

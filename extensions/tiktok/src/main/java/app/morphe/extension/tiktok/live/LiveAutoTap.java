/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Auto tap in LIVE rooms (#335). While the switch is on, a small chip sits in the room under the
 * Record chip. Tapping it starts sending taps to the host's side of the stream at a steady pace
 * with a little jitter, the same way a finger would: each tap goes in through the window as a touch down
 * and up, so the room's own like handling counts and batches them. Tapping it again stops.
 *
 * Taps wait while a real finger is on the screen, while a panel or the keyboard has the window,
 * and never land on anything that takes a tap. Tapping stops on its own when the room closes, a
 * swipe brings another room in front, TikTok goes to the background, the screen turns off or
 * locks, the switch goes off, or Hushfeed is paused. Main thread only.
 */
@SuppressWarnings("unused")
public final class LiveAutoTap {
    private LiveAutoTap() {
    }

    /** Why tapping ended. */
    enum End { STOPPED, LEFT_ROOM, OTHER_ROOM, BACKGROUND, SCREEN_OFF, SWITCHED_OFF, PANEL, FAILED }

    static final int MIN_RATE = 1;
    static final int MAX_RATE = 8;
    static final int DEFAULT_RATE = 4;
    /** No two taps are closer than this, whatever the jitter: the 8 a second cap. */
    static final long MIN_GAP_MS = 1000 / MAX_RATE;
    /** How often a held loop looks again; never sooner than {@link #MIN_GAP_MS}. */
    static final long HOLD_CHECK_MS = 250;
    /** The first tap comes this long after the chip is tapped. */
    static final long FIRST_TAP_MS = 300;
    /** A real touch holds the taps until this long after the finger lifts. */
    static final long FINGER_GRACE_MS = 400;
    /** The window losing focus this soon after a tap means the tap opened something. */
    static final long PANEL_AFTER_TAP_MS = 800;

    public static final String[] RATE_LABELS = {"2 taps a second", "4 taps a second", "6 taps a second", "8 taps a second"};
    public static final String[] RATE_VALUES = {"2", "4", "6", "8"};

    private static final long TICK_MS = 1000;
    /** Right under the Record chip (132dp plus its 48dp and the 8dp gap), so the two never trade places. */
    private static final int TOP_DP = 188;
    private static final int GAP_DP = 8;
    /** Space kept between a tap and anything that takes one. */
    private static final int CLEAR_DP = 16;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Random RANDOM = new Random();

    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static WeakReference<Object> tappingPage = new WeakReference<>(null);
    private static String tappingRoom;
    private static TextView chip;
    private static Loop loop;
    private static WeakReference<Application> callbacksOn = new WeakReference<>(null);

    // The finger watch: on the window only while tapping runs.
    private static Window watchedWindow;
    private static Window.Callback watchedInner;
    private static Window.Callback watchProxy;
    private static boolean watching;
    private static boolean injecting;
    private static boolean fingerDown;
    private static long lastFingerAt;
    private static long lastTapAt;
    private static int upGeneration;

    /** True while the switch is on and Hushfeed isn't paused. */
    static boolean enabled() {
        return SettingsStatus.liveControlsEnabled && Settings.LIVE_AUTO_TAP.get() && !Setting.isPaused();
    }

    /** The rate a stored value asks for, held between 1 and 8 taps a second. */
    static int rate(String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException unreadable) {
            return DEFAULT_RATE;
        }
        return Math.max(MIN_RATE, Math.min(MAX_RATE, parsed));
    }

    /** Called by the Record chip's room watch as a LIVE room page resumes in {@code activity}. */
    static void roomResumed(Activity activity) {
        try {
            if (activity == null) return;
            host = new WeakReference<>(activity);
            registerCallbacks(activity);
            tick();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not set up LIVE auto tap", failure);
        }
    }

    // ---- the loop ---------------------------------------------------------------------------

    /**
     * Paces the taps. Each step first asks whether tapping must end, then whether it must wait,
     * and only then taps and schedules the next step 1000/rate ms away with plus or minus a
     * quarter of jitter, never under {@link #MIN_GAP_MS}. A stop turns every step still queued
     * into nothing.
     */
    static final class Loop {
        interface Host {
            /** Why tapping must end now, or null to carry on. */
            End stopReason();
            /** Whether to skip this tap and look again shortly. */
            boolean holding();
            /** Sends one tap; false when there was nowhere free to tap. */
            boolean tap();
            void post(Runnable step, long delayMs);
            void stopped(End why, int taps);
        }

        private final Host host;
        private final int rate;
        private final Random random;
        private int generation;
        private boolean running;
        private int taps;

        Loop(Host host, int rate, Random random) {
            this.host = host;
            this.rate = Math.max(MIN_RATE, Math.min(MAX_RATE, rate));
            this.random = random;
        }

        void start() {
            if (running) return;
            running = true;
            taps = 0;
            int current = ++generation;
            host.post(() -> step(current), FIRST_TAP_MS);
        }

        void stop(End why) {
            if (!running) return;
            running = false;
            generation++;
            host.stopped(why, taps);
        }

        boolean running() {
            return running;
        }

        int taps() {
            return taps;
        }

        int rate() {
            return rate;
        }

        long nextGap() {
            long gap = Math.round(1000.0 / rate * (0.75 + 0.5 * random.nextDouble()));
            return Math.max(MIN_GAP_MS, gap);
        }

        private void step(int current) {
            if (!running || current != generation) return;
            End why = host.stopReason();
            if (why != null) {
                stop(why);
                return;
            }
            if (host.holding()) {
                host.post(() -> step(current), HOLD_CHECK_MS);
                return;
            }
            if (host.tap()) taps++;
            if (running && current == generation) host.post(() -> step(current), nextGap());
        }
    }

    private static final Loop.Host LIVE_HOST = new Loop.Host() {
        @Override public End stopReason() {
            return reasonToStop();
        }

        @Override public boolean holding() {
            return held();
        }

        @Override public boolean tap() {
            Activity activity = host.get();
            return activity != null && tapOnce(activity, RANDOM);
        }

        @Override public void post(Runnable step, long delayMs) {
            HANDLER.postDelayed(() -> {
                try {
                    step.run();
                } catch (Throwable failure) {
                    Logger.printException(() -> "LIVE auto tap failed", failure);
                    stop(End.FAILED);
                }
            }, delayMs);
        }

        @Override public void stopped(End why, int taps) {
            loop = null;
            unwatch();
            Logger.printDebug(() -> "LIVE auto tap stopped (" + why + ") after " + taps + " taps");
            if (why == End.PANEL) {
                skipSpots = skipAfterCard(skipSpots, lastSpot);
                Utils.showToastShort(L10n.t("Auto tap stopped"));
            }
            refresh();
        }
    };

    private static void start() {
        Activity activity = host.get();
        if (activity == null || !enabled() || loop != null) return;
        Object front = LiveRecordChip.pageInFront(activity);
        if (front == null) return;
        tappingPage = new WeakReference<>(front);
        tappingRoom = LiveRecordChip.roomKey(LiveRecordChip.roomOf(front));
        if (!sameRoom(skipRoom, tappingRoom)) {
            skipRoom = tappingRoom;
            skipSpots = 0;
        }
        lastSpot = -1;
        watch(activity.getWindow());
        Loop started = new Loop(LIVE_HOST, rate(Settings.LIVE_AUTO_TAP_RATE.get()), RANDOM);
        loop = started;
        started.start();
        refresh();
        scheduleTick();
    }

    private static void stop(End why) {
        Loop running = loop;
        if (running != null) running.stop(why);
    }

    /** Why the running loop must end, or null while the room it started in is still in front. */
    private static End reasonToStop() {
        if (!enabled()) return End.SWITCHED_OFF;
        Activity activity = host.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return End.LEFT_ROOM;
        if (!screenOn(activity)) return End.SCREEN_OFF;
        Window window = activity.getWindow();
        View decor = window == null ? null : window.getDecorView();
        if (decor == null || !decor.isShown()) return End.BACKGROUND;
        if (openedSomething(decor.hasWindowFocus(), lastTapAt, lastFingerAt, SystemClock.uptimeMillis())) {
            return End.PANEL;
        }
        Object front = LiveRecordChip.pageInFront(activity);
        if (front == null) return End.LEFT_ROOM;
        if (front != tappingPage.get()) {
            // A page can be rebuilt for the same LIVE; only another room ends the taps.
            if (!sameRoom(tappingRoom, LiveRecordChip.roomKey(LiveRecordChip.roomOf(front)))) return End.OTHER_ROOM;
            tappingPage = new WeakReference<>(front);
        }
        return null;
    }

    /** A window that lost focus right after a tap, with no finger since, was opened by that tap. */
    static boolean openedSomething(boolean focused, long lastTap, long lastFinger, long now) {
        return !focused && lastTap > 0 && lastTap >= lastFinger && now - lastTap < PANEL_AFTER_TAP_MS;
    }

    /** A room whose id can't be read never counts as the same one, so an unreadable swipe still stops. */
    static boolean sameRoom(String started, String now) {
        return started != null && started.equals(now);
    }

    /** Taps wait for a finger on the screen, a panel holding the window, or typing. */
    private static boolean held() {
        if (fingerHolds(fingerDown, lastFingerAt, SystemClock.uptimeMillis())) return true;
        Activity activity = host.get();
        Window window = activity == null ? null : activity.getWindow();
        if (window == null || !window.getDecorView().hasWindowFocus()) return true;
        View focus = activity.getCurrentFocus();
        return focus instanceof EditText && focus.isShown();
    }

    static boolean fingerHolds(boolean down, long lastFinger, long now) {
        return down || (lastFinger > 0 && now - lastFinger < FINGER_GRACE_MS);
    }

    private static boolean screenOn(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power != null && !power.isInteractive()) return false;
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard == null || !keyguard.isKeyguardLocked();
    }

    // ---- one tap ----------------------------------------------------------------------------

    /**
     * Where the taps go, as fractions of the room's width and height, tried in this order. All of
     * them sit on the host's side: the left pane in a battle and the top-left tile in a guest grid,
     * which are drawn into one mixed stream with no views to dodge. The other creator's or a
     * guest's part of the picture opens their card when tapped (#335). In a room with only the
     * host, the left side likes the same as the middle.
     */
    private static final float[][] SPOTS = {{0.25f, 0.38f}, {0.25f, 0.30f}, {0.25f, 0.46f}, {0.15f, 0.38f}, {0.36f, 0.38f}};
    /** Every spot skipped. */
    private static final int ALL_SPOTS = (1 << SPOTS.length) - 1;

    // A spot whose tap opened a card is skipped the next time tapping starts in that room.
    private static String skipRoom;
    private static int skipSpots;
    private static int lastSpot = -1;

    /**
     * A point in a {@code width} by {@code height} room to tap, kept {@code clear} pixels away from
     * every rect in {@code taken} and off the spots set in {@code skip}, as {x, y, spot index}, or
     * null when every spot is covered. Each spot moves up to 3% of the room each way so the taps
     * don't all hit one pixel.
     */
    static float[] tapPoint(int width, int height, List<Rect> taken, int clear, int skip, Random random) {
        if (width <= 0 || height <= 0) return null;
        for (int i = 0; i < SPOTS.length; i++) {
            if ((skip & (1 << i)) != 0) continue;
            float x = width * (SPOTS[i][0] + (random.nextFloat() - 0.5f) * 0.06f);
            float y = height * (SPOTS[i][1] + (random.nextFloat() - 0.5f) * 0.06f);
            if (free(x, y, taken, clear)) return new float[]{x, y, i};
        }
        return null;
    }

    /** The spots to skip once the tap at {@code spot} opened a card; when every spot has, they all get another go. */
    static int skipAfterCard(int skip, int spot) {
        if (spot < 0 || spot >= SPOTS.length) return skip;
        int next = skip | (1 << spot);
        return next == ALL_SPOTS ? 0 : next;
    }

    private static boolean free(float x, float y, List<Rect> taken, int clear) {
        for (Rect rect : taken) {
            if (x > rect.left - clear && x < rect.right + clear && y > rect.top - clear && y < rect.bottom + clear) {
                return false;
            }
        }
        return true;
    }

    /**
     * Sends one tap into {@code activity}'s room: a touch down now and the matching up 40 to 70 ms
     * later at the same point, both dispatched through the window like a finger's. A real touch in
     * between takes over the gesture and the up is dropped. False when nothing free was found.
     */
    static boolean tapOnce(Activity activity, Random random) {
        Window window = activity.getWindow();
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (window == null || content == null) return false;
        View decor = window.getDecorView();
        Rect area = new Rect();
        if (!content.isShown() || !content.getGlobalVisibleRect(area)) return false;
        List<Rect> taken = new ArrayList<>();
        LiveRecordChip.tapTargets(content, null, area, (long) area.width() * area.height() / 4, taken);
        float[] point = tapPoint(area.width(), area.height(), taken, dp(activity, CLEAR_DP), skipSpots, random);
        if (point == null) return false;
        lastSpot = (int) point[2];
        float x = area.left + point[0];
        float y = area.top + point[1];
        long downAt = SystemClock.uptimeMillis();
        inject(decor, downAt, downAt, MotionEvent.ACTION_DOWN, x, y);
        lastTapAt = downAt;
        int generation = ++upGeneration;
        HANDLER.postDelayed(() -> {
            if (generation != upGeneration) return;
            try {
                inject(decor, downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y);
            } catch (Throwable failure) {
                Logger.printException(() -> "LIVE auto tap could not lift", failure);
            }
        }, 40 + random.nextInt(31));
        return true;
    }

    private static void inject(View decor, long downAt, long at, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downAt, at, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        injecting = true;
        try {
            decor.dispatchTouchEvent(event);
        } finally {
            injecting = false;
            event.recycle();
        }
    }

    // ---- the finger watch -------------------------------------------------------------------

    /** Wraps the window's callback to see real touches; on only while tapping runs. */
    private static void watch(Window window) {
        if (window == null || watching) return;
        Window.Callback inner = window.getCallback();
        if (inner == null) return;
        InvocationHandler watcher = (proxy, method, args) -> {
            if (watching && !injecting && args != null && args.length == 1 && args[0] instanceof MotionEvent
                    && "dispatchTouchEvent".equals(method.getName())) {
                finger(((MotionEvent) args[0]).getActionMasked());
            }
            try {
                return method.invoke(inner, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause() != null ? ex.getCause() : ex;
            }
        };
        Window.Callback proxy = (Window.Callback) Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(), new Class<?>[]{Window.Callback.class}, watcher);
        window.setCallback(proxy);
        watchedWindow = window;
        watchedInner = inner;
        watchProxy = proxy;
        watching = true;
        fingerDown = false;
        lastFingerAt = 0;
        lastTapAt = 0;
    }

    /** Puts the window's own callback back; one wrapped over ours since then keeps a dormant pass-through. */
    private static void unwatch() {
        watching = false;
        fingerDown = false;
        upGeneration++;
        Window window = watchedWindow;
        if (window != null && window.getCallback() == watchProxy) window.setCallback(watchedInner);
        watchedWindow = null;
        watchedInner = null;
        watchProxy = null;
    }

    private static void finger(int action) {
        long now = SystemClock.uptimeMillis();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                fingerDown = true;
                // The new gesture replaces any half-sent tap: its up must not land in this one.
                upGeneration++;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                fingerDown = false;
                break;
            default:
                break;
        }
        lastFingerAt = now;
    }

    // ---- the chip ---------------------------------------------------------------------------

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void showChip(Activity activity) {
        if (chip != null && chip.getContext() == activity && chip.getParent() != null) return;
        removeChip();
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        TextView view = new TextView(activity);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(activity, 48));
        view.setMinWidth(dp(activity, 48));
        view.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(activity, 24));
        background.setColor(0xB3000000);
        view.setBackground(background);
        view.setOnClickListener(clicked -> onChipTap());
        LiveRecordChip.markStacked(view, LiveRecordChip.STACK_AUTO_TAP, dp(activity, TOP_DP), dp(activity, GAP_DP));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        params.topMargin = dp(activity, TOP_DP);
        params.rightMargin = dp(activity, 12);
        content.addView(view, params);
        chip = view;
        view.post(() -> stayClear(activity));
    }

    /** Moves the chip below anything to tap where it sits, the Record chip included, with the rest of the stack. */
    private static void stayClear(Activity activity) {
        TextView view = chip;
        if (view != null && view.getParent() instanceof ViewGroup) LiveRecordChip.restack((ViewGroup) view.getParent());
    }

    private static void removeChip() {
        TextView view = chip;
        chip = null;
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private static void refresh() {
        TextView view = chip;
        if (view == null) return;
        Loop running = loop;
        // Clear screen leaves only the stream; a running chip stays so Stop is always in reach.
        // A screen recording hides it even then, so it isn't filmed (#265); it comes back after.
        boolean hidden = LiveRecordChip.filmingScreen() || (running == null && LiveRecordChip.clearScreenOn());
        view.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        if (running != null) {
            view.setText("● " + running.taps() + "  " + L10n.t("Stop"));
            view.setContentDescription(L10n.t("Stop tapping this LIVE"));
        } else {
            view.setText(L10n.t("Auto tap"));
            view.setContentDescription(L10n.t("Start tapping this LIVE"));
        }
    }

    private static void onChipTap() {
        try {
            if (loop != null) stop(End.STOPPED);
            else start();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not use the LIVE auto tap button", failure);
            stop(End.FAILED);
        }
    }

    // ---- watching the room ------------------------------------------------------------------

    private static final Runnable TICK = LiveAutoTap::tick;

    /** One check pending at a time; a room resuming checks at once and starts the count again. */
    private static void scheduleTick() {
        HANDLER.removeCallbacks(TICK);
        HANDLER.postDelayed(TICK, TICK_MS);
    }

    private static void tick() {
        HANDLER.removeCallbacks(TICK);
        try {
            Activity activity = host.get();
            Object front = activity != null && enabled() ? LiveRecordChip.pageInFront(activity) : null;
            if (front == null) {
                stop(enabled() ? End.LEFT_ROOM : End.SWITCHED_OFF);
                removeChip();
                return;
            }
            if (loop != null) {
                End why = reasonToStop();
                if (why != null) stop(why);
            }
            showChip(activity);
            refresh();
            stayClear(activity);
            scheduleTick();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not check the LIVE room for auto tap", failure);
        }
    }

    private static void registerCallbacks(Activity activity) {
        Application application = activity.getApplication();
        if (application == null || callbacksOn.get() == application) return;
        callbacksOn = new WeakReference<>(application);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPaused(Activity paused) {
                if (paused == host.get()) {
                    // The room's next resume checks again and puts the chip back.
                    HANDLER.removeCallbacks(TICK);
                    stop(End.BACKGROUND);
                    removeChip();
                }
            }

            @Override public void onActivityDestroyed(Activity destroyed) {
                if (destroyed == host.get()) {
                    HANDLER.removeCallbacks(TICK);
                    stop(End.LEFT_ROOM);
                    removeChip();
                    host = new WeakReference<>(null);
                    tappingPage = new WeakReference<>(null);
                }
            }

            @Override public void onActivityCreated(Activity created, Bundle state) { }
            @Override public void onActivityStarted(Activity started) { }
            @Override public void onActivityResumed(Activity resumed) { }
            @Override public void onActivityStopped(Activity stopped) { }
            @Override public void onActivitySaveInstanceState(Activity saved, Bundle state) { }
        });
    }

    // ---- for the tests ----------------------------------------------------------------------

    /** Wraps {@code window} the way a start does, so a test can send real and injected touches. */
    static void watchForTests(Window window) {
        watch(window);
    }

    static void unwatchForTests() {
        unwatch();
    }

    static void forgetSkippedSpotsForTests() {
        skipRoom = null;
        skipSpots = 0;
        lastSpot = -1;
    }

    /** Whether a real finger is holding the taps right now. */
    static boolean fingerHoldsForTests() {
        return fingerHolds(fingerDown, lastFingerAt, SystemClock.uptimeMillis());
    }
}

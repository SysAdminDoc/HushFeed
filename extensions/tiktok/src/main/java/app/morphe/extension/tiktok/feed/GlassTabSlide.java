/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 *
 * Forked from: https://github.com/SysAdminDoc/HushGram/blob/main/extensions/instagram/src/main/java/app/hushgram/extension/instagram/misc/GlassTabSlide.java
 */
package app.morphe.extension.tiktok.feed;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;

import app.morphe.extension.shared.Logger;

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.WeakHashMap;

/**
 * Slide across the tabs: a finger that goes down on the glass pill and moves sideways carries the
 * lens with it, and lifting it opens the tab it ended on, the way HushGram's glass tab bar and the
 * iPhone's do.
 *
 * <p>Like Swipe for brightness and volume, it wraps the window's callback so it sees each touch
 * before the screen does. A touch that starts on the pill goes on to TikTok as it is, so a tap
 * still opens its tab and a long press still does what it did. It turns into a slide only once
 * the finger has gone sideways past the touch slop, and further sideways than up or down. TikTok
 * then gets a cancel, as when the system takes a gesture away, so the tab under the finger
 * neither clicks nor long presses, and the rest of the gesture stays here. A finger held still on
 * a tab that has a long press of its own is TikTok's once the long press time has passed.
 *
 * <p>While it slides, the lens follows the finger ({@link Host#follow}). Lifting the finger clicks
 * the tab under it, or the nearest one, unless that tab is already open. A cancel from the system
 * clicks nothing. Either way the lens goes back to the selected tab, sliding as it does after a
 * tap. With the switch off or the lens gone, every touch goes on and nothing starts. Anything
 * that throws ends the slide and the touch goes on to TikTok.
 */
final class GlassTabSlide {
    /** What a slide needs from the glass it moves. */
    interface Host {
        /** Whether {@code x}, {@code y}, in the bar's own pixels, are on the pill. */
        boolean onPill(float x, float y);

        /** Puts the lens's middle at {@code x}, in the bar's own pixels, or sends it back to the selected tab for NaN. */
        void follow(float x);
    }

    /** Whether slides may start and go on right now; java.util.function needs API 24. */
    interface Running {
        boolean now();
    }

    static final int IDLE = 0;
    /** A finger went down on the pill and may still turn into a slide. Its touches go on to TikTok. */
    static final int WATCHING = 1;
    /** Sliding: TikTok had its cancel, and the rest of the gesture is kept from it. */
    static final int SLIDING = 2;
    /** Not a slide: the rest of the gesture is TikTok's. */
    static final int REFUSED = 3;
    /** A slide stopped by the switch going off. TikTok already had its cancel, so the rest goes nowhere. */
    static final int DROPPED = 4;

    /** The watcher in each window's callbacks, held weakly both ways so a closed window takes it along. */
    private static final WeakHashMap<Window, WeakReference<Watcher>> WATCHERS = new WeakHashMap<>();

    private final ViewGroup bar;
    private final Host host;
    private final Running running;
    private final float slop;
    private final int[] place = new int[2];
    private int state = IDLE;
    private float downX, downY;
    /** The bar's left edge on the screen as the finger went down. The bar can't move while a finger slides. */
    private float barLeft;
    /** Whether the tab under the finger as it went down has a long press of its own. */
    private boolean tabHolds;

    GlassTabSlide(ViewGroup bar, Host host, Running running) {
        this.bar = bar;
        this.host = host;
        this.running = running;
        this.slop = ViewConfiguration.get(bar.getContext()).getScaledTouchSlop();
    }

    /** Where the gesture under way stands: {@link #IDLE}, {@link #WATCHING}, {@link #SLIDING} and so on. */
    int state() {
        return state;
    }

    ViewGroup bar() {
        return bar;
    }

    /**
     * Wraps {@code window}'s callback so {@code slide} sees its touches. Once per window: a window
     * already wrapped for an earlier bar (one TikTok rebuilt) hands its touches to {@code slide}.
     */
    static void install(Window window, GlassTabSlide slide) {
        WeakReference<Watcher> known = WATCHERS.get(window);
        Watcher watcher = known == null ? null : known.get();
        if (watcher != null) {
            if (watcher.slide != slide) {
                watcher.slide.giveUp();
                watcher.slide = slide;
            }
            return;
        }
        Window.Callback inner = window.getCallback();
        if (inner == null) return;
        watcher = new Watcher(inner, slide);
        window.setCallback((Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class}, watcher));
        WATCHERS.put(window, new WeakReference<>(watcher));
    }

    /** The slide {@code window}'s watcher hands its touches to, or null when it has none. */
    static GlassTabSlide installedIn(Window window) {
        WeakReference<Watcher> known = WATCHERS.get(window);
        Watcher watcher = known == null ? null : known.get();
        return watcher == null ? null : watcher.slide;
    }

    /**
     * Passes every call on to the callback it wraps, whatever the call is, so a method added in a
     * newer Android or a callback the app swaps in behaves as it did. Only a slide's touches are kept.
     */
    private static final class Watcher implements InvocationHandler {
        final Window.Callback inner;
        /** The bar's slide now: a rebuilt bar takes over the window's watcher. Main thread only. */
        GlassTabSlide slide;

        Watcher(Window.Callback inner, GlassTabSlide slide) {
            this.inner = inner;
            this.slide = slide;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 1 && args[0] instanceof MotionEvent
                    && "dispatchTouchEvent".equals(method.getName())) {
                boolean kept;
                try {
                    kept = slide.onTouch((MotionEvent) args[0], inner);
                } catch (RuntimeException failure) {
                    Logger.printException(() -> "Could not slide across the tabs", failure);
                    slide.giveUp();
                    kept = false;
                }
                if (kept) return Boolean.TRUE;
            }
            try {
                return method.invoke(inner, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause() == null ? failure : failure.getCause();
            }
        }
    }

    /**
     * Follows {@code event} and answers whether it's kept from TikTok. {@code inner} is the
     * callback TikTok's touches go on to. It gets a cancel as a slide starts.
     */
    boolean onTouch(MotionEvent event, Window.Callback inner) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // A slide whose end never came lets the lens go back first.
            if (state == SLIDING) host.follow(Float.NaN);
            state = IDLE;
            if (!running.now() || !barStill()) return false;
            bar.getLocationOnScreen(place);
            float x = event.getRawX() - place[0];
            float y = event.getRawY() - place[1];
            if (!host.onPill(x, y)) return false;
            View tab = tabAt(x);
            tabHolds = tab != null && tab.isLongClickable();
            barLeft = place[0];
            downX = event.getRawX();
            downY = event.getRawY();
            state = WATCHING;
            return false;
        }
        boolean ends = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        switch (state) {
            case WATCHING:
                if (ends || action == MotionEvent.ACTION_POINTER_DOWN || !running.now()) {
                    state = ends ? IDLE : REFUSED;
                    return false;
                }
                if (action != MotionEvent.ACTION_MOVE) return false;
                float dx = Math.abs(event.getRawX() - downX);
                float dy = Math.abs(event.getRawY() - downY);
                if (dx < slop || dx <= dy) {
                    // Up or down first: a swipe on the bar, never a slide.
                    if (dy >= slop) state = REFUSED;
                    return false;
                }
                if (tabHolds && event.getEventTime() - event.getDownTime() >= ViewConfiguration.getLongPressTimeout()) {
                    // The tab's own long press has had its turn.
                    state = REFUSED;
                    return false;
                }
                start(event, inner);
                return true;
            case SLIDING:
                if (!running.now()) {
                    state = ends ? IDLE : DROPPED;
                    host.follow(Float.NaN);
                    return true;
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    host.follow(event.getRawX() - barLeft);
                } else if (ends) {
                    state = IDLE;
                    if (action == MotionEvent.ACTION_UP) open(event.getRawX() - barLeft);
                    host.follow(Float.NaN);
                }
                return true;
            case DROPPED:
                if (ends) state = IDLE;
                return true;
            case REFUSED:
                if (ends) state = IDLE;
                return false;
            default:
                return false;
        }
    }

    /** Ends whatever was under way after a failure, so the rest of the gesture is TikTok's. */
    void giveUp() {
        boolean sliding = state == SLIDING;
        state = REFUSED;
        if (!sliding) return;
        try {
            host.follow(Float.NaN);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not slide across the tabs", failure);
        }
    }

    /** Whether the bar is attached, shown and not moved off its place by an animation. */
    private boolean barStill() {
        return bar.isAttachedToWindow() && bar.isShown() && Math.abs(bar.getTranslationY()) < 1f;
    }

    /** TikTok's own touch handling of the gesture ends the way it does when the system takes a gesture away. */
    private void start(MotionEvent event, Window.Callback inner) {
        state = SLIDING;
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try {
            inner.dispatchTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
        host.follow(event.getRawX() - barLeft);
    }

    /** Clicks the tab at {@code x}, in the bar's pixels, unless it's already open. */
    private void open(float x) {
        View tab = tabAt(x);
        if (tab == null || tab.isSelected()) return;
        tab.performClick();
    }

    /**
     * The shown tab whose middle is nearest {@code x}, in the bar's pixels, or null with none.
     * TikTok's tabs are wider than the slots the pill spreads them over, so neighbours overlap,
     * and the nearest middle is the slot the finger is in.
     */
    View tabAt(float x) {
        View nearest = null;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < bar.getChildCount(); i++) {
            View tab = bar.getChildAt(i);
            if (tab.getVisibility() != View.VISIBLE || tab.getWidth() <= 0) continue;
            float off = Math.abs(x - ((tab.getLeft() + tab.getRight()) / 2f + tab.getTranslationX()));
            if (off < best) {
                best = off;
                nearest = tab;
            }
        }
        return nearest;
    }
}

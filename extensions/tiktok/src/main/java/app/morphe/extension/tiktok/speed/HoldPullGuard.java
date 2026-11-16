package app.morphe.extension.tiktok.speed;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;
import java.lang.ref.WeakReference;

/**
 * Keeps a held video's pull with TikTok's lock (#422). TikTok's hold listener reads every move
 * while the video plays at the hold speed, but it never asks the views around it to leave the
 * finger alone, so the feed takes a pull down as a scroll back to the video before, or as For
 * You's refresh, and the hold is cancelled before its lock can arm. A slide on the hold speed
 * bubble loses its moves the same way.
 *
 * <p>When a hold's speed-up starts with the lock or the slide on, the view TikTok's listener sits
 * on asks every parent to keep its hands off the rest of that touch. The views clear the request
 * themselves when the finger lifts, so the next swipe scrolls the feed as always.
 */
public final class HoldPullGuard {
    private static WeakReference<View> held = new WeakReference<>(null);
    private static boolean down;

    private HoldPullGuard() {}

    /** Called with every event TikTok's hold listener gets, with the view it sits on. */
    public static void onHoldTouch(View view, MotionEvent event) {
        if (event == null) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                held = new WeakReference<>(view);
                down = true;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                down = false;
                held.clear();
                break;
            default:
                break;
        }
    }

    /**
     * Called from the hold's press, past its "already held" guard, so once per speed-up. TikTok's
     * first-run guide plays the same press with no finger down, and that one is left alone.
     */
    public static void onSpeedUp() {
        try {
            if (!down || !wanted()) return;
            View view = held.get();
            ViewParent parent = view == null ? null : view.getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not keep the held video's pull from the feed", ex);
        }
    }

    static boolean wanted() {
        return Settings.ENABLE_LONG_PRESS_SPEED_LOCK.get() || HoldSpeedHud.enabled();
    }

    /** Clears what a test left behind. */
    static void resetForTests() {
        held = new WeakReference<>(null);
        down = false;
    }
}

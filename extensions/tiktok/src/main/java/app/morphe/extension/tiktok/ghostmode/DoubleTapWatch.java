/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * Tells two quick taps in one spot from everything else a finger does on a LIVE (D#359). It only
 * watches, never takes a touch, so the room's own swipes, holds and taps go on as before. A tap is
 * a press shorter than a long press that stays within the touch slop; two count as a double tap
 * when the second press starts within the double tap timeout of the first lift, near the first,
 * and the second lift is what reports it, so a hold or a drag after one tap never does.
 */
final class DoubleTapWatch {
    /** Two lifts closer together than this are one bouncing finger, as in Android's GestureDetector. */
    private static final long MIN_GAP_MS = 40;

    private final float touchSlop;
    private final float doubleTapSlop;

    private boolean counting;
    private float downX;
    private float downY;
    private long downAt;
    private boolean haveTap;
    private float tapX;
    private float tapY;
    private long tapUpAt;

    DoubleTapWatch(float touchSlop, float doubleTapSlop) {
        this.touchSlop = touchSlop;
        this.doubleTapSlop = doubleTapSlop;
    }

    /** Feeds one touch event, in window coordinates; true when it ends a double tap. */
    boolean feed(int action, float x, float y, long at) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (haveTap && (at - tapUpAt > ViewConfiguration.getDoubleTapTimeout()
                        || at - tapUpAt < MIN_GAP_MS || far(x, y, tapX, tapY, doubleTapSlop))) {
                    haveTap = false;
                }
                counting = true;
                downX = x;
                downY = y;
                downAt = at;
                return false;
            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger is a pinch, not a tap.
                skipGesture();
                return false;
            case MotionEvent.ACTION_MOVE:
                if (counting && far(x, y, downX, downY, touchSlop)) skipGesture();
                return false;
            case MotionEvent.ACTION_UP:
                if (!counting) return false;
                counting = false;
                if (at - downAt >= ViewConfiguration.getLongPressTimeout() || far(x, y, downX, downY, touchSlop)) {
                    haveTap = false;
                    return false;
                }
                if (haveTap) {
                    // A third tap starts over rather than counting as a second double tap.
                    haveTap = false;
                    return true;
                }
                haveTap = true;
                tapX = x;
                tapY = y;
                tapUpAt = at;
                return false;
            case MotionEvent.ACTION_CANCEL:
                skipGesture();
                return false;
            default:
                return false;
        }
    }

    /** The finger now on the screen doesn't count, and neither does a tap before it. */
    void skipGesture() {
        counting = false;
        haveTap = false;
    }

    private static boolean far(float x, float y, float fromX, float fromY, float slop) {
        float dx = x - fromX;
        float dy = y - fromY;
        return dx * dx + dy * dy > slop * slop;
    }
}

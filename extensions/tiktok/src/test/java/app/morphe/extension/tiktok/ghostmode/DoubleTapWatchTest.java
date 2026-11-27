/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_POINTER_DOWN;
import static android.view.MotionEvent.ACTION_UP;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.ViewConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** What counts as the double tap that lets a LIVE host see you (D#359), and what doesn't. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class DoubleTapWatchTest {
    private static final float SLOP = 20;
    private static final float DOUBLE_SLOP = 100;

    private final DoubleTapWatch taps = new DoubleTapWatch(SLOP, DOUBLE_SLOP);

    /** One tap at ({@code x}, {@code y}) pressed at {@code at} for {@code heldMs}; true if it ended a double tap. */
    private boolean tap(float x, float y, long at, long heldMs) {
        assertFalse("a press alone is never a double tap", taps.feed(ACTION_DOWN, x, y, at));
        return taps.feed(ACTION_UP, x, y, at + heldMs);
    }

    @Test public void twoQuickTapsInOneSpotAreADoubleTap() {
        assertFalse(tap(500, 800, 1000, 60));
        assertTrue(tap(510, 805, 1150, 60));
    }

    @Test public void aThirdTapStartsOverAndAFourthMakesTheNextOne() {
        assertFalse(tap(500, 800, 1000, 50));
        assertTrue(tap(500, 800, 1120, 50));
        assertFalse("the third tap is a first tap", tap(500, 800, 1240, 50));
        assertTrue(tap(500, 800, 1360, 50));
    }

    @Test public void tapsTooFarApartInTimeOrPlaceAreTwoTaps() {
        long late = ViewConfiguration.getDoubleTapTimeout() + 1;
        assertFalse(tap(500, 800, 1000, 50));
        assertFalse("too late", tap(500, 800, 1050 + late, 50));
        assertFalse("too far from the one before", tap(500, 800 + DOUBLE_SLOP + 1, 1050 + late + 120, 50));
        assertFalse("a bounce, under 40 ms after the lift", tap(500, 800 + DOUBLE_SLOP + 1, 1050 + late + 120 + 50 + 20, 50));
    }

    @Test public void aHoldADragOrAPinchIsNoTap() {
        assertFalse(tap(500, 800, 1000, 50));
        // The second press is held into a long press: the room's own menu, not a double tap.
        assertFalse(tap(500, 800, 1120, ViewConfiguration.getLongPressTimeout()));

        assertFalse(tap(500, 800, 5000, 50));
        taps.feed(ACTION_DOWN, 500, 800, 5120);
        taps.feed(ACTION_MOVE, 500, 800 + SLOP + 1, 5140);
        assertFalse("a swipe to the next LIVE", taps.feed(ACTION_UP, 500, 800 + SLOP + 1, 5160));

        assertFalse(tap(500, 800, 9000, 50));
        taps.feed(ACTION_DOWN, 500, 800, 9120);
        taps.feed(ACTION_POINTER_DOWN, 600, 900, 9130);
        assertFalse("two fingers", taps.feed(ACTION_UP, 500, 800, 9160));

        assertFalse(tap(500, 800, 13000, 50));
        taps.feed(ACTION_DOWN, 500, 800, 13120);
        taps.feed(ACTION_CANCEL, 500, 800, 13130);
        assertFalse("taken by something else", taps.feed(ACTION_UP, 500, 800, 13160));
    }

    @Test public void aSkippedGestureCountsForNeitherTap() {
        assertFalse(tap(500, 800, 1000, 50));
        taps.feed(ACTION_DOWN, 500, 800, 1120);
        taps.skipGesture();
        assertFalse("the skipped press", taps.feed(ACTION_UP, 500, 800, 1170));
        assertFalse("and the next tap is a first tap again", tap(500, 800, 1250, 50));
        assertTrue(tap(500, 800, 1370, 50));
    }
}

package app.morphe.extension.tiktok;

import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

/** A mouse wheel turned over a view, handed out the way Android hands one out. */
public final class Wheel {
    private Wheel() {}

    /**
     * Turns the wheel {@code notches} at (x, y) in {@code view}'s coordinates, a positive turn
     * being away from the reader. Answers whether something took it.
     */
    public static boolean turn(View view, float x, float y, float notches) {
        MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
        properties.id = 0;
        properties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, notches);
        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_SCROLL, 1,
                new MotionEvent.PointerProperties[] {properties}, new MotionEvent.PointerCoords[] {coords},
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        try {
            return view.dispatchGenericMotionEvent(event);
        } finally {
            event.recycle();
        }
    }

    /** Turns the wheel one notch toward the reader at the middle of {@code view}. */
    public static boolean turn(View view) {
        return turn(view, view.getWidth() / 2f, view.getHeight() / 2f, -1f);
    }

    /** Counts the turns that reach {@code view}, which takes each one the way a list does. */
    public static int[] count(View view) {
        int[] turns = {0};
        view.setOnGenericMotionListener((target, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_SCROLL) return false;
            turns[0]++;
            return true;
        });
        return turns;
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.speed;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.lang.ref.WeakReference;
import java.util.TreeSet;

/**
 * Slide to change the hold speed (#248). Holding a video plays it at the hold speed, as TikTok
 * does; with {@link Settings#HOLD_SPEED_SLIDE} on, a bubble shows that speed and sliding left or
 * right moves it one step at a time through the speeds the speed menu offers (the custom list
 * when one is set, else every {@link PlaybackSpeedPatch#LIVE_STEP} from
 * {@link PlaybackSpeedPatch#MIN_SPEED} to {@link PlaybackSpeedPatch#MAX_SPEED}).
 *
 * <p>The finger has to go {@link #DEAD_ZONE_DP} sideways before the slide starts and then
 * {@link #STEP_DP} per step, counted from the last step taken, so a finger resting on a boundary
 * doesn't flicker between two speeds. A hold that first goes {@link #AXIS_LOCK_DP} down is a
 * pull toward TikTok's lock and doesn't slide. While the hold is pulled down far enough to lock,
 * the speed stays put and the bubble's padlock lights; pulling back up lets the slide go on.
 *
 * <p>The speed reaches the player through the gesture's own apply call, the one TikTok plays its
 * 2x with, so TikTok's speed state is left alone and letting go returns the video to the speed it
 * had, as it always does. TikTok's pull-down lock stays the only lock: the slid speed is what
 * {@link PlaybackSpeedPatch#holdSpeed} answers, so a lock keeps it, its toast names it, and the
 * next hold starts there, until the hold speed row under Speed is changed. The finger is read
 * from the gesture's own touch listener, which already has the move events while the video is
 * held; nothing here takes a touch from TikTok.
 *
 * <p>There is one bubble at most. A new hold, letting go, the video on screen changing, the window
 * losing focus (a pause, a dialog, the notification shade), Hushfeed Pause and the switch going off
 * each take it down, and a recreated activity drops it with its old views.
 */
public final class HoldSpeedHud {
    /** How far the finger moves sideways before the slide starts, then how far per step, in dp. */
    static final float DEAD_ZONE_DP = 16f;
    static final float STEP_DP = 32f;
    /** How far down a hold goes, more down than sideways, before it counts as a pull and not a slide. */
    static final float AXIS_LOCK_DP = 18f;

    private static WeakReference<Object> gesture = new WeakReference<>(null);
    private static WeakReference<Bubble> bubble = new WeakReference<>(null);
    private static boolean holding;
    private static float[] steps = new float[0];
    private static int index;
    private static float anchorX = Float.NaN;
    private static float anchorY = Float.NaN;
    private static float originX = Float.NaN;
    private static boolean sliding;
    private static boolean vertical;
    private static boolean pulled;
    private static String heldVideo = "";
    /**
     * The speed last slid to and the hold speed row it was slid from, or NaN. Read by the
     * gesture's literals off the main thread too; the speed is written first.
     */
    private static volatile float slidSpeed = Float.NaN;
    private static volatile String slidFrom;

    /** What the gesture component's apply call does, so a test can stand in for TikTok's. */
    public interface Applier {
        void apply(Object gesture, float speed);
    }

    /** Set by tests only. Unpatched, as in the tests, {@link #applyNative} does nothing. */
    public static volatile Applier applierForTests;

    private HoldSpeedHud() {}

    static boolean enabled() {
        return SettingsStatus.holdSpeedSlideEnabled && Settings.HOLD_SPEED_SLIDE.get() && !Setting.isPaused();
    }

    /**
     * The speed last slid to, or NaN when there is none, the switch is off, or the hold speed
     * row has changed since.
     */
    static float slidSpeed() {
        if (!enabled()) return Float.NaN;
        String from = slidFrom;
        return from != null && from.equals(Settings.HOLD_SPEED.get()) ? slidSpeed : Float.NaN;
    }

    /**
     * Replaced with a call to the gesture component's apply method, the (F)V call its press plays
     * the hold speed with.
     */
    public static void applyNative(Object gesture, float speed) { }

    /** Called from the gesture's press, past its "already held" guard, with the component. */
    public static void onHoldPress(Object component) {
        try {
            dismiss();
            holding = false;
            if (!enabled() || component == null) return;
            gesture = new WeakReference<>(component);
            float slid = slidSpeed();
            float start = Float.isNaN(slid) ? PlaybackSpeedPatch.boostSpeed() : slid;
            steps = stepsFor(start, Settings.CUSTOM_SPEEDS.get());
            index = indexOf(steps, start);
            anchorX = Float.NaN;
            anchorY = Float.NaN;
            sliding = false;
            vertical = false;
            pulled = false;
            heldVideo = PlaybackSpeedPatch.onScreenVideoId();
            holding = true;
            show(start);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not start the hold speed bubble", ex);
        }
    }

    /**
     * Called with every event the gesture's touch listener gets. Only a move during a hold does
     * anything: the first one marks where the finger is, later ones step the speed by how far it
     * moved sideways from there. Up and cancel are left to the gesture's release.
     */
    public static void onHoldTouch(MotionEvent event) {
        if (!holding || event == null || event.getActionMasked() != MotionEvent.ACTION_MOVE) return;
        try {
            if (!enabled()) {
                end();
                return;
            }
            // Raw, so the bubble or a view moving under the finger can't shift where it seems to be.
            float x = event.getRawX();
            float y = event.getRawY();
            if (Float.isNaN(anchorX)) {
                anchorX = x;
                anchorY = y;
                return;
            }
            if (pulled || vertical) return;
            float density = density();
            float dx = x - anchorX;
            if (!sliding) {
                float dy = y - anchorY;
                if (dy > AXIS_LOCK_DP * density && dy > Math.abs(dx)) {
                    vertical = true;
                    return;
                }
                float dead = DEAD_ZONE_DP * density;
                if (Math.abs(dx) <= dead) return;
                sliding = true;
                originX = anchorX + Math.signum(dx) * dead;
            }
            float step = STEP_DP * density;
            int moved = (int) ((x - originX) / step);
            if (moved == 0) return;
            // The origin follows the finger past either end too, so coming back steps at once.
            originX += moved * step;
            int to = Math.max(0, Math.min(steps.length - 1, index + moved));
            if (to == index) return;
            index = to;
            apply(steps[to]);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not change the hold speed", ex);
            end();
        }
    }

    /**
     * Called from TikTok's pull-down lock with 1 when the held finger has gone far enough down to
     * lock on release, and 0 when it comes back up. Down, the speed stays where it is; back up,
     * the finger is marked again where it is now and the slide goes on from there.
     */
    public static void onPullDown(int down) {
        try {
            pulled = down != 0;
            if (!pulled) {
                vertical = false;
                sliding = false;
                anchorX = Float.NaN;
                anchorY = Float.NaN;
            }
            Bubble shown = bubble.get();
            if (shown != null) shown.setLocked(pulled);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not follow the hold's pull-down", ex);
        }
    }

    /** Called from the gesture's release, past its "not held" guard: the bubble goes. */
    public static void onHoldRelease() {
        holding = false;
        pulled = false;
        gesture.clear();
        dismiss();
    }

    /** The on-screen tracker named another video: a hold still going is over for this bubble. */
    static void onScreenVideo(String aid) {
        if (aid == null || aid.equals(heldVideo)) return;
        // Checked again on the main thread: a hold that began on this video since then stays.
        Utils.runOnMainThread(() -> {
            if (holding && !aid.equals(heldVideo)) end();
        });
    }

    /**
     * The speeds a slide from {@code start} steps through, slowest first: the speed menu's custom
     * list, or the whole {@link PlaybackSpeedPatch#LIVE_STEP} grid when the list is empty or one
     * the menu refuses, with {@code start} among them.
     */
    static float[] stepsFor(float start, String menu) {
        TreeSet<Float> speeds = new TreeSet<>();
        try {
            speeds.addAll(PlaybackSpeedPatch.parseMenuSpeeds(menu));
        } catch (IllegalArgumentException refused) {
            // The menu shows TikTok's own speeds for such a list; the slide takes the grid.
        }
        if (speeds.isEmpty()) {
            int count = Math.round((PlaybackSpeedPatch.MAX_SPEED - PlaybackSpeedPatch.MIN_SPEED)
                    / PlaybackSpeedPatch.LIVE_STEP);
            for (int i = 0; i <= count; i++) {
                speeds.add(PlaybackSpeedPatch.MIN_SPEED + i * PlaybackSpeedPatch.LIVE_STEP);
            }
        }
        speeds.add(start);
        float[] out = new float[speeds.size()];
        int i = 0;
        for (float speed : speeds) out[i++] = speed;
        return out;
    }

    private static int indexOf(float[] speeds, float speed) {
        for (int i = 0; i < speeds.length; i++) {
            if (Float.compare(speeds[i], speed) == 0) return i;
        }
        return 0;
    }

    private static float density() {
        Bubble shown = bubble.get();
        Resources resources = shown != null ? shown.getResources() : Resources.getSystem();
        return resources.getDisplayMetrics().density;
    }

    private static void apply(float speed) {
        Object component = gesture.get();
        if (component == null) return;
        try {
            Applier applier = applierForTests;
            if (applier != null) applier.apply(component, speed);
            else applyNative(component, speed);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not play the held video at " + speed + "x", ex);
            return;
        }
        slidSpeed = speed;
        slidFrom = Settings.HOLD_SPEED.get();
        Bubble shown = bubble.get();
        if (shown != null) shown.setSpeed(speed, true);
    }

    /** Stops following the finger for this hold; a speed already slid to stays for the lock. */
    private static void end() {
        holding = false;
        dismiss();
    }

    private static void show(float speed) {
        Activity activity = Utils.getActivity();
        if (activity == null || activity.isFinishing()) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        Bubble view = new Bubble(activity, steps);
        view.setSpeed(speed, false);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = Math.round(content.getHeight() * 0.14f);
        content.addView(view, params);
        bubble = new WeakReference<>(view);
    }

    /** Takes the bubble down, wherever it is. */
    static void dismiss() {
        Bubble shown = bubble.get();
        bubble.clear();
        if (shown == null) return;
        ViewGroup parent = (ViewGroup) shown.getParent();
        if (parent != null) parent.removeView(shown);
    }

    /** The bubble on screen, for tests. */
    static View bubble() {
        return bubble.get();
    }

    /** A small pill: a padlock and the speed over a row of dots, one per step, the current one lit. */
    static final class Bubble extends View {
        private final float density;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lock = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private final float[] steps;
        private final float widestLabel;
        private float speed = Float.NaN;
        private String label = "";
        private boolean locked;

        Bubble(Context context, float[] steps) {
            super(context);
            this.steps = steps.clone();
            density = context.getResources().getDisplayMetrics().density;
            fill.setColor(0xB3000000);
            text.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(18 * density);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            lock.setStrokeWidth(1.6f * density);
            lock.setStrokeCap(Paint.Cap.ROUND);
            float widest = 0;
            for (float step : this.steps) {
                widest = Math.max(widest, text.measureText(PlaybackSpeedPatch.speedLabel(step) + "x"));
            }
            widestLabel = widest;
            setClickable(false);
            setFocusable(false);
            setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        }

        void setSpeed(float speed, boolean tick) {
            if (Float.compare(speed, this.speed) == 0) return;
            this.speed = speed;
            label = PlaybackSpeedPatch.speedLabel(speed) + "x";
            setContentDescription(label);
            if (tick) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            invalidate();
        }

        void setLocked(boolean locked) {
            if (this.locked == locked) return;
            this.locked = locked;
            invalidate();
        }

        String label() {
            return label;
        }

        boolean locked() {
            return locked;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            float content = widestLabel + 13 * density + 28 * density;
            float dots = (steps.length * 9 + 20) * density;
            setMeasuredDimension(Math.round(Math.max(content, dots)), Math.round(44 * density));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = getHeight() / 2f;
            bounds.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(bounds, radius, radius, fill);

            // The padlock sits just left of the speed, the two centered together.
            float textWidth = text.measureText(label);
            float left = (getWidth() - (8 * density + 5 * density + textWidth)) / 2f;
            lock.setColor(locked ? 0xFFFFFFFF : 0x66FFFFFF);
            lock.setStyle(Paint.Style.STROKE);
            float shackle = left + 4 * density;
            bounds.set(shackle - 2.5f * density, 10.5f * density, shackle + 2.5f * density, 15.5f * density);
            canvas.drawArc(bounds, 180, 180, false, lock);
            canvas.drawLine(shackle - 2.5f * density, 13 * density, shackle - 2.5f * density, 16 * density, lock);
            canvas.drawLine(shackle + 2.5f * density, 13 * density, shackle + 2.5f * density, 16 * density, lock);
            lock.setStyle(Paint.Style.FILL);
            bounds.set(left, 16 * density, left + 8 * density, 23 * density);
            canvas.drawRoundRect(bounds, 1.5f * density, 1.5f * density, lock);
            canvas.drawText(label, left + 13 * density, 24 * density, text);

            float first = (getWidth() - (steps.length - 1) * 9 * density) / 2f;
            float y = 35 * density;
            for (int i = 0; i < steps.length; i++) {
                boolean lit = Float.compare(steps[i], speed) == 0;
                dot.setColor(lit ? 0xFFFFFFFF : 0x66FFFFFF);
                canvas.drawCircle(first + i * 9 * density, y, (lit ? 3 : 2) * density, dot);
            }
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus);
            if (!hasWindowFocus && bubble.get() == this) end();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (bubble.get() == this) {
                bubble.clear();
                holding = false;
            }
        }
    }
}

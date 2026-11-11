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
import android.util.DisplayMetrics;
import android.util.TypedValue;
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
 * 2x with, so TikTok's speed state is left alone and letting go of an unlocked hold returns the
 * video to the speed it had, as it always does. The locks stay TikTok's: the slid speed is what
 * {@link PlaybackSpeedPatch#holdSpeed} answers, so a lock keeps it, its toast names it, and the
 * next hold starts there, until the hold speed row under Speed is changed. The finger is read
 * from the gesture's own touch listener, which already has the move events while the video is
 * held; nothing here takes a touch from TikTok.
 *
 * <p>A video already at the hold speed is one TikTok counts as locked: its padlock starts closed,
 * a pull opens it (that pull is TikTok's unlock), and letting go on another speed locks that one
 * by arming TikTok's own lock just before its release reads the state.
 *
 * <p>There is one bubble at most. A new hold, letting go, the video on screen changing, the window
 * losing focus (a pause, a dialog, the notification shade), Hushfeed Pause and the switch going off
 * each take it down, and a recreated activity drops it with its old views.
 *
 * <p>{@link Settings#HOLD_SPEED_HUD_STYLE} picks how the bubble looks: {@link #PILL}, the wide
 * bubble, or {@link #CIRCLE}, a small dial adapted from OmerKurdi79's fork. Both follow the hold
 * the same way; only the drawing differs.
 *
 * <p>{@link Settings#HOLD_SPEED_SLIDE_AXIS} can turn the slide on its side, the way that fork's
 * dial works: with {@link #UP_DOWN}, up steps faster and down slower, so a hold near the screen's
 * edge has room for every speed, and a slide {@link #SIDE_LOCK_DP} to either side arms the lock.
 * The lock is still TikTok's: the sideways slide drives its pull-down state, and TikTok's own
 * trigger, a finger far enough down, is overruled for the hold, since down means slower now.
 */
public final class HoldSpeedHud {
    /** The values of {@link Settings#HOLD_SPEED_HUD_STYLE}. */
    public static final String PILL = "pill";
    public static final String CIRCLE = "circle";
    /** The values of {@link Settings#HOLD_SPEED_SLIDE_AXIS}. */
    public static final String SIDEWAYS = "sideways";
    public static final String UP_DOWN = "up_down";

    /** How far the finger moves along the slide before it starts, then how far per step, in dp. */
    static final float DEAD_ZONE_DP = 16f;
    static final float STEP_DP = 32f;
    /** How far a hold goes toward the lock, more that way than along the slide, before it counts as heading for the lock and not a slide. */
    static final float AXIS_LOCK_DP = 18f;
    /**
     * With up and down for the speed, how far to either side of where the hold began arms the
     * lock, and how near it has to come back to let it go, so a finger resting there doesn't flicker.
     */
    static final float SIDE_LOCK_DP = 40f;
    static final float SIDE_UNLOCK_DP = 32f;

    private static WeakReference<Object> gesture = new WeakReference<>(null);
    private static WeakReference<Bubble> bubble = new WeakReference<>(null);
    private static boolean holding;
    private static float[] steps = new float[0];
    private static int index;
    private static float anchorX = Float.NaN;
    private static float anchorY = Float.NaN;
    /** Where the last step was taken, as a distance toward faster from the anchor. */
    private static float origin;
    private static boolean sliding;
    /** The hold went down toward TikTok's pull before it slid, so it doesn't slide until the pull lets go. */
    private static boolean vertical;
    private static boolean pulled;
    /** This hold steps the speed up and down, and where it began, which its sideways lock is measured from. */
    private static boolean upDown;
    private static float lockX = Float.NaN;
    /** Whether this hold's sideways slide has armed TikTok's lock. */
    private static boolean sideArmed;
    /** Whether this hold began on a video TikTok counts as locked, and the speed that video had. */
    private static boolean startedLocked;
    private static float heldFrom = Float.NaN;
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

    /** Set by tests only. Unpatched, as in the tests, {@link #armLockNative} does nothing. */
    public static volatile Applier lockArmerForTests;

    /** What TikTok's pull-down lock state does with a pull, so a test can stand in for it. */
    public interface Puller {
        void pull(Object gesture, boolean down);
    }

    /** Set by tests only. Unpatched, as in the tests, {@link #pullNative} does nothing. */
    public static volatile Puller pullerForTests;

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
        return from != null && from.equals(PlaybackSpeedPatch.holdSpeedRows()) ? slidSpeed : Float.NaN;
    }

    /**
     * Replaced with a call to the gesture component's apply method, the (F)V call its press plays
     * the hold speed with.
     */
    public static void applyNative(Object gesture, float speed) { }

    /**
     * Replaced with what makes the gesture's release lock {@code speed}: the speed it would go
     * back to becomes this one, and its lock state reads as a pull armed from an unlocked hold.
     */
    public static void armLockNative(Object gesture, float speed) { }

    /**
     * Replaced with a call to TikTok's pull-down lock state on the gesture, the one its touch
     * listener makes when the finger goes far enough down and when it comes back.
     */
    public static void pullNative(Object gesture, boolean down) { }

    /**
     * Called at the top of TikTok's pull-down lock state with what its touch listener worked out
     * from how far down the finger is, 1 or 0, and answers the one the state goes by. With up and
     * down for the speed, a finger going down is slowing the video, so the hold's sideways slide
     * answers instead; otherwise TikTok's stands.
     */
    public static boolean pullState(int tiktok) {
        try {
            if (!holding || !upDown || !enabled()) return tiktok != 0;
            return sideArmed;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not answer the hold's pull-down", ex);
            return tiktok != 0;
        }
    }

    /** Called from the gesture's press, past its "already held" guard, with the component. */
    public static void onHoldPress(Object component) {
        try {
            dismiss();
            holding = false;
            if (!enabled() || component == null) return;
            gesture = new WeakReference<>(component);
            // The speed the gesture plays, unsnapped: a typed 1.8 plays 1.8, so the bubble says
            // 1.8 and not the grid's 1.75 (#357). A speed slid to comes first, as before.
            float start = PlaybackSpeedPatch.holdSpeed(PlaybackSpeedPatch.TIKTOK_HOLD_SPEED);
            steps = stepsFor(start, Settings.CUSTOM_SPEEDS.get());
            index = indexOf(steps, start);
            anchorX = Float.NaN;
            anchorY = Float.NaN;
            sliding = false;
            vertical = false;
            pulled = false;
            // Saved on a build whose patch had TikTok's lock state, it stays sideways on one without.
            upDown = SettingsStatus.holdSpeedAxisEnabled && UP_DOWN.equals(Settings.HOLD_SPEED_SLIDE_AXIS.get());
            lockX = Float.NaN;
            sideArmed = false;
            startedLocked = false;
            heldFrom = Float.NaN;
            heldVideo = PlaybackSpeedPatch.onScreenVideoId();
            holding = true;
            show(start);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not start the hold speed bubble", ex);
        }
    }

    /**
     * Called from the gesture's press with the speed the video played at before the hold, right
     * after it keeps it. TikTok compares that with the hold speed the same way to tell whether
     * this video is already locked.
     */
    public static void onHoldFrom(float before) {
        if (!holding) return;
        try {
            heldFrom = before;
            startedLocked = Float.compare(before, PlaybackSpeedPatch.holdSpeed(2f)) == 0;
            showLockState();
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not read the speed before the hold", ex);
        }
    }

    /**
     * Called with every event the gesture's touch listener gets. Only a move during a hold does
     * anything: the first one marks where the finger is, later ones step the speed by how far it
     * moved along the slide from there, sideways or, with {@link #UP_DOWN}, up for faster. Up and
     * cancel are left to the gesture's release.
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
            if (Float.isNaN(lockX)) lockX = x;
            float density = density();
            // Letting the side lock go reaches onPullDown, which marks the finger again from here.
            if (upDown) followSideLock(x, density);
            if (pulled || vertical) return;
            if (Float.isNaN(anchorX)) {
                anchorX = x;
                anchorY = y;
                return;
            }
            float along = upDown ? anchorY - y : x - anchorX;
            if (!sliding) {
                float toward = upDown ? Math.abs(x - anchorX) : y - anchorY;
                if (toward > AXIS_LOCK_DP * density && toward > Math.abs(along)) {
                    // TikTok's pull holds the slide off until it lets go. The sideways lock is
                    // measured here, so coming back toward the middle lets the slide start.
                    if (!upDown) vertical = true;
                    return;
                }
                float dead = DEAD_ZONE_DP * density;
                if (Math.abs(along) <= dead) return;
                sliding = true;
                origin = Math.signum(along) * dead;
            }
            float step = STEP_DP * density;
            int moved = (int) ((along - origin) / step);
            if (moved == 0) return;
            // The origin follows the finger past either end too, so coming back steps at once.
            origin += moved * step;
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
     * With up and down for the speed, a slide {@link #SIDE_LOCK_DP} to either side of where the
     * hold began arms TikTok's lock, as its pull down would, and coming back within
     * {@link #SIDE_UNLOCK_DP} lets it go. TikTok's own state takes both, so its release locks or
     * unlocks just as it does after a pull, and its signal tells {@link #onPullDown} as usual.
     */
    private static void followSideLock(float x, float density) {
        float side = Math.abs(x - lockX);
        boolean arm = sideArmed ? side >= SIDE_UNLOCK_DP * density : side > SIDE_LOCK_DP * density;
        if (arm == sideArmed) return;
        sideArmed = arm;
        Object component = gesture.get();
        if (component == null) return;
        Puller puller = pullerForTests;
        if (puller != null) puller.pull(component, arm);
        else pullNative(component, arm);
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
            showLockState();
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not follow the hold's pull-down", ex);
        }
    }

    /** The padlock is closed on a locked video and when a pull will lock; a pull on a locked video opens it. */
    private static void showLockState() {
        Bubble shown = bubble.get();
        if (shown != null) shown.setLocked(startedLocked != pulled);
    }

    /**
     * Called from the gesture's release, past its "not held" guard, before it reads the speed to
     * go back to or the lock state: a locked hold let go on a new speed locks that one, and the
     * bubble goes.
     */
    public static void onHoldRelease() {
        try {
            lockTheSlidSpeed();
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not lock the speed the hold was let go on", ex);
        }
        holding = false;
        pulled = false;
        sideArmed = false;
        lockX = Float.NaN;
        gesture.clear();
        dismiss();
    }

    private static void lockTheSlidSpeed() {
        if (!holding || !startedLocked || pulled) return;
        Object component = gesture.get();
        float speed = slidSpeed();
        if (component == null || Float.isNaN(speed) || Float.compare(speed, heldFrom) == 0) return;
        Applier armer = lockArmerForTests;
        if (armer != null) armer.apply(component, speed);
        else armLockNative(component, speed);
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
        slidFrom = PlaybackSpeedPatch.holdSpeedRows();
        Bubble shown = bubble.get();
        if (shown != null) shown.setSpeed(speed, true);
    }

    /** Stops following the finger for this hold; a speed already slid to stays for the lock. */
    private static void end() {
        holding = false;
        dismiss();
    }

    private static void show(float speed) {
        // The activity in front, not the main one: a video opened from search, a profile or a
        // chat plays in an activity of its own, and the bubble went on the window behind it.
        Activity activity = Utils.getVisibleActivity();
        if (activity == null || activity.isFinishing()) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) return;
        boolean round = CIRCLE.equals(Settings.HOLD_SPEED_HUD_STYLE.get());
        Bubble view = round ? new Circle(activity, steps) : new Bubble(activity, steps);
        view.setSpeed(speed, false);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = Math.round(content.getHeight() * 0.14f);
        content.addView(view, params);
        bubble = new WeakReference<>(view);
        if (round) {
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(Circle.FADE_IN_MS);
        }
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

    /**
     * A small pill: a padlock and the speed over a row of dots, one per step, the current one lit.
     * {@link Circle} draws the same state its own way.
     */
    static class Bubble extends View {
        /** A dp grown with the phone's font size, so the whole dial grows with its speed. */
        final float unit;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint lock = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF bounds = new RectF();
        final float[] steps;
        private final float widestLabel;
        float speed = Float.NaN;
        String label = "";
        boolean locked;

        Bubble(Context context, float[] steps) {
            this(context, steps, 18);
        }

        /**
         * {@code labelSp} is the speed's size. The padlock, the dots and the dial around them
         * scale with it, since a larger label in the same 44dp would clip, and at the default
         * font size {@link #unit} is exactly one dp.
         */
        Bubble(Context context, float[] steps, float labelSp) {
            super(context);
            this.steps = steps.clone();
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            unit = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, labelSp, metrics) / labelSp;
            fill.setColor(0xB3000000);
            text.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(18 * unit);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            lock.setStrokeWidth(1.6f * unit);
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
            float content = widestLabel + 13 * unit + 28 * unit;
            float dots = (steps.length * 9 + 20) * unit;
            setMeasuredDimension(Math.round(Math.max(content, dots)), Math.round(44 * unit));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float radius = getHeight() / 2f;
            bounds.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(bounds, radius, radius, fill);

            // The padlock sits just left of the speed, the two centered together.
            float textWidth = text.measureText(label);
            float left = (getWidth() - (8 * unit + 5 * unit + textWidth)) / 2f;
            lock.setColor(locked ? 0xFFFFFFFF : 0xB3FFFFFF);
            lock.setStyle(Paint.Style.STROKE);
            float shackle = left + 4 * unit;
            // Open, the shackle stands higher and its left leg stops short of the body.
            float lift = locked ? 0 : 2.5f * unit;
            bounds.set(shackle - 2.5f * unit, 10.5f * unit - lift, shackle + 2.5f * unit, 15.5f * unit - lift);
            canvas.drawArc(bounds, 180, 180, false, lock);
            canvas.drawLine(shackle - 2.5f * unit, 13 * unit - lift, shackle - 2.5f * unit,
                    locked ? 16 * unit : 13 * unit - lift + unit, lock);
            canvas.drawLine(shackle + 2.5f * unit, 13 * unit - lift, shackle + 2.5f * unit, 16 * unit, lock);
            lock.setStyle(Paint.Style.FILL);
            bounds.set(left, 16 * unit, left + 8 * unit, 23 * unit);
            canvas.drawRoundRect(bounds, 1.5f * unit, 1.5f * unit, lock);
            canvas.drawText(label, left + 13 * unit, 24 * unit, text);

            float first = (getWidth() - (steps.length - 1) * 9 * unit) / 2f;
            float y = 35 * unit;
            for (int i = 0; i < steps.length; i++) {
                boolean lit = Float.compare(steps[i], speed) == 0;
                dot.setColor(lit ? 0xFFFFFFFF : 0x66FFFFFF);
                canvas.drawCircle(first + i * 9 * unit, y, (lit ? 3 : 2) * unit, dot);
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

    /**
     * The round style (#248), after the dial in OmerKurdi79's fork: a dark disc with a hairline
     * rim, a ring that fills from the slowest step to the current one, a notch per step around it
     * with the current one lit, and the speed in the middle. The padlock shows only while closed,
     * above a smaller speed.
     */
    static final class Circle extends Bubble {
        static final float DIAMETER_DP = 52f;
        static final long FADE_IN_MS = 150;
        /** The ring opens at the bottom: it starts down and to the left and turns three quarters. */
        static final float START = 135f;
        static final float SWEEP = 270f;

        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();

        Circle(Context context, float[] steps) {
            super(context, steps, 13);
            fill.setColor(0xBE101218);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
            text.setTextAlign(Paint.Align.CENTER);
        }

        /** Where step {@code i} sits on the ring, in degrees clockwise from three o'clock. */
        float angleOf(int i) {
            int last = steps.length - 1;
            return START + (last > 0 ? SWEEP * i / last : 0f);
        }

        /** The ring's radius for a view {@code size} pixels across. */
        float ringRadius(float size) {
            return size / 2f - unit - 3.5f * unit;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int diameter = Math.round(DIAMETER_DP * unit);
            setMeasuredDimension(diameter, diameter);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float outer = size / 2f - unit;
            canvas.drawCircle(cx, cy, outer, fill);
            ring.setStrokeWidth(unit);
            ring.setColor(0x23FFFFFF);
            canvas.drawCircle(cx, cy, outer, ring);

            if (steps.length > 0) {
                float radius = ringRadius(size);
                arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
                ring.setStrokeWidth(2 * unit);
                ring.setColor(0x19FFFFFF);
                canvas.drawArc(arc, START, SWEEP, false, ring);
                int current = indexOf(steps, speed);
                ring.setColor(0xB9D7E1EB);
                canvas.drawArc(arc, START, Math.max(3f, angleOf(current) - START), false, ring);
                for (int i = 0; i < steps.length; i++) {
                    double angle = Math.toRadians(angleOf(i));
                    float x = cx + (float) (radius * Math.cos(angle));
                    float y = cy + (float) (radius * Math.sin(angle));
                    if (i == current) {
                        dot.setColor(0x96FFFFFF);
                        canvas.drawCircle(x, y, 3.5f * unit, dot);
                        dot.setColor(0xFFFFFFFF);
                        canvas.drawCircle(x, y, 1.8f * unit, dot);
                    } else {
                        dot.setColor(0x32FFFFFF);
                        canvas.drawCircle(x, y, 1.2f * unit, dot);
                    }
                }
            }

            if (locked) {
                // A closed padlock over the speed: the shackle's arch, its legs and the body.
                lock.setColor(0xDCFFFFFF);
                lock.setStyle(Paint.Style.STROKE);
                bounds.set(cx - 2.5f * unit, cy - 12.5f * unit, cx + 2.5f * unit, cy - 7.5f * unit);
                canvas.drawArc(bounds, 180, 180, false, lock);
                canvas.drawLine(cx - 2.5f * unit, cy - 10 * unit, cx - 2.5f * unit, cy - 7 * unit, lock);
                canvas.drawLine(cx + 2.5f * unit, cy - 10 * unit, cx + 2.5f * unit, cy - 7 * unit, lock);
                lock.setStyle(Paint.Style.FILL);
                bounds.set(cx - 4 * unit, cy - 7 * unit, cx + 4 * unit, cy - 1.5f * unit);
                canvas.drawRoundRect(bounds, 1.5f * unit, 1.5f * unit, lock);
                text.setTextSize(11 * unit);
                canvas.drawText(label, cx, cy + 9.5f * unit, text);
            } else {
                text.setTextSize(13 * unit);
                canvas.drawText(label, cx, cy + 4.5f * unit, text);
            }
        }
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * The look and feel of Hushfeed's own notices (#545): the banner a finished save, a block's Undo
 * or a reminder shows, and the rows a running save shows beside it.
 *
 * <p>Every notice goes with a swipe left or right ({@link SwipeRow}). Blur behind notices gives
 * one the glass the bar's blur draws ({@link GlassVideoBlur}): on Android 12 and up the video
 * behind it is read back and blurred into it, under a lighter scrim so the text still reads.
 * Below Android 12, away from a video, or until a strip has been read, it's the plain dark chip.
 * Where a notice goes, under Show notices at the top, is BlockAuthorOverlay's noticeParams.
 */
public final class Notices {
    /**
     * The scrim over a blurred strip: white text on it keeps better than 4.5 to 1 over a white
     * video, and the blur still shows through, which the plain chip's 91% black doesn't let it.
     */
    static final int FROSTED_SCRIM = Color.argb(150, 0, 0, 0);

    private Notices() {
    }

    /**
     * Gives a notice its backdrop: the blurring glass while Blur behind notices is on and the
     * phone can draw it, else the overlay family's plain chip. The glass lets go of the window
     * once the notice leaves it, since a window outlives many notices.
     */
    public static void dress(View notice) {
        Context context = notice.getContext();
        if (!GlassVideoBlur.supported() || !Settings.NOTICE_BLUR.get()) {
            notice.setBackground(SettingsUi.overlayBanner(context));
            return;
        }
        Glass glass = new Glass(context);
        notice.setBackground(glass);
        notice.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                glass.blur.release();
            }
        });
    }

    /** A notice's glass: the blurred video behind it where there is one, the right scrim over that. */
    static final class Glass extends Drawable {
        final GlassVideoBlur blur;
        private final GradientDrawable plain;
        private final GradientDrawable frosted;
        private final float radius;

        Glass(Context context) {
            plain = SettingsUi.overlayBanner(context);
            frosted = SettingsUi.overlayBanner(context);
            frosted.setColor(FROSTED_SCRIM);
            radius = SettingsUi.dp(context, SettingsUi.RADIUS_OVERLAY);
            blur = new GlassVideoBlur(context.getResources().getDisplayMetrics().density, this,
                    Settings.NOTICE_BLUR, false);
        }

        /** True when the last draw put a blurred strip under the scrim. */
        boolean frosted() {
            return blur.drawnBackdrop() != null;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            plain.setBounds(bounds);
            frosted.setBounds(bounds);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            Callback callback = getCallback();
            if (callback instanceof View) blur.drawBehind(canvas, (View) callback, bounds, radius);
            (frosted() ? frosted : plain).draw(canvas);
        }

        @Override
        public void setAlpha(int alpha) {
            plain.setAlpha(alpha);
            frosted.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public int getAlpha() {
            return plain.getAlpha();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            plain.setColorFilter(filter);
            frosted.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * A notice that goes with a swipe left or right. It follows the finger across and fades as it
     * goes; let go past a third of its width, or flung that way, and it slides off and its
     * {@link #setOnSwipedAway action} runs, else it slides back. A tap on one of its buttons is
     * still the button's, and a drag that starts on one and goes sideways is the swipe. TalkBack
     * offers the same as the notice's Dismiss action.
     */
    public static final class SwipeRow extends LinearLayout {
        /** Let go past this share of its width and the notice goes. */
        static final float GO_FRACTION = 1f / 3f;
        /** Flung sideways at least this fast, in dp a second, it goes however far it moved. */
        static final float FLING_DP_PER_SECOND = 600f;
        /** How long the slide off, or back, takes. */
        static final long SLIDE_MS = 150;

        private final int slop;
        private final float fling;
        private Runnable swipedAway;
        private VelocityTracker velocity;
        private float downX;
        private float downY;
        private boolean dragging;
        private boolean gone;

        public SwipeRow(Context context) {
            super(context);
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
            fling = FLING_DP_PER_SECOND * context.getResources().getDisplayMetrics().density;
        }

        /** What runs once the notice has slid off, or Dismiss was used. Null, and it stays put. */
        public void setOnSwipedAway(Runnable action) {
            swipedAway = action;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (swipedAway == null || gone) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    begin(event);
                    return false;
                case MotionEvent.ACTION_MOVE:
                    track(event);
                    // From here the gesture is the notice's, and the button under it is cancelled.
                    return startsDragging(event);
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopTracking();
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (swipedAway == null || gone) return super.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    begin(event);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    track(event);
                    if (startsDragging(event)) follow(event.getRawX() - downX);
                    return true;
                case MotionEvent.ACTION_UP:
                    track(event);
                    if (dragging) letGo(event.getRawX() - downX);
                    stopTracking();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) slideBack();
                    stopTracking();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (swipedAway == null || gone) return;
            info.setDismissable(true);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_DISMISS && swipedAway != null && !gone) {
                gone = true;
                swipedAway.run();
                return true;
            }
            return super.performAccessibilityAction(action, arguments);
        }

        /** Screen coordinates throughout: the notice moves under the finger, its own don't hold still. */
        private void begin(MotionEvent event) {
            stopTracking();
            velocity = VelocityTracker.obtain();
            track(event);
            downX = event.getRawX();
            downY = event.getRawY();
        }

        private void track(MotionEvent event) {
            if (velocity == null) return;
            MotionEvent onScreen = MotionEvent.obtain(event);
            onScreen.setLocation(event.getRawX(), event.getRawY());
            velocity.addMovement(onScreen);
            onScreen.recycle();
        }

        private void stopTracking() {
            dragging = false;
            if (velocity != null) velocity.recycle();
            velocity = null;
        }

        /** True once the finger has gone past the slop, and further sideways than up or down. */
        private boolean startsDragging(MotionEvent event) {
            if (dragging) return true;
            float dx = event.getRawX() - downX;
            float dy = event.getRawY() - downY;
            if (Math.abs(dx) <= slop || Math.abs(dx) <= Math.abs(dy)) return false;
            dragging = true;
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            return true;
        }

        private void follow(float dx) {
            setTranslationX(dx);
            setAlpha(Math.max(0.2f, 1f - Math.abs(dx) / Math.max(1, getWidth())));
        }

        private void letGo(float dx) {
            float speed = 0;
            if (velocity != null) {
                velocity.computeCurrentVelocity(1000);
                speed = velocity.getXVelocity();
            }
            boolean far = Math.abs(dx) >= getWidth() * GO_FRACTION;
            boolean flung = Math.abs(speed) >= fling && Math.signum(speed) == Math.signum(dx);
            if (far || flung) slideOff(dx);
            else slideBack();
        }

        private void slideOff(float dx) {
            gone = true;
            animate().cancel();
            animate().translationX(Math.signum(dx) * Math.max(1, getWidth())).alpha(0f)
                    .setDuration(SLIDE_MS).withEndAction(() -> {
                        Runnable action = swipedAway;
                        if (action != null) action.run();
                    });
        }

        private void slideBack() {
            animate().cancel();
            animate().translationX(0f).alpha(1f).setDuration(SLIDE_MS);
        }
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * The hold's panel: centred while its text and buttons fit, scrollable once they don't.
 *
 * <p>At Android's largest font and display sizes on a small phone, or in split screen, the stack
 * is taller than the room above the tab bar. A vertical LinearLayout squeezes whatever comes last
 * into the space that is left, and what comes last are Open the feed anyway and Open messages, so
 * the two ways off the panel were cut off with nothing to drag. A ScrollView can't stand in: it
 * takes a single child, and this panel is the scrim the overlay tracks, with its controls as
 * direct children.
 */
final class HoldPanel extends LinearLayout {
    private final int touchSlop;
    /** How far one notch of a mouse wheel moves the panel, as it moves a list. */
    private final float wheelStep;
    /** The scroll range from the last layout. The two are equal when everything fits. */
    private int minScroll;
    private int maxScroll;
    private float lastY;
    private boolean dragging;
    /** The finger the scroll follows, as a ScrollView does when a second one lands or lifts. */
    private int activePointer = MotionEvent.INVALID_POINTER_ID;

    HoldPanel(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        // Below Android 8 the factor isn't public. Lists there move a preferred list item's height
        // per notch, the 64dp the public factor defaults to.
        wheelStep = Build.VERSION.SDK_INT >= 26
                ? ViewConfiguration.get(context).getScaledVerticalScrollFactor()
                : 64 * context.getResources().getDisplayMetrics().density;
        // Content fades out at an edge it continues past, the one hint there's more to drag to.
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(Math.round(24 * context.getResources().getDisplayMetrics().density));
    }

    /** Whether the content is taller than the panel, as of the last layout. */
    boolean scrolls() {
        return maxScroll > minScroll;
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Every child is measured at its full height, so an overflow becomes a scroll range
        // rather than a button squeezed to nothing. The panel itself keeps the height it was
        // given. While everything fits the children measure the same either way.
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        if (mode == MeasureSpec.UNSPECIFIED) return;
        int size = MeasureSpec.getSize(heightMeasureSpec);
        int height = mode == MeasureSpec.EXACTLY ? size : Math.min(getMeasuredHeight(), size);
        setMeasuredDimension(getMeasuredWidthAndState(), height);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        boolean scrolledBefore = scrolls();
        int fromTop = getScrollY() - minScroll;
        int contentTop = Integer.MAX_VALUE;
        int contentBottom = Integer.MIN_VALUE;
        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) continue;
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            contentTop = Math.min(contentTop, child.getTop() - params.topMargin);
            contentBottom = Math.max(contentBottom, child.getBottom() + params.bottomMargin);
        }
        if (contentTop > contentBottom) {
            minScroll = 0;
            maxScroll = 0;
        } else {
            // Centring pushes an overflow out past both edges by half each. The padding scrolls
            // with the content, so the first and last controls keep their gap from the edge.
            minScroll = Math.min(0, contentTop - getPaddingTop());
            maxScroll = Math.max(0, contentBottom + getPaddingBottom() - getHeight());
        }
        // Opens at the title, which is where the panel starts reading. Later layouts (the
        // countdown changes once a minute, the hint or a button's label can grow) keep the
        // reader's distance from the top. Centring moves the top by half of any change in
        // height, so keeping the raw scroll would shift what they were reading.
        scrollTo(0, scrolls() && !scrolledBefore ? minScroll : clamp(minScroll + fromTop));
    }

    @Override protected int computeVerticalScrollRange() {
        return scrolls() ? maxScroll - minScroll + getHeight() : super.computeVerticalScrollRange();
    }

    @Override protected int computeVerticalScrollOffset() {
        return scrolls() ? getScrollY() - minScroll : super.computeVerticalScrollOffset();
    }

    @Override protected int computeVerticalScrollExtent() {
        return scrolls() ? getHeight() : super.computeVerticalScrollExtent();
    }

    @Override protected float getTopFadingEdgeStrength() {
        return scrolls() && getScrollY() > minScroll ? 1f : 0f;
    }

    @Override protected float getBottomFadingEdgeStrength() {
        return scrolls() && getScrollY() < maxScroll ? 1f : 0f;
    }

    private int clamp(int scrollY) {
        return Math.max(minScroll, Math.min(maxScroll, scrollY));
    }

    private boolean scrollWithin(int dy) {
        int target = clamp(getScrollY() + dy);
        if (target == getScrollY()) return false;
        scrollTo(0, target);
        return true;
    }

    private void startDrag(float y) {
        dragging = true;
        lastY = y;
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
    }

    /** Where the followed finger is, or NaN once it's gone from the event. */
    private float followedY(MotionEvent event) {
        int index = event.findPointerIndex(activePointer);
        return index < 0 ? Float.NaN : event.getY(index);
    }

    /** Follows the finger that's down, or another one when the followed finger lifts. */
    private void trackPointers(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            activePointer = event.getPointerId(0);
            lastY = event.getY(0);
            dragging = false;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN && dragging) {
            int index = event.getActionIndex();
            activePointer = event.getPointerId(index);
            lastY = event.getY(index);
        } else if (action == MotionEvent.ACTION_POINTER_UP
                && event.getPointerId(event.getActionIndex()) == activePointer) {
            int next = event.getActionIndex() == 0 ? 1 : 0;
            activePointer = event.getPointerId(next);
            lastY = event.getY(next);
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            activePointer = MotionEvent.INVALID_POINTER_ID;
            dragging = false;
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!scrolls()) return super.onInterceptTouchEvent(event);
        if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
            trackPointers(event);
            return false;
        }
        float y = followedY(event);
        if (!Float.isNaN(y) && Math.abs(y - lastY) > touchSlop) {
            // A drag that starts on a button scrolls the panel, and the button gets a cancel
            // rather than a tap.
            startDrag(y);
            return true;
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!scrolls()) return super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float y = followedY(event);
            if (!Float.isNaN(y)) {
                if (!dragging && Math.abs(y - lastY) > touchSlop) startDrag(y);
                if (dragging) {
                    scrollWithin(Math.round(lastY - y));
                    lastY = y;
                }
            }
        } else {
            trackPointers(event);
        }
        // Still the scrim that swallows every touch, so nothing reaches the feed underneath.
        super.onTouchEvent(event);
        return true;
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if (!SessionLockOverlay.isWheel(event)) return super.onGenericMotionEvent(event);
        // A wheel scrolls a panel with more to show, the way it scrolls a ScrollView, and stops
        // here either way: a wheel nothing takes goes on to the feed under the panel.
        if (scrolls()) scrollWithin(-Math.round(event.getAxisValue(MotionEvent.AXIS_VSCROLL) * wheelStep));
        return true;
    }

    @Override public boolean shouldDelayChildPressedState() {
        // As a ScrollView does: a drag that starts on a button doesn't flash it pressed first.
        return scrolls();
    }

    @Override public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        if (scrolls()) {
            int rectTop = child.getTop() + rectangle.top;
            int rectBottom = child.getTop() + rectangle.bottom;
            int visibleTop = getScrollY() + getPaddingTop();
            int visibleBottom = getScrollY() + getHeight() - getPaddingBottom();
            int dy = 0;
            if (rectBottom > visibleBottom) dy = rectBottom - visibleBottom;
            if (rectTop - dy < visibleTop) dy = rectTop - visibleTop;
            if (dy != 0 && scrollWithin(dy)) return true;
        }
        return super.requestChildRectangleOnScreen(child, rectangle, immediate);
    }

    @Override public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        // A keyboard or d-pad moving onto a button that is scrolled out of sight brings it in.
        if (scrolls() && child.getHeight() > 0) {
            requestChildRectangleOnScreen(child, new Rect(0, 0, child.getWidth(), child.getHeight()), true);
        }
    }

    @Override public CharSequence getAccessibilityClassName() {
        // Read as a scroll container while it is one, which is what tells a screen reader to
        // scroll it when its swipes reach the last control that is on screen.
        return scrolls() ? ScrollView.class.getName() : super.getAccessibilityClassName();
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        // The view is clickable so it swallows every touch, not so it can be pressed. Left on the
        // node, that made TalkBack stop on the panel itself, read the whole hold again and offer
        // to activate it, which does nothing. Its text and buttons are their own stops.
        info.setClickable(false);
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
        if (!scrolls()) return;
        info.setScrollable(true);
        if (getScrollY() > minScroll) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
        if (getScrollY() < maxScroll) {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        }
    }

    @Override public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (!scrolls()) return;
        // Where a scroll event says the panel is, counted from the top as a ScrollView does.
        event.setScrollable(true);
        event.setScrollY(getScrollY() - minScroll);
        event.setMaxScrollY(maxScroll - minScroll);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (scrolls()) {
            int page = Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom());
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) return scrollWithin(page);
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) return scrollWithin(-page);
        }
        return super.performAccessibilityAction(action, arguments);
    }
}

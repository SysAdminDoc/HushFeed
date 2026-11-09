/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.content.Context;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Method;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * A drag handle and Top, Back and End buttons for long profile, Liked and sound grids (#378).
 * They come up while the grid moves and leave a moment after it stops, so nothing sits over the
 * videos the rest of the time. The handle moves through the videos loaded so far. Back returns
 * to the spot the last jump left from, and swaps with where you are so it can be undone.
 */
public final class GridPosition {
    static final String RAIL_TAG = "hushfeed_grid_position";
    /** About three screens of a three-column grid. A shorter one has nothing to jump over. */
    static final int MIN_ITEMS = 30;
    static final long IDLE_MS = 2500;
    private static final long FADE_MS = 150;
    private static final String HOOK = "grid position";
    private static final int NOWHERE = -1;

    private GridPosition() {
    }

    /** Where a handle at {@code fraction} of its track lands in a list of {@code count}, or -1. */
    static int positionAt(float fraction, int count) {
        return count <= 0 ? NOWHERE : Math.round(clamp(fraction) * (count - 1));
    }

    /** How far through its scrollable length the grid is, from RecyclerView's own numbers. */
    static float fractionOf(int offset, int range, int extent) {
        int room = range - extent;
        return room <= 0 ? 0f : clamp((float) offset / room);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static Rail railOn(FrameLayout frame) {
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (child instanceof Rail && RAIL_TAG.equals(child.getTag())) return (Rail) child;
        }
        return null;
    }

    /** Runs each time TikTok hands a grid a list, on the main thread. */
    static void show(Object adapter, View grid) {
        try {
            if (grid == null || !(grid.getParent() instanceof FrameLayout)) return;
            FrameLayout frame = (FrameLayout) grid.getParent();
            Rail rail = railOn(frame);
            if (!Settings.GRID_POSITION_CONTROLS.get() || Setting.isPaused()) {
                if (rail != null) frame.removeView(rail);
                return;
            }
            Object current = Reflect.invoke(grid, "getAdapter");
            if (current != null && current != adapter) return;
            Method scrollTo = Reflect.requiredMethod(grid.getClass(), "scrollToPosition", HOOK, int.class);
            Method childPosition = Reflect.requiredMethod(grid.getClass(), "getChildAdapterPosition", HOOK, View.class);
            if (scrollTo == null || childPosition == null) return;
            if (rail == null) {
                rail = new Rail(frame.getContext());
                rail.setTag(RAIL_TAG);
                frame.addView(rail, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            rail.bind(adapter, grid, scrollTo, childPosition);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not add the grid position controls", ex);
        }
    }

    /**
     * Covers the grid's frame but takes no touch of its own, so while it's up only the handle and
     * the buttons catch anything. While it's hidden they don't either.
     */
    static final class Rail extends FrameLayout
            implements ViewTreeObserver.OnScrollChangedListener, ViewTreeObserver.OnGlobalLayoutListener {
        final FrameLayout handle;
        final TextView top;
        final TextView back;
        final TextView end;
        private final LinearLayout buttons;
        private final Rect visible = new Rect();
        private final int[] location = new int[2];
        private final Runnable hide = this::fadeOut;
        private final int touchSlop;

        private Object adapter;
        private View grid;
        private Method scrollTo;
        private Method childPosition;
        private int loaded;
        private int lastOffset = NOWHERE;
        private int lastTarget = NOWHERE;
        private boolean dragging;
        private boolean moved;
        private float downY;
        private int trackTop;
        private int trackBottom;
        /** The spot Back goes to, or -1 for none. */
        int remembered = NOWHERE;

        Rail(Context context) {
            super(context);
            setVisibility(INVISIBLE);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

            handle = new FrameLayout(context);
            View pill = new View(context);
            pill.setBackground(SettingsUi.overlayChip(context, 3));
            LayoutParams pillAt = new LayoutParams(
                    SettingsUi.dp(context, 6), SettingsUi.dp(context, 48), Gravity.CENTER_VERTICAL | Gravity.END);
            pillAt.setMarginEnd(SettingsUi.dp(context, 4));
            handle.addView(pill, pillAt);
            handle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            handle.setOnTouchListener((view, event) -> onHandleTouch(event));
            addView(handle, new LayoutParams(
                    SettingsUi.dp(context, 44), SettingsUi.dp(context, 72), Gravity.TOP | Gravity.END));

            buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.VERTICAL);
            top = button(context, L10n.t("Top"), this::goTop);
            back = button(context, L10n.t("Back"), this::goBack);
            back.setVisibility(GONE);
            end = button(context, L10n.t("End"), this::goEnd);
            buttons.addView(top);
            buttons.addView(back);
            buttons.addView(end);
            LayoutParams buttonsAt = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            buttonsAt.setMarginEnd(SettingsUi.dp(context, 48));
            addView(buttons, buttonsAt);
        }

        private static TextView button(Context context, String label, Runnable action) {
            TextView button = new TextView(context);
            button.setText(label);
            button.setTextColor(SettingsUi.OVERLAY_TEXT);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_CAPTION);
            button.setMinWidth(SettingsUi.dp(context, 48));
            button.setMinHeight(SettingsUi.dp(context, 48));
            button.setGravity(Gravity.CENTER);
            int padding = SettingsUi.dp(context, 8);
            button.setPadding(padding, padding, padding, padding);
            button.setBackground(SettingsUi.overlayChip(context, SettingsUi.RADIUS_OVERLAY));
            button.setElevation(SettingsUi.dp(context, 2));
            button.setFocusable(true);
            SettingsUi.markAsButton(button);
            button.setOnClickListener(view -> action.run());
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            at.bottomMargin = SettingsUi.dp(context, 8);
            button.setLayoutParams(at);
            return button;
        }

        void bind(Object adapter, View grid, Method scrollTo, Method childPosition) {
            if (this.adapter != adapter) {
                setRemembered(NOWHERE);
                lastOffset = NOWHERE;
            }
            this.adapter = adapter;
            this.grid = grid;
            this.scrollTo = scrollTo;
            this.childPosition = childPosition;
            loaded = itemCount();
            post(this::place);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            ViewTreeObserver observer = getViewTreeObserver();
            observer.addOnScrollChangedListener(this);
            observer.addOnGlobalLayoutListener(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            ViewTreeObserver observer = getViewTreeObserver();
            observer.removeOnScrollChangedListener(this);
            observer.removeOnGlobalLayoutListener(this);
            removeCallbacks(hide);
            super.onDetachedFromWindow();
        }

        @Override
        public void onScrollChanged() {
            place();
        }

        @Override
        public void onGlobalLayout() {
            place();
        }

        private int number(String getter) {
            Object value = Reflect.invoke(grid, getter);
            return value instanceof Integer ? (Integer) value : NOWHERE;
        }

        private int itemCount() {
            Object value = Reflect.invoke(Reflect.invoke(grid, "getAdapter"), "getItemCount");
            return value instanceof Integer ? (Integer) value : 0;
        }

        void place() {
            if (grid == null) return;
            Object current = Reflect.invoke(grid, "getAdapter");
            if (Setting.isPaused() || !Settings.GRID_POSITION_CONTROLS.get()
                    || (current != null && current != adapter)
                    || grid.getParent() != getParent() || !grid.isShown()) {
                hideNow();
                lastOffset = NOWHERE;
                return;
            }
            int offset = number("computeVerticalScrollOffset");
            if (offset < 0) return;
            if (lastOffset >= 0 && offset != lastOffset && loaded >= MIN_ITEMS) wake();
            lastOffset = offset;
            if (getVisibility() != VISIBLE || !findTrack()) return;
            buttons.setTranslationY(trackTop + (trackBottom - trackTop - buttons.getHeight()) / 2f);
            if (!dragging) {
                float fraction = fractionOf(offset, number("computeVerticalScrollRange"),
                        number("computeVerticalScrollExtent"));
                handle.setTranslationY(trackTop + handleRoom() * fraction);
            }
        }

        /** The part of the grid on screen, in this view's coordinates. The header can cover the rest. */
        private boolean findTrack() {
            if (!grid.getGlobalVisibleRect(visible)) return false;
            getLocationInWindow(location);
            trackTop = visible.top - location[1];
            trackBottom = visible.bottom - location[1];
            return trackBottom > trackTop;
        }

        private float handleRoom() {
            return Math.max(1, trackBottom - trackTop - handle.getHeight());
        }

        private boolean onHandleTouch(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    moved = false;
                    downY = event.getRawY();
                    lastTarget = NOWHERE;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    wake();
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!moved && Math.abs(event.getRawY() - downY) < touchSlop) break;
                    if (!moved) {
                        moved = true;
                        remember();
                    }
                    dragTo(event.getRawY());
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    wake();
                    break;
                default:
                    break;
            }
            return true;
        }

        private void dragTo(float rawY) {
            if (!findTrack()) return;
            getLocationOnScreen(location);
            float fraction = clamp((rawY - location[1] - trackTop - handle.getHeight() / 2f) / handleRoom());
            handle.setTranslationY(trackTop + handleRoom() * fraction);
            int target = positionAt(fraction, itemCount());
            if (target >= 0 && target != lastTarget) {
                lastTarget = target;
                jump(target);
            }
        }

        /** The lowest position on screen, or -1. */
        private int firstVisible() {
            if (!(grid instanceof ViewGroup) || childPosition == null) return NOWHERE;
            ViewGroup rows = (ViewGroup) grid;
            int first = NOWHERE;
            for (int i = 0; i < rows.getChildCount(); i++) {
                Object value = Reflect.invokeRequired(childPosition, grid, HOOK, rows.getChildAt(i));
                int position = value instanceof Integer ? (Integer) value : NOWHERE;
                if (position >= 0 && (first < 0 || position < first)) first = position;
            }
            return first;
        }

        private void setRemembered(int position) {
            remembered = position;
            back.setVisibility(position >= 0 ? VISIBLE : GONE);
        }

        private void remember() {
            int here = firstVisible();
            if (here >= 0) setRemembered(here);
        }

        private void jump(int position) {
            Reflect.invoke(grid, "stopScroll");
            Reflect.invokeRequired(scrollTo, grid, HOOK, position);
        }

        void goTop() {
            // Already there, so what's remembered is still the way back.
            if (firstVisible() != 0) remember();
            jump(0);
            wake();
        }

        void goEnd() {
            remember();
            int count = itemCount();
            if (count > 0) jump(count - 1);
            wake();
        }

        void goBack() {
            int target = remembered;
            remember();
            if (target >= 0) jump(target);
            wake();
        }

        private void wake() {
            removeCallbacks(hide);
            animate().cancel();
            if (getVisibility() != VISIBLE) {
                setAlpha(0f);
                setVisibility(VISIBLE);
            }
            animate().alpha(1f).setDuration(FADE_MS);
            postDelayed(hide, IDLE_MS);
        }

        private void fadeOut() {
            if (dragging) {
                postDelayed(hide, IDLE_MS);
                return;
            }
            animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> setVisibility(INVISIBLE));
        }

        private void hideNow() {
            removeCallbacks(hide);
            animate().cancel();
            dragging = false;
            setVisibility(INVISIBLE);
        }
    }
}

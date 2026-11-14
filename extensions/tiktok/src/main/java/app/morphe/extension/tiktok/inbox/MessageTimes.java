/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Show message times: the time each message was sent, in small gray text under its bubble in an
 * open chat. TikTok itself only puts a time over a message that comes after a gap.
 *
 * <p>TikTok binds every message row through one method in each of its two row slots, and each bind
 * sets the row's padding again. The patch calls {@link #bound} right after that, with the row
 * TikTok padded and the bound item. The time comes from the item's message through {@link
 * #createdAt}, which the patch writes. The row's bottom padding gets room for one line and the time
 * is drawn there by a drawable on the row's overlay, so TikTok's views, their gestures and the
 * row's recycling stay as they are. Formatting reuses one formatter until the clock style, the
 * language or the time zone changes, so a fast scroll allocates next to nothing.
 *
 * <p>With the switch off, or Hushfeed paused, the drawable is emptied and the padding TikTok just
 * set stays. The time follows the zone TikTok runs in, which Region spoof can set, so it agrees with
 * TikTok's own date headers in the chat.
 */
@SuppressWarnings("unused")
public final class MessageTimes {
    private static final String HOOK_FAMILY = "message times";

    /** TikTok's gray for secondary text in a chat. */
    static final int COLOR = 0xFF8A8B91;
    static final float TEXT_SP = 11f;

    /**
     * A time this small is in seconds, not milliseconds (it would be a date in 1973 otherwise), and
     * gets scaled up.
     */
    private static final long SECONDS_BELOW = 100_000_000_000L;

    /** Each row's label. The label only holds its row weakly, so a row TikTok drops goes away. */
    static final Map<View, Label> LABELS = new WeakHashMap<>();

    private static java.text.DateFormat format;
    private static boolean formatTwentyFour;
    @Nullable private static Locale formatLocale;
    @Nullable private static String formatZone;

    private MessageTimes() {
    }

    /** Called on the main thread right after TikTok sets a message row's padding in its bind. */
    public static void bound(View row, Object item) {
        try {
            show(row, Settings.SHOW_MESSAGE_TIMES.get() ? createdAt(item) : 0L);
        } catch (Throwable error) {
            // Recorded once; the row keeps what TikTok's bind set.
            HookStatus.threw(HOOK_FAMILY, "message row", error);
            Logger.printDebug(() -> "[MessageTimes] left a message row as TikTok set it: " + error);
        }
    }

    /** Shows {@code at}, a send time in milliseconds or seconds, under the row, or nothing for 0. */
    static void show(View row, long at) {
        Label label = LABELS.get(row);
        long millis = at > 0 && at < SECONDS_BELOW ? at * 1000L : at;
        if (millis <= 0) {
            // Off, or a row with no message time: TikTok's padding from this bind stays.
            if (label != null) label.setText(null);
            return;
        }
        if (label == null) {
            label = new Label(row);
            row.getOverlay().add(label);
            LABELS.put(row, label);
        }
        label.setText(format(row.getContext(), millis));
        // The bind set this padding a moment ago, so the room is added once per bind.
        row.setPadding(row.getPaddingLeft(), row.getPaddingTop(), row.getPaddingRight(),
                row.getPaddingBottom() + label.lineHeight());
        HookStatus.bound(HOOK_FAMILY, "message row");
    }

    /** The time of day for {@code millis}, the way the phone's clock shows it. */
    static String format(Context context, long millis) {
        boolean twentyFour = DateFormat.is24HourFormat(context);
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        if (format == null || formatTwentyFour != twentyFour || !locale.equals(formatLocale)
                || !zone.getID().equals(formatZone)) {
            // The pattern the status bar clock uses, for the language and the 12 or 24 hour setting.
            format = DateFormat.getTimeFormat(context);
            format.setTimeZone(zone);
            formatTwentyFour = twentyFour;
            formatLocale = locale;
            formatZone = zone.getID();
        }
        return format.format(new Date(millis));
    }

    /**
     * When the bound item's message was sent, or 0. Replaced by the patch with a read through
     * TikTok's row item to its message's {@code getCreatedAt()}.
     */
    private static long createdAt(Object item) {
        return 0L;
    }

    /**
     * The message's bubble in the row: the widest shown part, looking inside any part that spans
     * nearly the whole row, as a layer that only handles swipes and long presses does. Null when
     * nothing narrower turns up, as for a notice across the chat.
     */
    @Nullable
    static View bubble(View row) {
        if (!(row instanceof ViewGroup)) return null;
        int rowWidth = row.getWidth();
        ViewGroup group = (ViewGroup) row;
        for (int depth = 0; depth < 4; depth++) {
            View widest = null;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() <= 0 || child.getHeight() <= 0) continue;
                if (widest == null || child.getWidth() > widest.getWidth()
                        || (child.getWidth() == widest.getWidth() && child.getHeight() > widest.getHeight())) {
                    widest = child;
                }
            }
            if (widest == null) return null;
            if (widest.getWidth() < rowWidth * 0.9f) return widest;
            if (!(widest instanceof ViewGroup)) return null;
            group = (ViewGroup) widest;
        }
        return null;
    }

    /** The time, drawn over the row in the room under the bubble. */
    static final class Label extends Drawable {
        private final WeakReference<View> row;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int gap;
        @Nullable String text;

        Label(View row) {
            this.row = new WeakReference<>(row);
            DisplayMetrics metrics = row.getResources().getDisplayMetrics();
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, metrics));
            paint.setColor(COLOR);
            gap = Math.round(2 * metrics.density);
            // The overlay draws within the row's bounds whatever these are.
            setBounds(0, 0, 1 << 14, 1 << 14);
        }

        /** The room one line takes under the bubble. */
        int lineHeight() {
            Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            return metrics.descent - metrics.ascent + gap * 2;
        }

        void setText(@Nullable String value) {
            if (value == null ? text == null : value.equals(text)) return;
            text = value;
            invalidateSelf();
            View host = row.get();
            if (host != null) host.invalidate();
        }

        /** Where the text starts: under the bubble's outer edge, or centered with no bubble. */
        float left(View host, float width) {
            int hostWidth = host.getWidth();
            View bubble = bubble(host);
            float x;
            if (bubble == null) {
                x = (hostWidth - width) / 2f;
            } else {
                int start = bubble.getLeft();
                for (ViewParent parent = bubble.getParent(); parent instanceof View && parent != host; parent = parent.getParent()) {
                    start += ((View) parent).getLeft() - ((View) parent).getScrollX();
                }
                int end = start + bubble.getWidth();
                // Your own messages sit on the right half, so the time lines up with the bubble's outer edge.
                x = start + end > hostWidth ? end - width : start;
            }
            float max = hostWidth - host.getPaddingRight() - width;
            return Math.max(host.getPaddingLeft(), Math.min(x, max));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            View host = row.get();
            if (text == null || host == null) return;
            float width = paint.measureText(text);
            Paint.FontMetricsInt metrics = paint.getFontMetricsInt();
            // The room starts where the row's content ends, above TikTok's own bottom padding.
            float top = host.getHeight() - host.getPaddingBottom();
            canvas.drawText(text, left(host, width), top + gap - metrics.ascent, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}

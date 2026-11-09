/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */

package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.preference.Preference;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.tiktok.settings.L10n;

/**
 * One recent settings search under the empty search box: a tap searches it again, the bin
 * forgets it. OmerKurdi79's row (#232).
 */
@SuppressWarnings("deprecation")
public final class RecentSearchPreference extends Preference {
    public interface OnDeleteListener {
        void onDelete(String query);
    }

    public interface OnSelectListener {
        void onSelect(String query);
    }

    private final String query;
    private final OnSelectListener onSelect;
    private final OnDeleteListener onDelete;

    public RecentSearchPreference(Context context, String query,
                                  OnSelectListener onSelect, OnDeleteListener onDelete) {
        super(context);
        this.query = query;
        this.onSelect = onSelect;
        this.onDelete = onDelete;
    }

    public String getQuery() {
        return query;
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(SettingsUi.dp(context, 48));
        // No vertical padding: the delete control is the full 48dp, so it sets the row's height.
        row.setPaddingRelative(SettingsUi.dp(context, 16), 0, SettingsUi.dp(context, 12), 0);
        row.setBackground(SettingsUi.pressAndFocusOver(context, SettingsUi.RADIUS_SETTINGS_FIELD,
                new ColorDrawable(Color.TRANSPARENT)));

        ImageView icon = new ImageView(context);
        icon.setImageDrawable(new HistoryDrawable(context));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                SettingsUi.dp(context, 24), SettingsUi.dp(context, 24));
        iconParams.setMarginEnd(SettingsUi.dp(context, 16));
        row.addView(icon, iconParams);

        TextView text = new TextView(context);
        text.setText(query);
        text.setTextSize(SettingsUi.TEXT_TITLE);
        text.setTextColor(SettingsUi.textPrimary());
        // Breathing room for a query that wraps past the 48dp the delete control sets.
        text.setPadding(0, SettingsUi.dp(context, 4), 0, SettingsUi.dp(context, 4));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView delete = new ImageView(context);
        delete.setImageDrawable(new TrashDrawable(context));
        delete.setContentDescription(L10n.t(context, "Delete search"));
        delete.setBackground(SettingsUi.pressAndFocusOver(context, SettingsUi.RADIUS_FIELD,
                new ColorDrawable(Color.TRANSPARENT)));
        delete.setClickable(true);
        delete.setFocusable(true);
        SettingsUi.markAsButton(delete);
        // A 48dp target, with the glyph drawn in the same 40dp it always had.
        int deleteSize = SettingsUi.dp(context, 48);
        int deleteInset = SettingsUi.dp(context, 4);
        delete.setPadding(deleteInset, deleteInset, deleteInset, deleteInset);
        delete.setOnClickListener(v -> {
            if (onDelete != null) onDelete.onDelete(query);
        });
        row.addView(delete, new LinearLayout.LayoutParams(deleteSize, deleteSize));

        row.setOnClickListener(v -> {
            if (onSelect != null) onSelect.onSelect(query);
        });

        return row;
    }

    static final class HistoryDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        HistoryDrawable(Context context) {
            paint.setColor(SettingsUi.textSecondary());
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(SettingsUi.strokePx(context, 1.8f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float cx = bounds.exactCenterX();
            float cy = bounds.exactCenterY();
            float radius = Math.min(bounds.width(), bounds.height()) * 0.38f;

            canvas.drawCircle(cx, cy, radius, paint);
            canvas.drawLine(cx, cy, cx, cy - radius * 0.55f, paint);
            canvas.drawLine(cx, cy, cx + radius * 0.45f, cy, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    static final class TrashDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TrashDrawable(Context context) {
            paint.setColor(SettingsUi.textSecondary());
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(SettingsUi.strokePx(context, 1.8f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float cx = bounds.exactCenterX();
            float cy = bounds.exactCenterY();
            float w = Math.min(bounds.width(), bounds.height()) * 0.35f;
            float h = w * 1.2f;

            float left = cx - w / 2f;
            float right = cx + w / 2f;
            float top = cy - h / 2f + w * 0.2f;
            float bottom = cy + h / 2f;

            // Lid handle
            canvas.drawLine(cx - w * 0.25f, top - w * 0.2f, cx + w * 0.25f, top - w * 0.2f, paint);
            // Lid bar
            canvas.drawLine(left - w * 0.15f, top, right + w * 0.15f, top, paint);
            // Can body (outline)
            canvas.drawRect(left, top, right, bottom, paint);
            // Vertical ribs inside can
            canvas.drawLine(cx - w * 0.2f, top + w * 0.25f, cx - w * 0.2f, bottom - w * 0.25f, paint);
            canvas.drawLine(cx + w * 0.2f, top + w * 0.25f, cx + w * 0.2f, bottom - w * 0.25f, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}

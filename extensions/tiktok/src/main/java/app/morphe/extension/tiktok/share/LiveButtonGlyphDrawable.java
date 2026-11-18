/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The symbols of the Record and Auto tap rows in a LIVE's menu (#359): a solid record dot, and a
 * small dot with short rays around it for a tap. LIVE recording quality already uses a ring with a
 * dot in it, so neither looks like that row.
 */
public final class LiveButtonGlyphDrawable extends Drawable {
    private static final float RECORD_RADIUS = 0.26f;
    private static final float TAP_RADIUS = 0.11f;
    private static final float RAY_FROM = 0.2f;
    private static final float RAY_TO = 0.33f;
    private static final int RAYS = 8;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean record;

    private LiveButtonGlyphDrawable(int color, float strokeWidthPx, boolean record) {
        this.record = record;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(strokeWidthPx);
        line.setColor(color);
    }

    static Drawable forRecord(int color, float strokeWidthPx) {
        return new LiveButtonGlyphDrawable(color, strokeWidthPx, true);
    }

    static Drawable forAutoTap(int color, float strokeWidthPx) {
        return new LiveButtonGlyphDrawable(color, strokeWidthPx, false);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }
        float size = Math.min(bounds.width(), bounds.height());
        float x = bounds.exactCenterX();
        float y = bounds.exactCenterY();
        if (record) {
            canvas.drawCircle(x, y, size * RECORD_RADIUS, fill);
            return;
        }
        canvas.drawCircle(x, y, size * TAP_RADIUS, fill);
        for (int i = 0; i < RAYS; i++) {
            double angle = 2 * Math.PI * i / RAYS;
            float dx = (float) Math.cos(angle) * size;
            float dy = (float) Math.sin(angle) * size;
            canvas.drawLine(x + dx * RAY_FROM, y + dy * RAY_FROM, x + dx * RAY_TO, y + dy * RAY_TO, line);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        line.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        line.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

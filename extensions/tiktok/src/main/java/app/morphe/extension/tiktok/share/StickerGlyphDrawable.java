/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Make sticker's symbol, a smiling sticker with its corner peeling up, drawn like the cover glyph. */
public final class StickerGlyphDrawable extends Drawable {
    /** Sticker half-size as a fraction of the shorter side. */
    private static final float HALF_FRACTION = 0.30f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final Path flap = new Path();
    private final RectF smile = new RectF();

    public StickerGlyphDrawable(int color, float strokeWidthPx) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
        paint.setStrokeWidth(strokeWidthPx);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) {
            return;
        }

        float centreX = bounds.exactCenterX();
        float centreY = bounds.exactCenterY();
        float half = Math.min(bounds.width(), bounds.height()) * HALF_FRACTION;
        float left = centreX - half;
        float top = centreY - half;
        float right = centreX + half;
        float bottom = centreY + half;
        float corner = half * 0.25f;
        float peel = half * 0.75f;

        // A rounded square whose bottom right corner is cut off where it lifts.
        outline.reset();
        outline.moveTo(left + corner, top);
        outline.lineTo(right - corner, top);
        outline.quadTo(right, top, right, top + corner);
        outline.lineTo(right, bottom - peel);
        outline.lineTo(right - peel, bottom);
        outline.lineTo(left + corner, bottom);
        outline.quadTo(left, bottom, left, bottom - corner);
        outline.lineTo(left, top + corner);
        outline.quadTo(left, top, left + corner, top);
        outline.close();
        canvas.drawPath(outline, paint);

        // The lifted corner folds back over the face.
        flap.reset();
        flap.moveTo(right - peel, bottom);
        flap.quadTo(right - peel, bottom - peel, right, bottom - peel);
        canvas.drawPath(flap, paint);

        // Two eyes and a smile, kept clear of the flap.
        float faceX = centreX - half * 0.18f;
        float faceY = centreY - half * 0.15f;
        canvas.drawPoint(faceX - half * 0.30f, faceY - half * 0.20f, paint);
        canvas.drawPoint(faceX + half * 0.30f, faceY - half * 0.20f, paint);
        smile.set(faceX - half * 0.42f, faceY - half * 0.30f, faceX + half * 0.42f, faceY + half * 0.40f);
        canvas.drawArc(smile, 25f, 130f, false, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

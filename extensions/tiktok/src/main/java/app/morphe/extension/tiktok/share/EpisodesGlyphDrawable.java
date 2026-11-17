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

/** Save episodes' symbol: a video frame with a play mark and a second frame's edge behind it. */
public final class EpisodesGlyphDrawable extends Drawable {
    /** Frame half-size as a fraction of the shorter side, matching Save cover's frame. */
    private static final float HALF_FRACTION = 0.30f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private final Path behind = new Path();
    private final Path play = new Path();

    public EpisodesGlyphDrawable(int color, float strokeWidthPx) {
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

        float half = Math.min(bounds.width(), bounds.height()) * HALF_FRACTION;
        // The front frame sits a little low and left, so the one behind shows above and right.
        float shift = half * 0.22f;
        float centreX = bounds.exactCenterX() - shift;
        float centreY = bounds.exactCenterY() + shift;
        frame.set(centreX - half, centreY - half * 0.80f, centreX + half, centreY + half * 0.80f);
        float corner = half * 0.25f;
        canvas.drawRoundRect(frame, corner, corner, paint);

        float gap = shift * 1.6f;
        behind.reset();
        behind.moveTo(frame.left + gap, frame.top - gap);
        behind.lineTo(frame.right + gap - corner, frame.top - gap);
        behind.quadTo(frame.right + gap, frame.top - gap, frame.right + gap, frame.top - gap + corner);
        behind.lineTo(frame.right + gap, frame.bottom - gap);
        canvas.drawPath(behind, paint);

        float mark = half * 0.38f;
        play.reset();
        play.moveTo(centreX - mark * 0.7f, centreY - mark);
        play.lineTo(centreX + mark, centreY);
        play.lineTo(centreX - mark * 0.7f, centreY + mark);
        play.close();
        canvas.drawPath(play, paint);
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

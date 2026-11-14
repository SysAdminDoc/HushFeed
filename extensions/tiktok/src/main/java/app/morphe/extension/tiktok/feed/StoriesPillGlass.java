/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;

import app.morphe.extension.tiktok.settings.Settings;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Glass Stories pill (#409): the "N Stories" pill under the Following tab drawn as the bottom
 * bar's liquid glass, with round ends, in place of TikTok's flat gray. On Android 12 and newer the
 * video behind it is read back and blurred into it the way the bar's glass does it
 * ({@link GlassVideoBlur}); below that, or until a strip has been read, it is the plain glass.
 *
 * <p>TikTok paints the pill (the overlay pass's Following story target, a FrameLayout) with one
 * GradientDrawable, 15% black with a hairline (Login2, 47.7.3). The pass hands each pill it finds
 * to {@link #apply}, which swaps that background for {@link Glass} and puts TikTok's own back once
 * the switch is off or Hushfeed is paused. A background TikTok set since is never overwritten.
 */
final class StoriesPillGlass {
    /** Pills this class gave glass, with the background each had. Package-private for tests. */
    static final Map<View, Drawable> ORIGINALS = new WeakHashMap<>();

    private StoriesPillGlass() {
    }

    /** True while the switch is on and Hushfeed isn't paused. */
    static boolean wanted() {
        return Settings.GLASS_STORIES_PILL.get();
    }

    /** True while a pill holds the glass, so the pass keeps running to put TikTok's back. */
    static boolean holdsAny() {
        return !ORIGINALS.isEmpty();
    }

    /**
     * Gives {@code pill} the glass, or puts back the background it had. A pill with no background
     * of its own is a layout this wasn't written for, and keeps TikTok's look.
     */
    static void apply(View pill, boolean glass) {
        if (pill == null) return;
        Drawable current = pill.getBackground();
        if (glass) {
            if (current == null || current instanceof Glass) return;
            ORIGINALS.put(pill, current);
            pill.setBackground(new Glass(pill.getResources().getDisplayMetrics().density));
            return;
        }
        if (!ORIGINALS.containsKey(pill)) return;
        Drawable original = ORIGINALS.remove(pill);
        if (current instanceof Glass) pill.setBackground(original);
    }

    /** The pill's glass: the blurred video behind it where there is one, the glass over that. */
    static final class Glass extends Drawable {
        /** Null below Android 12, where only the plain glass is drawn. */
        final GlassVideoBlur blur;
        private final SeeThroughBottomBar.GlassPill pill;

        Glass(float density) {
            pill = new SeeThroughBottomBar.GlassPill(density, false);
            blur = GlassVideoBlur.supported()
                    ? new GlassVideoBlur(density, this, Settings.GLASS_STORIES_PILL, false)
                    : null;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            pill.setBounds(bounds);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            Callback callback = getCallback();
            if (blur != null && callback instanceof View) {
                blur.drawBehind(canvas, (View) callback, bounds, bounds.height() / 2f);
            }
            pill.draw(canvas);
        }

        @Override
        public void setAlpha(int alpha) {
            pill.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public int getAlpha() {
            return pill.getAlpha();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            pill.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}

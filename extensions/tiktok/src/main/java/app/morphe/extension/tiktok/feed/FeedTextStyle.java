/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * The color and the backdrop picked for the text on a video (#318). {@link FeedTextSize} owns the
 * views these go on, since it already follows their binds. Zero means TikTok's own.
 */
final class FeedTextStyle {
    private static final float CORNER_DP = 4;

    private FeedTextStyle() { }

    static int color() {
        switch (Settings.FEED_TEXT_COLOR.get()) {
            case "white": return 0xFFFFFFFF;
            case "light_gray": return 0xFFC8C8C8;
            case "gray": return 0xFF8C8C8C;
            case "black": return 0xFF000000;
            case "yellow": return 0xFFFFD60A;
            default: return 0;
        }
    }

    /** See-through, so the video still shows behind the words. */
    static int backdrop() {
        switch (Settings.FEED_TEXT_BACKDROP.get()) {
            case "black": return 0x99000000;
            case "dark_gray": return 0x99303030;
            case "gray": return 0x99707070;
            case "white": return 0xCCFFFFFF;
            default: return 0;
        }
    }

    /** One view's backdrop, and the background to give back when it goes. */
    static final class Band {
        private Drawable nativeBackground;
        private Drawable written;
        private int writtenColor;

        void apply(View view) {
            int color = backdrop();
            if (color == 0) {
                restore(view);
                return;
            }
            // A background TikTok set since our write is the newer one to go back to.
            if (written == null || view.getBackground() != written) {
                nativeBackground = view.getBackground();
                written = null;
                writtenColor = 0;
            }
            if (writtenColor == color) return;
            GradientDrawable band = new GradientDrawable();
            band.setColor(color);
            band.setCornerRadius(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, CORNER_DP,
                    view.getResources().getDisplayMetrics()));
            view.setBackground(band);
            written = band;
            writtenColor = color;
        }

        void restore(View view) {
            if (written == null) return;
            if (view.getBackground() == written) view.setBackground(nativeBackground);
            nativeBackground = null;
            written = null;
            writtenColor = 0;
        }
    }
}

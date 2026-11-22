/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * UI scale (#477, ported from inxomnyaa's pull request).
 *
 * <p>TikTok's text size manager rewrites the app's display density from its text size factor
 * (X.03aF's LIZLLL at startup and LJFF for each activity, on 47.9.3). This scales that factor
 * by the UI size picked in Hushfeed settings, so layouts, icons and text all shrink together and
 * TikTok's own sizes keep their proportions. Smaller text sizes answers the factor itself, so
 * the two multiply.
 */
@SuppressWarnings("unused")
public final class UiScale {
    private UiScale() {}

    /** TikTok's density factor, scaled by the UI size picked in Hushfeed settings. */
    public static float scaleDensityFactor(float factor) {
        return scale(factor, Settings.UI_SCALE_PERCENT.get());
    }

    /**
     * The factor at {@code percent} of its size. 100 leaves it alone, and so does anything outside
     * the setting's range: the setting can't hold such a value, so it isn't a size anyone picked.
     */
    static float scale(float factor, int percent) {
        if (percent >= 100 || percent < Settings.UI_SCALE_PERCENT.minimum()) return factor;
        return factor * percent / 100f;
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * Shrinks TikTok's whole interface. At startup TikTok's text size manager rewrites the app's
 * display density from a factor (1.0 on its default size); this scales that factor, so layouts,
 * icons and text all get smaller together and TikTok's own sizes keep their proportions.
 */
@SuppressWarnings("unused")
public final class UiScale {
    private UiScale() {}

    /** TikTok's density factor, scaled by the UI size picked in Hushfeed settings. */
    public static float scaleDensityFactor(float factor) {
        int percent = Settings.UI_SCALE_PERCENT.get();
        if (percent <= 0 || percent >= 100) return factor;
        return factor * percent / 100f;
    }
}

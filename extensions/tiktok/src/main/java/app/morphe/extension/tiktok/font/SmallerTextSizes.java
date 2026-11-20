/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * Adds stops below the smallest size on TikTok's own Text size slider (Settings and privacy >
 * Accessibility > Text size).
 *
 * TikTok keeps a mode from 0 (its smallest, 100 %) to 3 and turns it into a factor for the whole
 * interface. The slider works in positions; with the switch on it gets {@link #EXTRA_STOPS} more,
 * at its left end. TikTok still stores only its own mode, 0 for every extra stop, and which extra
 * stop was picked is kept here, so turning the switch off leaves TikTok exactly as it was.
 */
@SuppressWarnings("unused")
public final class SmallerTextSizes {
    /** Extra stops, closest to TikTok's smallest first. */
    private static final float[] FACTORS = {0.93f, 0.87f, 0.8f};
    private static final int EXTRA_STOPS = FACTORS.length;

    private SmallerTextSizes() {}

    private static int extraStops() {
        return Settings.SMALLER_TEXT_SIZES.get() ? EXTRA_STOPS : 0;
    }

    private static int level() {
        int level = Settings.SMALLER_TEXT_SIZE_LEVEL.get();
        return Math.max(0, Math.min(level, EXTRA_STOPS));
    }

    /** The slider's highest position, written when the slider is built. */
    public static int sliderMax(int original) {
        return original + extraStops();
    }

    /** TikTok's saved mode to the position the slider opens on. */
    public static int modeToPosition(int mode) {
        int extra = extraStops();
        if (extra == 0) return mode;
        return mode > 0 ? mode + extra : extra - level();
    }

    /** A slider position to TikTok's mode, for the page's preview. */
    public static int positionToMode(int position) {
        return Math.max(position - extraStops(), 0);
    }

    /** The confirmed position: keeps which extra stop it was and hands TikTok its mode. */
    public static int commitPosition(int position) {
        int extra = extraStops();
        if (extra > 0) {
            Settings.SMALLER_TEXT_SIZE_LEVEL.save(position < extra ? extra - position : 0);
        }
        return positionToMode(position);
    }

    /** True when TikTok is on its smallest mode and one of the extra stops is picked. */
    public static boolean useExtraStop(int mode) {
        return mode == 0 && extraStops() > 0 && level() > 0;
    }

    /** The factor for the picked extra stop. */
    public static float extraStopFactor() {
        int level = level();
        return level == 0 ? 1f : FACTORS[level - 1];
    }
}

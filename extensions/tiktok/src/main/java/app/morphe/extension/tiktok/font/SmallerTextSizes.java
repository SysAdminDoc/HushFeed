/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * Smaller text sizes (#476, ported from inxomnyaa's pull request): three stops below the
 * smallest size on TikTok's own Text size slider, in Settings and privacy > Accessibility.
 *
 * <p>TikTok keeps a mode from 0, its smallest and its default, to 3, and its startup density
 * rewrite scales the whole interface by the mode's factor. The slider works in positions. With
 * the switch on it gets {@link #EXTRA_STOPS} more at its left end: positions 0 to 2 are the
 * extra stops, smallest first, and position 3 on is TikTok's mode 0 on. TikTok still saves only
 * its own mode, 0 for every extra stop, and which extra stop was picked is kept here, so with
 * the switch off TikTok is exactly where it was.
 */
@SuppressWarnings("unused")
public final class SmallerTextSizes {
    /** The extra stops' factors, the one next to TikTok's smallest first. */
    private static final float[] FACTORS = {0.93f, 0.87f, 0.8f};
    static final int EXTRA_STOPS = FACTORS.length;

    private SmallerTextSizes() {}

    private static int extraStops() {
        return Settings.SMALLER_TEXT_SIZES.get() ? EXTRA_STOPS : 0;
    }

    /** The extra stop last confirmed, 0 for none, 1 to 3 counting down from TikTok's smallest. */
    private static int level() {
        return Math.max(0, Math.min(Settings.SMALLER_TEXT_SIZE_LEVEL.get(), EXTRA_STOPS));
    }

    /** The slider's top position, from TikTok's own top, while its constructor writes it. */
    public static int sliderMax(int tiktokTop) {
        return tiktokTop + extraStops();
    }

    /** TikTok's saved mode to the position the Text size page opens on. */
    public static int modeToPosition(int mode) {
        int extra = extraStops();
        if (extra == 0 || mode < 0) return mode;
        return mode > 0 ? mode + extra : extra - level();
    }

    /** A slider position to the mode it shows, for the page's preview. Every extra stop shows mode 0. */
    public static int positionToMode(int position) {
        return Math.max(position - extraStops(), 0);
    }

    /**
     * The position the page was confirmed on, on its way to TikTok's mode store: keeps which
     * extra stop it is, or none, and hands TikTok its mode. With the switch off the position is
     * TikTok's mode already and nothing is kept, so a stop picked earlier comes back with it.
     */
    public static int commitPosition(int position) {
        int extra = extraStops();
        if (extra > 0) {
            Settings.SMALLER_TEXT_SIZE_LEVEL.save(position < extra ? extra - position : 0);
        }
        return positionToMode(position);
    }

    /** Whether an extra stop answers TikTok's factor: TikTok on mode 0 and one of them picked. */
    public static boolean useExtraStop(int mode) {
        return mode == 0 && extraStops() > 0 && level() > 0;
    }

    /** The picked extra stop's factor, 1 for none. */
    public static float extraStopFactor() {
        int level = level();
        return level == 0 ? 1f : FACTORS[level - 1];
    }
}

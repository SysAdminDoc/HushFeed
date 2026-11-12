/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Makes the bottom bar shorter or taller. TikTok sizes the bar from a 47 dp dimension, keeps the
 * space every screen leaves above it in a static it works out once a launch (49 dp on most
 * phones), and places a divider and a stub over the bar with 47 and 45 dp literals. Each of
 * those passes through here, so the bar and everything sitting on it move together.
 */
public final class BottomBarHeight {
    public static final String TIKTOK = "tiktok";
    public static final String SHORTER = "40";
    public static final String SHORT = "44";
    public static final String TALL = "52";
    public static final String TALLER = "56";
    /** TikTok's own bar, in dp. */
    static final int TIKTOK_DP = 47;

    /** The picked height in dp, read once a launch like TikTok reads its own. */
    private static volatile int pickedDp;

    private BottomBarHeight() {
    }

    /** The height a choice stands for, or TikTok's for anything that isn't one of the choices. */
    static int heightFor(String choice) {
        if (SHORTER.equals(choice) || SHORT.equals(choice) || TALL.equals(choice) || TALLER.equals(choice)) {
            return Integer.parseInt(choice);
        }
        return TIKTOK_DP;
    }

    static int heightDp() {
        int dp = pickedDp;
        if (dp != 0) return dp;
        // TikTok can work out its static while its Application is still attaching. The settings
        // aren't readable before the context arrives, and that launch keeps TikTok's bar.
        if (!Utils.hasContext()) return TIKTOK_DP;
        try {
            dp = heightFor(Settings.BOTTOM_BAR_HEIGHT.get());
        } catch (Throwable problem) {
            Logger.printException(() -> "Bottom bar height: left it to TikTok", problem);
            dp = TIKTOK_DP;
        }
        pickedDp = dp;
        return dp;
    }

    static void resetForTests() {
        pickedDp = 0;
    }

    /** A size read from the bar's dimension, in pixels. */
    @SuppressWarnings("unused")
    public static float barPixels(float pixels) {
        int dp = heightDp();
        return dp == TIKTOK_DP ? pixels : pixels * dp / TIKTOK_DP;
    }

    /** The same size where TikTok reads it as whole pixels. */
    @SuppressWarnings("unused")
    public static int barPixels(int pixels) {
        int dp = heightDp();
        return dp == TIKTOK_DP ? pixels : Math.round((float) pixels * dp / TIKTOK_DP);
    }

    /** A distance up from the screen's bottom edge, in dp, that clears the bar. */
    @SuppressWarnings("unused")
    public static float aboveBar(float dp) {
        return dp + heightDp() - TIKTOK_DP;
    }

    /** The room TikTok's screens leave at the bottom for the bar, in dp. */
    @SuppressWarnings("unused")
    public static int navHeight(int dp) {
        return Math.max(0, dp + heightDp() - TIKTOK_DP);
    }
}

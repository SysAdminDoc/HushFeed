/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Picks which of TikTok's bottom tab icon sets the app draws (#285). TikTok chooses between them
 * with an experiment that it reads once a launch, so people only get the newer sets if the
 * server hands them out.
 */
public final class BottomTabIcons {
    public static final String TIKTOK = "tiktok";
    public static final String ORIGINAL = "original";
    public static final String NEW_STYLE_1 = "new_1";
    public static final String NEW_STYLE_2 = "new_2";
    /** TikTok's own answer stands. */
    static final int KEEP = -1;

    private BottomTabIcons() {
    }

    /** The values TikTok's experiment takes: it maps anything but 2 and 5 to the original set. */
    static int versionFor(String style) {
        if (ORIGINAL.equals(style)) return 0;
        if (NEW_STYLE_1.equals(style)) return 2;
        if (NEW_STYLE_2.equals(style)) return 5;
        return KEEP;
    }

    /** What the experiment should read, or -1 for whatever TikTok would have read. */
    @SuppressWarnings("unused")
    public static int version() {
        try {
            return versionFor(Settings.BOTTOM_TAB_ICON_STYLE.get());
        } catch (Throwable problem) {
            Logger.printException(() -> "Bottom bar icons: left them to TikTok", problem);
            return KEEP;
        }
    }
}

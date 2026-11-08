/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.theme;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * The "Add comment..." bar under a post opened from search, a profile or a link, for AMOLED dark
 * theme (#171).
 *
 * <p>TikTok leaves that bar see-through. A video there stops above it, so the bar shows the
 * page's black, but a photo post runs on underneath and its photo shows through. The patch calls
 * {@link #paint} once TikTok has built the bar, with the color picked for AMOLED dark theme, and
 * the bar takes it whenever the page is dark. Light mode keeps TikTok's own look.
 */
public final class AmoledInputBar {
    private AmoledInputBar() {}

    /** Called with the bar's row and the AMOLED color, right after TikTok sets the bar up. */
    public static void paint(View bar, int argb) {
        if (bar == null) return;
        try {
            // Off in Hushfeed settings, the bar keeps TikTok's look like the rest (#372).
            if (!AmoledTheme.on()) return;
            // The bar's own context carries the night mode TikTok applied, which the system's doesn't.
            if (SettingsUi.isDarkContext(bar.getContext())) bar.setBackgroundColor(argb);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not paint the comment bar", failure);
        }
    }
}

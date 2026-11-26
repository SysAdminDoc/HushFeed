/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.theme;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * TikTok Lite's own dark mode, turned on (#543).
 *
 * <p>Lite 47.8.2 carries TikTok's dark theme whole: a saved mode (Keva repo "repo_dark_mode", key
 * "dark_mode", AppCompat's night values, 1 light by default), an activity processor that hands it
 * to AppCompat's default night mode at the first screen and then gives every screen that supports
 * dark mode TikTok's dark TUX theme, and a list of hybrid pages that get dark parameters. Nothing
 * on Lite's own settings ever saves dark, so the mode stays light. The patch puts {@link #mode}
 * on the getter every one of those readers goes through, which leaves the saved value alone: with
 * the switch off, Lite reads what it saved, and a restart puts it back.
 */
@SuppressWarnings("unused")
public final class LiteDarkMode {
    /** {@link Settings#LITE_DARK_MODE}'s key, for a read before the settings context. */
    static final String SWITCH_KEY = "lite_dark_mode";

    /** AppCompat's MODE_NIGHT_YES, the value TikTok saves for dark. */
    static final int DARK = 2;

    private static volatile boolean logged;

    /**
     * The dark mode Lite reads: dark while the switch is on, otherwise the one it saved
     * ([saved]: -1 follows the phone, 1 light, 2 dark).
     */
    public static int mode(int saved) {
        boolean on = Utils.getContext() != null ? Settings.LITE_DARK_MODE.get() : EarlySwitch.isOn(SWITCH_KEY);
        if (!on) return saved;
        if (!logged) {
            logged = true;
            Logger.printInfo(() -> "Lite dark mode: dark in place of the saved mode " + saved);
        }
        return DARK;
    }

    /** For tests: log the first dark answer again. */
    static void resetForTests() {
        logged = false;
    }

    private LiteDarkMode() {}
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Exit with one Back (#489). TikTok quits on a second Back within a short gap and shows "Tap
 * again to exit" on the first. With the switch on, every Back counts as a first one, so TikTok
 * never quits on its own, and its toast is skipped the way TikTok skips it for an activity that
 * is finishing. Back then goes on to the tab it started on: Home leaves the app there
 * ({@link StartPage#leaveOnBack}), and any other tab still goes to Home first.
 */
public final class BackExit {
    static final String FAMILY = "exit with one back";

    private BackExit() {
    }

    static boolean isOn() {
        return Settings.EXIT_WITH_ONE_BACK.get();
    }

    /**
     * TikTok's compare of the time since the last Back with its quit gap: above zero when the
     * Back comes after the gap, a first Back. With the switch on every Back is one.
     */
    public static int pastTheGap(int compared) {
        if (compared > 0) return compared;
        try {
            if (!isOn()) return compared;
            HookStatus.bound(FAMILY, "no second Back");
            return 1;
        } catch (Throwable failure) {
            Logger.printException(() -> "Exit with one Back failed; TikTok keeps its second Back", failure);
            return compared;
        }
    }

    /**
     * Whether TikTok skips its "Tap again to exit" toast, given its own answer (non-zero when the
     * activity is finishing). With the switch on it always does.
     */
    public static int skipAgainToast(int finishing) {
        if (finishing != 0) return finishing;
        try {
            if (!isOn()) return finishing;
            HookStatus.bound(FAMILY, "no toast");
            return 1;
        } catch (Throwable failure) {
            Logger.printException(() -> "Exit with one Back failed; TikTok shows its toast", failure);
            return finishing;
        }
    }
}

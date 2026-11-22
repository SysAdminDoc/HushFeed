/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.foldable;

import android.app.Activity;
import android.content.pm.ActivityInfo;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;

/**
 * Screens that turn with a tablet (#490). On a tablet TikTok lets turn, its main screen asks to
 * follow the device's rotation, but a profile opened from search keeps the portrait lock TikTok's
 * manifest gives it, and the picture a comment carries asks for portrait each time it's created,
 * resumed or turned. On Android 15 and lower a tablet honors those, so either one flips the
 * screen to portrait. Here each of them asks for what the main screen asks for instead, only
 * where TikTok's own check says this device supports landscape. A phone, a tablet TikTok keeps
 * upright, and Pause all leave the screens as TikTok asks.
 */
public final class TabletRotation {
    /** TikTok's own check whether this device's screens may turn; the patch's bridge, swapped out in tests. */
    interface Landscape {
        boolean supported(Activity activity);
    }

    static volatile Landscape landscape = TabletRotation::tikTokSupportsLandscape;
    private static volatile boolean failureLogged;

    private TabletRotation() {
    }

    /**
     * The orientation a turning screen asks for: following the device on a tablet TikTok lets
     * turn, {@code requested} otherwise. Called with each orientation the comment picture asks for.
     */
    public static int orientation(Activity activity, int requested) {
        return turns(activity) ? ActivityInfo.SCREEN_ORIENTATION_FULL_USER : requested;
    }

    /** Called as a profile opened from search finishes being created. */
    public static void follow(Activity activity) {
        if (!turns(activity) || activity.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_FULL_USER) {
            return;
        }
        try {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        } catch (IllegalStateException refused) {
            // A see-through screen on Android 8.0 can't ask for an orientation; it keeps TikTok's.
        }
    }

    static boolean turns(Activity activity) {
        if (activity == null || Setting.isPaused()) return false;
        try {
            return landscape.supported(activity);
        } catch (Throwable failure) {
            if (!failureLogged) {
                failureLogged = true;
                Logger.printException(() -> "Could not ask TikTok whether this screen may turn", failure);
            }
            return false;
        }
    }

    /** TikTok's check whether this device supports landscape. The patch writes it; unpatched, no. */
    static boolean tikTokSupportsLandscape(Activity activity) {
        return false;
    }

    static void resetForTests() {
        landscape = TabletRotation::tikTokSupportsLandscape;
        failureLogged = false;
    }
}

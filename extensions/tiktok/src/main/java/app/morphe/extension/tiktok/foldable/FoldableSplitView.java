/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.foldable;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Build;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Map;
import java.util.WeakHashMap;

public final class FoldableSplitView {
    /**
     * The side of the width threshold each activity's comment containers were built on, and the
     * threshold that decided it, recorded the first time TikTok asked while that activity was
     * current with the switch on. TikTok builds the containers once, when the feed attaches, and
     * its feed activity takes size changes itself, so they stay whatever the window does next.
     *
     * <p>The live check, container check and rebuild comparison use the same available width:
     * a configuration TikTok hands over, else the window's own bounds without the bars and
     * cutout a configuration's width leaves out, else the activity's configuration. An
     * unavailable width is not a narrow build or a crossing.
     */
    private static final Map<Activity, Built> BUILT_FOR = new WeakHashMap<>();

    private static final class Built {
        final boolean wide;
        final int threshold;

        Built(boolean wide, int threshold) {
            this.wide = wide;
            this.threshold = threshold;
        }
    }

    /** Stands in for {@link Activity#recreate()}, so a test can count. */
    interface Recreator {
        void recreate(Activity activity);
    }

    static Recreator recreator = Activity::recreate;

    private FoldableSplitView() { }

    /** Hook status says what TikTok was told each time it asked, so a report shows whether it asks. */
    static final String FAMILY = "split view";

    public static boolean shouldForce(Activity activity, Configuration configuration) {
        int width = configuration == null ? 0 : configuration.screenWidthDp;
        boolean force = decide(activity, width);
        HookStatus.bound(FAMILY, force ? "comments beside the video" : "comments left to TikTok");
        return force;
    }

    private static boolean decide(Activity activity, int width) {
        if (!Settings.FOLDABLE_SPLIT_VIEW.get()) return false;
        if (activity == null) activity = Utils.getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        try {
            if (Build.VERSION.SDK_INT >= 24 && (activity.isInMultiWindowMode() || activity.isInPictureInPictureMode())) return false;
            if (width <= 0) width = widthDp(activity, null);
            return width >= threshold();
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not determine the comment panel width", error);
            return false;
        }
    }

    private static int threshold() {
        return Math.max(320, Math.min(1600, Settings.FOLDABLE_SPLIT_VIEW_MIN_WIDTH_DP.get()));
    }

    private static boolean besideOtherApps(Activity activity) {
        return Build.VERSION.SDK_INT >= 24 && (activity.isInMultiWindowMode() || activity.isInPictureInPictureMode());
    }

    /**
     * Supplied width, the window's bounds, then the activity's configuration. TikTok keeps the
     * screen width its process started with in the configuration its activities report (#398),
     * so after the feed was built again for a wider window the new activity still said the old
     * width, the container check said no, and comments opened over the video. A configuration
     * handed over with a change is the new one, so it still comes first.
     */
    private static int widthDp(Activity activity, Configuration configuration) {
        int width = configuration == null ? 0 : configuration.screenWidthDp;
        if (width <= 0) width = windowWidthDp(activity);
        if (width > 0) return width;
        try {
            return activity.getResources().getConfiguration().screenWidthDp;
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not determine the comment panel width", error);
            return 0;
        }
    }

    /**
     * The window's width in dp, or 0 below API 30 or when it can't be read. A configuration's
     * width leaves out the system bars and cutout below API 35 and for apps targeting less, so
     * they come off here too and the build record and later changes compare the same thing.
     */
    private static int windowWidthDp(Activity activity) {
        if (Build.VERSION.SDK_INT < 30) return 0;
        try {
            WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
            int width = metrics.getBounds().width();
            if (Build.VERSION.SDK_INT < 35 || activity.getApplicationInfo().targetSdkVersion < 35) {
                Insets bars = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                width -= bars.left + bars.right;
            }
            float density = Build.VERSION.SDK_INT >= 34 ? metrics.getDensity()
                    : activity.getResources().getDisplayMetrics().density;
            return density > 0 && width > 0 ? Math.round(width / density) : 0;
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not read the window width", error);
            return 0;
        }
    }

    public static boolean shouldForceContainer() {
        Activity activity = Utils.getActivity();
        boolean enabled = Settings.FOLDABLE_SPLIT_VIEW.get();
        int width = activity != null && enabled ? widthDp(activity, null) : 0;
        boolean force = width > 0 && decide(activity, width);
        HookStatus.bound(FAMILY, force ? "split container" : "container left to TikTok");
        // Only an answer given with the switch on is a split decision. Off, the row says the
        // switch applies at the next start, and a record taken now would rebuild the feed on
        // the first configuration change after the switch is turned on, with no crossing at all.
        if (activity != null && enabled) {
            try {
                int threshold = threshold();
                if (width <= 0) return force;
                boolean wide = !besideOtherApps(activity) && width >= threshold;
                synchronized (BUILT_FOR) {
                    if (!BUILT_FOR.containsKey(activity)) BUILT_FOR.put(activity, new Built(wide, threshold));
                }
            } catch (RuntimeException error) {
                Logger.printException(() -> "Could not record the comment panel width", error);
            }
        }
        return force;
    }

    /**
     * The value TikTok's feed reads before it registers its split container, with -1 meaning the
     * container is set up. TikTok's server sends 1 to phones, and the feed then registers a
     * container only on a tablet, so on a wide phone window both checks above said yes and the
     * comments still opened over the video. With the switch on the container is always
     * registered, and {@link #shouldForceContainer} still decides by width whether it splits.
     */
    public static long splitContainerInit(long serverValue) {
        boolean on = Settings.FOLDABLE_SPLIT_VIEW.get();
        HookStatus.bound(FAMILY, on ? "feed container registered" : "feed container left to TikTok");
        return on ? -1L : serverValue;
    }

    /**
     * First thing in the feed activity's configuration change (issue #26). An unfold reaches the
     * feed as a size change rather than as a new activity, so the comment containers TikTok built
     * for the folded width stayed until TikTok was started again. When the width crosses the
     * threshold, the activity is built again, once per crossing. A window beside other apps is
     * left to TikTok, and with the switch off nothing is rebuilt.
     */
    public static void onConfigurationChanged(Activity activity, Configuration configuration) {
        if (activity == null || !Settings.FOLDABLE_SPLIT_VIEW.get()) return;
        if (besideOtherApps(activity)) return;
        Built built;
        synchronized (BUILT_FOR) {
            built = BUILT_FOR.get(activity);
        }
        // Never asked with the switch on, so nothing was built for the other width. The record's
        // own threshold decides, so a threshold edited mid-session applies at the next start,
        // as its row says, and not to the next rotation.
        if (built == null) return;
        int width = widthDp(activity, configuration);
        if (width <= 0 || (width >= built.threshold) == built.wide) return;
        synchronized (BUILT_FOR) {
            BUILT_FOR.remove(activity);
        }
        Logger.printInfo(() -> "Split view: the window crossed the width threshold, so the feed is built again");
        try {
            // TikTok puts the video that was playing back after the rebuild, unless Hide seen
            // videos has taken it out of the lists it reads again by then (issue #26).
            SeenVideoHistory.keepThroughRebuild();
            recreator.recreate(activity);
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not build the feed again for the new window width", error);
        }
    }

    static void forgetForTests() {
        synchronized (BUILT_FOR) {
            BUILT_FOR.clear();
        }
        recreator = Activity::recreate;
    }
}

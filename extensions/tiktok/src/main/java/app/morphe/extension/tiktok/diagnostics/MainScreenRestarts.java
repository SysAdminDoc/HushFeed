/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.diagnostics;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;

/**
 * Notes in the diagnostic report why TikTok's main screen started over in a running app (#419).
 * The feed goes back to its start when that happens, with no crash and no exit to show for it,
 * so a report only had two instances of the screen to go on. The line says what became of the
 * screen before: finished, torn down for a configuration change and which one, taken down by
 * the system and restored, or still open under the new one. Always on and touches nothing.
 */
public final class MainScreenRestarts {
    /** The screen before was finished, by TikTok or by something it called. */
    static final String FINISHED = "finished";
    /** The screen before was torn down so the new one could take a configuration change. */
    static final String CONFIGURATION = "configuration";
    /** The screen before was destroyed without being finished or changing configuration. */
    static final String DESTROYED = "destroyed";

    private static WeakReference<Application> followed = new WeakReference<>(null);
    private static Class<?> mainClass;
    private static int started;
    private static WeakReference<Activity> last = new WeakReference<>(null);
    /** How the last screen ended, or null while it's still open. */
    private static String lastEnd;
    private static long lastEndAt;
    private static Configuration lastConfiguration;
    /** Why the system may have destroyed the last screen, or null when nothing points anywhere. */
    private static String lastWhy;

    private static final Application.ActivityLifecycleCallbacks CALLBACKS = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity created, Bundle state) { created(created, state); }
        @Override public void onActivityDestroyed(Activity destroyed) { destroyed(destroyed); }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityResumed(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    };

    private MainScreenRestarts() {
    }

    /**
     * From the patched {@code MainActivity.onCreate}, before the host's own onCreate, so this
     * instance's creation still reaches the callbacks registered here.
     */
    public static void onMainActivityCreate(Activity activity) {
        try {
            Application application = activity == null ? null : activity.getApplication();
            if (application == null || followed.get() == application) return;
            followed = new WeakReference<>(application);
            mainClass = activity.getClass();
            application.registerActivityLifecycleCallbacks(CALLBACKS);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not follow TikTok's main screen", error);
        }
    }

    static void created(Activity activity, Bundle state) {
        if (activity == null || activity.getClass() != mainClass) return;
        try {
            started++;
            if (started > 1) {
                Activity before = last.get();
                boolean stillOpen = lastEnd == null && before != null && !before.isDestroyed();
                Configuration now = activity.getResources().getConfiguration();
                int changes = lastConfiguration == null || now == null ? 0 : lastConfiguration.diff(now);
                long ago = lastEnd == null ? -1 : SystemClock.uptimeMillis() - lastEndAt;
                String line = describe(started, stillOpen ? null : lastEnd, state != null, changes, ago,
                        intentOf(activity), stillOpen ? null : lastWhy);
                Logger.printInfo(() -> line);
            }
            last = new WeakReference<>(activity);
            lastEnd = null;
            lastConfiguration = null;
            lastWhy = null;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not note TikTok's main screen starting", error);
        }
    }

    static void destroyed(Activity activity) {
        if (activity == null || activity != last.get()) return;
        try {
            lastEnd = activity.isChangingConfigurations() ? CONFIGURATION
                    : activity.isFinishing() ? FINISHED : DESTROYED;
            lastEndAt = SystemClock.uptimeMillis();
            Configuration then = activity.getResources().getConfiguration();
            lastConfiguration = then == null ? null : new Configuration(then);
            if (DESTROYED.equals(lastEnd)) {
                Runtime runtime = Runtime.getRuntime();
                lastWhy = destroyedWhy(dontKeepActivities(activity),
                        runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory());
            }
        } catch (Throwable error) {
            Logger.printException(() -> "Could not note TikTok's main screen closing", error);
        }
    }

    /** Developer options' Don't keep activities, which destroys every screen the moment it's left. */
    private static boolean dontKeepActivities(Activity activity) {
        try {
            return android.provider.Settings.Global.getInt(activity.getContentResolver(),
                    android.provider.Settings.Global.ALWAYS_FINISH_ACTIVITIES, 0) != 0;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * Why a screen the app didn't finish was destroyed with the app still running (#419). Android
     * does it for Don't keep activities, and when the app's Java heap passes three quarters of its
     * limit, when it closes the app's screens in the background to free memory.
     */
    static String destroyedWhy(boolean dontKeepActivities, long heapUsed, long heapMax) {
        if (dontKeepActivities) return "Don't keep activities is on in Developer options";
        if (heapMax <= 0 || heapUsed < 0) return null;
        String heap = "Java heap " + heapUsed / (1024 * 1024) + " of " + heapMax / (1024 * 1024) + " MB";
        return heapUsed * 4 >= heapMax * 3 ? heap + ", past the three quarters where Android closes background screens"
                : heap;
    }

    /**
     * The intent that started the new screen. A link is named by its scheme alone: the report's
     * redaction drops any address, and the scheme still tells TikTok's own link from a web one.
     */
    private static String intentOf(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent == null) return "none";
        StringBuilder text = new StringBuilder(String.valueOf(intent.getAction()));
        text.append(", flags 0x").append(Integer.toHexString(intent.getFlags()));
        Uri data = intent.getData();
        if (data != null) text.append(", a ").append(data.getScheme()).append(" link");
        Uri referrer = activity.getReferrer();
        if (referrer != null) text.append(", from ").append(referrer.getHost());
        return text.toString();
    }

    /**
     * The report line. {@code end} is how the screen before ended, null while it's still open;
     * {@code ago} is how long before this one started, -1 when unknown.
     */
    static String describe(int count, String end, boolean restored, int changes, long ago, String intent) {
        return describe(count, end, restored, changes, ago, intent, null);
    }

    /** The same, with {@code why} from {@link #destroyedWhy} for a screen the system destroyed. */
    static String describe(int count, String end, boolean restored, int changes, long ago, String intent,
                           String why) {
        StringBuilder line = new StringBuilder("TikTok's main screen started over, ")
                .append(ordinal(count)).append(" this run: ");
        if (end == null) {
            line.append("the one before is still open under it");
        } else {
            line.append("the one before was ");
            if (CONFIGURATION.equals(end)) {
                line.append("torn down for a configuration change");
            } else if (FINISHED.equals(end)) {
                line.append("finished");
            } else {
                line.append("destroyed by the system");
                if (why != null) line.append(" (").append(why).append(')');
            }
            if (ago >= 0) line.append(' ').append(String.format(Locale.US, "%.1f", ago / 1000.0)).append(" s earlier");
            List<String> names = configurationNames(changes);
            if (!names.isEmpty()) line.append(", changed ").append(String.join(", ", names));
        }
        line.append(restored ? ", restoring its saved state" : ", starting fresh");
        line.append(". Intent ").append(intent);
        return line.toString();
    }

    /** The parts of a {@link Configuration#diff} mask, in plain words. */
    static List<String> configurationNames(int changes) {
        List<String> names = new ArrayList<>();
        if ((changes & (ActivityInfo.CONFIG_MCC | ActivityInfo.CONFIG_MNC)) != 0) names.add("SIM country");
        if ((changes & ActivityInfo.CONFIG_LOCALE) != 0) names.add("language");
        if ((changes & ActivityInfo.CONFIG_LAYOUT_DIRECTION) != 0) names.add("layout direction");
        if ((changes & ActivityInfo.CONFIG_ORIENTATION) != 0) names.add("orientation");
        if ((changes & (ActivityInfo.CONFIG_SCREEN_SIZE | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE
                | ActivityInfo.CONFIG_SCREEN_LAYOUT)) != 0) names.add("screen size");
        if ((changes & ActivityInfo.CONFIG_DENSITY) != 0) names.add("display size");
        if ((changes & ActivityInfo.CONFIG_UI_MODE) != 0) names.add("dark mode or UI mode");
        if ((changes & ActivityInfo.CONFIG_FONT_SCALE) != 0) names.add("font size");
        if ((changes & 0x10000000) != 0) names.add("bold text");
        if ((changes & (ActivityInfo.CONFIG_KEYBOARD | ActivityInfo.CONFIG_KEYBOARD_HIDDEN
                | ActivityInfo.CONFIG_NAVIGATION | ActivityInfo.CONFIG_TOUCHSCREEN)) != 0) names.add("keyboard or input");
        if ((changes & ActivityInfo.CONFIG_COLOR_MODE) != 0) names.add("color mode");
        if ((changes & 0x80000000) != 0) names.add("app resources");
        return names;
    }

    private static String ordinal(int count) {
        int tens = count % 100;
        if (tens >= 11 && tens <= 13) return count + "th";
        switch (count % 10) {
            case 1: return count + "st";
            case 2: return count + "nd";
            case 3: return count + "rd";
            default: return count + "th";
        }
    }

    static void resetForTests() {
        Application application = followed.get();
        followed = new WeakReference<>(null);
        mainClass = null;
        started = 0;
        last = new WeakReference<>(null);
        lastEnd = null;
        lastEndAt = 0;
        lastConfiguration = null;
        lastWhy = null;
        if (application != null) application.unregisterActivityLifecycleCallbacks(CALLBACKS);
    }
}

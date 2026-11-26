/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.diagnostics;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Asks TikTok to free memory before Android closes its main screen (#419).
 *
 * <p>After each garbage collection Android's ActivityThread compares the Java heap with its
 * limit, and past three quarters it has the system destroy the app's stopped screens, so the
 * main screen behind a profile, a video or the settings goes and the feed starts over when it
 * comes back. One reporter's heap reached 462 of 512 MB.
 *
 * <p>This watches the same reading at the same moment: a sentinel object that only a garbage
 * collection can free, whose finalizer reads the heap and leaves another one behind, the way
 * ActivityThread's own watcher works. Nothing runs between collections. Past five eighths it
 * hands TikTok {@link ComponentCallbacks2#TRIM_MEMORY_RUNNING_LOW}, the trim Android sends a
 * running app when memory is getting low, through the application and every live screen, on
 * the main thread like a real one. On 47.9.3 that level reaches TikTok's
 * {@code AwemeHostApplication.onTrimMemory}, whose TrimMemoryTask has its Fresco loader
 * ({@code FrescoImageLoaderImpl.trimMemory}) trim Fresco's caches as
 * {@code OnCloseToDalvikHeapLimit} and then calls {@code System.gc()}, and the callbacks it
 * registered: Keva's value cache, a search placeholder bitmap pool, a startup library prewarm
 * and a few small maps. The background levels (40 and up) are left alone, since TikTok treats
 * those as the app having left the screen.
 *
 * <p>At most one trim a minute, five minutes after one that freed under 16 MB, so a heap held
 * by something no trim reaches isn't trimmed over and over. The lowest reading in the ten
 * seconds after each trim goes to the diagnostic report, which is how a report tells whether it
 * helped.
 */
public final class HeapRelief implements LogBufferManager.ReportSection {
    static final String TITLE = "JAVA HEAP";
    /** The trim handed to TikTok. */
    static final int LEVEL = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
    /** Readings this long after a trim count toward what it freed. */
    static final long WINDOW_MS = 10_000L;
    /** The shortest time from one trim to the next. */
    static final long GAP_MS = 60_000L;
    /** The time from a trim that freed little to the next. */
    static final long QUIET_GAP_MS = 5 * 60_000L;
    /** Under this much freed, a trim counts as having freed little. */
    static final long LITTLE_BYTES = 16L * 1024 * 1024;

    private static final HeapRelief INSTANCE = new HeapRelief();
    private static final Object LOCK = new Object();

    /** Where a reading comes from, so a test can hand in its own figures. */
    interface Reading {
        long used();

        long max();
    }

    private static final Reading RUNTIME = new Reading() {
        @Override public long used() {
            Runtime runtime = Runtime.getRuntime();
            return runtime.totalMemory() - runtime.freeMemory();
        }

        @Override public long max() {
            return Runtime.getRuntime().maxMemory();
        }
    };
    static volatile Reading reading = RUNTIME;
    /**
     * Off in JVM tests, where the collections are the test JVM's own and a reading of its heap
     * would trim screens in whatever test runs next.
     */
    static volatile boolean followGc = !"robolectric".equals(Build.FINGERPRINT);

    private static WeakReference<Application> followed = new WeakReference<>(null);
    /** The screens alive in the app, read and written on the main thread only. */
    private static final Map<Activity, Boolean> LIVE = new WeakHashMap<>();
    private static volatile boolean watching;
    /** The sentinel waiting for the next collection; only weakly held, so a collection frees it. */
    private static volatile WeakReference<GcSentinel> sentinel;

    // Guarded by LOCK.
    private static long heapMax;
    private static long peak;
    private static boolean posted;
    private static long before;
    /** When the open trim went out, or -1 with none open. */
    private static long trimAt = -1;
    private static long lowestAfter;
    private static long nextAt;
    private static int trims;
    private static long lastBefore = -1;
    private static long lastAfter = -1;
    private static long totalFreed;

    private static final Application.ActivityLifecycleCallbacks CALLBACKS = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity activity, Bundle state) { LIVE.put(activity, Boolean.TRUE); }
        @Override public void onActivityDestroyed(Activity activity) { LIVE.remove(activity); }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityResumed(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    };

    private HeapRelief() {
    }

    /** From the patched {@code MainActivity.onCreate}, before the host's own onCreate. */
    public static void onMainActivityCreate(Activity activity) {
        try {
            Application application = activity == null ? null : activity.getApplication();
            if (application == null || followed.get() == application) return;
            followed = new WeakReference<>(application);
            application.registerActivityLifecycleCallbacks(CALLBACKS);
            LIVE.put(activity, Boolean.TRUE);
            LogBufferManager.registerReportSection(INSTANCE);
            if (!watching && followGc) {
                watching = true;
                arm();
            }
        } catch (Throwable error) {
            Logger.printException(() -> "Could not watch TikTok's Java heap", error);
        }
    }

    /** Leaves a sentinel for the next collection to free. */
    static void arm() {
        if (watching) sentinel = new WeakReference<>(new GcSentinel());
    }

    /**
     * Freed only by a garbage collection, so its finalizer runs once after each one, on the
     * finalizer thread, where it reads the heap and leaves the next sentinel. It does nothing
     * slow there: a trim is posted to the main thread.
     */
    static final class GcSentinel {
        @Override
        protected void finalize() {
            try {
                if (watching) {
                    Reading now = reading;
                    noteHeap(now.used(), now.max(), SystemClock.uptimeMillis());
                }
            } catch (Throwable ignored) {
                // One reading missed beats an exception on the finalizer thread.
            } finally {
                arm();
            }
        }
    }

    /** Whether {@code used} is past five eighths of {@code max}, an eighth short of Android's line. */
    static boolean pastThreshold(long used, long max) {
        return max > 0 && used * 8 >= max * 5;
    }

    /**
     * One reading of the heap after a collection: {@code used} of {@code max} bytes at uptime
     * {@code now}. Closes the trim before once its window has passed, and posts a trim when the
     * heap is past the threshold, the switch is on and the last trim is long enough ago.
     */
    static void noteHeap(long used, long max, long now) {
        if (max <= 0 || used < 0) return;
        boolean trim = false;
        String closed = null;
        synchronized (LOCK) {
            heapMax = max;
            if (used > peak) peak = used;
            if (trimAt >= 0) {
                if (now - trimAt <= WINDOW_MS) {
                    if (used < lowestAfter) lowestAfter = used;
                } else {
                    closed = close();
                }
            }
            if (trimAt < 0 && !posted && now >= nextAt && pastThreshold(used, max) && enabled()) {
                posted = true;
                before = used;
                trim = true;
            }
        }
        // Both go to the main thread: this runs on the finalizer thread, which Android stops the
        // app over if one finalizer takes too long.
        if (closed != null) {
            String line = closed;
            Utils.runOnMainThread(() -> Logger.printInfo(() -> line));
        }
        if (trim) Utils.runOnMainThread(HeapRelief::trimNow);
    }

    /** Ends the open trim with what it freed. Under LOCK. */
    private static String close() {
        long after = lowestAfter == Long.MAX_VALUE ? -1 : lowestAfter;
        long freed = after < 0 ? 0 : Math.max(0, lastBefore - after);
        lastAfter = after;
        totalFreed += freed;
        nextAt = trimAt + (freed < LITTLE_BYTES ? QUIET_GAP_MS : GAP_MS);
        trimAt = -1;
        return "Free memory early: trimmed TikTok's caches at " + mb(lastBefore) + " of " + mb(heapMax)
                + " MB Java heap, " + (after < 0 ? "no collection read after it"
                : mb(after) + " MB at the lowest in the " + WINDOW_MS / 1000 + " s after, " + mb(freed) + " MB freed")
                + " (" + ordinal(trims) + " this run)";
    }

    private static boolean enabled() {
        try {
            return Settings.FREE_MEMORY_EARLY.get();
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /** On the main thread: hands TikTok the trim, the way ActivityThread hands out a real one. */
    static void trimNow() {
        synchronized (LOCK) {
            posted = false;
            trimAt = SystemClock.uptimeMillis();
            lowestAfter = Long.MAX_VALUE;
            lastBefore = before;
            trims++;
        }
        Application application = followed.get();
        if (application != null) {
            try {
                application.onTrimMemory(LEVEL);
            } catch (Throwable error) {
                Logger.printException(() -> "TikTok's memory trim failed", error);
            }
        }
        for (Activity activity : new ArrayList<>(LIVE.keySet())) {
            if (activity.isFinishing() || activity.isDestroyed()) continue;
            try {
                activity.onTrimMemory(LEVEL);
            } catch (Throwable error) {
                Logger.printException(() -> "A screen's memory trim failed", error);
            }
        }
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public List<String> lines() {
        synchronized (LOCK) {
            if (heapMax <= 0) return Collections.emptyList();
            List<String> lines = new ArrayList<>();
            String state = enabled() ? "trims at " + mb(heapMax * 5 / 8) + " MB" : "off";
            lines.add("Highest after a collection: " + mb(peak) + " of " + mb(heapMax)
                    + " MB (Android closes background screens past " + mb(heapMax * 3 / 4)
                    + " MB, Free memory early " + state + ")");
            if (trims > 0) {
                StringBuilder last = new StringBuilder("Trimmed TikTok's caches ").append(trims)
                        .append(trims == 1 ? " time" : " times").append(", the last at ").append(mb(lastBefore))
                        .append(" MB");
                long after = trimAt >= 0 ? (lowestAfter == Long.MAX_VALUE ? -1 : lowestAfter) : lastAfter;
                if (after >= 0) {
                    last.append(", ").append(mb(after)).append(" MB at the lowest after it");
                    if (trimAt >= 0) last.append(" so far");
                }
                lines.add(last.toString());
                lines.add("Freed in all: " + mb(totalFreed + (trimAt >= 0 && after >= 0
                        ? Math.max(0, lastBefore - after) : 0)) + " MB");
            }
            return lines;
        }
    }

    private static long mb(long bytes) {
        return bytes / (1024 * 1024);
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

    static HeapRelief sectionForTests() {
        return INSTANCE;
    }

    static boolean watchingForTests() {
        return watching;
    }

    static Object sentinelForTests() {
        return sentinel;
    }

    static void resetForTests() {
        Application application = followed.get();
        if (application != null) application.unregisterActivityLifecycleCallbacks(CALLBACKS);
        followed = new WeakReference<>(null);
        LIVE.clear();
        watching = false;
        sentinel = null;
        reading = RUNTIME;
        followGc = !"robolectric".equals(Build.FINGERPRINT);
        synchronized (LOCK) {
            heapMax = 0;
            peak = 0;
            posted = false;
            before = 0;
            trimAt = -1;
            lowestAfter = 0;
            nextAt = 0;
            trims = 0;
            lastBefore = -1;
            lastAfter = -1;
            totalFreed = 0;
        }
    }
}

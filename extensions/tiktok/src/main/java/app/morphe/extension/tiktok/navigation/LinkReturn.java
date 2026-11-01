/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.os.Bundle;
import android.os.SystemClock;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;

/**
 * Back from the page a handed-over link opened goes back to the app the link came from (#276).
 *
 * <p>{@link LinkTaskGuard} moves a link tapped in Telegram into TikTok's own task (#227), and
 * there TikTok's main screen sits under the link's page, so Back from a profile showed the For
 * You feed instead of the chat, and Back on the feed only refreshed it. Opened in the sender's
 * task, as before the guard, Back went straight back to the chat. This watches the first page of
 * TikTok's that comes up within {@link #PAGE_WINDOW_MS} of the handover. When that page closes
 * onto the main screen, the sender's task comes to the front by id and TikTok's moves to the
 * back: the chat comes back and TikTok keeps its own Recents card with the feed in it. Moving
 * TikTok back alone isn't enough once it was picked from Recents, because the system puts the
 * home screen right under it then, and that's what showed. A short trip out of the app keeps the
 * watch: sharing the link's video to Telegram and switching back used to drop it, so Back from
 * the profile then landed on For You. Coming back after {@link #AWAY_MS}, or onto the main
 * screen, drops it, so Back inside TikTok later works as usual.
 */
public final class LinkReturn {
    /** How long the link's page may take to come up after the handover. */
    static final long PAGE_WINDOW_MS = 10_000;
    /** How long TikTok may be out of sight (sharing the video, a look at another app) with the watch kept. */
    static final long AWAY_MS = 5 * 60_000;

    /** Answers how many activities the task holds, or null when it can't say. */
    interface TaskSize {
        Integer of(Activity activity);
    }

    static volatile TaskSize taskSizeForTests;

    /** Brings the task with this id to the front. */
    interface TaskFront {
        void move(Activity from, int taskId);
    }

    static volatile TaskFront taskFrontForTests;

    private static WeakReference<Application> registeredOn = new WeakReference<>(null);
    private static Class<?> mainClass;
    /** The finished instance in the sender's task. It takes no part. */
    private static WeakReference<Activity> handedOver = new WeakReference<>(null);
    /** The sender's task, read before the handed-over instance finished, or -1. */
    private static int senderTask = -1;
    private static WeakReference<Activity> page = new WeakReference<>(null);
    /** TikTok's activity resumed last. */
    private static WeakReference<Activity> front = new WeakReference<>(null);
    private static long armedAt = -1;
    /** When TikTok went out of sight with the watch on, or -1 while it's in sight. */
    private static long awayAt = -1;
    /** The page closed onto more than the main screen, or the task couldn't be read. */
    private static boolean pageClosed;

    private static final Application.ActivityLifecycleCallbacks CALLBACKS =
            new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity activity) {
                    resumed(activity);
                }

                @Override public void onActivityPaused(Activity activity) {
                    paused(activity);
                }

                @Override public void onActivityStopped(Activity activity) {
                    stopped(activity);
                }

                @Override public void onActivityCreated(Activity activity, Bundle state) { }
                @Override public void onActivityStarted(Activity activity) { }
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
                @Override public void onActivityDestroyed(Activity activity) { }
            };

    private LinkReturn() {
    }

    /** From the guard, once it has handed the link to TikTok's own task. */
    static void arm(Activity handed, int fromTask) {
        try {
            Application application = handed.getApplication();
            if (application == null) return;
            if (registeredOn.get() != application) {
                Application old = registeredOn.get();
                if (old != null) old.unregisterActivityLifecycleCallbacks(CALLBACKS);
                application.registerActivityLifecycleCallbacks(CALLBACKS);
                registeredOn = new WeakReference<>(application);
            }
            mainClass = handed.getClass();
            handedOver = new WeakReference<>(handed);
            senderTask = fromTask;
            page = new WeakReference<>(null);
            pageClosed = false;
            awayAt = -1;
            armedAt = SystemClock.uptimeMillis();
        } catch (Throwable error) {
            Logger.printException(() -> "Could not watch the page a link opened", error);
        }
    }

    static boolean armed() {
        return armedAt >= 0;
    }

    private static void resumed(Activity activity) {
        if (activity == null || activity == handedOver.get()) return;
        front = new WeakReference<>(activity);
        if (!armed()) return;
        boolean main = activity.getClass() == mainClass;
        if (awayAt >= 0) {
            long away = SystemClock.uptimeMillis() - awayAt;
            awayAt = -1;
            // Back on the feed (TikTok's icon can clear the page) or after a long while: as usual from here.
            if (main || away > AWAY_MS) {
                disarm();
                return;
            }
        }
        if (pageClosed) {
            if (main) {
                leave(activity);
            } else {
                // The page that closed started this one in its place, the way a loading page does.
                page = new WeakReference<>(activity);
                pageClosed = false;
            }
            return;
        }
        if (page.get() != null) return;
        if (SystemClock.uptimeMillis() - armedAt > PAGE_WINDOW_MS) {
            disarm();
            return;
        }
        // The main screen resumes first when the link reaches a running TikTok.
        if (!main) page = new WeakReference<>(activity);
    }

    private static void paused(Activity activity) {
        if (!armed() || activity == null || activity != page.get() || !activity.isFinishing()) return;
        // A page that starts another before it finishes has that one in the task already, so
        // only a page alone over the main screen is the link being closed.
        Integer size = taskSize(activity);
        if (size != null && size <= 2) {
            leave(activity);
        } else {
            pageClosed = true;
        }
    }

    private static void stopped(Activity activity) {
        if (!armed() || activity == null || activity.isFinishing()) return;
        // Stopped with nothing of TikTok's resumed after it: the app went out of sight.
        if (front.get() == activity) awayAt = SystemClock.uptimeMillis();
    }

    private static void leave(Activity activity) {
        int sender = senderTask;
        disarm();
        try {
            // First, while TikTok is still in front: the system refuses a task move from an app
            // in the background. A task id that's gone by now is a quiet no-op, and TikTok
            // moving back then shows what it did before.
            if (sender >= 0 && !screenPinned(activity)) senderToFront(activity, sender);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not bring back the app a link came from", error);
        }
        try {
            if (activity.moveTaskToBack(true)) {
                Logger.printInfo(() -> "A link's page closed; back to the app the link came from");
            } else {
                Logger.printInfo(() -> "TikTok's task stayed in front after a link's page closed");
            }
        } catch (Throwable error) {
            Logger.printException(() -> "Could not go back to the app a link came from", error);
        }
    }

    /** TikTok declares REORDER_TASKS, which moveTaskToFront needs. */
    private static void senderToFront(Activity activity, int taskId) {
        TaskFront forTests = taskFrontForTests;
        if (forTests != null) {
            forTests.move(activity, taskId);
            return;
        }
        ActivityManager manager = activity.getSystemService(ActivityManager.class);
        if (manager != null) manager.moveTaskToFront(taskId, 0);
    }

    /** A pinned screen refuses the move with a system message of its own, so don't ask. */
    private static boolean screenPinned(Activity activity) {
        ActivityManager manager = activity.getSystemService(ActivityManager.class);
        return manager != null
                && manager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    private static void disarm() {
        armedAt = -1;
        awayAt = -1;
        senderTask = -1;
        handedOver = new WeakReference<>(null);
        page = new WeakReference<>(null);
        pageClosed = false;
    }

    private static Integer taskSize(Activity activity) {
        TaskSize forTests = taskSizeForTests;
        if (forTests != null) return forTests.of(activity);
        try {
            ActivityManager manager = activity.getSystemService(ActivityManager.class);
            if (manager == null) return null;
            int mine = activity.getTaskId();
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info != null && info.id == mine) return info.numActivities;
            }
        } catch (Throwable error) {
            Logger.printInfo(() -> "The link's task isn't readable: " + error);
        }
        return null;
    }

    static void resetForTests() {
        Application application = registeredOn.get();
        if (application != null) application.unregisterActivityLifecycleCallbacks(CALLBACKS);
        registeredOn = new WeakReference<>(null);
        mainClass = null;
        front = new WeakReference<>(null);
        taskSizeForTests = null;
        taskFrontForTests = null;
        disarm();
    }
}

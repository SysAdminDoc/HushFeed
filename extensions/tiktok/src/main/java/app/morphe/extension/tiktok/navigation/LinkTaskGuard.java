/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.net.Uri;

import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;

/**
 * A TikTok link tapped in an app that starts it without FLAG_ACTIVITY_NEW_TASK (Telegram does)
 * opened TikTok inside that app's task: one Recents card for both, Back landing in the app, a
 * swipe closing both (#227). Chrome and launchers add the flag, so their links already got a
 * card of their own.
 *
 * <p>TikTok turns its AppLinkHandlerV2 (singleTask, excluded from Recents) off at run time and
 * its link alias of MainActivity on, so links land in MainActivity with the default affinity and
 * nothing to stop it joining the sender's task. The hook runs at the start of
 * {@code MainActivity.onCreate} (the alias target), turns the instance already made into a plain
 * launch and finishes it, so TikTok doesn't also open the link in it, then starts the same intent
 * again with the flag, which finds TikTok's own task or makes one. {@link LinkReturn} then sends
 * Back from the link's page to the app it came from, the way it went before the guard.
 *
 * <p>{@code onNewIntent} needs no guard. A foreign task can only get a new intent if a
 * MainActivity is already on top of it, and this keeps one from ever being created there.
 */
public final class LinkTaskGuard {
    private LinkTaskGuard() {
    }

    /** From the patched {@code MainActivity.onCreate}, before the host's own onCreate runs. */
    public static void onMainActivityCreate(Activity activity) {
        try {
            redirectIfForeign(activity, activity.isTaskRoot(), ownsTask(activity));
        } catch (Throwable error) {
            Logger.printException(() -> "Could not check the task of a TikTok link", error);
        }
    }

    /** Returns whether the link was handed to a task of TikTok's own. */
    static boolean redirectIfForeign(Activity activity, boolean taskRoot, Boolean ownsTask) {
        try {
            Intent intent = activity.getIntent();
            if (intent == null) return false;
            Uri data = intent.getData();
            Uri referrer = activity.getReferrer();
            String referrerPackage = referrer == null ? null : referrer.getHost();
            if (!shouldRelaunch(Setting.isPaused(), intent.getAction(),
                    data == null ? null : data.getScheme(), data == null ? null : data.getHost(),
                    intent.getFlags(), taskRoot, ownsTask, referrerPackage,
                    activity.getPackageName())) {
                return false;
            }
            Intent relaunch = new Intent(intent);
            // Started from TikTok's own process onto a page other than the main screen, the link
            // alias got a new MainActivity that TikTok never finished after opening the link: a
            // whole For You feed under every profile, memory growing with each link and Back
            // landing on one feed after another (#276, 47.5.3). CLEAR_TOP with SINGLE_TOP hands
            // the link to the MainActivity already at the bottom of TikTok's task as onNewIntent
            // and drops the pages above it, the way the first link after a cold start goes.
            relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            // The sender's task, for LinkReturn to bring back. Read while this instance is still
            // alive in it: a finished one can lose its task before the read.
            int senderTask = activity.getTaskId();
            // This instance still runs the rest of onCreate. Give it nothing to open.
            activity.setIntent(new Intent(Intent.ACTION_MAIN).setPackage(activity.getPackageName()));
            // Finish first. The link alias is singleTop, so while this instance is still the
            // running top of the focused task the system hands the relaunch straight back to it
            // as onNewIntent (START_DELIVERED_TO_TOP), NEW_TASK or not, and TikTok opens the link
            // right here. A finishing source also makes the start take a task of TikTok's own.
            activity.finish();
            activity.overridePendingTransition(0, 0);
            activity.startActivity(relaunch);
            LinkReturn.arm(activity, senderTask);
            Logger.printInfo(() -> "A TikTok link joined another app's task; started it in TikTok's own");
            return true;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not move a TikTok link to its own task", error);
            return false;
        }
    }

    /**
     * True or false when the app's own task list says whether this activity's task is TikTok's.
     * getAppTasks lists only the calling app's tasks. Null when it can't say.
     */
    private static Boolean ownsTask(Activity activity) {
        try {
            ActivityManager manager = activity.getSystemService(ActivityManager.class);
            if (manager == null) return null;
            List<ActivityManager.AppTask> tasks = manager.getAppTasks();
            if (tasks == null || tasks.isEmpty()) return null;
            int mine = activity.getTaskId();
            for (ActivityManager.AppTask task : tasks) {
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info != null && info.id == mine) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (Throwable error) {
            Logger.printInfo(() -> "The app's task list isn't readable: " + error);
            return null;
        }
    }

    /**
     * The decision. {@code ownsTask} is null when the task list couldn't say, and then the
     * package that started the activity decides.
     */
    static boolean shouldRelaunch(boolean paused, String action, String scheme, String host,
            int flags, boolean taskRoot, Boolean ownsTask, String referrerPackage,
            String ownPackage) {
        if (paused || taskRoot) return false;
        if (!Intent.ACTION_VIEW.equals(action)) return false;
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return false;
        if (!isTikTokHost(host)) return false;
        if ((flags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) return false;
        if (ownsTask != null) return !ownsTask;
        return referrerPackage != null && !referrerPackage.equals(ownPackage);
    }

    static boolean isTikTokHost(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("tiktok.com") || lower.endsWith(".tiktok.com");
    }
}

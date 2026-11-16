/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Says a save landed, with a way to it. The two-second toast carried a folder path and offered
 * nothing, so the reader went hunting through the gallery; a completed save now shows the same
 * words on the action banner, up for six seconds, whose Open hands the saved row to a viewer.
 * The toast stays for a save without a row to open: below API 29, and wherever no screen is up
 * to draw the banner on, which the banner path itself falls back from.
 */
final class SaveNotice {
    private SaveNotice() {
    }

    /** Announces a completed save of {@code saved}, or by toast alone when it has no row. */
    static void saved(String message, MediaFileWriter.Saved saved) {
        saved(message, saved, null);
    }

    /**
     * As above, with Retry beside Open for a save whose failed files can be tried again. A save
     * without a row to open still gets the banner then, with Retry alone, since a toast can't
     * carry it.
     */
    static void saved(String message, MediaFileWriter.Saved saved, Runnable retry) {
        // The banner says it without a toast, so the Downloads page hears it from here.
        MediaJobScheduler.said(message);
        Uri uri = saved == null ? null : saved.uri;
        if (uri == null && retry == null) {
            Utils.showToastShort(message);
            return;
        }
        // Not at once: a download finishing is exactly when TikTok swaps its share sheet for a
        // fresh one, and a banner put on whichever window was top at that instant landed on the
        // dying sheet, under the new one (found by the view walk, invisible on the S22, top and
        // bottom placement alike). The swap settles well inside this wait.
        Utils.runOnMainThreadDelayed(() -> announce(message, uri, retry), SHEET_SETTLE_MS);
    }

    /**
     * Announces a saved video or story as {@link #saved(String, MediaFileWriter.Saved)} does, or,
     * with Share the video after a save on, opens Android's share sheet on the file in place of
     * the banner, so it can go straight to a chat app (#423). The words go up as a toast then. A
     * save that lands while TikTok is in the background, where Android opens no share sheet, gets
     * the banner as before.
     */
    static void savedVideo(String message, MediaFileWriter.Saved saved) {
        if (saved == null || (saved.uri == null && saved.file == null) || !Settings.SHARE_AFTER_SAVE.get()) {
            saved(message, saved);
            return;
        }
        MediaJobScheduler.said(message);
        Utils.runOnMainThreadDelayed(() -> {
            if (!share(Utils.getActivity(), saved, message)) announce(message, saved.uri, null);
        }, SHEET_SETTLE_MS);
    }

    /** The banner on the top window, or the toast when there's no row, no Retry or no screen. */
    private static void announce(String message, Uri uri, Runnable retry) {
        Activity activity = Utils.getActivity();
        if ((uri == null && retry == null)
                || activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Utils.showToastShort(message);
            return;
        }
        // TikTok brings its share sheet back over the feed when a download finishes, and the
        // sheet is a window of its own: a banner on the activity stood underneath it, where
        // the old toast would have floated above. The banner goes on the top window instead,
        // which is the sheet while it is up and the activity's own decor otherwise.
        ViewGroup root = topWindowRoot(activity);
        if (root == null) root = activity.findViewById(android.R.id.content);
        // A quick save lands inside the two seconds of its own "Saving" toast, and the banner
        // drew over the toast's text (#299). It says the newer thing, so the toasts go: the one
        // up and any queued behind it, which would come up over the banner next.
        Utils.cancelToasts();
        if (retry == null) {
            BlockAuthorOverlay.showActionBanner(root, message, L10n.t("Open"), () -> open(uri));
        } else if (uri == null) {
            BlockAuthorOverlay.showActionBanner(root, message, L10n.t("Retry"), retry);
        } else {
            BlockAuthorOverlay.showActionBanner(root, message, L10n.t("Open"), () -> open(uri),
                    L10n.t("Retry"), retry);
        }
    }

    /** For the tests, where the process has no real importance; null asks Android. */
    static volatile Boolean foregroundForTests;

    /** Whether one of TikTok's screens shows, which Android needs before it opens a share sheet. */
    private static boolean inForeground() {
        Boolean forTests = foregroundForTests;
        if (forTests != null) return forTests;
        ActivityManager.RunningAppProcessInfo state = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(state);
        return state.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    /**
     * Opens Android's share sheet on the saved file, or answers false so the banner says it. Below
     * Android 10 the file has no row yet, so the scanner gives it one first.
     */
    private static boolean share(Activity activity, MediaFileWriter.Saved saved, String message) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !inForeground()) {
            return false;
        }
        if (saved.uri != null) return share(activity, saved.uri, message);
        android.media.MediaScannerConnection.scanFile(activity.getApplicationContext(),
                new String[]{saved.file.getAbsolutePath()}, null,
                (path, uri) -> Utils.runOnMainThread(() -> {
                    if (uri == null || !share(activity, uri, message)) announce(message, uri, null);
                }));
        return true;
    }

    private static boolean share(Activity activity, Uri uri, String message) {
        try {
            String type = activity.getContentResolver().getType(uri);
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType(type != null ? type : "video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // The share sheet's preview and the app picked read the file through this grant.
            send.setClipData(ClipData.newRawUri(null, uri));
            activity.startActivity(Intent.createChooser(send, null));
            // The share sheet takes the banner's place, so its words go up as a toast.
            Utils.cancelToasts();
            Utils.showToastShort(message);
            return true;
        } catch (ActivityNotFoundException | SecurityException noSheet) {
            Logger.printDebug(() -> "No share sheet for the saved file: " + noSheet);
            return false;
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not share the saved file", failure);
            return false;
        }
    }

    /** How long TikTok gets to finish swapping its share sheet before the banner picks a window. */
    static final long SHEET_SETTLE_MS = 800;

    /** For the tests, which have no WindowManagerGlobal to read; null uses the real windows. */
    static volatile List<View> windowRootsForTests;

    /**
     * The topmost attached window root, the way the verification probe reads them, or null when
     * the host keeps its windows somewhere this Android does not expose.
     */
    static ViewGroup topWindowRoot(Activity activity) {
        try {
            List<View> roots = windowRootsForTests;
            if (roots == null) {
                Class<?> globalClass = Class.forName("android.view.WindowManagerGlobal");
                java.lang.reflect.Method getInstance = globalClass.getDeclaredMethod("getInstance");
                getInstance.setAccessible(true);
                Object global = getInstance.invoke(null);
                Object value;
                try {
                    java.lang.reflect.Method getWindowViews = globalClass.getDeclaredMethod("getWindowViews");
                    getWindowViews.setAccessible(true);
                    value = getWindowViews.invoke(global);
                } catch (NoSuchMethodException missingMethod) {
                    java.lang.reflect.Field views = globalClass.getDeclaredField("mViews");
                    views.setAccessible(true);
                    value = views.get(global);
                }
                if (!(value instanceof List)) return null;
                @SuppressWarnings("unchecked")
                List<View> read = (List<View>) value;
                // mViews is the live list, and windows are added and removed from other
                // threads; a copy keeps the walk from tripping over a change mid-iteration.
                roots = new java.util.ArrayList<>(read);
            }
            for (int index = roots.size() - 1; index >= 0; index--) {
                View root = roots.get(index);
                if (!(root instanceof ViewGroup) || !root.isShown() || root.getWindowToken() == null) {
                    continue;
                }
                // Not a toast or another system-range window: a banner on one goes down with it
                // in a couple of seconds, with Open never reachable.
                android.view.ViewGroup.LayoutParams params = root.getLayoutParams();
                if (params instanceof android.view.WindowManager.LayoutParams
                        && ((android.view.WindowManager.LayoutParams) params).type
                                >= android.view.WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW) {
                    continue;
                }
                return (ViewGroup) root;
            }
            return null;
        } catch (Throwable unreadable) {
            Logger.printDebug(() -> "The window list is not readable here");
            return null;
        }
    }

    static void open(MediaFileWriter.Saved saved) {
        if (saved.uri != null) {
            open(saved.uri);
        } else if (saved.file != null) {
            // Give the viewer a media URI even on old Android, never a file:// URI.
            android.media.MediaScannerConnection.scanFile(Utils.getContext(),
                    new String[]{saved.file.getAbsolutePath()}, new String[]{"video/mp4"},
                    (path, uri) -> Utils.runOnMainThread(() -> {
                        if (uri == null) Utils.showToastShort(L10n.t("No app on this phone opens that file"));
                        else open(uri);
                    }));
        } else {
            Utils.showToastShort(L10n.t("No app on this phone opens that file"));
        }
    }

    private static void open(Uri uri) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, uri);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Activity activity = Utils.getActivity();
            if (activity != null) {
                activity.startActivity(view);
                return;
            }
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Utils.getContext().startActivity(view);
        } catch (ActivityNotFoundException | SecurityException nothingOpensIt) {
            // SecurityException is the row having gone away inside the banner's six seconds:
            // to the reader, the same thing as nothing opening it.
            Utils.showToastShort(L10n.t("No app on this phone opens that file"));
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not open the saved file", failure);
            Utils.showToastShort(L10n.t("No app on this phone opens that file"));
        }
    }
}

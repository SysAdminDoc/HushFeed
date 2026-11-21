/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.download.SongRecognition;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The "Identify the song with Shazam" gesture: the video's sound goes to {@link SongRecognition}
 * on a worker, and the song it names comes back as a small sheet with Copy and YouTube Music.
 * One lookup at a time; a second gesture while one runs says so instead of starting another.
 */
public final class SongLookup {
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private SongLookup() {
    }

    public static void start(Object aweme, Activity activity) {
        Context context = activity != null ? activity : Utils.getContext();
        if (context == null) return;
        if (!SongRecognition.hasSound(aweme)) {
            Utils.showToastShort(L10n.t("This video has no sound to identify"));
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            Utils.showToastShort(L10n.t("Still looking up the song"));
            return;
        }
        Context app = context.getApplicationContext();
        WeakReference<Activity> screen = new WeakReference<>(activity);
        Utils.showToastShort(L10n.t("Looking up the song"));
        boolean queued = Utils.runOnBackgroundThread(() -> {
            SongRecognition.Result result;
            try {
                result = SongRecognition.identify(app, aweme);
            } finally {
                BUSY.set(false);
            }
            Utils.runOnMainThread(() -> answer(screen.get(), result));
        });
        if (!queued) {
            BUSY.set(false);
            Utils.showToastShort(L10n.t("The song couldn't be looked up. Try again."));
        }
    }

    /** What the reader sees once the lookup is over. */
    static void answer(Activity activity, SongRecognition.Result result) {
        switch (result.outcome) {
            case FOUND:
                show(activity, result.title, result.artist);
                return;
            case NOT_FOUND:
                Utils.showToastLong(L10n.t("Shazam didn't recognize this song"));
                return;
            case NO_SOUND:
                Utils.showToastShort(L10n.t("This video has no sound to identify"));
                return;
            case QUIET:
                Utils.showToastShort(L10n.t("This sound is too quiet to identify"));
                return;
            case LIMITED:
                Utils.showToastLong(L10n.t("Shazam is busy. Try again later."));
                return;
            default:
                Utils.showToastLong(L10n.t("The song couldn't be looked up. Try again."));
        }
    }

    /** "Title by Artist", or the title alone for a match with no artist. */
    static String songText(String title, String artist) {
        return artist == null ? title : L10n.f("%1$s by %2$s", title, artist);
    }

    private static void show(Activity activity, String title, String artist) {
        String text = songText(title, artist);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            // The screen went away while Shazam answered, so the match is said instead of shown.
            Utils.showToastLong(text);
            return;
        }
        try {
            SettingsUi.syncDarkMode(activity);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(artist == null ? "" : artist)
                    .setPositiveButton(L10n.t("Close"), null)
                    .setNeutralButton(L10n.t("Copy"), (ignored, which) -> {
                        if (GestureActions.copyToClipboard(title, text)) {
                            Utils.showToastShort(L10n.t("Song copied"));
                        }
                    })
                    .setNegativeButton(L10n.t("YouTube Music"), (ignored, which) ->
                            YouTubeMusicSearch.openQuery(artist == null ? title : title + " " + artist, activity))
                    .create();
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            dialog.show();
        } catch (Throwable error) {
            Logger.printException(() -> "Could not show the song", error);
            Utils.showToastLong(text);
        }
    }

    static void resetForTests() {
        BUSY.set(false);
    }
}

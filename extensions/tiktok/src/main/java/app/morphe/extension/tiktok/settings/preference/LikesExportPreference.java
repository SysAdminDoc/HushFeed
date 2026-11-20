/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.view.View;

import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.download.ExportSave;
import app.morphe.extension.tiktok.download.LikesExport;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Save your likes from a TikTok data export: picks the export (its JSON file or the zip it comes
 * in), says how many liked and favorited videos it lists, and saves the lists ticked as one run
 * of picked videos ({@link ExportSave}).
 *
 * <p>The picker answers the settings page and the file is read on a {@link DocumentOperation}
 * worker, as the font import does, so a file app that keeps it waiting can't hold the row.
 */
@SuppressWarnings("deprecation")
public final class LikesExportPreference extends Preference {
    static final int REQUEST_PICK = 7351;
    static final String KEY = "action_save_likes_from_export";

    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final CopyOnWriteArrayList<WeakReference<LikesExportPreference>> ROWS =
            new CopyOnWriteArrayList<>();
    /** The picker is open. Main thread only. */
    private static boolean picking;
    /** The read the row waits on. Main thread only. */
    private static DocumentOperation reading;

    public LikesExportPreference(Context context) {
        super(context);
        setKey(KEY);
        setPersistent(false);
        setTitle(L10n.t(context, "Save your likes from a TikTok data export"));
        ROWS.add(new WeakReference<>(this));
        refreshState();
        setOnPreferenceClickListener(preference -> {
            if (offersStop()) stopWaiting(getContext());
            else if (!BUSY.get() && !picking && !heldAfterStop()) TikTokPreferenceFragment.openLikesExportPicker();
            return true;
        });
    }

    static void pickFile(TikTokPreferenceFragment fragment) {
        if (BUSY.get() || picking) return;
        // The export comes as a zip holding a .json file, and file apps type both several ways,
        // so every file is offered and the read checks what it is.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .setType("*/*");
        picking = true;
        refreshRows();
        try {
            fragment.startActivityForResult(intent, REQUEST_PICK);
        } catch (RuntimeException failure) {
            picking = false;
            refreshRows();
            Logger.printException(() -> "Could not open the data export picker", failure);
            SettingsActionBanner.showNotice(fragment.getActivity(), L10n.t(
                    "This phone has no file picker, so there's no way to choose a file here"));
        }
    }

    static boolean onResult(TikTokPreferenceFragment fragment, int request, int result, Intent data) {
        if (request != REQUEST_PICK) return false;
        picking = false;
        Activity activity = fragment.getActivity();
        if (result != Activity.RESULT_OK || data == null || data.getData() == null
                || activity == null || !BUSY.compareAndSet(false, true)) {
            refreshRows();
            return true;
        }
        Context context = activity.getApplicationContext();
        WeakReference<Activity> window = new WeakReference<>(activity);
        Uri uri = data.getData();
        refreshRows();
        SettingsActionBanner.showNotice(activity, L10n.t(context, "Reading the data export"));
        reading = DocumentOperation.start(DocumentOperation.Kind.LIKES_EXPORT_FILE, file -> {
            try {
                LikesExport.Videos videos = LikesExport.read(file.openForRead(context.getContentResolver(), uri));
                // Stopped while the file app had it: the stop gave the row back and said so.
                if (!file.commit()) return;
                Utils.runOnMainThread(() -> {
                    finish();
                    choose(TikTokPreferenceFragment.reportWindow(window, context), videos);
                });
            } catch (Exception failure) {
                if (file.isStopped()) return;
                if (failure instanceof LikesExport.Rejected) {
                    Logger.printInfo(() -> "Data export refused: " + failure.getMessage());
                } else {
                    Logger.printException(() -> "Could not read a data export", failure);
                }
                Utils.runOnMainThread(() -> {
                    finish();
                    SettingsActionBanner.showNotice(TikTokPreferenceFragment.reportWindow(window, context),
                            failureMessage(context, failure));
                });
            }
        }, LikesExportPreference::refreshRows);
        if (reading == null) {
            boolean held = DocumentOperation.busy(DocumentOperation.Kind.LIKES_EXPORT_FILE);
            finish();
            SettingsActionBanner.showNotice(activity, held
                    ? L10n.t(context, "The file app still has the last file. Try again once it lets go, or restart TikTok.")
                    : L10n.t(context, "Couldn't start the import. Try again in a moment."));
        }
        return true;
    }

    /** The lists the export holds, ticked, and Save. */
    static void choose(Context window, LikesExport.Videos videos) {
        List<CharSequence> labels = new ArrayList<>();
        List<List<String>> lists = new ArrayList<>();
        NumberFormat count = NumberFormat.getIntegerInstance();
        if (!videos.liked.isEmpty()) {
            labels.add(L10n.f(window, "Liked videos (%1$s)", count.format(videos.liked.size())));
            lists.add(videos.liked);
        }
        if (!videos.favorites.isEmpty()) {
            labels.add(L10n.f(window, "Favorites (%1$s)", count.format(videos.favorites.size())));
            lists.add(videos.favorites);
        }
        if (lists.isEmpty()) {
            SettingsActionBanner.showNotice(window, L10n.t(window,
                    "That export lists no liked or favorited videos"));
            return;
        }
        boolean[] checked = new boolean[lists.size()];
        java.util.Arrays.fill(checked, true);
        AlertDialog dialog = new AlertDialog.Builder(window)
                .setTitle(L10n.t(window, "Save your likes from a TikTok data export"))
                .setMultiChoiceItems(labels.toArray(new CharSequence[0]), checked,
                        (ignored, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(L10n.t(window, "Save"), (ignored, which) -> {
                    java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < lists.size(); i++) if (checked[i]) ids.addAll(lists.get(i));
                    if (!ids.isEmpty()) plan(window, new ArrayList<>(ids));
                })
                .setNegativeButton(L10n.t(window, "Cancel"), null)
                .show();
        SettingsUi.styleStandardAlertDialog(dialog);
    }

    /** Leaves out what was saved before, off the main thread, then starts the run. */
    private static void plan(Context window, List<String> ids) {
        Context app = window.getApplicationContext() != null ? window.getApplicationContext() : window;
        WeakReference<Context> shown = new WeakReference<>(window);
        boolean queued = Utils.runOnBackgroundThread(() -> {
            ExportSave.Plan plan = ExportSave.plan(app, ids);
            Utils.runOnMainThread(() -> {
                Context where = shown.get() != null ? shown.get() : app;
                SettingsActionBanner.showNotice(where, started(where, plan, ExportSave.start(where, plan)));
            });
        });
        if (!queued) SettingsActionBanner.showNotice(window, L10n.t(window, "Couldn't start saving. Try again in a moment."));
    }

    /** What the reader is told as the run is queued, or why it wasn't. */
    static String started(Context context, ExportSave.Plan plan, boolean queued) {
        NumberFormat count = NumberFormat.getIntegerInstance();
        if (plan.ids.isEmpty()) {
            return L10n.t(context, "You've already saved every one of these videos here");
        }
        if (!queued) return L10n.t(context, "Couldn't start saving. Try again in a moment.");
        StringBuilder said = new StringBuilder(L10n.quantity(context, plan.ids.size(),
                "Saving one video", "Saving %1$s videos", count.format(plan.ids.size())));
        if (plan.alreadySaved > 0) {
            said.append('\n').append(L10n.quantity(context, plan.alreadySaved,
                    "One you saved before is left out", "%1$s you saved before are left out",
                    count.format(plan.alreadySaved)));
        }
        if (plan.later > 0) {
            said.append('\n').append(L10n.f(context,
                    "Choose the file again after this run to save the other %1$s",
                    count.format(plan.later)));
        }
        return said.toString();
    }

    static String failureMessage(Context context, Exception failure) {
        if (!(failure instanceof LikesExport.Rejected)) {
            return L10n.t(context, "Couldn't read that file. Choose it again.");
        }
        switch (((LikesExport.Rejected) failure).reason) {
            case TOO_LARGE:
                return L10n.f(context, "That file is larger than %1$s MB. Choose the JSON file from the export.",
                        NumberFormat.getIntegerInstance().format(LikesExport.MAX_BYTES / (1024 * 1024)));
            case UNSUPPORTED:
            case DAMAGED:
                return L10n.t(context,
                        "That isn't a TikTok data export in JSON format. Choose user_data_tiktok.json or the zip it came in.");
            default:
                return L10n.t(context, "Couldn't read that file. Choose it again.");
        }
    }

    private static boolean offersStop() {
        DocumentOperation acting = reading;
        return acting != null && acting.offersStop();
    }

    private static boolean heldAfterStop() {
        return DocumentOperation.heldAfterStop(DocumentOperation.Kind.LIKES_EXPORT_FILE);
    }

    /** Stops waiting on a file app that has kept the read waiting. */
    private static void stopWaiting(Context window) {
        DocumentOperation acting = reading;
        if (acting == null || acting.stop() != DocumentOperation.Stage.STOPPED) {
            refreshRows();
            return;
        }
        finish();
        SettingsActionBanner.showNotice(window, L10n.t(window,
                "Stopped waiting for the file app. Nothing was imported."));
    }

    private static void finish() {
        BUSY.set(false);
        reading = null;
        refreshRows();
    }

    private static void refreshRows() {
        for (WeakReference<LikesExportPreference> held : ROWS) {
            LikesExportPreference row = held.get();
            if (row == null) ROWS.remove(held);
            else row.refreshState();
        }
    }

    private void refreshState() {
        Context context = getContext();
        boolean stoppable = offersStop();
        boolean held = !BUSY.get() && heldAfterStop();
        setEnabled(stoppable || !BUSY.get() && !picking && !held);
        setSummary(stoppable ? L10n.t(context, "Still waiting for the file app. Tap to stop waiting.")
                : BUSY.get() ? L10n.t(context, "Reading the data export")
                : held ? L10n.t(context,
                        "Waiting for the file app to let go of the last file. Restart TikTok if it doesn't.")
                : L10n.f(context, "Pick the export TikTok sends from Settings and privacy > Account > "
                        + "Download your data, in JSON format. The videos it lists as liked or "
                        + "favorited are saved like picked videos, up to %1$s at a time.",
                        NumberFormat.getIntegerInstance().format(ExportSave.MAX_RUN)));
    }

    @Override protected void onBindView(View view) {
        refreshState();
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }
}

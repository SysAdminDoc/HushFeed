/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.widget.Button;
import android.widget.ListView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Save episodes (#444): a checklist of a short drama's episodes, opened from the share sheet of
 * any episode, that saves the picked ones as one {@link BatchSave} run with one progress row and
 * one banner. The episode on screen starts checked. A run takes at most
 * {@link GridPicker#MAX_PICKED}; with more checked, Save starts that many and leaves the rest
 * checked for the next tap.
 */
public final class EpisodePicker {
    static final String DIALOG_KEY = "episode picker";
    private static final AtomicBoolean LOADING = new AtomicBoolean();

    /** Loads a drama's episodes. The tests answer with their own. */
    interface Loader {
        List<DramaEpisodes.Episode> load(long collectionId) throws Exception;
    }

    /** Hands a run to the media queue. The tests keep what they're given. */
    interface Starter {
        boolean start(Context context, List<Object> posts);
    }

    interface Worker {
        boolean run(Runnable task);
    }

    static volatile Loader loader = DramaEpisodes::load;
    static volatile Starter starter = BatchSave::start;
    static volatile Worker worker = Utils::runOnBackgroundThread;

    private EpisodePicker() {
    }

    /** Whether Advanced downloads is patched in, its Save episodes switch is on, and Hushfeed isn't paused. */
    public static boolean enabled() {
        return SettingsStatus.advancedDownloadsEnabled && Settings.SAVE_EPISODES_BUTTON.get()
                && !HushfeedPause.isPaused();
    }

    /** Whether the post is an episode of a short drama or another Series TikTok lists episodes for. */
    public static boolean isEpisode(Object post) {
        return post != null && DramaEpisodes.collectionId(post) > 0;
    }

    /**
     * Loads the drama's episodes off the main thread, then shows the checklist over whichever
     * activity is up by then. A second tap while a list is loading does nothing more.
     */
    public static void open(Context context, Object post) {
        long collection = post == null ? 0L : DramaEpisodes.collectionId(post);
        if (collection <= 0 || !LOADING.compareAndSet(false, true)) return;
        Context from = context;
        String current = Reflect.string(post, "getAid", "aid");
        String name = DramaEpisodes.name(post);
        Utils.showToastShort(L10n.t("Loading episodes"));
        Loader load = loader;
        boolean started = worker.run(() -> {
            List<DramaEpisodes.Episode> episodes = null;
            try {
                episodes = load.load(collection);
            } catch (Exception | LinkageError failure) {
                Logger.printException(() -> "Could not load the drama's episodes", failure);
            }
            List<DramaEpisodes.Episode> loaded = episodes;
            Utils.runOnMainThreadNowOrLater(() -> {
                LOADING.set(false);
                shown(from, name, loaded, current);
            });
        });
        if (!started) LOADING.set(false);
    }

    private static void shown(Context context, String name, List<DramaEpisodes.Episode> episodes, String current) {
        if (episodes == null || episodes.isEmpty()) {
            Utils.showToastLong(L10n.t("Couldn't load the episodes. Try again."));
            return;
        }
        Activity activity = context instanceof Activity ? (Activity) context : null;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) activity = Utils.getVisibleActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (SettingsUi.dialogOpen(DIALOG_KEY, activity)) return;
        try {
            show(activity, name, episodes, current);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not open the episode picker", failure);
        }
    }

    /** Each row: the episode's number, then whether TikTok sent only a preview or it's on screen. */
    static String[] labels(Context context, List<DramaEpisodes.Episode> episodes, String current) {
        String[] labels = new String[episodes.size()];
        for (int i = 0; i < labels.length; i++) {
            DramaEpisodes.Episode episode = episodes.get(i);
            StringBuilder label = new StringBuilder(L10n.f(context, "Episode %1$s", String.valueOf(episode.number)));
            if (episode.preview) label.append(" · ").append(L10n.t(context, "Preview only"));
            if (episode.id.equals(current)) label.append(" · ").append(L10n.t(context, "On screen"));
            labels[i] = label.toString();
        }
        return labels;
    }

    /** The checklist's title, with the drama's name under it when TikTok gave one. */
    static String title(Context context, String name) {
        String title = L10n.t(context, "Pick episodes to save");
        return name == null ? title : title + "\n" + name;
    }

    /** The checked episodes' posts, in episode order. */
    static List<Object> picked(List<DramaEpisodes.Episode> episodes, boolean[] checked) {
        List<Object> picked = new ArrayList<>();
        for (int i = 0; i < checked.length && i < episodes.size(); i++) {
            if (checked[i]) picked.add(episodes.get(i).post);
        }
        return picked;
    }

    /**
     * Saves the checked episodes, at most one run's worth. The ones that went into the run are
     * unchecked, so what's still checked is what the next Save takes; true when nothing is left.
     */
    static boolean save(Context context, List<DramaEpisodes.Episode> episodes, boolean[] checked, ListView list) {
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < checked.length && i < episodes.size(); i++) if (checked[i]) rows.add(i);
        List<Integer> run = rows.subList(0, Math.min(rows.size(), GridPicker.MAX_PICKED));
        List<Object> posts = new ArrayList<>();
        for (int row : run) posts.add(episodes.get(row).post);
        if (!starter.start(context, posts)) return false;
        for (int row : run) {
            checked[row] = false;
            if (list != null) list.setItemChecked(row, false);
        }
        if (rows.size() <= run.size()) return true;
        Utils.showToastLong(L10n.f(context, "Saving %1$s. Tap Save again for the rest.", String.valueOf(run.size())));
        return false;
    }

    private static void show(Activity activity, String name, List<DramaEpisodes.Episode> episodes, String current) {
        List<DramaEpisodes.Episode> rows = Collections.unmodifiableList(new ArrayList<>(episodes));
        boolean[] checked = new boolean[rows.size()];
        for (int i = 0; i < checked.length; i++) checked[i] = rows.get(i).id.equals(current);
        SettingsUi.syncDarkMode(activity);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title(activity, name))
                .setMultiChoiceItems(labels(activity, rows, current), checked,
                        (ignored, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(L10n.t(activity, "Save"), null)
                .setNegativeButton(L10n.t(activity, "Cancel"), null)
                .setNeutralButton(L10n.t(activity, "Pick all"), null)
                .create();
        dialog.setOnShowListener(ignored -> {
            SettingsUi.styleStandardAlertDialog(dialog);
            // Both stay open on a tap: Pick all leaves the picks to look over, and Save with
            // nothing checked has nothing to do yet.
            Button all = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            if (all != null) all.setOnClickListener(view -> {
                ListView list = dialog.getListView();
                for (int i = 0; i < checked.length; i++) {
                    checked[i] = true;
                    if (list != null) list.setItemChecked(i, true);
                }
            });
            Button save = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (save != null) save.setOnClickListener(view -> {
                if (picked(rows, checked).isEmpty()) {
                    Utils.showToastShort(L10n.t(activity, "Pick at least one episode"));
                    return;
                }
                if (save(activity, rows, checked, dialog.getListView())) dialog.dismiss();
            });
        });
        SettingsUi.dialogOpened(DIALOG_KEY, activity, dialog);
        dialog.show();
    }

    /** So a test starts with nothing loading. */
    static void resetForTests() {
        LOADING.set(false);
        loader = DramaEpisodes::load;
        starter = BatchSave::start;
        worker = Utils::runOnBackgroundThread;
    }
}

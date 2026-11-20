/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Saves the videos a TikTok data export lists ({@link LikesExport}) as one queued run, the way
 * picked videos save ({@link BatchSave}): a count with Cancel, each post saved as a single
 * download saves it, and one banner at the end.
 *
 * <p>The export has ids, not posts, so each post is asked for first ({@link DramaEpisodes#post}),
 * at most one ask every {@link #ASK_GAP_MS}. A post TikTok no longer has (deleted or made private)
 * is skipped and counted on its own line. With Check for already-saved videos on, videos saved
 * here before are left out before the run is cut to {@link #MAX_RUN}, so choosing the same file
 * again picks up after the last run.
 */
public final class ExportSave {
    /** A run's most: what one record of unfinished saves can name. */
    public static final int MAX_RUN = 1000;
    /** The gap between two asks for a post, so a long run stays at a person's pace. */
    static final long ASK_GAP_MS = 1500;

    /** Asks TikTok for one post. The tests answer with stand-ins. */
    interface Fetcher {
        Object post(String id) throws Exception;
    }

    static volatile Fetcher fetcherForTests;
    static volatile BatchSave.Saver saverForTests;
    static volatile long gapForTests = -1;

    /** What a run takes: the ids to save, and what was left out of it. */
    public static final class Plan {
        public final List<String> ids;
        /** Saved here before, so left out. */
        public final int alreadySaved;
        /** Past {@link #MAX_RUN}, for a later run. */
        public final int later;

        Plan(List<String> ids, int alreadySaved, int later) {
            this.ids = Collections.unmodifiableList(ids);
            this.alreadySaved = alreadySaved;
            this.later = later;
        }
    }

    /** A post TikTok sent nothing for: left out quietly, then counted on the banner's last line. */
    private static final class Gone extends SaveProgress.Skip {
        Gone(String id) {
            super("TikTok has no post " + id + " any more");
        }
    }

    private ExportSave() {
    }

    /**
     * Off the main thread: the ids a run takes, in the export's order. The saved-videos record is
     * read from disk, so this doesn't run on the main thread.
     */
    public static Plan plan(Context context, List<String> ids) {
        boolean checkSaved = Settings.CHECK_SAVED_VIDEOS.get();
        List<String> taken = new ArrayList<>();
        int already = 0;
        int later = 0;
        for (String id : ids) {
            if (checkSaved && SavedVideoArchive.find(context, id) != null) already++;
            else if (taken.size() < MAX_RUN) taken.add(id);
            else later++;
        }
        return new Plan(taken, already, later);
    }

    /** Queues the run. False when it couldn't start: nothing to save, no permission or a full queue. */
    public static boolean start(Context context, Plan plan) {
        if (context == null || plan == null || plan.ids.isEmpty()) return false;
        if (android.os.Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        List<String> ids = plan.ids;
        Fetcher fetcher = fetcherForTests != null ? fetcherForTests : DramaEpisodes::post;
        BatchSave.Saver saver = saverForTests != null ? saverForTests : BatchSave::saveOne;
        SaveProgress progress = SaveProgress.queuedFiles(ids.size());
        MediaJobScheduler.Job job = progress.submit(BatchSave.LABEL, null, ids.size(),
                () -> run(app, ids, fetcher, saver, progress), null);
        return job != null;
    }

    /** The run itself, on a media worker. */
    static void run(Context app, List<String> ids, Fetcher fetcher, BatchSave.Saver saver, SaveProgress progress) {
        int[] already = {0};
        int[] gone = {0};
        long[] lastAsk = {0};
        long gap = gapForTests >= 0 ? gapForTests : ASK_GAP_MS;
        MediaFileWriter.Saved[] last = {null};
        try {
            SaveProgress.Outcome outcome = progress.run(index -> {
                String id = ids.get(index);
                long wait = lastAsk[0] + gap - System.currentTimeMillis();
                if (lastAsk[0] != 0 && wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException stopped) {
                        Thread.currentThread().interrupt();
                        throw new IOException("The run was stopped", stopped);
                    }
                }
                lastAsk[0] = System.currentTimeMillis();
                Object post;
                try {
                    post = fetcher.post(id);
                } catch (IOException | RuntimeException failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IOException("Could not ask TikTok for post " + id, failure);
                }
                if (post == null) {
                    gone[0]++;
                    throw new Gone(id);
                }
                if (saver.save(app, post, last) == BatchSave.Result.ALREADY_SAVED) {
                    already[0]++;
                    SaveRecords.countedWithoutFile();
                }
            });
            String message = message(app, outcome, already[0], gone[0]);
            if (message == null) {
                Utils.showToastLong(L10n.t("None of the picked videos could be saved. Try again."));
                return;
            }
            SaveNotice.saved(message, last[0]);
        } catch (RuntimeException failure) {
            Logger.printException(() -> "The data export run failed", failure);
            Utils.showToastLong(L10n.t("None of the picked videos could be saved. Try again."));
        }
    }

    /**
     * The banner: what {@link BatchSave#message} says for picked videos, and on a line of its own
     * how many TikTok no longer has. Null when nothing landed and nothing explains why.
     */
    static String message(Context context, SaveProgress.Outcome outcome, int already, int gone) {
        String goneLine = gone <= 0 ? null : L10n.quantity(context, gone,
                "One isn't on TikTok anymore", "%1$s aren't on TikTok anymore", String.valueOf(gone));
        if (outcome.saved == 0 && outcome.cancelled == 0 && outcome.stop == SaveProgress.Stop.NONE) {
            // Every one gone says that alone; any other failure is the usual try again.
            return gone == outcome.total ? goneLine : null;
        }
        String said = BatchSave.message(context, outcome, already);
        if (said == null) return goneLine;
        return goneLine == null ? said : said + "\n" + goneLine;
    }
}

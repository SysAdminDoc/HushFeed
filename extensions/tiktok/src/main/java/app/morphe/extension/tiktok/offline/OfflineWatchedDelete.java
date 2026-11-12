/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.offline;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Deletes the offline videos already watched each time TikTok starts, so the download that
 * follows fills their places with new ones.
 *
 * <p>TikTok has this built in and keeps it for a phone short on space. Its start-up task asks for
 * the current account's watched, unexpired offline videos and hands their ids to the same delete
 * that clears expired ones, which removes the files and the rows, and the next step of that task
 * downloads up to the limit again. Before it does, a server switch, a low-storage check, a flag
 * saying nothing offline was played this session and an hours-between-runs window all have to
 * agree, so on most phones it never runs. The patch calls {@link #onBootCleanup()} at the top of
 * that step, which makes the same two calls without the server switch, the storage check or the
 * window while the switch is on. TikTok's own step then finds nothing watched left.
 *
 * <p>TikTok counts a video as watched once its page was on screen, on the offline page or in the
 * feed. The step runs once per start, but it is a suspend function that also comes back through
 * its own entry when it resumes, so the clean-up guards itself to one run per process.
 */
@SuppressWarnings("unused")
public final class OfflineWatchedDelete {
    private static final String FAMILY = "offline watched delete";

    private static final AtomicBoolean RAN = new AtomicBoolean();

    /** TikTok's calls, which the patch fills in the stubs below with. The test hands its own. */
    interface Host {
        Object watchedAids();

        void deleteNow(List<?> aids);

        Object unwatchedCount();

        boolean consumedThisSession();
    }

    static volatile Host hostForTests;

    private OfflineWatchedDelete() {
    }

    /** At the top of TikTok's watched-video clean-up, on its start-up task. */
    public static void onBootCleanup() {
        try {
            if (!Settings.DELETE_WATCHED_OFFLINE_VIDEOS.get()) {
                HookStatus.bound(FAMILY, "off");
                return;
            }
            if (!RAN.compareAndSet(false, true)) return;
            // The start-up task runs off the main thread. Room refuses a query on it, so a caller
            // that ever moved there would hand the work on rather than lose it.
            if (Utils.isCurrentlyOnMainThread()) {
                Utils.runOnBackgroundThread(OfflineWatchedDelete::deleteWatched);
            } else {
                deleteWatched();
            }
        } catch (Throwable error) {
            Logger.printException(() -> "Could not start deleting watched offline videos", error);
        }
    }

    /** How many watched videos were handed to TikTok's delete. */
    static int deleteWatched() {
        try {
            // TikTok skips its own clean-up once an offline video was played this session, so the
            // one on screen keeps its file.
            if (consumedThisSession()) {
                HookStatus.bound(FAMILY, "skipped while playing offline");
                return 0;
            }
            Object aids = watchedAids();
            int count = aids instanceof List ? ((List<?>) aids).size() : 0;
            if (count == 0) {
                HookStatus.bound(FAMILY, "nothing watched");
                return 0;
            }
            deleteNow((List<?>) aids);
            HookStatus.bound(FAMILY, "deleted watched");
            Logger.printDebug(() -> "Deleted " + count + " watched offline videos");
            return count;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not delete watched offline videos", error);
            return 0;
        }
    }

    /**
     * How many offline videos are saved and not watched yet for the current account, or null when
     * TikTok can't say. Reads TikTok's database, so never on the main thread.
     */
    public static Integer unwatchedCountOrNull() {
        try {
            Object count = unwatchedCount();
            return count instanceof Integer ? (Integer) count : null;
        } catch (Throwable error) {
            Logger.printException(() -> "Could not count unwatched offline videos", error);
            return null;
        }
    }

    static void resetForTests() {
        RAN.set(false);
        hostForTests = null;
    }

    /** TikTok's watched, unexpired offline video ids for the current account. The patch fills this in. */
    private static Object watchedAids() {
        Host forTests = hostForTests;
        return forTests != null ? forTests.watchedAids() : null;
    }

    /** TikTok's delete for a list of offline video ids: files, rows and listeners. The patch fills this in. */
    private static void deleteNow(List<?> aids) {
        Host forTests = hostForTests;
        if (forTests != null) forTests.deleteNow(aids);
    }

    /** TikTok's count of saved offline videos not watched yet, boxed. The patch fills this in. */
    private static Object unwatchedCount() {
        Host forTests = hostForTests;
        return forTests != null ? forTests.unwatchedCount() : null;
    }

    /** TikTok's own flag that an offline video was played this session. The patch fills this in. */
    private static boolean consumedThisSession() {
        Host forTests = hostForTests;
        return forTests != null && forTests.consumedThisSession();
    }
}

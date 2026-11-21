/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.offline;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Counts the offline videos not watched yet without loading them (#482).
 *
 * <p>TikTok's offline update task runs every few minutes and asks for the account's saved,
 * unwatched videos twice, both times only to read how many there are. Its loader answers with
 * every one of them decoded into a full video, so with thousands saved (Custom offline videos
 * limit goes to 10,000) the task fills TikTok's heap and the app freezes and dies of
 * OutOfMemoryError. The patch points those two asks at {@link #countedUnwatched()}, which marks
 * the thread and calls the same loader. The loader, patched in front of its query, sees the mark
 * and asks the database two counts instead (saved minus watched, over the same account and
 * expiry), and answers a list of that length that holds nothing. Every other caller of the
 * loader, the offline page included, still gets the videos.
 */
@SuppressWarnings("unused")
public final class OfflineUnwatchedCount {
    private static final String FAMILY = "offline unwatched count";

    private static final ThreadLocal<Boolean> COUNTING = new ThreadLocal<>();

    /** TikTok's loader, which the patch fills in the stub below with. The test hands its own. */
    interface Host {
        List<?> load();
    }

    static volatile Host hostForTests;

    static {
        // The count reads no setting, so a paused Hushfeed still counts.
        HookStatus.runsWhilePaused(FAMILY);
    }

    private OfflineUnwatchedCount() {
    }

    /** Where the update task asked for the unwatched videos only to read the list's size. */
    public static List<?> countedUnwatched() {
        COUNTING.set(Boolean.TRUE);
        try {
            return loadNative();
        } finally {
            COUNTING.remove();
        }
    }

    /** In front of the loader's query: whether this ask only wants the count. */
    public static boolean counting() {
        return Boolean.TRUE.equals(COUNTING.get());
    }

    /**
     * The loader's answer while counting: as long as the videos saved and not watched, and holding
     * nothing, since the task that asked reads only its size.
     */
    public static List<?> unwatched(int saved, int watched) {
        int count = Math.max(0, saved - watched);
        HookStatus.bound(FAMILY, "counted");
        Logger.printDebug(() -> "Counted " + count + " unwatched offline videos without loading them");
        return Collections.nCopies(count, null);
    }

    static void resetForTests() {
        COUNTING.remove();
        hostForTests = null;
    }

    /** TikTok's unwatched offline video loader. The patch fills this in. */
    private static List<?> loadNative() {
        Host forTests = hostForTests;
        return forTests != null ? forTests.load() : null;
    }
}

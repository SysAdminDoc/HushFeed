/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Rebuild message search, an action in Hushfeed settings > Inbox.
 *
 * <p>TikTok's Inbox search reads a local index of the messages saved on the phone. Its message
 * search engine fills that index from the newest saved message back to the oldest, a batch at a
 * time with a tenth of a second between batches, but only while a search page is open: the
 * search page starts the walk in its own scope, so leaving search stops it. The walk also starts
 * over from the newest message whenever TikTok's refresh setting says so. A search made soon after
 * one of those restarts misses the older messages the walk hasn't reached yet.</p>
 *
 * <p>This clears the message index through TikTok's own reset (the one its chat history search
 * feature calls), then hands the engine's own catch-up a scope of this class's making, so the
 * walk runs to the oldest saved message whether a search page is open or not. Stop cancels that
 * scope, and the engine keeps the place it reached for the next time search opens. Nothing is
 * fetched from TikTok's servers: a message the phone never synced stays out of search.</p>
 *
 * <p>The four bridges at the bottom are filled in by the patch with calls to the engine and to
 * the coroutine helpers it uses. Unpatched, they answer that the engine isn't there.</p>
 */
public final class MessageSearchRebuild {
    /** The index was cleared and the walk is running. */
    public static final int STARTED = 1;
    /** TikTok's reset reported that it couldn't clear the index. */
    public static final int FAILED = 0;
    /** No message search engine for this account, or the patch didn't reach it. */
    public static final int UNAVAILABLE = -1;
    /** The reset's answer when it cleared the index. */
    private static final int CLEARED = 1;

    /** What {@link #rebuild} found, on the main thread. */
    public interface Callback {
        void onResult(int outcome);
    }

    private static final Object lock = new Object();
    @Nullable
    private static Object running;

    private MessageSearchRebuild() {
    }

    /**
     * Clears the index and starts the walk off the main thread, since the reset deletes rows from
     * TikTok's search database. A walk this process started is stopped first, so a second tap
     * starts over rather than running two.
     *
     * @return false when the background queue was full and nothing was started.
     */
    public static boolean rebuild(Callback callback) {
        return Utils.runOnBackgroundThread(() -> {
            int outcome = run();
            Utils.runOnMainThread(() -> callback.onResult(outcome));
        });
    }

    static int run() {
        stop();
        int reset;
        try {
            reset = resetIndex();
        } catch (RuntimeException error) {
            Logger.printException(() -> "Rebuild message search: the reset threw", error);
            return FAILED;
        }
        if (reset != CLEARED) {
            Logger.printInfo(() -> "Rebuild message search: the reset answered " + reset);
            return reset == FAILED ? FAILED : UNAVAILABLE;
        }
        Object scope = newScope();
        if (scope == null) return UNAVAILABLE;
        try {
            startIndex(scope);
        } catch (RuntimeException error) {
            Logger.printException(() -> "Rebuild message search: the catch-up threw", error);
            cancelScope(scope);
            return FAILED;
        }
        synchronized (lock) {
            running = scope;
        }
        Logger.printInfo(() -> "Rebuild message search: index cleared, catch-up started");
        return STARTED;
    }

    /** Whether a walk this process started is still there to stop. */
    public static boolean isRunning() {
        synchronized (lock) {
            return running != null;
        }
    }

    /**
     * Stops the walk this process started. The engine keeps the place it reached, so the next
     * search page carries on from there.
     *
     * @return false when there was nothing to stop.
     */
    public static boolean stop() {
        Object scope;
        synchronized (lock) {
            scope = running;
            running = null;
        }
        if (scope == null) return false;
        cancelScope(scope);
        return true;
    }

    /**
     * TikTok's own reset of the message index: {@link #CLEARED} when it cleared the index and
     * set the walk back to the newest message, {@link #FAILED} when the delete failed, and
     * {@link #UNAVAILABLE} when there is no engine for the signed-in account.
     */
    private static int resetIndex() {
        return UNAVAILABLE;
    }

    /** A new scope with its own job, the kind TikTok's MainScope() makes. */
    @Nullable
    private static Object newScope() {
        return null;
    }

    /** The engine's catch-up, launched in {@code scope}. */
    private static void startIndex(Object scope) {
    }

    /** Cancels {@code scope}, and the walk running in it, at its next batch. */
    private static void cancelScope(Object scope) {
    }
}

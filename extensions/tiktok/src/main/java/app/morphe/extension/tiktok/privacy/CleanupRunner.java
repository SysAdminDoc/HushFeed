/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Removes every item of one of the account's lists, one at a time, the way a person tapping
 * through them would: a page is read, each item on it is removed with a pause before every
 * request, and the page is read again until nothing new comes back.
 *
 * <p>Nothing here knows TikTok. A {@link Source} reads pages and removes items, a {@link Pacer}
 * waits, and the first refusal, captcha, rate limit or network error ends the run with the count
 * so far.</p>
 */
public final class CleanupRunner {
    /** Shortest pause before a request, so a run reads like a person and not a script. */
    static final long PAUSE_MS = 1_500L;
    /** The most the pause adds on top of {@link #PAUSE_MS}, picked afresh each time. */
    static final long JITTER_MS = 1_500L;
    /**
     * Pages in a row that bring nothing new before a run calls it done. A list whose items
     * keep coming back after they were removed would otherwise be walked forever.
     */
    static final int STALE_PAGES = 20;

    /** How a run ended. */
    public enum End {
        /** The list ran out. */
        DONE,
        /** Someone pressed Stop, or the account changed. */
        STOPPED,
        /** TikTok said no: a status code, a captcha or verify check, or a rate limit. */
        REFUSED,
        /** The request didn't get an answer. */
        NETWORK,
        /** This TikTok build doesn't have the call. */
        UNSUPPORTED
    }

    /** One page of a list: the ids on it, where the next page starts and whether there is one. */
    public static final class Page {
        final List<String> ids;
        final long next;
        final boolean more;

        public Page(List<String> ids, long next, boolean more) {
            this.ids = ids == null ? Collections.<String>emptyList() : ids;
            this.next = next;
            this.more = more;
        }
    }

    /** A request that ended the run. */
    public static final class Failure extends Exception {
        final End end;

        public Failure(End end, String detail) {
            super(detail);
            this.end = end;
        }

        public End end() {
            return end;
        }
    }

    /** A request with nothing to read back but whether it worked. */
    public interface Request {
        void send() throws Failure;
    }

    /** Where the list comes from and how an item leaves it. */
    public interface Source {
        Page fetch(long cursor) throws Failure;

        void remove(String id) throws Failure;

        /**
         * One more request once the list has run out, for what the list never shows, sent after
         * the same pause as the rest. Null when there's nothing more.
         */
        default Request last() {
            return null;
        }
    }

    /** Waits between requests. {@link #wake()} ends a wait early, for Stop. */
    public interface Pacer {
        void pause(long ms) throws InterruptedException;

        void wake();
    }

    /** Hears each removal and the end, on the run's own thread. */
    public interface Listener {
        void removed(int count);
    }

    private final Source source;
    private final Pacer pacer;
    private final Random random;
    private final Listener listener;
    private final Set<String> tried = new HashSet<>();
    private volatile boolean stopRequested;
    private volatile int removed;

    public CleanupRunner(Source source, Pacer pacer, Random random, Listener listener) {
        this.source = source;
        this.pacer = pacer;
        this.random = random;
        this.listener = listener;
    }

    /** Asks the run to end before its next request. Safe from any thread. */
    public void stop() {
        stopRequested = true;
        pacer.wake();
    }

    public boolean stopRequested() {
        return stopRequested;
    }

    /** Items removed so far. */
    public int removed() {
        return removed;
    }

    /**
     * Works through the list and answers how it ended. Each page is read again from the same
     * place after it gave up items, since an offset list moves up the ones behind them and a
     * cursor list leaves the rest where they were, so the same read serves both.
     */
    public End run() {
        long cursor = 0;
        boolean first = true;
        int stale = 0;
        try {
            while (true) {
                if (!first && !pause()) return End.STOPPED;
                first = false;
                if (stopRequested) return End.STOPPED;
                Page page = source.fetch(cursor);
                boolean removedAny = false;
                for (String id : page.ids) {
                    if (id == null || id.isEmpty() || !tried.add(id)) continue;
                    if (!pause() || stopRequested) return End.STOPPED;
                    source.remove(id);
                    removedAny = true;
                    removed++;
                    if (listener != null) listener.removed(removed);
                }
                if (removedAny) {
                    stale = 0;
                    continue;
                }
                if (!page.more || page.next == cursor || ++stale >= STALE_PAGES) return finish();
                cursor = page.next;
            }
        } catch (Failure failure) {
            return stopRequested ? End.STOPPED : failure.end;
        } catch (RuntimeException unexpected) {
            return stopRequested ? End.STOPPED : End.UNSUPPORTED;
        }
    }

    /** Sends the source's last request, if it has one, and answers how the run ended. */
    private End finish() throws Failure {
        Request last = source.last();
        if (last == null) return End.DONE;
        if (!pause() || stopRequested) return End.STOPPED;
        last.send();
        return End.DONE;
    }

    /** Waits before a request. False when Stop came during the wait. */
    private boolean pause() {
        if (stopRequested) return false;
        long ms = PAUSE_MS + (long) (random.nextDouble() * JITTER_MS);
        try {
            pacer.pause(ms);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stopRequested = true;
            return false;
        }
        return !stopRequested;
    }

    /** A pacer that sleeps on a lock, so {@link CleanupRunner#stop()} cuts a wait short. */
    public static final class LockPacer implements Pacer {
        private final Object lock = new Object();
        private boolean woken;

        @Override
        public void pause(long ms) throws InterruptedException {
            long until = System.currentTimeMillis() + ms;
            synchronized (lock) {
                while (!woken) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) return;
                    lock.wait(left);
                }
            }
        }

        @Override
        public void wake() {
            synchronized (lock) {
                woken = true;
                lock.notifyAll();
            }
        }
    }
}

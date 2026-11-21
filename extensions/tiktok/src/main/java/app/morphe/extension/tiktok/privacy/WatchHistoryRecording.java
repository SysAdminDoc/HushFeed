/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Whether TikTok's view report for a watched video goes out.
 *
 * <p>Asked at the entry of AwemeStatsApi's two senders: the report for one video, which sends
 * at once or queues for a batch, and the batch send. That report (/aweme/v1/aweme/stats/) is
 * how TikTok counts a view, and Watch history is built from it on TikTok's side. It also carries
 * how long the video before played, so For You learns less while it's held back. Read on every
 * call, so a change applies to the next video, and while Hushfeed is paused the switch answers
 * off and TikTok reports as it ships. Off by default even with the patch picked: people use
 * Watch history to find a video again.
 *
 * <p>The senders also count what they did for the diagnostic report (#448): a reporter whose
 * Watch history kept only the first video couldn't say whether TikTok made the reports, sent
 * them or had them fail, and nothing here reproduced it.
 */
@SuppressWarnings("unused")
public final class WatchHistoryRecording {
    static final String TITLE = "WATCH HISTORY";
    /** Characters kept of the last failed send's message. */
    static final int MAX_FAILURE = 120;

    private static final AtomicInteger REPORTS = new AtomicInteger();
    private static final AtomicInteger HELD = new AtomicInteger();
    private static final AtomicInteger HELD_BY_TIKTOK = new AtomicInteger();
    private static final AtomicInteger SENT_ALONE = new AtomicInteger();
    private static final AtomicInteger BATCHES = new AtomicInteger();
    private static final AtomicInteger BATCHED = new AtomicInteger();
    private static final AtomicInteger FAILED = new AtomicInteger();
    /** The list the batch send on this thread is sending, so its send counts its videos. */
    private static final ThreadLocal<Integer> SENDING = new ThreadLocal<>();
    private static volatile String lastFailure;
    /** TikTok's `basic_vv_batch_size` as the last report read it, or -1 before one did. */
    private static volatile int batchSize = -1;
    private static volatile boolean registered;

    private WatchHistoryRecording() {
    }

    public static boolean shouldSkip() {
        // The batch send can run from a feed pause early in a start, before the settings load.
        boolean skip = SettingsStatus.watchHistoryEnabled && Utils.getContext() != null
                && Settings.STOP_WATCH_HISTORY.get();
        if (skip) {
            HELD.incrementAndGet();
            Logger.printDebug(() -> "Watch history: a view report was not sent");
        }
        return skip;
    }

    /** The view report for one video was made, before the switch is asked. */
    public static void noteReport() {
        register();
        REPORTS.incrementAndGet();
    }

    /**
     * How many For You reports TikTok queues before it sends them together, read where the
     * report decides. One or less sends each report on its own.
     */
    public static void noteBatchSize(int size) {
        batchSize = size;
    }

    /**
     * TikTok's own request limit stopped that report before it was sent or queued. The server
     * can tell the app to back off, and a report made then is dropped, not kept for later.
     */
    public static void noteHeldByTikTok() {
        HELD_BY_TIKTOK.incrementAndGet();
        Logger.printDebug(() -> "Watch history: TikTok's request limit dropped a view report");
    }

    /** That report went out on its own, after TikTok waited for the answer. */
    public static void noteSentAlone() {
        SENT_ALONE.incrementAndGet();
        Logger.printDebug(() -> "Watch history: a view report was sent");
    }

    /**
     * The batch send was asked to send these, before the switch is asked. It's also asked with
     * an empty list after each queued video, and once on the main thread before it moves the
     * same list to a worker, so only a send that went out counts.
     */
    public static void noteBatch(List<?> items) {
        register();
        SENDING.set(items == null ? 0 : items.size());
    }

    /** The batch send went out, after TikTok waited for the answer. */
    public static void noteBatchSent() {
        Integer size = SENDING.get();
        SENDING.remove();
        int videos = size == null ? 0 : size;
        BATCHES.incrementAndGet();
        BATCHED.addAndGet(videos);
        Logger.printDebug(() -> "Watch history: " + videos + " view reports were sent together");
    }

    /**
     * The batch send's wait for its request, called in place of TikTok's own Future.get(). A
     * failure is counted and thrown on unchanged, so TikTok's catch handles it as it ships. The
     * count lives here rather than in that catch because 47.9.3's catch keeps no exception: it
     * lands straight on the method's last return-void.
     */
    public static Object awaitBatch(Future<?> request) throws InterruptedException, ExecutionException {
        try {
            return request.get();
        } catch (ExecutionException error) {
            noteBatchFailed(error.getCause() != null ? error.getCause() : error);
            throw error;
        } catch (InterruptedException | RuntimeException error) {
            noteBatchFailed(error);
            throw error;
        }
    }

    /** The batch send failed. */
    static void noteBatchFailed(Throwable error) {
        SENDING.remove();
        FAILED.incrementAndGet();
        lastFailure = describe(error);
        Logger.printDebug(() -> "Watch history: a batch of view reports failed: " + lastFailure);
    }

    static String describe(Throwable error) {
        if (error == null) return "unknown";
        String message = error.getMessage();
        String line = error.getClass().getSimpleName();
        if (message != null && !message.isEmpty()) {
            int end = message.indexOf('\n');
            String first = end < 0 ? message : message.substring(0, end);
            int query = first.indexOf('?');
            if (query >= 0) first = first.substring(0, query);
            if (first.length() > MAX_FAILURE) first = first.substring(0, MAX_FAILURE) + "...";
            line += ": " + first;
        }
        return line;
    }

    private static void register() {
        if (registered) return;
        registered = true;
        try {
            LogBufferManager.registerReportSection(Report.INSTANCE);
        } catch (Throwable ignored) {
            // A report without this section beats a view report that throws.
        }
    }

    /** The WATCH HISTORY section of the diagnostic report. */
    static final class Report implements LogBufferManager.ReportSection {
        static final Report INSTANCE = new Report();

        @Override public String title() {
            return TITLE;
        }

        @Override public List<String> lines() {
            List<String> lines = new ArrayList<>();
            boolean on = Settings.STOP_WATCH_HISTORY.savedValue();
            lines.add("Keep videos out of Watch history: " + (on ? "on" : "off"));
            if (on && !Settings.STOP_WATCH_HISTORY.get()) {
                lines.add("Hushfeed is paused, so TikTok sends every view report");
            }
            lines.add("View reports since TikTok started: " + REPORTS.get());
            int size = batchSize;
            if (size > 1) {
                lines.add("TikTok sends For You's reports " + size + " at a time");
            } else if (size >= 0) {
                lines.add("TikTok sends each report on its own");
            }
            lines.add("Sent one at a time: " + SENT_ALONE.get());
            lines.add("Sent together: " + BATCHED.get() + " in " + BATCHES.get()
                    + (BATCHES.get() == 1 ? " send" : " sends"));
            lines.add("Dropped by TikTok's own request limit: " + HELD_BY_TIKTOK.get());
            lines.add("Held back by the switch: " + HELD.get());
            int failed = FAILED.get();
            lines.add("Sends that failed: " + failed);
            String last = lastFailure;
            if (failed > 0 && last != null) lines.add("Last failure: " + last);
            return lines;
        }
    }

    static void resetForTests() {
        REPORTS.set(0);
        HELD.set(0);
        HELD_BY_TIKTOK.set(0);
        SENT_ALONE.set(0);
        BATCHES.set(0);
        BATCHED.set(0);
        FAILED.set(0);
        SENDING.remove();
        lastFailure = null;
        batchSize = -1;
    }
}

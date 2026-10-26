/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/** Adopts legacy files before publication and retries records with durable destination identity. */
final class LiveSpoolRecovery {
    static final long SETTLE_MS = 3000;
    private static final Pattern NAMED = Pattern.compile("live-(\\d{12,})-([A-Za-z0-9._]*)-(-?\\d+)\\.(flv|ts|mp4|scr)");
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private LiveSpoolRecovery() { }
    /** The start of a spool's name, with the handle cut to what a file name can hold. */
    static String spoolPrefix(String creator, long startedAtMs) {
        String handle = creator == null ? "" : creator.replaceAll("[^A-Za-z0-9._]", "");
        if (handle.length() > 40) handle = handle.substring(0, 40);
        return "live-" + startedAtMs + "-" + handle + "-";
    }

    /** The same start for the MP4 a spool is rewritten into, so a cut-off rewrite finds its spool. */
    static String prefixOf(File spool) {
        Matcher named = NAMED.matcher(spool.getName());
        return named.matches() ? "live-" + named.group(1) + "-" + named.group(2) + "-" : "live-";
    }


    static void atStart(Context context) {
        if (context == null || !STARTED.compareAndSet(false, true)) return;
        Utils.runOnMainThread(() -> Utils.runOnOwnThread("hushfeed-live-recovery", () -> {
            Context app = Utils.getContext() == null ? context : Utils.getContext();
            try {
                adoptLegacy(RecordingRetention.in(app), MediaCache.cacheDirectory(app));
                List<LiveRecorder.Result> results = LiveRecordingSaver.recoverRetained(app);
                if (!results.isEmpty()) Utils.runOnMainThreadDelayed(() -> {
                    for (LiveRecorder.Result result : results) LiveRecordingSaver.announce(result);
                }, SETTLE_MS);
            } catch (IOException failure) { Logger.printException(() -> "Recording recovery ownership could not be established", failure); }
        }));
    }
    static void adoptLegacy(RecordingRetention store, File directory) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Legacy recordings cannot be inspected");
        // Sources claim their uncommitted rewrites before any standalone MP4 is considered.
        Arrays.sort(files, (a, b) -> {
            boolean ar = a.getName().endsWith(".flv") || a.getName().endsWith(".ts");
            boolean br = b.getName().endsWith(".flv") || b.getName().endsWith(".ts");
            return ar == br ? a.getName().compareTo(b.getName()) : ar ? -1 : 1;
        });
        for (File file : files) {
            if (!RecordingRetention.legacyCandidate(file) || MediaCache.isActive(file) || store.protectsCache(file)) continue;
            Matcher named = NAMED.matcher(file.getName());
            String creator = "", format; long started = file.lastModified();
            if (named.matches()) {
                creator = named.group(2); format = named.group(4);
                try { started = Long.parseLong(named.group(1)); } catch (NumberFormatException ignored) { /* Keep the source file timestamp. */ }
            } else if (file.getName().endsWith(".flv")) format = "flv";
            else if (file.getName().endsWith(".ts")) format = "ts";
            else format = "screen";
            if ("scr".equals(format)) format = "screen";
            RecordingRetention.Record record = store.adopt(file, format, creator, started);
            if (record != null) record.close();
        }
    }
    static void resetForTests() { STARTED.set(false); }
}

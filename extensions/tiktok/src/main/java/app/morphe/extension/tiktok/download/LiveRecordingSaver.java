/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Owns recording sources outside evictable cache until their exact publication is acknowledged.
 * Finished MP4s use the native saving and announcement path.
 */
public final class LiveRecordingSaver {
    private LiveRecordingSaver() {
    }

    /** Where recordings are saved, which is where videos are. */
    public static String folder() {
        return DownloadsPatch.getVideoDownloadPath();
    }

    /** Builds one recording with durable ownership before allocating source bytes. */
    public static LiveRecorder create(Context context, LiveRecorder.Target target, LiveRecorder.Listener listener) throws IOException {
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        boolean flv = target.transport == LiveRecorder.Transport.FLV;
        RecordingRetention.Record record = begin(app, flv ? "flv" : "ts", target.creator);
        try {
            File spool = record.file(record.get("source"));
            if (!spool.createNewFile()) throw new IOException("Recording source already exists");
            LiveRecorder.Source source = flv ? new LiveStreamSources.Flv(LiveStreamSources.NETWORK, target.url)
                    : new LiveStreamSources.Hls(LiveStreamSources.NETWORK, target.url);
            return new LiveRecorder(target, spool, source, finisher(app, record), result -> finished(record, result, listener));
        } catch (IOException | RuntimeException failure) { close(record); throw failure; }
    }

    /** The durable owner exists before the projection is consumed or an encoder writes bytes. */
    public static LiveRecorder createScreen(Context context, LiveRecorder.Target target, boolean audio,
                                            LiveRecorder.Listener listener) throws IOException {
        Context app=context.getApplicationContext()==null?context:context.getApplicationContext();
        RecordingRetention.Record record=begin(app,"flv",target.creator);
        try {
            record.update("capture",true,"captureComplete",false,"screenAudio",LiveRecorder.ScreenAudio.VIDEO_ONLY.name());
            File spool=record.source();if(!spool.createNewFile())throw new IOException("Screen capture source already exists");
            ScreenSource source=new ScreenSource(app,app.getResources().getDisplayMetrics(),audio,(output,capturedAudio)->new CaptureJournal(record,output,capturedAudio));
            return new LiveRecorder(target,spool,source,finisher(app,record),result->finished(record,result.withScreenAudio(source.screenAudio()),listener));
        } catch(IOException | RuntimeException failure){close(record);throw failure;}
    }
    private static RecordingRetention.Record begin(Context app, String format, String creator) throws IOException {
        try { return RecordingRetention.in(app).begin(format, creator, System.currentTimeMillis()); }
        catch (IOException failure) { showRetained(app); throw failure; }
    }
    private static LiveRecorder.Finisher finisher(Context app, RecordingRetention.Record record) {
        return new LiveRecorder.Finisher() {
            @Override public LiveRecorder.Published finish(File file, LiveRecorder.Target target, long startedAtMs) throws IOException { return publishRecord(app, record); }
            @Override public boolean ownsFiles() { return true; }
            @Override public boolean usefulWithoutSpool() { return !record.get("final").isEmpty() || "true".equals(record.get("capture")); }
            @Override public boolean partial() { return "true".equals(record.get("captureIncomplete")); }
        };
    }
    private static void finished(RecordingRetention.Record record, LiveRecorder.Result result, LiveRecorder.Listener listener) {
        rememberStopReason(record, result.end);
        close(record);
        boolean retained = !record.acknowledged() && !"DISCARDED".equals(record.get("state"));
        listener.onFinished(retained ? result.withRetainedId(record.id) : result);
    }
    private static void rememberStopReason(RecordingRetention.Record record, LiveRecorder.End end) {
        if (end == null || end == LiveRecorder.End.RECOVERED || record.quarantined || record.acknowledged()) return;
        try {
            // rawFiles checks the live lease even when publication evidence alone remains.
            boolean payload = !record.rawFiles().isEmpty();
            if (payload || !record.get("final").isEmpty() || !record.get("intentKey").isEmpty()
                    || !record.get("uri").isEmpty() || !record.get("file").isEmpty()
                    || !record.get("legacy").isEmpty()) record.update("stopReason", end.name());
        } catch (IOException failure) {
            // This extra receipt cannot change an already verified publication's outcome.
            Logger.printException(() -> "Recording stop reason could not be retained", failure);
        }
    }

    private static void close(RecordingRetention.Record record) {
        try { record.close(); } catch (IOException failure) { Logger.printException(() -> "Recording recovery cleanup is pending", failure); }
    }

    static LiveRecorder.Published publishRecord(Context app, RecordingRetention.Record record) throws IOException {
        if(record.quarantined)throw new IOException("Damaged recording metadata needs explicit raw export or deletion");
        if ("true".equals(record.get("legacyUnknown"))) throw new IOException("Legacy recording has no destination acknowledgement identity");
        RecordingPublication publication = new RecordingPublication(app, record);
        MediaFileWriter.Saved existing = publication.alreadyPublished();
        if (existing != null) return new LiveRecorder.Published(existing.name, existing);
        File mp4 = record.finalized();
        if (mp4 == null) {
            File source = record.source();
            switch (record.get("format")) {
                case "screen": throw new IOException("The screen recording has no successful finalization receipt");
                case "mp4":
                    validateMp4(source); mp4 = source; break;
                case "flv":
                    mp4 = record.file("remux.mp4");
                    if("true".equals(record.get("capture"))) {
                        CaptureFlv.Prefix prefix=CaptureJournal.recoverPrefix(record);
                        LiveRemux.fromFlv(source,new CaptureMuxer(record,prefix),prefix.completeBytes,true);validateMp4(mp4);
                    } else LiveRemux.fromFlv(source,new LiveRemux.MuxerWriter(mp4));
                    break;
                case "ts":
                    mp4 = record.file("remux.mp4"); LiveRemux.fromTransportStream(source, mp4); break;
                default: throw new IOException("Unknown recording source format");
            }
            record.finalized(mp4, record.get("screenAudio"));
        }
        MediaFileWriter.Saved saved = MediaFileWriter.publishForResult(app, mp4,
                fileName(record.get("creator"), record.number("started")), "video/mp4", folder(), true,
                Math.max(mp4.length(), MediaBudget.MAX_LIVE_RECORDING_BYTES), publication);
        return new LiveRecorder.Published(saved.name, saved);
    }
    private static void validateMp4(File file) throws IOException {
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    extractor.selectTrack(i);
                    if (extractor.getSampleTime() >= 0) return;
                }
            }
            throw new IOException("Retained MP4 has no readable video sample");
        } catch (RuntimeException failure) { throw new IOException("Retained MP4 is not finalized", failure); }
        finally { extractor.release(); }
    }

    static java.util.List<LiveRecorder.Result> recoverRetained(Context app) throws IOException {
        java.util.List<LiveRecorder.Result> results = new java.util.ArrayList<>();
        RecordingRetention store = RecordingRetention.in(app);
        for (String id : store.ids()) {
            RecordingRetention.Record record = null;
            try {
                record=store.open(id);if(record==null)continue;
                if ("DISCARDED".equals(record.get("state"))) continue;
                if ("ADOPTING".equals(record.get("state"))) record.moveLegacy();
                LiveRecorder.Published published = publishRecord(app, record);
                results.add(new LiveRecorder.Result(LiveRecorder.Outcome.SAVED_PARTIAL, LiveRecorder.End.APP_EXIT, 0, published.name, published.saved, null)
                        .withScreenAudio(LiveRecorder.ScreenAudio.valueOf(record.get("screenAudio"))));
            } catch (IOException | RuntimeException failure) {
                results.add(new LiveRecorder.Result(LiveRecorder.Outcome.FAILED, LiveRecorder.End.APP_EXIT, 0, null, null, null).withRetainedId(id));
            } finally { if(record!=null)close(record); }
        }
        return results;
    }

    /** Existing Retry banners open an action list; no usable recording is silently pruned. */
    static void showRetained(Context context) {
        Utils.runOnOwnThread("hushfeed-recording-recovery-list", () -> {
            try {
                RecordingRetention store = RecordingRetention.in(context);
                java.util.List<String> ids = store.ids();
                java.util.List<String> names = new java.util.ArrayList<>();
                java.util.List<String> idle = new java.util.ArrayList<>();
                for (String id : ids) {
                    RecordingRetention.Record record = null;
                    try {record=store.open(id);if(record==null)continue;if (!record.acknowledged()) { idle.add(id); names.add(record.quarantined?L10n.t("Recording files need recovery"):fileName(record.get("creator"), record.number("started"))); } }
                    catch(IOException failure){Logger.printException(()->"One retained recording is unavailable",failure);}
                    finally {if(record!=null)close(record);}
                }
                if (idle.isEmpty()) return;
                Utils.runOnMainThread(() -> {
                    android.app.Activity activity = Utils.getVisibleActivity();
                    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                    showRecoveryDialog(new android.app.AlertDialog.Builder(activity).setTitle(L10n.t("Record LIVEs you watch"))
                            .setItems(names.toArray(new String[0]), (dialog, index) -> chooseRetained(context, activity, idle.get(index), names.get(index)))
                            .setNegativeButton(android.R.string.cancel, null));
                });
            } catch (IOException failure) { Logger.printException(() -> "Retained recordings could not be read", failure); }
        });
    }
    private static void chooseRetained(Context context, android.app.Activity activity, String id, String name) {
        Utils.runOnOwnThread("hushfeed-recording-recovery-options", () -> {
            try {
                RecordingRetention.Record record = RecordingRetention.in(context).open(id);
                if (record == null) return;
                boolean quarantined=record.quarantined;
                boolean legacy = "true".equals(record.get("legacyUnknown"));
                boolean unfinished = "screen".equals(record.get("format")) && record.get("final").isEmpty();
                close(record);
                Utils.runOnMainThread(() -> {
                    String[] actions = quarantined || legacy || unfinished ? new String[]{L10n.t("Export recording files"), L10n.t("Delete recording files")}
                            : new String[]{L10n.t("Retry"), L10n.t("Export recording files"), L10n.t("Delete recording files")};
                    android.app.AlertDialog.Builder chooser = new android.app.AlertDialog.Builder(activity).setTitle(name);
                    if (quarantined || legacy || unfinished) {
                        chooser.setMessage(quarantined
                                ? L10n.t("The recovery record is damaged. Export a copy of the recording files or delete them.")
                                : legacy
                                ? L10n.t("The publication state of these older recording files is unknown. Export a copy or delete the retained files.")
                                : L10n.t("The screen recording did not finish. Its files can be exported but may not play."))
                                .setPositiveButton(actions[0], (dialog, action) -> RecordingExport.open(activity, id))
                                .setNeutralButton(actions[1], (dialog, action) -> actOnRetained(context, id, true));
                    } else chooser.setItems(actions, (dialog, action) -> {
                        if (action == 1) RecordingExport.open(activity, id);
                        else actOnRetained(context, id, action == 2);
                    });
                    showRecoveryDialog(chooser.setNegativeButton(android.R.string.cancel, null));
                });
            } catch (IOException failure) { Logger.printException(() -> "Recording recovery options are unavailable", failure); }
        });
    }
    private static void showRecoveryDialog(android.app.AlertDialog.Builder builder) {
        android.app.AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
        dialog.show();
    }

    private static LiveRecorder.Result retriedResult(RecordingRetention.Record record, LiveRecorder.Published published) {
        LiveRecorder.End reason;
        try { reason = LiveRecorder.End.valueOf(record.get("stopReason")); }
        catch (IllegalArgumentException unknown) { reason = LiveRecorder.End.RECOVERED; }
        boolean complete = (reason == LiveRecorder.End.STOPPED || reason == LiveRecorder.End.LEFT_ROOM)
                && !"true".equals(record.get("captureIncomplete"));
        LiveRecorder.Result result = new LiveRecorder.Result(
                complete ? LiveRecorder.Outcome.SAVED : LiveRecorder.Outcome.SAVED_PARTIAL,
                reason, 0, published.name, published.saved, null)
                .withScreenAudio(LiveRecorder.ScreenAudio.valueOf(record.get("screenAudio")));
        return record.acknowledged() ? result : result.withRetainedId(record.id);
    }

    private static void actOnRetained(Context app, String id, boolean discard) {
        Utils.runOnOwnThread("hushfeed-recording-recovery-action", () -> {
            try {
                RecordingRetention.Record record = RecordingRetention.in(app).open(id);
                if (record == null) return;
                try {
                    if (discard) {new RecordingPublication(app, record).discard();record.close();Utils.showToastShort(L10n.t("Done"));}
                    else {
                        LiveRecorder.Published published = publishRecord(app, record);
                        announce(retriedResult(record, published));
                    }
                } finally { close(record); }
            } catch (IOException | RuntimeException failure) {
                Logger.printException(() -> "Retained recording action failed", failure);
                Utils.showToastLong(discard
                        ? L10n.t("Some recording files remain. Try deleting them again.")
                        : L10n.t("The recording couldn't be saved. Try again."));
            }
        });
    }

    /** {@code live_<handle>_<yyyyMMdd>_<HHmmss>.mp4}, with the handle cleaned for a file name. */
    static String fileName(String creator, long startedAtMs) {
        String handle = creator == null ? "" : creator.replaceAll("[^A-Za-z0-9._-]", "");
        if (handle.length() > 40) handle = handle.substring(0, 40);
        String when = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(startedAtMs));
        return "live_" + (handle.isEmpty() ? "" : handle + "_") + when + ".mp4";
    }

    /** Tells the viewer how a recording ended. Safe to call from any thread. */
    public static void announce(LiveRecorder.Result result) {
        try {
            String folder = folder();
            if (result.retainedId != null) {
                String message = result.outcome == LiveRecorder.Outcome.SAVED_PARTIAL
                        ? partialText(result.end, folder)
                        : result.outcome == LiveRecorder.Outcome.SAVED
                        ? L10n.f("LIVE recording saved to %1$s", folder) : L10n.t("The recording couldn't be saved. Try again.");
                if (result.saved != null && result.screenAudio == LiveRecorder.ScreenAudio.VIDEO_ONLY) message += "\n" + L10n.t("This screen recording has no sound");
                SaveNotice.saved(message, result.saved instanceof MediaFileWriter.Saved ? (MediaFileWriter.Saved) result.saved : null,
                        () -> showRetained(Utils.getContext()));
                return;
            }
            switch (result.outcome) {
                case SAVED:
                    saved(L10n.f("LIVE recording saved to %1$s", folder), result);
                    break;
                case SAVED_PARTIAL:
                    saved(partialText(result.end, folder), result);
                    break;
                case FAILED:
                    Utils.showToastLong(L10n.t("The recording couldn't be saved. Try again."));
                    break;
                case NOTHING_SAVED:
                default:
                    Utils.showToastLong(nothingMessage(result.end));
                    break;
            }
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not announce the end of a LIVE recording", failure);
        }
    }

    private static void saved(String message, LiveRecorder.Result result) {
        if (result.screenAudio == LiveRecorder.ScreenAudio.VIDEO_ONLY) {
            message += "\n" + L10n.t("This screen recording has no sound");
        }
        if (result.saved instanceof MediaFileWriter.Saved) {
            SaveNotice.saved(message, (MediaFileWriter.Saved) result.saved);
        } else {
            Utils.showToastLong(message);
        }
    }

    /** Why a saved recording stops short, one translatable sentence for each reason. */
    static String partialText(LiveRecorder.End end, String folder) {
        switch (end) {
            case DISCONNECTED:
                return L10n.f("LIVE ended or dropped. Recording saved to %1$s", folder);
            case STORAGE:
                return L10n.f("Storage almost full. Recording saved to %1$s", folder);
            case SIZE_LIMIT:
                return L10n.f("Size limit reached. Recording saved to %1$s", folder);
            case APP_EXIT:
                return L10n.f("TikTok closed. What was recorded is saved to %1$s", folder);
            case RECOVERED:
                return L10n.f("Recovered recording saved to %1$s", folder);
            default:
                return L10n.f("Recording stopped early. Saved to %1$s", folder);
        }
    }

    static String nothingMessage(LiveRecorder.End end) {
        switch (end) {
            case UNSUPPORTED:
                return L10n.t("This LIVE's stream can't be saved");
            case STORAGE:
                return L10n.t("There isn't enough room on the phone to record");
            default:
                return L10n.t("Nothing was recorded");
        }
    }
}

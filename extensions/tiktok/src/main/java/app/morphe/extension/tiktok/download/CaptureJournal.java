/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import java.io.IOException;

/** Binds source grants and synced complete-tag checkpoints to the pre-existing durable owner. */
final class CaptureJournal implements CaptureFlv.Sink {
    private final RecordingRetention.Record record;
    private final LiveRecorder.Output output;
    private final CaptureSpace budget;
    private final CaptureSpace.Free free;
    private long payload, samples;

    CaptureJournal(
            RecordingRetention.Record record,
            LiveRecorder.Output output,
            LiveRecorder.ScreenAudio audio)
            throws IOException {
        this(record, output, audio, new CaptureSpace(), java.io.File::getUsableSpace);
    }

    CaptureJournal(
            RecordingRetention.Record record,
            LiveRecorder.Output output,
            LiveRecorder.ScreenAudio audio,
            CaptureSpace budget,
            CaptureSpace.Free free)
            throws IOException {
        this.record = record;
        this.output = output;
        this.budget = budget;
        this.free = free;
        record.update("capture", true, "captureComplete", false, "screenAudio", audio.name());
    }

    @Override
    public void configured(CaptureFlv.Config config) throws IOException {
        record.update(
                "captureConfig",
                config.fingerprint,
                "captureWidth",
                config.width,
                "captureHeight",
                config.height);
    }

    @Override
    public void ending(long presentationUs) throws IOException {
        record.update("captureEndUs", presentationUs);
    }

    @Override
    public void append(byte[] bytes, int media, int count) throws IOException {
        try {
            budget.sourceWrite(
                    footprint(record, 0, payload, samples),
                    bytes.length,
                    media,
                    count,
                    free.bytes(record.directory));
        } catch (CaptureSpace.Limit limit) {
            throw new LiveRecorder.SpoolFull(
                    limit.size ? LiveRecorder.End.SIZE_LIMIT : LiveRecorder.End.STORAGE,
                    limit.getMessage());
        }
        output.write(bytes, 0, bytes.length);
        payload += media;
        samples += count;
    }

    @Override
    public void checkpoint(long completeBytes, boolean ended) throws IOException {
        if (completeBytes < 0 || completeBytes > record.source().length())
            throw new IOException("Capture checkpoint exceeds its source");
        output.checkpoint();
        record.update(
                "captureBytes",
                completeBytes,
                "capturePayload",
                payload,
                "captureSamples",
                samples,
                "captureComplete",
                ended);
    }

    static CaptureFlv.Prefix recoverPrefix(RecordingRetention.Record record) throws IOException {
        CaptureFlv.Prefix prefix = CaptureFlv.prefix(record.source());
        if (!prefix.usable()) throw new LiveRemux.NothingRecorded("No video was recorded");
        if (record.get("captureConfig").isEmpty()
                || !record.get("captureConfig").equals(prefix.configuration))
            throw new IOException("Capture codec configuration changed after its durable receipt");
        if (prefix.ended
                && (record.get("captureEndUs").isEmpty()
                        || record.number("captureEndUs") / 1000 != prefix.endTimeUs / 1000))
            throw new IOException("Capture end timestamp changed after its durable receipt");
        record.update(
                "captureBytes",
                prefix.completeBytes,
                "capturePayload",
                prefix.payloadBytes,
                "captureSamples",
                prefix.samples,
                "captureComplete",
                !prefix.incomplete,
                "captureIncomplete",
                prefix.incomplete);
        return prefix;
    }

    static void beforePublication(
            RecordingRetention.Record record,
            long copied,
            int count,
            java.io.File destination,
            CaptureSpace.Free available)
            throws IOException {
        if (!"true".equals(record.get("capture"))) return;
        java.io.File existing = destination;
        while (existing != null && !existing.exists()) existing = existing.getParentFile();
        long free = existing == null ? 0 : available.bytes(existing);
        new CaptureSpace()
                .publicationWrite(
                        footprint(
                                record,
                                copied,
                                record.number("capturePayload"),
                                record.number("captureSamples")),
                        record.number("bytes"),
                        copied,
                        count,
                        free);
    }

    static CaptureSpace.Footprint footprint(
            RecordingRetention.Record record, long published, long payload, long samples)
            throws IOException {
        return new CaptureSpace.Footprint(
                record.source().length(),
                record.file("remux.mp4").length(),
                published,
                record.otherOwnedBytes(),
                payload,
                samples);
    }
}

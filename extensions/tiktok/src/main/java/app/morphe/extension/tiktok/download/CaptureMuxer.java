/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import app.morphe.extension.shared.Logger;

import java.io.File;
import java.io.IOException;

/**
 * Reserves full remaining native output before each muxer operation. Native internal writes remain
 * opaque.
 */
final class CaptureMuxer implements LiveRemux.Mp4Writer {
    interface Open {
        LiveRemux.Mp4Writer open(File output) throws IOException;
    }

    private final RecordingRetention.Record record;
    private final CaptureFlv.Prefix prefix;
    private final File output;
    private final CaptureSpace budget = new CaptureSpace();
    private final CaptureSpace.Free free;
    private final LiveRemux.Mp4Writer delegate;
    private long samples, payload;

    CaptureMuxer(RecordingRetention.Record record, CaptureFlv.Prefix prefix) throws IOException {
        this(record, prefix, LiveRemux.MuxerWriter::new, File::getUsableSpace);
    }

    CaptureMuxer(
            RecordingRetention.Record record,
            CaptureFlv.Prefix prefix,
            Open open,
            CaptureSpace.Free free)
            throws IOException {
        if (!prefix.usable()) throw new LiveRemux.NothingRecorded("No video was recorded");
        this.record = record;
        this.prefix = prefix;
        this.free = free;
        output = record.file("remux.mp4");
        CaptureSpace.Footprint actual =
                CaptureJournal.footprint(record, 0, prefix.payloadBytes, prefix.samples);
        // Opening MediaMuxer truncates an old unacknowledged rewrite. Give no pre-write credit for
        // it.
        budget.nativeRemux(
                new CaptureSpace.Footprint(
                        actual.source,
                        0,
                        0,
                        Math.addExact(actual.retained, actual.remux),
                        actual.payload,
                        actual.samples),
                free.bytes(record.directory));
        delegate = open.open(output);
        try {
            check();
        } catch (IOException failure) {
            delegate.abort();
            throw failure;
        }
    }

    private void check() throws IOException {
        budget.nativeRemux(
                CaptureJournal.footprint(record, 0, prefix.payloadBytes, prefix.samples),
                free.bytes(record.directory));
    }

    @Override
    public int addVideoTrack(String mime, byte[] a, byte[] b, int width, int height)
            throws IOException {
        check();
        int value = delegate.addVideoTrack(mime, a, b, width, height);
        check();
        return value;
    }

    @Override
    public int addAudioTrack(byte[] specific, int rate, int channels) throws IOException {
        check();
        int value = delegate.addAudioTrack(specific, rate, channels);
        check();
        return value;
    }

    @Override
    public void start() throws IOException {
        check();
        delegate.start();
        check();
    }

    @Override
    public void write(int track, byte[] data, long pts, boolean key) throws IOException {
        if (samples >= prefix.samples || data.length > prefix.payloadBytes - payload)
            throw new IOException("Capture remux exceeded its scanned sample budget");
        check();
        delegate.write(track, data, pts, key);
        samples++;
        payload += data.length;
        check();
    }

    @Override
    public void endVideo(long presentationUs) throws IOException {
        check();
        delegate.endVideo(presentationUs);
        check();
    }

    @Override
    public void finish() throws IOException {
        if (samples != prefix.samples || payload != prefix.payloadBytes)
            throw new IOException("Capture remux did not consume its exact scanned prefix");
        check();
        delegate.finish();
        check();
    }

    @Override
    public void abort() {
        try {
            check();
        } catch (IOException failure) {
            Logger.printInfo(
                    () -> "Recording muxer cleanup has insufficient reserved headroom: " + failure);
        }
        // Releasing a native encoder/muxer cannot be skipped even if other storage users consumed
        // headroom.
        // No source receipt or ACK is written on this path.
        delegate.abort();
    }
}

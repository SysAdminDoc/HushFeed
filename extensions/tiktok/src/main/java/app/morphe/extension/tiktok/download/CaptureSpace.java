/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import java.io.IOException;

/**
 * Per-write headroom for the source, remux and publication copies; existing bytes already debit
 * free space.
 */
final class CaptureSpace {
    interface Free {
        long bytes(java.io.File directory);
    }

    // AOSP MPEG4Writer.Track.trackMetaDataSize uses at most 44 bytes/sample for these AVC/AAC
    // tables.
    // https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libstagefright/MPEG4Writer.cpp
    static final long SAMPLE_TABLE_BYTES = 64;
    static final long MAX_SAMPLES = (32L * 1024 * 1024) / SAMPLE_TABLE_BYTES;
    final long maxSourceBytes;

    CaptureSpace() {
        this(MediaBudget.MAX_LIVE_RECORDING_BYTES);
    }

    CaptureSpace(long maximum) {
        if (maximum < 1) throw new IllegalArgumentException("Invalid capture limit");
        maxSourceBytes = maximum;
    }

    static final class Limit extends IOException {
        final boolean size;

        Limit(boolean size, String message) {
            super(message);
            this.size = size;
        }
    }

    static final class Footprint {
        final long source, remux, published, retained, payload, samples;

        Footprint(
                long source, long remux, long published, long retained, long payload, long samples)
                throws IOException {
            if (source < 0
                    || remux < 0
                    || published < 0
                    || retained < 0
                    || payload < 0
                    || samples < 0) throw new IOException("Invalid recording footprint");
            this.source = source;
            this.remux = remux;
            this.published = published;
            this.retained = retained;
            this.payload = payload;
            this.samples = samples;
        }

        long actual() throws IOException {
            return sum(source, remux, published, retained);
        }
    }

    static final class Grant {
        final long actualOwnedBytes, projectedOwnedBytes, additionalBytes, requiredFreeBytes;

        Grant(long actual, long projected, long additional) throws IOException {
            actualOwnedBytes = actual;
            projectedOwnedBytes = projected;
            additionalBytes = additional;
            requiredFreeBytes = sum(additional, MediaBudget.MIN_FREE_BYTES);
        }

        Grant require(long available) throws IOException {
            if (available <= 0 || available < requiredFreeBytes)
                throw new Limit(false, "Not enough space for recording and its remaining copies");
            return this;
        }
    }

    Grant sourceWrite(Footprint now, int bytes, int payload, int samples, long free)
            throws IOException {
        if (bytes < 1 || payload < 0 || payload > bytes || samples < 0 || samples > 1)
            throw new IOException("Invalid capture write reservation");
        long nextSource = sum(now.source, bytes);
        if (nextSource > maxSourceBytes)
            throw new Limit(true, "The recording reached its size limit");
        long nextSamples = sum(now.samples, samples);
        if (nextSamples > MAX_SAMPLES)
            throw new Limit(true, "The recording reached its sample-table limit");
        long bound = remuxBound(sum(now.payload, payload), nextSamples);
        long future = sum(bytes, remaining(bound, now.remux), remaining(bound, now.published));
        return new Grant(
                        now.actual(),
                        sum(
                                now.retained,
                                nextSource,
                                Math.max(bound, now.remux),
                                Math.max(bound, now.published)),
                        future)
                .require(free);
    }

    Grant nativeRemux(Footprint now, long free) throws IOException {
        if (now.samples > MAX_SAMPLES)
            throw new Limit(true, "Recording sample tables exceed the capture recovery bound");
        long bound = remuxBound(now.payload, now.samples);
        if (now.remux > bound)
            throw new Limit(true, "Recording muxer exceeded its reserved output bound");
        return new Grant(
                        now.actual(),
                        sum(now.retained, now.source, bound, Math.max(bound, now.published)),
                        sum(remaining(bound, now.remux), remaining(bound, now.published)))
                .require(free);
    }

    Grant publicationWrite(Footprint now, long finalBytes, long copied, int bytes, long free)
            throws IOException {
        if (finalBytes < 1
                || copied < 0
                || copied > finalBytes
                || bytes < 1
                || bytes > finalBytes - copied
                || now.published != copied)
            throw new IOException("Recording publication exceeded its exact copy length");
        return new Grant(
                        now.actual(),
                        sum(now.retained, now.source, now.remux, finalBytes),
                        sum(finalBytes - copied, MediaBudget.PUBLISH_OVERHEAD_BYTES))
                .require(free);
    }

    static long remuxBound(long payload, long samples) throws IOException {
        if (payload < 0 || samples < 0)
            throw new IOException("Invalid recording sample accounting");
        try {
            return sum(
                    payload,
                    Math.multiplyExact(samples, SAMPLE_TABLE_BYTES),
                    MediaBudget.PUBLISH_OVERHEAD_BYTES);
        } catch (ArithmeticException overflow) {
            throw new IOException("Recording size accounting overflow", overflow);
        }
    }

    private static long remaining(long expected, long existing) {
        return Math.max(0, expected - existing);
    }

    private static long sum(long... values) throws IOException {
        long total = 0;
        try {
            for (long value : values) {
                if (value < 0) throw new IOException("Negative recording byte count");
                total = Math.addExact(total, value);
            }
            return total;
        } catch (ArithmeticException overflow) {
            throw new IOException("Recording size accounting overflow", overflow);
        }
    }
}

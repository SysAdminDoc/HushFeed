/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import app.morphe.extension.shared.Logger;

/** The two ways a LIVE's video reaches the recorder: one long FLV download, or HLS pieces polled. */
final class LiveStreamSources {
    private LiveStreamSources() {
    }

    /** Opens an address for reading. Closing the stream ends the request. */
    interface Http {
        InputStream open(String url) throws IOException;
    }

    /** The real thing: HTTPS only, public addresses only, like every other media download. */
    static final Http NETWORK = url -> {
        MediaBudget.Deadline deadline = MediaBudget.deadline();
        // A recording runs for as long as the viewer watches; the grant slides forward with it.
        deadline.allowAtLeast(24L * 60 * 60 * 1000);
        MediaTransport.Response response = MediaTransport.open(url, deadline, 10_000, 20_000, null, true);
        boolean handedOver = false;
        try {
            if (response.statusCode != 200) throw new IOException("The LIVE server answered " + response.statusCode);
            InputStream body = response.inputStream();
            handedOver = true;
            return new java.io.FilterInputStream(body) {
                @Override public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        response.close();
                    }
                }
            };
        } finally {
            if (!handedOver) response.close();
        }
    };

    /** MediaMuxer writes HEVC into an MP4 from Android 7.0 on; before that such a LIVE can't be saved. */
    static final boolean HEVC_WRITABLE = android.os.Build.VERSION.SDK_INT >= 24;

    private static final int BUFFER_BYTES = 32 * 1024;
    /** How much of an FLV stream is looked at to learn early that it can't be saved. */
    private static final int HEAD_BYTES = 48 * 1024;
    private static final long PLAYLIST_LIMIT_BYTES = 512 * 1024;
    private static final long SEGMENT_LIMIT_BYTES = 64L * 1024 * 1024;
    private static final int MAX_FAILURES = 5;
    private static final long STALL_NANOS = 45_000_000_000L;

    /**
     * A LIVE's flv address: the stream is one never-ending download, written to the spool as it
     * arrives. A connection that drops or stalls past the read timeout is opened again while the
     * viewer is still in the room, so a slow patch of internet no longer ends the recording
     * (#526). Only {@link #MAX_FAILURES} tries in a row that bring nothing end it, which is also
     * how a LIVE that really ended looks.
     */
    static final class Flv implements LiveRecorder.Source {
        private final Http http;
        private final String url;

        Flv(Http http, String url) {
            this.http = http;
            this.url = url;
        }

        @Override public void pump(LiveRecorder.Output out, LiveRecorder.Control control) throws IOException {
            FlvTags tags = new FlvTags(out);
            byte[] buffer = new byte[BUFFER_BYTES];
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            boolean checked = false;
            int failures = 0;
            while (!control.stopRequested()) {
                long before = tags.written();
                IOException lost = null;
                try {
                    InputStream in = http.open(url);
                    control.onStop(in);
                    try {
                        tags.newConnection();
                        // A download dropped before its head was looked at leaves part of a tag,
                        // and the next one's FLV header would land in the middle of it.
                        if (!checked) head.reset();
                        int count;
                        while ((count = in.read(buffer)) >= 0) {
                            if (control.stopRequested()) return;
                            if (!checked) {
                                head.write(buffer, 0, Math.min(count, HEAD_BYTES - head.size()));
                                if (head.size() >= HEAD_BYTES) {
                                    checked = true;
                                    checkHead(head.toByteArray(), HEVC_WRITABLE);
                                }
                            }
                            tags.write(buffer, 0, count);
                        }
                        if (!checked && head.size() > 0) {
                            checked = true;
                            checkHead(head.toByteArray(), HEVC_WRITABLE);
                        }
                    } finally {
                        control.release(in);
                        in.close();
                    }
                } catch (FlvReader.UnsupportedStream | LiveRecorder.SpoolFull fatal) {
                    throw fatal;
                } catch (IOException failure) {
                    if (control.stopRequested()) return;
                    lost = failure;
                    Logger.printDebug(() -> "LIVE recording connection lost, opening it again: " + failure);
                }
                if (tags.written() > before) {
                    failures = 0;
                } else if (++failures >= MAX_FAILURES) {
                    // A recording that never got a tag says why; one that did ends as the LIVE ending.
                    if (lost != null && tags.written() == 0) throw lost;
                    return;
                }
                if (!control.pause(failures == 0 ? 500 : 1000L * failures)) return;
            }
        }

        /**
         * Throws when the start of the stream shows it can't be turned into an MP4: a video format
         * the reader doesn't know, or HEVC on a phone whose muxer can't write it ({@code hevc} false).
         */
        static void checkHead(byte[] head, boolean hevc) throws IOException {
            try {
                FlvReader.read(new ByteArrayInputStream(head), new NoOpListener(), hevc);
            } catch (FlvReader.UnsupportedStream unsupported) {
                throw unsupported;
            } catch (IOException cutOff) {
                // The head ends mid-tag, which is only the slice looked at.
            }
        }
    }

    /**
     * Writes FLV downloads to the spool one whole tag at a time, so several connections join into
     * one readable FLV (#526). A connection cut off mid-tag leaves no half tag behind. The first
     * header goes through and later connections' headers are dropped. A tag whose time runs back,
     * or jumps far ahead, as a new connection's clock can, is moved on to just after the last one,
     * so the recording keeps running forward the way an MP4 needs.
     */
    static final class FlvTags {
        private static final int MAX_TAG_BYTES = 8 * 1024 * 1024;
        private static final int TAG_HEADER = 11;
        private static final int TAG_TRAILER = 4;
        private static final long JUMP_MS = 2_000;
        private static final long STEP_MS = 33;

        private final LiveRecorder.Output out;
        private byte[] buffer = new byte[64 * 1024];
        private int start;
        private int end;
        private boolean inHeader = true;
        private boolean headerWritten;
        private boolean freshConnection;
        private boolean rejoined;
        private long written;
        private boolean started;
        private long offset;
        private long highest;

        FlvTags(LiveRecorder.Output out) {
            this.out = out;
        }

        /** How many whole tags have gone to the spool. */
        long written() {
            return written;
        }

        /** A new download starts: whatever was left of the last one's tag is dropped. */
        void newConnection() {
            start = 0;
            end = 0;
            inHeader = true;
            freshConnection = started;
            rejoined = started;
        }

        void write(byte[] bytes, int from, int length) throws IOException {
            append(bytes, from, length);
            while (true) {
                int available = end - start;
                if (inHeader) {
                    if (available < 9) return;
                    if (buffer[start] != 'F' || buffer[start + 1] != 'L' || buffer[start + 2] != 'V') {
                        throw new IOException("Not an FLV stream");
                    }
                    long size = ((buffer[start + 5] & 0xFFL) << 24) | ((buffer[start + 6] & 0xFF) << 16)
                            | ((buffer[start + 7] & 0xFF) << 8) | (buffer[start + 8] & 0xFF);
                    if (size < 9 || size > 1024) throw new IOException("FLV header size " + size + " is not believable");
                    int total = (int) size + TAG_TRAILER;
                    if (available < total) return;
                    if (!headerWritten) {
                        out.write(buffer, start, total);
                        headerWritten = true;
                    }
                    start += total;
                    inHeader = false;
                    continue;
                }
                if (available < TAG_HEADER) return;
                int size = ((buffer[start + 1] & 0xFF) << 16) | ((buffer[start + 2] & 0xFF) << 8)
                        | (buffer[start + 3] & 0xFF);
                if (size > MAX_TAG_BYTES) throw new IOException("FLV tag size " + size + " is not believable");
                int total = TAG_HEADER + size + TAG_TRAILER;
                if (available < total) return;
                long raw = ((buffer[start + 4] & 0xFFL) << 16) | ((buffer[start + 5] & 0xFF) << 8)
                        | (buffer[start + 6] & 0xFF) | ((buffer[start + 7] & 0xFFL) << 24);
                // Settings and metadata tags come at time 0 on every connection, whatever the
                // stream's clock reads, so only samples decide whether that clock started over.
                long time = isSettings(start, size) ? (rejoined ? highest : raw) : map(raw);
                if (time != raw) {
                    buffer[start + 4] = (byte) (time >> 16);
                    buffer[start + 5] = (byte) (time >> 8);
                    buffer[start + 6] = (byte) time;
                    buffer[start + 7] = (byte) (time >> 24);
                }
                out.write(buffer, start, total);
                written++;
                start += total;
            }
        }

        /** A metadata tag, or the video or AAC settings a stream sends before its samples. */
        private boolean isSettings(int at, int size) {
            int type = buffer[at] & 0x1F;
            if (type == 18) return true;
            if (size < 2) return false;
            int first = buffer[at + TAG_HEADER] & 0xFF;
            int packet = buffer[at + TAG_HEADER + 1] & 0xFF;
            if (type == 9) return (first & 0x80) == 0 && packet == 0;
            return type == 8 && (first >> 4) == 10 && packet == 0;
        }

        private long map(long raw) {
            if (!started) {
                started = true;
                highest = raw;
                return raw;
            }
            long mapped = raw + offset;
            boolean restart = freshConnection && mapped <= highest;
            freshConnection = false;
            if (restart || mapped < highest - JUMP_MS || mapped > highest + 5 * JUMP_MS) {
                offset = highest + STEP_MS - raw;
                mapped = highest + STEP_MS;
            }
            mapped = Math.max(0, Math.min(0xFFFFFFFFL, mapped));
            if (mapped > highest) highest = mapped;
            return mapped;
        }

        private void append(byte[] bytes, int from, int length) {
            if (buffer.length - end < length) {
                int kept = end - start;
                if (buffer.length - kept < length) {
                    byte[] bigger = new byte[Math.max(buffer.length * 2, kept + length)];
                    System.arraycopy(buffer, start, bigger, 0, kept);
                    buffer = bigger;
                } else {
                    System.arraycopy(buffer, start, buffer, 0, kept);
                }
                start = 0;
                end = kept;
            }
            System.arraycopy(bytes, from, buffer, end, length);
            end += length;
        }
    }

    private static final class NoOpListener implements FlvReader.Listener {
        @Override public void onVideoConfig(String mime, byte[] csd0, byte[] csd1, int width, int height) { }

        @Override public void onAudioConfig(byte[] specific, int sampleRate, int channels) { }

        @Override public void onVideo(byte[] annexB, long presentationUs, boolean keyFrame) { }

        @Override public void onAudio(byte[] aac, long presentationUs) { }
    }

    /**
     * A LIVE's HLS address: the playlist is read again every few seconds and each new MPEG-TS piece
     * is fetched once and appended. What the playlist already listed when the recording began is
     * skipped, because a recording is of what the viewer watches from the moment they started it.
     */
    static final class Hls implements LiveRecorder.Source {
        private final Http http;
        private final String url;

        Hls(Http http, String url) {
            this.http = http;
            this.url = url;
        }

        @Override public void pump(LiveRecorder.Output out, LiveRecorder.Control control) throws IOException {
            String playlistUrl = url;
            HlsPlaylist.Follower follower = new HlsPlaylist.Follower();
            long lastFresh = System.nanoTime();
            int failures = 0;
            while (!control.stopRequested()) {
                try {
                    HlsPlaylist playlist = HlsPlaylist.parse(readText(control, playlistUrl), playlistUrl);
                    if (playlist.master) {
                        HlsPlaylist.Variant best = playlist.best();
                        if (best == null) throw new IOException("The LIVE's playlist lists no streams");
                        playlistUrl = best.uri;
                        continue;
                    }
                    if (playlist.encrypted || playlist.needsInitSection) {
                        throw new FlvReader.UnsupportedStream("This LIVE's stream can't be saved");
                    }
                    List<HlsPlaylist.Segment> fresh = follower.fresh(playlist);
                    boolean allFetched = true;
                    for (HlsPlaylist.Segment segment : fresh) {
                        if (control.stopRequested()) return;
                        try {
                            copy(control, segment.uri, out);
                            lastFresh = System.nanoTime();
                        } catch (FlvReader.UnsupportedStream | LiveRecorder.SpoolFull fatal) {
                            throw fatal;
                        } catch (IOException lost) {
                            // One piece missing is a short skip in the video; the rest still count.
                            if (control.stopRequested()) return;
                            allFetched = false;
                            if (++failures >= MAX_FAILURES) throw lost;
                        }
                    }
                    if (allFetched) failures = 0;
                    if (playlist.ended) return;
                    if (System.nanoTime() - lastFresh > STALL_NANOS) {
                        throw new IOException("The stream stopped sending video");
                    }
                } catch (FlvReader.UnsupportedStream unsupported) {
                    throw unsupported;
                } catch (LiveRecorder.SpoolFull full) {
                    throw full;
                } catch (IOException failure) {
                    if (control.stopRequested()) return;
                    if (++failures >= MAX_FAILURES) throw failure;
                }
                if (!control.pause(1500)) return;
            }
        }

        private String readText(LiveRecorder.Control control, String address) throws IOException {
            InputStream in = http.open(address);
            control.onStop(in);
            try {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    text.write(buffer, 0, count);
                    if (text.size() > PLAYLIST_LIMIT_BYTES) throw new IOException("The playlist is too large");
                }
                return text.toString("UTF-8");
            } finally {
                control.release(in);
                in.close();
            }
        }

        private void copy(LiveRecorder.Control control, String address, LiveRecorder.Output out) throws IOException {
            InputStream in = http.open(address);
            control.onStop(in);
            try {
                byte[] buffer = new byte[BUFFER_BYTES];
                long total = 0;
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    total += count;
                    if (total > SEGMENT_LIMIT_BYTES) throw new IOException("A piece of the stream is too large");
                    out.write(buffer, 0, count);
                }
            } finally {
                control.release(in);
                in.close();
            }
        }
    }
}

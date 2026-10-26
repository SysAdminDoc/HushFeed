/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A local AVC/AAC capture spool. A complete FLV tag, including its trailer, is a recovery edge. */
final class CaptureFlv {
    static final int MAX_TAG_BYTES = 8 * 1024 * 1024;
    static final int MAX_CONFIG_BYTES = 64 * 1024;
    static final int MAX_AUDIO_BYTES = 64 * 1024;
    static final int MAX_NALS = 256;
    private static final int[] RATES = {
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    };

    private CaptureFlv() {}

    interface Sink {
        default void configured(Config config) throws IOException {}

        default void ending(long presentationUs) throws IOException {}

        /** Reserve the entire write plus the future remux/publication copies before writing. */
        void append(byte[] bytes, int payloadBytes, int samples) throws IOException;

        /** Sync source bytes first, then commit this complete edge to its durable owner. */
        void checkpoint(long completeBytes, boolean ended) throws IOException;
    }

    static final class Config {
        final byte[] sps, pps, avc, aac;
        final int sampleRate, channels, width, height;
        final String fingerprint;

        Config(ByteBuffer csd0, ByteBuffer csd1, ByteBuffer specific, int sampleRate, int channels)
                throws IOException {
            List<byte[]> nals = new ArrayList<>();
            byte[] first = remaining(csd0, MAX_CONFIG_BYTES);
            if (first.length >= 7 && first[0] == 1) avcConfig(first, nals);
            else nals.addAll(nals(first));
            if (csd1 != null && csd1.hasRemaining())
                nals.addAll(nals(remaining(csd1, MAX_CONFIG_BYTES)));
            byte[] s = null, p = null;
            for (byte[] nal : nals) {
                int type = nal[0] & 31;
                if (type == 7) {
                    if (s != null && !Arrays.equals(s, nal))
                        throw new IOException("Multiple AVC SPS are unsupported");
                    s = nal;
                } else if (type == 8) {
                    if (p != null && !Arrays.equals(p, nal))
                        throw new IOException("Multiple AVC PPS are unsupported");
                    p = nal;
                } else throw new IOException("Unexpected AVC codec configuration NAL");
            }
            int[] dimensions = s == null ? null : FlvReader.SpsSize.of(s);
            if (s == null
                    || p == null
                    || s.length < 4
                    || p.length < 2
                    || (s[1] & 255) != 66
                    || dimensions == null)
                throw new IOException("Capture requires Baseline AVC SPS/PPS");
            width = dimensions[0];
            height = dimensions[1];
            if (s.length > 65535 || p.length > 65535 || s.length + p.length > MAX_CONFIG_BYTES)
                throw new IOException("Capture codec configuration is too large");
            if (first.length >= 7
                    && first[0] == 1
                    && (first[1] != s[1] || first[2] != s[2] || first[3] != s[3]))
                throw new IOException("AVC configuration profile disagrees with its SPS");
            sps = s;
            pps = p;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(1);
            out.write(s[1]);
            out.write(s[2]);
            out.write(s[3]);
            out.write(255);
            out.write(225);
            u16(out, s.length);
            out.write(s, 0, s.length);
            out.write(1);
            u16(out, p.length);
            out.write(p, 0, p.length);
            avc = out.toByteArray();
            aac = specific == null ? null : audioConfiguration(specific, sampleRate, channels);
            this.sampleRate = sampleRate;
            this.channels = channels;
            fingerprint = fingerprint(avc, aac);
        }
    }

    static final class Writer {
        private final Sink sink;
        final Config config;
        long bytes, payloadBytes, samples, videoSamples, audioSamples;
        private long lastUs = -1, lastVideoUs = -1, lastAudioUs = -1, lastCheckpointMs = -1000;
        private boolean ended;

        Writer(Sink sink, Config config) throws IOException {
            this.sink = sink;
            this.config = config;
            sink.configured(config);
            append(
                    new byte[] {
                        'F',
                        'L',
                        'V',
                        1,
                        (byte) (config.aac == null ? 1 : 5),
                        0,
                        0,
                        0,
                        9,
                        0,
                        0,
                        0,
                        0
                    },
                    0,
                    0);
            tag(9, 0, prefix(new byte[] {0x17, 0, 0, 0, 0}, config.avc), 0, false);
            if (config.aac != null)
                tag(8, 0, prefix(new byte[] {(byte) 0xAF, 0}, config.aac), 0, false);
        }

        boolean video(ByteBuffer buffer, int offset, int size, long presentationUs)
                throws IOException {
            List<byte[]> nals = nals(slice(buffer, offset, size, MAX_TAG_BYTES - 5));
            boolean idr = false, frame = false;
            ByteArrayOutputStream sample = new ByteArrayOutputStream(size + 16);
            for (byte[] nal : nals) {
                int type = nal[0] & 31;
                if (type == 1 || type == 5) {
                    checkSlice(nal);
                    frame = true;
                    idr |= type == 5;
                } else if (type == 7 && !Arrays.equals(nal, config.sps)
                        || type == 8 && !Arrays.equals(nal, config.pps))
                    throw new IOException("Capture AVC configuration changed");
                u32(sample, nal.length);
                sample.write(nal, 0, nal.length);
            }
            if (!frame) throw new IOException("Capture buffer has no AVC picture");
            if (videoSamples == 0 && !idr) return false;
            long time = time(presentationUs, true);
            tag(
                    9,
                    time,
                    prefix(
                            new byte[] {(byte) (idr ? 0x17 : 0x27), 1, 0, 0, 0},
                            sample.toByteArray()),
                    sample.size(),
                    true);
            videoSamples++;
            if (videoSamples == 1 || time - lastCheckpointMs >= 1000) checkpoint(time);
            return true;
        }

        void audio(ByteBuffer buffer, int offset, int size, long presentationUs)
                throws IOException {
            if (config.aac == null) throw new IOException("Unexpected capture audio");
            byte[] data = slice(buffer, offset, size, MAX_AUDIO_BYTES);
            if (data.length < 1
                    || data.length > 1 && (data[0] & 255) == 255 && (data[1] & 0xF6) == 0xF0)
                throw new IOException("Capture audio is not raw AAC");
            long time = time(presentationUs, false);
            tag(8, time, prefix(new byte[] {(byte) 0xAF, 1}, data), data.length, true);
            audioSamples++;
            if (time - lastCheckpointMs >= 1000 && videoSamples > 0) checkpoint(time);
        }

        void end() throws IOException {
            end(Math.max(0, lastUs));
        }

        void end(long presentationUs) throws IOException {
            if (ended) return;
            if (presentationUs < lastUs
                    || presentationUs < 0
                    || presentationUs / 1000 > 0x7FFF_FFFFL)
                throw new IOException("Invalid capture end timestamp");
            if (videoSamples > 0) {
                sink.ending(presentationUs);
                tag(9, presentationUs / 1000, new byte[] {0x17, 2, 0, 0, 0}, 0, false);
                ended = true;
            }
            sink.checkpoint(bytes, ended);
        }

        long lastPresentationUs() {
            return Math.max(0, lastUs);
        }

        void checkpoint() throws IOException {
            sink.checkpoint(bytes, ended);
        }

        private void checkpoint(long time) throws IOException {
            sink.checkpoint(bytes, ended);
            lastCheckpointMs = time;
        }

        private long time(long us, boolean video) throws IOException {
            long previous = video ? lastVideoUs : lastAudioUs;
            if (ended || us < 0 || us < previous || us / 1000 > 0x7FFF_FFFFL)
                throw new IOException(
                        "Capture track timestamps are not ordered or exceed FLV range");
            if (video) lastVideoUs = us;
            else lastAudioUs = us;
            lastUs = Math.max(lastUs, us);
            return us / 1000;
        }

        private void tag(int type, long time, byte[] body, int media, boolean sample)
                throws IOException {
            if (body.length > MAX_TAG_BYTES) throw new IOException("Capture tag is too large");
            ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 15);
            out.write(type);
            u24(out, body.length);
            u24(out, (int) time);
            out.write((int) (time >>> 24));
            u24(out, 0);
            out.write(body, 0, body.length);
            u32(out, 11 + body.length);
            append(out.toByteArray(), media, sample ? 1 : 0);
        }

        private void append(byte[] data, int media, int count) throws IOException {
            sink.append(data, media, count);
            bytes += data.length;
            payloadBytes += media;
            samples += count;
        }
    }

    static final class Prefix {
        final long completeBytes, payloadBytes, samples, videoSamples, audioSamples;
        final boolean ended, incomplete;
        final String configuration;
        final long endTimeUs;

        Prefix(long complete, Writer writer, boolean ended, long original, long endTimeUs) {
            completeBytes = complete;
            payloadBytes = writer == null ? 0 : writer.payloadBytes;
            samples = writer == null ? 0 : writer.samples;
            videoSamples = writer == null ? 0 : writer.videoSamples;
            audioSamples = writer == null ? 0 : writer.audioSamples;
            this.ended = ended;
            this.endTimeUs = endTimeUs;
            incomplete = !ended || complete != original;
            configuration = writer == null ? "" : writer.config.fingerprint;
        }

        boolean usable() {
            return videoSamples > 0;
        }
    }

    private static final Sink VALIDATE =
            new Sink() {
                @Override
                public void append(byte[] bytes, int payload, int samples) {}

                @Override
                public void checkpoint(long bytes, boolean ended) {}
            };

    /**
     * Scans only locally generated captures. Invalid or torn tails never consume the prior edge.
     */
    static Prefix prefix(File file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long original = in.length(), complete = 0;
            Writer writer = null;
            byte[] video = null, audio = null;
            boolean ended = false;
            long endTimeUs = 0;
            if (original < 13) return new Prefix(0, null, false, original, 0);
            byte[] header = new byte[13];
            in.readFully(header);
            if (!Arrays.equals(
                            header,
                            new byte[] {'F', 'L', 'V', 1, header[4], 0, 0, 0, 9, 0, 0, 0, 0})
                    || (header[4] != 1 && header[4] != 5))
                throw new IOException("Not a local capture FLV");
            boolean expectsAudio = header[4] == 5;
            complete = 13;
            while (in.getFilePointer() < original && !ended) {
                long start = in.getFilePointer();
                if (original - start < 11) break;
                int type = in.readUnsignedByte(), size = read24(in);
                long time = read24(in) | ((long) in.readUnsignedByte() << 24);
                int stream = read24(in);
                if (stream != 0
                        || size > MAX_TAG_BYTES
                        || original - in.getFilePointer() < size + 4L) break;
                byte[] body = new byte[size];
                in.readFully(body);
                if (in.readInt() != 11 + size) break;
                try {
                    if (writer == null) {
                        if (video == null
                                && type == 9
                                && size >= 12
                                && body[0] == 0x17
                                && body[1] == 0
                                && body[2] == 0
                                && body[3] == 0
                                && body[4] == 0
                                && time == 0) video = Arrays.copyOfRange(body, 5, size);
                        else if (video != null
                                && expectsAudio
                                && audio == null
                                && type == 8
                                && size == 4
                                && body[0] == (byte) 0xAF
                                && body[1] == 0
                                && time == 0) audio = Arrays.copyOfRange(body, 2, size);
                        else break;
                        if (video != null && (!expectsAudio || audio != null)) {
                            int rate =
                                    audio == null
                                            ? 0
                                            : RATES[(audio[0] & 7) << 1 | (audio[1] & 128) >>> 7];
                            int channels = audio == null ? 0 : (audio[1] >>> 3) & 15;
                            writer =
                                    new Writer(
                                            VALIDATE,
                                            new Config(
                                                    ByteBuffer.wrap(video),
                                                    null,
                                                    audio == null ? null : ByteBuffer.wrap(audio),
                                                    rate,
                                                    channels));
                        }
                    } else if (type == 9
                            && size > 5
                            && (body[0] == 0x17 || body[0] == 0x27)
                            && body[1] == 1
                            && body[2] == 0
                            && body[3] == 0
                            && body[4] == 0) {
                        long before = writer.videoSamples;
                        if ((body[0] == 0x17) != hasIdr(Arrays.copyOfRange(body, 5, size))
                                || !writer.video(ByteBuffer.wrap(body), 5, size - 5, time * 1000L))
                            break;
                        if (writer.videoSamples != before + 1) break;
                    } else if (type == 8 && size > 2 && body[0] == (byte) 0xAF && body[1] == 1)
                        writer.audio(ByteBuffer.wrap(body), 2, size - 2, time * 1000L);
                    else if (type == 9
                            && size == 5
                            && body[0] == 0x17
                            && body[1] == 2
                            && body[2] == 0
                            && body[3] == 0
                            && body[4] == 0
                            && writer.videoSamples > 0) {
                        if (time < Math.max(0, writer.lastUs / 1000) || time > 0x7FFF_FFFFL) break;
                        ended = true;
                        endTimeUs = time * 1000;
                    } else break;
                } catch (IOException | RuntimeException invalid) {
                    break;
                }
                complete = in.getFilePointer();
            }
            return new Prefix(complete, writer, ended, original, endTimeUs);
        }
    }

    private static boolean hasIdr(byte[] sample) throws IOException {
        for (byte[] nal : nals(sample)) if ((nal[0] & 31) == 5) return true;
        return false;
    }

    static byte[] audioConfiguration(ByteBuffer specific, int sampleRate, int channels)
            throws IOException {
        byte[] data = remaining(specific, 2);
        validateAac(data, sampleRate, channels);
        return data;
    }

    static void rawAudio(ByteBuffer buffer, int offset, int size) throws IOException {
        byte[] data = slice(buffer, offset, size, MAX_AUDIO_BYTES);
        if (data.length > 1 && (data[0] & 255) == 255 && (data[1] & 0xF6) == 0xF0)
            throw new IOException("Capture audio is not raw AAC");
    }

    private static void validateAac(byte[] data, int sampleRate, int channels) throws IOException {
        if (data.length != 2 || (data[0] & 255) >>> 3 != 2 || (data[1] & 7) != 0)
            throw new IOException("Capture requires AAC LC without extensions");
        int frequency = (data[0] & 7) << 1 | (data[1] & 128) >>> 7, count = (data[1] >>> 3) & 15;
        if (frequency >= RATES.length
                || RATES[frequency] != sampleRate
                || count != channels
                || channels < 1
                || channels > 2) throw new IOException("Capture AAC format changed");
    }

    private static byte[] remaining(ByteBuffer source, int maximum) throws IOException {
        if (source == null || source.remaining() < 1 || source.remaining() > maximum)
            throw new IOException("Invalid capture codec configuration");
        byte[] out = new byte[source.remaining()];
        source.duplicate().get(out);
        return out;
    }

    private static byte[] slice(ByteBuffer buffer, int offset, int size, int maximum)
            throws IOException {
        if (buffer == null
                || offset < 0
                || size < 1
                || size > maximum
                || offset > buffer.capacity() - size)
            throw new IOException("Invalid capture buffer range");
        ByteBuffer view = buffer.duplicate();
        view.clear();
        view.position(offset);
        view.limit(offset + size);
        byte[] out = new byte[size];
        view.get(out);
        return out;
    }

    private static List<byte[]> nals(byte[] data) throws IOException {
        List<byte[]> result = new ArrayList<>();
        if (startCode(data, 0) > 0) {
            int at = 0;
            while (at < data.length) {
                int prefix = startCode(data, at);
                if (prefix == 0) throw new IOException("Cut-off Annex B capture sample");
                int from = at + prefix, next = from;
                while (next < data.length && startCode(data, next) == 0) next++;
                int end = next;
                while (end > from && data[end - 1] == 0) end--;
                addNal(result, data, from, end - from);
                at = next;
            }
        } else {
            int at = 0;
            while (at < data.length) {
                if (data.length - at < 4) throw new IOException("Cut-off AVCC capture sample");
                long length =
                        ((long) (data[at] & 255) << 24)
                                | ((data[at + 1] & 255) << 16)
                                | ((data[at + 2] & 255) << 8)
                                | (data[at + 3] & 255);
                at += 4;
                if (length < 1 || length > data.length - at)
                    throw new IOException("Invalid AVCC capture NAL length");
                addNal(result, data, at, (int) length);
                at += (int) length;
            }
        }
        if (result.isEmpty()) throw new IOException("Empty capture NAL sample");
        return result;
    }

    private static void addNal(List<byte[]> result, byte[] data, int from, int size)
            throws IOException {
        if (size < 1 || result.size() >= MAX_NALS || (data[from] & 128) != 0)
            throw new IOException("Invalid capture NAL");
        int type = data[from] & 31;
        if (type < 1 || type > 12 || type == 2 || type == 3 || type == 4)
            throw new IOException("Unsupported capture NAL type");
        result.add(Arrays.copyOfRange(data, from, from + size));
    }

    private static int startCode(byte[] data, int at) {
        if (at + 3 <= data.length && data[at] == 0 && data[at + 1] == 0) {
            if (data[at + 2] == 1) return 3;
            if (at + 4 <= data.length && data[at + 2] == 0 && data[at + 3] == 1) return 4;
        }
        return 0;
    }

    private static void avcConfig(byte[] data, List<byte[]> result) throws IOException {
        if ((data[4] & 255) != 255 || (data[5] & 224) != 224)
            throw new IOException("Capture requires four-byte AVCC lengths");
        int at = 6, count = data[5] & 31;
        for (int pass = 0; pass < 2; pass++) {
            if (count == 0) throw new IOException("Missing capture AVC configuration");
            for (int i = 0; i < count; i++) {
                if (data.length - at < 2)
                    throw new IOException("Cut-off capture AVC configuration");
                int length = (data[at] & 255) << 8 | (data[at + 1] & 255);
                at += 2;
                if (length < 1 || length > data.length - at)
                    throw new IOException("Cut-off capture AVC configuration");
                addNal(result, data, at, length);
                at += length;
            }
            if (pass == 0) {
                if (at >= data.length) throw new IOException("Missing capture PPS count");
                count = data[at++] & 255;
            }
        }
        if (at != data.length)
            throw new IOException("Unexpected capture AVC configuration extension");
    }

    private static void checkSlice(byte[] nal) throws IOException {
        ByteArrayOutputStream clean = new ByteArrayOutputStream();
        int zeros = 0;
        for (int i = 1; i < nal.length; i++) {
            int value = nal[i] & 255;
            if (zeros >= 2 && value == 3) {
                if (i + 1 >= nal.length || (nal[i + 1] & 255) > 3)
                    throw new IOException("Invalid capture emulation prevention");
                zeros = 0;
                continue;
            }
            if (zeros >= 2 && value < 3) throw new IOException("Unescaped capture slice bytes");
            clean.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        byte[] data = clean.toByteArray();
        int[] bit = {0};
        ue(data, bit);
        int type = ue(data, bit);
        if (type > 9 || type % 5 == 1)
            throw new IOException("B-frame capture requires decode timestamps");
        if (type % 5 > 2 || (nal[0] & 31) == 5 && type % 5 != 2)
            throw new IOException("Capture slice is not a Baseline I/P picture");
    }

    private static int ue(byte[] data, int[] position) throws IOException {
        int zeros = 0;
        while (readBit(data, position) == 0)
            if (++zeros > 30) throw new IOException("Invalid capture slice header");
        int value = 0;
        for (int i = 0; i < zeros; i++) value = (value << 1) | readBit(data, position);
        return ((1 << zeros) - 1) + value;
    }

    private static int readBit(byte[] data, int[] position) throws IOException {
        int bit = position[0]++;
        if (bit >= data.length * 8) throw new IOException("Cut-off capture slice header");
        return (data[bit / 8] >>> (7 - bit % 8)) & 1;
    }

    private static String fingerprint(byte[] avc, byte[] aac) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(avc);
            digest.update((byte) 0);
            if (aac != null) digest.update(aac);
            StringBuilder text = new StringBuilder();
            for (byte value : digest.digest())
                text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    private static byte[] prefix(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static int read24(RandomAccessFile in) throws IOException {
        return in.readUnsignedByte() << 16 | in.readUnsignedByte() << 8 | in.readUnsignedByte();
    }

    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    private static void u24(ByteArrayOutputStream out, int value) {
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private static void u32(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 8));
        out.write((int) value);
    }
}

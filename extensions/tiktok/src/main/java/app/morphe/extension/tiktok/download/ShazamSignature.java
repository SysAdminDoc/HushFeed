/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Ported from SongRec's Rust signature generator and signature format
 * (https://github.com/marin-m/SongRec, GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

/**
 * A Shazam fingerprint of 16 kHz mono samples, as Shazam's own app sends it: no audio, only the
 * peaks of a spectrogram, grouped in four frequency bands.
 *
 * <p>Ported from SongRec (Marin M., GPL-3.0, https://github.com/marin-m/SongRec), whose Rust
 * {@code SignatureGenerator} this follows step for step, and whose {@code signature_format}
 * gives the binary layout. Every 128 samples, a 2048 sample window under a Hann curve goes
 * through an FFT; each spectrum is spread over its neighbours in frequency and time, and a bin
 * that stands above its spread neighbours 46 windows back is a peak.
 */
final class ShazamSignature {
    static final int SAMPLE_RATE = 16_000;
    static final String URI_PREFIX = "data:audio/vnd.shazam.sig;base64,";
    /** 250 to 520 Hz, 520 to 1450, 1450 to 3500 and 3500 to 5500. Nothing else is stored. */
    static final int BANDS = 4;

    private static final int WINDOW = 2048;
    private static final int BINS = WINDOW / 2 + 1;
    private static final int HOP = 128;
    private static final int RING = 256;
    private static final int MAGIC1 = 0xCAFE2580;
    private static final int MAGIC2 = 0x94119C00;
    private static final int HEADER_BYTES = 48;
    private static final int BAND_TAG = 0x60030040;
    /** SampleRate._16000 is 3 in SongRec's table, stored shifted left by 27. */
    private static final int SAMPLE_RATE_ID = 3 << 27;
    private static final int FIXED_VALUE = (15 << 19) + 0x40000;
    private static final int[] FREQUENCY_NEIGHBOURS = {-10, -7, -4, -3, 1, 2, 5, 8};
    private static final int[] TIME_NEIGHBOURS =
            {-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249};

    /** A Hann window of 2050 points without its two zero ends, as numpy's hanning(2050)[1:-1]. */
    private static final float[] HANN = new float[WINDOW];
    private static final int[] BIT_REVERSED = new int[WINDOW];
    private static final double[] COS = new double[WINDOW / 2];
    private static final double[] SIN = new double[WINDOW / 2];

    static {
        for (int i = 0; i < WINDOW; i++) {
            HANN[i] = (float) (0.5 * (1 - Math.cos(2 * Math.PI * (i + 1) / (WINDOW + 1))));
        }
        int bits = Integer.numberOfTrailingZeros(WINDOW);
        for (int i = 0; i < WINDOW; i++) BIT_REVERSED[i] = Integer.reverse(i) >>> (32 - bits);
        for (int i = 0; i < WINDOW / 2; i++) {
            COS[i] = Math.cos(-2 * Math.PI * i / WINDOW);
            SIN[i] = Math.sin(-2 * Math.PI * i / WINDOW);
        }
    }

    /** One spectrogram peak: the window it was found in, its loudness and its frequency times 64. */
    static final class Peak {
        final int pass;
        final int magnitude;
        final int bin;

        Peak(int pass, int magnitude, int bin) {
            this.pass = pass;
            this.magnitude = magnitude;
            this.bin = bin;
        }

        double hertz() {
            return bin * (SAMPLE_RATE / 2.0 / 1024 / 64);
        }
    }

    final int numberSamples;
    final List<List<Peak>> bands;

    private ShazamSignature(int numberSamples, List<List<Peak>> bands) {
        this.numberSamples = numberSamples;
        this.bands = bands;
    }

    int peakCount() {
        int count = 0;
        for (List<Peak> band : bands) count += band.size();
        return count;
    }

    /** The length the fingerprint says it covers, in milliseconds, as the request reports it. */
    long sampleMillis() {
        return (long) ((float) numberSamples / SAMPLE_RATE * 1000f);
    }

    /** The fingerprint of {@code samples}, 16 kHz mono. A partial last 128 samples is left out, as SongRec does. */
    static ShazamSignature of(short[] samples) {
        return new Generator(samples.length).run(samples);
    }

    String uri() {
        return URI_PREFIX + Base64.encodeToString(encode(), Base64.NO_WRAP);
    }

    /** SongRec's DecodedMessage.encode_to_binary: a 48 byte header, then each band's peaks. */
    byte[] encode() {
        ByteArrayOutputStream contents = new ByteArrayOutputStream();
        for (int band = 0; band < BANDS; band++) {
            List<Peak> peaks = bands.get(band);
            if (peaks.isEmpty()) continue;
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            int pass = 0;
            for (Peak peak : peaks) {
                if (peak.pass < pass) throw new IllegalStateException("Peaks out of order");
                if (peak.pass - pass >= 255) {
                    packed.write(0xFF);
                    writeInt(packed, peak.pass);
                    pass = peak.pass;
                }
                packed.write(peak.pass - pass);
                writeShort(packed, peak.magnitude);
                writeShort(packed, peak.bin);
                pass = peak.pass;
            }
            writeInt(contents, BAND_TAG + band);
            writeInt(contents, packed.size());
            byte[] bytes = packed.toByteArray();
            contents.write(bytes, 0, bytes.length);
            for (int pad = -bytes.length & 3; pad > 0; pad--) contents.write(0);
        }

        int size = contents.size() + 8;
        ByteBuffer message = ByteBuffer.allocate(HEADER_BYTES + size).order(ByteOrder.LITTLE_ENDIAN);
        message.putInt(MAGIC1).putInt(0).putInt(size).putInt(MAGIC2);
        message.putInt(0).putInt(0).putInt(0);
        message.putInt(SAMPLE_RATE_ID);
        message.putInt(0).putInt(0);
        message.putInt((int) (numberSamples + SAMPLE_RATE * 0.24));
        message.putInt(FIXED_VALUE);
        message.putInt(0x40000000).putInt(size);
        message.put(contents.toByteArray());
        byte[] bytes = message.array();
        CRC32 crc = new CRC32();
        crc.update(bytes, 8, bytes.length - 8);
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) crc.getValue());
        return bytes;
    }

    /** SongRec's decode_from_uri, which reads back what {@link #uri} writes. */
    static ShazamSignature fromUri(String uri) throws IOException {
        if (uri == null || !uri.startsWith(URI_PREFIX)) throw new IOException("Not a Shazam signature");
        try {
            return decode(Base64.decode(uri.substring(URI_PREFIX.length()), Base64.DEFAULT));
        } catch (IllegalArgumentException notBase64) {
            throw new IOException("The signature isn't base64", notBase64);
        }
    }

    static ShazamSignature decode(byte[] data) throws IOException {
        if (data.length < HEADER_BYTES + 8) throw new IOException("The signature is too short");
        ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        CRC32 crc = new CRC32();
        crc.update(data, 8, data.length - 8);
        if (in.getInt(0) != MAGIC1 || in.getInt(12) != MAGIC2) throw new IOException("Not a Shazam signature");
        if (in.getInt(4) != (int) crc.getValue()) throw new IOException("The signature's checksum is wrong");
        if (in.getInt(8) != data.length - HEADER_BYTES) throw new IOException("The signature's size is wrong");
        if (in.getInt(28) >>> 27 != 3) throw new IOException("The signature isn't 16 kHz");
        int numberSamples = (int) (in.getInt(40) - SAMPLE_RATE * 0.24);
        in.position(HEADER_BYTES);
        if (in.getInt() != 0x40000000 || in.getInt() != data.length - HEADER_BYTES) {
            throw new IOException("The signature's first chunk is wrong");
        }
        List<List<Peak>> bands = emptyBands();
        while (in.remaining() >= 8) {
            int band = in.getInt() - BAND_TAG;
            int length = in.getInt();
            if (band < 0 || band >= BANDS || length < 0 || length > in.remaining()) {
                throw new IOException("The signature has a bad band");
            }
            int end = in.position() + length;
            int pass = 0;
            while (in.position() < end) {
                int offset = in.get() & 0xFF;
                if (offset == 0xFF) {
                    pass = in.getInt();
                    continue;
                }
                pass += offset;
                int magnitude = in.getShort() & 0xFFFF;
                int bin = in.getShort() & 0xFFFF;
                bands.get(band).add(new Peak(pass, magnitude, bin));
            }
            in.position(Math.min(in.limit(), end + (-length & 3)));
        }
        return new ShazamSignature(numberSamples, freeze(bands));
    }

    private static List<List<Peak>> emptyBands() {
        List<List<Peak>> bands = new ArrayList<>(BANDS);
        for (int band = 0; band < BANDS; band++) bands.add(new ArrayList<>());
        return bands;
    }

    private static List<List<Peak>> freeze(List<List<Peak>> bands) {
        List<List<Peak>> frozen = new ArrayList<>(BANDS);
        for (List<Peak> band : bands) frozen.add(Collections.unmodifiableList(band));
        return Collections.unmodifiableList(frozen);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
    }

    /** SongRec's SignatureGenerator, one run over one buffer. */
    private static final class Generator {
        private final short[] ring = new short[WINDOW];
        private int ringIndex;
        private final double[] re = new double[WINDOW];
        private final double[] im = new double[WINDOW];
        private final float[][] fftOutputs = new float[RING][BINS];
        private int fftIndex;
        private final float[][] spreadOutputs = new float[RING][BINS];
        private int spreadIndex;
        private int spreadDone;
        private final int numberSamples;
        private final List<List<Peak>> bands = emptyBands();

        Generator(int numberSamples) {
            this.numberSamples = numberSamples;
        }

        ShazamSignature run(short[] samples) {
            for (int start = 0; start + HOP <= samples.length; start += HOP) {
                fft(samples, start);
                spread();
                spreadDone++;
                if (spreadDone >= 46) recognize();
            }
            return new ShazamSignature(numberSamples, freeze(bands));
        }

        private void fft(short[] samples, int start) {
            System.arraycopy(samples, start, ring, ringIndex, HOP);
            ringIndex = (ringIndex + HOP) & (WINDOW - 1);
            // The oldest sample first, so the window reads the ring in time order.
            for (int i = 0; i < WINDOW; i++) {
                int from = BIT_REVERSED[i];
                re[i] = (float) ring[(from + ringIndex) & (WINDOW - 1)] * HANN[from];
                im[i] = 0;
            }
            for (int size = 2; size <= WINDOW; size <<= 1) {
                int half = size >> 1;
                int step = WINDOW / size;
                for (int at = 0; at < WINDOW; at += size) {
                    for (int k = 0; k < half; k++) {
                        double cos = COS[k * step];
                        double sin = SIN[k * step];
                        int even = at + k;
                        int odd = even + half;
                        double oddRe = re[odd] * cos - im[odd] * sin;
                        double oddIm = re[odd] * sin + im[odd] * cos;
                        re[odd] = re[even] - oddRe;
                        im[odd] = im[even] - oddIm;
                        re[even] += oddRe;
                        im[even] += oddIm;
                    }
                }
            }
            float[] out = fftOutputs[fftIndex];
            for (int bin = 0; bin < BINS; bin++) {
                double power = (re[bin] * re[bin] + im[bin] * im[bin]) / (1 << 17);
                out[bin] = (float) Math.max(power, 0.0000000001);
            }
            fftIndex = (fftIndex + 1) & (RING - 1);
        }

        private void spread() {
            float[] latest = fftOutputs[(fftIndex - 1) & (RING - 1)];
            float[] spread = spreadOutputs[spreadIndex];
            System.arraycopy(latest, 0, spread, 0, BINS);
            for (int bin = 0; bin <= BINS - 3; bin++) {
                spread[bin] = Math.max(spread[bin], Math.max(spread[bin + 1], spread[bin + 2]));
            }
            for (int former : new int[]{1, 3, 6}) {
                float[] earlier = spreadOutputs[(spreadIndex - former) & (RING - 1)];
                for (int bin = 0; bin < BINS; bin++) earlier[bin] = Math.max(earlier[bin], spread[bin]);
            }
            spreadIndex = (spreadIndex + 1) & (RING - 1);
        }

        private void recognize() {
            float[] minus46 = fftOutputs[(fftIndex - 46) & (RING - 1)];
            float[] minus49 = spreadOutputs[(spreadIndex - 49) & (RING - 1)];
            for (int bin = 10; bin <= 1014; bin++) {
                float value = minus46[bin];
                if (value < 1f / 64 || value < minus49[bin - 1]) continue;
                float neighbours = 0;
                for (int offset : FREQUENCY_NEIGHBOURS) neighbours = Math.max(neighbours, minus49[bin + offset]);
                if (value <= neighbours) continue;
                float around = neighbours;
                for (int offset : TIME_NEIGHBOURS) {
                    around = Math.max(around, spreadOutputs[(spreadIndex + offset) & (RING - 1)][bin - 1]);
                }
                if (value <= around) continue;

                float magnitude = loudness(value);
                float before = loudness(minus46[bin - 1]);
                float after = loudness(minus46[bin + 1]);
                float variation1 = magnitude * 2 - before - after;
                if (!(variation1 >= 0)) continue;
                float variation2 = (after - before) * 32 / variation1;
                int corrected = (bin * 64 + (int) variation2) & 0xFFFF;
                int hertz = (int) (corrected * (SAMPLE_RATE / 2f / 1024f / 64f));
                int band;
                if (hertz >= 250 && hertz <= 519) band = 0;
                else if (hertz >= 520 && hertz <= 1449) band = 1;
                else if (hertz >= 1450 && hertz <= 3499) band = 2;
                else if (hertz >= 3500 && hertz <= 5500) band = 3;
                else continue;
                int stored = (int) Math.max(0f, Math.min(65535f, magnitude));
                bands.get(band).add(new Peak(spreadDone - 46, stored, corrected));
            }
        }

        private static float loudness(float power) {
            return Math.max((float) Math.log(power), 1f / 64) * 1477.3f + 6144f;
        }
    }
}

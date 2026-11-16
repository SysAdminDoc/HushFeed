/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import app.morphe.extension.shared.Logger;

/**
 * A LIVE room's sound for LIVE name alerts (#424). The room's FLV stream is pulled a second time,
 * the audio-only one where the room has it, and its AAC is decoded into the 16 kHz mono PCM that
 * speech recognition reads. Nothing is kept: each piece goes to the sink and is dropped.
 */
public final class LiveSpeechAudio {
    /** The rate the sink gets, as 16-bit little-endian mono. */
    public static final int RATE = 16_000;

    /** Where the sound goes. Called on the audio thread. */
    public interface Sink {
        void pcm(byte[] data, int length);
    }

    /** Dropped streams in a row, each with no sound out of it, before the room is given up on. */
    static final int MAX_RETRIES = 4;
    private static final long RETRY_MS = 2_000;

    private final LiveStreamSources.Http http;
    private final String url;
    private final Sink sink;
    private final Thread thread;
    private volatile boolean stopped;
    private volatile InputStream reading;

    LiveSpeechAudio(LiveStreamSources.Http http, String url, Sink sink) {
        this.http = http;
        this.url = url;
        this.sink = sink;
        thread = new Thread(this::run, "hushfeed-live-listen");
        thread.setDaemon(true);
    }

    /** Starts pulling {@code url}, an https FLV address, into {@code sink}. */
    public static LiveSpeechAudio start(String url, Sink sink) {
        LiveSpeechAudio audio = new LiveSpeechAudio(LiveStreamSources.NETWORK, url, sink);
        audio.thread.start();
        return audio;
    }

    /** Ends the download. Safe from any thread, more than once. */
    public void stop() {
        stopped = true;
        InputStream in = reading;
        if (in != null) {
            try {
                in.close();
            } catch (IOException | RuntimeException ignored) {
                // Closed already.
            }
        }
        thread.interrupt();
    }

    void run() {
        int emptyInARow = 0;
        while (!stopped) {
            Decoder decoder = new Decoder(sink);
            try {
                InputStream in = http.open(url);
                reading = in;
                try {
                    if (!stopped) FlvReader.readSound(in, decoder);
                } finally {
                    reading = null;
                    in.close();
                }
            } catch (IOException | RuntimeException failure) {
                if (stopped) return;
                Logger.printDebug(() -> "LIVE listen: the room's sound stopped: " + failure);
            } finally {
                decoder.release();
            }
            emptyInARow = decoder.bytes == 0 ? emptyInARow + 1 : 0;
            if (emptyInARow > MAX_RETRIES) {
                Logger.printInfo(() -> "LIVE listen: no sound came from this room's stream");
                return;
            }
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException stop) {
                return;
            }
        }
    }

    /** Decodes the stream's AAC as it arrives and hands the sink 16 kHz mono. */
    static final class Decoder implements FlvReader.Listener {
        private final Sink sink;
        private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        private MediaCodec codec;
        private Resampler resampler;
        private int rate;
        private int channels;
        private short[] samples = new short[0];
        private byte[] out = new byte[0];
        long bytes;

        Decoder(Sink sink) {
            this.sink = sink;
        }

        @Override public void onVideoConfig(String mime, byte[] csd0, byte[] csd1, int width, int height) {
        }

        @Override public void onVideo(byte[] annexB, long presentationUs, boolean keyFrame) {
        }

        @Override public void onAudioConfig(byte[] specific, int sampleRate, int channelCount) throws IOException {
            // Some servers send the settings again; the decoder already running keeps going.
            if (codec != null) return;
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(specific));
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            try {
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                codec.configure(format, null, null, 0);
                codec.start();
            } catch (IOException | RuntimeException failure) {
                release();
                throw new IOException("No AAC decoder for the LIVE's sound", failure);
            }
            use(sampleRate, channelCount);
        }

        @Override public void onAudio(byte[] aac, long presentationUs) {
            MediaCodec running = codec;
            if (running == null) return;
            int index = running.dequeueInputBuffer(20_000);
            if (index >= 0) {
                ByteBuffer input = running.getInputBuffer(index);
                if (input != null && aac.length <= input.capacity()) {
                    input.clear();
                    input.put(aac);
                    running.queueInputBuffer(index, 0, aac.length, presentationUs, 0);
                } else {
                    running.queueInputBuffer(index, 0, 0, presentationUs, 0);
                }
            }
            drain(running);
        }

        private void drain(MediaCodec running) {
            while (true) {
                int index = running.dequeueOutputBuffer(info, 0);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = running.getOutputFormat();
                    use(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    continue;
                }
                if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue;
                if (index < 0) return;
                ByteBuffer output = running.getOutputBuffer(index);
                if (output != null && info.size > 1) {
                    output.position(info.offset).limit(info.offset + info.size);
                    int count = info.size / 2;
                    if (samples.length < count) samples = new short[count];
                    output.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples, 0, count);
                    feed(samples, count);
                }
                running.releaseOutputBuffer(index, false);
            }
        }

        void use(int sampleRate, int channelCount) {
            if (resampler != null && sampleRate == rate && channelCount == channels) return;
            rate = sampleRate;
            channels = channelCount;
            resampler = new Resampler(sampleRate, channelCount);
        }

        void feed(short[] pcm, int count) {
            Resampler running = resampler;
            if (running == null || count <= 0) return;
            short[] speech = running.resample(pcm, count);
            int length = speech.length * 2;
            if (length == 0) return;
            if (out.length < length) out = new byte[length];
            for (int i = 0; i < speech.length; i++) {
                out[2 * i] = (byte) speech[i];
                out[2 * i + 1] = (byte) (speech[i] >> 8);
            }
            bytes += length;
            sink.pcm(out, length);
        }

        void release() {
            MediaCodec running = codec;
            codec = null;
            if (running == null) return;
            try {
                running.stop();
            } catch (RuntimeException ignored) {
                // Never started, or stopped already.
            }
            try {
                running.release();
            } catch (RuntimeException ignored) {
                // Released already.
            }
        }
    }

    /**
     * Turns interleaved 16-bit PCM at any rate and channel count into 16 kHz mono, one piece at a
     * time. The channels are averaged and a low-pass filter takes off what 16 kHz can't hold,
     * since what's above it would fold back over the voices, then {@link PcmResampler} changes
     * the rate. The filter carries over from piece to piece, so pieces join without a seam.
     */
    static final class Resampler {
        private final int channels;
        private final boolean filtered;
        private final PcmResampler rate;
        private final double b0;
        private final double b1;
        private final double b2;
        private final double a1;
        private final double a2;
        private double x1;
        private double x2;
        private double y1;
        private double y2;
        private boolean primed;
        private short[] mono = new short[0];

        Resampler(int inputRate, int channelCount) {
            channels = Math.max(1, channelCount);
            int from = inputRate > 0 ? inputRate : RATE;
            filtered = from > RATE;
            rate = new PcmResampler(from, RATE, 1);
            // A Butterworth low-pass (the RBJ cookbook's), under the 8 kHz that 16 kHz can carry.
            double cutoff = 7_000;
            double w0 = 2 * Math.PI * Math.min(cutoff, from * 0.45) / from;
            double alpha = Math.sin(w0) / (2 * Math.sqrt(0.5));
            double cos = Math.cos(w0);
            double a0 = 1 + alpha;
            b0 = (1 - cos) / 2 / a0;
            b1 = (1 - cos) / a0;
            b2 = (1 - cos) / 2 / a0;
            a1 = -2 * cos / a0;
            a2 = (1 - alpha) / a0;
        }

        /** The 16 kHz mono samples for {@code count} more interleaved input samples. */
        short[] resample(short[] in, int count) {
            int frames = count / channels;
            if (frames <= 0) return new short[0];
            if (mono.length < frames) mono = new short[frames];
            for (int frame = 0; frame < frames; frame++) {
                double sum = 0;
                int at = frame * channels;
                for (int channel = 0; channel < channels; channel++) sum += in[at + channel];
                double x = sum / channels;
                if (filtered) {
                    if (!primed) {
                        // The filter starts settled on the first sample, so the opening doesn't click.
                        x1 = x2 = y1 = y2 = x;
                        primed = true;
                    }
                    double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                    x2 = x1;
                    x1 = x;
                    y2 = y1;
                    y1 = y;
                    x = y;
                }
                long sample = Math.round(x);
                mono[frame] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
            }
            return rate.process(mono, frames, false);
        }
    }
}

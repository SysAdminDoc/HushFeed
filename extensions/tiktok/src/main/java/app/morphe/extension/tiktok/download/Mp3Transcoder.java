/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

import de.sciss.jump3r.mp3.BitStream;
import de.sciss.jump3r.mp3.GainAnalysis;
import de.sciss.jump3r.mp3.ID3Tag;
import de.sciss.jump3r.mp3.Lame;
import de.sciss.jump3r.mp3.LameGlobalFlags;
import de.sciss.jump3r.mp3.Presets;
import de.sciss.jump3r.mp3.Quantize;
import de.sciss.jump3r.mp3.QuantizePVT;
import de.sciss.jump3r.mp3.Reservoir;
import de.sciss.jump3r.mp3.Takehiro;
import de.sciss.jump3r.mp3.VBRTag;
import de.sciss.jump3r.mp3.Version;
import de.sciss.jump3r.mpg.Common;
import de.sciss.jump3r.mpg.Interface;
import de.sciss.jump3r.mpg.MPGLib;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Turns a sound into an MP3: decoded by whatever decoder its track asks for, then encoded by
 * jump3r, a pure Java port of LAME. Android has an MP3 decoder and no MP3 encoder at any
 * version, so this has no codec to ask and no muxer to start. The MP3 stream is plain frames
 * written straight to the file, which is also why it works on every Android version the
 * decoder does.
 *
 * <p>Robolectric has no real codecs, so the encoding itself is {@link Encoder}, which takes
 * samples from anywhere. What's left in {@link #transcode} is the order the decoder calls go
 * in, the loop {@link OpusTranscoder} runs.
 */
final class Mp3Transcoder {
    static final String EXTENSION = "mp3";
    static final String MIME_TYPE = "audio/mpeg";
    private static final long TIMEOUT_US = 10_000L;
    /** Turns in a row with nothing moving, each with a short wait, before the sound counts as stuck. */
    private static final int MAX_IDLE_TURNS = 500;
    /** android.media.AudioFormat.ENCODING_PCM_16BIT, the only sample shape this takes. */
    private static final int PCM_16BIT = 2;
    /** Sample frames handed to the encoder at a time, so its work arrays stay small. */
    private static final int CHUNK_FRAMES = 8192;
    /** LAME's own advice for the size of the buffer a call may fill, plus the flush's. */
    private static final int FLUSH_BYTES = 7200;
    /** LAME's quality scale runs 0 (best, slowest) to 9; 5 is its "good" and keeps a phone quick. */
    private static final int QUALITY = 5;

    private Mp3Transcoder() {}

    /**
     * A constant bit rate that suits the sound. 192 kbps for stereo is transparent to nearly
     * everyone and above the 128 kbps AAC TikTok serves, so the file doesn't lose to its source.
     * The lower MPEG sample rates top out at 160 kbps, hence the lower picks under 32 kHz.
     */
    static int bitRateKbps(int sampleRate, int channels) {
        if (sampleRate >= 32_000) return channels == 1 ? 128 : 192;
        return channels == 1 ? 96 : 128;
    }

    /**
     * True when {@code head} starts like an MP3: an ID3 tag, or an MPEG frame sync with a layer.
     * ADTS AAC shares the sync and leaves the two layer bits at zero, which MPEG audio never does.
     */
    static boolean looksLikeMp3(byte[] head, int length) {
        if (length < 3) return false;
        if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') return true;
        return (head[0] & 255) == 255 && (head[1] & 0xE0) == 0xE0 && (head[1] & 0x06) != 0;
    }

    /** True when {@code file} is an MP3 already, so there's nothing to encode. */
    static boolean isMp3(File file) {
        byte[] head = new byte[4];
        try (InputStream input = new FileInputStream(file)) {
            int read = input.read(head);
            return looksLikeMp3(head, Math.max(read, 0));
        } catch (IOException unreadable) {
            Logger.printInfo(() -> "Could not read the sound's header: " + unreadable);
            return false;
        }
    }

    /**
     * {@link #transcode}, or false when it failed, so the caller saves the sound as TikTok sent it
     * rather than losing the save to a decoder this phone gets wrong. The reader is told why the
     * file isn't an .mp3. A refusal from the budget (no room, out of time) still throws, since the
     * other format wouldn't get through either.
     */
    static boolean transcodeOrKeep(File source, File output) throws MediaBudget.StopException {
        try {
            transcode(source, output);
            return true;
        } catch (MediaBudget.StopException refusal) {
            throw refusal;
        } catch (IOException | RuntimeException failure) {
            Logger.printException(() -> "MP3 encode failed, keeping the sound as TikTok sent it", failure);
            Utils.showToastLong(L10n.t("MP3 didn't work. Saving TikTok's own file."));
            return false;
        }
    }

    static void transcode(File source, File output) throws IOException {
        MediaBudget.check(null);
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        Encoder encoder = null;
        try (OutputStream out = new FileOutputStream(output)) {
            extractor.setDataSource(source.getAbsolutePath());
            MediaFormat input = selectAudio(extractor);
            MediaBudget.checkDiskSpace(output.getParentFile(), estimatedBytes(input, source));
            decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
            decoder.configure(input, null, null, 0);
            decoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int inRate = 0, channels = 0, idle = 0;
            long frames = 0;
            boolean fed = false, done = false, moved = true;
            while (!done) {
                MediaBudget.check(null);
                long wait = moved ? 0 : TIMEOUT_US;
                moved = false;

                // Compressed samples into the decoder. A negative stamp is the AAC priming
                // TrackMuxer.leadIn describes, and nothing here reads the stamps back.
                if (!fed) {
                    int index = decoder.dequeueInputBuffer(0);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            fed = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, Math.max(0, extractor.getSampleTime()), 0);
                            extractor.advance();
                        }
                        moved = true;
                    }
                }

                // One decoded buffer at a time, straight into the encoder.
                int index = decoder.dequeueOutputBuffer(info, wait);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (index >= 0 && encoder == null)) {
                    MediaFormat raw = decoder.getOutputFormat();
                    if (raw.containsKey("pcm-encoding") && raw.getInteger("pcm-encoding") != PCM_16BIT) {
                        throw new IOException("The sound decodes to samples the encoder can't take");
                    }
                    int nowRate = raw.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int nowChannels = raw.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (encoder == null) {
                        if (nowRate <= 0 || nowChannels < 1 || nowChannels > 2) {
                            throw new IOException("The sound has " + nowChannels + " channels at " + nowRate + " Hz");
                        }
                        inRate = nowRate;
                        channels = nowChannels;
                        encoder = new Encoder(inRate, channels);
                    } else if (nowRate != inRate || nowChannels != channels) {
                        throw new IOException("The sound changed shape partway through");
                    }
                    moved = true;
                }
                if (index >= 0) {
                    moved = true;
                    boolean last = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    ByteBuffer data = info.size > 0 ? decoder.getOutputBuffer(index) : null;
                    short[] pcm = new short[0];
                    int count = 0;
                    if (data != null) {
                        data.position(info.offset);
                        data.limit(info.offset + info.size);
                        ShortBuffer shorts = data.order(ByteOrder.nativeOrder()).asShortBuffer();
                        // A stray half frame at a buffer's end can't be encoded on its own.
                        count = shorts.remaining() / channels;
                        pcm = new short[count * channels];
                        shorts.get(pcm);
                    }
                    decoder.releaseOutputBuffer(index, false);
                    if (count > 0) {
                        MediaBudget.checkDiskSpace(output.getParentFile(), (long) count * channels);
                        encoder.write(pcm, count, out);
                        frames += count;
                    }
                    if (last) done = true;
                }

                if (moved) idle = 0;
                else if (++idle > MAX_IDLE_TURNS) throw new IOException("The sound conversion stopped moving");
            }
            if (encoder == null || frames == 0) throw new IOException("The sound gave nothing to encode");
            encoder.finish(out);
            MediaBudget.check(null);
        } finally {
            stopAndRelease(decoder);
            extractor.release();
        }
        if (output.length() == 0) throw new IOException("The MP3 encoder wrote an empty file");
    }

    /** What the finished file will take, so a full phone is told before the work, not after. */
    private static long estimatedBytes(MediaFormat input, File source) {
        if (input.containsKey(MediaFormat.KEY_DURATION)) {
            long seconds = input.getLong(MediaFormat.KEY_DURATION) / 1_000_000L + 1;
            // 192 kbps is 24,000 bytes a second.
            return seconds * 24_000L;
        }
        return source.length();
    }

    private static MediaFormat selectAudio(MediaExtractor extractor) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                extractor.selectTrack(i);
                return format;
            }
        }
        throw new IOException("The sound file has no audio track");
    }

    private static void stopAndRelease(MediaCodec codec) {
        if (codec == null) return;
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
            // Already failed or never started. Release below is what matters.
        }
        try {
            codec.release();
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Could not release a sound codec: " + failure);
        }
    }

    /**
     * jump3r's LAME core, wired the way its javax.sound front end wires it, minus that front end:
     * Android has no javax.sound. 16-bit samples go in, MP3 frames come out, no ID3 tag and no
     * Xing header, so the file is frames from its first byte.
     */
    static final class Encoder {
        private final Lame lame = new Lame();
        private final LameGlobalFlags flags;
        private final int channels;
        private boolean finished;

        Encoder(int sampleRate, int channels) throws IOException {
            this.channels = channels;
            GainAnalysis gain = new GainAnalysis();
            BitStream bits = new BitStream();
            Presets presets = new Presets();
            QuantizePVT quantizePvt = new QuantizePVT();
            Quantize quantize = new Quantize();
            VBRTag vbr = new VBRTag();
            Version version = new Version();
            ID3Tag id3 = new ID3Tag();
            Reservoir reservoir = new Reservoir();
            Takehiro takehiro = new Takehiro();
            MPGLib mpg = new MPGLib();
            Interface decoderInterface = new Interface();
            Common common = new Common();

            lame.setModules(gain, bits, presets, quantizePvt, quantize, vbr, version, id3, mpg);
            bits.setModules(gain, mpg, version, vbr);
            id3.setModules(bits, version);
            presets.setModules(lame);
            quantize.setModules(bits, reservoir, quantizePvt, takehiro);
            quantizePvt.setModules(takehiro, reservoir, lame.enc.psy);
            reservoir.setModules(bits);
            takehiro.setModules(quantizePvt);
            vbr.setModules(lame, bits, version);
            mpg.setModules(decoderInterface, common);
            decoderInterface.setModules(vbr, common);

            flags = lame.lame_init();
            flags.num_channels = channels;
            flags.in_samplerate = sampleRate;
            flags.brate = bitRateKbps(sampleRate, channels);
            flags.quality = QUALITY;
            id3.id3tag_init(flags);
            // The tag and the Xing frame both sit ahead of the audio. Neither is wanted, and the
            // ID3 setting has to be off before lame_init_params, which would write it.
            flags.write_id3tag_automatic = false;
            flags.bWriteVbrTag = false;
            if (lame.lame_init_params(flags) < 0) {
                throw new IOException("The MP3 encoder refused " + channels + " channels at " + sampleRate + " Hz");
            }
        }

        /** {@code frames} sample frames from {@code pcm}, interleaved when there are two channels. */
        void write(short[] pcm, int frames, OutputStream out) throws IOException {
            for (int from = 0; from < frames; from += CHUNK_FRAMES) {
                int count = Math.min(CHUNK_FRAMES, frames - from);
                // The encoder takes 32-bit samples, so a 16-bit one moves up by sixteen bits.
                int[] left = new int[count];
                int[] right = channels == 2 ? new int[count] : left;
                for (int i = 0; i < count; i++) {
                    int at = (from + i) * channels;
                    left[i] = pcm[at] << 16;
                    if (channels == 2) right[i] = pcm[at + 1] << 16;
                }
                byte[] mp3 = new byte[count * 5 / 4 + FLUSH_BYTES];
                int made = lame.lame_encode_buffer_int(flags, left, right, count, mp3, 0, mp3.length);
                if (made < 0) throw new IOException("The MP3 encoder failed with code " + made);
                out.write(mp3, 0, made);
            }
        }

        /** The frames still held back, and the encoder let go. Call once, after the last sample. */
        void finish(OutputStream out) throws IOException {
            if (finished) return;
            finished = true;
            byte[] mp3 = new byte[FLUSH_BYTES];
            int made = lame.lame_encode_flush(flags, mp3, 0, mp3.length);
            if (made < 0) throw new IOException("The MP3 encoder failed to finish with code " + made);
            out.write(mp3, 0, made);
            lame.lame_close(flags);
        }
    }
}

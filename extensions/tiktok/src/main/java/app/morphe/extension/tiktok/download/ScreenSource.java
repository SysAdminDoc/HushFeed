/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Process;
import android.util.DisplayMetrics;
import android.view.Surface;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.live.ScreenCapture;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Encodes AVC/AAC directly into complete recoverable FLV tags on the recorder's bounded spool. */
final class ScreenSource implements LiveRecorder.Source {
    static final int MAX_SIDE = 1920;
    private static final int FRAME_RATE = 30, SAMPLE_RATE = 44100, AUDIO_BITRATE = 128_000;
    private static final long POLL_MS = 8,
            END_WAIT_MS = 4000,
            FORMAT_WAIT_MS = 4000,
            OUTPUT_WAIT_MS = 1500;

    interface SinkFactory {
        CaptureFlv.Sink open(LiveRecorder.Output output, LiveRecorder.ScreenAudio audio)
                throws IOException;
    }

    private final Context app;
    private final int width, height, dpi;
    private final boolean wantAudio;
    private final SinkFactory factory;
    private final MediaProjection expected;
    private LiveRecorder.ScreenAudio screenAudio = LiveRecorder.ScreenAudio.VIDEO_ONLY;

    ScreenSource(Context app, DisplayMetrics metrics, boolean audio, SinkFactory factory) {
        int[] size = captureSize(metrics.widthPixels, metrics.heightPixels);
        this.app = app;
        this.width = size[0];
        this.height = size[1];
        this.dpi = metrics.densityDpi;
        wantAudio = audio;
        this.factory = factory;
        expected = ScreenCapture.pending();
    }

    ScreenSource(Context app, DisplayMetrics metrics, boolean audio) {
        this(app, metrics, audio, null);
    }

    static int[] captureSize(int screenWidth, int screenHeight) {
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(screenWidth, screenHeight));
        return new int[] {
            Math.max(2, (int) Math.round(screenWidth * scale)) & ~1,
            Math.max(2, (int) Math.round(screenHeight * scale)) & ~1
        };
    }

    @android.annotation.TargetApi(29)
    static AudioPlaybackCaptureConfiguration captureConfiguration(MediaProjection projection) {
        return new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUid(Process.myUid())
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build();
    }

    LiveRecorder.ScreenAudio screenAudio() {
        return screenAudio;
    }

    @Override
    public void abandon() {
        ScreenCapture.discardUnused(expected);
    }

    @Override
    public void pump(LiveRecorder.Output output, LiveRecorder.Control control) throws IOException {
        MediaProjection projection = ScreenCapture.take(expected);
        if (projection == null) throw new IOException("Screen recording was not allowed");
        Encoder encoder = new Encoder(projection);
        control.onStop(encoder);
        try {
            encoder.run(output, control);
        } finally {
            control.release(encoder);
            try {
                try {
                    if (encoder.writer != null) encoder.writer.checkpoint();
                } finally {
                    encoder.release();
                }
            } finally {
                ScreenCapture.release(projection);
            }
        }
    }

    final class Encoder implements Closeable {
        private final MediaProjection projection;
        private volatile boolean stopRequested;
        private MediaCodec videoCodec, audioCodec;
        private VirtualDisplay display;
        private Surface surface;
        private AudioRecord audioRecord;
        private MediaFormat videoFormat, audioFormat;
        private Frame videoFrame, audioFrame;
        private CaptureFlv.Writer writer;
        private boolean videoDone, audioDone, audioInputDone, formatsReady;
        private long originUs, audioBaseUs, audioFrames;

        Encoder(MediaProjection projection) {
            this.projection = projection;
        }

        @Override
        public void close() {
            stopRequested = true;
        }

        void run(LiveRecorder.Output output, LiveRecorder.Control control) throws IOException {
            originUs = System.nanoTime() / 1000L;
            ScreenCapture.whenEnded(this::close);
            if (control.stopRequested() || stopRequested) return;
            setUp();
            long formatDeadline = System.nanoTime() / 1_000_000L + FORMAT_WAIT_MS,
                    firstOutputDeadline = Long.MAX_VALUE,
                    firstIdrDeadline = Long.MAX_VALUE,
                    endAt = 0,
                    endUs = 0;
            boolean ending = false;
            byte[] pcm = new byte[4096];
            while (!(videoDone && audioDone)) {
                long now = System.nanoTime() / 1_000_000L;
                if ((stopRequested || control.stopRequested()) && !ending) {
                    ending = true;
                    endUs = System.nanoTime() / 1000L - originUs;
                    videoCodec.signalEndOfInputStream();
                    endAt = now + END_WAIT_MS;
                }
                if (writer == null && now > formatDeadline) {
                    if (videoFormat == null)
                        throw new IOException("Screen encoder did not provide video settings");
                    if (audioCodec != null && audioFormat == null) {
                        releaseAudio();
                        audioDone = true;
                        screenAudio = LiveRecorder.ScreenAudio.VIDEO_ONLY;
                    }
                }
                if (ending && now > endAt)
                    throw new IOException("Screen encoder did not finish all output");
                boolean moved = readVideo();
                if (audioCodec != null) {
                    moved |= feedAudio(pcm, ending);
                    moved |= readAudio();
                }
                if (!formatsReady
                        && videoFormat != null
                        && (audioCodec == null || audioFormat != null)) {
                    formatsReady = true;
                    firstOutputDeadline = now + OUTPUT_WAIT_MS;
                }
                if (writer == null
                        && formatsReady
                        && videoFrame == null
                        && now > firstOutputDeadline)
                    throw new IOException("Screen encoder did not produce its first video sample");
                checkStall(now);
                if (writer == null
                        && formatsReady
                        && videoFrame != null
                        && (audioDone || audioFrame != null)) {
                    if (audioDone && audioFrame == null) {
                        releaseAudio();
                        audioFormat = null;
                        screenAudio = LiveRecorder.ScreenAudio.VIDEO_ONLY;
                    }
                    beginWriter(output);
                    firstIdrDeadline = now + FORMAT_WAIT_MS;
                    moved = true;
                }
                if (writer != null) {
                    moved |= emitReady();
                    if (writer.videoSamples == 0 && now > firstIdrDeadline)
                        throw new IOException("Screen encoder did not produce an IDR frame");
                }
                if (!moved) {
                    if (ending)
                        try {
                            Thread.sleep(POLL_MS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Screen encoder drain interrupted", interrupted);
                        }
                    else if (!control.pause(POLL_MS)) stopRequested = true;
                }
            }
            if (writer != null)
                writer.end(
                        Math.max(
                                writer.lastPresentationUs(),
                                endUs > 0 ? endUs : System.nanoTime() / 1000L - originUs));
        }

        private void beginWriter(LiveRecorder.Output output) throws IOException {
            byte[] specific = null;
            int rate = 0, channels = 0;
            if (audioFormat != null) {
                try {
                    rate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (rate != SAMPLE_RATE || channels != 2)
                        throw new IOException(
                                "Screen AAC output disagrees with the PCM input clock");
                    specific =
                            CaptureFlv.audioConfiguration(
                                    audioFormat.getByteBuffer("csd-0"), rate, channels);
                    if (audioFrame != null)
                        CaptureFlv.rawAudio(audioFrame.buffer, audioFrame.offset, audioFrame.size);
                } catch (IOException | RuntimeException failure) {
                    Logger.printInfo(() -> "Screen recording without valid AAC output: " + failure);
                    specific = null;
                    rate = 0;
                    channels = 0;
                    releaseFrame(audioFrame);
                    audioFrame = null;
                    releaseAudio();
                    audioFormat = null;
                    audioDone = true;
                    screenAudio = LiveRecorder.ScreenAudio.VIDEO_ONLY;
                }
            }
            CaptureFlv.Config config =
                    new CaptureFlv.Config(
                            videoFormat.getByteBuffer("csd-0"),
                            videoFormat.getByteBuffer("csd-1"),
                            specific == null ? null : ByteBuffer.wrap(specific),
                            rate,
                            channels);
            if (config.width != width || config.height != height)
                throw new IOException("Screen AVC dimensions disagree with the configured display");
            if (factory == null) throw new IOException("Screen capture journal is missing");
            writer = new CaptureFlv.Writer(factory.open(output, screenAudio), config);
        }

        private void setUp() throws IOException {
            MediaFormat format =
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    Math.min(12_000_000, Math.max(2_000_000, width * height * 4)));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            format.setInteger(
                    MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL, captureLevel(width, height));
            if (Build.VERSION.SDK_INT >= 29) format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = videoCodec.createInputSurface();
            videoCodec.start();
            display =
                    projection.createVirtualDisplay(
                            "hushfeed-live",
                            width,
                            height,
                            dpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            surface,
                            null,
                            null);
            setUpAudio(projection);
            audioDone = audioCodec == null;
        }

        /** Sound is optional: any failure here leaves a recording of the picture alone. */
        void setUpAudio(MediaProjection projection) {
            screenAudio = LiveRecorder.ScreenAudio.VIDEO_ONLY;
            if (!wantAudio || Build.VERSION.SDK_INT < 29) return;
            try {
                AudioPlaybackCaptureConfiguration capture = captureConfiguration(projection);
                AudioFormat pcmFormat =
                        new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                                .build();
                audioRecord =
                        new AudioRecord.Builder()
                                .setAudioPlaybackCaptureConfig(capture)
                                .setAudioFormat(pcmFormat)
                                .setBufferSizeInBytes(SAMPLE_RATE * 4)
                                .build();
                if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                    throw new IllegalStateException("Playback capture was not initialized");
                }
                MediaFormat format =
                        MediaFormat.createAudioFormat(
                                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2);
                format.setInteger(
                        MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
                audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                audioCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                audioCodec.start();
                audioBaseUs = System.nanoTime() / 1000L - originUs;
                audioRecord.startRecording();
                if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("Playback capture did not start");
                }
                screenAudio = LiveRecorder.ScreenAudio.ENABLED;
            } catch (IOException | RuntimeException failure) {
                Logger.printInfo(() -> "Screen recording without sound: " + failure);
                releaseAudio();
            }
        }

        private boolean readVideo() throws IOException {
            if (videoDone || videoFrame != null || videoFormat != null && !formatsReady)
                return false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean moved = false;
            while (true) {
                int index = videoCodec.dequeueOutputBuffer(info, 0);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return moved;
                moved = true;
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (videoFormat != null) throw new IOException("Screen video format changed");
                    videoFormat = videoCodec.getOutputFormat();
                    return true;
                }
                if (index < 0) continue;
                boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size == 0) {
                    videoCodec.releaseOutputBuffer(index, false);
                    if (end) videoDone = true;
                    if (end) return true;
                    continue;
                }
                try {
                    if (info.size > CaptureFlv.MAX_TAG_BYTES - 5)
                        throw new IOException("Screen video sample exceeds the capture bound");
                    videoFrame =
                            new Frame(
                                    videoCodec,
                                    index,
                                    info,
                                    Math.subtractExact(info.presentationTimeUs, originUs));
                } catch (IOException | RuntimeException failure) {
                    videoCodec.releaseOutputBuffer(index, false);
                    throw failure;
                }
                return true;
            }
        }

        private boolean readAudio() throws IOException {
            if (audioDone || audioFrame != null || audioFormat != null && !formatsReady)
                return false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean moved = false;
            while (true) {
                int index = audioCodec.dequeueOutputBuffer(info, 0);
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return moved;
                moved = true;
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (audioFormat != null) throw new IOException("Screen audio format changed");
                    audioFormat = audioCodec.getOutputFormat();
                    return true;
                }
                if (index < 0) continue;
                boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size == 0) {
                    audioCodec.releaseOutputBuffer(index, false);
                    if (end) audioDone = true;
                    if (end) return true;
                    continue;
                }
                try {
                    if (info.size > CaptureFlv.MAX_AUDIO_BYTES)
                        throw new IOException("Screen audio sample exceeds the capture bound");
                    audioFrame = new Frame(audioCodec, index, info, info.presentationTimeUs);
                } catch (IOException | RuntimeException failure) {
                    audioCodec.releaseOutputBuffer(index, false);
                    throw failure;
                }
                return true;
            }
        }

        private void checkStall(long now) throws IOException {
            if (writer != null) return;
            if (videoFrame != null
                    && !audioDone
                    && audioFrame == null
                    && now - videoFrame.waitingAt > OUTPUT_WAIT_MS) {
                // Optional sound may fail after format setup but before its first sample.
                // The first IDR stays codec-owned until this bounded video-only decision.
                releaseAudio();
                audioFormat = null;
                audioDone = true;
                screenAudio = LiveRecorder.ScreenAudio.VIDEO_ONLY;
            }
            if (audioFrame != null
                    && !videoDone
                    && videoFrame == null
                    && now - audioFrame.waitingAt > OUTPUT_WAIT_MS)
                throw new IOException("Screen video encoder output stalled");
        }

        private boolean emitReady() throws IOException {
            Frame frame;
            boolean video;
            if (videoFrame != null && audioFrame != null)
                video = videoFrame.timeUs <= audioFrame.timeUs;
            else if (videoFrame != null && (audioDone || writer.audioSamples > 0)) video = true;
            else if (audioFrame != null && (videoDone || writer.videoSamples > 0)) video = false;
            else return false;
            frame = video ? videoFrame : audioFrame;
            try {
                if (video) writer.video(frame.buffer, frame.offset, frame.size, frame.timeUs);
                else writer.audio(frame.buffer, frame.offset, frame.size, frame.timeUs);
            } finally {
                frame.release();
                if (video) videoFrame = null;
                else audioFrame = null;
            }
            if (frame.end) {
                if (video) videoDone = true;
                else audioDone = true;
            }
            return true;
        }

        private boolean feedAudio(byte[] pcm, boolean ending) throws IOException {
            if (audioInputDone) return false;
            int index = audioCodec.dequeueInputBuffer(0);
            if (index < 0) return false;
            ByteBuffer input = audioCodec.getInputBuffer(index);
            if (input == null) throw new IOException("Screen audio input is unavailable");
            long time = audioTimeUs(audioBaseUs, audioFrames, SAMPLE_RATE);
            if (ending) {
                audioCodec.queueInputBuffer(
                        index, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                audioInputDone = true;
                return true;
            }
            int size = Math.min(pcm.length, input.capacity()) & ~3;
            if (size < 4) throw new IOException("Screen audio buffer cannot hold one PCM frame");
            int read = audioRecord.read(pcm, 0, size, AudioRecord.READ_NON_BLOCKING);
            if (read < 0 || read > size || read % 4 != 0)
                throw new IOException("Playback capture read failed: " + read);
            if (read == 0) {
                audioCodec.queueInputBuffer(index, 0, 0, time, 0);
                return false;
            }
            input.clear();
            input.put(pcm, 0, read);
            audioCodec.queueInputBuffer(index, 0, read, time, 0);
            audioFrames += read / 4;
            return true;
        }

        void releaseAudio() {
            AudioRecord record = audioRecord;
            MediaCodec codec = audioCodec;
            audioRecord = null;
            audioCodec = null;
            if (record != null) {
                try {
                    record.stop();
                } catch (RuntimeException ignored) {
                    // Not started.
                }
                try {
                    record.release();
                } catch (RuntimeException failure) {
                    Logger.printInfo(() -> "Could not release playback capture: " + failure);
                }
            }
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                    // Not started.
                }
                try {
                    codec.release();
                } catch (RuntimeException failure) {
                    Logger.printInfo(
                            () ->
                                    "Could not release a screen recording's audio encoder: "
                                            + failure);
                }
            }
        }

        void release() {
            releaseFrame(videoFrame);
            videoFrame = null;
            releaseFrame(audioFrame);
            audioFrame = null;
            VirtualDisplay ownedDisplay = display;
            display = null;
            if (ownedDisplay != null)
                try {
                    ownedDisplay.release();
                } catch (RuntimeException failure) {
                    Logger.printInfo(() -> "Could not release screen display: " + failure);
                }
            Surface ownedSurface = surface;
            surface = null;
            if (ownedSurface != null)
                try {
                    ownedSurface.release();
                } catch (RuntimeException failure) {
                    Logger.printInfo(() -> "Could not release screen surface: " + failure);
                }
            releaseAudio();
            MediaCodec codec = videoCodec;
            videoCodec = null;
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                try {
                    codec.release();
                } catch (RuntimeException failure) {
                    Logger.printInfo(() -> "Could not release screen encoder: " + failure);
                }
            }
        }
    }

    static int captureLevel(int width, int height) {
        long blocks = ((width + 15L) / 16) * ((height + 15L) / 16), perSecond = blocks * FRAME_RATE;
        if (blocks <= 3600 && perSecond <= 108000)
            return MediaCodecInfo.CodecProfileLevel.AVCLevel31;
        if (blocks <= 5120 && perSecond <= 216000)
            return MediaCodecInfo.CodecProfileLevel.AVCLevel32;
        if (blocks <= 8192 && perSecond <= 245760)
            return MediaCodecInfo.CodecProfileLevel.AVCLevel4;
        if (blocks <= 8704 && perSecond <= 522240)
            return MediaCodecInfo.CodecProfileLevel.AVCLevel42;
        return MediaCodecInfo.CodecProfileLevel.AVCLevel5;
    }

    static long audioTimeUs(long start, long frames, int rate) throws IOException {
        if (start < 0 || frames < 0 || rate < 1)
            throw new IOException("Invalid capture audio clock");
        try {
            return Math.addExact(
                    start,
                    Math.addExact(
                            Math.multiplyExact(frames / rate, 1_000_000L),
                            (frames % rate) * 1_000_000L / rate));
        } catch (ArithmeticException overflow) {
            throw new IOException("Capture audio clock overflow", overflow);
        }
    }

    private static void releaseFrame(Frame frame) {
        if (frame != null)
            try {
                frame.release();
            } catch (RuntimeException failure) {
                Logger.printInfo(() -> "Could not release screen output: " + failure);
            }
    }

    private static final class Frame {
        final MediaCodec codec;
        final int index, offset, size;
        final long timeUs, waitingAt;
        final boolean end;
        final ByteBuffer buffer;
        private boolean released;

        Frame(MediaCodec codec, int index, MediaCodec.BufferInfo info, long time)
                throws IOException {
            if (time < 0
                    || info.size < 1
                    || Build.VERSION.SDK_INT >= 26
                            && (info.flags & MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0)
                throw new IOException("Screen encoder produced incomplete or untimed output");
            this.codec = codec;
            this.index = index;
            offset = info.offset;
            size = info.size;
            timeUs = time;
            end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            buffer = codec.getOutputBuffer(index);
            if (buffer == null || offset < 0 || offset > buffer.capacity() - size)
                throw new IOException("Screen output buffer range is invalid");
            waitingAt = System.nanoTime() / 1_000_000L;
        }

        void release() {
            if (!released) {
                released = true;
                codec.releaseOutputBuffer(index, false);
            }
        }
    }
}

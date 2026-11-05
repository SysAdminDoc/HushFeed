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
    /** One AAC frame of 16-bit stereo PCM, and eight seconds of it waiting for the encoder. */
    private static final int AAC_FRAME_BYTES = 1024 * 4, BACKLOG_BYTES = SAMPLE_RATE * 4 * 8;
    /** How long to wait for the capture thread's read in progress, about ten of its reads. */
    private static final long READ_WAIT_MS = 250;
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
        int background = raisePriority();
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
                try {
                    ScreenCapture.release(projection);
                } finally {
                    restorePriority(background);
                }
            }
        }
    }

    /**
     * The encoders drop what this thread doesn't drain in time, so while it records it runs at
     * normal priority, as Android's own recorder does. Returns the priority to go back to, or
     * {@link Integer#MIN_VALUE} when it was left alone.
     */
    static int raisePriority() {
        try {
            int before = android.os.Process.getThreadPriority(android.os.Process.myTid());
            if (before <= android.os.Process.THREAD_PRIORITY_DEFAULT) return Integer.MIN_VALUE;
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);
            return before;
        } catch (RuntimeException denied) {
            Logger.printInfo(() -> "Screen recording stays at background priority: " + denied);
            return Integer.MIN_VALUE;
        }
    }

    /** Saving the recording afterwards goes back to the background. */
    static void restorePriority(int before) {
        if (before == Integer.MIN_VALUE) return;
        try {
            android.os.Process.setThreadPriority(before);
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Could not restore the recorder's priority: " + failure);
        }
    }

    final class Encoder implements Closeable {
        private final MediaProjection projection;
        private volatile boolean stopRequested;
        private MediaCodec videoCodec, audioCodec;
        private VirtualDisplay display;
        private Surface surface;
        private AudioRecord audioRecord;
        private PcmBacklog audioBacklog;
        private CaptureReader capture;
        private MediaFormat videoFormat, audioFormat;
        private Frame videoFrame, audioFrame;
        private CaptureFlv.Writer writer;
        private boolean videoDone, audioDone, audioReadDone, audioInputDone, formatsReady;
        private long originUs, audioBaseUs, audioCutAt;

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
            startCapture();
            long formatDeadline = System.nanoTime() / 1_000_000L + FORMAT_WAIT_MS,
                    firstOutputDeadline = Long.MAX_VALUE,
                    firstIdrDeadline = Long.MAX_VALUE,
                    endAt = 0,
                    endUs = 0;
            boolean ending = false;
            while (!(videoDone && audioDone)) {
                long now = System.nanoTime() / 1_000_000L;
                if ((stopRequested || control.stopRequested()) && !ending) {
                    ending = true;
                    endUs = System.nanoTime() / 1000L - originUs;
                    videoCodec.signalEndOfInputStream();
                    endAt = now + END_WAIT_MS;
                    audioCutAt = now + END_WAIT_MS / 2;
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
                    moved |= feedAudio(ending, now);
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
                audioBacklog = new PcmBacklog(BACKLOG_BYTES);
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

        private void startCapture() {
            AudioRecord record = audioRecord;
            if (record == null) return;
            capture =
                    new CaptureReader(
                            (into, offset, length) ->
                                    record.read(into, offset, length, AudioRecord.READ_BLOCKING),
                            audioBacklog,
                            AAC_FRAME_BYTES);
            capture.start();
        }

        /**
         * Hands the encoder whole input buffers of the sound the capture thread has read. Each
         * buffer is stamped with its place in the capture, so sound a full backlog gave up still
         * counts and the rest stays in step with the picture.
         */
        private boolean feedAudio(boolean ending, long now) throws IOException {
            if (audioInputDone) return false;
            IOException failed = capture == null ? null : capture.failure();
            if (failed != null) throw failed;
            if (ending && !audioReadDone) {
                finishCapture();
                audioReadDone = true;
            }
            if (ending && now > audioCutAt) audioBacklog.dropAll();
            boolean moved = false;
            for (int queued = 0; queued < 8; queued++) {
                if (!audioReadDone && audioBacklog.bytes() < AAC_FRAME_BYTES) break;
                int index = audioCodec.dequeueInputBuffer(0);
                if (index < 0) break;
                ByteBuffer input = audioCodec.getInputBuffer(index);
                if (input == null) throw new IOException("Screen audio input is unavailable");
                if (input.capacity() < 4)
                    throw new IOException("Screen audio buffer cannot hold one PCM frame");
                input.clear();
                long time = audioTimeUs(audioBaseUs, audioBacklog.take(input, input.capacity()),
                        SAMPLE_RATE);
                int size = input.position();
                if (size == 0) {
                    audioCodec.queueInputBuffer(
                            index, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    audioInputDone = true;
                    return true;
                }
                audioCodec.queueInputBuffer(index, 0, size, time, 0);
                moved = true;
            }
            return moved;
        }

        /** Ends the capture thread after its last read, so every sound so far is in the backlog. */
        private void finishCapture() throws IOException {
            CaptureReader reader = capture;
            if (reader == null) return;
            reader.finish();
            awaitReader(reader);
            if (reader.isAlive()) {
                // A blocking read only waits this long when no sound is arriving at all.
                AudioRecord record = audioRecord;
                if (record != null)
                    try {
                        record.stop();
                    } catch (RuntimeException ignored) {
                        // Already stopped.
                    }
                awaitReader(reader);
            }
            IOException failed = reader.failure();
            if (failed != null) throw failed;
        }

        void releaseAudio() {
            AudioRecord record = audioRecord;
            MediaCodec codec = audioCodec;
            CaptureReader reader = capture;
            audioRecord = null;
            audioCodec = null;
            audioBacklog = null;
            capture = null;
            if (reader != null) reader.finish();
            if (record != null) {
                try {
                    record.stop();
                } catch (RuntimeException ignored) {
                    // Not started.
                }
                // Stopping wakes a blocked read; the reader is done with the record before release.
                if (reader != null) awaitReader(reader);
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

    private static void awaitReader(CaptureReader reader) {
        try {
            reader.join(READ_WAIT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Playback capture: reads up to {@code length} bytes of whole PCM frames. */
    interface PcmReader {
        int read(byte[] into, int offset, int length) throws IOException;
    }

    /**
     * Reads playback capture as it arrives into the backlog, on its own thread at audio priority.
     * Android drops capture an app leaves unread for about a second, and a busy phone can keep an
     * ordinary thread off the CPU for that long.
     */
    static final class CaptureReader implements Runnable {
        private final PcmReader capture;
        private final PcmBacklog backlog;
        private final int chunkBytes;
        // A plain Thread handed this Runnable, never a subclass (OVERRIDDEN_THREAD_START_METHOD).
        private final Thread thread;
        private volatile boolean stopping;
        private volatile IOException failure;

        CaptureReader(PcmReader capture, PcmBacklog backlog, int chunkBytes) {
            if (chunkBytes < 4 || chunkBytes % 4 != 0)
                throw new IllegalArgumentException("Invalid PCM chunk size");
            this.capture = capture;
            this.backlog = backlog;
            this.chunkBytes = chunkBytes;
            thread = new Thread(this, "hushfeed-live-sound");
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        boolean isAlive() {
            return thread.isAlive();
        }

        void join(long millis) throws InterruptedException {
            thread.join(millis);
        }

        @Override
        public void run() {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            } catch (RuntimeException denied) {
                Logger.printInfo(() -> "Screen sound is read at normal priority: " + denied);
            }
            byte[] chunk = new byte[chunkBytes];
            try {
                while (!stopping) {
                    int read = capture.read(chunk, 0, chunk.length);
                    if (read < 0 || read > chunk.length || read % 4 != 0)
                        throw new IOException("Playback capture read failed: " + read);
                    if (read > 0) backlog.write(chunk, 0, read);
                    else Thread.sleep(POLL_MS);
                }
            } catch (IOException readFailure) {
                failure = readFailure;
            } catch (InterruptedException interrupted) {
                failure = new IOException("Playback capture read interrupted", interrupted);
            } catch (RuntimeException readFailure) {
                failure = new IOException("Playback capture read failed", readFailure);
            }
        }

        /** Stops after the read in progress. */
        void finish() {
            stopping = true;
        }

        IOException failure() {
            return failure;
        }
    }

    /**
     * Sound the capture thread has read and the encoder hasn't taken yet. When it fills, the
     * oldest sound goes, and its frames still count on the clock.
     */
    static final class PcmBacklog {
        private final byte[] ring;
        private int head, size;
        private long framesOut, droppedFrames;

        PcmBacklog(int bytes) {
            if (bytes < 4 || bytes % 4 != 0)
                throw new IllegalArgumentException("Invalid PCM backlog size");
            ring = new byte[bytes];
        }

        synchronized int bytes() {
            return size;
        }

        synchronized long droppedFrames() {
            return droppedFrames;
        }

        synchronized void write(byte[] pcm, int offset, int length) {
            if (offset < 0 || length < 0 || length % 4 != 0 || offset > pcm.length - length)
                throw new IllegalArgumentException("Invalid PCM range");
            int skip = Math.max(0, length - ring.length);
            if (skip > 0) {
                drop(size);
                count(skip);
                offset += skip;
                length -= skip;
            }
            int over = size + length - ring.length;
            // A quarter at a time, so a long stall leaves one gap rather than a gap per read.
            if (over > 0) drop(Math.max(over, (ring.length / 4) & ~3));
            int tail = (head + size) % ring.length;
            int first = Math.min(length, ring.length - tail);
            System.arraycopy(pcm, offset, ring, tail, first);
            System.arraycopy(pcm, offset + first, ring, 0, length - first);
            size += length;
        }

        /**
         * Moves whole frames, as many as fit, into {@code into} and returns the place in the
         * capture, in frames, of the first one.
         */
        synchronized long take(ByteBuffer into, int limit) {
            long at = framesOut;
            int count = Math.min(size, Math.min(limit, into.remaining())) & ~3;
            int first = Math.min(count, ring.length - head);
            into.put(ring, head, first);
            if (count > first) into.put(ring, 0, count - first);
            advance(count);
            framesOut += count / 4;
            return at;
        }

        synchronized void dropAll() {
            drop(size);
        }

        private void drop(int bytes) {
            int count = Math.min(size, bytes) & ~3;
            advance(count);
            count(count);
        }

        private void count(int droppedBytes) {
            framesOut += droppedBytes / 4;
            droppedFrames += droppedBytes / 4;
        }

        private void advance(int count) {
            head = (head + count) % ring.length;
            size -= count;
            if (size == 0) head = 0;
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

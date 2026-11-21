/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.GLES20;

import app.morphe.extension.shared.Logger;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The codec half of a photo post saved as a video: an H.264 track drawn photo by photo through
 * the encoder's input surface, with any of the post's own videos played into it between them,
 * and the post's sound turned into an AAC track no longer than the pictures.
 *
 * <p>Robolectric has no real codecs, so nothing here decides anything a test would want to
 * check. The sizes, frame counts, timestamps and trim length all come from {@link SlideshowVideo}.
 * What is left is the order the codec calls go in and letting every one of them go again.
 */
final class SlideshowEncoder implements Closeable {
    static final String VIDEO_MIME = "video/avc";
    static final String AUDIO_MIME = "audio/mp4a-latm";
    private static final long TIMEOUT_US = 10_000L;
    /** How far past the trim the sound keeps decoding, so the decoder's own delay can't cut it short. */
    private static final long DECODE_MARGIN_US = 500_000L;
    /** Turns in a row with nothing moving, each with a short wait, before the sound counts as stuck. */
    private static final int MAX_IDLE_TURNS = 500;
    /** android.media.AudioFormat.ENCODING_PCM_16BIT, the only sample shape this hands the encoder. */
    private static final int PCM_16BIT = 2;

    interface Progress {
        /** {@code drawn} of the frames one {@link #add} call shows. */
        void frames(long drawn);
    }

    /** How far a post's video is into the photos' video, and whether the save was cancelled. */
    interface ClipProgress {
        void played(long doneUs, long totalUs);

        boolean cancelled();
    }

    final int width;
    final int height;
    private final int fps;
    private final File directory;
    private final MediaMuxer muxer;
    private final MediaCodec encoder;
    private final AnimatedWebpMp4Converter.CodecSurface surface;
    private final AnimatedWebpMp4Converter.EncoderState state = new AnimatedWebpMp4Converter.EncoderState();
    /** Frames handed to the encoder, photos and videos alike. */
    private long frames;
    /**
     * Where the photos since the last video began, and how many frames they've had. A photo's
     * frames are counted at the video's own rate from there, a post's video keeps its own times.
     */
    private long runStartNs, runFrames;

    private SlideshowEncoder(int width, int height, int fps, File directory, MediaMuxer muxer,
            MediaCodec encoder, AnimatedWebpMp4Converter.CodecSurface surface) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.directory = directory;
        this.muxer = muxer;
        this.encoder = encoder;
        this.surface = surface;
    }

    /**
     * Starts an encoder at the first of {@code sizes} one will take. Each size goes first to the
     * encoders that say they support it, then to the default one, because a phone's list of what
     * it supports is sometimes stricter than what it will actually do.
     */
    static SlideshowEncoder open(File output, List<int[]> sizes, int fps) throws IOException {
        File directory = output.getAbsoluteFile().getParentFile();
        MediaBudget.checkStreamingDiskSpace(directory, null);
        MediaMuxer muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        IOException failure = new IOException("No H.264 encoder took any of " + sizes.size() + " frame sizes");
        for (int[] size : sizes) {
            List<String> names = encodersFor(size[0], size[1]);
            names.add(null);
            for (String name : names) {
                MediaCodec encoder = null;
                AnimatedWebpMp4Converter.CodecSurface surface = null;
                try {
                    encoder = name == null ? MediaCodec.createEncoderByType(VIDEO_MIME) : MediaCodec.createByCodecName(name);
                    encoder.configure(format(size[0], size[1], fps), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                    surface = new AnimatedWebpMp4Converter.CodecSurface(encoder.createInputSurface(), size[0], size[1]);
                    encoder.start();
                    return new SlideshowEncoder(size[0], size[1], fps, directory, muxer, encoder, surface);
                } catch (IOException | RuntimeException refused) {
                    failure.addSuppressed(refused);
                    release(encoder, surface);
                }
            }
        }
        releaseMuxer(muxer);
        throw failure;
    }

    private static MediaFormat format(int width, int height, int fps) {
        return format(width, height, fps, SlideshowVideo.bitRate(width, height));
    }

    /** An H.264 encoder fed through its input surface. {@link CaptionBurner} picks its own bit rate. */
    static MediaFormat format(int width, int height, int fps, int bitRate) {
        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, SlideshowVideo.KEYFRAME_SECONDS);
        return format;
    }

    /** The H.264 encoders that say they take this size, in the phone's own order of preference. */
    static List<String> encodersFor(int width, int height) {
        List<String> names = new ArrayList<>();
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!VIDEO_MIME.equalsIgnoreCase(type)) continue;
                    MediaCodecInfo.VideoCapabilities video = info.getCapabilitiesForType(type).getVideoCapabilities();
                    if (video != null && video.isSizeSupported(width, height)) names.add(info.getName());
                }
            }
        } catch (RuntimeException unreadable) {
            Logger.printInfo(() -> "Could not read the encoder list: " + unreadable);
        }
        return names;
    }

    /** Shows {@code frame} for {@code count} frames, draining the encoder as it goes. */
    void add(Bitmap frame, int count, Progress progress) throws IOException {
        surface.upload(frame);
        for (int i = 0; i < count; i++) {
            surface.present(nextPhotoNs());
            runFrames++;
            frames++;
            drain();
            if (progress != null) progress.frames(i + 1);
        }
    }

    /** When the next photo frame shows, which is also where the video so far ends. */
    private long nextPhotoNs() {
        return runStartNs + SlideshowVideo.presentationTimeNs(runFrames, fps);
    }

    /** How long the video drawn so far runs, which is where its sound is cut. */
    long durationUs() {
        return nextPhotoNs() / 1000L;
    }

    /**
     * Wait only when the encoder falls behind, so its output buffers can't all fill while the
     * next frame waits for an input slot.
     */
    private void drain() throws IOException {
        long behind = frames - state.samples;
        AnimatedWebpMp4Converter.drainEncoder(encoder, muxer, false, state, directory,
                behind > 2 ? AnimatedWebpMp4Converter.CODEC_TIMEOUT_US : 0);
    }

    /**
     * The size a post's video shows at, turned the way its player turns it, as width and height.
     * Read before the first picture, since the frame of the whole video is shaped from it.
     */
    static int[] clipSize(File clip) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(clip.getAbsolutePath());
            return shownSize(TrackMuxer.select(extractor, "video/"));
        } finally {
            extractor.release();
        }
    }

    private static int[] shownSize(MediaFormat format) throws IOException {
        int width = CaptionBurner.number(format, MediaFormat.KEY_WIDTH, 0);
        int height = CaptionBurner.number(format, MediaFormat.KEY_HEIGHT, 0);
        if (width <= 0 || height <= 0) throw new IOException("The video doesn't say how big it is");
        return SlideshowVideo.shownSize(width, height, CaptionBurner.rotation(format));
    }

    /**
     * Plays a post's video (#465) from where the photos before it reached, at its own pace and
     * fitted into the frame the way a photo is, without its own sound: the post's sound runs
     * under the whole video. Each decoded frame goes onto a texture in the encoder's context, as
     * {@link CaptionBurner} does it, so nothing is copied through memory. The decoder turns a
     * picture that's stored sideways itself, since it draws onto a surface.
     *
     * <p>A video that fails partway leaves what it drew, and the photos after it start past that.
     */
    void addClip(File clip, ClipProgress progress) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        CaptionBurner.DecodedFrames decoded = null;
        long startNs = nextPhotoNs();
        long firstUs = -1, lastUs = -1, gapUs = SlideshowVideo.DEFAULT_CLIP_GAP_US, lastNs = -1;
        try {
            extractor.setDataSource(clip.getAbsolutePath());
            MediaFormat input = TrackMuxer.select(extractor, "video/");
            int[] shown = shownSize(input);
            long lengthUs = CaptionBurner.duration(input);
            MediaBudget.deadline().allowAtLeast(CaptionBurner.timeAllowedMs(lengthUs));
            int[] box = SlideshowVideo.viewport(SlideshowVideo.fit(shown[0], shown[1], width, height), height);
            decoded = new CaptionBurner.DecodedFrames();
            decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
            decoder.configure(input, decoded.surface, null, 0);
            decoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int idle = 0;
            boolean fed = false, decodedAll = false;
            while (!decodedAll) {
                MediaBudget.check(null);
                if (progress != null && progress.cancelled()) {
                    throw new MediaBudget.StopException("Photo video cancelled", MediaBudget.StopException.Reason.CANCELLED);
                }
                boolean moved = false;
                if (!fed) {
                    int index = decoder.dequeueInputBuffer(0);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            fed = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                        moved = true;
                    }
                }
                int frame = decoder.dequeueOutputBuffer(info, moved ? 0 : TIMEOUT_US);
                if (frame >= 0) {
                    moved = true;
                    boolean last = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean render = info.size > 0;
                    decoder.releaseOutputBuffer(frame, render);
                    if (render) {
                        decoded.await();
                        GLES20.glViewport(0, 0, width, height);
                        GLES20.glClearColor(0f, 0f, 0f, 1f);
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                        decoded.draw(box[0], box[1], box[2], box[3]);
                        if (firstUs < 0) firstUs = info.presentationTimeUs;
                        if (lastUs >= 0 && info.presentationTimeUs > lastUs) gapUs = info.presentationTimeUs - lastUs;
                        lastUs = Math.max(lastUs, info.presentationTimeUs);
                        lastNs = SlideshowVideo.clipFrameNs(startNs, firstUs, info.presentationTimeUs, lastNs);
                        surface.submit(lastNs);
                        frames++;
                        drain();
                        if (progress != null) progress.played(lastUs - firstUs, lengthUs);
                    }
                    if (last) decodedAll = true;
                } else if (frame == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    moved = true;
                }
                if (moved) idle = 0;
                else if (++idle > MAX_IDLE_TURNS) throw new IOException("The post's video stopped moving partway through");
            }
            if (lastNs < 0) throw new IOException("The post's video gave no frames to draw");
        } finally {
            // The photos after it start where its last frame ends, however far it got.
            if (lastNs >= 0) {
                runStartNs = SlideshowVideo.clipEndNs(lastNs, gapUs);
                runFrames = 0;
            }
            stopAndRelease(decoder);
            if (decoded != null) decoded.release();
            extractor.release();
        }
    }

    /** Ends the stream and closes the file. The MP4 isn't playable until this returns. */
    void finish() throws IOException {
        if (frames == 0) throw new IOException("No photo was drawn into the video");
        encoder.signalEndOfInputStream();
        AnimatedWebpMp4Converter.drainEncoder(encoder, muxer, true, state, directory);
        if (!state.muxerStarted) throw new IOException("The encoder gave no video");
        muxer.stop();
        state.muxerStarted = false;
    }

    @Override public void close() {
        release(encoder, surface);
        releaseMuxer(muxer);
    }

    /** Package-private, with {@link #releaseMuxer}, for {@link CaptionBurner}'s encoder and file. */
    static void release(MediaCodec encoder, AnimatedWebpMp4Converter.CodecSurface surface) {
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (RuntimeException ignored) {
                // One that never started, or already failed, refuses to stop. Release still has to run.
            }
            try {
                encoder.release();
            } catch (RuntimeException failure) {
                Logger.printInfo(() -> "Could not release the video encoder: " + failure);
            }
        }
        if (surface != null) {
            try {
                surface.release();
            } catch (RuntimeException failure) {
                Logger.printInfo(() -> "Could not release the encoder surface: " + failure);
            }
        }
    }

    /** A muxer started and never stopped throws from release over its empty track; that says nothing new. */
    static void releaseMuxer(MediaMuxer muxer) {
        if (muxer == null) return;
        try {
            muxer.release();
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Could not release the video muxer cleanly: " + failure);
        }
    }

    /**
     * Writes the sound in {@code source} to {@code output} as AAC in an MP4 container, cut at
     * {@code limitUs}. The sound comes as an MP3 more often than not, and the muxer takes AAC, so
     * it's decoded and encoded again rather than copied. A sound shorter than the photos starts
     * again from the top. The cut is counted in samples, so the track ends where the last photo
     * does.
     */
    static void soundTrack(File source, File output, long limitUs) throws IOException {
        if (limitUs <= 0) throw new IOException("There's no length to cut the sound to");
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null, encoder = null;
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            MediaFormat input = selectAudio(extractor);
            decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
            decoder.configure(input, null, null, 0);
            decoder.start();
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteBuffer pcm = null;
            int pcmIndex = -1, track = -1, sampleRate = 0, frameBytes = 0, samples = 0, idle = 0;
            long frameLimit = 0, queued = 0;
            // Where the current pass of the sound starts, and how far it has got.
            long passStartUs = 0, passLastUs = 0, passGapUs = 0;
            int passSamples = 0;
            boolean fed = false, decoded = false, closed = false, done = false, muxing = false, moved = true;
            while (!done) {
                MediaBudget.check(null);
                long wait = moved ? 0 : TIMEOUT_US;
                moved = false;

                // Compressed samples into the decoder, a little past the cut, from the top again
                // when the sound runs out first.
                if (!fed) {
                    int index = decoder.dequeueInputBuffer(0);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        long time = extractor.getSampleTime();
                        long nextPassUs = passStartUs + SlideshowVideo.loopLengthUs(passLastUs, passGapUs);
                        if (size < 0 && buffer != null && passSamples > 0 && nextPassUs < limitUs) {
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                            passStartUs = nextPassUs;
                            passLastUs = passGapUs = 0;
                            passSamples = 0;
                            size = extractor.readSampleData(buffer, 0);
                            time = extractor.getSampleTime();
                        }
                        long at = passStartUs + Math.max(0, time);
                        if (size < 0 || at > limitUs + DECODE_MARGIN_US) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            fed = true;
                        } else {
                            if (passSamples > 0 && time > passLastUs) passGapUs = time - passLastUs;
                            passLastUs = Math.max(passLastUs, time);
                            passSamples++;
                            decoder.queueInputBuffer(index, 0, size, at, 0);
                            extractor.advance();
                        }
                        moved = true;
                    }
                }

                // One decoded buffer at a time, held until the encoder has taken all of it.
                if (pcm == null && !decoded) {
                    int index = decoder.dequeueOutputBuffer(info, wait);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (index >= 0 && encoder == null)) {
                        if (encoder == null) {
                            MediaFormat raw = decoder.getOutputFormat();
                            if (raw.containsKey("pcm-encoding") && raw.getInteger("pcm-encoding") != PCM_16BIT) {
                                throw new IOException("The sound decodes to samples the encoder can't take");
                            }
                            sampleRate = raw.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            int channels = raw.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            if (sampleRate <= 0 || channels < 1 || channels > 2) {
                                throw new IOException("The sound has " + channels + " channels at " + sampleRate + " Hz");
                            }
                            frameBytes = 2 * channels;
                            frameLimit = SlideshowVideo.trimFrames(limitUs, sampleRate);
                            encoder = aacEncoder(sampleRate, channels);
                        }
                        moved = true;
                    }
                    if (index >= 0) {
                        moved = true;
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decoded = true;
                        ByteBuffer data = info.size > 0 ? decoder.getOutputBuffer(index) : null;
                        if (data != null) {
                            data.position(info.offset);
                            data.limit(info.offset + info.size);
                            pcm = data;
                            pcmIndex = index;
                        } else {
                            decoder.releaseOutputBuffer(index, false);
                        }
                    }
                }

                // A stray half sample at a buffer's end can't be encoded on its own.
                if (pcm != null && (pcm.remaining() < frameBytes || queued >= frameLimit)) {
                    decoder.releaseOutputBuffer(pcmIndex, false);
                    pcm = null;
                    if (queued >= frameLimit) decoded = fed = true;
                    moved = true;
                }

                // Samples into the encoder, stamped by how many came before, until the cut.
                if (encoder != null && !closed && (pcm != null || decoded)) {
                    int index = encoder.dequeueInputBuffer(0);
                    if (index >= 0) {
                        moved = true;
                        long time = queued * 1_000_000L / sampleRate;
                        ByteBuffer target = encoder.getInputBuffer(index);
                        int bytes = pcm == null || target == null ? 0
                                : SlideshowVideo.pcmBytes(pcm.remaining(), target.capacity(), frameLimit - queued, frameBytes);
                        if (bytes > 0) {
                            target.clear();
                            ByteBuffer slice = pcm.duplicate();
                            slice.limit(slice.position() + bytes);
                            target.put(slice);
                            pcm.position(pcm.position() + bytes);
                            encoder.queueInputBuffer(index, 0, bytes, time, 0);
                            queued += bytes / frameBytes;
                        } else {
                            encoder.queueInputBuffer(index, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            closed = true;
                        }
                    }
                }

                // AAC out to the file.
                if (encoder != null) {
                    int index = encoder.dequeueOutputBuffer(info, moved ? 0 : wait);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxing) throw new IOException("The sound encoder changed format twice");
                        track = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                        muxing = true;
                        moved = true;
                    } else if (index >= 0) {
                        moved = true;
                        ByteBuffer data = encoder.getOutputBuffer(index);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                        if (info.size > 0 && data != null) {
                            if (!muxing) throw new IOException("The sound muxer hasn't started");
                            data.position(info.offset);
                            data.limit(info.offset + info.size);
                            MediaBudget.checkDiskSpace(output.getParentFile(), info.size);
                            muxer.writeSampleData(track, data, info);
                            samples++;
                        }
                        encoder.releaseOutputBuffer(index, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                    }
                } else if (decoded) {
                    break;
                }

                if (moved) idle = 0;
                else if (++idle > MAX_IDLE_TURNS) throw new IOException("The sound conversion stopped moving");
            }
            if (samples == 0) throw new IOException("The sound gave no samples to keep");
            muxer.stop();
        } finally {
            stopAndRelease(decoder);
            stopAndRelease(encoder);
            extractor.release();
            releaseMuxer(muxer);
        }
    }

    private static MediaCodec aacEncoder(int sampleRate, int channels) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, SlideshowVideo.audioBitRate(channels));
        MediaCodec codec = MediaCodec.createEncoderByType(AUDIO_MIME);
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            return codec;
        } catch (RuntimeException refused) {
            codec.release();
            throw refused;
        }
    }

    /** How long the sound in {@code source} plays, from its container, or -1 when it doesn't say. */
    static long soundLengthUs(File source) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(source.getAbsolutePath());
            MediaFormat format = selectAudio(extractor);
            return format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : -1;
        } finally {
            extractor.release();
        }
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
}

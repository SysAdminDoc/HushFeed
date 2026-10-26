/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns what a LIVE recording kept on disk (a raw FLV stream, or MPEG-TS pieces joined end to end)
 * into an MP4 other apps can play. Both end at {@link Mp4Writer}, so the FLV side can be tested
 * without a codec.
 */
final class LiveRemux {
    private LiveRemux() {
    }

    /** What an MP4 file is built through. */
    interface Mp4Writer {
        /** {@code csd1} is null for HEVC, whose settings all travel in {@code csd0}. */
        int addVideoTrack(String mime, byte[] csd0, byte[] csd1, int width, int height) throws IOException;

        int addAudioTrack(byte[] specific, int sampleRate, int channels) throws IOException;

        void start() throws IOException;

        void write(int track, byte[] data, long presentationUs, boolean keyFrame) throws IOException;

        default void endVideo(long presentationUs)throws IOException { }

        /** Closes the file so it plays. */
        void finish() throws IOException;

        /** Drops the file without finishing it. Never throws. */
        void abort();
    }

    /** Nothing playable was in the recording. */
    static final class NothingRecorded extends IOException {
        NothingRecorded(String message) {
            super(message);
        }
    }

    /** What went into a remuxed file. */
    static final class Summary {
        final int videoSamples;
        final int audioSamples;
        final long durationUs;

        Summary(int videoSamples, int audioSamples, long durationUs) {
            this.videoSamples = videoSamples;
            this.audioSamples = audioSamples;
            this.durationUs = durationUs;
        }
    }

    /** The real MP4 writer. */
    static final class MuxerWriter implements Mp4Writer {
        private final MediaMuxer muxer;
        private boolean started;
        private boolean released;
        private int videoTrack=-1;

        MuxerWriter(File output) throws IOException {
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        }

        @Override public int addVideoTrack(String mime, byte[] csd0, byte[] csd1, int width, int height) {
            MediaFormat format = MediaFormat.createVideoFormat(mime, width, height);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
            if (csd1 != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
            videoTrack=muxer.addTrack(format);return videoTrack;
        }

        @Override public int addAudioTrack(byte[] specific, int sampleRate, int channels) {
            MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(specific));
            return muxer.addTrack(format);
        }

        @Override public void start() {
            muxer.start();
            started = true;
        }

        @Override public void write(int track, byte[] data, long presentationUs, boolean keyFrame) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            info.set(0, data.length, presentationUs, keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            muxer.writeSampleData(track, ByteBuffer.wrap(data), info);
        }

        @Override public void endVideo(long presentationUs) {
            if(videoTrack<0 || !started || released)return;
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();info.set(0,0,presentationUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            muxer.writeSampleData(videoTrack,ByteBuffer.allocate(0),info);
        }

        @Override public void finish() {
            try { if (started) muxer.stop(); } finally { started=false; release(); }
        }

        private void release() { if(!released){released=true;muxer.release();} }

        @Override public void abort() {
            if(released)return;
            try {
                if (started) muxer.stop();
            } catch (RuntimeException ignored) {
                // The file is being thrown away.
            }
            try {
                started=false;release();
            } catch (RuntimeException ignored) {
                // Same.
            }
        }
    }

    /** How many video samples to wait for audio settings before going on without audio. */
    private static final int AUDIO_WAIT_SAMPLES = 150;
    /** Samples held while waiting for the other track's settings. */
    private static final int HELD_LIMIT = 600;
    private static final long HELD_BYTES_LIMIT = 16L * 1024 * 1024;

    /**
     * Rewrites an FLV file as an MP4 through {@code writer}. The first video sample written is a key
     * frame, and a track whose settings never arrive is left out. Throws {@link NothingRecorded}
     * when no video sample made it.
     */
    static Summary fromFlv(File flv, Mp4Writer writer) throws IOException {
        return fromFlv(flv,writer,flv.length());
    }

    static Summary fromFlv(File flv,Mp4Writer writer,long completeBytes)throws IOException {return fromFlv(flv,writer,completeBytes,false);}

    static Summary fromFlv(File flv,Mp4Writer writer,long completeBytes,boolean relativeOrigin)throws IOException {
        FlvSink sink = new FlvSink(writer);
        try (BufferedInputStream input = new BufferedInputStream(prefixInput(flv,completeBytes), 1 << 16)) {
            FlvReader.read(input,sink,true,relativeOrigin);
        } catch (IOException | RuntimeException failure) {
            writer.abort();
            throw failure;
        }
        try {
            return sink.finish();
        } catch (IOException | RuntimeException failure) {
            writer.abort();
            throw failure;
        }
    }

    private static InputStream prefixInput(File file,long completeBytes)throws IOException {
        if(completeBytes<0 || completeBytes>file.length())throw new IOException("Recording prefix exceeds its source");
        return new FilterInputStream(new FileInputStream(file)) {
            private long remaining=completeBytes;
            @Override public int read()throws IOException {if(remaining==0)return -1;int value=super.read();if(value>=0)remaining--;return value;}
            @Override public int read(byte[] bytes,int offset,int count)throws IOException {
                if(count==0)return 0;if(remaining==0)return -1;int read=super.read(bytes,offset,(int)Math.min(count,remaining));if(read>0)remaining-=read;return read;
            }
            @Override public long skip(long count)throws IOException {long skipped=super.skip(Math.min(Math.max(0,count),remaining));remaining-=skipped;return skipped;}
        };
    }

    private static final class Held {
        final boolean video;
        final byte[] data;
        final long presentationUs;
        final boolean key;

        Held(boolean video, byte[] data, long presentationUs, boolean key) {
            this.video = video;
            this.data = data;
            this.presentationUs = presentationUs;
            this.key = key;
        }
    }

    /** Collects FLV's pieces, opens the MP4 once its tracks are known and writes the samples. */
    private static final class FlvSink implements FlvReader.Listener {
        private final Mp4Writer writer;
        private final List<Held> held = new ArrayList<>();
        private String mime;
        private byte[] csd0;
        private byte[] csd1;
        private int width;
        private int height;
        private byte[] audioSpecific;
        private int sampleRate;
        private int channels;
        private boolean open;
        private int videoTrack = -1;
        private int audioTrack = -1;
        private boolean sawKey;
        private int videoSamples;
        private int audioSamples;
        private int videoSinceConfig;
        private long lastUs;
        private long videoEndUs=-1,lastVideoUs=-1;
        private long heldBytes;

        FlvSink(Mp4Writer writer) {
            this.writer = writer;
        }

        @Override public void onVideoConfig(String mime, byte[] csd0, byte[] csd1, int width, int height) {
            if (this.csd0 != null) return;
            this.mime = mime;
            this.csd0 = csd0;
            this.csd1 = csd1;
            this.width = width;
            this.height = height;
        }

        @Override public void onAudioConfig(byte[] specific, int sampleRate, int channels) {
            if (audioSpecific != null || open) return;
            audioSpecific = specific;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }

        @Override public void onVideo(byte[] annexB, long presentationUs, boolean keyFrame) throws IOException {
            if (csd0 == null) return;
            if (!sawKey) {
                if (!keyFrame) return;
                sawKey = true;
            }
            videoSinceConfig++;
            if (!open) {
                hold(new Held(true, annexB, presentationUs, keyFrame));
                if (audioSpecific != null || videoSinceConfig >= AUDIO_WAIT_SAMPLES) openWriter();
                return;
            }
            put(videoTrack, annexB, presentationUs, keyFrame, true);
        }

        @Override public void onAudio(byte[] aac, long presentationUs) throws IOException {
            if (!open) {
                if (audioSpecific != null && csd0 != null) hold(new Held(false, aac, presentationUs, false));
                return;
            }
            if (audioTrack >= 0) put(audioTrack, aac, presentationUs, false, false);
        }

        private void hold(Held sample) throws IOException {
            if(sample.data.length>HELD_BYTES_LIMIT-heldBytes)throw new IOException("Recording codec startup exceeds the memory bound");
            heldBytes+=sample.data.length;
            held.add(sample);
            if (held.size() >= HELD_LIMIT) openWriter();
        }

        private void openWriter() throws IOException {
            if (open) return;
            videoTrack = writer.addVideoTrack(mime, csd0, csd1, width, height);
            if (audioSpecific != null) {
                audioTrack = writer.addAudioTrack(audioSpecific, sampleRate, channels);
            }
            writer.start();
            open = true;
            for (Held sample : held) {
                if (sample.video) put(videoTrack, sample.data, sample.presentationUs, sample.key, true);
                else if (audioTrack >= 0) put(audioTrack, sample.data, sample.presentationUs, false, false);
            }
            held.clear();
            heldBytes=0;
        }

        private void put(int track, byte[] data, long presentationUs, boolean key, boolean video) throws IOException {
            writer.write(track, data, presentationUs, key);
            if (video) {videoSamples++;lastVideoUs=presentationUs;}
            else audioSamples++;
            if (presentationUs > lastUs) lastUs = presentationUs;
        }

        @Override public void onVideoEnd(long presentationUs){videoEndUs=Math.max(videoEndUs,presentationUs);}

        Summary finish() throws IOException {
            if (!open && csd0 != null && sawKey) openWriter();
            if (!open || videoSamples == 0) throw new NothingRecorded("No video was recorded");
            if(videoEndUs>lastVideoUs){writer.endVideo(videoEndUs);lastUs=Math.max(lastUs,videoEndUs);}
            writer.finish();
            return new Summary(videoSamples, audioSamples, lastUs);
        }
    }

    /**
     * Keeps timestamps running forward through a discontinuity: pieces of a LIVE joined end to end
     * can restart their clock, which an MP4 can't hold. The first sample is time zero.
     */
    static final class TimeRebaser {
        private static final long JUMP_US = 2_000_000L;
        private static final long STEP_US = 33_000L;
        private boolean started;
        private long offset;
        private long highest;

        long map(long presentationUs) {
            if (!started) {
                started = true;
                offset = -presentationUs;
                highest = 0;
                return 0;
            }
            long mapped = presentationUs + offset;
            if (mapped < highest - JUMP_US || mapped > highest + 5 * JUMP_US) {
                offset = highest + STEP_US - presentationUs;
                mapped = highest + STEP_US;
            }
            if (mapped > highest) highest = mapped;
            return mapped;
        }
    }

    /**
     * Rewrites MPEG-TS pieces joined end to end (a LIVE's HLS segments) as an MP4. Needs a real
     * codec stack, so it is exercised on a phone rather than in unit tests.
     */
    static Summary fromTransportStream(File ts, File output) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            extractor.setDataSource(ts.getAbsolutePath());
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int[] mapping = new int[extractor.getTrackCount()];
            boolean[] isVideoTrack = new boolean[mapping.length];
            boolean video = false;
            boolean audio = false;
            for (int i = 0; i < mapping.length; i++) {
                mapping[i] = -1;
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                // One video and one audio track are enough, and a second of either is skipped.
                if ((FlvReader.MIME_AVC.equals(mime) || (FlvReader.MIME_HEVC.equals(mime) && LiveStreamSources.HEVC_WRITABLE))
                        && !video) {
                    video = true;
                    isVideoTrack[i] = true;
                } else if ("audio/mp4a-latm".equals(mime) && !audio) {
                    audio = true;
                } else {
                    continue;
                }
                extractor.selectTrack(i);
                mapping[i] = muxer.addTrack(format);
            }
            if (!video) throw new NothingRecorded("No video was recorded");
            muxer.start();
            started = true;
            ByteBuffer buffer = ByteBuffer.allocate(2 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            TimeRebaser rebaser = new TimeRebaser();
            int videoSamples = 0;
            int audioSamples = 0;
            long last = 0;
            while (true) {
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                int track = extractor.getSampleTrackIndex();
                if (track >= 0 && mapping[track] >= 0) {
                    long time = rebaser.map(extractor.getSampleTime());
                    int flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    info.set(0, size, time, flags);
                    muxer.writeSampleData(mapping[track], buffer, info);
                    last = Math.max(last, time);
                    if (isVideoTrack[track]) videoSamples++;
                    else audioSamples++;
                }
                extractor.advance();
            }
            if (videoSamples == 0) throw new NothingRecorded("No video was recorded");
            muxer.stop();
            started = false;
            return new Summary(videoSamples, audioSamples, last);
        } catch (RuntimeException failure) {
            throw new IOException("Could not rewrite the recording: " + failure.getMessage(), failure);
        } finally {
            extractor.release();
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (RuntimeException ignored) {
                    // Discarding the file.
                }
                try {
                    muxer.release();
                } catch (RuntimeException ignored) {
                    // Same.
                }
            }
        }
    }
}

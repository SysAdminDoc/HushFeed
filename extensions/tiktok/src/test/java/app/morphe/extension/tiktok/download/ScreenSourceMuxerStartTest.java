package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.media.MediaCodec;
import android.media.MediaFormat;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.live.ScreenCapture;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowMediaCodec;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;

/** The complete-tag capture path preserves the committed first-IDR/AAC startup guarantees. */
@RunWith(RobolectricTestRunner.class)
@Config(
        manifest = Config.NONE,
        sdk = 29,
        shadows = {ScreenSourceMuxerStartTest.Codec.class})
public class ScreenSourceMuxerStartTest {
    @Rule public SettingsContextRule settings = new SettingsContextRule();
    static final Map<String, Codec> codecs = new HashMap<>();
    static ScreenSource.Encoder startup;
    static long observedOrigin;
    final List<ScreenSource.Encoder> owned = new ArrayList<>();
    Sink sink;
    static final byte[] IDR = {(byte) 0x65, (byte) 0xB8, 0x20},
            P = {(byte) 0x41, (byte) 0xE0, 0x20};

    @Before
    public void reset() {
        codecs.clear();
        startup = null;
        observedOrigin = 0;
        sink = new Sink();
    }

    @After
    public void release() {
        for (ScreenSource.Encoder encoder : owned) encoder.release();
        ScreenCapture.whenEnded(null);
    }

    @Test
    public void commonOriginPrecedesTheFirstEncoderAllocation() throws Exception {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
        ScreenSource.Encoder encoder = encoder(false);
        startup = encoder;
        long before = System.nanoTime() / 1000L;
        try {
            encoder.run(null, new Control());
            fail();
        } catch (Observed expected) {
        }
        assertTrue(observedOrigin >= before && observedOrigin <= System.nanoTime() / 1000L);
        assertEquals(observedOrigin, ((Number) get(encoder, "originUs")).longValue());
        startup = null;
    }

    @Test
    public void lateAudioSettingsKeepTheFirstIdrAndAllSetupFramesCodecOwned() throws Exception {
        ScreenSource.Encoder encoder = encoder(true);
        set(encoder, "originUs", 1_000_000L);
        Codec video = video(), audio = audio();
        audio.formatReady = false;
        video.output.add(new Sample(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG, new byte[] {90}));
        video.output.add(new Sample(1_007_000, 1, annex(IDR)));
        video.output.add(new Sample(1_040_333, 0, annex(P)));
        video.output.add(new Sample(0, 4, new byte[0]));
        audio.output.add(new Sample(9000, 0, new byte[] {21}));
        audio.output.add(new Sample(0, 4, new byte[0]));
        assertTrue(read(encoder, true));
        int calls = video.dequeues;
        for (int i = 0; i < 32; i++) {
            assertFalse(read(encoder, true));
            assertFalse(read(encoder, false));
        }
        assertEquals(calls, video.dequeues);
        assertEquals(0, video.releases);
        assertEquals(4, video.output.size());
        audio.formatReady = true;
        assertTrue(read(encoder, false));
        start(encoder, true);
        drive(encoder);
        assertEquals(Arrays.asList("v7000", "a9000", "v40000"), times());
        assertEquals(4, video.releases);
        assertEquals(2, audio.releases);
    }

    @Test
    public void lateVideoSettingsKeepTheFirstAacFrame() throws Exception {
        ScreenSource.Encoder encoder = encoder(true);
        video().formatReady = false;
        audio().output.add(new Sample(0, 0, new byte[] {31}));
        audio().output.add(new Sample(23220, 0, new byte[] {32}));
        audio().output.add(new Sample(0, 4, new byte[0]));
        video().output.add(new Sample(5000, 1, annex(IDR)));
        video().output.add(new Sample(0, 4, new byte[0]));
        assertTrue(read(encoder, false));
        int calls = audio().dequeues;
        for (int i = 0; i < 32; i++) assertFalse(read(encoder, false));
        assertEquals(calls, audio().dequeues);
        assertEquals(0, audio().releases);
        video().formatReady = true;
        assertTrue(read(encoder, true));
        start(encoder, true);
        drive(encoder);
        assertEquals(Arrays.asList("a0", "v5000", "a23000"), times());
    }

    @Test
    public void codecOwnedWaitingIsBoundedToOneOutputPerTrack() throws Exception {
        ScreenSource.Encoder encoder = encoder(true);
        read(encoder, true);
        read(encoder, false);
        start(encoder, true);
        video().output.add(new Sample(0, 1, annex(IDR)));
        audio().output.add(new Sample(1000, 0, new byte[] {1}));
        read(encoder, true);
        read(encoder, false);
        int v = video().dequeues, a = audio().dequeues;
        for (int i = 0; i < 128; i++) {
            assertFalse(read(encoder, true));
            assertFalse(read(encoder, false));
        }
        assertEquals(v, video().dequeues);
        assertEquals(a, audio().dequeues);
        assertEquals(0, video().releases);
        assertEquals(0, audio().releases);
    }

    @Test
    public void eosAndRepeatedCloseNeverDoubleConsumeOrReleaseAnOutput() throws Exception {
        ScreenSource.Encoder encoder = encoder(false);
        video().output.add(new Sample(0, 5, annex(IDR)));
        read(encoder, true);
        start(encoder, false);
        encoder.close();
        encoder.close();
        drive(encoder);
        int calls = video().dequeues;
        assertFalse(read(encoder, true));
        assertEquals(calls, video().dequeues);
        assertEquals(1, video().releases);
        assertEquals(Arrays.asList("v0"), times());
    }

    @Test
    public void negativeOrPartialVideoOutputIsRejectedAndReleased() throws Exception {
        for (boolean partial : new boolean[] {false, true}) {
            ScreenSource.Encoder encoder = encoder(false);
            read(encoder, true);
            start(encoder, false);
            set(encoder, "originUs", 1000L);
            video().output.add(new Sample(partial ? 2000 : 999, partial ? 9 : 1, annex(IDR)));
            try {
                read(encoder, true);
                fail();
            } catch (IOException expected) {
            }
            assertEquals(1, video().releases);
        }
    }

    @Test
    public void firstAacOutputFailureKeepsTheOwnedIdrForVideoOnlyFallback() throws Exception {
        ScreenSource.Encoder encoder = encoder(true);
        read(encoder, true);
        read(encoder, false);
        set(encoder, "formatsReady", true);
        video().output.add(new Sample(0, 1, annex(IDR)));
        assertTrue(read(encoder, true));
        int released = video().releases;
        Method check = ScreenSource.Encoder.class.getDeclaredMethod("checkStall", long.class);
        check.setAccessible(true);
        check.invoke(encoder, System.nanoTime() / 1_000_000L + 2000);
        assertTrue((Boolean) get(encoder, "audioDone"));
        assertNull(get(encoder, "audioCodec"));
        assertEquals(released, video().releases);
        assertNotNull(get(encoder, "videoFrame"));
        start(encoder, false);
        invoke(encoder, "emitReady");
        assertEquals(Arrays.asList("v0"), times());
        assertEquals(1, video().releases);
    }

    @Test
    public void invalidFirstAacConfigurationFallsBackWithoutReleasingTheHeldIdr() throws Exception {
        ScreenSource.Encoder encoder = encoder(true);
        read(encoder, true);
        read(encoder, false);
        set(encoder, "formatsReady", true);
        MediaFormat videoFormat = (MediaFormat) get(encoder, "videoFormat");
        videoFormat.setByteBuffer(
                "csd-0", ByteBuffer.wrap(annex(FlvFixtures.baselineSps(20, 15, 0))));
        videoFormat.setByteBuffer("csd-1", ByteBuffer.wrap(annex(FlvFixtures.pps())));
        ((MediaFormat) get(encoder, "audioFormat"))
                .setByteBuffer("csd-0", ByteBuffer.wrap(new byte[] {0x2A, 0x10}));
        video().output.add(new Sample(0, 1, annex(IDR)));
        audio().output.add(new Sample(1000, 0, new byte[] {1}));
        read(encoder, true);
        read(encoder, false);
        Codec audio = audio();
        Method begin =
                ScreenSource.Encoder.class.getDeclaredMethod(
                        "beginWriter", LiveRecorder.Output.class);
        begin.setAccessible(true);
        begin.invoke(encoder, new Object[] {null});
        assertNull(get(encoder, "audioCodec"));
        assertEquals(1, audio.releases);
        assertEquals(0, video().releases);
        assertNotNull(get(encoder, "videoFrame"));
        invoke(encoder, "emitReady");
        assertEquals(Arrays.asList("v0"), times());
    }

    @Test
    public void firstAdtsOrWrongAudioRateFallsBackToAVideoOnlyHeader() throws Exception {
        for (boolean adts : new boolean[] {true, false}) {
            sink = new Sink();
            ScreenSource.Encoder encoder = encoder(true);
            read(encoder, true);
            read(encoder, false);
            set(encoder, "formatsReady", true);
            MediaFormat format = (MediaFormat) get(encoder, "videoFormat");
            format.setByteBuffer(
                    "csd-0", ByteBuffer.wrap(annex(FlvFixtures.baselineSps(20, 15, 0))));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(annex(FlvFixtures.pps())));
            MediaFormat audioFormat = (MediaFormat) get(encoder, "audioFormat");
            audioFormat.setByteBuffer(
                    "csd-0",
                    ByteBuffer.wrap(
                            adts ? new byte[] {0x12, 0x10} : new byte[] {0x11, (byte) 0x90}));
            if (!adts) audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 48000);
            video().output.add(new Sample(0, 1, annex(IDR)));
            audio().output
                    .add(
                            new Sample(
                                    1000,
                                    0,
                                    adts
                                            ? new byte[] {(byte) 255, (byte) 0xF1, 1}
                                            : new byte[] {1}));
            read(encoder, true);
            read(encoder, false);
            Method begin =
                    ScreenSource.Encoder.class.getDeclaredMethod(
                            "beginWriter", LiveRecorder.Output.class);
            begin.setAccessible(true);
            begin.invoke(encoder, new Object[] {null});
            invoke(encoder, "emitReady");
            assertEquals(1, sink.bytes.toByteArray()[4]);
            assertEquals(Arrays.asList("v0"), times());
        }
    }

    @Test
    public void aStaticPictureDoesNotBlockLaterAacAndCarriesAnExplicitEndTime() throws Exception {
        ScreenSource.Encoder encoder = encoder(true);
        read(encoder, true);
        read(encoder, false);
        start(encoder, true);
        video().output.add(new Sample(0, 1, annex(IDR)));
        audio().output.add(new Sample(0, 0, new byte[] {1}));
        drive(encoder);
        audio().output.add(new Sample(23000, 0, new byte[] {2}));
        audio().output.add(new Sample(46000, 0, new byte[] {3}));
        drive(encoder);
        assertEquals(Arrays.asList("v0", "a0", "a23000", "a46000"), times());
        Method check = ScreenSource.Encoder.class.getDeclaredMethod("checkStall", long.class);
        check.setAccessible(true);
        check.invoke(encoder, System.nanoTime() / 1000000 + 5000);
        ((CaptureFlv.Writer) get(encoder, "writer")).end(6_000_000);
        java.io.File file = java.io.File.createTempFile("capture-static-", ".flv");
        try {
            java.nio.file.Files.write(file.toPath(), sink.bytes.toByteArray());
            CaptureFlv.Prefix prefix = CaptureFlv.prefix(file);
            assertTrue(prefix.ended);
            assertEquals(6_000_000, prefix.endTimeUs);
            assertEquals(1, prefix.videoSamples);
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test
    public void configuredAvcLevelFitsPhoneAndSquareCaptureMacroblockRates() {
        assertEquals(
                android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel31,
                ScreenSource.captureLevel(1280, 720));
        assertEquals(
                android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel4,
                ScreenSource.captureLevel(1080, 1920));
        assertEquals(
                android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel5,
                ScreenSource.captureLevel(1920, 1920));
    }

    @Test
    public void pcmFrameClockUsesOneCommonOriginWithoutReadTimingDrift() throws Exception {
        assertEquals(12000, ScreenSource.audioTimeUs(12000, 0, 44100));
        assertEquals(35219, ScreenSource.audioTimeUs(12000, 1024, 44100));
        assertEquals(1012000, ScreenSource.audioTimeUs(12000, 44100, 44100));
        assertEquals(
                12000 + 123456789L * 1_000_000L / 44100,
                ScreenSource.audioTimeUs(12000, 123456789, 44100));
        try {
            ScreenSource.audioTimeUs(-1, 0, 44100);
            fail();
        } catch (IOException expected) {
        }
    }

    ScreenSource.Encoder encoder(boolean audio) throws Exception {
        var app = RuntimeEnvironment.getApplication();
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        metrics.widthPixels = 320;
        metrics.heightPixels = 240;
        metrics.densityDpi = 160;
        ScreenSource source = new ScreenSource(app, metrics, audio, (output, state) -> sink);
        ScreenSource.Encoder encoder = source.new Encoder(null);
        owned.add(encoder);
        set(encoder, "videoCodec", MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC));
        if (audio)
            set(
                    encoder,
                    "audioCodec",
                    MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC));
        set(encoder, "audioDone", !audio);
        return encoder;
    }

    void start(ScreenSource.Encoder encoder, boolean audio) throws Exception {
        set(encoder, "formatsReady", true);
        set(
                encoder,
                "writer",
                new CaptureFlv.Writer(
                        sink,
                        new CaptureFlv.Config(
                                ByteBuffer.wrap(annex(FlvFixtures.baselineSps(20, 15, 0))),
                                ByteBuffer.wrap(annex(FlvFixtures.pps())),
                                audio ? ByteBuffer.wrap(new byte[] {0x12, 0x10}) : null,
                                44100,
                                2)));
    }

    void drive(ScreenSource.Encoder encoder) throws Exception {
        for (int i = 0; i < 100; i++) {
            boolean moved = read(encoder, true);
            if (get(encoder, "audioCodec") != null) moved |= read(encoder, false);
            moved |= invoke(encoder, "emitReady");
            if (!moved) break;
        }
    }

    static boolean read(ScreenSource.Encoder encoder, boolean video) throws Exception {
        return invoke(encoder, video ? "readVideo" : "readAudio");
    }

    static boolean invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        try {
            return (Boolean) method.invoke(target);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception) throw (Exception) failure.getCause();
            throw failure;
        }
    }

    static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    static Codec video() {
        return codecs.get(MediaFormat.MIMETYPE_VIDEO_AVC);
    }

    static Codec audio() {
        return codecs.get(MediaFormat.MIMETYPE_AUDIO_AAC);
    }

    static byte[] annex(byte[] bytes) {
        byte[] out = new byte[bytes.length + 4];
        out[3] = 1;
        System.arraycopy(bytes, 0, out, 4, bytes.length);
        return out;
    }

    List<String> times() throws Exception {
        List<String> result = new ArrayList<>();
        FlvReader.read(
                new ByteArrayInputStream(sink.bytes.toByteArray()),
                new FlvReader.Listener() {
                    public void onVideoConfig(String a, byte[] b, byte[] c, int w, int h) {}

                    public void onAudioConfig(byte[] a, int r, int c) {}

                    public void onVideo(byte[] a, long p, boolean k) {
                        result.add("v" + p);
                    }

                    public void onAudio(byte[] a, long p) {
                        result.add("a" + p);
                    }
                },
                true,
                true);
        return result;
    }

    static class Sink implements CaptureFlv.Sink {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        public void append(byte[] a, int b, int c) throws IOException {
            bytes.write(a);
        }

        public void checkpoint(long a, boolean b) {}
    }

    static class Sample {
        final long time;
        final int flags;
        final byte[] bytes;

        Sample(long time, int flags, byte[] bytes) {
            this.time = time;
            this.flags = flags;
            this.bytes = bytes;
        }
    }

    static class Observed extends RuntimeException {}

    static class Control implements LiveRecorder.Control {
        public boolean stopRequested() {
            return false;
        }

        public void onStop(Closeable c) {}

        public void release(Closeable c) {}

        public boolean pause(long ms) {
            return true;
        }
    }

    @Implements(MediaCodec.class)
    public static class Codec extends ShadowMediaCodec {
        final ArrayDeque<Sample> output = new ArrayDeque<>();
        boolean formatReady = true, formatReported;
        String mime;
        Sample held;
        int dequeues, releases;

        @Override
        @Implementation
        protected void __constructor__(String name, boolean byType, boolean encoder) {
            if (startup != null) {
                try {
                    observedOrigin = ((Number) get(startup, "originUs")).longValue();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                throw new Observed();
            }
            super.__constructor__(name, byType, encoder);
            mime = name;
            codecs.put(name, this);
        }

        @Implementation
        protected int dequeueOutputBuffer(MediaCodec.BufferInfo info, long timeout) {
            dequeues++;
            assertNull(held);
            if (!formatReported) {
                if (!formatReady) return MediaCodec.INFO_TRY_AGAIN_LATER;
                formatReported = true;
                return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
            }
            held = output.poll();
            if (held == null) return MediaCodec.INFO_TRY_AGAIN_LATER;
            info.set(1, held.bytes.length, held.time, held.flags);
            return 0;
        }

        @Implementation
        protected MediaFormat getOutputFormat() {
            return MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime)
                    ? MediaFormat.createVideoFormat(mime, 320, 240)
                    : MediaFormat.createAudioFormat(mime, 44100, 2);
        }

        @Implementation
        protected ByteBuffer getOutputBuffer(int index) {
            ByteBuffer out = ByteBuffer.allocate(held.bytes.length + 2);
            out.position(1);
            out.put(held.bytes);
            return out;
        }

        @Implementation
        protected void releaseOutputBuffer(int index, boolean render) {
            assertNotNull(held);
            assertFalse(render);
            releases++;
            held = null;
        }
    }
}

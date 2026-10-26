package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.content.*;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29)
public class CaptureRecoveryTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    @Rule public SettingsContextRule settings = new SettingsContextRule();
    Context app;
    RecordingRetention store;
    static final long FREE = 1024L * 1024 * 1024;

    @Before
    public void setup() throws Exception {
        File durable = files.newFolder("durable"), cache = files.newFolder("cache");
        app =
                new ContextWrapper(RuntimeEnvironment.getApplication()) {
                    public File getNoBackupFilesDir() {
                        return durable;
                    }

                    public File getCacheDir() {
                        return cache;
                    }

                    public Context getApplicationContext() {
                        return this;
                    }
                };
        store = RecordingRetention.in(app);
        RecordingRetentionTest.Provider.reset(files.newFolder("provider"), app.getPackageName());
        Robolectric.setupContentProvider(RecordingRetentionTest.Provider.class, "media");
    }

    RecordingRetention.Record begin() throws Exception {
        RecordingRetention.Record record = store.begin("flv", "creator", 1760480102000L);
        record.update("capture", true);
        return record;
    }

    static final class Output implements LiveRecorder.Output, AutoCloseable {
        final FileOutputStream stream;
        int syncs;
        int fragment = -1;

        Output(File file) throws IOException {
            stream = new FileOutputStream(file);
        }

        public void write(byte[] data, int offset, int count) throws IOException {
            if (fragment >= 0) {
                stream.write(data, offset, Math.min(fragment, count));
                throw new IOException("process stopped during tag write");
            }
            stream.write(data, offset, count);
        }

        public void checkpoint() throws IOException {
            stream.flush();
            stream.getFD().sync();
            syncs++;
        }

        public void close() throws IOException {
            stream.close();
        }
    }

    static byte[] frame(boolean key) throws Exception {
        return CaptureFlvTest.annex(key ? CaptureFlvTest.IDR : CaptureFlvTest.P);
    }

    void key(CaptureFlv.Writer writer) throws Exception {
        byte[] data = frame(true);
        writer.video(ByteBuffer.wrap(data), 0, data.length, 0);
    }

    @Test
    public void syncedCheckpointsAreExactCompleteTagsAndAnEosReceiptIsDurable() throws Exception {
        RecordingRetention.Record record = begin();
        try (Output out = new Output(record.source())) {
            CaptureFlv.Writer writer =
                    new CaptureFlv.Writer(
                            new CaptureJournal(record, out, LiveRecorder.ScreenAudio.VIDEO_ONLY),
                            CaptureFlvTest.config(false));
            key(writer);
            assertTrue(out.syncs > 0);
            assertEquals(record.source().length(), record.number("captureBytes"));
            assertEquals("false", record.get("captureComplete"));
            writer.end();
            assertEquals("true", record.get("captureComplete"));
            assertEquals(record.source().length(), record.number("captureBytes"));
        }
        record.close();
    }

    @Test
    public void deathDuringATagRecoversOnlyTheCompletePrefixAndNeverRewritesTheOriginal()
            throws Exception {
        RecordingRetention.Record record = begin();
        String id = record.id;
        long edge;
        try (Output out = new Output(record.source())) {
            CaptureFlv.Writer writer =
                    new CaptureFlv.Writer(
                            new CaptureJournal(record, out, LiveRecorder.ScreenAudio.VIDEO_ONLY),
                            CaptureFlvTest.config(false));
            key(writer);
            edge = writer.bytes;
            out.fragment = 7;
            byte[] p = frame(false);
            try {
                writer.video(ByteBuffer.wrap(p), 0, p.length, 33000);
                fail();
            } catch (IOException expected) {
            }
            writer.checkpoint();
        }
        byte[] original = Files.readAllBytes(record.source().toPath());
        record.close();
        RecordingRetention.Record restart = store.open(id);
        CaptureFlv.Prefix prefix = CaptureJournal.recoverPrefix(restart);
        assertEquals(edge, prefix.completeBytes);
        assertTrue(prefix.incomplete);
        assertEquals("true", restart.get("captureIncomplete"));
        assertArrayEquals(original, Files.readAllBytes(restart.source().toPath()));
        FakeMuxer[] made = {null};
        CaptureMuxer muxer =
                new CaptureMuxer(
                        restart,
                        prefix,
                        file -> {
                            made[0] = new FakeMuxer(file);
                            return made[0];
                        },
                        file -> FREE);
        LiveRemux.Summary summary =
                LiveRemux.fromFlv(restart.source(), muxer, prefix.completeBytes);
        assertEquals(1, summary.videoSamples);
        assertEquals(1, made[0].samples);
        assertTrue(made[0].finished);
        restart.close();
    }

    @Test
    public void changedOrMissingDurableCodecConfigurationCannotPublishARecoveredPrefix()
            throws Exception {
        RecordingRetention.Record record = withCapture(false);
        byte[] original = Files.readAllBytes(record.source().toPath());
        record.update("captureConfig", "different");
        try {
            CaptureJournal.recoverPrefix(record);
            fail();
        } catch (IOException expected) {
        }
        assertArrayEquals(original, Files.readAllBytes(record.source().toPath()));
        assertFalse(record.acknowledged());
        record.close();
    }

    @Test
    public void sourceSizeAndSpaceFailuresHappenBeforeWritingTheNextWholeTag() throws Exception {
        for (boolean size : new boolean[] {true, false}) {
            RecordingRetention.Record record = begin();
            AtomicLong free = new AtomicLong(FREE);
            try (Output out = new Output(record.source())) {
                CaptureFlv.Writer writer =
                        new CaptureFlv.Writer(
                                new CaptureJournal(
                                        record,
                                        out,
                                        LiveRecorder.ScreenAudio.VIDEO_ONLY,
                                        new CaptureSpace(size ? 150 : 10000),
                                        file -> free.get()),
                                CaptureFlvTest.config(false));
                key(writer);
                long edge = record.source().length();
                byte[] p = new byte[300];
                Arrays.fill(p, (byte) 0x55);
                p[0] = 0x41;
                p[1] = (byte) 0xE0;
                byte[] data = CaptureFlvTest.annex(p);
                if (!size) free.set(0);
                try {
                    writer.video(ByteBuffer.wrap(data), 0, data.length, 33000);
                    fail();
                } catch (LiveRecorder.SpoolFull full) {
                    assertEquals(
                            size ? LiveRecorder.End.SIZE_LIMIT : LiveRecorder.End.STORAGE,
                            full.reason);
                }
                assertEquals(edge, record.source().length());
                writer.checkpoint();
            }
            record.update("state", "DISCARDED");
            record.close();
        }
    }

    @Test
    public void aStaticPictureGetsAnExplicitEndDurationWithoutAnInventedVideoSample()
            throws Exception {
        RecordingRetention.Record record = begin();
        try (Output out = new Output(record.source())) {
            CaptureFlv.Writer writer =
                    new CaptureFlv.Writer(
                            new CaptureJournal(record, out, LiveRecorder.ScreenAudio.VIDEO_ONLY),
                            CaptureFlvTest.config(false));
            key(writer);
            writer.end(6_000_000);
        }
        CaptureFlv.Prefix prefix = CaptureJournal.recoverPrefix(record);
        FakeMuxer[] made = {null};
        CaptureMuxer muxer =
                new CaptureMuxer(
                        record,
                        prefix,
                        file -> {
                            made[0] = new FakeMuxer(file);
                            return made[0];
                        },
                        file -> FREE);
        LiveRemux.Summary result =
                LiveRemux.fromFlv(record.source(), muxer, prefix.completeBytes, true);
        assertEquals(1, result.videoSamples);
        assertEquals(1, made[0].samples);
        assertEquals(6_000_000, made[0].videoEndUs);
        assertEquals(6_000_000, result.durationUs);
        record.close();
    }

    @Test
    public void aNativeBudgetFailureDoesNotConstructTheMuxerOrTouchTheSource() throws Exception {
        RecordingRetention.Record record = withCapture(false);
        CaptureFlv.Prefix prefix = CaptureJournal.recoverPrefix(record);
        AtomicInteger opened = new AtomicInteger();
        byte[] source = Files.readAllBytes(record.source().toPath());
        try {
            new CaptureMuxer(
                    record,
                    prefix,
                    file -> {
                        opened.incrementAndGet();
                        return new FakeMuxer(file);
                    },
                    file -> 0);
            fail();
        } catch (CaptureSpace.Limit expected) {
        }
        assertEquals(0, opened.get());
        assertArrayEquals(source, Files.readAllBytes(record.source().toPath()));
        record.close();
    }

    @Test
    public void
            nativeCallsReserveBeforeStartAndAnUnexpectedOutputGrowthAbortsWithoutDeletingSource()
                    throws Exception {
        RecordingRetention.Record record = withCapture(false);
        CaptureFlv.Prefix prefix = CaptureJournal.recoverPrefix(record);
        AtomicLong free = new AtomicLong(FREE);
        FakeMuxer[] made = {null};
        CaptureMuxer muxer =
                new CaptureMuxer(
                        record,
                        prefix,
                        file -> {
                            made[0] = new FakeMuxer(file);
                            return made[0];
                        },
                        file -> free.get());
        free.set(0);
        try {
            muxer.start();
            fail();
        } catch (CaptureSpace.Limit expected) {
        }
        assertFalse(made[0].started);
        muxer.abort();
        assertTrue(made[0].aborted);
        assertTrue(record.source().exists());
        free.set(FREE);
        CaptureMuxer oversized =
                new CaptureMuxer(
                        record,
                        prefix,
                        file -> {
                            made[0] = new FakeMuxer(file);
                            made[0].oversize =
                                    CaptureSpace.remuxBound(prefix.payloadBytes, prefix.samples)
                                            + 1;
                            return made[0];
                        },
                        file -> free.get());
        try {
            LiveRemux.fromFlv(record.source(), oversized, prefix.completeBytes);
            fail();
        } catch (CaptureSpace.Limit expected) {
            assertTrue(expected.size);
        }
        assertTrue(made[0].aborted);
        assertTrue(record.source().exists());
        record.close();
    }

    @Test
    public void nativeFinishCannotCertifyAnUnconsumedScannedPrefix() throws Exception {
        RecordingRetention.Record record = withCapture(false);
        CaptureFlv.Prefix prefix = CaptureJournal.recoverPrefix(record);
        FakeMuxer[] made = {null};
        CaptureMuxer muxer =
                new CaptureMuxer(
                        record,
                        prefix,
                        file -> {
                            made[0] = new FakeMuxer(file);
                            return made[0];
                        },
                        file -> FREE);
        muxer.start();
        try {
            muxer.finish();
            fail();
        } catch (IOException expected) {
        }
        assertFalse(made[0].finished);
        muxer.abort();
        record.close();
    }

    @Test
    public void publicationHeadroomFailureLeavesNoPendingCopyAndKeepsTheFinalizedOriginal()
            throws Exception {
        RecordingRetention.Record record = withCapture(false);
        CaptureJournal.recoverPrefix(record);
        byte[] encoded =
                "model finalized MP4 for publication receipt"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(record.file("remux.mp4").toPath(), encoded);
        record.finalized(record.file("remux.mp4"), "VIDEO_ONLY");
        try {
            MediaFileWriter.publishForResult(
                    app,
                    record.finalized(),
                    "capture.mp4",
                    "video/mp4",
                    "Movies/Hushfeed",
                    true,
                    10000,
                    new RecordingPublication(app, record, file -> 0));
            fail();
        } catch (CaptureSpace.Limit expected) {
        }
        assertFalse(record.acknowledged());
        assertTrue(record.source().exists());
        assertArrayEquals(encoded, Files.readAllBytes(record.finalized().toPath()));
        assertTrue(RecordingRetentionTest.Provider.rows.isEmpty());
        record.close();
    }

    @Test
    public void restartAndRepeatedRecoveryPublishTheSameJournaledCaptureOnlyOnce()
            throws Exception {
        RecordingRetention.Record record = withCapture(true);
        CaptureFlv.Prefix prefix = CaptureJournal.recoverPrefix(record);
        assertTrue(prefix.incomplete);
        String id = record.id;
        byte[] encoded =
                "model validated MP4 from the complete prefix"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(record.file("remux.mp4").toPath(), encoded);
        record.finalized(record.file("remux.mp4"), "VIDEO_ONLY");
        record.close();
        assertEquals(1, LiveRecordingSaver.recoverRetained(app).size());
        assertTrue(store.ids().isEmpty());
        assertTrue(LiveRecordingSaver.recoverRetained(app).isEmpty());
        assertEquals(1, RecordingRetentionTest.Provider.inserts);
        assertEquals(1, RecordingRetentionTest.Provider.rows.size());
    }

    @Test
    public void theRealRecorderReportsABudgetStopAsPartialAndRetainsItsCheckpoint()
            throws Exception {
        RecordingRetention.Record record = begin();
        AtomicReference<LiveRecorder.Result> result = new AtomicReference<>();
        LiveRecorder.Source source =
                (output, control) -> {
                    try {
                        CaptureFlv.Writer writer =
                                new CaptureFlv.Writer(
                                        new CaptureJournal(
                                                record,
                                                output,
                                                LiveRecorder.ScreenAudio.VIDEO_ONLY,
                                                new CaptureSpace(150),
                                                file -> FREE),
                                        CaptureFlvTest.config(false));
                        key(writer);
                        byte[] data = new byte[300];
                        Arrays.fill(data, (byte) 0x55);
                        data[0] = 0x41;
                        data[1] = (byte) 0xE0;
                        byte[] p = CaptureFlvTest.annex(data);
                        writer.video(ByteBuffer.wrap(p), 0, p.length, 33000);
                    } catch (IOException failure) {
                        throw failure;
                    } catch (Exception failure) {
                        throw new IOException(failure);
                    }
                };
        LiveRecorder.Finisher finisher =
                new LiveRecorder.Finisher() {
                    public boolean ownsFiles() {
                        return true;
                    }

                    public boolean usefulWithoutSpool() {
                        return true;
                    }

                    public boolean partial() {
                        return true;
                    }

                    public LiveRecorder.Published finish(
                            File file, LiveRecorder.Target target, long started)
                            throws IOException {
                        assertTrue(CaptureJournal.recoverPrefix(record).usable());
                        return new LiveRecorder.Published("partial.mp4", "model publication");
                    }
                };
        LiveRecorder recorder =
                new LiveRecorder(
                        new LiveRecorder.Target(
                                "", LiveRecorder.Transport.SCREEN, "creator", "", ""),
                        record.source(),
                        source,
                        finisher,
                        result::set);
        recorder.start();
        assertTrue(recorder.join(5000));
        assertEquals(LiveRecorder.Outcome.SAVED_PARTIAL, result.get().outcome);
        assertEquals(LiveRecorder.End.SIZE_LIMIT, result.get().end);
        assertTrue(record.number("captureBytes") > 0);
        assertTrue(record.source().exists());
        record.close();
    }

    RecordingRetention.Record withCapture(boolean torn) throws Exception {
        RecordingRetention.Record record = begin();
        try (Output out = new Output(record.source())) {
            CaptureFlv.Writer writer =
                    new CaptureFlv.Writer(
                            new CaptureJournal(record, out, LiveRecorder.ScreenAudio.VIDEO_ONLY),
                            CaptureFlvTest.config(false));
            key(writer);
            if (torn) {
                out.stream.write(new byte[] {9, 0, 0});
                writer.checkpoint();
            } else writer.end();
        }
        return record;
    }

    static class FakeMuxer implements LiveRemux.Mp4Writer {
        final File file;
        boolean started, finished, aborted;
        long oversize;
        int samples;
        long videoEndUs = -1;

        FakeMuxer(File file) throws IOException {
            this.file = file;
            new FileOutputStream(file).close();
        }

        public int addVideoTrack(String m, byte[] a, byte[] b, int w, int h) {
            return 0;
        }

        public int addAudioTrack(byte[] a, int r, int c) {
            return 1;
        }

        public void start() {
            started = true;
        }

        public void write(int track, byte[] data, long pts, boolean key) throws IOException {
            samples++;
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write(data);
            }
            if (oversize > 0)
                try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
                    output.setLength(oversize);
                }
        }

        public void endVideo(long presentationUs) {
            videoEndUs = presentationUs;
        }

        public void finish() throws IOException {
            finished = true;
            try (FileOutputStream output = new FileOutputStream(file, true)) {
                output.write(new byte[32]);
            }
        }

        public void abort() {
            aborted = true;
        }
    }
}

package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.*;

public class CaptureFlvTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    static final byte[] IDR = {(byte) 0x65, (byte) 0xB8, 0x20},
            P = {(byte) 0x41, (byte) 0xE0, 0x20},
            B = {(byte) 0x41, (byte) 0xA0, 0x20};

    static byte[] annex(byte[]... nals) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] nal : nals) {
            out.write(new byte[] {0, 0, 0, 1});
            out.write(nal);
        }
        return out.toByteArray();
    }

    static byte[] avcc(byte[]... nals) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);
        for (byte[] nal : nals) {
            data.writeInt(nal.length);
            data.write(nal);
        }
        return out.toByteArray();
    }

    static CaptureFlv.Config config(boolean audio) throws Exception {
        return new CaptureFlv.Config(
                ByteBuffer.wrap(annex(FlvFixtures.baselineSps(20, 15, 0))),
                ByteBuffer.wrap(annex(FlvFixtures.pps())),
                audio ? ByteBuffer.wrap(new byte[] {0x12, 0x10}) : null,
                44100,
                2);
    }

    static class Sink implements CaptureFlv.Sink {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<Long> edges = new ArrayList<>();
        boolean failCheckpoint;
        int failAt = -1;

        @Override
        public void append(byte[] data, int payload, int samples) throws IOException {
            if (failAt >= 0) {
                bytes.write(data, 0, Math.min(failAt, data.length));
                throw new IOException("death during write");
            }
            bytes.write(data);
        }

        @Override
        public void checkpoint(long bytes, boolean ended) throws IOException {
            edges.add(bytes);
            if (failCheckpoint) throw new IOException("checkpoint interrupted");
        }
    }

    File file(byte[] data) throws Exception {
        File file = files.newFile();
        Files.write(file.toPath(), data);
        return file;
    }

    @Test
    public void avcAacRoundTripPreservesFirstIdrAndFirstAudio() throws Exception {
        Sink sink = new Sink();
        CaptureFlv.Writer writer = new CaptureFlv.Writer(sink, config(true));
        writer.audio(ByteBuffer.wrap(new byte[] {1, 2, 3}), 0, 3, 1000);
        byte[] frame = annex(new byte[] {9, 0x10}, new byte[] {6, 5, 1, 0x44, (byte) 0x80}, IDR);
        writer.video(ByteBuffer.wrap(frame), 0, frame.length, 2000);
        writer.end();
        CaptureFlv.Prefix prefix = CaptureFlv.prefix(file(sink.bytes.toByteArray()));
        assertTrue(prefix.ended);
        assertFalse(prefix.incomplete);
        assertEquals(1, prefix.videoSamples);
        assertEquals(1, prefix.audioSamples);
        List<String> values = new ArrayList<>();
        FlvReader.read(
                new ByteArrayInputStream(sink.bytes.toByteArray()),
                new FlvReader.Listener() {
                    public void onVideoConfig(String mime, byte[] a, byte[] b, int w, int h) {
                        assertEquals(320, w);
                        assertEquals(240, h);
                    }

                    public void onAudioConfig(byte[] a, int rate, int channels) {
                        assertEquals(44100, rate);
                        assertEquals(2, channels);
                    }

                    public void onVideo(byte[] a, long pts, boolean key) {
                        assertTrue(key);
                        values.add("v" + pts);
                    }

                    public void onAudio(byte[] a, long pts) {
                        assertArrayEquals(new byte[] {1, 2, 3}, a);
                        values.add("a" + pts);
                    }
                });
        assertEquals(Arrays.asList("a0", "v1000"), values);
        assertTrue(sink.edges.size() >= 2);
    }

    @Test
    public void bufferOffsetsDoNotChangeCodecOwnedPositionOrLimit() throws Exception {
        Sink sink = new Sink();
        CaptureFlv.Writer writer = new CaptureFlv.Writer(sink, config(false));
        byte[] data = annex(IDR);
        ByteBuffer buffer = ByteBuffer.allocate(data.length + 12);
        buffer.position(5);
        buffer.put(data);
        buffer.position(2);
        buffer.limit(3);
        writer.video(buffer, 5, data.length, 0);
        assertEquals(2, buffer.position());
        assertEquals(3, buffer.limit());
        assertEquals(1, writer.videoSamples);
    }

    @Test
    public void fourByteAvccAndMixedAnnexBPrefixesRoundTrip() throws Exception {
        for (byte[] frame :
                new byte[][] {
                    avcc(new byte[] {9, 0x10}, IDR),
                    concat(new byte[] {0, 0, 1, 9, 0x10, 0, 0, 0, 1}, IDR)
                }) {
            Sink sink = new Sink();
            CaptureFlv.Writer writer = new CaptureFlv.Writer(sink, config(false));
            writer.video(ByteBuffer.wrap(frame), 0, frame.length, 0);
            writer.end();
            assertEquals(1, CaptureFlv.prefix(file(sink.bytes.toByteArray())).videoSamples);
        }
    }

    @Test
    public void avccConfigurationMustMatchFourByteLengthsAndBaselineProfile() throws Exception {
        CaptureFlv.Config config = config(false);
        new CaptureFlv.Config(ByteBuffer.wrap(config.avc), null, null, 0, 0);
        byte[] bad = config.avc.clone();
        bad[4] = (byte) 0xFD;
        reject(() -> new CaptureFlv.Config(ByteBuffer.wrap(bad), null, null, 0, 0));
        byte[] main = FlvFixtures.baselineSps(20, 15, 0);
        main[1] = 77;
        reject(
                () ->
                        new CaptureFlv.Config(
                                ByteBuffer.wrap(annex(main)),
                                ByteBuffer.wrap(annex(FlvFixtures.pps())),
                                null,
                                0,
                                0));
    }

    @Test
    public void bSlicesAndRegressingPtsAreRejectedWithoutInventingTimes() throws Exception {
        Sink sink = new Sink();
        CaptureFlv.Writer writer = new CaptureFlv.Writer(sink, config(false));
        byte[] key = annex(IDR);
        writer.video(ByteBuffer.wrap(key), 0, key.length, 10000);
        int edge = sink.bytes.size();
        byte[] b = annex(B);
        reject(() -> writer.video(ByteBuffer.wrap(b), 0, b.length, 20000));
        byte[] p = annex(P);
        reject(() -> writer.video(ByteBuffer.wrap(p), 0, p.length, 9999));
        assertEquals(edge, sink.bytes.size());
    }

    @Test
    public void leadingNonIdrIsSkippedButTheFirstIdrIsKept() throws Exception {
        Sink sink = new Sink();
        CaptureFlv.Writer writer = new CaptureFlv.Writer(sink, config(false));
        byte[] p = annex(P), key = annex(IDR);
        assertFalse(writer.video(ByteBuffer.wrap(p), 0, p.length, 0));
        writer.video(ByteBuffer.wrap(key), 0, key.length, 1000);
        assertEquals(1, writer.videoSamples);
    }

    @Test
    public void rawAacRejectsAdtsWrongProfileChannelsOrRate() throws Exception {
        for (byte[] asc :
                new byte[][] {
                    {0x2A, 0x10}, {0x12, 0x08}, {0x11, (byte) 0x90}, {0x12, 0x11}, {0x12, 0x10, 0}
                })
            reject(
                    () ->
                            new CaptureFlv.Config(
                                    ByteBuffer.wrap(config(false).avc),
                                    null,
                                    ByteBuffer.wrap(asc),
                                    44100,
                                    2));
        CaptureFlv.Writer writer = new CaptureFlv.Writer(new Sink(), config(true));
        reject(
                () ->
                        writer.audio(
                                ByteBuffer.wrap(new byte[] {(byte) 255, (byte) 0xF1, 1}), 0, 3, 0));
    }

    @Test
    public void malformedLengthsAndRangesNeverBecomeSamples() throws Exception {
        CaptureFlv.Writer writer = new CaptureFlv.Writer(new Sink(), config(false));
        for (byte[] frame :
                new byte[][] {
                    {0, 0, 0, 10, 0x65, 1}, {0, 0, 0, 0}, {0, 0}, {0, 0, 0, 2, (byte) 0xE5, 1}
                }) reject(() -> writer.video(ByteBuffer.wrap(frame), 0, frame.length, 0));
        reject(() -> writer.video(ByteBuffer.allocate(10), 9, 2, 0));
        reject(() -> writer.video(ByteBuffer.allocate(10), 0, CaptureFlv.MAX_TAG_BYTES, 0));
        assertEquals(0, writer.videoSamples);
    }

    @Test
    public void excessiveNalCountsAndTimestampRangeFailClosed() throws Exception {
        CaptureFlv.Writer writer = new CaptureFlv.Writer(new Sink(), config(false));
        byte[][] nals = new byte[257][];
        Arrays.fill(nals, new byte[] {9, 0x10});
        byte[] many = avcc(nals);
        reject(() -> writer.video(ByteBuffer.wrap(many), 0, many.length, 0));
        byte[] key = annex(IDR);
        reject(() -> writer.video(ByteBuffer.wrap(key), 0, key.length, 0x8000_0000L * 1000));
    }

    @Test
    public void everyTruncatedTagOrTrailerStopsAtThePreviousCompleteEdge() throws Exception {
        Sink sink = new Sink();
        CaptureFlv.Writer writer = new CaptureFlv.Writer(sink, config(false));
        byte[] key = annex(IDR), p = annex(P);
        writer.video(ByteBuffer.wrap(key), 0, key.length, 0);
        int first = sink.bytes.size();
        writer.video(ByteBuffer.wrap(p), 0, p.length, 33000);
        int second = sink.bytes.size();
        writer.end();
        byte[] full = sink.bytes.toByteArray();
        File input = files.newFile();
        for (int cut = 0; cut <= full.length; cut++) {
            Files.write(input.toPath(), Arrays.copyOf(full, cut));
            CaptureFlv.Prefix prefix = CaptureFlv.prefix(input);
            assertTrue(prefix.completeBytes <= cut);
            assertEquals(cut < first ? 0 : cut < second ? 1 : 2, prefix.videoSamples);
            assertEquals(cut < full.length, prefix.incomplete);
        }
    }

    @Test
    public void badTrailerAndFakeKeyFrameDoNotAdvanceRecoveryStatistics() throws Exception {
        Sink sink = new Sink();
        CaptureFlv.Writer writer = new CaptureFlv.Writer(sink, config(false));
        byte[] key = annex(IDR), p = annex(P);
        writer.video(ByteBuffer.wrap(key), 0, key.length, 0);
        int first = sink.bytes.size();
        writer.video(ByteBuffer.wrap(p), 0, p.length, 33000);
        byte[] bad = sink.bytes.toByteArray();
        bad[bad.length - 1] ^= 1;
        CaptureFlv.Prefix prefix = CaptureFlv.prefix(file(bad));
        assertEquals(first, prefix.completeBytes);
        assertEquals(1, prefix.videoSamples);
        bad = sink.bytes.toByteArray();
        bad[first + 11] = 0x17;
        prefix = CaptureFlv.prefix(file(bad));
        assertEquals(first, prefix.completeBytes);
        assertEquals(1, prefix.videoSamples);
    }

    @Test
    public void midWriteDeathAndCheckpointFailurePreserveTheLastCompleteFrame() throws Exception {
        Sink sink = new Sink();
        CaptureFlv.Writer writer = new CaptureFlv.Writer(sink, config(false));
        byte[] key = annex(IDR), p = annex(P);
        writer.video(ByteBuffer.wrap(key), 0, key.length, 0);
        long first = writer.bytes;
        sink.failAt = 7;
        CaptureFlv.Writer firstWriter = writer;
        reject(() -> firstWriter.video(ByteBuffer.wrap(p), 0, p.length, 33000));
        assertEquals(first, CaptureFlv.prefix(file(sink.bytes.toByteArray())).completeBytes);
        sink = new Sink();
        writer = new CaptureFlv.Writer(sink, config(false));
        sink.failCheckpoint = true;
        CaptureFlv.Writer capture = writer;
        reject(() -> capture.video(ByteBuffer.wrap(key), 0, key.length, 0));
        assertEquals(1, CaptureFlv.prefix(file(sink.bytes.toByteArray())).videoSamples);
    }

    @Test
    public void eosIsIdempotentAndMissingEosOrTrailingGarbageIsFlagged() throws Exception {
        Sink sink = new Sink();
        CaptureFlv.Writer writer = new CaptureFlv.Writer(sink, config(false));
        byte[] key = annex(IDR);
        writer.video(ByteBuffer.wrap(key), 0, key.length, 0);
        assertTrue(CaptureFlv.prefix(file(sink.bytes.toByteArray())).incomplete);
        writer.end();
        int bytes = sink.bytes.size();
        writer.end();
        assertEquals(bytes, sink.bytes.size());
        reject(() -> writer.video(ByteBuffer.wrap(key), 0, key.length, 1000));
        CaptureFlv.Prefix prefix =
                CaptureFlv.prefix(file(concat(sink.bytes.toByteArray(), new byte[] {1})));
        assertTrue(prefix.incomplete);
        assertTrue(prefix.ended);
    }

    @Test
    public void localRelativeOriginKeepsCrossTrackPtsWithoutClampingAnEarlierVideoFrame()
            throws Exception {
        Sink sink = new Sink();
        CaptureFlv.Writer writer = new CaptureFlv.Writer(sink, config(true));
        byte[] key = annex(IDR), p = annex(P);
        writer.video(ByteBuffer.wrap(key), 0, key.length, 1000);
        writer.audio(ByteBuffer.wrap(new byte[] {1}), 0, 1, 9000);
        writer.video(ByteBuffer.wrap(p), 0, p.length, 2000);
        writer.end(10000);
        List<Long> pts = new ArrayList<>();
        FlvReader.read(
                new ByteArrayInputStream(sink.bytes.toByteArray()),
                new FlvReader.Listener() {
                    public void onVideoConfig(String m, byte[] a, byte[] b, int w, int h) {}

                    public void onAudioConfig(byte[] a, int r, int c) {}

                    public void onVideo(byte[] a, long p, boolean k) {
                        pts.add(p);
                    }

                    public void onAudio(byte[] a, long p) {
                        pts.add(p);
                    }
                },
                true,
                true);
        assertEquals(Arrays.asList(1000L, 9000L, 2000L), pts);
        assertTrue(CaptureFlv.prefix(file(sink.bytes.toByteArray())).ended);
    }

    private interface Work {
        void run() throws Exception;
    }

    private static void reject(Work work) throws Exception {
        try {
            work.run();
            fail("invalid capture accepted");
        } catch (IOException expected) {
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}

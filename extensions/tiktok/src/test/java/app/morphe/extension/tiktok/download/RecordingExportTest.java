package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Random;
import java.util.zip.ZipFile;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29)
public class RecordingExportTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    @Rule public SettingsContextRule settings = new SettingsContextRule();
    RecordingRetention store;

    @Before
    public void setup() throws Exception {
        File durable = files.newFolder("durable"), cache = files.newFolder("cache");
        Context app = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getNoBackupFilesDir() { return durable; }
            @Override public File getCacheDir() { return cache; }
            @Override public Context getApplicationContext() { return this; }
        };
        store = RecordingRetention.in(app);
    }

    @Test
    public void incompressibleExportUsesBulkWritesAndLeavesTheCallerStreamOpen() throws Exception {
        byte[] source = randomBytes(2 * 1024 * 1024, 47_503);
        byte[] screen = randomBytes(65_553, 47_504);
        try (RecordingRetention.Record record = store.begin("flv", "fixture", 1)) {
            Files.write(record.source().toPath(), source);
            Files.write(record.file("screen.mp4").toPath(), screen);
            CountingSink output = new CountingSink();
            RecordingExport.exportFiles(record, output);
            byte[] archive = output.data.toByteArray();
            assertTrue("the fixture must exercise megabytes of compressed output", archive.length > source.length);
            assertTrue("archive payload reached the sink one byte at a time: " + output.singleWrites, output.singleWrites < 4096);
            assertTrue("archive payload never used a bulk write", output.bulkBytes > archive.length * 99L / 100L);
            assertTrue(output.bulkWrites > 0);
            assertTrue(output.flushes > 0);
            assertEquals(0, output.closes);
            assertFalse(output.closed);
            File zipFile = files.newFile("export.zip");
            Files.write(zipFile.toPath(), archive);
            try (ZipFile zip = new ZipFile(zipFile)) {
                assertEquals(2, zip.size());
                assertArrayEquals(source, zip.getInputStream(zip.getEntry(record.source().getName())).readAllBytes());
                assertArrayEquals(screen, zip.getInputStream(zip.getEntry("screen.mp4")).readAllBytes());
            }
            assertArrayEquals(source, Files.readAllBytes(record.source().toPath()));
            assertArrayEquals(screen, Files.readAllBytes(record.file("screen.mp4").toPath()));
            assertFalse(record.acknowledged());
            output.write(7);
            output.flush();
            output.close();
            assertEquals(1, output.closes);
        }
    }

    @Test
    public void aWriteFailurePropagatesWithoutClosingTheCallerOrRemovingOriginals() throws Exception {
        byte[] source = randomBytes(64 * 1024, 47_505);
        try (RecordingRetention.Record record = store.begin("flv", "fixture", 2)) {
            Files.write(record.source().toPath(), source);
            CountingSink output = new CountingSink();
            output.failAfter = 8192;
            try {
                RecordingExport.exportFiles(record, output);
                fail("an output failure was swallowed");
            } catch (IOException failure) {
                assertSame(output.firstFailure, failure);
            }
            assertTrue(output.data.size() > 0);
            assertEquals(0, output.closes);
            assertFalse(output.closed);
            assertArrayEquals(source, Files.readAllBytes(record.source().toPath()));
            assertFalse(record.acknowledged());
        }
    }

    @Test
    public void aFlushFailureIsStillReportedAndTheCallerOwnsClosing() throws Exception {
        byte[] source = randomBytes(32 * 1024, 47_506);
        try (RecordingRetention.Record record = store.begin("flv", "fixture", 3)) {
            Files.write(record.source().toPath(), source);
            CountingSink output = new CountingSink();
            output.failFlush = true;
            try {
                RecordingExport.exportFiles(record, output);
                fail("a flush failure was swallowed");
            } catch (IOException failure) {
                assertSame(output.firstFailure, failure);
            }
            assertTrue(output.flushes > 0);
            assertEquals(0, output.closes);
            assertFalse(output.closed);
            assertArrayEquals(source, Files.readAllBytes(record.source().toPath()));
            assertFalse(record.acknowledged());
        }
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static final class CountingSink extends OutputStream {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        int singleWrites, bulkWrites, flushes, closes;
        long bulkBytes;
        int failAfter = Integer.MAX_VALUE;
        boolean closed, failFlush;
        IOException firstFailure;

        private IOException failure() {
            IOException failure = new IOException("scripted export I/O failure");
            if (firstFailure == null) firstFailure = failure;
            return failure;
        }
        @Override public void write(int value) throws IOException {
            if (closed || data.size() >= failAfter) throw failure();
            singleWrites++;
            data.write(value);
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (closed || data.size() + length > failAfter) throw failure();
            bulkWrites++;
            bulkBytes += length;
            data.write(bytes, offset, length);
        }
        @Override public void flush() throws IOException {
            flushes++;
            if (closed || failFlush) throw failure();
        }
        @Override public void close() {
            closes++;
            closed = true;
        }
    }
}

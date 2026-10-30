package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.content.*;
import android.net.Uri;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29)
public class RecordingQuarantineTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    @Rule public SettingsContextRule settings = new SettingsContextRule();
    Context app;
    RecordingRetention store;
    static final byte[] ORIGINAL = {1, 3, 5, 7, 9};

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
    }

    File broken(String id) throws Exception {
        File directory = new File(store.root, id);
        assertTrue(directory.mkdir());
        Files.write(
                new File(directory, "record.json").toPath(),
                "not JSON".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(new File(directory, "source.ts").toPath(), ORIGINAL);
        return directory;
    }

    @Test
    public void aDamagedRecordCannotHideValidRecordsAndRawExportOrDeleteDoesNotTrustItsMetadata()
            throws Exception {
        RecordingRetention.Record valid = store.begin("flv", "valid", 1);
        Files.write(valid.source().toPath(), new byte[] {2, 4});
        valid.update("legacyUnknown", true);
        String validId = valid.id;
        valid.close();
        String id = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        File directory = broken(id);
        assertEquals(2, store.ids().size());
        RecordingRetention.Record quarantined = store.open(id);
        assertTrue(quarantined.quarantined);
        assertFalse(quarantined.acknowledged());
        assertNull(store.open(id));
        try {
            quarantined.update("state", "ACKNOWLEDGED");
            fail();
        } catch (IOException expected) {
        }
        try {
            LiveRecordingSaver.publishRecord(app, quarantined);
            fail();
        } catch (IOException expected) {
        }
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        RecordingExport.exportFiles(quarantined, archive);
        assertZip(archive.toByteArray());
        quarantined.close();
        assertEquals(2, LiveRecordingSaver.recoverRetained(app).size());
        RecordingRetention.Record good = store.open(validId);
        assertFalse(good.quarantined);
        assertArrayEquals(new byte[] {2, 4}, Files.readAllBytes(good.source().toPath()));
        good.close();
        quarantined = store.open(id);
        new RecordingPublication(app, quarantined).discard();
        quarantined.close();
        assertFalse(directory.exists());
        assertEquals(Collections.singletonList(validId), store.ids());
    }

    @Test
    public void unsafeMetadataPathsAreIgnoredAndCannotExportOrDeleteAnOutsideFile()
            throws Exception {
        String id = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        File directory = broken(id), outside = files.newFile("outside-private-data");
        Files.write(outside.toPath(), new byte[] {11, 12});
        JSONObject hostile =
                new JSONObject()
                        .put("v", 1)
                        .put("id", id)
                        .put("format", "flv")
                        .put("source", "source.flv")
                        .put("legacy", outside.getCanonicalPath())
                        .put("state", "READY");
        Files.write(
                new File(directory, "record.json").toPath(),
                hostile.toString().getBytes(StandardCharsets.UTF_8));
        RecordingRetention.Record record = store.open(id);
        assertTrue(record.quarantined);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        RecordingExport.exportFiles(record, archive);
        assertZip(archive.toByteArray());
        try {
            record.file("../outside-private-data");
            fail();
        } catch (IOException expected) {
        }
        new RecordingPublication(app, record).discard();
        record.close();
        assertArrayEquals(new byte[] {11, 12}, Files.readAllBytes(outside.toPath()));
    }

    @Test
    public void closingOrCancellingRawRecoveryPreservesAllOriginals() throws Exception {
        String id = "cccccccccccccccccccccccccccccccc";
        File directory = broken(id);
        RecordingRetention.Record record = store.open(id);
        record.close();
        assertArrayEquals(ORIGINAL, Files.readAllBytes(new File(directory, "source.ts").toPath()));
        assertTrue(new File(directory, "record.json").isFile());
        try {
            store.begin("flv", "new", 2);
            fail();
        } catch (IOException expected) {
        }
        record = store.open(id);
        assertTrue(record.quarantined);
        record.close();
    }

    @Test
    public void partialWriteAndCloseFailuresKeepHashesAndUseTruthfulFeedback() throws Exception {
        for (boolean closeFailure : new boolean[] {false, true}) {
            String id =
                    closeFailure
                            ? "dddddddddddddddddddddddddddddddd"
                            : "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
            File directory = broken(id);
            byte[] before = Files.readAllBytes(new File(directory, "source.ts").toPath());
            Uri uri = Uri.parse("content://chosen-export/" + id);
            OutputStream fail =
                    new OutputStream() {
                        int bytes;

                        public void write(int value) throws IOException {
                            if (!closeFailure && ++bytes > 20)
                                throw new IOException("partial document");
                        }

                        public void close() throws IOException {
                            if (closeFailure)
                                throw new IOException("document close not acknowledged");
                        }
                    };
            org.robolectric.Shadows.shadowOf(app.getContentResolver())
                    .registerOutputStream(uri, fail);
            try {
                RecordingExport.exportTo(app, id, uri);
                fail("unconfirmed export accepted");
            } catch (IOException expected) {
            }
            assertArrayEquals(
                    before, Files.readAllBytes(new File(directory, "source.ts").toPath()));
            RecordingRetention.Record record = store.open(id);
            assertTrue(record.quarantined);
            record.close();
            assertEquals(
                    "Export may be incomplete. The recordings are kept.",
                    RecordingExport.failureMessage());
        }
    }

    @Test
    public void aSuccessfulChosenDocumentExportClosesOnceAndLeavesTheRawOwner() throws Exception {
        String id = "ffffffffffffffffffffffffffffffff";
        File directory = broken(id);
        Uri uri = Uri.parse("content://chosen-export/success");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] closed = {0};
        OutputStream output =
                new FilterOutputStream(data) {
                    public void close() throws IOException {
                        closed[0]++;
                        super.close();
                    }
                };
        org.robolectric.Shadows.shadowOf(app.getContentResolver())
                .registerOutputStream(uri, output);
        RecordingExport.exportTo(app, id, uri);
        assertEquals(1, closed[0]);
        assertZip(data.toByteArray());
        assertTrue(directory.exists());
        assertArrayEquals(ORIGINAL, Files.readAllBytes(new File(directory, "source.ts").toPath()));
    }

    @Test
    public void linkedMetadataIsNotReadAndPayloadLinksCannotEscapeRawExport() throws Exception {
        String id = "0123456789abcdef0123456789abcdef";
        File directory = broken(id), outside = files.newFile("outside-linked-data");
        Files.write(outside.toPath(), new byte[] {42});
        Path link = new File(directory, "unexpected.bin").toPath();
        try {
            Files.createSymbolicLink(link, outside.toPath());
        } catch (IOException | UnsupportedOperationException failure) {
            Assume.assumeNoException("Host symlinks unavailable", failure);
            return;
        }
        try {
            RecordingRetention.Record record = store.open(id);
            assertTrue(record.quarantined);
            try {
                RecordingExport.exportFiles(record, new ByteArrayOutputStream());
                fail("payload symlink followed");
            } catch (IOException expected) {
            }
            assertArrayEquals(new byte[] {42}, Files.readAllBytes(outside.toPath()));
            new RecordingPublication(app, record).discard();
            record.close();
            assertArrayEquals(new byte[] {42}, Files.readAllBytes(outside.toPath()));
            assertFalse(directory.exists());
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    public void linkedJournalIsNeverReadOrWrittenAndDeleteUnlinksOnlyTheOwnedLeaf()
            throws Exception {
        String id = "11111111111111111111111111111111";
        File directory = broken(id), outside = files.newFile("outside-metadata");
        byte[] foreign =
                new JSONObject()
                        .put("v", 1)
                        .put("id", id)
                        .put("source", "source.ts")
                        .put("state", "READY")
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(outside.toPath(), foreign);
        Path metadata = new File(directory, "record.json").toPath();
        Files.delete(metadata);
        try {
            Files.createSymbolicLink(metadata, outside.toPath());
        } catch (IOException | UnsupportedOperationException failure) {
            Assume.assumeNoException(failure);
            return;
        }
        try {
            RecordingRetention.Record record = store.open(id);
            assertTrue(record.quarantined);
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            RecordingExport.exportFiles(record, archive);
            assertZip(archive.toByteArray());
            new RecordingPublication(app, record).discard();
            record.close();
            assertArrayEquals(foreign, Files.readAllBytes(outside.toPath()));
            assertFalse(directory.exists());
        } finally {
            Files.deleteIfExists(metadata);
        }
    }

    @Test
    public void aJournalWriteCannotFollowALinkCreatedAfterTheOwnerWasOpened() throws Exception {
        RecordingRetention.Record record = store.begin("flv", "creator", 1);
        Files.write(record.source().toPath(), ORIGINAL);
        File outside = files.newFile("outside-write");
        Files.write(outside.toPath(), new byte[] {7});
        Path link = new File(record.directory, "record.json.new").toPath();
        try {
            Files.createSymbolicLink(link, outside.toPath());
        } catch (IOException | UnsupportedOperationException failure) {
            record.close();
            Assume.assumeNoException(failure);
            return;
        }
        try {
            try {
                record.update("state", "ACKNOWLEDGED");
                fail();
            } catch (IOException expected) {
            }
            assertArrayEquals(new byte[] {7}, Files.readAllBytes(outside.toPath()));
        } finally {
            Files.deleteIfExists(link);
            record.close();
        }
    }

    @Test
    public void aLinkedUuidDirectoryCannotBeOpenedOrHideOtherOwners() throws Exception {
        File directory = files.newFolder("outside-directory"),
                outside = new File(directory, "source.ts");
        Files.write(outside.toPath(), ORIGINAL);
        String id = "22222222222222222222222222222222";
        Path link = new File(store.root, id).toPath();
        try {
            Files.createSymbolicLink(link, directory.toPath());
        } catch (IOException | UnsupportedOperationException failure) {
            Assume.assumeNoException(failure);
            return;
        }
        try {
            assertFalse(store.ids().contains(id));
            try {
                store.open(id);
                fail();
            } catch (IOException expected) {
            }
            assertArrayEquals(ORIGINAL, Files.readAllBytes(outside.toPath()));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    public void rawExportUsesSafeUniqueArchiveNamesWithoutDroppingUnknownFiles() throws Exception {
        String id = "33333333333333333333333333333333";
        File directory = broken(id);
        Files.write(new File(directory, "recording-1.bin").toPath(), new byte[] {2});
        Files.write(new File(directory, "x y.bin").toPath(), new byte[] {3});
        RecordingRetention.Record record = store.open(id);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        RecordingExport.exportFiles(record, archive);
        java.util.Set<String> names = new java.util.HashSet<>();
        try (ZipInputStream zip =
                new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertTrue(entry.getName().matches("[A-Za-z0-9][A-Za-z0-9._-]*"));
                assertTrue(names.add(entry.getName()));
                assertTrue(zip.readAllBytes().length > 0);
            }
        }
        assertEquals(3, names.size());
        assertTrue(new File(directory, "x y.bin").exists());
        record.close();
    }

    @Test
    public void syntacticallyValidButIncompleteMetadataStillOffersRawRecovery() throws Exception {
        String id = "44444444444444444444444444444444";
        File directory = broken(id);
        Files.write(
                new File(directory, "record.json").toPath(),
                new JSONObject()
                        .put("v", 1)
                        .put("id", id)
                        .toString()
                        .getBytes(StandardCharsets.UTF_8));
        RecordingRetention.Record record = store.open(id);
        assertTrue(record.quarantined);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        RecordingExport.exportFiles(record, archive);
        assertZip(archive.toByteArray());
        new RecordingPublication(app, record).discard();
        record.close();
        assertFalse(directory.exists());
    }

    @Test
    public void corruptLegacyCountsAreBoundedBeforeInspectingPayloads() throws Exception {
        String id = "55555555555555555555555555555555";
        File directory = broken(id);
        JSONObject data =
                new JSONObject()
                        .put("v", 1)
                        .put("id", id)
                        .put("format", "ts")
                        .put("source", "source.ts")
                        .put("state", "READY")
                        .put("extras", Integer.MAX_VALUE);
        Files.write(
                new File(directory, "record.json").toPath(),
                data.toString().getBytes(StandardCharsets.UTF_8));
        RecordingRetention.Record record = store.open(id);
        assertTrue(record.quarantined);
        assertArrayEquals(ORIGINAL, Files.readAllBytes(record.rawFiles().get(0).toPath()));
        record.close();
    }

    private static void assertZip(byte[] bytes) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            assertEquals("source.ts", zip.getNextEntry().getName());
            assertArrayEquals(ORIGINAL, zip.readAllBytes());
            assertNull(zip.getNextEntry());
        }
    }
}

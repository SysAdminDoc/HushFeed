package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Only proven empty capture setup can be reclaimed without an explicit delete or ACK. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29)
public class CaptureEmptyRetentionTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    @Rule public SettingsContextRule settings = new SettingsContextRule();

    RecordingRetention store() throws IOException {
        return new RecordingRetention(files.newFolder());
    }

    void retained(RecordingRetention store, RecordingRetention.Record record) throws Exception {
        String id = record.id;
        record.close();
        assertTrue(record.directory.exists());
        assertTrue(store.ids().contains(id));
        try {
            store.begin("flv", "another", 2);
            fail("retained evidence admitted another capture");
        } catch (IOException expected) {
        }
    }

    @Test
    public void missingOrZeroFreshSourceReleasesAdmission() throws Exception {
        for (boolean create : new boolean[] {false, true}) {
            RecordingRetention store = store();
            RecordingRetention.Record record = store.begin("flv", "creator", 1);
            record.update("capture", true, "captureComplete", false, "screenAudio", "VIDEO_ONLY");
            if (create) assertTrue(record.source().createNewFile());
            record.close();
            assertFalse(record.directory.exists());
            assertTrue(store.ids().isEmpty());
            store.begin("flv", "again", 2).close();
        }
    }

    @Test
    public void configurationReceiptBeforeFirstByteDoesNotStrandEmptySetup() throws Exception {
        RecordingRetention store = store();
        RecordingRetention.Record record = store.begin("flv", "creator", 1);
        record.update(
                "capture",
                true,
                "captureComplete",
                false,
                "captureConfig",
                "abc",
                "captureWidth",
                640,
                "captureHeight",
                480);
        record.close();
        assertFalse(record.directory.exists());
        assertTrue(store.ids().isEmpty());
    }

    @Test
    public void realCreateScreenSetupFailureClosesTheEmptyOwner() throws Exception {
        File durable = files.newFolder(), cache = files.newFolder();
        Context app =
                new ContextWrapper(RuntimeEnvironment.getApplication()) {
                    @Override
                    public File getNoBackupFilesDir() {
                        return durable;
                    }

                    @Override
                    public File getCacheDir() {
                        return cache;
                    }

                    @Override
                    public Context getApplicationContext() {
                        return this;
                    }

                    @Override
                    public Resources getResources() {
                        throw new IllegalStateException("screen setup failed");
                    }
                };
        try {
            LiveRecordingSaver.createScreen(
                    app,
                    new LiveRecorder.Target("", LiveRecorder.Transport.SCREEN, "", "", ""),
                    false,
                    result -> {});
            fail("setup failure not injected");
        } catch (IllegalStateException expected) {
            assertEquals("screen setup failed", expected.getMessage());
        }
        assertTrue(RecordingRetention.in(app).ids().isEmpty());
    }

    @Test
    public void anySourceByteIsPreserved() throws Exception {
        RecordingRetention store = store();
        RecordingRetention.Record record = store.begin("flv", "creator", 1);
        File source = record.source();
        byte[] bytes = {1};
        Files.write(source.toPath(), bytes);
        retained(store, record);
        assertArrayEquals(bytes, Files.readAllBytes(source.toPath()));
    }

    @Test
    public void nonCapturingStatesCannotAuthorizeEmptyCleanup() throws Exception {
        for (String state :
                new String[] {
                    "ADOPTING",
                    "READY",
                    "READY_MP4",
                    "INSERT_INTENT",
                    "DESTINATION_LOCATED",
                    "FILE_INTENT"
                }) {
            RecordingRetention store = store();
            RecordingRetention.Record record = store.begin("flv", "creator", 1);
            record.update("state", state);
            retained(store, record);
        }
    }

    @Test
    public void destinationLegacyFinalAndCheckpointEvidenceAreNeverEmptySetup() throws Exception {
        for (String key :
                new String[] {
                    "file",
                    "uri",
                    "intentKey",
                    "temporaryName",
                    "collection",
                    "folder",
                    "savedName",
                    "origin",
                    "legacyUnknown",
                    "extras",
                    "bytes",
                    "sha256",
                    "captureBytes",
                    "capturePayload",
                    "captureSamples",
                    "captureEndUs",
                    "captureIncomplete",
                    "futureReceipt"
                }) {
            RecordingRetention store = store();
            RecordingRetention.Record record = store.begin("flv", "creator", 1);
            Object value =
                    key.equals("extras")
                            ? 0
                            : key.matches(
                                            "bytes|captureBytes|capturePayload|captureSamples|captureEndUs")
                                    ? 1
                                    : key.matches("legacyUnknown|captureIncomplete")
                                            ? true
                                            : "evidence";
            record.update(key, value);
            retained(store, record);
        }
    }

    @Test
    public void finalizedReceiptWithMissingOutputIsPreserved() throws Exception {
        RecordingRetention store = store();
        RecordingRetention.Record record = store.begin("flv", "creator", 1);
        record.update(
                "final",
                "screen.mp4",
                "bytes",
                1,
                "sha256",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        retained(store, record);
    }

    @Test
    public void completedCaptureReceiptIsPreservedEvenIfSourceIsMissing() throws Exception {
        RecordingRetention store = store();
        RecordingRetention.Record record = store.begin("flv", "creator", 1);
        record.update("capture", true, "captureComplete", true);
        retained(store, record);
    }

    @Test
    public void anyAdditionalPayloadIncludingZeroBytesIsPreserved() throws Exception {
        for (String name : new String[] {"screen.mp4", "remux.mp4", "unknown.bin"}) {
            RecordingRetention store = store();
            RecordingRetention.Record record = store.begin("flv", "creator", 1);
            File extra = new File(record.directory, name);
            assertTrue(extra.createNewFile());
            retained(store, record);
            assertTrue(extra.exists());
        }
    }

    @Test
    public void zeroLegacySourceCannotDeleteItsUsableRemuxCompanion() throws Exception {
        File legacy = files.newFolder();
        RecordingRetention store = new RecordingRetention(files.newFolder(), legacy);
        File source = new File(legacy, "live-1760480102000-creator-1.flv");
        File rewrite = new File(legacy, "live-1760480102000-creator-2.mp4");
        assertTrue(source.createNewFile());
        byte[] bytes = {1, 2, 3, 4};
        Files.write(rewrite.toPath(), bytes);
        RecordingRetention.Record record = store.adopt(source, "flv", "creator", 1);
        assertEquals(1, record.number("extras"));
        File retainedRewrite = record.extra(0);
        retained(store, record);
        assertArrayEquals(bytes, Files.readAllBytes(retainedRewrite.toPath()));
    }

    @Test
    public void liveOwnerCannotEraseDamagedOrChangedMetadata() throws Exception {
        for (String changed : new String[] {"{", "different"}) {
            RecordingRetention store = store();
            RecordingRetention.Record record = store.begin("flv", "creator", 1);
            File journal = new File(record.directory, "record.json");
            String value =
                    changed.equals("{")
                            ? changed
                            : new org.json.JSONObject(
                                            new String(
                                                    Files.readAllBytes(journal.toPath()),
                                                    java.nio.charset.StandardCharsets.UTF_8))
                                    .put("state", "READY")
                                    .toString();
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(journal.toPath(), bytes);
            retained(store, record);
            assertArrayEquals(bytes, Files.readAllBytes(journal.toPath()));
        }
    }

    @Test
    public void emptyQuarantinedOwnerKeepsDamagedMetadata() throws Exception {
        RecordingRetention store = store();
        RecordingRetention.Record record = store.begin("flv", "creator", 1);
        File journal = new File(record.directory, "record.json");
        record.update("state", "READY");
        record.close();
        byte[] damaged = {123};
        Files.write(journal.toPath(), damaged);
        RecordingRetention.Record reopened = store.open(record.id);
        assertTrue(reopened.quarantined);
        retained(store, reopened);
        assertArrayEquals(damaged, Files.readAllBytes(journal.toPath()));
    }
}

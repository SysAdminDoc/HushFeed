package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** Destination acknowledgement, not a callback return, authorizes recording source cleanup. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29)
public class RecordingRetentionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Rule public SettingsContextRule settings = new SettingsContextRule();
    Context app;
    File durable, cache;
    RecordingRetention store;
    static final byte[] PAYLOAD = "finalized recording payload".getBytes(StandardCharsets.UTF_8);

    static final class ProcessDeath extends Error {}

    @Before
    public void setup() throws Exception {
        durable = temporary.newFolder("durable");
        cache = temporary.newFolder("cache");
        app =
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
                };
        store = RecordingRetention.in(app);
        Provider.reset(temporary.newFolder("provider"), app.getPackageName());
        Robolectric.setupContentProvider(Provider.class, "media");
    }

    RecordingRetention.Record ready() throws Exception {
        RecordingRetention.Record record = store.begin("screen", "creator", 1_760_480_102_000L);
        File file = record.file("screen.mp4");
        Files.write(file.toPath(), PAYLOAD);
        record.finalized(file, LiveRecorder.ScreenAudio.VIDEO_ONLY.name());
        return record;
    }

    MediaFileWriter.Saved publish(RecordingRetention.Record record) throws Exception {
        return MediaFileWriter.publishForResult(
                app,
                record.finalized(),
                "live_creator.mp4",
                "video/mp4",
                "Movies/Hushfeed",
                true,
                MediaBudget.MAX_LIVE_RECORDING_BYTES,
                new RecordingPublication(app, record));
    }

    JSONObject disk(RecordingRetention.Record record) throws Exception {
        return new JSONObject(
                new String(
                        Files.readAllBytes(new File(record.directory, "record.json").toPath()),
                        StandardCharsets.UTF_8));
    }

    @Test
    public void insertIntentAndExactUriAreDurableBeforeTheirNextSideEffects() throws Exception {
        RecordingRetention.Record record = ready();
        Provider.afterInsert =
                () -> {
                    try {
                        assertFalse(disk(record).getString("intentKey").isEmpty());
                        assertEquals("INSERT_INTENT", disk(record).getString("state"));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                };
        Provider.beforeOpen =
                () -> {
                    try {
                        assertFalse(disk(record).getString("uri").isEmpty());
                        assertTrue(record.finalized().exists());
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                };
        MediaFileWriter.Saved saved = publish(record);
        assertTrue(record.acknowledged());
        assertNotNull(saved.uri);
        assertTrue(record.finalized().exists());
        File original = record.finalized();
        record.close();
        assertFalse(original.exists());
        assertEquals(1, Provider.inserts);
        assertEquals(1, Provider.rows.size());
    }

    @Test
    public void aCrashAfterInsertBeforeUriReceiptCannotDuplicateACommittedFile() throws Exception {
        RecordingRetention.Record record = ready();
        String id = record.id;
        Provider.afterInsert =
                () -> {
                    throw new ProcessDeath();
                };
        try {
            publish(record);
            fail("death not injected");
        } catch (ProcessDeath expected) {
        }
        assertTrue(record.finalized().exists());
        assertEquals("", record.get("uri"));
        record.close();
        Provider.afterInsert = null;
        RecordingRetention.Record restarted = store.open(id);
        assertNull(new RecordingPublication(app, restarted).alreadyPublished());
        publish(restarted);
        restarted.close();
        assertEquals(2, Provider.inserts);
        assertEquals("only one published destination", 1, Provider.rows.size());
    }

    @Test
    public void aCrashAfterCommitBeforeAcknowledgementFindsTheSameDestinationOnEveryRetry()
            throws Exception {
        RecordingRetention.Record record = ready();
        String id = record.id;
        Provider.afterCommit =
                () -> {
                    throw new ProcessDeath();
                };
        try {
            publish(record);
            fail("death not injected");
        } catch (ProcessDeath expected) {
        }
        assertFalse(record.acknowledged());
        assertTrue(record.finalized().exists());
        record.close();
        Provider.afterCommit = null;
        RecordingRetention.Record restarted = store.open(id);
        RecordingPublication retry = new RecordingPublication(app, restarted);
        Uri saved = retry.alreadyPublished().uri;
        assertEquals(saved, retry.alreadyPublished().uri);
        assertEquals(1, Provider.inserts);
        File original = restarted.finalized();
        restarted.close();
        assertFalse(original.exists());
        assertTrue(store.ids().isEmpty());
        assertEquals(1, Provider.rows.size());
    }

    @Test
    public void normalPublicationRejectsTruncatedCommittedDestinationAndRetainsOriginal()
            throws Exception {
        rejectsCorruptPublication(0);
    }

    @Test
    public void normalPublicationRejectsCorruptCommittedDestinationAndRetainsOriginal()
            throws Exception {
        rejectsCorruptPublication(1);
    }

    @Test
    public void normalPublicationRejectsChangedCommittedOwnerAndRetainsOriginal() throws Exception {
        rejectsCorruptPublication(2);
    }

    private void rejectsCorruptPublication(int corruption) throws Exception {
        RecordingRetention.Record record = ready();
        String id = record.id;
        Provider.afterCommit =
                () -> {
                    try {
                        if (corruption == 2) Provider.owner = "another.package";
                        else {
                            byte[] altered = corruption == 0 ? new byte[] {1} : PAYLOAD.clone();
                            if (corruption == 1) altered[0] ^= 1;
                            Files.write(
                                    Provider.rows.values().iterator().next().file.toPath(),
                                    altered);
                        }
                    } catch (IOException failure) {
                        throw new AssertionError(failure);
                    }
                };
        try {
            publish(record);
            fail("unverified committed destination accepted");
        } catch (IOException expected) {
        }
        assertFalse(record.acknowledged());
        assertArrayEquals(PAYLOAD, Files.readAllBytes(record.finalized().toPath()));
        assertEquals(1, Provider.rows.size());
        assertEquals(1, Provider.inserts);
        record.close();
        RecordingRetention.Record reopened = store.open(id);
        assertFalse(reopened.acknowledged());
        assertTrue(reopened.finalized().exists());
        reopened.close();
    }

    @Test
    public void aProviderFailureAfterCommittingCannotRollBackThePublishedRow() throws Exception {
        RecordingRetention.Record record = ready();
        String id = record.id;
        Provider.afterCommit =
                () -> {
                    throw new IllegalStateException("commit receipt lost");
                };
        try {
            publish(record);
            fail("commit failure not injected");
        } catch (IllegalStateException expected) {
        }
        assertEquals(1, Provider.rows.size());
        assertFalse(record.acknowledged());
        assertTrue(record.finalized().exists());
        record.close();
        Provider.afterCommit = null;
        RecordingRetention.Record restarted = store.open(id);
        assertNotNull(new RecordingPublication(app, restarted).alreadyPublished());
        restarted.close();
        assertEquals(1, Provider.inserts);
    }

    @Test
    public void aChangedDestinationOwnerOrHashCannotAcknowledgeSourceDeletion() throws Exception {
        RecordingRetention.Record record = ready();
        String id = record.id;
        Provider.afterCommit =
                () -> {
                    throw new ProcessDeath();
                };
        try {
            publish(record);
        } catch (ProcessDeath expected) {
        }
        record.close();
        Provider.afterCommit = null;
        RecordingRetention.Record restarted = store.open(id);
        String owner = Provider.owner;
        Provider.owner = "another.package";
        try {
            new RecordingPublication(app, restarted).alreadyPublished();
            fail("changed owner accepted");
        } catch (IOException expected) {
        }
        Provider.owner = owner;
        byte[] changed = PAYLOAD.clone();
        changed[0] ^= 1;
        Files.write(Provider.rows.values().iterator().next().file.toPath(), changed);
        try {
            new RecordingPublication(app, restarted).alreadyPublished();
            fail("changed bytes accepted");
        } catch (IOException expected) {
        }
        assertFalse(restarted.acknowledged());
        assertTrue(restarted.finalized().exists());
        assertEquals(1, Provider.inserts);
        restarted.close();
    }

    @Test
    public void permissionFailureDoesNotAuthorizeAnotherInsertOrSourceDeletion() throws Exception {
        RecordingRetention.Record record = ready();
        String id = record.id;
        Provider.afterCommit =
                () -> {
                    throw new ProcessDeath();
                };
        try {
            publish(record);
        } catch (ProcessDeath expected) {
        }
        record.close();
        Provider.afterCommit = null;
        Provider.denyQueries = true;
        RecordingRetention.Record restarted = store.open(id);
        try {
            new RecordingPublication(app, restarted).alreadyPublished();
            fail("unavailable row accepted");
        } catch (IOException expected) {
        }
        assertTrue(restarted.finalized().exists());
        assertEquals(1, Provider.inserts);
        restarted.close();
    }

    @Test
    public void aFailedAcknowledgementKeepsBothTheCommittedDestinationAndItsOriginal()
            throws Exception {
        RecordingRetention.Record record = ready();
        String id = record.id;
        File obstruction = new File(record.directory, "record.json.new");
        Provider.afterCommit =
                () -> {
                    assertTrue(obstruction.mkdir());
                };
        MediaFileWriter.Saved saved = publish(record);
        assertNotNull(saved);
        assertFalse(record.acknowledged());
        assertTrue(record.finalized().exists());
        assertEquals(1, Provider.rows.size());
        record.close();
        assertTrue(obstruction.delete());
        Provider.afterCommit = null;
        RecordingRetention.Record restarted = store.open(id);
        assertNotNull(new RecordingPublication(app, restarted).alreadyPublished());
        restarted.close();
        assertEquals(1, Provider.inserts);
    }

    @Test
    public void aVerifiedRetryWithAFailedAckWriteReturnsTheSameSavedDestinationAndRetainsDebt()
            throws Exception {
        RecordingRetention.Record record = ready();
        String id = record.id;
        Provider.afterCommit =
                () -> {
                    throw new ProcessDeath();
                };
        try {
            publish(record);
        } catch (ProcessDeath expected) {
        }
        record.close();
        Provider.afterCommit = null;
        RecordingRetention.Record retry = store.open(id);
        File obstruction = new File(retry.directory, "record.json.new");
        assertTrue(obstruction.mkdir());
        MediaFileWriter.Saved saved = new RecordingPublication(app, retry).alreadyPublished();
        assertNotNull(saved.uri);
        assertFalse(retry.acknowledged());
        assertTrue(retry.finalized().exists());
        assertEquals(1, Provider.inserts);
        retry.close();
        assertTrue(obstruction.delete());
        RecordingRetention.Record finalRetry = store.open(id);
        assertEquals(saved.uri, new RecordingPublication(app, finalRetry).alreadyPublished().uri);
        finalRetry.close();
        assertTrue(store.ids().isEmpty());
        assertEquals(1, Provider.rows.size());
    }

    @Test
    @Config(sdk = 28)
    public void normalLegacyPublicationVerifiesTheExactFileBeforeAcknowledgement()
            throws Exception {
        RecordingRetention.Record record = ready();
        File original = record.finalized();
        MediaFileWriter.Saved saved =
                MediaFileWriter.publishForResult(
                        app,
                        original,
                        "live_creator.mp4",
                        "video/mp4",
                        "Movies/HushfeedRetained-" + record.id,
                        true,
                        MediaBudget.MAX_LIVE_RECORDING_BYTES,
                        new RecordingPublication(app, record));
        assertTrue(record.acknowledged());
        assertArrayEquals(PAYLOAD, Files.readAllBytes(saved.file.toPath()));
        record.close();
        assertFalse(original.exists());
        assertTrue(saved.file.exists());
    }

    @Test
    @Config(sdk = 28)
    public void normalLegacyPublicationCannotAcknowledgeACorruptDestination() throws Exception {
        RecordingRetention.Record record = ready();
        RecordingPublication owner = new RecordingPublication(app, record);
        java.util.concurrent.atomic.AtomicBoolean acknowledged =
                new java.util.concurrent.atomic.AtomicBoolean();
        MediaFileWriter.Publication corrupting =
                new MediaFileWriter.Publication() {
                    @Override
                    public void intent(String key, String collection, String name, String folder)
                            throws IOException {
                        owner.intent(key, collection, name, folder);
                    }

                    @Override
                    public void located(Uri uri) throws IOException {
                        owner.located(uri);
                    }

                    @Override
                    public String legacyName(String name) {
                        return owner.legacyName(name);
                    }

                    @Override
                    public void legacyIntent(File file) throws IOException {
                        owner.legacyIntent(file);
                    }

                    @Override
                    public void legacyLocated(File file) throws IOException {
                        owner.legacyLocated(file);
                    }

                    @Override
                    public void verify(MediaFileWriter.Saved saved) throws IOException {
                        Files.write(saved.file.toPath(), new byte[] {1});
                        owner.verify(saved);
                    }

                    @Override
                    public void acknowledged(MediaFileWriter.Saved saved) throws IOException {
                        acknowledged.set(true);
                        owner.acknowledged(saved);
                    }
                };
        try {
            MediaFileWriter.publishForResult(
                    app,
                    record.finalized(),
                    "live_creator.mp4",
                    "video/mp4",
                    "Movies/HushfeedRetained-" + record.id,
                    true,
                    MediaBudget.MAX_LIVE_RECORDING_BYTES,
                    corrupting);
            fail("corrupt legacy destination accepted");
        } catch (IOException expected) {
        }
        assertFalse(acknowledged.get());
        assertFalse(record.acknowledged());
        assertArrayEquals(PAYLOAD, Files.readAllBytes(record.finalized().toPath()));
        record.close();
    }

    @Test
    public void aPublicationFailureAndRemuxFailureRetainTheirOwnedSourceBytes() throws Exception {
        RecordingRetention.Record record = ready();
        Provider.failOpen = true;
        try {
            publish(record);
            fail("publication failure not injected");
        } catch (IOException expected) {
        }
        assertArrayEquals(PAYLOAD, Files.readAllBytes(record.finalized().toPath()));
        String id = record.id;
        record.close();
        assertTrue(store.ids().contains(id));
        Provider.failOpen = false;
        RecordingRetention.Record restarted = store.open(id);
        new RecordingPublication(app, restarted).alreadyPublished();
        publish(restarted);
        restarted.close();
        assertEquals(1, Provider.rows.size());
        RecordingRetention.Record raw = store.begin("flv", "creator", 1);
        Files.write(raw.source().toPath(), new byte[] {1, 2, 3, 4});
        String rawId = raw.id;
        try {
            LiveRecordingSaver.publishRecord(app, raw);
            fail("malformed source was accepted");
        } catch (IOException expected) {
        }
        raw.close();
        RecordingRetention.Record failed = store.open(rawId);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(failed.source().toPath()));
        failed.close();
    }

    @Test
    public void recorderConclusionCannotDeleteAJournalOwnedSourceOnFinisherFailure()
            throws Exception {
        RecordingRetention.Record record = store.begin("flv", "creator", 1);
        String id = record.id;
        byte[] bytes = new byte[16 * 1024];
        java.util.Arrays.fill(bytes, (byte) 7);
        java.util.concurrent.atomic.AtomicReference<LiveRecorder.Result> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        LiveRecorder.Finisher failing =
                new LiveRecorder.Finisher() {
                    @Override
                    public LiveRecorder.Published finish(
                            File file, LiveRecorder.Target target, long started)
                            throws IOException {
                        throw new IOException("publication unavailable");
                    }

                    @Override
                    public boolean ownsFiles() {
                        return true;
                    }
                };
        LiveRecorder recorder =
                new LiveRecorder(
                        new LiveRecorder.Target("", LiveRecorder.Transport.FLV, "creator", "", ""),
                        record.source(),
                        (output, control) -> output.write(bytes, 0, bytes.length),
                        failing,
                        result::set);
        recorder.start();
        assertTrue(recorder.join(5000));
        assertEquals(LiveRecorder.Outcome.FAILED, result.get().outcome);
        record.close();
        RecordingRetention.Record retained = store.open(id);
        assertArrayEquals(bytes, Files.readAllBytes(retained.source().toPath()));
        retained.close();
    }

    @Test
    public void aFinalizedScreenWorkCanPublishEvenWhenItsCopySpoolIsEmpty() throws Exception {
        RecordingRetention.Record record = ready();
        java.util.concurrent.atomic.AtomicReference<LiveRecorder.Result> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        LiveRecorder.Finisher owned =
                new LiveRecorder.Finisher() {
                    @Override
                    public LiveRecorder.Published finish(
                            File file, LiveRecorder.Target target, long started) {
                        return new LiveRecorder.Published("saved.mp4", "saved");
                    }

                    @Override
                    public boolean ownsFiles() {
                        return true;
                    }

                    @Override
                    public boolean usefulWithoutSpool() {
                        return true;
                    }
                };
        LiveRecorder recorder =
                new LiveRecorder(
                        new LiveRecorder.Target(
                                "", LiveRecorder.Transport.SCREEN, "creator", "", ""),
                        record.source(),
                        (output, control) -> {},
                        owned,
                        result::set);
        recorder.start();
        assertTrue(recorder.join(5000));
        assertEquals(LiveRecorder.Outcome.SAVED_PARTIAL, result.get().outcome);
        assertTrue(record.finalized().exists());
        record.close();
    }

    @Test
    public void retainedRecordingDebtRefusesNewCaptureWithoutPruningAnyBytes() throws Exception {
        RecordingRetention retained = new RecordingRetention(temporary.newFolder("retained-debt"));
        RecordingRetention.Record one = retained.begin("flv", "creator", 1);
        Files.write(one.source().toPath(), new byte[20]);
        one.close();
        try {
            retained.begin("flv", "creator", 2);
            fail("retained recording admitted another capture");
        } catch (IOException expected) {
        }
        assertEquals(1, retained.ids().size());
        RecordingRetention.Record original = retained.open(retained.ids().get(0));
        assertEquals(20, original.source().length());
        original.close();
    }

    @Test
    public void multipleLegacyFilesBlockCaptureBeforeAndAfterAdoptionAndAreNeverPruned()
            throws Exception {
        File legacy = temporary.newFolder("legacy-debt"), root = temporary.newFolder("legacy-root");
        File source = new File(legacy, "live-123.flv"), second = new File(legacy, "live-456.flv");
        Files.write(source.toPath(), new byte[40]);
        Files.write(second.toPath(), new byte[60]);
        RecordingRetention migration = new RecordingRetention(root, legacy);
        try {
            migration.begin("flv", "creator", 2);
            fail("unadopted legacy debt admitted capture");
        } catch (IOException expected) {
        }
        LiveSpoolRecovery.adoptLegacy(migration, legacy);
        assertEquals(2, migration.ids().size());
        try {
            migration.begin("flv", "creator", 3);
            fail("adopted legacy debt admitted capture");
        } catch (IOException expected) {
        }
        long total = 0;
        for (String id : migration.ids()) {
            RecordingRetention.Record record = migration.open(id);
            total += record.source().length();
            record.close();
        }
        assertEquals(100, total);
    }

    @Test
    public void threeConcurrentEmptyBeginsAdmitExactlyOneCapture() throws Exception {
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1),
                done = new java.util.concurrent.CountDownLatch(3);
        java.util.concurrent.atomic.AtomicInteger
                admitted = new java.util.concurrent.atomic.AtomicInteger(),
                refused = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<RecordingRetention.Record> owned =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> error =
                new java.util.concurrent.atomic.AtomicReference<>();
        for (int i = 0; i < 3; i++)
            new Thread(
                            () -> {
                                try {
                                    start.await();
                                    owned.set(store.begin("flv", "creator", 1));
                                    admitted.incrementAndGet();
                                } catch (IOException expected) {
                                    refused.incrementAndGet();
                                } catch (Throwable failure) {
                                    error.set(failure);
                                } finally {
                                    done.countDown();
                                }
                            },
                            "retention-admission-test")
                    .start();
        start.countDown();
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals(1, admitted.get());
        assertEquals(2, refused.get());
        assertEquals(1, store.ids().size());
        owned.get().close();
    }

    @Test
    public void closeAndUpdateAcquireTheRecordMonitorBeforeTheGlobalLock() throws Exception {
        RecordingRetention.Record record = ready();
        java.lang.reflect.Field field = RecordingRetention.class.getDeclaredField("LOCK");
        field.setAccessible(true);
        Object global = field.get(null);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicReference<Throwable> error =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread update =
                new Thread(
                        () -> {
                            try {
                                record.update("concurrency", "written");
                            } catch (Throwable failure) {
                                error.set(failure);
                            } finally {
                                done.countDown();
                            }
                        },
                        "retention-update-test");
        Thread close =
                new Thread(
                        () -> {
                            try {
                                record.close();
                            } catch (Throwable failure) {
                                error.set(failure);
                            } finally {
                                done.countDown();
                            }
                        },
                        "retention-close-test");
        update.setDaemon(true);
        close.setDaemon(true);
        boolean updateOrder, closeOrder;
        synchronized (global) {
            update.start();
            updateOrder = waitsFor(update, global);
            close.start();
            closeOrder = waitsFor(close, record);
        }
        assertTrue(
                "concurrent close/update completed",
                done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue("update takes record before global lock", updateOrder);
        assertTrue("close takes record before global lock", closeOrder);
        assertNull(error.get());
        RecordingRetention.Record reopened = store.open(record.id);
        assertEquals("written", reopened.get("concurrency"));
        reopened.close();
    }

    private static boolean waitsFor(Thread thread, Object monitor) throws Exception {
        // The host JVM exposes exact monitor identities, but Android's compile API omits this
        // package.
        Object bean =
                Class.forName("java.lang.management.ManagementFactory")
                        .getMethod("getThreadMXBean")
                        .invoke(null);
        java.lang.reflect.Method snapshot =
                Class.forName("java.lang.management.ThreadMXBean")
                        .getMethod("getThreadInfo", long.class);
        Class<?> infoType = Class.forName("java.lang.management.ThreadInfo");
        java.lang.reflect.Method state = infoType.getMethod("getThreadState"),
                lock = infoType.getMethod("getLockInfo");
        java.lang.reflect.Method identity =
                Class.forName("java.lang.management.LockInfo").getMethod("getIdentityHashCode");
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < end) {
            Object info = snapshot.invoke(bean, thread.getId());
            Object held = info == null ? null : lock.invoke(info);
            if (held != null
                    && state.invoke(info) == Thread.State.BLOCKED
                    && ((Integer) identity.invoke(held)).intValue()
                            == System.identityHashCode(monitor)) return true;
            Thread.sleep(1);
        }
        return false;
    }

    @Test
    public void closedOwnersCannotMutateOrReacquireTheirFiles() throws Exception {
        RecordingRetention.Record record = ready();
        File original = record.finalized();
        record.close();
        try {
            record.update("state", "ACKNOWLEDGED");
            fail("closed update accepted");
        } catch (IOException expected) {
        }
        try {
            record.file("screen.mp4");
            fail("closed file access accepted");
        } catch (IOException expected) {
        }
        try {
            record.finalized(original, "VIDEO_ONLY");
            fail("closed finalization accepted");
        } catch (IOException expected) {
        }
        try {
            record.finalized();
            fail("closed finalized access accepted");
        } catch (IOException expected) {
        }
        RecordingRetention.Record reopened = store.open(record.id);
        assertNotNull(reopened);
        assertTrue(reopened.finalized().exists());
        reopened.close();
    }

    @Test
    public void aFailedInitialMetadataWriteWithoutAnySourceBytesDoesNotTrapAdmission()
            throws Exception {
        File empty = new File(store.root, "0123456789abcdef0123456789abcdef");
        assertTrue(empty.mkdir());
        Files.write(new File(empty, "record.json.new").toPath(), new byte[] {1, 2, 3});
        assertTrue(store.ids().isEmpty());
        assertFalse(empty.exists());
        RecordingRetention.Record record = ready();
        assertTrue(record.finalized().isFile());
        record.close();
    }

    @Test
    public void unfinishedScreenWorkHasNoFinalizedReceiptAndCannotBePublished() throws Exception {
        RecordingRetention.Record record = store.begin("screen", "creator", 1);
        Files.write(record.file("screen.mp4").toPath(), PAYLOAD);
        try {
            LiveRecordingSaver.publishRecord(app, record);
            fail("unfinished muxer work was published");
        } catch (IOException expected) {
        }
        assertEquals(0, Provider.inserts);
        assertTrue(record.file("screen.mp4").exists());
        record.close();
    }

    @Test
    public void anOldValidSpoolSurvivesEvictionAndCanBeExportedWithoutGuessingItsPublicationState()
            throws Exception {
        File file =
                new File(
                        MediaCache.cacheDirectory(app),
                        LiveSpoolRecovery.spoolPrefix("creator", 1_760_480_102_000L) + "7.flv");
        byte[] video =
                FlvFixtures.stream(
                        FlvFixtures.avcConfigTag(
                                0, FlvFixtures.baselineSps(20, 15, 0), FlvFixtures.pps()),
                        FlvFixtures.avcFrameTag(0, true, 0, new byte[] {0x65, 1}),
                        FlvFixtures.avcFrameTag(40, false, 0, new byte[] {0x41, 2}));
        Files.write(file.toPath(), video);
        assertTrue(
                file.setLastModified(
                        System.currentTimeMillis() - MediaCache.STALE_AFTER_MS - 60_000));
        MediaCache.reconcile(app);
        assertTrue(file.exists());
        LiveSpoolRecovery.adoptLegacy(store, MediaCache.cacheDirectory(app));
        RecordingRetention.Record record = store.open(store.ids().get(0));
        try {
            LiveRecordingSaver.publishRecord(app, record);
            fail("legacy publication state was guessed");
        } catch (IOException expected) {
        }
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        RecordingExport.exportFiles(record, output);
        try (java.util.zip.ZipInputStream zip =
                new java.util.zip.ZipInputStream(
                        new java.io.ByteArrayInputStream(output.toByteArray()))) {
            assertNotNull(zip.getNextEntry());
            assertArrayEquals(video, zip.readAllBytes());
        }
        assertTrue(record.source().exists());
        assertEquals(0, Provider.inserts);
        record.close();
    }

    public static final class Provider extends ContentProvider {
        static final Map<Long, Row> rows = new LinkedHashMap<>();
        static long next;
        static int inserts;
        static File directory;
        static String owner;
        static Runnable afterInsert, beforeOpen, afterCommit;
        static boolean denyQueries, failOpen;

        static final class Row {
            long id;
            String name, folder, mime;
            int pending;
            File file;
        }

        static void reset(File d, String packageName) {
            rows.clear();
            next = 1;
            inserts = 0;
            directory = d;
            owner = packageName;
            afterInsert = beforeOpen = afterCommit = null;
            denyQueries = failOpen = false;
        }

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public String getType(Uri uri) {
            return "video/mp4";
        }

        @Override
        public Uri insert(Uri collection, ContentValues values) {
            Row row = new Row();
            row.id = next++;
            row.name = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
            row.folder = values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH);
            row.mime = values.getAsString(MediaStore.MediaColumns.MIME_TYPE);
            row.pending = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING);
            row.file = new File(directory, "row-" + row.id);
            rows.put(row.id, row);
            inserts++;
            if (afterInsert != null) afterInsert.run();
            return ContentUris.withAppendedId(collection, row.id);
        }

        @Override
        public ParcelFileDescriptor openFile(Uri uri, String mode)
                throws java.io.FileNotFoundException {
            if (failOpen) throw new java.io.FileNotFoundException("injected write failure");
            if (beforeOpen != null && mode.contains("w")) beforeOpen.run();
            Row row = rows.get(ContentUris.parseId(uri));
            return ParcelFileDescriptor.open(
                    row.file,
                    mode.contains("w")
                            ? ParcelFileDescriptor.MODE_CREATE
                                    | ParcelFileDescriptor.MODE_TRUNCATE
                                    | ParcelFileDescriptor.MODE_READ_WRITE
                            : ParcelFileDescriptor.MODE_READ_ONLY);
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            Row row = rows.get(ContentUris.parseId(uri));
            if (row == null) return 0;
            if (values.containsKey(MediaStore.MediaColumns.DISPLAY_NAME))
                row.name = values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
            if (values.containsKey(MediaStore.MediaColumns.IS_PENDING))
                row.pending = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING);
            if (row.pending == 0 && afterCommit != null) afterCommit.run();
            return 1;
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            Row row = rows.remove(ContentUris.parseId(uri));
            if (row == null) return 0;
            row.file.delete();
            return 1;
        }

        @Override
        public Cursor query(
                Uri uri, String[] projection, String selection, String[] args, String sort) {
            if (denyQueries) throw new SecurityException("injected unavailable destination");
            MatrixCursor cursor = new MatrixCursor(projection);
            Long id = null;
            try {
                id = ContentUris.parseId(uri);
            } catch (NumberFormatException ignored) {
            }
            for (Row row : rows.values()) {
                if (id != null && row.id != id) continue;
                if (selection != null
                        && selection.contains(MediaStore.MediaColumns.DISPLAY_NAME)
                        && (args == null || !row.name.equals(args[0]))) continue;
                if (selection != null
                        && selection.contains(MediaStore.MediaColumns.RELATIVE_PATH)
                        && !slash(row.folder).equals(slash(args[1]))) continue;
                Object[] data = new Object[projection.length];
                for (int i = 0; i < projection.length; i++) {
                    switch (projection[i]) {
                        case "_id":
                            data[i] = row.id;
                            break;
                        case "_display_name":
                            data[i] = row.name;
                            break;
                        case "relative_path":
                            data[i] = row.folder;
                            break;
                        case "mime_type":
                            data[i] = row.mime;
                            break;
                        case "is_pending":
                            data[i] = row.pending;
                            break;
                        case "_size":
                            data[i] = row.file.length();
                            break;
                        case "owner_package_name":
                            data[i] = owner;
                            break;
                    }
                }
                cursor.addRow(data);
            }
            return cursor;
        }

        static String slash(String text) {
            return text.endsWith("/") ? text : text + "/";
        }
    }
}

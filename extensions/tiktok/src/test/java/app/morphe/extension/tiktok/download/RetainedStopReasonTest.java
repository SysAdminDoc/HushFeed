package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Retained Retry reports what ended this recording; startup recovery remains a process exit. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29, qualifiers = "en-w360dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class RetainedStopReasonTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    @Rule public SettingsContextRule settings = new SettingsContextRule();
    private static final byte[] PAYLOAD = {1, 3, 5, 7, 9};
    Context app;
    RecordingRetention store;
    ActivityController<Host> owner;
    boolean oldDark;
    final List<AlertDialog> dialogs = new ArrayList<>();

    public static class Host extends Activity {
        @Override
        public void onCreate(android.os.Bundle state) {
            setTheme(
                    Utils.isDarkModeEnabled()
                            ? android.R.style.Theme_Material_NoActionBar
                            : android.R.style.Theme_Material_Light_NoActionBar);
            super.onCreate(state);
        }
    }

    @Before
    public void setup() throws Exception {
        File durable = files.newFolder(), cache = files.newFolder();
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
        Utils.setContext(app);
        oldDark = Utils.isDarkModeEnabled();
        Utils.setIsDarkModeEnabled(true);
        store = RecordingRetention.in(app);
        RecordingRetentionTest.Provider.reset(files.newFolder(), app.getPackageName());
        Robolectric.setupContentProvider(RecordingRetentionTest.Provider.class, "media");
        SaveNotice.windowRootsForTests = List.of();
    }

    @After
    public void cleanup() throws Exception {
        RecordingRetentionTest.Provider.afterCommit = null;
        RecordingRetentionTest.Provider.failOpen = false;
        for (AlertDialog dialog : dialogs) if (dialog.isShowing()) dialog.dismiss();
        if (owner != null) owner.pause().stop().destroy();
        Utils.setActivity(null);
        Utils.setIsDarkModeEnabled(oldDark);
        SaveNotice.windowRootsForTests = null;
    }

    RecordingRetention.Record ready() throws Exception {
        RecordingRetention.Record record = store.begin("screen", "creator", 1_760_480_102_000L);
        File mp4 = record.file("screen.mp4");
        Files.write(mp4.toPath(), PAYLOAD);
        record.finalized(mp4, LiveRecorder.ScreenAudio.VIDEO_ONLY.name());
        return record;
    }

    void window(boolean dark) {
        Utils.setIsDarkModeEnabled(dark);
        owner = Robolectric.buildActivity(Host.class).setup().visible();
        Utils.setActivity(owner.get());
    }

    LiveRecorder.Result run(RecordingRetention.Record record, LiveRecorder.End end, boolean bytes)
            throws Exception {
        Method finisher =
                LiveRecordingSaver.class.getDeclaredMethod(
                        "finisher", Context.class, RecordingRetention.Record.class);
        finisher.setAccessible(true);
        AtomicReference<LiveRecorder> holder = new AtomicReference<>();
        AtomicReference<LiveRecorder.Result> result = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        LiveRecorder.Source source =
                (out, control) -> {
                    if (bytes) {
                        byte[] data = new byte[5000];
                        out.write(data, 0, data.length);
                    }
                    if (end != LiveRecorder.End.DISCONNECTED) holder.get().stop(end);
                };
        LiveRecorder recorder =
                new LiveRecorder(
                        new LiveRecorder.Target(
                                "", LiveRecorder.Transport.SCREEN, "creator", "", ""),
                        record.source(),
                        source,
                        (LiveRecorder.Finisher) finisher.invoke(null, app, record),
                        ended -> {
                            try {
                                finished(
                                        record,
                                        ended.withScreenAudio(LiveRecorder.ScreenAudio.VIDEO_ONLY),
                                        result::set);
                            } catch (Throwable failure) {
                                failed.set(failure);
                            }
                        });
        holder.set(recorder);
        recorder.start();
        assertTrue("recorder worker did not finish", recorder.join(5000));
        assertNull(failed.get());
        assertNotNull(result.get());
        return result.get();
    }

    static void finished(
            RecordingRetention.Record record,
            LiveRecorder.Result result,
            LiveRecorder.Listener listener)
            throws Exception {
        Method finished =
                LiveRecordingSaver.class.getDeclaredMethod(
                        "finished",
                        RecordingRetention.Record.class,
                        LiveRecorder.Result.class,
                        LiveRecorder.Listener.class);
        finished.setAccessible(true);
        finished.invoke(null, record, result, listener);
    }

    @Test
    public void storageSizeAndDisconnectSurviveAPublicationFailureAndVisibleRetry()
            throws Exception {
        window(true);
        for (LiveRecorder.End end :
                new LiveRecorder.End[] {
                    LiveRecorder.End.STORAGE,
                    LiveRecorder.End.SIZE_LIMIT,
                    LiveRecorder.End.DISCONNECTED
                }) {
            RecordingRetention.Record record = ready();
            String id = record.id;
            RecordingRetentionTest.Provider.failOpen = true;
            LiveRecorder.Result initial = run(record, end, true);
            assertEquals(LiveRecorder.Outcome.FAILED, initial.outcome);
            assertEquals(id, initial.retainedId);
            RecordingRetention.Record retained = store.open(id);
            assertEquals(end.name(), retained.get("stopReason"));
            retained.close();
            RecordingRetentionTest.Provider.failOpen = false;
            retry();
            assertMessage(
                    LiveRecordingSaver.partialText(end, LiveRecordingSaver.folder())
                            + "\nThis screen recording has no sound.");
            assertTrue(store.ids().isEmpty());
        }
    }

    @Test
    public void explicitRetryReadsThePersistedCauseInsteadOfInventingAnAppExit() throws Exception {
        window(true);
        RecordingRetention.Record record = ready();
        record.update("stopReason", LiveRecorder.End.STORAGE.name());
        record.close();
        retry();
        assertMessage(
                "The phone is running out of room. What was recorded is saved to "
                        + LiveRecordingSaver.folder()
                        + "\nThis screen recording has no sound.");
    }

    @Test
    public void aKnownUserStopKeepsTheWholeSaveAndAnIncompletePrefixKeepsThePartialNotice()
            throws Exception {
        window(true);
        for (boolean partial : new boolean[] {false, true}) {
            RecordingRetention.Record record = ready();
            if (partial) record.update("captureIncomplete", true);
            RecordingRetentionTest.Provider.failOpen = true;
            assertEquals(
                    LiveRecorder.Outcome.FAILED,
                    run(record, LiveRecorder.End.STOPPED, true).outcome);
            RecordingRetentionTest.Provider.failOpen = false;
            retry();
            String text =
                    partial
                            ? "Recording stopped early. What was recorded is saved to "
                            : "LIVE recording saved to ";
            assertMessage(
                    text + LiveRecordingSaver.folder() + "\nThis screen recording has no sound.");
        }
    }

    @Test
    public void anOlderOrUnknownReasonGetsATruthfulRecoveredSaveNotice() throws Exception {
        window(true);
        for (String reason : new String[] {"", "NO_SUCH_END"}) {
            RecordingRetention.Record record = ready();
            if (!reason.isEmpty()) record.update("stopReason", reason);
            record.close();
            retry();
            assertMessage(
                    "Recovered recording saved to "
                            + LiveRecordingSaver.folder()
                            + "\nThis screen recording has no sound.");
        }
    }

    @Test
    public void startupStillReportsAppExitEvenIfAnEarlierAttemptHadStoppedNormally()
            throws Exception {
        window(true);
        RecordingRetention.Record record = ready();
        record.update("stopReason", LiveRecorder.End.STOPPED.name());
        record.close();
        List<LiveRecorder.Result> recovered = LiveRecordingSaver.recoverRetained(app);
        assertEquals(1, recovered.size());
        assertEquals(LiveRecorder.End.APP_EXIT, recovered.get(0).end);
        assertEquals(LiveRecorder.Outcome.SAVED_PARTIAL, recovered.get(0).outcome);
        LiveRecordingSaver.announce(recovered.get(0));
        banner();
        assertMessage(
                "TikTok closed. What was recorded is saved to "
                        + LiveRecordingSaver.folder()
                        + "\nThis screen recording has no sound.");
    }

    @Test
    public void emptySetupDoesNotGainReasonMetadataOrBlockTheNextCapture() throws Exception {
        for (boolean capture : new boolean[] {false, true}) {
            RecordingRetention.Record record = store.begin("flv", "creator", 1);
            if (capture) record.update("capture", true, "captureComplete", false);
            LiveRecorder.Result result = run(record, LiveRecorder.End.STOPPED, false);
            assertEquals(LiveRecorder.Outcome.NOTHING_SAVED, result.outcome);
            assertEquals("", record.get("stopReason"));
            assertTrue(store.ids().isEmpty());
            store.begin("flv", "again", 2).close();
        }
    }

    @Test
    public void publicationIntentAloneKeepsItsKnownReasonWithoutInventingPayload()
            throws Exception {
        RecordingRetention.Record record = store.begin("flv", "creator", 1);
        record.intent(
                record.id,
                "content://media/external/video/media",
                "hushfeed-pending-" + record.id + ".mp4",
                "DCIM/TikTok");
        AtomicReference<LiveRecorder.Result> result = new AtomicReference<>();
        finished(
                record,
                new LiveRecorder.Result(
                        LiveRecorder.Outcome.FAILED,
                        LiveRecorder.End.SIZE_LIMIT,
                        1,
                        null,
                        null,
                        null),
                result::set);
        assertEquals("SIZE_LIMIT", record.get("stopReason"));
        assertEquals(record.id, result.get().retainedId);
        assertTrue(record.directory.exists());
    }

    @Test
    public void verifiedRetryWithAnAckWriteFailureKeepsTheKnownReasonAndBothActions()
            throws Exception {
        window(true);
        RecordingRetention.Record record = ready();
        record.update("stopReason", LiveRecorder.End.DISCONNECTED.name());
        File obstruction = new File(record.directory, "record.json.new");
        record.close();
        RecordingRetentionTest.Provider.afterCommit =
                () -> {
                    if (!obstruction.mkdir()) throw new AssertionError("failed to obstruct ACK");
                };
        retry();
        assertMessage(
                LiveRecordingSaver.partialText(
                                LiveRecorder.End.DISCONNECTED, LiveRecordingSaver.folder())
                        + "\nThis screen recording has no sound.");
        assertNotNull(find(root(), "Retry"));
        assertEquals(1, RecordingRetentionTest.Provider.rows.size());
        assertTrue(obstruction.delete());
    }

    @Test
    public void reasonPersistenceFailureCannotUndoAVerifiedSaveOrLoseItsActions() throws Exception {
        window(true);
        RecordingRetention.Record record = ready();
        File source = record.file("screen.mp4");
        File obstruction = new File(record.directory, "record.json.new");
        RecordingRetentionTest.Provider.afterCommit =
                () -> {
                    if (!obstruction.mkdir())
                        throw new AssertionError("failed to obstruct the ACK and reason write");
                };
        LiveRecorder.Result result = run(record, LiveRecorder.End.STORAGE, true);
        assertEquals(LiveRecorder.Outcome.SAVED_PARTIAL, result.outcome);
        assertEquals(LiveRecorder.End.STORAGE, result.end);
        assertNotNull(result.saved);
        assertEquals(record.id, result.retainedId);
        assertArrayEquals(PAYLOAD, Files.readAllBytes(source.toPath()));
        assertEquals(1, RecordingRetentionTest.Provider.rows.size());
        LiveRecordingSaver.announce(result);
        banner();
        assertMessage(
                LiveRecordingSaver.partialText(
                                LiveRecorder.End.STORAGE, LiveRecordingSaver.folder())
                        + "\nThis screen recording has no sound.");
        assertNotNull(find(root(), "Retry"));
        assertTrue(obstruction.delete());
    }

    @Test
    public void aClosedOwnerCannotBeWrittenByTheCompletionBoundary() throws Exception {
        RecordingRetention.Record record = ready();
        File journal = new File(record.directory, "record.json");
        record.close();
        byte[] before = Files.readAllBytes(journal.toPath());
        AtomicReference<LiveRecorder.Result> result = new AtomicReference<>();
        finished(
                record,
                new LiveRecorder.Result(
                        LiveRecorder.Outcome.FAILED, LiveRecorder.End.STORAGE, 1, null, null, null),
                result::set);
        assertNotNull(result.get());
        assertArrayEquals(before, Files.readAllBytes(journal.toPath()));
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void bothRetainedDialogsUseTheDarkHouseStyle() throws Exception {
        styled(true);
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void bothRetainedDialogsUseTheLightHouseStyle() throws Exception {
        styled(false);
    }

    private void styled(boolean dark) throws Exception {
        window(dark);
        RecordingRetention.Record record = ready();
        record.close();
        AlertDialog list = recoveryList();
        assertStyled(list, "Record LIVEs you watch");
        screenshot(list, dark ? "dark-retained-list" : "light-retained-list");
        AlertDialog options = options(list);
        assertStyled(options, LiveRecordingSaver.fileName("creator", 1_760_480_102_000L));
        screenshot(options, dark ? "dark-retained-options" : "light-retained-options");
    }

    private void assertStyled(AlertDialog dialog, String title) {
        View decor = dialog.getWindow().getDecorView();
        decor.measure(
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, 360, decor.getMeasuredHeight());
        idle(60);
        assertTrue(
                "recovery dialog kept the stock window surface",
                decor.getBackground() instanceof GradientDrawable);
        assertEquals(
                SettingsUi.liftedSurface(),
                ((GradientDrawable) decor.getBackground()).getColor().getDefaultColor());
        assertEquals(SettingsUi.textPrimary(), find(decor, title).getCurrentTextColor());
        assertEquals(
                SettingsUi.textSecondary(),
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).getCurrentTextColor());
        View item = dialog.getListView().getChildAt(0);
        assertNotNull(item);
        TextView label =
                item instanceof TextView ? (TextView) item : item.findViewById(android.R.id.text1);
        assertNotNull(label);
        assertEquals(SettingsUi.textPrimary(), label.getCurrentTextColor());
    }

    private AlertDialog recoveryList() throws Exception {
        AlertDialog previous = ShadowAlertDialog.getLatestAlertDialog();
        LiveRecordingSaver.showRetained(app);
        await(
                () -> {
                    AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
                    return latest != null && latest != previous && latest.isShowing();
                });
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialogs.add(dialog);
        return dialog;
    }

    private AlertDialog options(AlertDialog list) throws Exception {
        assertTrue(
                list.getListView()
                        .performItemClick(
                                list.getListView()
                                        .getAdapter()
                                        .getView(0, null, list.getListView()),
                                0,
                                0));
        await(
                () -> {
                    AlertDialog latest = ShadowAlertDialog.getLatestAlertDialog();
                    return latest != list && latest != null && latest.isShowing();
                });
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialogs.add(dialog);
        return dialog;
    }

    private void retry() throws Exception {
        View previousOpen = find(root(), "Open");
        AlertDialog actions = options(recoveryList());
        ListView list = actions.getListView();
        assertEquals("Retry", list.getAdapter().getItem(0));
        assertTrue(list.performItemClick(list.getAdapter().getView(0, null, list), 0, 0));
        await(() -> find(root(), "Open") != null && find(root(), "Open") != previousOpen);
    }

    private void banner() throws Exception {
        await(() -> find(root(), "Open") != null);
    }

    private View root() {
        return owner.get().findViewById(android.R.id.content);
    }

    private void assertMessage(String expected) {
        assertNotNull("incorrect saved notice: " + expected, find(root(), expected));
    }

    interface Condition {
        boolean ready();
    }

    private static void await(Condition condition) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            idle(50);
            if (condition.ready()) return;
            Thread.sleep(10);
        }
        fail("recovery worker or visible action did not finish");
    }

    private static void idle(long ms) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private static TextView find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText()))
            return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void screenshot(AlertDialog dialog, String name) throws Exception {
        String output = System.getProperty("hushfeed.recovery.screenshots");
        if (output == null) return;
        File directory = new File(output);
        if (!directory.mkdirs() && !directory.isDirectory())
            throw new IOException("Cannot create renders");
        View view = dialog.getWindow().getDecorView();
        Bitmap bitmap =
                Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name + ".png"))) {
            view.draw(new Canvas(bitmap));
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } finally {
            bitmap.recycle();
        }
    }
}

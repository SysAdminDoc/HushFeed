/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import static org.junit.Assert.*;

import android.os.Looper;
import android.os.SystemClock;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorPatch;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;

/** Receipts come only from the existing extension callback, before its deferred playback work. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class FirstFrameReceiptTest {
    private boolean previousDebug;
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    public static final class Clip {
        final String aid;

        Clip(String id) {
            aid = id;
        }

        public String getAid() {
            return aid;
        }
    }

    public static final class Manager {
        public boolean isPlaying() {
            return true;
        }
    }

    public static final class Controller {
        public Object mCurrentSourceId;
        Clip aweme;
        Manager manager = new Manager();
        int modelReads, managerReads;
        Runnable duringModelRead;

        Controller(String id) {
            mCurrentSourceId = id;
            aweme = new Clip(id);
        }

        public Manager getPlayerManager() {
            managerReads++;
            return manager;
        }
    }

    @Before
    public void prepare() throws Exception {
        Arrays.fill(
                (Object[])
                        ReflectionHelpers.getStaticField(
                                SessionPlaybackHold.class, "FRAME_RECEIPTS"),
                null);
        ReflectionHelpers.setStaticField(SessionPlaybackHold.class, "nextFrameReceipt", 0);
        ReflectionHelpers.setStaticField(SessionPlaybackHold.class, "current", null);
        SessionPlaybackHold.nativeForTests =
                new SessionPlaybackHold.NativeControls() {
                    public Object currentAweme(Object raw) {
                        Controller c = (Controller) raw;
                        c.modelReads++;
                        if (c.duringModelRead != null) c.duringModelRead.run();
                        return c.aweme;
                    }

                    public void pause(Object raw) {
                        fail("Receipt capture must not pause.");
                    }

                    public void resume(Object raw) {
                        fail("Receipt capture must not resume.");
                    }
                };
        previousDebug = BaseSettings.DEBUG.get();
        BaseSettings.DEBUG.save(false);
    }

    @After
    public void restore() throws Exception {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        SessionPlaybackHold.nativeForTests = null;
        BaseSettings.DEBUG.save(previousDebug);
        ReflectionHelpers.setStaticField(SessionPlaybackHold.class, "current", null);
        Arrays.fill(
                (Object[])
                        ReflectionHelpers.getStaticField(
                                SessionPlaybackHold.class, "FRAME_RECEIPTS"),
                null);
    }

    private static Object[] receipts() throws Exception {
        Method m = SessionPlaybackHold.class.getDeclaredMethod("snapshotFirstFrameReceipts");
        m.setAccessible(true);
        return (Object[]) m.invoke(null);
    }

    private static Object only() throws Exception {
        Object found = null;
        for (Object record : receipts())
            if (record != null) {
                assertNull(found);
                found = record;
            }
        assertNotNull(
                "The native callback must retain a source-bound receipt with diagnostics off.",
                found);
        return found;
    }

    private static void none() throws Exception {
        for (Object record : receipts()) assertNull(record);
    }

    private static long at(Object record) {
        return ReflectionHelpers.getField(record, "elapsedRealtimeMs");
    }

    private static Object owner(Object record, String name) {
        return ((WeakReference<?>) ReflectionHelpers.getField(record, name)).get();
    }

    @Test
    public void callbackTimePrecedesExistingNativeReadsAndMainWorkWithDiagnosticsOff()
            throws Exception {
        Controller c = new Controller("source");
        long expected = SystemClock.elapsedRealtime();
        c.duringModelRead = () -> ShadowSystemClock.advanceBy(Duration.ofMillis(75));
        BlockAuthorPatch.onFirstFrame(c);
        Object record = only();
        assertEquals(expected, at(record));
        assertTrue(at(record) < SystemClock.elapsedRealtime());
        assertEquals("source", ReflectionHelpers.getField(record, "sourceId"));
        assertSame(c, owner(record, "controller"));
        assertSame(c.aweme, owner(record, "aweme"));
        assertSame(c.manager, owner(record, "manager"));
        assertNull(ReflectionHelpers.getStaticField(SessionPlaybackHold.class, "current"));
        assertEquals(1, c.modelReads);
        assertEquals(1, c.managerReads);
    }

    @Test
    public void aMissingOrMismatchedSourceCannotBorrowTheAwemeId() throws Exception {
        Controller c = new Controller("aid");
        c.mCurrentSourceId = null;
        BlockAuthorPatch.onFirstFrame(c);
        none();
        c.mCurrentSourceId = "different";
        BlockAuthorPatch.onFirstFrame(c);
        none();
        c.mCurrentSourceId = 123L;
        BlockAuthorPatch.onFirstFrame(c);
        none();
    }

    @Test
    public void aReboundSourceDuringTheExistingModelReadIsNotRecorded() throws Exception {
        Controller c = new Controller("old");
        c.duringModelRead = () -> c.mCurrentSourceId = "new";
        BlockAuthorPatch.onFirstFrame(c);
        none();
    }

    @Test
    public void callbackWithoutAManagerOrValidIdHasNoReceipt() throws Exception {
        Controller c = new Controller("source");
        c.manager = null;
        BlockAuthorPatch.onFirstFrame(c);
        none();
        c = new Controller("");
        BlockAuthorPatch.onFirstFrame(c);
        none();
        BlockAuthorPatch.onFirstFrame(null);
        none();
    }

    @Test
    public void captureIsBoundedAndSnapshotDoesNotReadOrRetainNativeOwnersStrongly()
            throws Exception {
        Controller c = new Controller("source");
        for (int i = 0; i < 80; i++) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(1));
            BlockAuthorPatch.onFirstFrame(c);
        }
        assertEquals(32, receipts().length);
        int models = c.modelReads, managers = c.managerReads;
        Object[] copy = receipts();
        for (Object record : copy) {
            assertNotNull(record);
            assertSame(c, owner(record, "controller"));
        }
        Arrays.fill(copy, null);
        assertNotNull(receipts()[0]);
        assertEquals(models, c.modelReads);
        assertEquals(managers, c.managerReads);
    }

    @Test
    public void progressCannotSynthesizeAnExtensionFrameReceipt() throws Exception {
        Controller c = new Controller("source");
        SessionPlaybackHold.onPlayerProgress(c, "source");
        none();
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowToast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * A data export's likes saved as one run: each post asked for by id at a paced gap, saved as a
 * picked video is, a post TikTok no longer has counted on its own line, and videos saved here
 * before left out before the run is cut to its most.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, qualifiers = "en")
public class ExportSaveTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        SaveNotice.windowRootsForTests = List.of();
        SaveRecordsFixtures.reset(context);
        ShadowToast.reset();
        ExportSave.gapForTests = 0;
    }

    @After public void tearDown() throws Exception {
        ExportSave.gapForTests = -1;
        SaveRecordsFixtures.reset(context);
        SaveNotice.windowRootsForTests = null;
        Settings.CHECK_SAVED_VIDEOS.resetToDefault();
        SavedVideoArchiveAccess.reset(context);
    }

    private static String said() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(SaveNotice.SHEET_SETTLE_MS + 1, TimeUnit.MILLISECONDS);
        return ShadowToast.getTextOfLatestToast();
    }

    @Test public void eachPostIsAskedForThenSavedAndOnesTikTokNoLongerHasAreNamed() {
        List<String> asked = new ArrayList<>();
        List<Object> saved = new ArrayList<>();
        List<String> ids = Arrays.asList("a", "b", "c", "d", "e");
        ExportSave.run(context, ids, id -> {
            asked.add(id);
            if ("b".equals(id)) return null;
            if ("c".equals(id)) throw new IOException("no network");
            return "post " + id;
        }, (app, post, last) -> {
            saved.add(post);
            return "post d".equals(post) ? BatchSave.Result.ALREADY_SAVED : BatchSave.Result.SAVED;
        }, SaveProgress.queuedFiles(ids.size()));
        assertEquals(ids, asked);
        assertEquals(Arrays.asList("post a", "post d", "post e"), saved);
        assertEquals("Saved 3 of 5, 2 skipped\nOne was already saved\nOne isn't on TikTok anymore", said());
    }

    @Test public void everyPostSavedSaysHowMany() {
        ExportSave.run(context, Arrays.asList("a", "b"), id -> "post " + id,
                (app, post, last) -> BatchSave.Result.SAVED, SaveProgress.queuedFiles(2));
        assertEquals("Saved 2 videos", said());
    }

    @Test public void aRunOfOnlyGonePostsSaysThatAlone() {
        ExportSave.run(context, Arrays.asList("a", "b"), id -> null,
                (app, post, last) -> BatchSave.Result.SAVED, SaveProgress.queuedFiles(2));
        assertEquals("2 aren't on TikTok anymore", said());
    }

    @Test public void aPostTikTokNoLongerHasRaisesNoErrorToastWithDebugOn() {
        BaseSettings.DEBUG.save(true);
        BaseSettings.DEBUG_TOAST_ON_ERROR.save(true);
        try {
            ShadowApplication app = Shadows.shadowOf(RuntimeEnvironment.getApplication());
            ExportSave.run(context, Arrays.asList("g1", "g2", "g3"), id -> null,
                    (a, post, last) -> BatchSave.Result.SAVED, SaveProgress.queuedFiles(3));
            assertEquals("3 aren't on TikTok anymore", said());
            assertEquals("only the line at the end", 1, app.getShownToasts().size());

            // A file that really failed still toasts while debugging, so the count above isn't one
            // of debug toasts being off here.
            ShadowToast.reset();
            ExportSave.run(context, Arrays.asList("g4", "f5"), id -> {
                if ("f5".equals(id)) throw new IOException("the network dropped while asking for f5");
                return null;
            }, (a, post, last) -> BatchSave.Result.SAVED, SaveProgress.queuedFiles(2));
            assertEquals("None of the picked videos could be saved. Try again.", said());
            assertEquals("the failed file's toast and the line at the end", 2, app.getShownToasts().size());
        } finally {
            BaseSettings.DEBUG.resetToDefault();
            BaseSettings.DEBUG_TOAST_ON_ERROR.resetToDefault();
        }
    }

    @Test public void asksAreSpacedOut() {
        ExportSave.gapForTests = 60;
        List<Long> times = new ArrayList<>();
        ExportSave.run(context, Arrays.asList("a", "b", "c"), id -> {
            times.add(System.nanoTime());
            return "post " + id;
        }, (app, post, last) -> BatchSave.Result.SAVED, SaveProgress.queuedFiles(3));
        for (int i = 1; i < times.size(); i++) {
            long gap = TimeUnit.NANOSECONDS.toMillis(times.get(i) - times.get(i - 1));
            assertTrue("two asks came " + gap + " ms apart", gap >= 55);
        }
    }

    @Test public void videosSavedBeforeAreLeftOutBeforeTheRunIsCut() throws IOException {
        Settings.CHECK_SAVED_VIDEOS.save(true);
        SavedVideoArchiveAccess.remember(context, "7000000000000000002");
        SavedVideoArchiveAccess.remember(context, "7000000000000000005");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < ExportSave.MAX_RUN + 5; i++) ids.add(String.valueOf(7000000000000000000L + i));

        ExportSave.Plan plan = ExportSave.plan(context, ids);

        assertEquals(ExportSave.MAX_RUN, plan.ids.size());
        assertEquals(2, plan.alreadySaved);
        assertEquals(3, plan.later);
        assertEquals("7000000000000000000", plan.ids.get(0));
        assertEquals("7000000000000000003", plan.ids.get(2));
        assertTrue(!plan.ids.contains("7000000000000000005"));
    }

    @Test public void withTheCheckOffNothingIsLeftOut() throws IOException {
        Settings.CHECK_SAVED_VIDEOS.save(false);
        SavedVideoArchiveAccess.remember(context, "7000000000000000001");
        ExportSave.Plan plan = ExportSave.plan(context, Arrays.asList("7000000000000000001", "7000000000000000002"));
        assertEquals(Arrays.asList("7000000000000000001", "7000000000000000002"), plan.ids);
        assertEquals(0, plan.alreadySaved);
    }
}

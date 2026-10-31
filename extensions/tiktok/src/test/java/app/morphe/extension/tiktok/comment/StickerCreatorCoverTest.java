/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Make sticker in the share sheet (#88): the video's cover goes through the photo path and is cleaned up. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StickerCreatorCoverTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final StickerCreator.CoverFetcher stockFetcher = StickerCreator.coverFetcher;
    private final List<File> targets = new ArrayList<>();

    @After public void tearDown() {
        StickerCreator.coverFetcher = stockFetcher;
    }

    @Test public void aPostWithNoCoverSaysItCouldNotMakeOne() throws InterruptedException {
        Object post = new Object();
        StickerCreator.coverFetcher = (context, aweme, target) -> {
            assertSame(post, aweme);
            targets.add(target);
            return false;
        };
        StickerCreator.fromCover(context(), post);
        awaitToast("Couldn't make the sticker. Try again.");
        assertEquals(1, targets.size());
        assertFalse(targets.get(0).exists());
    }

    /** An unpatched build has no upload token, so a readable cover still ends at the save failing. */
    @Test public void aFetchedCoverIsDeletedWhateverTheUploadSays() throws InterruptedException {
        StickerCreator.coverFetcher = (context, aweme, target) -> {
            targets.add(target);
            Bitmap cover = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888);
            try (OutputStream out = new FileOutputStream(target)) {
                cover.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
            return true;
        };
        StickerCreator.fromCover(context(), new Object());
        awaitToast("Couldn't make the sticker. Try again.");
        assertFalse("the downloaded cover", targets.get(0).exists());
        String[] left = context().getCacheDir().list((folder, name) -> name.startsWith("hushfeed-sticker"));
        assertNotNull(left);
        assertEquals("no cover or prepared PNG is left in the cache", 0, left.length);
    }

    @Test public void aFailedDownloadLetsTheNextTapThrough() throws InterruptedException {
        StickerCreator.coverFetcher = (context, aweme, target) -> {
            targets.add(target);
            throw new IOException("offline");
        };
        StickerCreator.fromCover(context(), new Object());
        awaitToast("Couldn't make the sticker. Try again.");
        StickerCreator.fromCover(context(), new Object());
        awaitToast("Couldn't make the sticker. Try again.");
        assertEquals("the second tap wasn't turned away as busy", 2, targets.size());
    }

    @Test public void noContextMakesNothing() {
        StickerCreator.coverFetcher = (context, aweme, target) -> {
            throw new AssertionError("fetched without a context");
        };
        StickerCreator.fromCover(null, new Object());
    }

    /** The worker toasts its answer through the main thread, after "Making your sticker". */
    private static void awaitToast(String wanted) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle();
            if (wanted.equals(ShadowToast.getTextOfLatestToast())) {
                ShadowToast.reset();
                return;
            }
            Thread.sleep(20);
        }
        fail("no toast \"" + wanted + "\", last was \"" + ShadowToast.getTextOfLatestToast() + "\"");
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }
}

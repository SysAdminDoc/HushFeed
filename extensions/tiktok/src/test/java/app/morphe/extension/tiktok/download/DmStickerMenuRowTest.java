package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.view.View;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Save media in a sticker message's long-press menu (#470), with TikTok's row pieces stood in. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DmStickerMenuRowTest {
    private static final int ICON = 0x7f010001;
    private final Object menu = new Object();
    private FakeRows pieces;

    /** What the patch's bridges answer on a patched build, recorded instead of built. */
    static final class FakeRows implements StickerGallerySaver.DmMenuRows {
        int icon = ICON;
        boolean bridged = true;
        String title;
        int givenIcon;
        View.OnClickListener action;
        final List<Object> dismissed = new ArrayList<>();
        final Object row = new Object();

        @Override public int icon() {
            return icon;
        }

        @Override public Object row(String title, int icon, View.OnClickListener action) {
            if (!bridged) return null;
            this.title = title;
            this.givenIcon = icon;
            this.action = action;
            return row;
        }

        @Override public void dismiss(Object menu) {
            dismissed.add(menu);
        }
    }

    /** Kotlin's Function1 as TikTok's menu calls it, for the click the row carries. */
    public interface RowClick {
        Object invoke(Object view);
    }

    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        pieces = new FakeRows();
        StickerGallerySaver.dmMenuRows = pieces;
    }

    @After public void cleanup() {
        StickerGallerySaver.dmMenuRows = StickerGallerySaver.NATIVE_DM_MENU_ROWS;
        Settings.DM_STICKER_SAVE_BOX.resetToDefault();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void saveMediaGoesAfterTikToksOwnRowsWithItsLabelAndIcon() {
        List<Object> rows = new ArrayList<>(Arrays.asList("reply", "fav sticker", "store"));
        StickerGallerySaver.addDmStickerSaveRow(rows, template(null), menu);
        assertEquals(Arrays.asList("reply", "fav sticker", "store", pieces.row), rows);
        assertEquals(L10n.t("Save media"), pieces.title);
        assertEquals(ICON, pieces.givenIcon);
        assertTrue(pieces.dismissed.isEmpty());
    }

    @Test public void turningTheSaveBoxOffLeavesTheMenuToTikTok() {
        Settings.DM_STICKER_SAVE_BOX.save(false);
        List<Object> rows = new ArrayList<>(Arrays.asList("reply"));
        StickerGallerySaver.addDmStickerSaveRow(rows, template(null), menu);
        assertEquals(Arrays.asList("reply"), rows);
        assertNull(pieces.title);
    }

    @Test public void aStickerWithoutASourceThisCanSaveGetsNoRow() {
        List<Object> rows = new ArrayList<>(Arrays.asList("reply"));
        StickerGallerySaver.addDmStickerSaveRow(rows, new DmStickerPreviewTest.MissingKeyTemplate(), menu);
        StickerGallerySaver.addDmStickerSaveRow(rows, template("not-a-key"), menu);
        StickerGallerySaver.addDmStickerSaveRow(rows, new Object(), menu);
        assertEquals(Arrays.asList("reply"), rows);
        assertNull(pieces.title);
    }

    @Test public void aMissingIconOrAnUnpatchedBridgeAddsNothing() {
        List<Object> rows = new ArrayList<>();
        pieces.icon = 0;
        StickerGallerySaver.addDmStickerSaveRow(rows, template(null), menu);
        assertTrue(rows.isEmpty());
        assertNull(pieces.title);

        pieces.icon = ICON;
        pieces.bridged = false;
        StickerGallerySaver.addDmStickerSaveRow(rows, template(null), menu);
        assertTrue(rows.isEmpty());
    }

    @Test public void theStubsAloneAddNothingAndCloseNothing() throws Exception {
        StickerGallerySaver.dmMenuRows = StickerGallerySaver.NATIVE_DM_MENU_ROWS;
        List<Object> rows = new ArrayList<>(Arrays.asList("reply"));
        StickerGallerySaver.addDmStickerSaveRow(rows, template(null), menu);
        assertEquals(Arrays.asList("reply"), rows);
        assertNull(StickerGallerySaver.NATIVE_DM_MENU_ROWS.row("Save media", ICON, ignored -> { }));
        StickerGallerySaver.NATIVE_DM_MENU_ROWS.dismiss(menu);
    }

    @Test public void missingPiecesNeverThrowIntoTikToksMenu() {
        List<Object> rows = new ArrayList<>();
        StickerGallerySaver.addDmStickerSaveRow(null, template(null), menu);
        StickerGallerySaver.addDmStickerSaveRow(rows, null, menu);
        StickerGallerySaver.addDmStickerSaveRow(rows, template(null), null);
        assertTrue(rows.isEmpty());
        assertNull(pieces.title);
    }

    @Test public void theRowsClickTakesOnlyTheTappedViewAndAnswersWhatTikTokExpects() {
        Object done = new Object();
        AtomicInteger taps = new AtomicInteger();
        View view = new View(RuntimeEnvironment.getApplication());
        RowClick click = (RowClick) StickerGallerySaver.dmMenuClick(RowClick.class, done, tapped -> {
            assertSame(view, tapped);
            taps.incrementAndGet();
        });
        assertSame(done, click.invoke(view));
        assertEquals(1, taps.get());
        assertSame(done, click.invoke("not a view"));
        assertSame(done, click.invoke(null));
        assertEquals(1, taps.get());
        assertEquals(StickerGallerySaver.DM_MENU_ROW, click.toString());
        assertTrue(click.equals(click));
        assertFalse(click.equals(done));
        assertEquals(System.identityHashCode(click), click.hashCode());
    }

    @Test public void aTapClosesTheMenuThenQueuesOnlyTheFullSource() throws Exception {
        List<Object> rows = new ArrayList<>();
        StickerGallerySaver.addDmStickerSaveRow(rows, template(null), menu);
        assertEquals(1, rows.size());
        CountDownLatch hold = new CountDownLatch(1), started = new CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
        List<MediaJobScheduler.Job> waiting = new ArrayList<>();
        try {
            for (int i = 0; i < MediaJobScheduler.MAX_RUNNING_JOBS; i++) {
                assertTrue(MediaJobScheduler.submit("DM menu test hold", () -> {
                    started.countDown();
                    try { hold.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }));
            }
            assertTrue(started.await(3, TimeUnit.SECONDS));
            pieces.action.onClick(new View(RuntimeEnvironment.getApplication()));
            assertEquals(Arrays.asList(menu), pieces.dismissed);
            Field field = MediaJobScheduler.class.getDeclaredField("EXECUTOR");
            field.setAccessible(true);
            for (Runnable runnable : ((ThreadPoolExecutor) field.get(null)).getQueue()) {
                if (runnable instanceof MediaJobScheduler.Job) waiting.add((MediaJobScheduler.Job) runnable);
            }
            assertEquals(1, waiting.size());
            assertEquals("sticker", waiting.get(0).label);
            assertNull(waiting.get(0).key);
        } finally {
            for (MediaJobScheduler.Job job : waiting) job.cancel();
            hold.countDown();
            for (int i = 0; i < 500 && !MediaJobScheduler.idle(); i++) Thread.sleep(10);
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertTrue(MediaJobScheduler.idle());
    }

    /** The patch calls the hook and writes the stubs by these exact shapes. */
    @Test public void theHookAndTheStubsKeepTheShapesThePatchWrites() throws Exception {
        assertShape(StickerGallerySaver.class.getDeclaredMethod("addDmStickerSaveRow", List.class, Object.class, Object.class),
                void.class, true);
        assertShape(StickerGallerySaver.class.getDeclaredMethod("dmStickerMenuRow", String.class, String.class, int.class, Object.class),
                Object.class, false);
        assertShape(StickerGallerySaver.class.getDeclaredMethod("dmStickerMenuDone"), Object.class, false);
        assertShape(StickerGallerySaver.class.getDeclaredMethod("dismissDmStickerMenu", Object.class), void.class, false);
    }

    private static void assertShape(Method method, Class<?> returns, boolean isPublic) {
        assertTrue(method.getName(), Modifier.isStatic(method.getModifiers()));
        assertEquals(method.getName(), isPublic, Modifier.isPublic(method.getModifiers()));
        assertEquals(method.getName(), returns, method.getReturnType());
    }

    private static DmStickerPreviewTest.Template template(String key) {
        return new DmStickerPreviewTest.Template(
                new DmStickerPreviewTest.ImageComponent("https://example.invalid/held.gif", key), null, null);
    }
}

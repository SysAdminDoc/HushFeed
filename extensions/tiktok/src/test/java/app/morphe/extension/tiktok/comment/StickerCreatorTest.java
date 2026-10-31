/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Create on the comment panel's saved stickers page (#156): the button, its place and the photo's size. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StickerCreatorTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for the page's sticker grid, which StickerCreator finds by its class name. */
    static final class RecyclerView extends FrameLayout {
        boolean scrolled;

        RecyclerView(Context context) {
            super(context);
        }

        @Override
        public boolean canScrollVertically(int direction) {
            return direction < 0 && scrolled;
        }
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        StickerRecents.rowsAboveForTests(0);
    }

    @Test public void fitKeepsTheShapeWithNeitherSideOverTheMax() {
        assertArrayEquals(new int[]{512, 256}, StickerCreator.fit(2048, 1024, 512));
        assertArrayEquals(new int[]{288, 512}, StickerCreator.fit(1080, 1920, 512));
        assertArrayEquals(new int[]{300, 200}, StickerCreator.fit(300, 200, 512));
        assertArrayEquals(new int[]{512, 1}, StickerCreator.fit(6000, 3, 512));
        assertArrayEquals(new int[]{0, 0}, StickerCreator.fit(0, 400, 512));
    }

    @Test public void theSavedPageGetsOneCreateButton() {
        FrameLayout page = page();
        StickerCreator.onFavouritesPage(page);
        StickerCreator.onFavouritesPage(page);
        int buttons = 0;
        for (int index = 0; index < page.getChildCount(); index++) {
            if (StickerCreator.PILL_TAG.equals(page.getChildAt(index).getTag())) buttons++;
        }
        assertEquals(1, buttons);
        TextView pill = page.findViewWithTag(StickerCreator.PILL_TAG);
        assertEquals("Create", pill.getText().toString());
        assertEquals("Make a sticker from a photo", pill.getContentDescription().toString());
    }

    @Test public void aPageWithNoGridOrAPausedHushfeedGetsNone() {
        FrameLayout empty = new FrameLayout(context());
        StickerCreator.onFavouritesPage(empty);
        assertNull(empty.findViewWithTag(StickerCreator.PILL_TAG));

        PausedProcess.set(true);
        FrameLayout page = page();
        StickerCreator.onFavouritesPage(page);
        assertNull(page.findViewWithTag(StickerCreator.PILL_TAG));
    }

    @Test public void createSitsLevelWithTheHeadingAndHidesOnceItScrollsAway() {
        RecyclerView grid = new RecyclerView(context());
        View heading = new View(context());
        grid.addView(heading);
        grid.layout(0, 100, 1000, 1100);
        heading.layout(0, 0, 1000, 120);
        TextView pill = new TextView(context());
        pill.layout(0, 0, 200, 60);

        StickerCreator.place(grid, pill);
        assertEquals(800f, pill.getTranslationX(), 0f);
        assertEquals(130f, pill.getTranslationY(), 0f);
        assertEquals(View.VISIBLE, pill.getVisibility());

        grid.scrolled = true;
        StickerCreator.place(grid, pill);
        assertEquals(View.INVISIBLE, pill.getVisibility());
        grid.scrolled = false;
        StickerCreator.place(grid, pill);
        assertEquals(View.VISIBLE, pill.getVisibility());
    }

    @Test public void createSkipsRecentlyUsedForTheSavedHeading() {
        RecyclerView grid = new RecyclerView(context());
        View recentHeading = new View(context());
        View recent = new View(context());
        View savedHeading = new View(context());
        grid.addView(recentHeading);
        grid.addView(recent);
        grid.layout(0, 100, 1000, 1100);
        recentHeading.layout(0, 0, 1000, 120);
        recent.layout(0, 120, 250, 370);
        TextView pill = new TextView(context());
        pill.layout(0, 0, 200, 60);

        StickerRecents.rowsAboveForTests(2);
        StickerCreator.place(grid, pill);
        assertEquals("the Saved heading isn't drawn yet", View.INVISIBLE, pill.getVisibility());

        grid.addView(savedHeading);
        savedHeading.layout(0, 370, 1000, 490);
        StickerCreator.place(grid, pill);
        assertEquals(500f, pill.getTranslationY(), 0f);
        assertEquals(View.VISIBLE, pill.getVisibility());
    }

    @Test public void thePickerAsksForImagesBeforeAndroid13() {
        Intent intent = StickerCreator.pickIntent();
        assertEquals(Intent.ACTION_GET_CONTENT, intent.getAction());
        assertEquals("image/*", intent.getType());
    }

    /** What a suspend call finished with comes out of Kotlin's one-field boxes, and a failure is nothing. */
    @Test public void unboxOpensResultsAndDropsFailures() {
        Object answer = new Object();
        assertSame(answer, StickerCreator.unbox(new Box(new Box(answer)), value -> value == answer));
        assertNull(StickerCreator.unbox(new Box(new IllegalStateException("no")), value -> false));
        assertNull(StickerCreator.unbox(new Box(new Object()), value -> false));
    }

    @Test public void anUnpatchedBuildMakesNoSuspendCall() throws InterruptedException {
        assertNull(StickerCreator.continuationType());
        assertNull(StickerCreator.await(continuation -> {
            throw new AssertionError("called without a continuation type");
        }));
    }

    @Test public void bridgesAnswerNothingUnpatched() {
        assertNull(StickerCreator.uploadConfig(null));
        assertNull(StickerCreator.uploadServerParameter());
        assertNull(StickerCreator.saveSticker(StickerCreator.PHOTO_STICKER, "key", 1, 1, null, null, null, null));
        StickerCreator.addToSaved(new Object());
    }

    static final class Box {
        final Object value;

        Box(Object value) {
            this.value = value;
        }
    }

    private FrameLayout page() {
        FrameLayout page = new FrameLayout(context());
        FrameLayout holder = new FrameLayout(context());
        holder.addView(new RecyclerView(context()));
        page.addView(holder);
        return page;
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }
}

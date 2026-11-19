/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * The comment box inside a LIVE room (#267, #323): a room Dark LIVE panels darkened makes the
 * typed text and the hint light, and a room it left light keeps TikTok's colors. In a dark room
 * the rest of the panel follows (#329): the picker's white boxes take the panel's own fill and the
 * bar's dark icons are drawn white.
 */
@RunWith(RobolectricTestRunner.class)
public class LiveCommentBoxTest {
    private static final int TIKTOK_TEXT = 0xFF161823;
    private static final int TIKTOK_HINT = 0xFF888888;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.liveControlsEnabled = true;
        Settings.DARK_LIVE_PANELS.save(true);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        SettingsStatus.liveControlsEnabled = false;
        Settings.DARK_LIVE_PANELS.save(Settings.DARK_LIVE_PANELS.defaultValue);
    }

    private static EditText box(FrameLayout root) {
        EditText box = new EditText(root.getContext());
        box.setTextColor(TIKTOK_TEXT);
        box.setHintTextColor(TIKTOK_HINT);
        FrameLayout inner = new FrameLayout(root.getContext());
        inner.addView(box);
        root.addView(inner);
        return box;
    }

    private static void assertTikToksColors(EditText box) {
        assertEquals(TIKTOK_TEXT, box.getCurrentTextColor());
        assertEquals(TIKTOK_HINT, box.getCurrentHintTextColor());
    }

    @Test
    @Config(qualifiers = "night")
    public void aDarkRoomLightensTheTypedTextAndTheHint() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        EditText box = box(root);
        LiveCommentBox.paint(root);
        assertEquals(Color.WHITE, box.getCurrentTextColor());
        assertEquals(LiveCommentBox.HINT, box.getCurrentHintTextColor());
    }

    @Test
    @Config(qualifiers = "notnight")
    public void lightModeKeepsTikToksColors() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        EditText box = box(root);
        LiveCommentBox.paint(root);
        assertTikToksColors(box);
    }

    /** The room stays light with the switch off, even in dark mode, so white text would vanish. */
    @Test
    @Config(qualifiers = "night")
    public void aRoomTheSwitchLeftLightKeepsTikToksColorsInDarkMode() {
        Settings.DARK_LIVE_PANELS.save(false);
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        EditText box = box(root);
        LiveCommentBox.paint(root);
        assertTikToksColors(box);
    }

    @Test
    @Config(qualifiers = "night")
    public void pauseLeavesTheBoxAlone() {
        PausedProcess.set(true);
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        EditText box = box(root);
        LiveCommentBox.paint(root);
        assertTikToksColors(box);
    }

    @Test
    public void aMissingPanelIsLeftAlone() {
        LiveCommentBox.onPanel(null);
        LiveCommentBox.paint(null);
    }

    /** A custom AMOLED color, the one #329 was reported with. */
    private static final int AMOLED = 0xFF181825;

    /** The panel's root, its AMOLED colored fill under it, as the room lays it out. */
    private static FrameLayout panel(FrameLayout root) {
        FrameLayout panel = new FrameLayout(root.getContext());
        panel.setBackground(new ColorDrawable(AMOLED));
        root.addView(panel);
        return panel;
    }

    private static View filled(FrameLayout panel, Drawable background) {
        View view = new View(panel.getContext());
        view.setBackground(background);
        panel.addView(view);
        return view;
    }

    private static int solid(View view) {
        return ((ColorDrawable) view.getBackground()).getColor();
    }

    /** An icon of one ink on a clear ground, the way TikTok's bar draws its heart and emoji buttons. */
    private static ImageView icon(ViewGroup parent, int ink) {
        ImageView icon = new ImageView(parent.getContext());
        icon.setScaleType(ImageView.ScaleType.FIT_XY);
        icon.setPadding(10, 10, 10, 10);
        icon.setImageDrawable(new ColorDrawable(ink));
        parent.addView(icon, new LinearLayout.LayoutParams(40, 40));
        return icon;
    }

    private static void layOut(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 600);
    }

    /** #329: the picker's white boxes behind send and delete take the panel's own dark fill. */
    @Test
    @Config(qualifiers = "night")
    public void aDarkRoomGivesThePickersWhiteBoxesThePanelsOwnFill() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout panel = panel(root);
        GradientDrawable sendBox = new GradientDrawable();
        sendBox.setCornerRadius(8f);
        sendBox.setColor(Color.WHITE);
        filled(panel, sendBox);
        View deleteBox = filled(panel, new ColorDrawable(0xFFF6F6F6));
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00FFFFFF, Color.WHITE});
        filled(panel, fade);
        View tile = filled(panel, new ColorDrawable(0x29FFFFFF));
        View yellow = filled(panel, new ColorDrawable(0xFFFFC400));
        ImageView photo = new ImageView(root.getContext());
        photo.setImageDrawable(new ColorDrawable(Color.BLUE));
        photo.setBackground(new ColorDrawable(Color.WHITE));
        panel.addView(photo);

        LiveCommentBox.paint(root);

        assertEquals("the send box", AMOLED, sendBox.getColor().getDefaultColor());
        assertEquals("the delete box", AMOLED, solid(deleteBox));
        assertArrayEquals("a white fade keeps its see-through end", new int[]{0x00181825, AMOLED}, fade.getColors());
        assertEquals("a see-through tile stays", 0x29FFFFFF, solid(tile));
        assertEquals("a colored button keeps its color", 0xFFFFC400, solid(yellow));
        assertEquals("a photo's ground stays", Color.WHITE, solid(photo));
    }

    /** The picker's pages come from an adapter after the panel is set up. */
    @Test
    @Config(qualifiers = "night")
    public void aPickerPageThatArrivesLaterIsDarkenedOnTheNextLayout() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout panel = panel(root);
        LiveCommentBox.paint(root);

        View page = filled(panel, new ColorDrawable(Color.WHITE));
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals(AMOLED, solid(page));
    }

    /** #329: the bar's heart and emoji buttons, dark ink on their faint pill, are drawn white. */
    @Test
    @Config(qualifiers = "night")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theBarsDarkIconsAreDrawnWhiteAndCheckedAgainEachFrame() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout panel = panel(root);
        LinearLayout pill = new LinearLayout(root.getContext());
        GradientDrawable faint = new GradientDrawable();
        faint.setColor(0x0F161823);
        pill.setBackground(faint);
        panel.addView(pill);
        ImageView heart = icon(pill, 0xFF161823);
        ImageView send = icon(pill, 0xFFFE2C55);
        TextView count = new TextView(root.getContext());
        count.setTextColor(0x99161823);
        pill.addView(count);
        layOut(root);

        LiveCommentBox.paint(root);
        assertNotNull("the heart goes white", heart.getColorFilter());
        assertNull("a colored send button stays", send.getColorFilter());
        assertEquals("gray text goes light", 0x99FFFFFF, count.getCurrentTextColor());

        // TikTok gives the heart a colored icon, then a dark one again: each frame shows it right.
        heart.setImageDrawable(new ColorDrawable(0xFFFE2C55));
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertNull(heart.getColorFilter());
        heart.setImageDrawable(new ColorDrawable(0xFF161823));
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertNotNull(heart.getColorFilter());
    }

    /** TikTok's panel behind delete and send: a near-white box, a faint glow ring, clear corners. */
    private static Bitmap glowPicture() {
        Bitmap picture = Bitmap.createBitmap(170, 96, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < 170; x++) {
            for (int y = 0; y < 96; y++) {
                int edge = Math.min(Math.min(x, 169 - x), Math.min(y, 95 - y));
                picture.setPixel(x, y, edge < 4 ? Color.TRANSPARENT : edge < 12 ? 0x30F9F9F9 : 0xFFF9F9F9);
            }
        }
        return picture;
    }

    /** A thin white bar on a clear ground, like a glyph drawn as a background. */
    private static Bitmap glyphPicture() {
        Bitmap picture = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        for (int x = 4; x < 44; x++) {
            for (int y = 22; y < 26; y++) picture.setPixel(x, y, Color.WHITE);
        }
        return picture;
    }

    private static int drawnAt(Drawable drawable, int x, int y) {
        Bitmap out = Bitmap.createBitmap(170, 96, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, 170, 96);
        drawable.draw(new Canvas(out));
        return out.getPixel(x, y);
    }

    /**
     * #329: the panel behind the picker's delete and send keys is a white picture with a soft
     * glow. It takes the panel's fill, its glow stays see-through, and the delete key on it is lit.
     */
    @Test
    @Config(qualifiers = "night")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theWhiteGlowPanelBehindDeleteAndSendTakesThePanelsFill() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout panel = panel(root);
        LinearLayout keys = new LinearLayout(root.getContext());
        BitmapDrawable glow = new BitmapDrawable(root.getResources(), glowPicture());
        BitmapDrawable glowElsewhere = (BitmapDrawable) glow.getConstantState().newDrawable();
        keys.setBackground(glow);
        panel.addView(keys);
        ImageView delete = icon(keys, 0xFF161823);
        Bitmap blue = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        blue.eraseColor(0xFF3366CC);
        BitmapDrawable photo = new BitmapDrawable(root.getResources(), blue);
        filled(panel, photo);
        BitmapDrawable glyph = new BitmapDrawable(root.getResources(), glyphPicture());
        filled(panel, glyph);
        layOut(root);

        LiveCommentBox.paint(root);

        assertEquals("the white box takes the panel's fill", AMOLED, drawnAt(glow, 85, 48));
        assertEquals("its glow stays see-through", 0x30, Color.alpha(drawnAt(glow, 85, 6)));
        assertEquals("the corners stay clear", 0, Color.alpha(drawnAt(glow, 1, 1)));
        assertNotNull("the delete key on it goes white", delete.getColorFilter());
        assertNull("the same picture outside the room stays white", glowElsewhere.getColorFilter());
        assertNull("a colored picture keeps its look", photo.getColorFilter());
        assertNull("a thin white glyph keeps its look", glyph.getColorFilter());

        root.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals("it stays dark on the next layout", AMOLED, drawnAt(glow, 85, 48));
        assertNotNull(delete.getColorFilter());
    }

    @Test
    @Config(qualifiers = "night")
    public void aRoomTheSwitchLeftLightKeepsItsWhiteBoxes() {
        Settings.DARK_LIVE_PANELS.save(false);
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        View box = filled(panel(root), new ColorDrawable(Color.WHITE));
        LiveCommentBox.paint(root);
        assertEquals(Color.WHITE, solid(box));
    }

    @Test
    public void thePanelsFillIsItsNearestDarkOneOrTheDarkCardsFill() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        assertEquals(LivePanelTheme.DARK_FILL, LiveCommentBox.panelFill(root));
        filled(panel(root), new ColorDrawable(Color.BLACK));
        assertEquals(AMOLED, LiveCommentBox.panelFill(root));
    }

    /** An AMOLED color needn't be a gray: a navy panel gives its boxes navy. */
    @Test
    @Config(qualifiers = "night")
    public void aColoredAmoledFillIsThePanelsFillToo() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout panel = new FrameLayout(root.getContext());
        panel.setBackground(new ColorDrawable(0xFF00264D));
        root.addView(panel);
        View box = filled(panel, new ColorDrawable(Color.WHITE));
        LiveCommentBox.paint(root);
        assertEquals(0xFF00264D, solid(box));
    }

    /** A white fill can share its state with another view's copy, outside the room. */
    @Test
    @Config(qualifiers = "night")
    public void aSharedWhiteFillChangesOnlyInThePanel() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout panel = panel(root);
        ColorDrawable white = new ColorDrawable(Color.WHITE);
        ColorDrawable elsewhere = (ColorDrawable) white.getConstantState().newDrawable();
        filled(panel, white);
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x00FFFFFF, Color.WHITE});
        GradientDrawable fadeElsewhere = (GradientDrawable) fade.getConstantState().newDrawable();
        filled(panel, fade);

        LiveCommentBox.paint(root);

        assertEquals(AMOLED, white.getColor());
        assertEquals(Color.WHITE, elsewhere.getColor());
        assertArrayEquals(new int[]{0x00181825, AMOLED}, fade.getColors());
        assertArrayEquals(new int[]{0x00FFFFFF, Color.WHITE}, fadeElsewhere.getColors());
    }

    /** TikTok's emoji grids are RecyclerView subclasses; the name is what's matched. */
    static final class RecyclerView extends FrameLayout {
        RecyclerView(android.content.Context context) {
            super(context);
        }
    }

    /** A dark emoji in the picker's grid keeps its ink, a dark key on a box made dark is drawn white. */
    @Test
    @Config(qualifiers = "night")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void anEmojiInTheGridKeepsItsInkButADarkKeyOnADarkenedBoxGoesWhite() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout panel = panel(root);
        RecyclerView grid = new RecyclerView(root.getContext());
        panel.addView(grid);
        ImageView blackHeart = icon(grid, 0xFF161823);
        FrameLayout deleteBox = new FrameLayout(root.getContext());
        deleteBox.setBackground(new ColorDrawable(Color.WHITE));
        grid.addView(deleteBox, new FrameLayout.LayoutParams(60, 60));
        ImageView delete = icon(deleteBox, 0xFF161823);
        layOut(root);

        LiveCommentBox.paint(root);
        assertNull("an emoji keeps its look", blackHeart.getColorFilter());
        assertEquals("the delete box", AMOLED, solid(deleteBox));
        assertNotNull("the delete key goes white", delete.getColorFilter());

        // Its box is dark already on the next layout, and the key is still kept white.
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        delete.setImageDrawable(new ColorDrawable(0xFFFE2C55));
        root.getViewTreeObserver().dispatchOnPreDraw();
        delete.setImageDrawable(new ColorDrawable(0xFF161823));
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertNotNull(delete.getColorFilter());
        assertNull(blackHeart.getColorFilter());
    }

    /** The LIVE module's own night grays: the bar and its boxes, and the emoji grid's ground. */
    private static final int NIGHT_BAR = 0xFF121212;
    private static final int NIGHT_GRID = 0xFF1B1B1B;

    private static FrameLayout nightBar(FrameLayout root) {
        FrameLayout bar = new FrameLayout(root.getContext());
        bar.setBackground(new ColorDrawable(NIGHT_BAR));
        root.addView(bar);
        return bar;
    }

    /**
     * #329: with a picked AMOLED color, the bar, the picker's grid and the boxes behind send and
     * delete take it in place of the LIVE module's grays, so none of them is left a different hue.
     * A raised gray, a colored button, a see-through tile and the emoji keep their look.
     */
    @Test
    @Config(qualifiers = "night")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aPickedAmoledColorReplacesThePanelsNightGrays() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout bar = nightBar(root);
        RecyclerView grid = new RecyclerView(root.getContext());
        grid.setBackground(new ColorDrawable(NIGHT_GRID));
        bar.addView(grid);
        FrameLayout cell = new FrameLayout(root.getContext());
        GradientDrawable cellGround = new GradientDrawable();
        cellGround.setCornerRadius(8f);
        cellGround.setColor(NIGHT_GRID);
        cell.setBackground(cellGround);
        grid.addView(cell, new FrameLayout.LayoutParams(60, 60));
        ImageView blackHeart = icon(cell, 0xFF161823);
        View sendBox = filled(bar, new ColorDrawable(Color.WHITE));
        View raised = filled(bar, new ColorDrawable(0xFF4A4A4A));
        View yellow = filled(bar, new ColorDrawable(0xFFFFC400));
        View tile = filled(bar, new ColorDrawable(0x29FFFFFF));
        layOut(root);

        LiveCommentBox.darken(root, AMOLED, new ArrayList<>());

        assertEquals("the bar", AMOLED, solid(bar));
        assertEquals("the emoji grid", AMOLED, solid(grid));
        assertEquals("a cell in it", AMOLED, cellGround.getColor().getDefaultColor());
        assertEquals("the send box", AMOLED, solid(sendBox));
        assertEquals("a raised gray stays", 0xFF4A4A4A, solid(raised));
        assertEquals("a colored button keeps its color", 0xFFFFC400, solid(yellow));
        assertEquals("a see-through tile stays", 0x29FFFFFF, solid(tile));
        assertNull("an emoji on a night gray cell keeps its ink", blackHeart.getColorFilter());
    }

    /** With the default black, or the AMOLED switch off, the grays stay and a white box takes the bar's. */
    @Test
    @Config(qualifiers = "night")
    public void withoutAPickedColorTheNightGraysStay() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout bar = nightBar(root);
        View grid = filled(bar, new ColorDrawable(NIGHT_GRID));
        View sendBox = filled(bar, new ColorDrawable(Color.WHITE));
        ColorDrawable deleteBox = new ColorDrawable(NIGHT_BAR);
        bar.addView(new LiveIconView(root.getContext(), deleteBox));

        LiveCommentBox.darken(root, 0, new ArrayList<>());

        assertEquals(NIGHT_BAR, solid(bar));
        assertEquals(NIGHT_GRID, solid(grid));
        assertEquals(NIGHT_BAR, solid(sendBox));
        assertEquals(NIGHT_BAR, deleteBox.getColor());
    }

    /**
     * TikTok's LIVE icon view keeps its box in a field and draws it inside the icon, not as a
     * background. Its glyph sits in a field of a Drawable subclass's type, which isn't the box.
     */
    static final class LiveIconView extends ImageView {
        private final ColorDrawable glyph = new ColorDrawable(Color.WHITE);
        private Drawable iconBackground;

        LiveIconView(android.content.Context context, Drawable box) {
            super(context);
            iconBackground = box;
            setImageDrawable(glyph);
        }

        LiveIconView(android.content.Context context, Drawable box, Drawable image) {
            super(context);
            iconBackground = box;
            setImageDrawable(image);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // The box first, then the glyph over it.
            if (iconBackground != null) iconBackground.draw(canvas);
            super.onDraw(canvas);
        }
    }

    /** A glyph of one ink in the middle half of its bounds, with clear pixels around it. */
    static final class Glyph extends Drawable {
        private final Paint paint = new Paint();

        Glyph(int color) {
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            canvas.drawRect(bounds.left + bounds.width() / 4f, bounds.top + bounds.height() / 4f,
                    bounds.right - bounds.width() / 4f, bounds.bottom - bounds.height() / 4f, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** TikTok's drawn icon box (X.1O1s on 47.9.2): a rounded rect whose paint takes its color on every draw. */
    static final class DrawnBox extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        DrawnBox(int color) {
            this.color = color;
            setBounds(0, 0, 158, 113);
        }

        @Override
        public void draw(Canvas canvas) {
            paint.setColor(color);
            canvas.drawRoundRect(new RectF(getBounds()), 24f, 24f, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private static int middle(Drawable box) {
        Bitmap drawn = Bitmap.createBitmap(box.getBounds().width(), box.getBounds().height(), Bitmap.Config.ARGB_8888);
        box.draw(new Canvas(drawn));
        return drawn.getPixel(drawn.getWidth() / 2, drawn.getHeight() / 2);
    }

    /**
     * #329 on the S22, second pass: the delete key's box is TikTok's own drawn box, no fill the swap
     * knows, so it's read where it paints and filtered to the picked color. A colored drawn box and
     * one not laid out yet keep their look, and a second pass leaves the color as it is.
     */
    @Test
    @Config(qualifiers = "night")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aPickedAmoledColorReachesTikToksDrawnDeleteBox() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout bar = nightBar(root);
        DrawnBox deleteBox = new DrawnBox(NIGHT_BAR);
        bar.addView(new LiveIconView(root.getContext(), deleteBox));
        DrawnBox pink = new DrawnBox(0xFFFE2C55);
        bar.addView(new LiveIconView(root.getContext(), pink));
        DrawnBox unplaced = new DrawnBox(NIGHT_BAR);
        unplaced.setBounds(0, 0, 0, 0);
        bar.addView(new LiveIconView(root.getContext(), unplaced));
        layOut(root);

        LiveCommentBox.darken(root, AMOLED, new ArrayList<>());
        LiveCommentBox.darken(root, AMOLED, new ArrayList<>());

        assertEquals("the delete key's drawn box", AMOLED, middle(deleteBox));
        assertEquals("a colored drawn box keeps its color", 0xFFFE2C55, middle(pink));
        assertNull("a box with no size yet is left for the next layout", unplaced.paint.getColorFilter());
        unplaced.setBounds(0, 0, 158, 113);
        LiveCommentBox.darken(root, AMOLED, new ArrayList<>());
        assertEquals("and takes the color once it has one", AMOLED, middle(unplaced));
    }

    /**
     * An icon's light box made dark counts as made dark, like a background, so its glyph is drawn
     * light even inside a list. One on a night gray box was light already and is left as it is.
     */
    @Test
    @Config(qualifiers = "night")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void anIconsLightBoxMadeDarkInAListGetsALightGlyph() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout bar = nightBar(root);
        RecyclerView grid = new RecyclerView(root.getContext());
        bar.addView(grid);
        DrawnBox whiteBox = new DrawnBox(Color.WHITE);
        LiveIconView onWhite = new LiveIconView(root.getContext(), whiteBox);
        grid.addView(onWhite);
        LiveIconView onNight = new LiveIconView(root.getContext(), new DrawnBox(NIGHT_BAR));
        grid.addView(onNight);
        layOut(root);

        ArrayList<View> ink = new ArrayList<>();
        LiveCommentBox.darken(root, AMOLED, ink);
        assertEquals("the white box", AMOLED, middle(whiteBox));
        assertTrue("its glyph turns light", ink.contains(onWhite));
        assertFalse("a night gray box's glyph was light already", ink.contains(onNight));

        // The box is dark already on the next pass, and its glyph is still drawn light.
        LiveCommentBox.darken(root, AMOLED, ink);
        assertTrue(ink.contains(onWhite));
    }

    /**
     * The glyph on a box made dark is judged without the box, so it turns light whatever hue the
     * picked color has. Drawn together, a teal box made the icon read as colored art and the dark
     * glyph stayed dark on it.
     */
    @Test
    @Config(qualifiers = "night")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aDarkGlyphOnItsBoxMadeDarkTurnsLightInAnyPickedColor() {
        int teal = 0xFF002B36;
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout bar = nightBar(root);
        LiveIconView delete = new LiveIconView(root.getContext(), new DrawnBox(Color.WHITE), new Glyph(0xFF161823));
        bar.addView(delete, new FrameLayout.LayoutParams(158, 113));
        layOut(root);

        LiveCommentBox.darken(root, teal, new ArrayList<>());

        Bitmap drawn = Bitmap.createBitmap(158, 113, Bitmap.Config.ARGB_8888);
        delete.draw(new Canvas(drawn));
        assertEquals("the box takes the picked color", teal, drawn.getPixel(20, 56));
        assertEquals("and the glyph on it turns light", Color.WHITE, drawn.getPixel(79, 56));
    }

    /**
     * #329 on the S22: the picker's delete key is a LiveIconView whose night gray box is drawn with
     * its glyph, so a picked AMOLED color reaches that box too. A colored icon box keeps its color.
     */
    @Test
    @Config(qualifiers = "night")
    public void aPickedAmoledColorReachesTheDeleteKeysOwnBox() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout bar = nightBar(root);
        GradientDrawable deleteBox = new GradientDrawable();
        deleteBox.setCornerRadius(8f);
        deleteBox.setColor(NIGHT_BAR);
        bar.addView(new LiveIconView(root.getContext(), deleteBox));
        ColorDrawable pink = new ColorDrawable(0xFFFE2C55);
        bar.addView(new LiveIconView(root.getContext(), pink));
        layOut(root);

        LiveCommentBox.darken(root, AMOLED, new ArrayList<>());

        assertEquals("the delete key's box", AMOLED, deleteBox.getColor().getDefaultColor());
        assertEquals("a colored icon box keeps its color", 0xFFFE2C55, pink.getColor());
        assertNull("a plain image has no icon box", LiveCommentBox.iconBox(new ImageView(root.getContext())));
    }
}

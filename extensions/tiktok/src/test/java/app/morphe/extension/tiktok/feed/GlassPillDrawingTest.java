package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Liquid glass look (#218), drawn for real over a gray video frame: the rim is brightest along
 * the top edge, the fill brightens toward the top, the middle is lighter than the plain pill's,
 * and nothing is drawn outside the rounded ends.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GlassPillDrawingTest {
    private static final int GRAY = 0xFF808080;
    private static final int W = 400, H = 100;

    /** The pill reads Pill corners and Glass opacity as it draws. */
    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @After
    public void tearDown() {
        Settings.GLASS_OPACITY.save("standard");
    }

    private static Bitmap drawn(Drawable pill) {
        Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(GRAY);
        pill.setBounds(0, 0, W, H);
        pill.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static int light(int color) {
        return Color.red(color) + Color.green(color) + Color.blue(color);
    }

    @Test
    public void theGlassCatchesTheLightAtTheTopAndStaysInsideItsEnds() {
        Bitmap glass = drawn(new SeeThroughBottomBar.GlassPill(1f));
        int topRim = glass.getPixel(W / 2, 0), bottomRim = glass.getPixel(W / 2, H - 1);
        assertTrue("top rim " + Integer.toHexString(topRim) + " not brighter than bottom "
                + Integer.toHexString(bottomRim), light(topRim) > light(bottomRim) + 30);
        int upper = glass.getPixel(W / 2, 10), lower = glass.getPixel(W / 2, H - 10);
        assertTrue("upper fill " + Integer.toHexString(upper) + " not brighter than lower "
                + Integer.toHexString(lower), light(upper) > light(lower) + 30);
        assertEquals("drew outside the rounded end", GRAY, glass.getPixel(1, 1));
        assertEquals("drew outside the rounded end", GRAY, glass.getPixel(W - 2, H - 2));
    }

    @Test
    public void theGlassIsLighterThanThePlainPill() {
        int glass = drawn(new SeeThroughBottomBar.GlassPill(1f)).getPixel(W / 2, H / 2);
        int plain = drawn(new SeeThroughBottomBar.FloatingBackground(1f).getDrawable()).getPixel(W / 2, H / 2);
        assertTrue("glass " + Integer.toHexString(glass) + " not lighter than the plain pill "
                + Integer.toHexString(plain), light(glass) > light(plain) + 60);
        assertTrue("the glass hid the video", light(glass) < light(0xFFFFFFFF));
    }

    /** Glass opacity: Most see-through lets more of the video through than Frosted. */
    @Test
    public void glassOpacityChangesHowMuchOfTheVideoShows() {
        Settings.GLASS_OPACITY.save("clear");
        int clear = drawn(new SeeThroughBottomBar.GlassPill(1f)).getPixel(W / 2, H / 2);
        Settings.GLASS_OPACITY.save("frosted");
        int frosted = drawn(new SeeThroughBottomBar.GlassPill(1f)).getPixel(W / 2, H / 2);
        assertTrue("clear " + Integer.toHexString(clear) + " not closer to the video than frosted "
                + Integer.toHexString(frosted),
                Math.abs(light(clear) - light(GRAY)) < Math.abs(light(frosted) - light(GRAY)));
    }

    /** A W x H bar of five 80 px tabs, the middle one selected, wearing {@code background}. */
    private static void barWearing(Drawable background) {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.widget.LinearLayout bar = new android.widget.LinearLayout(context);
        for (int i = 0; i < 5; i++) {
            bar.addView(new android.view.View(context), new android.widget.LinearLayout.LayoutParams(80, H));
        }
        bar.measure(android.view.View.MeasureSpec.makeMeasureSpec(W, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(H, android.view.View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, W, H);
        bar.getChildAt(2).setSelected(true);
        bar.setBackground(background);
    }

    /**
     * The lens on TikTok's own bar (#416, #483): TikTok's color everywhere but the thin outline
     * around the selected tab, nothing filled inside it. The line is light on a dark bar and dark
     * on a white bar, where a white one wouldn't show. A bar background that isn't a plain color
     * gets the same.
     */
    @Test
    public void theLensIsAThinOutlineOnTikToksOwnDarkAndWhiteBars() {
        SeeThroughBottomBar.ColorLensBackground dark = new SeeThroughBottomBar.ColorLensBackground(Color.BLACK, false, 1f);
        barWearing(dark);
        Bitmap onDark = drawn(dark);
        assertEquals("TikTok's color didn't draw", Color.BLACK, onDark.getPixel(40, H / 2));
        assertEquals("the lens filled its inside", Color.BLACK, onDark.getPixel(W / 2, H / 2));
        int[] darkLine = outline(onDark, Color.BLACK);
        assertTrue("no outline on the dark bar", darkLine[0] > 0);
        assertTrue("the outline on the dark bar is dim: " + Integer.toHexString(darkLine[1]),
                light(darkLine[1]) > light(Color.BLACK) + 200);
        assertTrue("the outline is thicker than a line: " + darkLine[0], darkLine[0] <= 2);

        SeeThroughBottomBar.ColorLensBackground white = new SeeThroughBottomBar.ColorLensBackground(Color.WHITE, false, 1f);
        barWearing(white);
        Bitmap onWhite = drawn(white);
        assertEquals(Color.WHITE, onWhite.getPixel(40, H / 2));
        assertEquals("the lens filled its inside", Color.WHITE, onWhite.getPixel(W / 2, H / 2));
        int[] whiteLine = outline(onWhite, Color.WHITE);
        assertTrue("the outline on the white bar doesn't show: " + Integer.toHexString(whiteLine[1]),
                whiteLine[0] > 0 && light(whiteLine[1]) < light(Color.WHITE) - 120);

        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(Color.WHITE);
        SeeThroughBottomBar.LensOverBackground over = new SeeThroughBottomBar.LensOverBackground(shape, 1f);
        barWearing(over);
        Bitmap onShape = drawn(over);
        assertEquals(Color.WHITE, onShape.getPixel(40, H / 2));
        assertEquals("the lens filled its inside on a shape", Color.WHITE, onShape.getPixel(W / 2, H / 2));
        assertTrue("the outline on a white shape doesn't show", outline(onShape, Color.WHITE)[0] > 0);
    }

    /**
     * Along the bar's middle row, right of its middle: how many pixels differ from {@code bar},
     * and the one that differs most. The lens's right side crosses that row once.
     */
    private static int[] outline(Bitmap bitmap, int bar) {
        int count = 0, most = bar;
        for (int x = W / 2; x < W; x++) {
            int pixel = bitmap.getPixel(x, H / 2);
            if (pixel == bar) continue;
            count++;
            if (Math.abs(light(pixel) - light(bar)) > Math.abs(light(most) - light(bar))) most = pixel;
        }
        return new int[]{count, most};
    }

    /** The clear bar's lens (#416): the video untouched around it, lighter behind the selected tab. */
    @Test
    public void theLensShowsOnTheClearBar() {
        SeeThroughBottomBar.ColorLensBackground clear =
                new SeeThroughBottomBar.ColorLensBackground(Color.TRANSPARENT, true, 1f);
        barWearing(clear);
        Bitmap bitmap = drawn(clear);
        assertEquals("the clear bar covered the video", GRAY, bitmap.getPixel(40, H / 2));
        assertTrue("no lens on the clear bar", light(bitmap.getPixel(W / 2, H / 2)) > light(GRAY) + 30);
        clear.setAlpha(0);
        assertEquals("a faded bar still drew its lens", GRAY, drawn(clear).getPixel(W / 2, H / 2));
    }

    /**
     * A W x H bar of five tabs labeled in {@code ink}, plus an Inbox badge's white number, wearing
     * {@code background}.
     */
    private static android.widget.LinearLayout labeledBarWearing(Drawable background, int ink) {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.widget.LinearLayout bar = new android.widget.LinearLayout(context);
        for (int i = 0; i < 5; i++) {
            android.widget.TextView tab = new android.widget.TextView(context);
            tab.setText("Tab " + i);
            bar.addView(tab, new android.widget.LinearLayout.LayoutParams(80, H));
        }
        android.widget.TextView badge = new android.widget.TextView(context);
        badge.setText("3");
        bar.addView(badge, new android.widget.LinearLayout.LayoutParams(0, H));
        ink(bar, ink);
        badge.setTextColor(Color.WHITE);
        bar.measure(android.view.View.MeasureSpec.makeMeasureSpec(W, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(H, android.view.View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, W, H);
        bar.setBackground(background);
        return bar;
    }

    /** Gives the bar's five tab labels {@code ink}. */
    private static void ink(android.widget.LinearLayout bar, int ink) {
        for (int i = 0; i < 5; i++) ((android.widget.TextView) bar.getChildAt(i)).setTextColor(ink);
    }

    private static Bitmap drawnOver(int page, Drawable background) {
        Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(page);
        background.setBounds(0, 0, W, H);
        background.draw(new Canvas(bitmap));
        return bitmap;
    }

    /**
     * Home's Community grid (#436): TikTok gives its tabs black labels there, over a white page,
     * and the dark pill under them read as a gray smear behind black icons. Dark labels, dimmed
     * ones too, get the pale pill and white glass with an edge that shows on white; white labels
     * keep the dark ones, and the pill follows the labels as TikTok changes them.
     */
    @Test
    public void thePillGoesPaleUnderTikToksDarkTabs() {
        SeeThroughBottomBar.FloatingBackground plain = new SeeThroughBottomBar.FloatingBackground(1f);
        android.widget.LinearLayout bar = labeledBarWearing(plain, 0xFF161823);
        int onGrid = drawnOver(Color.WHITE, plain).getPixel(W / 2, H / 2);
        assertTrue("the pill under black tabs is " + Integer.toHexString(onGrid),
                light(onGrid) > light(Color.WHITE) - 60);

        // Back on the video feed TikTok's labels go white again.
        ink(bar, Color.WHITE);
        int onFeed = drawnOver(GRAY, plain).getPixel(W / 2, H / 2);
        assertTrue("the pill under white tabs is " + Integer.toHexString(onFeed),
                light(onFeed) < light(GRAY) - 60);

        SeeThroughBottomBar.GlassBackground glass = new SeeThroughBottomBar.GlassBackground(1f);
        labeledBarWearing(glass, 0x80161823);
        Bitmap glassOnGrid = drawnOver(Color.WHITE, glass);
        int middle = glassOnGrid.getPixel(W / 2, H / 2);
        assertTrue("the glass under dimmed black tabs is " + Integer.toHexString(middle),
                light(middle) > light(Color.WHITE) - 60);
        int edge = Color.WHITE;
        for (int y = H - 4; y < H; y++) {
            int pixel = glassOnGrid.getPixel(W / 2, y);
            if (light(pixel) < light(edge)) edge = pixel;
        }
        assertTrue("the glass's edge vanished on white", light(edge) < light(Color.WHITE) - 60);
    }

    @Test
    public void aFadedGlassDrawsNothing() {
        SeeThroughBottomBar.GlassPill pill = new SeeThroughBottomBar.GlassPill(1f);
        pill.setAlpha(0);
        Bitmap bitmap = drawn(pill);
        assertEquals(GRAY, bitmap.getPixel(W / 2, 0));
        assertEquals(GRAY, bitmap.getPixel(W / 2, H / 2));
        assertEquals(0, pill.getAlpha());
    }
}

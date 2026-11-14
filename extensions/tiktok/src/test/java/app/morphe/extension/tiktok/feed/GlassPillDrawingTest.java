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
     * The lens on TikTok's own bar (#416): TikTok's color everywhere but behind the selected
     * tab, a lighter bubble there on a dark bar, and a darker one on a white bar, where white
     * glass wouldn't show. A bar background that isn't a plain color gets the same.
     */
    @Test
    public void theLensShowsOnTikToksOwnDarkAndWhiteBars() {
        SeeThroughBottomBar.ColorLensBackground dark = new SeeThroughBottomBar.ColorLensBackground(Color.BLACK, false, 1f);
        barWearing(dark);
        Bitmap onDark = drawn(dark);
        assertEquals("TikTok's color didn't draw", Color.BLACK, onDark.getPixel(40, H / 2));
        int darkLens = onDark.getPixel(W / 2, H / 2);
        assertTrue("no lens on the dark bar: " + Integer.toHexString(darkLens), light(darkLens) > light(Color.BLACK) + 60);

        SeeThroughBottomBar.ColorLensBackground white = new SeeThroughBottomBar.ColorLensBackground(Color.WHITE, false, 1f);
        barWearing(white);
        Bitmap onWhite = drawn(white);
        assertEquals(Color.WHITE, onWhite.getPixel(40, H / 2));
        int whiteLens = onWhite.getPixel(W / 2, H / 2);
        assertTrue("the lens on the white bar doesn't show: " + Integer.toHexString(whiteLens),
                light(whiteLens) < light(Color.WHITE) - 30);

        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(Color.WHITE);
        SeeThroughBottomBar.LensOverBackground over = new SeeThroughBottomBar.LensOverBackground(shape, 1f);
        barWearing(over);
        Bitmap onShape = drawn(over);
        assertEquals(Color.WHITE, onShape.getPixel(40, H / 2));
        assertTrue("the lens on a white shape doesn't show",
                light(onShape.getPixel(W / 2, H / 2)) < light(Color.WHITE) - 30);
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

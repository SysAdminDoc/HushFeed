package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Glass Stories pill (#409): the Following tab's Stories pill trades TikTok's flat gray for the
 * bar's glass with round ends and the video behind it blurred, under a switch of its own, and
 * gets TikTok's own background back once the switch is off or Hushfeed is paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StoriesPillGlassTest {
    private static final int WIDTH = 218;
    private static final int HEIGHT = 72;
    private static final int TOP = 384;

    /** A video under the whole window that records what it is asked to read. */
    private static final class Video implements GlassVideoBlur.Source {
        final Rect bounds = new Rect(0, 0, 1080, 2400);
        final List<Rect> reads = new ArrayList<>();

        @Override public Rect bounds() {
            return bounds;
        }

        @Override public int bufferWidth() {
            return 1080;
        }

        @Override public int bufferHeight() {
            return 2400;
        }

        @Override public void read(Rect from, Bitmap into, GlassVideoBlur.Done done) {
            reads.add(new Rect(from));
            done.done(true);
        }

        @Override public boolean current() {
            return true;
        }
    }

    /** A pill whose window focus the test sets, since Robolectric never gives a window focus. */
    private static final class Pill extends FrameLayout {
        Pill(Activity activity) {
            super(activity);
        }

        @Override public boolean hasWindowFocus() {
            return true;
        }
    }

    private GlassVideoBlur.Finder realFinder;
    private Video video;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        realFinder = GlassVideoBlur.finder;
        video = new Video();
        GlassVideoBlur.finder = bar -> video;
    }

    @After
    public void tearDown() {
        GlassVideoBlur.finder = realFinder;
        // A pill an earlier test left in glass would read as held in the next one.
        StoriesPillGlass.ORIGINALS.clear();
        PausedProcess.set(false);
        Settings.GLASS_STORIES_PILL.save(false);
        Settings.GLASS_VIDEO_BLUR.save(false);
    }

    @Test
    public void theSwitchStartsOffNeedsNoRestartAndPauseTurnsItOff() {
        assertEquals("glass_stories_pill", Settings.GLASS_STORIES_PILL.key);
        assertFalse(Settings.GLASS_STORIES_PILL.defaultValue);
        assertFalse("it is read on each layout", Settings.GLASS_STORIES_PILL.rebootApp);
        assertFalse(StoriesPillGlass.wanted());
        Settings.GLASS_STORIES_PILL.save(true);
        assertTrue(StoriesPillGlass.wanted());
        PausedProcess.set(true);
        assertFalse("Pause Hushfeed turns it off", StoriesPillGlass.wanted());
    }

    @Test
    public void thePillGetsGlassAndThenTikToksOwnBackgroundBack() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View pill = pill(controller.get());
            Drawable tiktoks = pill.getBackground();

            StoriesPillGlass.apply(pill, true);
            assertTrue(pill.getBackground() instanceof StoriesPillGlass.Glass);
            assertTrue(StoriesPillGlass.holdsAny());
            Drawable glass = pill.getBackground();
            StoriesPillGlass.apply(pill, true);
            assertSame("a pass that finds the glass leaves it", glass, pill.getBackground());

            StoriesPillGlass.apply(pill, false);
            assertSame(tiktoks, pill.getBackground());
            assertFalse(StoriesPillGlass.holdsAny());
        }
    }

    @Test
    public void aBackgroundTikTokSetSinceIsLeftAlone() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View pill = pill(controller.get());
            StoriesPillGlass.apply(pill, true);
            ColorDrawable rebound = new ColorDrawable(Color.RED);
            pill.setBackground(rebound);
            StoriesPillGlass.apply(pill, false);
            assertSame(rebound, pill.getBackground());
            assertFalse(StoriesPillGlass.holdsAny());
        }
    }

    @Test
    public void aPillWithNoBackgroundKeepsTikToksLook() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View pill = pill(controller.get());
            pill.setBackground(null);
            StoriesPillGlass.apply(pill, true);
            assertNull(pill.getBackground());
            assertFalse(StoriesPillGlass.holdsAny());
        }
    }

    @Test
    public void theGlassHasRoundEnds() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View pill = pill(controller.get());
            StoriesPillGlass.apply(pill, true);
            Drawable glass = pill.getBackground();
            glass.setBounds(0, 0, WIDTH, HEIGHT);
            Bitmap drawn = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            glass.draw(new Canvas(drawn));
            // The bar's standard 12dp corner would cover this pixel; a half-circle end doesn't.
            assertEquals("the corner is outside a half-circle end", 0, Color.alpha(drawn.getPixel(6, 6)));
            assertTrue("the middle is glass", Color.alpha(drawn.getPixel(WIDTH / 2, HEIGHT / 2)) > 0);
            assertTrue("the end's middle is glass", Color.alpha(drawn.getPixel(4, HEIGHT / 2)) > 0);
        }
    }

    @Test
    public void itBlursTheVideoBehindItUnderItsOwnSwitch() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View pill = pill(controller.get());
            Settings.GLASS_STORIES_PILL.save(true);
            assertFalse("the bar's blur switch stays off", Settings.GLASS_VIDEO_BLUR.get());
            StoriesPillGlass.apply(pill, true);
            StoriesPillGlass.Glass glass = (StoriesPillGlass.Glass) pill.getBackground();
            assertNotNull(glass.blur);
            assertTrue(glass.blur.on());
            glass.setBounds(0, 0, WIDTH, HEIGHT);
            glass.draw(new Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)));
            assertTrue("drawing starts the loop", glass.blur.sampling());
            glass.blur.step(1000);
            assertEquals(1, video.reads.size());
            int[] at = new int[2];
            pill.getLocationInWindow(at);
            assertEquals("the pill sits where it was laid out", TOP, at[1] - rootTop(pill));
            Rect behind = new Rect(at[0], at[1], at[0] + WIDTH, at[1] + HEIGHT);
            assertEquals("the strip right behind the pill",
                    GlassVideoBlur.sourceRect(behind, video.bounds, 1080, 2400), video.reads.get(0));
            assertTrue(glass.blur.hasFrame());

            Settings.GLASS_STORIES_PILL.save(false);
            assertFalse(glass.blur.on());
            glass.blur.step(1100);
            assertFalse("the loop stops with the switch", glass.blur.sampling());
            assertEquals(1, video.reads.size());
        }
    }

    @Test
    @Config(sdk = 28)
    public void belowAndroid12ItIsThePlainGlass() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View pill = pill(controller.get());
            Settings.GLASS_STORIES_PILL.save(true);
            StoriesPillGlass.apply(pill, true);
            assertNull(((StoriesPillGlass.Glass) pill.getBackground()).blur);
        }
    }

    /** Where the pill's parent starts in the window. */
    private static int rootTop(View pill) {
        int[] at = new int[2];
        ((View) pill.getParent()).getLocationInWindow(at);
        return at[1];
    }

    /** A pill with TikTok's 15% black, laid out under the tabs of a 1080 by 2400 window. */
    private static View pill(Activity activity) {
        FrameLayout root = new FrameLayout(activity);
        FrameLayout pill = new Pill(activity);
        GradientDrawable tiktoks = new GradientDrawable();
        tiktoks.setColor(0x26000000);
        tiktoks.setCornerRadius(HEIGHT / 2f);
        pill.setBackground(tiktoks);
        FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(WIDTH, HEIGHT);
        place.topMargin = TOP;
        root.addView(pill, place);
        activity.setContentView(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2400);
        return pill;
    }
}

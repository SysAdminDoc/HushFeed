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
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.TextureView;
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
 * Blur the video behind the bar (#231): the switch and where it sits, the crop and scale of the
 * strip read back from the video, the fallback to the plain glass when there is nothing to read,
 * and the sampling loop starting and stopping. The blur itself is drawn by the GPU and read by
 * PixelCopy, which Robolectric has neither of, so those are checked on the phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class GlassVideoBlurTest {
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 150;

    /** A video that records what it is asked to read. */
    private static final class Video implements GlassVideoBlur.Source {
        final Rect bounds;
        final List<Rect> reads = new ArrayList<>();
        boolean answer = true;
        /** False once a swipe has put another video on screen. */
        boolean current = true;

        @Override public boolean current() {
            return current;
        }

        Video(Rect bounds) {
            this.bounds = bounds;
        }

        @Override public Rect bounds() {
            return bounds;
        }

        @Override public int bufferWidth() {
            return 576;
        }

        @Override public int bufferHeight() {
            return 1280;
        }

        @Override public void read(Rect from, Bitmap into, GlassVideoBlur.Done done) {
            reads.add(new Rect(from));
            done.done(answer);
        }
    }

    private GlassVideoBlur.Finder realFinder;
    private int finds;
    private Video video;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        realFinder = GlassVideoBlur.finder;
        finds = 0;
        video = new Video(new Rect(0, 0, WIDTH, 2400));
        GlassVideoBlur.finder = bar -> {
            finds++;
            return video;
        };
    }

    @After
    public void tearDown() {
        GlassVideoBlur.finder = realFinder;
        PausedProcess.set(false);
        Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
        Settings.FLOATING_BOTTOM_BAR.save(false);
        Settings.LIQUID_GLASS_BOTTOM_BAR.save(false);
        Settings.GLASS_VIDEO_BLUR.save(false);
    }

    @Test
    public void theSwitchSitsUnderLiquidGlassAndStartsOff() {
        assertEquals("glass_video_blur", Settings.GLASS_VIDEO_BLUR.key);
        assertFalse(Settings.GLASS_VIDEO_BLUR.defaultValue);
        assertFalse("it is read on each layout, so no restart", Settings.GLASS_VIDEO_BLUR.rebootApp);
        assertFalse("off to begin with", GlassVideoBlur.wanted());
        Settings.GLASS_VIDEO_BLUR.save(true);
        assertTrue(GlassVideoBlur.wanted());
        PausedProcess.set(true);
        assertFalse("Pause Hushfeed turns it off", GlassVideoBlur.wanted());
    }

    @Test
    @Config(sdk = 28)
    public void belowAndroid12TheSwitchDoesNothing() {
        Settings.GLASS_VIDEO_BLUR.save(true);
        assertFalse(GlassVideoBlur.supported());
        assertFalse(GlassVideoBlur.wanted());
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View bar = bar(controller.get());
            SeeThroughBottomBar.setBackgroundClear(bar, true, true, true, false, true);
            SeeThroughBottomBar.GlassBackground glass = (SeeThroughBottomBar.GlassBackground) bar.getBackground();
            assertNull("the plain glass, with the switch on", glass.blur);
        }
    }

    /** A landscape video shows fitted across with black under it, and a pill over that black reads nothing. */
    @Test
    public void aPillUnderALandscapeVideoReadsNothing() {
        Rect screen = new Rect(0, 0, 1080, 2400);
        assertEquals(new Rect(0, 896, 1080, 1504), GlassVideoBlur.picture(screen, 1920, 1080));
        assertEquals("a portrait video fills the view", screen, GlassVideoBlur.picture(screen, 576, 1280));
        assertNull(GlassVideoBlur.sourceRect(new Rect(54, 2250, 1026, 2380), screen, 1920, 1080));
        assertNotNull("the same pill over a portrait video",
                GlassVideoBlur.sourceRect(new Rect(54, 2250, 1026, 2380), screen, 576, 1280));
    }

    /**
     * A swipe puts another video on screen, often under the same SurfaceView, while the last
     * one keeps its final frame. The blur drops the old picture at the next read and reads the
     * new video, rather than blurring the video before into the pill until the next search.
     */
    @Test
    public void afterASwipeTheBlurLetsGoOfTheVideoBefore() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View bar = bar(controller.get());
            Settings.GLASS_VIDEO_BLUR.save(true);
            SeeThroughBottomBar.setBackgroundClear(bar, true, true, true, false, true);
            SeeThroughBottomBar.GlassBackground glass = (SeeThroughBottomBar.GlassBackground) bar.getBackground();
            glass.setBounds(0, 0, WIDTH, HEIGHT);
            glass.draw(new Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)));
            glass.blur.step(1000);
            assertTrue(glass.blur.hasFrame());
            Video before = video;

            Video next = new Video(new Rect(0, 0, WIDTH, 2400));
            next.answer = false;
            video = next;
            before.current = false;
            glass.blur.step(1000 + GlassVideoBlur.SAMPLE_MS);
            assertFalse("the video before stayed in the pill", glass.blur.hasFrame());
            assertEquals("the video before was read again", 1, before.reads.size());
            assertEquals("the new video wasn't read", 1, next.reads.size());
        }
    }

    @Test
    public void theBlurSwapsInAndOutWithTheSwitchAndGivesTheOriginalBack() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View bar = bar(controller.get());
            Drawable original = bar.getBackground();

            SeeThroughBottomBar.setBackgroundClear(bar, true, true, true, false, false);
            SeeThroughBottomBar.GlassBackground plain = (SeeThroughBottomBar.GlassBackground) bar.getBackground();
            assertNull("switch off is today's glass exactly", plain.blur);
            assertFalse(plain instanceof SeeThroughBottomBar.GlassLensBackground);

            SeeThroughBottomBar.setBackgroundClear(bar, true, true, true, false, true);
            SeeThroughBottomBar.GlassBackground blurred = (SeeThroughBottomBar.GlassBackground) bar.getBackground();
            assertNotNull(blurred.blur);
            SeeThroughBottomBar.setBackgroundClear(bar, true, true, true, false, true);
            assertSame("a pass swapped the blurred glass for a new one", blurred, bar.getBackground());

            SeeThroughBottomBar.setBackgroundClear(bar, true, true, true, true, true);
            SeeThroughBottomBar.GlassBackground lens = (SeeThroughBottomBar.GlassBackground) bar.getBackground();
            assertTrue(lens instanceof SeeThroughBottomBar.GlassLensBackground);
            assertNotNull("the lens glass blurs too", lens.blur);

            SeeThroughBottomBar.setBackgroundClear(bar, true, true, true, false, false);
            assertNull(((SeeThroughBottomBar.GlassBackground) bar.getBackground()).blur);

            SeeThroughBottomBar.setBackgroundClear(bar, true, true, false, false, true);
            assertFalse("no glass, no blur", bar.getBackground() instanceof SeeThroughBottomBar.GlassBackground);

            SeeThroughBottomBar.setBackgroundClear(bar, false, false, false, false, false);
            assertSame(original, bar.getBackground());
        }
    }

    @Test
    public void theStripIsCroppedAndScaledIntoTheVideosBuffer() {
        Rect video = new Rect(0, 0, 1080, 2400);
        Rect pill = new Rect(12, 2250, 1068, 2394);
        // The 576 by 1280 buffer is a bit more than half the video each way.
        assertEquals(new Rect(6, 1200, 570, 1277), GlassVideoBlur.sourceRect(pill, video, 576, 1280));

        Rect moved = new Rect(100, 100, 1180, 2500);
        assertEquals("measured from the video's own corner",
                new Rect(6, 1200, 570, 1277), GlassVideoBlur.sourceRect(new Rect(112, 2350, 1168, 2494), moved, 576, 1280));

        assertNull("a pill hanging half off the video",
                GlassVideoBlur.sourceRect(new Rect(12, 2300, 1068, 2500), video, 576, 1280));
        assertEquals("a pill a few pixels past the edge reads the part over the video",
                new Rect(6, 1200, 570, 1280), GlassVideoBlur.sourceRect(new Rect(12, 2250, 1068, 2404), video, 576, 1280));
        assertNull("beside the video", GlassVideoBlur.sourceRect(new Rect(12, 2500, 1068, 2600), video, 576, 1280));
        assertNull(GlassVideoBlur.sourceRect(new Rect(), video, 576, 1280));
        assertNull(GlassVideoBlur.sourceRect(pill, new Rect(), 576, 1280));
        assertNull("no buffer yet", GlassVideoBlur.sourceRect(pill, video, 0, 1280));
        assertNull(GlassVideoBlur.sourceRect(null, video, 576, 1280));
    }

    /**
     * The S22 on 47.5.3: TikTok's player view is 1107 by 1968 at y 74, scaled about 1.07 from its
     * middle, so it draws down to y 2179 behind the bar at 2043. Its corner plus its unscaled size
     * stopped at 2042 and the blur never read a frame.
     */
    @Test
    public void theVideoIsMeasuredAsDrawnWithTikToksScale() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            FrameLayout cell = new FrameLayout(activity);
            View player = new View(activity);
            cell.addView(player, new FrameLayout.LayoutParams(1107, 1968));
            activity.setContentView(cell, new FrameLayout.LayoutParams(1080, 2181));
            cell.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2181, View.MeasureSpec.EXACTLY));
            cell.layout(0, 0, 1080, 2181);
            player.layout(-13, 144, 1094, 2112);
            player.setPivotX(553.5f);
            player.setPivotY(984f);
            player.setScaleX(1.07f);
            player.setScaleY(1.07f);

            Rect drawn = GlassVideoBlur.boundsInWindow(player);
            Rect root = GlassVideoBlur.boundsInWindow(cell.getRootView());
            Rect cellInWindow = GlassVideoBlur.boundsInWindow(cell);
            assertEquals("the root is the window", 0, root.left);
            drawn.offset(-cellInWindow.left, -cellInWindow.top);
            assertEquals(new Rect(-52, 75, 1133, 2181), drawn);
            assertTrue("the bar's pill sits over it",
                    GlassVideoBlur.sourceRect(new Rect(40, 2050, 1040, 2165), drawn, 1080, 1920) != null);
            assertNull("unscaled it looked off the video",
                    GlassVideoBlur.sourceRect(new Rect(40, 2050, 1040, 2165), new Rect(-13, 144, 1094, 2112), 1080, 1920));

            cell.scrollTo(0, 100);
            Rect scrolled = GlassVideoBlur.boundsInWindow(player);
            scrolled.offset(-cellInWindow.left, -cellInWindow.top);
            assertEquals("a parent's scroll moves it", new Rect(-52, -25, 1133, 2081), scrolled);
        }
    }

    @Test
    public void theSampleIsAnEighthOfThePillAndNeverEmpty() {
        assertEquals(132, GlassVideoBlur.sampleSize(1056, 144)[0]);
        assertEquals(18, GlassVideoBlur.sampleSize(1056, 144)[1]);
        assertEquals(1, GlassVideoBlur.sampleSize(5, 3)[0]);
        assertEquals(1, GlassVideoBlur.sampleSize(5, 3)[1]);
    }

    @Test
    public void nothingIsSampledWhileTheSwitchIsOff() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View bar = bar(controller.get());
            SeeThroughBottomBar.setBackgroundClear(bar, true, true, true, false, true);
            SeeThroughBottomBar.GlassBackground glass = (SeeThroughBottomBar.GlassBackground) bar.getBackground();
            glass.blur.start(bar);
            assertFalse(glass.blur.sampling());
            glass.draw(new Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)));
            assertFalse("drawing started the loop with the switch off", glass.blur.sampling());
            glass.blur.step(1000);
            assertEquals(0, finds);
            assertEquals(0, glass.blur.reads);
            assertFalse(glass.blur.hasFrame());
        }
    }

    @Test
    public void aDrawStartsTheLoopAndItReadsTheStripBehindThePill() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View bar = bar(controller.get());
            Settings.GLASS_VIDEO_BLUR.save(true);
            SeeThroughBottomBar.setBackgroundClear(bar, true, true, true, false, true);
            SeeThroughBottomBar.GlassBackground glass = (SeeThroughBottomBar.GlassBackground) bar.getBackground();
            glass.setBounds(0, 0, WIDTH, HEIGHT);
            glass.draw(new Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)));
            assertTrue(glass.blur.sampling());

            glass.blur.step(1000);
            assertEquals(1, finds);
            assertEquals(1, glass.blur.reads);
            assertEquals(1, video.reads.size());
            assertTrue(glass.blur.hasFrame());
            Rect pill = new Rect(glass.getDrawable().getBounds());
            int[] at = new int[2];
            bar.getLocationInWindow(at);
            pill.offset(at[0], at[1]);
            Rect expected = GlassVideoBlur.sourceRect(pill, video.bounds, 576, 1280);
            assertNotNull(expected);
            assertEquals(expected, video.reads.get(0));

            glass.blur.step(1020);
            assertEquals("a read every 50 milliseconds, not every pass", 1, video.reads.size());
            glass.blur.step(1060);
            assertEquals(2, video.reads.size());
            assertEquals("the video was found once", 1, finds);
        }
    }

    @Test
    public void aStillPictureIsReadFarLessOften() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View bar = bar(controller.get());
            Settings.GLASS_VIDEO_BLUR.save(true);
            SeeThroughBottomBar.setBackgroundClear(bar, true, true, true, false, true);
            SeeThroughBottomBar.GlassBackground glass = (SeeThroughBottomBar.GlassBackground) bar.getBackground();
            glass.setBounds(0, 0, WIDTH, HEIGHT);
            glass.draw(new Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)));
            long now = 1000;
            for (int i = 0; i < GlassVideoBlur.STILL_SAMPLES + 1; i++) {
                glass.blur.step(now);
                now += GlassVideoBlur.SAMPLE_MS;
            }
            int reads = video.reads.size();
            glass.blur.step(now);
            assertEquals("the picture has stopped, so the next read waits", reads, video.reads.size());
            glass.blur.step(now + GlassVideoBlur.STILL_MS);
            assertEquals(reads + 1, video.reads.size());
        }
    }

    @Test
    public void withNothingToReadTheGlassStaysPlainAndTheLoopKeepsLooking() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View bar = bar(controller.get());
            Settings.GLASS_VIDEO_BLUR.save(true);
            GlassVideoBlur.finder = view -> {
                finds++;
                return null;
            };
            SeeThroughBottomBar.setBackgroundClear(bar, true, true, true, false, true);
            SeeThroughBottomBar.GlassBackground glass = (SeeThroughBottomBar.GlassBackground) bar.getBackground();
            glass.setBounds(0, 0, WIDTH, HEIGHT);
            glass.draw(new Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)));
            glass.blur.step(1000);
            assertEquals(1, finds);
            assertFalse("no surface found, no picture", glass.blur.hasFrame());
            glass.draw(new Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)));

            // A video the pill is not over reads as nothing too.
            GlassVideoBlur.finder = view -> new Video(new Rect(0, 1000, WIDTH, 2400));
            glass.blur.step(3000);
            assertFalse(glass.blur.hasFrame());
        }
    }

    @Test
    public void readsThatKeepFailingGiveThePillBackToThePlainGlassForAWhile() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View bar = bar(controller.get());
            Settings.GLASS_VIDEO_BLUR.save(true);
            video.answer = false;
            SeeThroughBottomBar.setBackgroundClear(bar, true, true, true, false, true);
            SeeThroughBottomBar.GlassBackground glass = (SeeThroughBottomBar.GlassBackground) bar.getBackground();
            glass.setBounds(0, 0, WIDTH, HEIGHT);
            glass.draw(new Canvas(Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)));
            long now = 1000;
            for (int i = 0; i < GlassVideoBlur.MAX_FAILURES; i++) {
                glass.blur.step(now);
                now += GlassVideoBlur.SAMPLE_MS;
            }
            int reads = video.reads.size();
            assertEquals(GlassVideoBlur.MAX_FAILURES, reads);
            assertFalse(glass.blur.hasFrame());
            glass.blur.step(now);
            assertEquals("waiting before the next try", reads, video.reads.size());
            video.answer = true;
            glass.blur.step(now + GlassVideoBlur.RETRY_MS);
            assertEquals(reads + 1, video.reads.size());
            assertTrue(glass.blur.hasFrame());
        }
    }

    @Test
    public void theLoopStopsWhenTheBarIsHiddenTheSwitchGoesOffOrAnotherBackgroundTakesOver() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View bar = bar(controller.get());
            Settings.GLASS_VIDEO_BLUR.save(true);
            SeeThroughBottomBar.setBackgroundClear(bar, true, true, true, false, true);
            SeeThroughBottomBar.GlassBackground glass = (SeeThroughBottomBar.GlassBackground) bar.getBackground();
            glass.setBounds(0, 0, WIDTH, HEIGHT);

            glass.blur.start(bar);
            bar.setVisibility(View.GONE);
            glass.blur.step(1000);
            assertFalse("a hidden bar is not sampled", glass.blur.sampling());
            assertEquals(0, video.reads.size());

            bar.setVisibility(View.VISIBLE);
            glass.blur.start(bar);
            assertTrue(glass.blur.sampling());
            Settings.GLASS_VIDEO_BLUR.save(false);
            glass.blur.step(2000);
            assertFalse("switch off", glass.blur.sampling());
            assertEquals(0, video.reads.size());

            Settings.GLASS_VIDEO_BLUR.save(true);
            glass.blur.start(bar);
            PausedProcess.set(true);
            glass.blur.step(3000);
            assertFalse("Hushfeed paused", glass.blur.sampling());
            PausedProcess.set(false);

            glass.blur.start(bar);
            ((Bar) bar).focused = false;
            glass.blur.step(3500);
            assertFalse("the window lost focus", glass.blur.sampling());
            ((Bar) bar).focused = true;

            glass.blur.start(bar);
            bar.setBackground(new ColorDrawable(Color.BLACK));
            glass.blur.step(4000);
            assertFalse("TikTok's own background came back", glass.blur.sampling());
            assertEquals(0, video.reads.size());
        }
    }

    /**
     * A LIVE preview in For You plays in a TextureView, which PixelCopy can't read (PR #259). With
     * no SurfaceView on screen the blur reads it through its own copy, an eighth of its size, and
     * cuts the pill's strip from that, the sample's own size so nothing is stretched.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aTextureViewPlayerIsReadWhenNoSurfaceViewIsThere() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            Player player = new Player(activity);
            root.addView(player, new FrameLayout.LayoutParams(WIDTH, 2400));
            FrameLayout bar = new Bar(activity);
            root.addView(bar, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
            activity.setContentView(root);
            root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, WIDTH, 2400);

            GlassVideoBlur.Source source = GlassVideoBlur.findSource(bar);
            assertNotNull(source);
            Rect video = source.bounds();
            assertEquals(GlassVideoBlur.boundsInWindow(player), video);
            assertEquals(WIDTH / GlassVideoBlur.SAMPLE_DIVISOR, source.bufferWidth());
            assertEquals(2400 / GlassVideoBlur.SAMPLE_DIVISOR, source.bufferHeight());

            Rect pill = new Rect(54, video.top + 2250, 1026, video.top + 2380);
            Rect from = GlassVideoBlur.sourceRect(pill, video, source.bufferWidth(), source.bufferHeight());
            int[] size = GlassVideoBlur.sampleSize(pill.width(), pill.height());
            assertEquals(size[0], from.width(), 1);
            assertEquals(size[1], from.height(), 1);
            Bitmap sample = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            boolean[] copied = {false};
            source.read(from, sample, done -> copied[0] = done);
            assertTrue(copied[0]);
            assertEquals("the bottom of the picture, where the pill sits",
                    Color.BLUE, sample.getPixel(size[0] / 2, size[1] / 2));
            assertTrue(source.current());

            player.available = false;
            assertFalse("the player let its picture go", source.current());
            source.read(from, sample, done -> copied[0] = done);
            assertFalse(copied[0]);
            assertNull("nothing to read, so the plain glass stays", GlassVideoBlur.findSource(bar));
        }
    }

    /** A TextureView player whose picture the test paints: red, with a blue band along its bottom tenth. */
    private static final class Player extends TextureView {
        boolean available = true;

        Player(Activity activity) {
            super(activity);
        }

        @Override public boolean isAvailable() {
            return available;
        }

        @Override public Bitmap getBitmap(Bitmap bitmap) {
            bitmap.eraseColor(Color.RED);
            Paint blue = new Paint();
            blue.setColor(Color.BLUE);
            new Canvas(bitmap).drawRect(0, bitmap.getHeight() * 0.9f, bitmap.getWidth(), bitmap.getHeight(), blue);
            return bitmap;
        }
    }

    /** A bar whose window focus the test sets, since Robolectric never gives a window focus. */
    private static final class Bar extends FrameLayout {
        boolean focused = true;

        Bar(Activity activity) {
            super(activity);
        }

        @Override public boolean hasWindowFocus() {
            return focused;
        }
    }

    /** A bar with a dark background of TikTok's, laid out along the bottom of a window. */
    private static View bar(Activity activity) {
        FrameLayout root = new FrameLayout(activity);
        FrameLayout bar = new Bar(activity);
        bar.setBackground(new ColorDrawable(Color.BLACK));
        root.addView(bar, new FrameLayout.LayoutParams(WIDTH, HEIGHT));
        activity.setContentView(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, WIDTH, 2400);
        return bar;
    }
}

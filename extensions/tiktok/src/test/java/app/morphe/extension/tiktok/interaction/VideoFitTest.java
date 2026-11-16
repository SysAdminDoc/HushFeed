package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.interaction.narrowed.NarrowedResult;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.PlaybackPreferenceCategory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** What size a video is laid out at once the whole of it has to be on screen. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideoFitTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    /**
     * Stands in for VideoAdaptionResult, whose size getters kept their names. It is a data
     * class, so its equality and hash are built from the size, and the patch writes the fitted
     * width into it from inside the class before the height is asked for; both are copied here
     * because the second is what a map keyed by the result cannot survive.
     */
    public static final class Result {
        int width, height;
        final Float translateX, translateY;
        final Object operator;
        Result(int width, int height) { this(width, height, null, null, null); }
        Result(int width, int height, Float translateX, Float translateY, Object operator) {
            this.width = width;
            this.height = height;
            this.translateX = translateX;
            this.translateY = translateY;
            this.operator = operator;
        }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public Float getTranslateX() { return translateX; }
        public Float getTranslateY() { return translateY; }
        public Object getResultOperator() { return operator; }
        /** The copy a Kotlin data class generates, which is how a fitted result is remade. */
        public Result copy(int width, int height, Float translateX, Float translateY, Object operator) {
            return new Result(width, height, translateX, translateY, operator);
        }
        @Override public boolean equals(Object other) {
            return other instanceof Result && ((Result) other).width == width
                    && ((Result) other).height == height;
        }
        @Override public int hashCode() { return width * 31 + height; }
    }

    /** A result of a shape we cannot read. */
    public static final class Shapeless {
    }

    /** A result whose size can be read, with no copy to remake it through. */
    public static final class Uncopyable {
        int widthReads;
        int heightReads;
        public int getWidth() { widthReads++; return 1440; }
        public int getHeight() { heightReads++; return 2560; }
    }

    /** Stands in for Fresco's scale types, whose names TikTok's enum kept. */
    public enum Scale { CENTER_CROP, FIT_CENTER, FIT_XY }

    /** Stands in for TikTok's cover image view, recording each scale type it is given. */
    public static final class CoverView extends android.widget.ImageView {
        final java.util.List<Scale> scales = new java.util.ArrayList<>();
        CoverView(Context context) { super(context); }
        public void setActualImageScaleType(Scale scale) { scales.add(scale); }
    }

    /** Records whether changing gravity is handed back to Android as a layout change. */
    public static final class LayoutTrackingView extends View {
        int layoutWrites;
        LayoutTrackingView(Context context) { super(context); }
        @Override public void setLayoutParams(ViewGroup.LayoutParams params) {
            layoutWrites++;
            super.setLayoutParams(params);
        }
    }

    @Test public void theWholeVideoLandsInsideTheWindowWhicheverWayItOverflows() {
        // A 9:16 video cropped to fill a 4:3 window: 1440x2560 over 1200x900. Fitted, the
        // height is what touches and the width comes down with it.
        assertEquals(506, VideoFit.fitWidth(1440, 2560, 1200, 900));
        assertEquals(900, VideoFit.fitHeight(1440, 2560, 1200, 900));

        // The other way round, a wide video on a tall window: the width touches.
        assertEquals(1080, VideoFit.fitWidth(1920, 1080, 1080, 2400));
        assertEquals(608, VideoFit.fitHeight(1920, 1080, 1080, 2400));

        // Exactly the window's shape: both sides touch and nothing is added.
        assertEquals(1080, VideoFit.fitWidth(2160, 4800, 1080, 2400));
        assertEquals(2400, VideoFit.fitHeight(2160, 4800, 1080, 2400));

        // A sliver still gets a pixel rather than nothing at all.
        assertEquals(1, VideoFit.fitWidth(1, 100000, 1080, 2400));
        assertEquals(2400, VideoFit.fitHeight(1, 100000, 1080, 2400));
        assertEquals(1080, VideoFit.fitWidth(100000, 1, 1080, 2400));
        assertEquals(1, VideoFit.fitHeight(100000, 1, 1080, 2400));
    }

    @Test public void fillingTakesTheLargerScaleAndCoversTheWindow() {
        // A 9:16 video TikTok laid out at the width of a 20:9 phone, leaving a strip below.
        assertEquals(1350, VideoFit.fillWidth(1080, 1920, 1080, 2400));
        assertEquals(2400, VideoFit.fillHeight(1080, 1920, 1080, 2400));
        // A wide video on a tall window: the height touches and most of the width hangs over.
        assertEquals(4267, VideoFit.fillWidth(1920, 1080, 1080, 2400));
        assertEquals(2400, VideoFit.fillHeight(1920, 1080, 1080, 2400));
        // A tall video on a squarer window: the width touches.
        assertEquals(1200, VideoFit.fillWidth(1440, 2560, 1200, 900));
        assertEquals(2133, VideoFit.fillHeight(1440, 2560, 1200, 900));
        // Exactly the window's shape: nothing is added.
        assertEquals(1080, VideoFit.fillWidth(2160, 4800, 1080, 2400));
        assertEquals(2400, VideoFit.fillHeight(2160, 4800, 1080, 2400));

        // Fit wants a video that overflows; fill wants one that leaves a gap; each leaves the rest.
        assertTrue(VideoFit.wants(VideoFit.Mode.FIT, 1440, 2560, 1200, 900));
        assertFalse(VideoFit.wants(VideoFit.Mode.FIT, 1080, 1920, 1080, 2400));
        assertTrue(VideoFit.wants(VideoFit.Mode.FILL, 1080, 1920, 1080, 2400));
        assertFalse(VideoFit.wants(VideoFit.Mode.FILL, 1440, 2560, 1200, 900));
        assertFalse(VideoFit.wants(VideoFit.Mode.FILL, 1080, 2400, 1080, 2400));
        assertFalse(VideoFit.wants(VideoFit.Mode.LEAVE_ALONE, 1080, 1920, 1080, 2400));

        assertEquals(new float[]{-135f, 0f}[0], VideoFit.offsets(1350, 2400, 1080, 2400)[0], 0f);
        assertEquals(0f, VideoFit.offsets(1350, 2400, 1080, 2400)[1], 0f);
    }

    @Test public void theFillSwitchCropsAVideoThatLeavesAStripAndFitWinsWhenBothAreOn() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            FrameLayout container = new FrameLayout(activity);
            View video = new View(activity);
            container.addView(video);
            container.layout(0, 0, 1080, 2400);
            video.setLayoutParams(new FrameLayout.LayoutParams(1080, 1920));
            Object operator = new Object();
            Result strip = new Result(1080, 1920, 0f, 0f, operator);
            try {
                Settings.FILL_VIDEO_TO_SCREEN.save(true);
                Object filled = VideoFit.fitted(video, strip);
                assertNotSame(strip, filled);
                Result copy = (Result) filled;
                assertEquals(1350, copy.getWidth());
                assertEquals(2400, copy.getHeight());
                // A frame centres its children once told to, so the offsets stay at nothing.
                assertEquals(Float.valueOf(0f), copy.getTranslateX());
                assertEquals(Float.valueOf(0f), copy.getTranslateY());
                assertSame(operator, copy.getResultOperator());
                assertEquals(android.view.Gravity.CENTER,
                        ((FrameLayout.LayoutParams) video.getLayoutParams()).gravity);

                // A video that already covers the window is TikTok's own result, untouched.
                Result covers = new Result(1350, 2400);
                assertSame(covers, VideoFit.fitted(video, covers));
                Result cropped = new Result(1440, 2560);
                assertSame(cropped, VideoFit.fitted(video, cropped));

                // Both on: the whole video inside the window, as the page keeps them apart anyway.
                Settings.FIT_VIDEO_TO_SCREEN.save(true);
                assertSame(strip, VideoFit.fitted(video, strip));
                Result fitted = (Result) VideoFit.fitted(video, cropped);
                assertEquals(1080, fitted.getWidth());
                assertEquals(1920, fitted.getHeight());

                // The story cell's path: the width first, the height and the two offsets after.
                Settings.FIT_VIDEO_TO_SCREEN.save(false);
                assertEquals(1350, VideoFit.fitWidthFor(strip, video));
                assertEquals(2400, VideoFit.fittedHeightFor(strip));
                assertEquals(Float.valueOf(0f), VideoFit.fittedTranslation(strip, Float.valueOf(9f)));
                assertEquals(Float.valueOf(0f), VideoFit.fittedTranslation(strip, Float.valueOf(9f)));
                // The decision is spent after the second offset.
                assertEquals(Float.valueOf(9f), VideoFit.fittedTranslation(strip, Float.valueOf(9f)));

                // A container that cannot centre its children gets the crop centred by the offsets:
                // half the overhang each way, across first, then down.
                android.widget.LinearLayout plain = new android.widget.LinearLayout(activity);
                View inPlain = new View(activity);
                plain.addView(inPlain);
                plain.layout(0, 0, 1080, 2400);
                inPlain.setLayoutParams(new android.widget.LinearLayout.LayoutParams(1080, 1920));
                Result again = new Result(1080, 1920);
                assertEquals(1350, VideoFit.fitWidthFor(again, inPlain));
                assertEquals(2400, VideoFit.fittedHeightFor(again));
                assertEquals(Float.valueOf(-135f), VideoFit.fittedTranslation(again, Float.valueOf(9f)));
                assertEquals(Float.valueOf(0f), VideoFit.fittedTranslation(again, Float.valueOf(9f)));
                Result feedAgain = new Result(1080, 1920, 0f, 0f, operator);
                Result feedCopy = (Result) VideoFit.fitted(inPlain, feedAgain);
                assertEquals(Float.valueOf(-135f), feedCopy.getTranslateX());
                assertEquals(Float.valueOf(0f), feedCopy.getTranslateY());
            } finally {
                Settings.FILL_VIDEO_TO_SCREEN.save(false);
                Settings.FIT_VIDEO_TO_SCREEN.save(false);
            }
        }
    }

    /** #406: the window covered with nothing cropped, each side scaled on its own. */
    @Test public void stretchGivesACloseShapedVideoTheWindowsOwnSize() {
        // A 9:16 video with TikTok's strip under it on a 20:9 phone: a quarter off, so it stretches.
        assertTrue(VideoFit.wants(VideoFit.Mode.STRETCH, 1080, 1920, 1080, 2400));
        assertEquals(1080, VideoFit.widthFor(VideoFit.Mode.STRETCH, 1080, 1920, 1080, 2400));
        assertEquals(2400, VideoFit.heightFor(VideoFit.Mode.STRETCH, 1080, 1920, 1080, 2400));
        // TikTok's own crop comes back in at the sides instead of hanging over them.
        assertTrue(VideoFit.wants(VideoFit.Mode.STRETCH, 1245, 2213, 1080, 2213));
        assertEquals(1080, VideoFit.widthFor(VideoFit.Mode.STRETCH, 1245, 2213, 1080, 2213));
        assertEquals(2213, VideoFit.heightFor(VideoFit.Mode.STRETCH, 1245, 2213, 1080, 2213));
        // Already the window's size: nothing to do.
        assertFalse(VideoFit.wants(VideoFit.Mode.STRETCH, 1080, 2400, 1080, 2400));
        // Wide, square, or turned the other way: left whole, as fill leaves them (#394).
        assertFalse(VideoFit.wants(VideoFit.Mode.STRETCH, 1920, 1080, 1080, 2400));
        assertFalse(VideoFit.wants(VideoFit.Mode.STRETCH, 1080, 1080, 1080, 2400));
        assertFalse(VideoFit.wants(VideoFit.Mode.STRETCH, 1080, 1920, 2400, 1080));
        // A 3:4 video on a 20:9 phone is two thirds off, which would look plainly squashed.
        assertFalse(VideoFit.wants(VideoFit.Mode.STRETCH, 1080, 1440, 1080, 2400));
        // A third off exactly still stretches, and a pixel past it doesn't.
        assertTrue(VideoFit.wants(VideoFit.Mode.STRETCH, 1200, 1600, 900, 1600));
        assertFalse(VideoFit.wants(VideoFit.Mode.STRETCH, 1201, 1600, 900, 1600));
        // A wide video on a wide window stretches the same way.
        assertTrue(VideoFit.wants(VideoFit.Mode.STRETCH, 1920, 1000, 2400, 1080));

        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            FrameLayout container = new FrameLayout(activity);
            View video = new View(activity);
            container.addView(video);
            container.layout(0, 0, 1080, 2400);
            video.setLayoutParams(new FrameLayout.LayoutParams(1080, 1920));
            Object operator = new Object();
            Result strip = new Result(1080, 1920, 0f, 0f, operator);
            boolean was = SettingsStatus.videoFitEnabled;
            try {
                Settings.STRETCH_VIDEO_TO_SCREEN.save(true);
                Result stretched = (Result) VideoFit.fitted(video, strip);
                assertNotSame(strip, stretched);
                assertEquals(1080, stretched.getWidth());
                assertEquals(2400, stretched.getHeight());
                assertEquals(Float.valueOf(0f), stretched.getTranslateX());
                assertEquals(Float.valueOf(0f), stretched.getTranslateY());
                assertSame(operator, stretched.getResultOperator());

                // The story cell's path gives the same size and nothing to shift by.
                Result story = new Result(1080, 1920);
                assertEquals(1080, VideoFit.fitWidthFor(story, video));
                assertEquals(2400, VideoFit.fittedHeightFor(story));
                assertEquals(Float.valueOf(0f), VideoFit.fittedTranslation(story, Float.valueOf(9f)));
                assertEquals(Float.valueOf(0f), VideoFit.fittedTranslation(story, Float.valueOf(9f)));

                // A wide video keeps TikTok's result.
                Result wide = new Result(1080, 608);
                assertSame(wide, VideoFit.fitted(video, wide));

                // It covers the screen, so an opened video gets the strip under its comment bar too.
                SettingsStatus.videoFitEnabled = true;
                assertTrue(VideoFit.fillsScreen());
                // Fit and fill both come before it.
                Settings.FILL_VIDEO_TO_SCREEN.save(true);
                assertEquals(VideoFit.Mode.FILL, VideoFit.mode());
                Settings.FIT_VIDEO_TO_SCREEN.save(true);
                assertEquals(VideoFit.Mode.FIT, VideoFit.mode());
                assertFalse(VideoFit.fillsScreen());
            } finally {
                SettingsStatus.videoFitEnabled = was;
                Settings.STRETCH_VIDEO_TO_SCREEN.save(false);
                Settings.FILL_VIDEO_TO_SCREEN.save(false);
                Settings.FIT_VIDEO_TO_SCREEN.save(false);
            }
        }
    }

    /**
     * #421: crop and stretch meets the window halfway. A 9:16 video on a 20:9 phone is a quarter
     * off; the blend gives it the shape halfway between, about a ninth from each, and covers the
     * window with it, so it loses about a ninth of its width and is stretched about a ninth.
     */
    @Test public void blendCropsAndStretchesHalfTheGapEach() {
        assertTrue(VideoFit.wants(VideoFit.Mode.BLEND, 1080, 1920, 1080, 2400));
        assertEquals(1207, VideoFit.widthFor(VideoFit.Mode.BLEND, 1080, 1920, 1080, 2400));
        assertEquals(2400, VideoFit.heightFor(VideoFit.Mode.BLEND, 1080, 1920, 1080, 2400));
        // TikTok's own crop at the sides is cut back to the blend's.
        assertTrue(VideoFit.wants(VideoFit.Mode.BLEND, 1245, 2213, 1080, 2213));
        assertEquals(1160, VideoFit.widthFor(VideoFit.Mode.BLEND, 1245, 2213, 1080, 2213));
        assertEquals(2213, VideoFit.heightFor(VideoFit.Mode.BLEND, 1245, 2213, 1080, 2213));
        // A 3:4 video, too far off for stretch alone, is half as far off for each half here.
        assertFalse(VideoFit.wants(VideoFit.Mode.STRETCH, 1080, 1440, 1080, 2400));
        assertTrue(VideoFit.wants(VideoFit.Mode.BLEND, 1080, 1440, 1080, 2400));
        assertEquals(1394, VideoFit.widthFor(VideoFit.Mode.BLEND, 1080, 1440, 1080, 2400));
        // 4:5 on 20:9 is the farthest it goes, and a pixel wider is left to TikTok.
        assertTrue(VideoFit.wants(VideoFit.Mode.BLEND, 1200, 1500, 1080, 2400));
        assertFalse(VideoFit.wants(VideoFit.Mode.BLEND, 1201, 1500, 1080, 2400));
        // The window's own shape and size has nothing to blend.
        assertFalse(VideoFit.wants(VideoFit.Mode.BLEND, 1080, 2400, 1080, 2400));
        // Wide, square, or turned the other way: left whole, as fill and stretch leave them.
        assertFalse(VideoFit.wants(VideoFit.Mode.BLEND, 1920, 1080, 1080, 2400));
        assertFalse(VideoFit.wants(VideoFit.Mode.BLEND, 1080, 1080, 1080, 2400));
        assertFalse(VideoFit.wants(VideoFit.Mode.BLEND, 1080, 1920, 2400, 1080));
        // A wide video on a wider window blends the other way: cropped top and bottom.
        assertTrue(VideoFit.wants(VideoFit.Mode.BLEND, 1920, 1000, 2400, 1080));
        assertEquals(2400, VideoFit.widthFor(VideoFit.Mode.BLEND, 1920, 1000, 2400, 1080));
        assertEquals(1162, VideoFit.heightFor(VideoFit.Mode.BLEND, 1920, 1000, 2400, 1080));

        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            FrameLayout container = new FrameLayout(activity);
            View video = new View(activity);
            container.addView(video);
            container.layout(0, 0, 1080, 2400);
            video.setLayoutParams(new FrameLayout.LayoutParams(1080, 1920));
            Object operator = new Object();
            Result strip = new Result(1080, 1920, 0f, 0f, operator);
            boolean was = SettingsStatus.videoFitEnabled;
            try {
                Settings.BLEND_VIDEO_TO_SCREEN.save(true);
                assertEquals(VideoFit.Mode.BLEND, VideoFit.mode());
                Result blended = (Result) VideoFit.fitted(video, strip);
                assertNotSame(strip, blended);
                assertEquals(1207, blended.getWidth());
                assertEquals(2400, blended.getHeight());
                // Centred by the frame, so the overhang is even on both sides.
                assertEquals(android.view.Gravity.CENTER, gravityOf(video));
                assertEquals(Float.valueOf(0f), blended.getTranslateX());
                assertSame(operator, blended.getResultOperator());

                // The story cell's path gives the same size.
                Result story = new Result(1080, 1920);
                assertEquals(1207, VideoFit.fitWidthFor(story, video));
                assertEquals(2400, VideoFit.fittedHeightFor(story));
                assertEquals(Float.valueOf(0f), VideoFit.fittedTranslation(story, Float.valueOf(9f)));
                assertEquals(Float.valueOf(0f), VideoFit.fittedTranslation(story, Float.valueOf(9f)));

                // It covers the screen, so an opened video gets the strip under its comment bar.
                SettingsStatus.videoFitEnabled = true;
                assertTrue(VideoFit.fillsScreen());
                // The three others come before it.
                Settings.STRETCH_VIDEO_TO_SCREEN.save(true);
                assertEquals(VideoFit.Mode.STRETCH, VideoFit.mode());
            } finally {
                SettingsStatus.videoFitEnabled = was;
                Settings.BLEND_VIDEO_TO_SCREEN.save(false);
                Settings.STRETCH_VIDEO_TO_SCREEN.save(false);
            }
        }
    }

    /**
     * #421: the cover image gets the video's size from the same result, but kept TikTok's crop
     * inside it, so with Stretch on the picture jumped when the stretched video replaced it. A
     * cover under a reshaped video is drawn stretched to its size, and gets the crop back after.
     */
    @Test public void aCoverIsDrawnStretchedWithItsVideoAndGetsTikToksCropBack() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            FrameLayout container = new FrameLayout(activity);
            CoverView cover = new CoverView(activity);
            container.addView(cover);
            container.layout(0, 0, 1080, 2400);
            cover.setLayoutParams(new FrameLayout.LayoutParams(1080, 1920));
            try {
                // Nothing on: TikTok's cover is never touched.
                Result own = new Result(1080, 1920, 0f, 0f, null);
                assertSame(own, VideoFit.fitted(cover, own));
                assertTrue("a cover with every switch off was given a scale", cover.scales.isEmpty());

                // Fill crops the video too, so its cover keeps TikTok's crop and is left alone.
                Settings.FILL_VIDEO_TO_SCREEN.save(true);
                assertNotSame(VideoFit.Mode.LEAVE_ALONE, VideoFit.mode());
                VideoFit.fitted(cover, new Result(1080, 1920, 0f, 0f, null));
                assertTrue("a filled cover was given a scale", cover.scales.isEmpty());
                Settings.FILL_VIDEO_TO_SCREEN.save(false);

                // Stretch: drawn to its size, every time, since a recycled cover may be reset.
                Settings.STRETCH_VIDEO_TO_SCREEN.save(true);
                Result stretched = (Result) VideoFit.fitted(cover, new Result(1080, 1920, 0f, 0f, null));
                assertEquals(2400, stretched.getHeight());
                VideoFit.fitted(cover, new Result(1080, 1920, 0f, 0f, null));
                assertEquals(java.util.List.of(Scale.FIT_XY, Scale.FIT_XY), cover.scales);

                // The switch goes off: TikTok's crop comes back, once.
                Settings.STRETCH_VIDEO_TO_SCREEN.save(false);
                cover.scales.clear();
                VideoFit.fitted(cover, new Result(1080, 1920, 0f, 0f, null));
                VideoFit.fitted(cover, new Result(1080, 1920, 0f, 0f, null));
                assertEquals(java.util.List.of(Scale.CENTER_CROP), cover.scales);

                // Crop and stretch reshapes it as well; fill after it gives the crop back.
                Settings.BLEND_VIDEO_TO_SCREEN.save(true);
                cover.scales.clear();
                VideoFit.fitted(cover, new Result(1080, 1920, 0f, 0f, null));
                assertEquals(java.util.List.of(Scale.FIT_XY), cover.scales);
                Settings.BLEND_VIDEO_TO_SCREEN.save(false);
                Settings.FILL_VIDEO_TO_SCREEN.save(true);
                cover.scales.clear();
                VideoFit.fitted(cover, new Result(1080, 1920, 0f, 0f, null));
                assertEquals(java.util.List.of(Scale.CENTER_CROP), cover.scales);

                // A video too far off to stretch is left to TikTok, cover and all.
                Settings.FILL_VIDEO_TO_SCREEN.save(false);
                Settings.STRETCH_VIDEO_TO_SCREEN.save(true);
                cover.scales.clear();
                Result wide = new Result(1080, 608, 0f, 0f, null);
                assertSame(wide, VideoFit.fitted(cover, wide));
                assertTrue(cover.scales.isEmpty());

                // The story cell's path stretches its cover the same way.
                Result story = new Result(1080, 1920);
                assertEquals(1080, VideoFit.fitWidthFor(story, cover));
                assertEquals(java.util.List.of(Scale.FIT_XY), cover.scales);
                assertEquals(2400, VideoFit.fittedHeightFor(story));
                VideoFit.fittedTranslation(story, Float.valueOf(0f));
                VideoFit.fittedTranslation(story, Float.valueOf(0f));
            } finally {
                Settings.FILL_VIDEO_TO_SCREEN.save(false);
                Settings.STRETCH_VIDEO_TO_SCREEN.save(false);
                Settings.BLEND_VIDEO_TO_SCREEN.save(false);
            }
        }
    }

    /**
     * #421: Keep the middle needs Android 13's shader. Below it, a switch carried in by a backup
     * crops and stretches like blend, the page doesn't offer it, and the report says why.
     */
    @Test public void keepTheMiddleCropsAndStretchesBelowAndroid13() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            FrameLayout container = new FrameLayout(activity);
            View video = new View(activity);
            container.addView(video);
            container.layout(0, 0, 1080, 2400);
            video.setLayoutParams(new FrameLayout.LayoutParams(1080, 1920));
            KeepMiddle.resetForTests();
            VideoFit.resetReportForTests();
            try {
                Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.save(true);
                assertFalse(KeepMiddle.supported());
                assertEquals(VideoFit.Mode.BLEND, VideoFit.mode());
                Result blended = (Result) VideoFit.fitted(video, new Result(1080, 1920, 0f, 0f, null));
                assertEquals(1207, blended.getWidth());
                assertEquals(2400, blended.getHeight());
                assertFalse(KeepMiddle.warped(video));
                assertTrue(VideoFit.Report.INSTANCE.lines().contains(
                        "Keep the middle needs Android 13, so videos are cropped and stretched instead"));
                // Nothing here can draw it, so TikTok keeps its own player view.
                assertEquals(1, VideoFit.videoSurfaceGate(KeepMiddle.SURFACE_VIEW_GATE, 1));
            } finally {
                Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.save(false);
                KeepMiddle.resetForTests();
                VideoFit.resetReportForTests();
            }
        }
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            boolean was = SettingsStatus.videoFitEnabled;
            SettingsStatus.videoFitEnabled = true;
            try {
                PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
                new PlaybackPreferenceCategory(activity, screen);
                assertNotNull(screen.findPreference(Settings.BLEND_VIDEO_TO_SCREEN.key));
                assertNull("keep the middle was offered below Android 13",
                        screen.findPreference(Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.key));
                // A backup's hidden switch still goes off when another one comes on.
                Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.save(true);
                android.preference.TwoStatePreference fit =
                        (android.preference.TwoStatePreference) screen.findPreference(Settings.FIT_VIDEO_TO_SCREEN.key);
                assertTrue(fit.getOnPreferenceChangeListener().onPreferenceChange(fit, true));
                assertFalse(Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.get());
            } finally {
                SettingsStatus.videoFitEnabled = was;
                Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.save(false);
                Settings.FIT_VIDEO_TO_SCREEN.save(false);
            }
        }
    }

    @Test public void fillLeavesAWideOrSquareVideoWholeOnATallWindow() {
        // #394: a 16:9 video TikTok letterboxed on a 1220x2656 phone came out cropped to the
        // middle quarter of its width with fill on.
        assertFalse(VideoFit.wants(VideoFit.Mode.FILL, 1220, 686, 1220, 2656));
        assertFalse(VideoFit.wants(VideoFit.Mode.FILL, 1220, 1220, 1220, 2656));
        // A tall video on a wide window is the same mismatch the other way round.
        assertFalse(VideoFit.wants(VideoFit.Mode.FILL, 1080, 1920, 2400, 1080));
        // A wide video on a wide window still loses its bars.
        assertTrue(VideoFit.wants(VideoFit.Mode.FILL, 1920, 1000, 2400, 1080));
        // Fit is untouched by it: a wide video spilling past a tall window still comes back in.
        assertTrue(VideoFit.wants(VideoFit.Mode.FIT, 4267, 2400, 1080, 2400));

        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            FrameLayout container = new FrameLayout(activity);
            View video = new View(activity);
            container.addView(video);
            container.layout(0, 0, 1220, 2656);
            video.setLayoutParams(new FrameLayout.LayoutParams(1220, 686));
            Result wide = new Result(1220, 686, 0f, 985f, new Object());
            try {
                Settings.FILL_VIDEO_TO_SCREEN.save(true);
                assertSame(wide, VideoFit.fitted(video, wide));
                assertEquals(VideoFit.LEAVE, VideoFit.fitWidthFor(wide, video));
                assertEquals(Float.valueOf(985f), VideoFit.fittedTranslation(wide, Float.valueOf(985f)));
            } finally {
                Settings.FILL_VIDEO_TO_SCREEN.save(false);
                Settings.FIT_VIDEO_TO_SCREEN.save(false);
            }
        }
    }

    /**
     * TikTok reuses a cell's video view, layout parameters and all, for the next video, and lays
     * its own result out with offsets that assume its own gravity. A view this code centred for
     * one video has to get that gravity back when the next result is left alone, or TikTok's
     * offsets land on top of the centring and a video it cropped shows an edge, not the middle.
     */
    @Test public void aResultLeftAloneGetsTheViewsOwnGravityBack() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            FrameLayout container = new FrameLayout(activity);
            View video = new View(activity);
            container.addView(video);
            container.layout(0, 0, 1080, 2400);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1080, 1920);
            params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            video.setLayoutParams(params);
            int own = params.gravity;
            try {
                Settings.FILL_VIDEO_TO_SCREEN.save(true);
                Result strip = new Result(1080, 1920, 0f, 0f, null);
                assertNotSame("the strip was not filled, so this checks nothing", strip, VideoFit.fitted(video, strip));
                assertEquals(android.view.Gravity.CENTER, gravityOf(video));

                // The next video in the cell already covers the window, cropped by TikTok and
                // placed by its own offsets.
                Result covers = new Result(1350, 2400, -135f, 0f, null);
                assertSame(covers, VideoFit.fitted(video, covers));
                assertEquals("TikTok's offsets would land on top of the centring", own, gravityOf(video));

                // Filled again, then the switch goes off: TikTok's result and its gravity both.
                VideoFit.fitted(video, new Result(1080, 1920, 0f, 0f, null));
                assertEquals(android.view.Gravity.CENTER, gravityOf(video));
                Settings.FILL_VIDEO_TO_SCREEN.save(false);
                Result untouched = new Result(1080, 1920, 0f, 0f, null);
                assertSame(untouched, VideoFit.fitted(video, untouched));
                assertEquals("turning the switch off left the centring behind", own, gravityOf(video));

                // The story cell's path does the same.
                Settings.FILL_VIDEO_TO_SCREEN.save(true);
                assertEquals(1350, VideoFit.fitWidthFor(new Result(1080, 1920), video));
                assertEquals(android.view.Gravity.CENTER, gravityOf(video));
                assertEquals(VideoFit.LEAVE, VideoFit.fitWidthFor(new Result(1350, 2400), video));
                assertEquals(own, gravityOf(video));

                // Layout parameters TikTok replaced after the centring are TikTok's, and stay.
                VideoFit.fitted(video, new Result(1080, 1920, 0f, 0f, null));
                FrameLayout.LayoutParams replaced = new FrameLayout.LayoutParams(1350, 2400);
                replaced.gravity = android.view.Gravity.BOTTOM;
                video.setLayoutParams(replaced);
                assertSame(covers, VideoFit.fitted(video, covers));
                assertEquals("a gravity TikTok set after the centring was overwritten",
                        android.view.Gravity.BOTTOM, gravityOf(video));

                // A gravity TikTok set itself is TikTok's, and stays.
                View centred = new View(activity);
                container.addView(centred);
                FrameLayout.LayoutParams itsOwn = new FrameLayout.LayoutParams(1080, 1920);
                itsOwn.gravity = android.view.Gravity.CENTER;
                centred.setLayoutParams(itsOwn);
                VideoFit.fitted(centred, new Result(1080, 1920, 0f, 0f, null));
                assertSame(covers, VideoFit.fitted(centred, covers));
                assertEquals(android.view.Gravity.CENTER, gravityOf(centred));
            } finally {
                Settings.FILL_VIDEO_TO_SCREEN.save(false);
            }
        }
    }

    private static int gravityOf(View view) {
        return ((FrameLayout.LayoutParams) view.getLayoutParams()).gravity;
    }

    @Test public void thePageKeepsFitAndFillApart() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            boolean was = SettingsStatus.videoFitEnabled;
            SettingsStatus.videoFitEnabled = true;
            try {
                PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
                activity.setPreferenceScreen(screen);
                new PlaybackPreferenceCategory(activity, screen);
                android.preference.TwoStatePreference fit =
                        (android.preference.TwoStatePreference) screen.findPreference(Settings.FIT_VIDEO_TO_SCREEN.key);
                android.preference.TwoStatePreference fill =
                        (android.preference.TwoStatePreference) screen.findPreference(Settings.FILL_VIDEO_TO_SCREEN.key);
                assertNotNull("the fit row is missing", fit);
                assertNotNull("the fill row is missing", fill);

                Settings.FIT_VIDEO_TO_SCREEN.save(true);
                fit.setChecked(true);
                assertTrue(fill.getOnPreferenceChangeListener().onPreferenceChange(fill, true));
                assertFalse("fit stayed on beside fill", Settings.FIT_VIDEO_TO_SCREEN.get());
                assertFalse(fit.isChecked());

                Settings.FILL_VIDEO_TO_SCREEN.save(true);
                fill.setChecked(true);
                assertTrue(fit.getOnPreferenceChangeListener().onPreferenceChange(fit, true));
                assertFalse("fill stayed on beside fit", Settings.FILL_VIDEO_TO_SCREEN.get());
                assertFalse(fill.isChecked());

                // Turning one off leaves the other as it is.
                Settings.FIT_VIDEO_TO_SCREEN.save(true);
                assertTrue(fill.getOnPreferenceChangeListener().onPreferenceChange(fill, false));
                assertTrue(Settings.FIT_VIDEO_TO_SCREEN.get());

                // Stretch (#406) is the third of them: on, it turns both others off.
                android.preference.TwoStatePreference stretch =
                        (android.preference.TwoStatePreference) screen.findPreference(Settings.STRETCH_VIDEO_TO_SCREEN.key);
                assertNotNull("the stretch row is missing", stretch);
                Settings.FILL_VIDEO_TO_SCREEN.save(true);
                fit.setChecked(true);
                fill.setChecked(true);
                assertTrue(stretch.getOnPreferenceChangeListener().onPreferenceChange(stretch, true));
                assertFalse(Settings.FIT_VIDEO_TO_SCREEN.get());
                assertFalse(Settings.FILL_VIDEO_TO_SCREEN.get());
                assertFalse(fit.isChecked());
                assertFalse(fill.isChecked());

                // And either other one turns it off.
                Settings.STRETCH_VIDEO_TO_SCREEN.save(true);
                stretch.setChecked(true);
                assertTrue(fill.getOnPreferenceChangeListener().onPreferenceChange(fill, true));
                assertFalse("stretch stayed on beside fill", Settings.STRETCH_VIDEO_TO_SCREEN.get());
                assertFalse(stretch.isChecked());

                // Crop and stretch (#421) is the fourth: on, it turns the rest off, and back.
                android.preference.TwoStatePreference blend =
                        (android.preference.TwoStatePreference) screen.findPreference(Settings.BLEND_VIDEO_TO_SCREEN.key);
                assertNotNull("the crop and stretch row is missing", blend);
                Settings.FILL_VIDEO_TO_SCREEN.save(true);
                Settings.STRETCH_VIDEO_TO_SCREEN.save(true);
                fill.setChecked(true);
                stretch.setChecked(true);
                assertTrue(blend.getOnPreferenceChangeListener().onPreferenceChange(blend, true));
                assertFalse(Settings.FILL_VIDEO_TO_SCREEN.get());
                assertFalse(Settings.STRETCH_VIDEO_TO_SCREEN.get());
                assertFalse(fill.isChecked());
                assertFalse(stretch.isChecked());
                Settings.BLEND_VIDEO_TO_SCREEN.save(true);
                blend.setChecked(true);
                assertTrue(stretch.getOnPreferenceChangeListener().onPreferenceChange(stretch, true));
                assertFalse("crop and stretch stayed on beside stretch", Settings.BLEND_VIDEO_TO_SCREEN.get());
                assertFalse(blend.isChecked());
            } finally {
                SettingsStatus.videoFitEnabled = was;
                Settings.BLEND_VIDEO_TO_SCREEN.save(false);
                Settings.STRETCH_VIDEO_TO_SCREEN.save(false);
                Settings.FILL_VIDEO_TO_SCREEN.save(false);
                Settings.FIT_VIDEO_TO_SCREEN.save(false);
            }
        }
    }

    @Test public void theSwitchDecidesAndAnythingUnreadableIsLeftToTikTok() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);

            // A container of a shape a phone folded open has, with the video cropped to fill it.
            FrameLayout container = new FrameLayout(activity);
            View video = new View(activity);
            container.addView(video);
            container.layout(0, 0, 1200, 900);
            // Laid out the way TikTok would: taller than the window, so the ends are cut off.
            video.setLayoutParams(new FrameLayout.LayoutParams(1440, 2560));
            Result cropped = new Result(1440, 2560);

            // Off: the result is to be left alone, and nothing is remembered.
            Settings.FIT_VIDEO_TO_SCREEN.save(false);
            assertEquals(VideoFit.LEAVE, VideoFit.fitWidthFor(cropped, video));
            assertEquals(2560, VideoFit.fittedHeightFor(cropped));
            assertEquals(Float.valueOf(12f), VideoFit.fittedTranslation(cropped, Float.valueOf(12f)));

            Settings.FIT_VIDEO_TO_SCREEN.save(true);
            assertEquals(506, VideoFit.fitWidthFor(cropped, video));
            // The patch writes that width into the result before asking for the height, and the
            // result's hash moves with it. The answer has to be for this result all the same.
            cropped.width = 506;
            // The height that goes with that width, worked out from the size before it changed.
            assertEquals(900, VideoFit.fittedHeightFor(cropped));
            // Asked twice, because the host reads it once and this must not consume it.
            assertEquals(900, VideoFit.fittedHeightFor(cropped));
            // The offsets that centred a crop would push a fitted video off the screen.
            assertEquals(Float.valueOf(0f), VideoFit.fittedTranslation(cropped, Float.valueOf(12f)));
            // A video that no longer fills has to say where it sits, or every bar ends up on
            // the same side.
            assertEquals(android.view.Gravity.CENTER,
                    ((FrameLayout.LayoutParams) video.getLayoutParams()).gravity);

            // A video that already fits is left exactly as TikTok laid it out, offsets included.
            Result fits = new Result(400, 800);
            assertEquals(VideoFit.LEAVE, VideoFit.fitWidthFor(fits, video));
            assertEquals(800, VideoFit.fittedHeightFor(fits));
            assertEquals(Float.valueOf(12f), VideoFit.fittedTranslation(fits, Float.valueOf(12f)));

            // A second result equal to the last fitted one, by value, is still not it: the
            // decision belongs to the object it was made for.
            cropped.width = 1440;
            assertEquals(506, VideoFit.fitWidthFor(cropped, video));
            Result twin = new Result(1440, 2560);
            assertEquals(2560, VideoFit.fittedHeightFor(twin));
            assertEquals(Float.valueOf(12f), VideoFit.fittedTranslation(twin, Float.valueOf(12f)));
            assertEquals(900, VideoFit.fittedHeightFor(cropped));

            // Deciding again on the same result clears what the last pass remembered.
            container.layout(0, 0, 2000, 4000);
            View roomy = new View(activity);
            container.addView(roomy);
            assertEquals(VideoFit.LEAVE, VideoFit.fitWidthFor(cropped, roomy));
            assertEquals(2560, VideoFit.fittedHeightFor(cropped));
            assertEquals(Float.valueOf(12f), VideoFit.fittedTranslation(cropped, Float.valueOf(12f)));

            // Before the first layout pass nothing above the video has a size, and the window
            // is what is left to measure against. Taken from the view's own resources, which
            // in a split view is the half TikTok has rather than the whole display.
            android.util.DisplayMetrics window = activity.getResources().getDisplayMetrics();
            assertTrue("the window is a squarer shape than 9:16",
                    window.widthPixels * 16 > window.heightPixels * 9);
            FrameLayout unmeasured = new FrameLayout(activity);
            View early = new View(activity);
            unmeasured.addView(early);
            early.setLayoutParams(new FrameLayout.LayoutParams(1440, 2560));
            Result earlyResult = new Result(1440, 2560);
            assertEquals(Math.round(window.heightPixels * 1440f / 2560f),
                    VideoFit.fitWidthFor(earlyResult, early));
            assertEquals(window.heightPixels, VideoFit.fittedHeightFor(earlyResult));

            // Nothing to work with: no view, no result, a result we cannot read, and sizes
            // that make no sense. Every one of them says leave it, and the patch then writes
            // nothing. The old answer was the width it had read, 0 for a result it could not
            // read, and the patch wrote that into the result, so a renamed getter would have
            // laid the story out at nothing by nothing.
            assertEquals(VideoFit.LEAVE, VideoFit.fitWidthFor(cropped, null));
            assertEquals(VideoFit.LEAVE, VideoFit.fitWidthFor(null, video));
            assertEquals(VideoFit.LEAVE, VideoFit.fitWidthFor(new Shapeless(), video));
            assertEquals(VideoFit.LEAVE, VideoFit.fitWidthFor(new Result(0, 2560), video));
            assertEquals(VideoFit.LEAVE, VideoFit.fitWidthFor(new Result(1440, -1), video));
        } finally {
            Settings.FIT_VIDEO_TO_SCREEN.save(false);
        }
    }

    @Test public void theFeedCellIsHandedAFittedCopyAndOtherwiseItsOwnResult() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            FrameLayout container = new FrameLayout(activity);
            LayoutTrackingView video = new LayoutTrackingView(activity);
            container.addView(video);
            container.layout(0, 0, 1200, 900);
            video.setLayoutParams(new FrameLayout.LayoutParams(1440, 2560));
            int layoutWritesBeforeFit = video.layoutWrites;
            Object operator = new Object();
            Result cropped = new Result(1440, 2560, 0f, -320f, operator);

            // Off: the very same result, not a copy of it.
            Settings.FIT_VIDEO_TO_SCREEN.save(false);
            assertSame(cropped, VideoFit.fitted(video, cropped));

            // On: a copy at the fitted size, with no offsets, carrying the operator across.
            Settings.FIT_VIDEO_TO_SCREEN.save(true);
            Object fitted = VideoFit.fitted(video, cropped);
            assertNotSame(cropped, fitted);
            Result copy = (Result) fitted;
            assertEquals(506, copy.getWidth());
            assertEquals(900, copy.getHeight());
            assertEquals(Float.valueOf(0f), copy.getTranslateX());
            assertEquals(Float.valueOf(0f), copy.getTranslateY());
            assertSame(operator, copy.getResultOperator());
            assertEquals(android.view.Gravity.CENTER,
                    ((FrameLayout.LayoutParams) video.getLayoutParams()).gravity);
            assertEquals(layoutWritesBeforeFit + 1, video.layoutWrites);
            // The result handed in is left exactly as it was.
            assertEquals(1440, cropped.getWidth());
            assertEquals(2560, cropped.getHeight());
            assertEquals(Float.valueOf(-320f), cropped.getTranslateY());

            // A video that already fits is TikTok's own result again, offsets and all.
            Result fits = new Result(400, 800, 0f, 12f, operator);
            assertSame(fits, VideoFit.fitted(video, fits));

            // Nothing to work with hands the result straight back.
            assertSame(cropped, VideoFit.fitted(null, cropped));
            assertNull(VideoFit.fitted(video, null));
            Shapeless shapeless = new Shapeless();
            assertSame(shapeless, VideoFit.fitted(video, shapeless));
            // A result that can be read but not remade is also handed back as it is. Readable,
            // as the story path proves by working out a size from it; only the copy is missing.
            Uncopyable storyResult = new Uncopyable();
            assertEquals(506, VideoFit.fitWidthFor(storyResult, video));
            assertEquals(1, storyResult.widthReads);
            assertEquals(1, storyResult.heightReads);
            Uncopyable feedResult = new Uncopyable();
            assertSame(feedResult, VideoFit.fitted(video, feedResult));
            assertEquals(1, feedResult.widthReads);
            assertEquals(1, feedResult.heightReads);

            // A copy that is not public, on a class in another package, is still the copy to
            // remake the result through. VideoFit sits outside TikTok's package the way it sits
            // outside this one, so the copy is found among what the class declares and has to be
            // made accessible before it can be called.
            NarrowedResult narrowed = new NarrowedResult(1440, 2560);
            Object remade = VideoFit.fitted(video, narrowed);
            assertNotSame(narrowed, remade);
            assertEquals(506, ((NarrowedResult) remade).getWidth());
            assertEquals(900, ((NarrowedResult) remade).getHeight());
        } finally {
            Settings.FIT_VIDEO_TO_SCREEN.save(false);
        }
    }

    /** Stands in for the pager the feed's pages sit in, which the report knows by its class name. */
    public static final class PagesViewPager extends FrameLayout {
        PagesViewPager(Context context) { super(context); }
    }

    @Test public void theReportKeepsEachDecisionWithTheSizesAboveTheVideo() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            VideoFit.resetReportForTests();
            // #29's shape: the page is 2213 tall, the frame right above the video was 1500 tall
            // when the fit was worked out, and the video came out 844x1500 with bars all round.
            PagesViewPager pager = new PagesViewPager(activity);
            FrameLayout page = new FrameLayout(activity);
            FrameLayout frame = new FrameLayout(activity);
            View video = new View(activity);
            pager.addView(page);
            page.addView(frame);
            frame.addView(video);
            pager.layout(0, 0, 1080, 2213);
            page.layout(0, 0, 1080, 2213);
            frame.layout(0, 0, 1080, 1500);
            video.setLayoutParams(new FrameLayout.LayoutParams(1245, 2213));
            Settings.FIT_VIDEO_TO_SCREEN.save(true);

            Result cropped = new Result(1245, 2213, -82.5f, 0f, null);
            VideoFit.fitted(video, cropped);
            // The feed asks again to compare, and the same answer counts on the same line.
            VideoFit.fitted(video, cropped);
            VideoFit.fitted(video, new Result(800, 1400, 0f, 0f, null));

            java.util.List<String> lines = VideoFit.Report.INSTANCE.lines();
            assertEquals("Fit the video to the screen: on", lines.get(0));
            assertEquals("Fill the screen with the video: off", lines.get(1));
            assertEquals("Stretch the video to the screen: off", lines.get(2));
            assertEquals("Crop and stretch to fill the screen: off", lines.get(3));
            assertEquals("Keep the middle unstretched: off", lines.get(4));
            assertEquals("Fit checks since TikTok started: 2 resized the video, 1 left it at TikTok's size", lines.get(5));
            assertEquals("The last 2, oldest first:", lines.get(6));
            String above = ". Above the video: FrameLayout 1080x1500, FrameLayout 1080x2213 (the page); window 1080x2213";
            assertEquals("Fit, feed: TikTok's size 1245x2213, space 1080x1500, made 844x1500" + above + " (2 in a row)",
                    lines.get(7));
            assertEquals("Fit, feed: TikTok's size 800x1400, space 1080x1500, left as it was" + above, lines.get(8));
            assertEquals(9, lines.size());

            // A stretch says so on its own line.
            Settings.FIT_VIDEO_TO_SCREEN.save(false);
            Settings.STRETCH_VIDEO_TO_SCREEN.save(true);
            VideoFit.fitted(video, cropped);
            lines = VideoFit.Report.INSTANCE.lines();
            assertEquals("Stretch the video to the screen: on", lines.get(2));
            assertEquals("Stretch, feed: TikTok's size 1245x2213, space 1080x1500, made 1080x1500" + above,
                    lines.get(lines.size() - 1));

            // So does a blend.
            Settings.STRETCH_VIDEO_TO_SCREEN.save(false);
            Settings.BLEND_VIDEO_TO_SCREEN.save(true);
            VideoFit.fitted(video, cropped);
            lines = VideoFit.Report.INSTANCE.lines();
            assertEquals("Crop and stretch to fill the screen: on", lines.get(3));
            assertEquals("Crop and stretch, feed: TikTok's size 1245x2213, space 1080x1500, made 1080x1697" + above,
                    lines.get(lines.size() - 1));
        } finally {
            Settings.FIT_VIDEO_TO_SCREEN.save(false);
            Settings.STRETCH_VIDEO_TO_SCREEN.save(false);
            Settings.BLEND_VIDEO_TO_SCREEN.save(false);
            VideoFit.resetReportForTests();
        }
    }

    /** Under Pause every switch reads off; the report keeps the choice and says why nothing fits. */
    @Test public void aPausedReportKeepsTheChoiceAndSaysItIsPaused() {
        VideoFit.resetReportForTests();
        Settings.FILL_VIDEO_TO_SCREEN.save(true);
        PausedProcess.set(true);
        try {
            assertEquals(java.util.List.of("Fit the video to the screen: off",
                    "Fill the screen with the video: on",
                    "Stretch the video to the screen: off",
                    "Crop and stretch to fill the screen: off",
                    "Keep the middle unstretched: off",
                    "Hushfeed is paused, so videos keep TikTok's size",
                    "Fit checks since TikTok started: 0 resized the video, 0 left it at TikTok's size"),
                    VideoFit.Report.INSTANCE.lines());
        } finally {
            PausedProcess.set(false);
            Settings.FILL_VIDEO_TO_SCREEN.save(false);
        }
        // Not paused, the line goes.
        Settings.FIT_VIDEO_TO_SCREEN.save(true);
        try {
            assertFalse(VideoFit.Report.INSTANCE.lines().contains("Hushfeed is paused, so videos keep TikTok's size"));
        } finally {
            Settings.FIT_VIDEO_TO_SCREEN.save(false);
        }
    }

    @Test public void aFitIsWorkedOutAgainWhenItsSpaceIsLaidOutAtANewSize() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            FrameLayout page = new FrameLayout(activity);
            FrameLayout space = new FrameLayout(activity);
            View video = new View(activity);
            page.addView(space);
            space.addView(video);
            page.layout(0, 0, 1080, 2213);
            // #29: the space read at an old size while TikTok handed over its result for the new one.
            space.layout(0, 0, 1080, 1500);
            video.setLayoutParams(new FrameLayout.LayoutParams(1245, 2213));
            Settings.FIT_VIDEO_TO_SCREEN.save(true);

            Result copy = (Result) VideoFit.fitted(video, new Result(1245, 2213, -82.5f, 0f, null));
            assertEquals(844, copy.getWidth());
            assertEquals(1500, copy.getHeight());
            // TikTok writes the copy's size into the view.
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) video.getLayoutParams();
            params.width = 844;
            params.height = 1500;
            video.setLayoutParams(params);

            // The layout catches up. TikTok hands nothing over again, and the fit follows anyway.
            space.layout(0, 0, 1080, 2213);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(1080, video.getLayoutParams().width);
            assertEquals(1920, video.getLayoutParams().height);
            assertEquals(android.view.Gravity.CENTER,
                    ((FrameLayout.LayoutParams) video.getLayoutParams()).gravity);

            // A size the video was given by anything else since is that one's, and stays.
            params = (FrameLayout.LayoutParams) video.getLayoutParams();
            params.width = 500;
            params.height = 500;
            video.setLayoutParams(params);
            space.layout(0, 0, 1080, 1700);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(500, video.getLayoutParams().width);
            assertEquals(500, video.getLayoutParams().height);

            // Turned off between the fit and the new size: TikTok's next pass decides, not this.
            copy = (Result) VideoFit.fitted(video, new Result(1245, 2213, -82.5f, 0f, null));
            assertEquals(956, copy.getWidth());
            assertEquals(1700, copy.getHeight());
            params = (FrameLayout.LayoutParams) video.getLayoutParams();
            params.width = 956;
            params.height = 1700;
            video.setLayoutParams(params);
            Settings.FIT_VIDEO_TO_SCREEN.save(false);
            space.layout(0, 0, 1080, 2213);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(956, video.getLayoutParams().width);
            assertEquals(1700, video.getLayoutParams().height);
        } finally {
            Settings.FIT_VIDEO_TO_SCREEN.save(false);
            VideoFit.resetReportForTests();
        }
    }

    public static final class RecordingSpace extends FrameLayout {
        int removed;
        RecordingSpace(Context context) { super(context); }
        @Override public void removeOnLayoutChangeListener(OnLayoutChangeListener listener) {
            removed++;
            super.removeOnLayoutChangeListener(listener);
        }
    }

    /** A space whose video has gone lets go of its watch on its next pass, at any size. */
    @Test public void aWatchLetsGoOnceItsVideoLeavesEvenAtTheSameSize() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            FrameLayout page = new FrameLayout(activity);
            RecordingSpace space = new RecordingSpace(activity);
            View video = new View(activity);
            page.addView(space);
            space.addView(video);
            page.layout(0, 0, 1080, 2213);
            space.layout(0, 0, 1080, 1500);
            video.setLayoutParams(new FrameLayout.LayoutParams(1245, 2213));
            Settings.FIT_VIDEO_TO_SCREEN.save(true);
            VideoFit.fitted(video, new Result(1245, 2213, -82.5f, 0f, null));
            assertEquals(0, space.removed);

            // The page is recycled: the video goes, and the space is laid out again at its size.
            space.removeView(video);
            int exactly = View.MeasureSpec.EXACTLY;
            space.measure(View.MeasureSpec.makeMeasureSpec(1080, exactly),
                    View.MeasureSpec.makeMeasureSpec(1500, exactly));
            space.layout(0, 0, 1080, 1500);
            assertEquals(1, space.removed);
        } finally {
            Settings.FIT_VIDEO_TO_SCREEN.save(false);
            VideoFit.resetReportForTests();
        }
    }

    @Test public void aStoryFitStopsOwningTheResultAfterBothOffsets() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            FrameLayout container = new FrameLayout(activity);
            View video = new View(activity);
            container.addView(video);
            container.layout(0, 0, 1200, 900);
            video.setLayoutParams(new FrameLayout.LayoutParams(1440, 2560));
            Result cropped = new Result(1440, 2560);

            Settings.FIT_VIDEO_TO_SCREEN.save(true);
            assertEquals(506, VideoFit.fitWidthFor(cropped, video));
            cropped.width = 506;
            assertEquals(900, VideoFit.fittedHeightFor(cropped));
            assertEquals(Float.valueOf(0f),
                    VideoFit.fittedTranslation(cropped, Float.valueOf(12f)));
            assertEquals(Float.valueOf(0f),
                    VideoFit.fittedTranslation(cropped, Float.valueOf(34f)));
            assertEquals(Float.valueOf(56f),
                    VideoFit.fittedTranslation(cropped, Float.valueOf(56f)));
        } finally {
            VideoFit.fitWidthFor(null, null);
            Settings.FIT_VIDEO_TO_SCREEN.save(false);
        }
    }

    @Test public void theSwitchIsReachableOnItsOwn() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.videoFitEnabled = true;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new PlaybackPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("fit_video_to_screen"));
        } finally {
            SettingsStatus.videoFitEnabled = false;
        }
    }
}

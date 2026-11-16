package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceScreen;
import android.preference.TwoStatePreference;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.PlaybackPreferenceCategory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Keep the middle unstretched (#421): the video covers the window, its middle at its own shape,
 * with the rest of the gap made up in bands along the top and bottom.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class KeepMiddleTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void clean() {
        KeepMiddle.resetForTests();
        VideoFit.resetReportForTests();
    }

    @After public void restore() {
        Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.save(false);
        Settings.BLEND_VIDEO_TO_SCREEN.save(false);
        Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
        Settings.SEE_THROUGH_VIDEO_IN_WINDOW.save(false);
        KeepMiddle.resetForTests();
        VideoFit.resetReportForTests();
    }

    /** The S22: TikTok sizes a 9:16 video 1080x1920 in a 1080x2136 window. */
    @Test public void theMiddleKeepsItsShapeAndOnlyTheBandsStretch() {
        KeepMiddle.Plan plan = KeepMiddle.plan(1080, 1920, 1080, 2136);
        assertNotNull(plan);
        double height = 2136;
        double width = 1080;
        // Half the gap, as a ratio, comes from scaling up: the sides lose about 2.6% each.
        assertEquals(1 / Math.sqrt(2136 / 1920.0), plan.crop, 1e-9);
        assertEquals(0.0259, (1 - plan.crop) / 2, 1e-3);
        // At that scale the middle is the video's own shape: its full width over its height.
        assertEquals(1080 / 1920.0, (width / plan.crop) / plan.natural, 1e-9);
        // Each band starts a third taller at the edge and meets the middle at the video's scale.
        assertEquals(0.75, plan.rim * plan.natural, 1e-9);
        assertEquals(1 / plan.natural, slope(plan, plan.band - 1e-3, height), 1e-6);
        assertEquals(1 / plan.natural, slope(plan, height / 2, height), 1e-9);
        assertEquals(plan.rim, slope(plan, 1e-3, height), 1e-6);
        // More than half the screen is left exactly as it is.
        assertTrue((height - 2 * plan.band) / height > 0.55);

        // The whole video, top to bottom, with no jump where a band meets the middle.
        assertEquals(0, KeepMiddle.sourceY(plan, 0, height), 1e-9);
        assertEquals(1, KeepMiddle.sourceY(plan, height, height), 1e-9);
        assertEquals(0.5, KeepMiddle.sourceY(plan, height / 2, height), 1e-9);
        for (double edge : new double[]{plan.band, height - plan.band}) {
            assertEquals(KeepMiddle.sourceY(plan, edge - 1e-6, height),
                    KeepMiddle.sourceY(plan, edge + 1e-6, height), 1e-6);
        }
        double before = -1;
        for (int y = 0; y <= 2136; y++) {
            double v = KeepMiddle.sourceY(plan, y, height);
            assertTrue("row " + y + " went back up the video", v > before);
            before = v;
        }
        // Across, the middle column stays put and the edges show the cropped share.
        assertEquals(0.5, KeepMiddle.sourceX(plan, width / 2, width), 1e-9);
        assertEquals((1 - plan.crop) / 2, KeepMiddle.sourceX(plan, 0, width), 1e-9);
    }

    @Test public void aVideoTooFarFromTheScreensShapeIsLeftAlone() {
        // A 20:9 phone still takes a 9:16 video, the bands at their most.
        KeepMiddle.Plan tall = KeepMiddle.plan(1080, 1920, 1080, 2400);
        assertNotNull(tall);
        assertEquals(800, tall.band, 1e-9);
        // A 3:4 video would need the edge more than twice as tall.
        assertNull(KeepMiddle.plan(1080, 1440, 1080, 2400));
        // Already as tall as the window, or taller: nothing to make up.
        assertNull(KeepMiddle.plan(1080, 2136, 1080, 2136));
        assertNull(KeepMiddle.plan(1080, 2400, 1080, 2136));
        assertNull(KeepMiddle.plan(0, 1920, 1080, 2136));
        assertFalse(VideoFit.wants(VideoFit.Mode.KEEP_MIDDLE, 1080, 1440, 1080, 2400));
        assertFalse(VideoFit.wants(VideoFit.Mode.KEEP_MIDDLE, 1920, 1080, 1080, 2400));
        assertTrue(VideoFit.wants(VideoFit.Mode.KEEP_MIDDLE, 1080, 1920, 1080, 2136));
    }

    /** TikTok plays into a SurfaceView unless its App AB key comes back 0. */
    @Test public void theSwitchAnswersOnlyThePlayerViewKey() {
        assertEquals(1, VideoFit.videoSurfaceGate(KeepMiddle.SURFACE_VIEW_GATE, 1));
        assertFalse(KeepMiddle.videoInWindow);

        Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.save(true);
        assertEquals(1, VideoFit.videoSurfaceGate("player_enable_something_else", 1));
        assertFalse(KeepMiddle.videoInWindow);
        assertEquals(0, VideoFit.videoSurfaceGate(KeepMiddle.SURFACE_VIEW_GATE, 1));
        assertTrue(KeepMiddle.videoInWindow);

        // Switch off, a 0 from the server or from see-through still puts the video in the window.
        Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.save(false);
        KeepMiddle.resetForTests();
        assertEquals(0, VideoFit.videoSurfaceGate(KeepMiddle.SURFACE_VIEW_GATE, 0));
        assertTrue(KeepMiddle.videoInWindow);
        KeepMiddle.resetForTests();
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        Settings.SEE_THROUGH_VIDEO_IN_WINDOW.save(true);
        assertEquals(1, VideoFit.videoSurfaceGate(KeepMiddle.SURFACE_VIEW_GATE, 1));
        assertTrue(KeepMiddle.videoInWindow);
    }

    @Test public void theVideoIsLaidOutAtTheWindowAndDrawnWithTheShaderOnceTikTokDrawsItInTheWindow() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            FrameLayout container = new FrameLayout(activity);
            View video = new View(activity);
            container.addView(video);
            container.layout(0, 0, 1080, 2136);
            video.setLayoutParams(new FrameLayout.LayoutParams(1080, 1920));
            Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.save(true);
            assertEquals(VideoFit.Mode.KEEP_MIDDLE, VideoFit.mode());
            assertTrue(VideoFit.reshapes(VideoFit.Mode.KEEP_MIDDLE));

            // Before a restart TikTok's player is on its own layer: cropped and stretched instead.
            VideoFitTest.Result first = (VideoFitTest.Result) VideoFit.fitted(video,
                    new VideoFitTest.Result(1080, 1920, 0f, 0f, null));
            assertEquals(VideoFit.blendWidth(1080, 1920, 1080, 2136), first.getWidth());
            assertEquals(2136, first.getHeight());
            assertFalse(KeepMiddle.warped(video));
            assertTrue(VideoFit.Report.INSTANCE.lines().contains("TikTok still plays videos on a layer of "
                    + "their own, so they're cropped and stretched until TikTok restarts"));

            // After it, the window's own size and the shader over it.
            VideoFit.videoSurfaceGate(KeepMiddle.SURFACE_VIEW_GATE, 1);
            VideoFitTest.Result kept = (VideoFitTest.Result) VideoFit.fitted(video,
                    new VideoFitTest.Result(1080, 1920, 0f, 0f, null));
            assertEquals(1080, kept.getWidth());
            assertEquals(2136, kept.getHeight());
            assertTrue("the video was not drawn with the shader", KeepMiddle.warped(video));
            java.util.List<String> lines = VideoFit.Report.INSTANCE.lines();
            assertTrue(lines.contains("Keep the middle unstretched: on"));
            assertTrue(lines.get(lines.size() - 1).startsWith(
                    "Keep the middle, feed: TikTok's size 1080x1920, space 1080x2136, made 1080x2136"));

            // A video it can't keep, and the switch going off, give TikTok's drawing back.
            VideoFit.fitted(video, new VideoFitTest.Result(1080, 1440, 0f, 0f, null));
            assertFalse(KeepMiddle.warped(video));
            VideoFit.fitted(video, new VideoFitTest.Result(1080, 1920, 0f, 0f, null));
            assertTrue(KeepMiddle.warped(video));
            Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.save(false);
            VideoFit.fitted(video, new VideoFitTest.Result(1080, 1920, 0f, 0f, null));
            assertFalse(KeepMiddle.warped(video));

            // The story cell's path draws it the same way.
            Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.save(true);
            VideoFitTest.Result story = new VideoFitTest.Result(1080, 1920);
            assertEquals(1080, VideoFit.fitWidthFor(story, video));
            assertEquals(2136, VideoFit.fittedHeightFor(story));
            VideoFit.fittedTranslation(story, Float.valueOf(0f));
            VideoFit.fittedTranslation(story, Float.valueOf(0f));
            assertTrue(KeepMiddle.warped(video));
        }
    }

    /** A SurfaceView handed over says the video is on its own layer, whatever the key came back as. */
    @Test public void aSurfaceViewIsCroppedAndStretchedAndTheCoverFollows() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            FrameLayout container = new FrameLayout(activity);
            SurfaceView surface = new SurfaceView(activity);
            VideoFitTest.CoverView cover = new VideoFitTest.CoverView(activity);
            container.addView(surface);
            container.addView(cover);
            container.layout(0, 0, 1080, 2136);
            surface.setLayoutParams(new FrameLayout.LayoutParams(1080, 1920));
            cover.setLayoutParams(new FrameLayout.LayoutParams(1080, 1920));
            Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.save(true);
            VideoFit.videoSurfaceGate(KeepMiddle.SURFACE_VIEW_GATE, 1);

            VideoFitTest.Result video = (VideoFitTest.Result) VideoFit.fitted(surface,
                    new VideoFitTest.Result(1080, 1920, 0f, 0f, null));
            assertEquals(VideoFit.blendWidth(1080, 1920, 1080, 2136), video.getWidth());
            assertFalse(KeepMiddle.videoInWindow);
            VideoFitTest.Result shown = (VideoFitTest.Result) VideoFit.fitted(cover,
                    new VideoFitTest.Result(1080, 1920, 0f, 0f, null));
            assertEquals(video.getWidth(), shown.getWidth());
            assertFalse(KeepMiddle.warped(cover));
            assertEquals(java.util.List.of(VideoFitTest.Scale.FIT_XY), cover.scales);
        }
    }

    @Test public void thePageOffersItAndKeepsItApartFromTheOthers() {
        try (var controller = Robolectric.buildActivity(VideoFitTest.TestActivity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            boolean was = SettingsStatus.videoFitEnabled;
            SettingsStatus.videoFitEnabled = true;
            try {
                PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
                activity.setPreferenceScreen(screen);
                new PlaybackPreferenceCategory(activity, screen);
                TwoStatePreference middle =
                        (TwoStatePreference) screen.findPreference(Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.key);
                TwoStatePreference blend =
                        (TwoStatePreference) screen.findPreference(Settings.BLEND_VIDEO_TO_SCREEN.key);
                assertNotNull("the keep the middle row is missing", middle);
                assertEquals("Keep the middle unstretched", middle.getTitle().toString());

                Settings.BLEND_VIDEO_TO_SCREEN.save(true);
                blend.setChecked(true);
                assertTrue(middle.getOnPreferenceChangeListener().onPreferenceChange(middle, true));
                assertFalse("crop and stretch stayed on beside keep the middle", Settings.BLEND_VIDEO_TO_SCREEN.get());
                assertFalse(blend.isChecked());

                Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.save(true);
                middle.setChecked(true);
                assertTrue(blend.getOnPreferenceChangeListener().onPreferenceChange(blend, true));
                assertFalse("keep the middle stayed on beside crop and stretch",
                        Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.get());
                assertFalse(middle.isChecked());
                // TikTok picks its player view as it starts, so the switch asks for a restart.
                assertTrue(Settings.KEEP_MIDDLE_VIDEO_TO_SCREEN.rebootApp);
            } finally {
                SettingsStatus.videoFitEnabled = was;
            }
        }
    }

    /** How fast the shader walks down the video at row {@code y}, by a small step either side. */
    private static double slope(KeepMiddle.Plan plan, double y, double height) {
        double step = 1e-4;
        return (KeepMiddle.sourceY(plan, y + step, height) - KeepMiddle.sourceY(plan, y - step, height)) / (2 * step);
    }
}

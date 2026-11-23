/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.PlaybackPreferenceCategory;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #505: Fill the screen with the video leaves a wide or square video whole on a tall phone
 * (#394), so a landscape video still showed with bars above and below it. Fill wide videos too,
 * right under Fill, crops those to cover the screen as well. Off, #394's whole video stays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FillWideVideosTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    private boolean wasEnabled;

    private static BooleanSetting wide() throws Exception {
        return (BooleanSetting) Settings.class.getField("FILL_WIDE_VIDEOS").get(null);
    }

    @Before public void enable() {
        wasEnabled = SettingsStatus.videoFitEnabled;
        SettingsStatus.videoFitEnabled = true;
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @After public void reset() throws Exception {
        SettingsStatus.videoFitEnabled = wasEnabled;
        wide().resetToDefault();
        Settings.FILL_VIDEO_TO_SCREEN.resetToDefault();
        Settings.FIT_VIDEO_TO_SCREEN.resetToDefault();
        Settings.FIT_FILLS_TALL_SCREEN.resetToDefault();
    }

    @Test public void offByDefaultSoAWideVideoStaysWhole() throws Exception {
        assertEquals(Boolean.FALSE, wide().defaultValue);
        Settings.FILL_VIDEO_TO_SCREEN.save(true);
        // #394's 16:9 video on a 1220x2656 phone, and a square one.
        assertFalse(VideoFit.wants(VideoFit.Mode.FILL, 1220, 686, 1220, 2656));
        assertFalse(VideoFit.wants(VideoFit.Mode.FILL, 1220, 1220, 1220, 2656));
    }

    @Test public void onAWideOrSquareVideoIsCroppedToCoverTheScreen() throws Exception {
        Settings.FILL_VIDEO_TO_SCREEN.save(true);
        wide().save(true);
        assertTrue(VideoFit.wants(VideoFit.Mode.FILL, 1220, 686, 1220, 2656));
        assertEquals(4723, VideoFit.widthFor(VideoFit.Mode.FILL, 1220, 686, 1220, 2656));
        assertEquals(2656, VideoFit.heightFor(VideoFit.Mode.FILL, 1220, 686, 1220, 2656));
        assertTrue(VideoFit.wants(VideoFit.Mode.FILL, 1220, 1220, 1220, 2656));
        assertEquals(2656, VideoFit.widthFor(VideoFit.Mode.FILL, 1220, 1220, 1220, 2656));
        assertEquals(2656, VideoFit.heightFor(VideoFit.Mode.FILL, 1220, 1220, 1220, 2656));
        // A tall video fills as before, and one that already covers the screen is left alone.
        assertTrue(VideoFit.wants(VideoFit.Mode.FILL, 1080, 1920, 1080, 2400));
        assertFalse(VideoFit.wants(VideoFit.Mode.FILL, 1220, 2656, 1220, 2656));
        assertFalse(VideoFit.wants(VideoFit.Mode.FILL, 4723, 2656, 1220, 2656));
    }

    @Test public void theFeedCellGetsTheCroppedSize() throws Exception {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            FrameLayout container = new FrameLayout(activity);
            View video = new View(activity);
            container.addView(video);
            container.layout(0, 0, 1220, 2656);
            video.setLayoutParams(new FrameLayout.LayoutParams(1220, 686));
            VideoFitTest.Result letterboxed = new VideoFitTest.Result(1220, 686, 0f, 985f, new Object());
            Settings.FILL_VIDEO_TO_SCREEN.save(true);
            assertSame("off, TikTok's letterboxed size stays", letterboxed, VideoFit.fitted(video, letterboxed));

            wide().save(true);
            Object filled = VideoFit.fitted(video, letterboxed);
            assertNotSame(letterboxed, filled);
            VideoFitTest.Result copy = (VideoFitTest.Result) filled;
            assertEquals(4723, copy.getWidth());
            assertEquals(2656, copy.getHeight());
            // Centred by the frame, so TikTok's own offset down the screen is gone.
            assertEquals(Float.valueOf(0f), copy.getTranslateY());
            assertEquals(android.view.Gravity.CENTER,
                    ((FrameLayout.LayoutParams) video.getLayoutParams()).gravity);
        }
    }

    @Test public void itBelongsToFillAloneNotFitsTallScreen() throws Exception {
        // Fit's Fill a tall screen instead crops tall videos only; this switch is Fill's.
        wide().save(true);
        Settings.FIT_VIDEO_TO_SCREEN.save(true);
        Settings.FIT_FILLS_TALL_SCREEN.save(true);
        assertFalse(VideoFit.wants(VideoFit.Mode.FILL, 1220, 686, 1220, 2656));
    }

    @Test public void theReportSaysWhenItsOn() throws Exception {
        Settings.FILL_VIDEO_TO_SCREEN.save(true);
        List<String> off = VideoFit.Report.INSTANCE.lines();
        assertFalse(String.join("\n", off), off.contains("Fill wide videos too: on"));
        wide().save(true);
        List<String> on = VideoFit.Report.INSTANCE.lines();
        assertTrue(String.join("\n", on), on.contains("Fill wide videos too: on"));
    }

    @Test @Config(qualifiers = "w373dp-h841dp")
    public void theRowSitsUnderFill() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            activity.setPreferenceScreen(screen);
            new PlaybackPreferenceCategory(activity, screen);
            android.preference.Preference fill = screen.findPreference(Settings.FILL_VIDEO_TO_SCREEN.key);
            android.preference.Preference row = screen.findPreference(wide().key);
            assertNotNull("the Fill wide videos too row is missing", row);
            assertEquals("Fill wide videos too", String.valueOf(row.getTitle()));
            assertEquals("it isn't right under Fill", fill.getOrder() + 1, row.getOrder());
        }
    }
}

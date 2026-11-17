package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.PlaybackPreferenceCategory;

import java.lang.reflect.Method;

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
 * #345: on a folding phone, Fit for the big screen opened up and Fill for the tall front screen.
 * Fill a tall screen instead, under Fit, crops to cover a phone-shaped window and leaves Fit's
 * whole video on a squarer one. The window shapes are a Fold's front screen, its inner one, and a
 * phone turned sideways.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FitFillsTallScreenTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    private boolean wasEnabled;

    private static BooleanSetting tall() throws Exception {
        return (BooleanSetting) Settings.class.getField("FIT_FILLS_TALL_SCREEN").get(null);
    }

    private static boolean fitsWhole() throws Exception {
        Method method = VideoFit.class.getMethod("fitsWhole");
        return (Boolean) method.invoke(null);
    }

    @Before public void enable() {
        wasEnabled = SettingsStatus.videoFitEnabled;
        SettingsStatus.videoFitEnabled = true;
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @After public void reset() throws Exception {
        SettingsStatus.videoFitEnabled = wasEnabled;
        tall().resetToDefault();
        Settings.FIT_VIDEO_TO_SCREEN.resetToDefault();
        Settings.FILL_VIDEO_TO_SCREEN.resetToDefault();
    }

    @Test @Config(qualifiers = "w373dp-h841dp")
    public void theFrontScreenIsFilledOnlyWhenAsked() throws Exception {
        assertEquals(Boolean.FALSE, tall().defaultValue);
        Settings.FIT_VIDEO_TO_SCREEN.save(true);
        assertEquals("off, Fit stays Fit everywhere", VideoFit.Mode.FIT, VideoFit.mode());
        assertTrue(fitsWhole());

        tall().save(true);
        assertEquals(VideoFit.Mode.FILL, VideoFit.mode());
        assertTrue("an opened video gets the strip under its comment bar, as with Fill", VideoFit.fillsScreen());
        assertFalse("the status bar treats the picture as cropped, not shrunk", fitsWhole());

        // Only under Fit: with Fit off it decides nothing.
        Settings.FIT_VIDEO_TO_SCREEN.save(false);
        assertEquals(VideoFit.Mode.LEAVE_ALONE, VideoFit.mode());
    }

    @Test @Config(qualifiers = "w838dp-h900dp")
    public void theBigScreenOpenedUpKeepsTheWholeVideo() throws Exception {
        Settings.FIT_VIDEO_TO_SCREEN.save(true);
        tall().save(true);
        assertEquals(VideoFit.Mode.FIT, VideoFit.mode());
        assertFalse(VideoFit.fillsScreen());
        assertTrue(fitsWhole());
    }

    @Test @Config(qualifiers = "w841dp-h373dp")
    public void aPhoneTurnedSidewaysKeepsTheWholeVideo() throws Exception {
        Settings.FIT_VIDEO_TO_SCREEN.save(true);
        tall().save(true);
        assertEquals(VideoFit.Mode.FIT, VideoFit.mode());
    }

    @Test @Config(qualifiers = "w373dp-h841dp")
    public void theRowSitsUnderFit() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            activity.setPreferenceScreen(screen);
            new PlaybackPreferenceCategory(activity, screen);
            android.preference.Preference fit = screen.findPreference(Settings.FIT_VIDEO_TO_SCREEN.key);
            android.preference.Preference row = screen.findPreference(tall().key);
            assertNotNull("the Fill a tall screen instead row is missing", row);
            assertEquals("Fill a tall screen instead", String.valueOf(row.getTitle()));
            assertEquals("it isn't right under Fit", fit.getOrder() + 1, row.getOrder());
        }
    }
}

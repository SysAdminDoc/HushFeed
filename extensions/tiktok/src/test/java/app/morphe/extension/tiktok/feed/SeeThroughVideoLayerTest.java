package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Stop the blink under the bar (#232): the player's view gate answered with 0, so TikTok draws
 * feed video in its window, only while See-through bottom bar and the switch are both on, and
 * every other App AB int going through untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SeeThroughVideoLayerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        reset();
    }

    @After public void tearDown() {
        reset();
    }

    private static void reset() {
        Settings.SEE_THROUGH_BOTTOM_BAR.resetToDefault();
        Settings.SEE_THROUGH_VIDEO_IN_WINDOW.resetToDefault();
        SeeThroughVideoLayer.resetForTests();
    }

    @Test public void bothSwitchesOnDrawTheVideoInTheWindow() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        Settings.SEE_THROUGH_VIDEO_IN_WINDOW.save(true);

        assertEquals(0, SeeThroughVideoLayer.videoSurfaceGate("player_enable_surface_surfaceview", 1));
        assertEquals(0, SeeThroughVideoLayer.videoSurfaceGate("player_enable_surface_surfaceview", 2));
        assertEquals(0, SeeThroughVideoLayer.videoSurfaceGate("player_enable_surface_surfaceview", 0));
    }

    @Test public void otherKeysGoThroughAsTikTokReadThem() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        Settings.SEE_THROUGH_VIDEO_IN_WINDOW.save(true);

        assertEquals(1, SeeThroughVideoLayer.videoSurfaceGate("player_enable_surface_view", 1));
        assertEquals(7, SeeThroughVideoLayer.videoSurfaceGate("enable_non_personalized_search", 7));
        assertEquals(1, SeeThroughVideoLayer.videoSurfaceGate(null, 1));
    }

    @Test public void theSwitchAloneChangesNothing() {
        Settings.SEE_THROUGH_VIDEO_IN_WINDOW.save(true);

        assertEquals(1, SeeThroughVideoLayer.videoSurfaceGate("player_enable_surface_surfaceview", 1));
    }

    @Test public void seeThroughAloneKeepsTikToksLayer() {
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);

        assertEquals(1, SeeThroughVideoLayer.videoSurfaceGate("player_enable_surface_surfaceview", 1));
    }

    @Test public void theSwitchStartsOffAndAsksForARestart() {
        assertEquals(Boolean.FALSE, Settings.SEE_THROUGH_VIDEO_IN_WINDOW.get());
        assertEquals(true, Settings.SEE_THROUGH_VIDEO_IN_WINDOW.rebootApp);
    }
}

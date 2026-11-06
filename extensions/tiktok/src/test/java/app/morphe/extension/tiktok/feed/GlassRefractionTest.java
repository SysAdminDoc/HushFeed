package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Bend the video at the edges (#231): the switch and where it sits, how wide the bending rim is
 * and how far it reaches on pills of every size, and the plain blur it leaves where it can't run.
 * The shader itself runs on the GPU, which Robolectric doesn't have, so it is checked on the phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class GlassRefractionTest {
    private static final float DELTA = 0.0001f;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @After
    public void tearDown() {
        PausedProcess.set(false);
        Settings.GLASS_REFRACTION.save(false);
    }

    @Test
    public void theSwitchStartsOffAndTakesNoRestart() {
        assertEquals("glass_refraction", Settings.GLASS_REFRACTION.key);
        assertFalse(Settings.GLASS_REFRACTION.defaultValue);
        assertFalse("read on each draw, so no restart", Settings.GLASS_REFRACTION.rebootApp);
        assertFalse("off to begin with", GlassRefraction.wanted());
        Settings.GLASS_REFRACTION.save(true);
        assertTrue(GlassRefraction.wanted());
        PausedProcess.set(true);
        assertFalse("Pause Hushfeed turns it off", GlassRefraction.wanted());
    }

    @Test
    @Config(sdk = 32)
    public void belowAndroid13ThePillKeepsThePlainBlur() {
        Settings.GLASS_REFRACTION.save(true);
        assertFalse(GlassRefraction.supported());
        assertFalse(GlassRefraction.wanted());
        assertNull("the blur it was given comes straight back", new GlassRefraction().over(null, 1000, 160, 80f, 2f));
    }

    @Test
    public void aNormalPillBendsSixteenDpAndReachesTwentyDpIn() {
        assertArrayEquals(new float[]{32f, 40f}, GlassRefraction.size(1000, 160, 2f), DELTA);
        assertArrayEquals("the short side counts, either way round",
                new float[]{32f, 40f}, GlassRefraction.size(160, 1000, 2f), DELTA);
    }

    @Test
    public void aThinPillKeepsAMiddleTheRimLeavesAlone() {
        float[] thin = GlassRefraction.size(1000, 40, 2f);
        assertEquals("three quarters of the half height", 15f, thin[0], DELTA);
        assertEquals("never past the middle", 20f, thin[1], DELTA);
        assertArrayEquals(new float[]{0f, 0f}, GlassRefraction.size(0, 0, 2f), DELTA);
    }

    @Test
    public void theCornersAreNeverRounderThanHalfThePill() {
        assertEquals(80f, GlassRefraction.radius(1000, 160, 999f), DELTA);
        assertEquals(24f, GlassRefraction.radius(1000, 160, 24f), DELTA);
        assertEquals(0f, GlassRefraction.radius(1000, 160, -4f), DELTA);
    }

    @Test
    public void anEmptyPillKeepsThePlainBlur() {
        GlassRefraction refraction = new GlassRefraction();
        assertNull(refraction.over(null, 0, 160, 80f, 2f));
        assertNull(refraction.over(null, 1000, 0, 80f, 2f));
        assertFalse("nothing failed", refraction.broken());
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import static org.junit.Assert.assertEquals;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** #477: the UI size scales TikTok's density factor, and 100 or a size it can't hold leaves it alone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UiScaleTest {
    /** TikTok's text size factors on 48.0.2 (X.03Wq's LIZ and LIZJ), and one of Smaller text sizes' stops. */
    private static final float[] FACTORS = {1f, 1.05f, 1.1f, 1.18f, 0.8f};

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.UI_SCALE_PERCENT.save(100);
    }

    @After public void tearDown() {
        Settings.UI_SCALE_PERCENT.resetToDefault();
    }

    @Test public void theDefaultOf100LeavesEveryFactorExactlyAsItWas() {
        assertEquals(100, (int) Settings.UI_SCALE_PERCENT.defaultValue);
        for (float factor : FACTORS) {
            assertEquals(factor, UiScale.scaleDensityFactor(factor), 0f);
        }
    }

    @Test public void aSizeBelow100ScalesTheFactorByIt() {
        Settings.UI_SCALE_PERCENT.save(80);
        assertEquals(0.8f, UiScale.scaleDensityFactor(1f), 1e-6f);
        assertEquals(0.88f, UiScale.scaleDensityFactor(1.1f), 1e-6f);
        assertEquals("composes with an extra stop", 0.64f, UiScale.scaleDensityFactor(0.8f), 1e-6f);
        Settings.UI_SCALE_PERCENT.save(50);
        assertEquals(0.5f, UiScale.scaleDensityFactor(1f), 1e-6f);
    }

    @Test public void aPercentOutsideTheRangeLeavesTheFactorExactlyAsItWas() {
        int[] outside = {101, 150, 49, 1, 0, -20, Integer.MIN_VALUE, Integer.MAX_VALUE};
        for (int percent : outside) {
            for (float factor : FACTORS) {
                assertEquals(percent + "% of " + factor, factor, UiScale.scale(factor, percent), 0f);
            }
        }
    }

    @Test public void aWrittenValueOutsideTheRangeIsHeldToIt() {
        Settings.UI_SCALE_PERCENT.save(150);
        assertEquals(100, (int) Settings.UI_SCALE_PERCENT.get());
        assertEquals(1.1f, UiScale.scaleDensityFactor(1.1f), 0f);
        Settings.UI_SCALE_PERCENT.save(10);
        assertEquals(50, (int) Settings.UI_SCALE_PERCENT.get());
        assertEquals(0.55f, UiScale.scaleDensityFactor(1.1f), 1e-6f);
    }
}

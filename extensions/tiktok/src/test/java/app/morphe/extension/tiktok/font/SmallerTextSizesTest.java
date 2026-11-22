/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #476: the Text size slider's three extra stops. TikTok's own slider has positions 0 to 3, one
 * per mode; with the switch on it runs 0 to 6, the extra stops at 0 to 2 and TikTok's modes from 3.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SmallerTextSizesTest {
    /** TikTok's own top position, its last mode. */
    private static final int TIKTOK_TOP = 3;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.SMALLER_TEXT_SIZES.save(false);
        Settings.SMALLER_TEXT_SIZE_LEVEL.save(0);
    }

    @After public void tearDown() {
        Settings.SMALLER_TEXT_SIZES.resetToDefault();
        Settings.SMALLER_TEXT_SIZE_LEVEL.resetToDefault();
    }

    @Test public void theLevelSettingHoldsOneValuePerExtraStop() {
        assertEquals(0, Settings.SMALLER_TEXT_SIZE_LEVEL.minimum());
        assertEquals(SmallerTextSizes.EXTRA_STOPS, Settings.SMALLER_TEXT_SIZE_LEVEL.maximum());
    }

    @Test public void theSliderTopGrowsByTheExtraStopsOnlyWithTheSwitchOn() {
        assertEquals(TIKTOK_TOP, SmallerTextSizes.sliderMax(TIKTOK_TOP));
        Settings.SMALLER_TEXT_SIZES.save(true);
        assertEquals(TIKTOK_TOP + 3, SmallerTextSizes.sliderMax(TIKTOK_TOP));
    }

    /** Confirming a position, then opening the page again, puts the slider back where it was. */
    @Test public void everyPositionComesBackThroughTikToksSavedMode() {
        Settings.SMALLER_TEXT_SIZES.save(true);
        for (int position = 0; position <= TIKTOK_TOP + 3; position++) {
            int mode = SmallerTextSizes.commitPosition(position);
            assertTrue("position " + position + " saved mode " + mode, mode >= 0 && mode <= TIKTOK_TOP);
            assertEquals("position " + position, position, SmallerTextSizes.modeToPosition(mode));
        }
    }

    @Test public void tikToksModesSitAboveTheExtraStopsAndPreviewAsThemselves() {
        Settings.SMALLER_TEXT_SIZES.save(true);
        for (int mode = 1; mode <= TIKTOK_TOP; mode++) {
            assertEquals(mode + 3, SmallerTextSizes.modeToPosition(mode));
            assertEquals(mode, SmallerTextSizes.positionToMode(mode + 3));
        }
        assertEquals("mode 0 with no extra stop", 3, SmallerTextSizes.modeToPosition(0));
        for (int position = 0; position <= 3; position++) {
            assertEquals("every extra stop previews as TikTok's smallest", 0, SmallerTextSizes.positionToMode(position));
        }
    }

    @Test public void confirmingAnExtraStopKeepsItAndHandsTikTokMode0() {
        Settings.SMALLER_TEXT_SIZES.save(true);
        float[] factors = {0.8f, 0.87f, 0.93f};
        for (int position = 0; position < 3; position++) {
            assertEquals(0, SmallerTextSizes.commitPosition(position));
            assertEquals(3 - position, (int) Settings.SMALLER_TEXT_SIZE_LEVEL.get());
            assertTrue(SmallerTextSizes.useExtraStop(0));
            assertEquals(factors[position], SmallerTextSizes.extraStopFactor(), 0f);
        }
        // An extra stop answers only while TikTok is on its smallest mode.
        assertFalse(SmallerTextSizes.useExtraStop(1));

        assertEquals("TikTok's smallest", 0, SmallerTextSizes.commitPosition(3));
        assertEquals(0, (int) Settings.SMALLER_TEXT_SIZE_LEVEL.get());
        assertFalse(SmallerTextSizes.useExtraStop(0));
        assertEquals(1f, SmallerTextSizes.extraStopFactor(), 0f);

        assertEquals("TikTok's mode 2", 2, SmallerTextSizes.commitPosition(5));
        assertEquals(0, (int) Settings.SMALLER_TEXT_SIZE_LEVEL.get());
    }

    @Test public void withTheSwitchOffTikToksModeIsLeftAlone() {
        Settings.SMALLER_TEXT_SIZES.save(true);
        SmallerTextSizes.commitPosition(0);
        assertEquals(3, (int) Settings.SMALLER_TEXT_SIZE_LEVEL.get());

        Settings.SMALLER_TEXT_SIZES.save(false);
        assertFalse("an extra stop answered with the switch off", SmallerTextSizes.useExtraStop(0));
        for (int value = 0; value <= TIKTOK_TOP; value++) {
            assertEquals(value, SmallerTextSizes.modeToPosition(value));
            assertEquals(value, SmallerTextSizes.positionToMode(value));
            assertEquals(value, SmallerTextSizes.commitPosition(value));
        }
        assertEquals("the switch off forgot the stop", 3, (int) Settings.SMALLER_TEXT_SIZE_LEVEL.get());

        // Back on with TikTok still on mode 0, the stop picked before is where the slider opens.
        Settings.SMALLER_TEXT_SIZES.save(true);
        assertEquals(0, SmallerTextSizes.modeToPosition(0));
        assertTrue(SmallerTextSizes.useExtraStop(0));
    }
}

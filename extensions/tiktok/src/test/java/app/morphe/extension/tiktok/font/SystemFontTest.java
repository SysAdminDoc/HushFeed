/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Typeface;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Issue #7: the device font stands in for TikTok's, at the same weight, only when the switch is on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SystemFontTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
        Settings.SYSTEM_FONT.save(false);
    }

    @After public void tearDown() {
        Settings.SYSTEM_FONT.resetToDefault();
        SystemFont.resetReportForTests();
        HookStatus.clear();
    }

    @Test public void withTheSwitchOffTheTikTokTypefaceIsUntouched() {
        Typeface tiktok = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        assertSame("the font was swapped with the switch off", tiktok, SystemFont.systemize(tiktok));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("system font"));
    }

    @Test public void withTheSwitchOnADeviceTypefaceIsReturned() {
        Settings.SYSTEM_FONT.save(true);
        Typeface tiktok = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        Typeface replaced = SystemFont.systemize(tiktok);
        assertTrue("no typeface came back with the switch on", replaced != null);
        // The device font is a family of Typeface.DEFAULT, never the SERIF stand-in for TikTok's.
        assertTrue("the TikTok typeface was handed straight back", replaced != tiktok);
    }

    @Test public void nullPassesThroughWithTheSwitchesOff() {
        SystemFont.requested(700, 0);
        assertNull(SystemFont.systemize(null));
    }

    /**
     * #346: patched from the split bundle, TikTok's font files aren't installed, so the engine
     * answers null for every face. The replacement still goes out, at the weight it was asked for.
     */
    @Test public void aFaceTheEngineCannotBuildStillComesOutInTheReplacement() {
        Settings.SYSTEM_FONT.save(true);
        // Legacy typefaces keep the asked weight as their style, so the style is the weight carried.
        SystemFont.requested(700, 0);
        Typeface bold = SystemFont.systemize(null);
        assertNotNull("the engine's null went back with the switch on", bold);
        assertEquals("the requested weight was dropped",
                Typeface.create(Typeface.DEFAULT, 700, false).getStyle(), bold.getStyle());
        assertFalse(bold.isItalic());

        SystemFont.requestedAsset("font/TikTok-Text-Bold.otf");
        assertEquals("the asset's weight was not read from its name",
                Typeface.create(Typeface.DEFAULT, 700, false).getStyle(), SystemFont.systemize(null).getStyle());
    }

    /**
     * The slant half of the #346 case, on the style path below Android 9: Robolectric's Android 9
     * typefaces keep the weight a face is made at but not its slant, so only this path shows it.
     * Its legacy typefaces answer 0 to getStyle(), so the style is read from the face's shadow.
     */
    @Test @Config(sdk = 27) public void aFaceTheEngineCannotBuildKeepsTheRequestedSlant() {
        Settings.SYSTEM_FONT.save(true);
        SystemFont.requested(400, 1);
        Typeface italic = SystemFont.systemize(null);
        assertNotNull(italic);
        assertEquals("the requested slant was dropped", Typeface.ITALIC, styleOf(italic));

        SystemFont.requestedAsset("font/TikTok-Text-BoldItalic.otf");
        assertEquals("the asset's weight or slant was not read from its name",
                Typeface.BOLD_ITALIC, styleOf(SystemFont.systemize(null)));
    }

    private static int styleOf(Typeface typeface) {
        return Shadows.shadowOf(typeface).getFontDescription().getStyle();
    }

    @Test public void aFontFileNameGivesItsWeight() {
        assertEquals(700, SystemFont.weightNamed("font/TikTok-Display-Bold.otf"));
        assertEquals(500, SystemFont.weightNamed("font/TikTok-Text-Medium.otf"));
        assertEquals(400, SystemFont.weightNamed("font/TikTok-Text-Regular.otf"));
        assertEquals(600, SystemFont.weightNamed("font/TikTokSans-SemiBold.otf"));
        assertEquals(400, SystemFont.weightNamed(null));
    }

    @Test public void theExportNamesTheStateWhicheverWayTheSwitchIsSet() {
        SystemFont.systemize(Typeface.DEFAULT);
        assertTrue(HookStatus.report().toString().contains("system font"));
    }
}

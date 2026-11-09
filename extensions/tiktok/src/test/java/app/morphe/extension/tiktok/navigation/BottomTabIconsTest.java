/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
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
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** The bottom bar's icon set (#285): left to TikTok unless picked, and what each pick answers. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class BottomTabIconsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @After public void tearDown() {
        setPaused(false);
        Settings.BOTTOM_TAB_ICON_STYLE.resetToDefault();
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }

    @Test public void itIsLeftToTikTokByDefaultAndNeedsARestart() {
        assertEquals(BottomTabIcons.TIKTOK, Settings.BOTTOM_TAB_ICON_STYLE.defaultValue);
        assertTrue(Settings.BOTTOM_TAB_ICON_STYLE.rebootApp);
        assertEquals(BottomTabIcons.KEEP, BottomTabIcons.version());
    }

    @Test public void eachPickAnswersTheVersionTikTokKnows() {
        Settings.BOTTOM_TAB_ICON_STYLE.save(BottomTabIcons.ORIGINAL);
        assertEquals(0, BottomTabIcons.version());
        Settings.BOTTOM_TAB_ICON_STYLE.save(BottomTabIcons.NEW_STYLE_1);
        assertEquals(2, BottomTabIcons.version());
        Settings.BOTTOM_TAB_ICON_STYLE.save(BottomTabIcons.NEW_STYLE_2);
        assertEquals(5, BottomTabIcons.version());
    }

    @Test public void aValueFromAnotherBuildLeavesItToTikTok() {
        Settings.BOTTOM_TAB_ICON_STYLE.save("something_else");
        assertEquals(BottomTabIcons.KEEP, BottomTabIcons.version());
    }

    @Test public void pausedHushfeedLeavesItToTikTok() {
        Settings.BOTTOM_TAB_ICON_STYLE.save(BottomTabIcons.NEW_STYLE_1);
        setPaused(true);
        assertEquals(BottomTabIcons.KEEP, BottomTabIcons.version());
    }
}

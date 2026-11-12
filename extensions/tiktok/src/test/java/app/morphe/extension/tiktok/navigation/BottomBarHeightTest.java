/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

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

/** The bottom bar's height: TikTok's unless picked, and the bar and what sits on it move together. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class BottomBarHeightTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        BottomBarHeight.resetForTests();
    }

    @After public void tearDown() {
        setPaused(false);
        Settings.BOTTOM_BAR_HEIGHT.resetToDefault();
        BottomBarHeight.resetForTests();
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }

    private static void pick(String choice) {
        Settings.BOTTOM_BAR_HEIGHT.save(choice);
        BottomBarHeight.resetForTests();
    }

    @Test public void tikTokKeepsItsBarByDefaultAndAPickNeedsARestart() {
        assertEquals(BottomBarHeight.TIKTOK, Settings.BOTTOM_BAR_HEIGHT.defaultValue);
        assertTrue(Settings.BOTTOM_BAR_HEIGHT.rebootApp);
        assertEquals(126f, BottomBarHeight.barPixels(126f), 0f);
        assertEquals(126, BottomBarHeight.barPixels(126));
        assertEquals(47f, BottomBarHeight.aboveBar(47f), 0f);
        assertEquals(49, BottomBarHeight.navHeight(49));
    }

    @Test public void eachChoiceIsItsHeightInDp() {
        assertEquals(40, BottomBarHeight.heightFor(BottomBarHeight.SHORTER));
        assertEquals(44, BottomBarHeight.heightFor(BottomBarHeight.SHORT));
        assertEquals(47, BottomBarHeight.heightFor(BottomBarHeight.TIKTOK));
        assertEquals(52, BottomBarHeight.heightFor(BottomBarHeight.TALL));
        assertEquals(56, BottomBarHeight.heightFor(BottomBarHeight.TALLER));
        assertEquals(47, BottomBarHeight.heightFor("100"));
        assertEquals(47, BottomBarHeight.heightFor(null));
    }

    /** At 2.75x the 47 dp bar is 129.25 pixels, and the 49 dp room above it grows with it. */
    @Test public void aTallerBarScalesAndEverythingOnItMovesUp() {
        pick(BottomBarHeight.TALLER);
        assertEquals(129.25f * 56 / 47, BottomBarHeight.barPixels(129.25f), 0.001f);
        assertEquals(154, BottomBarHeight.barPixels(129));
        assertEquals(56f, BottomBarHeight.aboveBar(47f), 0f);
        assertEquals(54f, BottomBarHeight.aboveBar(45f), 0f);
        assertEquals(58, BottomBarHeight.navHeight(49));
    }

    @Test public void aShorterBarLeavesLessRoom() {
        pick(BottomBarHeight.SHORTER);
        assertEquals(110, BottomBarHeight.barPixels(129));
        assertEquals(40f, BottomBarHeight.aboveBar(47f), 0f);
        assertEquals(42, BottomBarHeight.navHeight(49));
        assertEquals(0, BottomBarHeight.navHeight(3));
    }

    /** The height is read once a launch, like TikTok's static, so a pick waits for the restart. */
    @Test public void aPickTakesHoldOnTheNextLaunch() {
        assertEquals(49, BottomBarHeight.navHeight(49));
        Settings.BOTTOM_BAR_HEIGHT.save(BottomBarHeight.TALL);
        assertEquals(49, BottomBarHeight.navHeight(49));
        BottomBarHeight.resetForTests();
        assertEquals(54, BottomBarHeight.navHeight(49));
    }

    /** TikTok can work its static out while its Application is still attaching. */
    @Test public void beforeTheContextArrivesTikTokKeepsItsBarForThatLaunchOnly() {
        Settings.BOTTOM_BAR_HEIGHT.save(BottomBarHeight.SHORT);
        BottomBarHeight.resetForTests();
        Object context = ReflectionHelpers.getStaticField(Utils.class, "context");
        ReflectionHelpers.setStaticField(Utils.class, "context", null);
        try {
            assertEquals(49, BottomBarHeight.navHeight(49));
        } finally {
            ReflectionHelpers.setStaticField(Utils.class, "context", context);
        }
        assertEquals(46, BottomBarHeight.navHeight(49));
    }

    /** At 2x TikTok's bar is 94 pixels and a 40 dp one 80, so the icon and the name each move 7. */
    @Test public void aTabsContentMovesHalfWhatTheBarGaveUp() {
        assertEquals(0, BottomBarHeight.contentShift(2f));
        pick(BottomBarHeight.SHORTER);
        assertEquals(7, BottomBarHeight.contentShift(2f));
        // Once the bar's dimension is read, its own pixels count: 138 * 7 / 94 is 10.3.
        BottomBarHeight.barPixels(138);
        assertEquals(10, BottomBarHeight.contentShift(2f));
        pick(BottomBarHeight.TALLER);
        BottomBarHeight.barPixels(138f);
        assertEquals(-13, BottomBarHeight.contentShift(2f));
    }

    /** On a 40 dp bar the name sat over the icon, each pinned to its own edge of the tab. */
    @Test public void aShorterBarBringsTheIconAndTheNameIn() {
        pick(BottomBarHeight.SHORTER);
        BottomBarHeight.barPixels(138);
        FrameLayout tab = new FrameLayout(RuntimeEnvironment.getApplication());
        View icon = child(tab, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 11, 0);
        View centered = child(tab, Gravity.CENTER, 0, 0);
        View unpinned = child(tab, -1, 5, 0);
        View filling = new View(tab.getContext());
        tab.addView(filling, new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.MATCH_PARENT));

        BottomBarHeight.onBarCell(tab);
        View name = new View(tab.getContext());
        FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        nameParams.bottomMargin = 11;
        tab.addView(name, nameParams);

        assertEquals(1, margins(icon).topMargin);
        assertEquals(1, margins(name).bottomMargin);
        assertEquals(-5, margins(unpinned).topMargin);
        assertEquals(0, margins(centered).topMargin);
        assertEquals(0, margins(centered).bottomMargin);
        assertEquals(0, margins(filling).topMargin);

        // Taken out and put back, it stays where it went the first time.
        tab.removeView(name);
        tab.addView(name);
        assertEquals(1, margins(name).bottomMargin);
    }

    @Test public void aTallerBarMovesThemApartAndTikToksLeavesThemBe() {
        FrameLayout tab = new FrameLayout(RuntimeEnvironment.getApplication());
        View icon = child(tab, Gravity.TOP, 11, 0);
        BottomBarHeight.onBarCell(tab);
        assertEquals(11, margins(icon).topMargin);

        pick(BottomBarHeight.TALLER);
        BottomBarHeight.barPixels(138);
        BottomBarHeight.onBarCell(tab);
        assertEquals(24, margins(icon).topMargin);
    }

    private static View child(FrameLayout tab, int gravity, int top, int bottom) {
        View view = new View(tab.getContext());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2, gravity);
        params.topMargin = top;
        params.bottomMargin = bottom;
        tab.addView(view, params);
        return view;
    }

    private static FrameLayout.LayoutParams margins(View view) {
        return (FrameLayout.LayoutParams) view.getLayoutParams();
    }

    @Test public void pausedHushfeedKeepsTikToksBar() {
        Settings.BOTTOM_BAR_HEIGHT.save(BottomBarHeight.TALLER);
        setPaused(true);
        BottomBarHeight.resetForTests();
        assertEquals(49, BottomBarHeight.navHeight(49));
        assertEquals(129, BottomBarHeight.barPixels(129));
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Matrix;
import android.graphics.RectF;
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

import java.util.IdentityHashMap;
import java.util.Map;

/** The bottom bar's height: TikTok's unless picked, and the bar and what sits on it move together. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class BottomBarHeightTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final BottomBarHeight.Drawing REAL_DRAWING = BottomBarHeight.drawing;

    @Before public void setUp() {
        BottomBarHeight.resetForTests();
    }

    @After public void tearDown() {
        BottomBarHeight.drawing = REAL_DRAWING;
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

    /**
     * TikTok pads its tabs one way while the nav height is its standard one and another way when it
     * isn't, so the standard moves with the nav height when the two matched and stays put otherwise.
     */
    @Test public void theStandardNavHeightMovesOnlyWhenItMatchedTikToks() {
        pick(BottomBarHeight.TALLER);
        assertEquals(49, BottomBarHeight.standardNavHeight(49));
        assertEquals(58, BottomBarHeight.navHeight(49));
        assertEquals(58, BottomBarHeight.standardNavHeight(49));
        assertEquals(42, BottomBarHeight.standardNavHeight(42));
        assertEquals(0, BottomBarHeight.standardNavHeight(0));

        pick(BottomBarHeight.SHORTER);
        assertEquals(42, BottomBarHeight.navHeight(49));
        assertEquals(42, BottomBarHeight.standardNavHeight(49));
    }

    @Test public void tikToksOwnBarLeavesTheStandardNavHeightAlone() {
        assertEquals(49, BottomBarHeight.navHeight(49));
        assertEquals(49, BottomBarHeight.standardNavHeight(49));
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

    /**
     * At 2x TikTok's bar is 94 pixels and a 56 dp one 112, so the icon and the name each move 9 apart.
     * A shorter bar shrinks the tab's content instead, so nothing moves.
     */
    @Test public void aTallerBarsTabContentMovesHalfWhatTheBarGained() {
        assertEquals(0, BottomBarHeight.contentShift(2f));
        pick(BottomBarHeight.TALLER);
        assertEquals(-9, BottomBarHeight.contentShift(2f));
        // Once the bar's dimension is read, its own pixels count: 138 * 9 / 94 is 13.2.
        BottomBarHeight.barPixels(138f);
        assertEquals(-13, BottomBarHeight.contentShift(2f));
        pick(BottomBarHeight.SHORTER);
        BottomBarHeight.barPixels(138);
        assertEquals(0, BottomBarHeight.contentShift(2f));
    }

    /** A tab is as tall as the room above the bar, so a shorter bar shrinks its content by as much. */
    @Test public void aShorterBarsTabContentShrinksWithTheTab() {
        assertEquals(1f, BottomBarHeight.contentScale(), 0f);
        pick(BottomBarHeight.TALLER);
        assertEquals(1f, BottomBarHeight.contentScale(), 0f);
        // Before TikTok works its room out, it goes by the 49 dp most phones get.
        pick(BottomBarHeight.SHORTER);
        assertEquals(42f / 49, BottomBarHeight.contentScale(), 0.0001f);
        pick(BottomBarHeight.SHORT);
        assertEquals(46f / 49, BottomBarHeight.contentScale(), 0.0001f);
        BottomBarHeight.navHeight(52);
        assertEquals(49f / 52, BottomBarHeight.contentScale(), 0.0001f);
    }

    /**
     * On a 40 dp bar the icon and the name didn't fit inside the selected tab's outline at TikTok's
     * size, so they shrink with the tab, each toward its own edge, and land where TikTok's bar would
     * put them at that scale.
     */
    @Test public void aShorterBarShrinksTheIconAndTheNameTowardTheirEdges() {
        pick(BottomBarHeight.SHORTER);
        BottomBarHeight.barPixels(138);
        BottomBarHeight.navHeight(49);
        float scale = 42f / 49;
        BottomBarHeight.drawing = (view, matrix) -> {
            drawings.put(view, matrix);
            return true;
        };
        FrameLayout tab = new FrameLayout(RuntimeEnvironment.getApplication());
        View icon = child(tab, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 11, 0);
        icon.getLayoutParams().width = 60;
        icon.getLayoutParams().height = 60;
        View centered = child(tab, Gravity.CENTER, 0, 0);
        View unpinned = child(tab, -1, 5, 0);
        View filling = new View(tab.getContext());
        tab.addView(filling, new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.MATCH_PARENT));

        BottomBarHeight.onBarCell(tab);
        View name = new View(tab.getContext());
        FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(100, 20, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        nameParams.bottomMargin = 11;
        tab.addView(name, nameParams);
        layOut(tab, 216, 118);

        // Where they're drawn: the icon's top 11 * scale below the tab's, the name's bottom 11 * scale
        // above the tab's, each that much smaller and still centered across the tab.
        assertDrawn(icon, 11 * scale, 60 * scale, 60 * scale);
        assertEquals(118 - 11 * scale, drawn(name).bottom, 0.01f);
        assertEquals(20 * scale, drawn(name).height(), 0.01f);
        assertEquals(108f, drawn(name).centerX(), 0.01f);
        assertEquals(5 * scale, drawn(unpinned).top, 0.01f);
        assertNull(drawings.get(centered));
        assertNull(drawings.get(filling));
        // The margins and the views' own scale stay TikTok's.
        assertEquals(11, margins(icon).topMargin);
        assertEquals(11, margins(name).bottomMargin);
        assertEquals(5, margins(unpinned).topMargin);
        assertEquals(1f, icon.getScaleX(), 0f);

        // TikTok sets an icon's own scale once its tab is picked, and the shrink stays on top of it.
        icon.setScaleX(1f);
        icon.setScaleY(1f);
        assertDrawn(icon, 11 * scale, 60 * scale, 60 * scale);

        // Taken out and put back, it stays where it went the first time.
        tab.removeView(name);
        tab.addView(name);
        layOut(tab, 216, 118);
        assertEquals(118 - 11 * scale, drawn(name).bottom, 0.01f);
    }

    private final Map<View, Matrix> drawings = new IdentityHashMap<>();

    private static void layOut(FrameLayout tab, int width, int height) {
        tab.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        tab.layout(0, 0, width, height);
    }

    /**
     * Where a view is drawn in its tab: Android applies its own scale, then the animation matrix
     * the shrink recorded, then moves it to its place.
     */
    private RectF drawn(View view) {
        RectF rect = new RectF(0, 0, view.getWidth(), view.getHeight());
        Matrix own = new Matrix();
        own.setScale(view.getScaleX(), view.getScaleY(), view.getPivotX(), view.getPivotY());
        own.mapRect(rect);
        Matrix animation = drawings.get(view);
        if (animation != null) animation.mapRect(rect);
        rect.offset(view.getLeft(), view.getTop());
        return rect;
    }

    private void assertDrawn(View view, float top, float width, float height) {
        RectF rect = drawn(view);
        assertEquals(top, rect.top, 0.01f);
        assertEquals(width, rect.width(), 0.01f);
        assertEquals(height, rect.height(), 0.01f);
        assertEquals(108f, rect.centerX(), 0.01f);
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

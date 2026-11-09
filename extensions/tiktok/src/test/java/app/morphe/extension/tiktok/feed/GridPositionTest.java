/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * The drag handle and Top, Back and End buttons on long grids (#378): off until asked for, gone
 * while Hushfeed is paused, up only while a long grid moves, and the jumps they make.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class GridPositionTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class Adapter {
        int count;
        public int getItemCount() { return count; }
    }

    /** The members of RecyclerView the controls use, under the names TikTok's dex keeps. */
    public static class RecyclerView extends FrameLayout {
        final Adapter adapter = new Adapter();
        final List<Integer> jumps = new ArrayList<>();
        int offset;
        int range = 10_000;
        int extent = 1_000;
        int first;
        int stops;

        public RecyclerView(Context context) {
            super(context);
        }

        public Adapter getAdapter() { return adapter; }
        @Override public int computeVerticalScrollOffset() { return offset; }
        @Override public int computeVerticalScrollRange() { return range; }
        @Override public int computeVerticalScrollExtent() { return extent; }
        public void stopScroll() { stops++; }
        public void scrollToPosition(int position) { jumps.add(position); first = position; }
        public int getChildAdapterPosition(View child) { return first + indexOfChild(child); }

        void onScreenRows(int rows) {
            for (int i = 0; i < rows; i++) addView(new View(getContext()));
        }
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @After public void tearDown() {
        setPaused(false);
        Settings.GRID_POSITION_CONTROLS.resetToDefault();
        HookStatus.clear();
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }

    private static void idle(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private static RecyclerView gridOnScreen(Activity activity, int items) {
        FrameLayout frame = new FrameLayout(activity);
        RecyclerView grid = new RecyclerView(activity);
        grid.adapter.count = items;
        grid.onScreenRows(6);
        frame.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activity.setContentView(frame);
        idle(50);
        return grid;
    }

    private static GridPosition.Rail railOf(RecyclerView grid) {
        FrameLayout frame = (FrameLayout) grid.getParent();
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (GridPosition.RAIL_TAG.equals(child.getTag())) return (GridPosition.Rail) child;
        }
        return null;
    }

    private static GridPosition.Rail shownRail(RecyclerView grid) {
        Settings.GRID_POSITION_CONTROLS.save(true);
        GridPosition.show(grid.adapter, grid);
        idle(0);
        GridPosition.Rail rail = railOf(grid);
        assertNotNull(rail);
        return rail;
    }

    private static void scroll(GridPosition.Rail rail, RecyclerView grid, int offset) {
        grid.offset = offset;
        rail.onScrollChanged();
    }

    private static void touch(View view, long down, int action, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 10f, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    @Test public void theSwitchIsOffByDefault() {
        assertEquals("grid_position_controls", Settings.GRID_POSITION_CONTROLS.key);
        assertFalse(Settings.GRID_POSITION_CONTROLS.defaultValue);
    }

    @Test public void aFractionLandsOnAPositionInTheLoadedList() {
        assertEquals(-1, GridPosition.positionAt(0.5f, 0));
        assertEquals(0, GridPosition.positionAt(0f, 100));
        assertEquals(99, GridPosition.positionAt(1f, 100));
        assertEquals(50, GridPosition.positionAt(0.5f, 101));
        assertEquals(0, GridPosition.positionAt(-1f, 100));
        assertEquals(99, GridPosition.positionAt(2f, 100));
    }

    @Test public void theHandleFollowsHowFarThroughTheGridItIs() {
        assertEquals(0f, GridPosition.fractionOf(0, 1_000, 1_000), 0f);
        assertEquals(0f, GridPosition.fractionOf(40, 500, 1_000), 0f);
        assertEquals(0.5f, GridPosition.fractionOf(500, 2_000, 1_000), 0.001f);
        assertEquals(1f, GridPosition.fractionOf(1_500, 2_000, 1_000), 0f);
    }

    @Test public void nothingIsAddedWithTheSwitchOff() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            RecyclerView grid = gridOnScreen(controller.get(), 200);
            GridPosition.show(grid.adapter, grid);
            assertNull(railOf(grid));
        }
    }

    @Test public void pausingHushfeedTakesTheControlsOffTheGrid() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            RecyclerView grid = gridOnScreen(controller.get(), 200);
            shownRail(grid);
            setPaused(true);
            GridPosition.show(grid.adapter, grid);
            assertNull(railOf(grid));
        }
    }

    @Test public void aGridThatMovedOnToAnotherAdapterKeepsNoControls() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            RecyclerView grid = gridOnScreen(controller.get(), 200);
            Settings.GRID_POSITION_CONTROLS.save(true);
            GridPosition.show(new Adapter(), grid);
            assertNull(railOf(grid));
        }
    }

    @Test public void aListHandedToTheGridBringsTheControlsThroughTheSortHook() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            RecyclerView grid = gridOnScreen(controller.get(), 200);
            Settings.GRID_POSITION_CONTROLS.save(true);
            GridSortTest.GridAdapter hooked = new GridSortTest.GridAdapter();
            hooked.mmRecyclerView = new View(controller.get());
            ((FrameLayout) grid.getParent()).addView(hooked.mmRecyclerView);
            GridSort.data(hooked, new ArrayList<>(Collections.emptyList()));
            idle(0);
            assertEquals("a view with no RecyclerView members gets no controls", 1, HookStatus.report().stream()
                    .filter(line -> line.startsWith("grid position")).count());
            assertNull(railOf(grid));
        }
    }

    @Test public void theControlsComeUpWhenALongGridMovesAndLeaveWhenItStops() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            RecyclerView grid = gridOnScreen(controller.get(), 200);
            GridPosition.Rail rail = shownRail(grid);
            assertEquals("hidden until the grid moves", View.INVISIBLE, rail.getVisibility());

            scroll(rail, grid, 0);
            scroll(rail, grid, 300);
            assertEquals(View.VISIBLE, rail.getVisibility());

            idle(GridPosition.IDLE_MS + 500);
            assertEquals(View.INVISIBLE, rail.getVisibility());
        }
    }

    @Test public void aShortGridNeverShowsThem() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            RecyclerView grid = gridOnScreen(controller.get(), GridPosition.MIN_ITEMS - 1);
            GridPosition.Rail rail = shownRail(grid);
            scroll(rail, grid, 0);
            scroll(rail, grid, 300);
            assertEquals(View.INVISIBLE, rail.getVisibility());
        }
    }

    @Test public void endAndTopJumpAndBackReturnsThenSwaps() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            RecyclerView grid = gridOnScreen(controller.get(), 200);
            GridPosition.Rail rail = shownRail(grid);
            grid.first = 40;
            assertEquals("nothing to go back to yet", View.GONE, rail.back.getVisibility());
            assertEquals("a screen reader hears a button", "android.widget.Button",
                    String.valueOf(rail.end.createAccessibilityNodeInfo().getClassName()));
            assertTrue("a keyboard can land on it", rail.end.isFocusable());

            assertTrue(rail.end.performClick());
            assertEquals(Integer.valueOf(199), grid.jumps.get(grid.jumps.size() - 1));
            assertEquals(40, rail.remembered);
            assertEquals(View.VISIBLE, rail.back.getVisibility());
            assertEquals(1, grid.stops);

            assertTrue(rail.back.performClick());
            assertEquals(Integer.valueOf(40), grid.jumps.get(grid.jumps.size() - 1));
            assertEquals("Back undoes itself", 199, rail.remembered);

            assertTrue(rail.top.performClick());
            assertEquals(0, grid.first);
            assertEquals(40, rail.remembered);

            assertTrue("Top from the top keeps the way back", rail.top.performClick());
            assertEquals(40, rail.remembered);

            assertTrue(rail.back.performClick());
            assertEquals("Back can go down from the very top", 40, grid.first);
            assertEquals("and back up to it", 0, rail.remembered);
            assertEquals(View.VISIBLE, rail.back.getVisibility());
        }
    }

    @Test public void aNewListForAnotherAdapterForgetsTheOldSpot() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            RecyclerView grid = gridOnScreen(controller.get(), 200);
            GridPosition.Rail rail = shownRail(grid);
            grid.first = 40;
            rail.end.performClick();
            rail.bind(new Adapter(), grid, null, null);
            assertEquals(-1, rail.remembered);
            assertEquals(View.GONE, rail.back.getVisibility());
        }
    }

    @Test public void tappingTheHandleWithoutDraggingMovesNothing() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            RecyclerView grid = gridOnScreen(controller.get(), 200);
            GridPosition.Rail rail = shownRail(grid);
            long down = SystemClock.uptimeMillis();
            touch(rail.handle, down, MotionEvent.ACTION_DOWN, 300f);
            touch(rail.handle, down, MotionEvent.ACTION_MOVE, 301f);
            touch(rail.handle, down, MotionEvent.ACTION_UP, 301f);
            assertTrue(grid.jumps.isEmpty());
            assertEquals(-1, rail.remembered);
        }
    }

    @Test public void draggingTheHandleMovesTheGridAndRemembersWhereItStarted() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            RecyclerView grid = gridOnScreen(controller.get(), 200);
            GridPosition.Rail rail = shownRail(grid);
            grid.first = 40;
            scroll(rail, grid, 0);
            scroll(rail, grid, 300);
            int height = grid.getHeight();
            assertTrue("the activity laid the grid out", height > 0);

            long down = SystemClock.uptimeMillis();
            touch(rail.handle, down, MotionEvent.ACTION_DOWN, 100f);
            // Past the bottom of the track, wherever the activity's frame starts on the screen.
            touch(rail.handle, down, MotionEvent.ACTION_MOVE, height * 2f);
            assertEquals("a drag to the bottom lands on the last video",
                    Integer.valueOf(199), grid.jumps.get(grid.jumps.size() - 1));
            assertEquals(40, rail.remembered);

            int jumps = grid.jumps.size();
            touch(rail.handle, down, MotionEvent.ACTION_MOVE, height * 2f);
            assertEquals("no repeat for the same video", jumps, grid.jumps.size());
            touch(rail.handle, down, MotionEvent.ACTION_UP, height);
            assertEquals(View.VISIBLE, rail.getVisibility());
        }
    }
}

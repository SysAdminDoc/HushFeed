package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Pages under the bar (#436): with See-through bottom bar, Home's Community grid and other pages
 * that stop at the bar's top ran on over a black band behind the clear bar or the pill. With the
 * switch, the page runs on to the bottom of the screen and its list takes the room back as
 * padding, so its last row still scrolls up clear of the tabs and the pill.
 *
 * <p>The scene is the main window on 47.9.3: a host padded by the gesture bar, the mask under it
 * holding the frame the tab pages live in and the tab bar, and a page that keeps the bar's height
 * free at its bottom as padding.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PageUnderBarTest {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int CELL_ID = 0x7f0a0201;
    private static final int BAR_ID = 0x7f0a0c22;
    private static final int GESTURE_BAR = 48;
    private static final int BAR = 138;
    /** The status bar and the top tabs over a Home page. */
    private static final int TOP_TABS = 360;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        VideoOverlayHider.resolveForTests("view_rootview", CELL_ID);
        VideoOverlayHider.resolveForTests("48.0.2:osm", BAR_ID);
        SeeThroughBottomBar.reset();
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        Settings.FLOATING_BOTTOM_BAR.save(true);
    }

    @After
    public void tearDown() {
        Settings.PAGES_UNDER_BOTTOM_BAR.save(false);
        Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
        Settings.FLOATING_BOTTOM_BAR.save(false);
        SeeThroughBottomBar.reset();
        VideoOverlayHider.resolveForTests("view_rootview", 0);
        VideoOverlayHider.resolveForTests("48.0.2:osm", 0);
    }

    /** The main window: host, mask, the frame the pages hang in, and the tab bar over it. */
    private static final class Window {
        final FrameLayout host;
        final FrameLayout frame;
        final View bar;

        Window(Activity activity) {
            this(activity, GESTURE_BAR);
        }

        /** With {@code gestureBar} as the system's bar under the tabs: three buttons are taller. */
        Window(Activity activity, int gestureBar) {
            host = new FrameLayout(activity);
            host.setPadding(0, 0, 0, gestureBar);
            FrameLayout mask = new FrameLayout(activity);
            host.addView(mask, new FrameLayout.LayoutParams(MATCH, MATCH));
            frame = new FrameLayout(activity);
            mask.addView(frame, new FrameLayout.LayoutParams(MATCH, MATCH));
            bar = new FrameLayout(activity);
            bar.setId(BAR_ID);
            bar.setBackgroundColor(Color.BLACK);
            mask.addView(bar, new FrameLayout.LayoutParams(MATCH, BAR, Gravity.BOTTOM));
        }

        /** A page in the frame that keeps {@code room} free at its bottom, as TikTok's do. */
        FrameLayout page(Activity activity, int room) {
            FrameLayout page = new FrameLayout(activity);
            page.setPadding(0, 0, 0, room);
            frame.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));
            return page;
        }

        void show(Activity activity) {
            activity.setContentView(host);
            layOut(host);
        }

        /** A few passes with a layout after each, as TikTok's layouts would bring them. */
        void settle(Activity activity) {
            for (int i = 0; i < 4; i++) {
                VideoOverlayHider.applyTo(activity);
                layOut(host);
            }
        }

        int strip() {
            return ((SeeThroughBottomBar.FloatingBackground) bar.getBackground()).strip();
        }

        int rise() {
            return SeeThroughBottomBar.riseOver(bar);
        }
    }

    /** A list of rows taller than the screen, the last one returned through {@code last}. */
    private static ScrollView list(Context context, View[] last) {
        ScrollView list = new ScrollView(context);
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.addView(new View(context), new LinearLayout.LayoutParams(MATCH, 3000));
        last[0] = new View(context);
        rows.addView(last[0], new LinearLayout.LayoutParams(MATCH, 120));
        list.addView(rows, new FrameLayout.LayoutParams(MATCH, WRAP));
        return list;
    }

    /** Where the last row ends on screen with the list scrolled all the way down. */
    private static int lastRowBottomScrolledDown(ScrollView list, View last) {
        list.scrollTo(0, 1_000_000);
        int bottom = last.getBottom() + ((View) last.getParent()).getTop() - list.getScrollY();
        for (View at = list; at.getParent() instanceof View && at.getId() != android.R.id.content;
             at = (View) at.getParent()) {
            bottom += at.getTop();
        }
        return bottom;
    }

    @Test
    public void communitysGridRunsOnUnderTheBarAndItsLastRowStillClearsThePill() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Window window = new Window(activity);
            FrameLayout page = window.page(activity, BAR);
            View[] last = new View[1];
            ScrollView grid = list(activity, last);
            page.addView(grid, new FrameLayout.LayoutParams(MATCH, MATCH));
            window.show(activity);

            // The switch off: the page stops at the bar's top over the band, and the pill's
            // strip is painted so the pill stays inside it, as before.
            window.settle(activity);
            int rise = window.rise();
            assertTrue(rise > 0);
            int barTop = window.bar.getTop();
            assertEquals("the page didn't stop over the bar", 2181 - BAR, grid.getBottom());
            assertEquals(Color.BLACK, window.strip());

            Settings.PAGES_UNDER_BOTTOM_BAR.save(true);
            window.settle(activity);
            assertEquals("the grid didn't reach the bottom of the screen", 2181, grid.getBottom());
            assertEquals("the bar moved", barTop, window.bar.getTop());
            assertEquals("the grid's room isn't the part under the bar and the pill",
                    2181 - (barTop - rise), grid.getPaddingBottom());
            assertFalse("rows stop at the grid's padding", grid.getClipToPadding());
            assertFalse("the grid is cut at the page's padding", page.getClipToPadding());
            assertEquals("TikTok's padding was changed", BAR, page.getPaddingBottom());
            assertEquals("the strip still covers the grid", 0, window.strip());
            assertEquals("the last row can't scroll up clear of the pill", barTop - rise,
                    lastRowBottomScrolledDown(grid, last[0]));

            // TikTok sets the grid's padding again: its value, with the room added to it.
            grid.setPadding(0, 0, 0, 20);
            window.settle(activity);
            assertEquals(20 + 2181 - (barTop - rise), grid.getPaddingBottom());
            assertEquals(barTop - rise - 20, lastRowBottomScrolledDown(grid, last[0]));

            // Off again: TikTok's page, with TikTok's own padding.
            Settings.PAGES_UNDER_BOTTOM_BAR.save(false);
            window.settle(activity);
            assertEquals(2181 - BAR, grid.getBottom());
            assertEquals(20, grid.getPaddingBottom());
            assertTrue(grid.getClipToPadding());
            assertTrue(page.getClipToPadding());
            assertEquals(0, ((ViewGroup.MarginLayoutParams) grid.getLayoutParams()).bottomMargin);
            assertEquals(Color.BLACK, window.strip());

            // The see-through bar going takes it back too.
            Settings.PAGES_UNDER_BOTTOM_BAR.save(true);
            window.settle(activity);
            assertEquals(2181, grid.getBottom());
            Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
            window.settle(activity);
            assertEquals(0, ((ViewGroup.MarginLayoutParams) grid.getLayoutParams()).bottomMargin);
            assertEquals(20, grid.getPaddingBottom());
            assertTrue(grid.getClipToPadding());
            assertTrue(page.getClipToPadding());
        }
    }

    /**
     * On three-button navigation the system's bar is about as tall as the tabs: once the frame
     * reaches under it, the grid TikTok stopped at the bar's top ends near the bar's bottom, and it
     * still goes down under the bar.
     */
    @Test
    public void threeButtonNavigationsTallBarStillLetsTheGridRunOnUnderTheTabs() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Window window = new Window(activity, 126);
            FrameLayout page = window.page(activity, BAR);
            View[] last = new View[1];
            ScrollView grid = list(activity, last);
            page.addView(grid, new FrameLayout.LayoutParams(MATCH, MATCH));
            window.show(activity);

            // Coming from the feed: the frame already reaches the bottom of the screen.
            window.settle(activity);
            int rise = window.rise();
            int barTop = window.bar.getTop();
            assertEquals(2181 - 126 - BAR, barTop);
            assertEquals(2181 - BAR, grid.getBottom());

            Settings.PAGES_UNDER_BOTTOM_BAR.save(true);
            window.settle(activity);
            assertEquals("the grid didn't reach the bottom of the screen", 2181, grid.getBottom());
            assertEquals(2181 - (barTop - rise), grid.getPaddingBottom());
            assertEquals(barTop - rise, lastRowBottomScrolledDown(grid, last[0]));
            assertEquals(0, window.strip());
        }
    }

    /**
     * Home's top pager could hold the feed beside the Community grid: that pager never goes down,
     * or the feed's video would go down with it.
     */
    @Test
    public void aPagerHoldingTheFeedBesideThePageKeepsItsPlace() {
        Settings.PAGES_UNDER_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Window window = new Window(activity);
            FrameLayout page = window.page(activity, BAR);
            SidePager pager = new SidePager(activity);
            ScrollView grid = list(activity, new View[1]);
            pager.addView(grid, new ViewGroup.LayoutParams(MATCH, MATCH));
            FrameLayout feedPage = new FrameLayout(activity);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(CELL_ID);
            feedPage.addView(cell, new FrameLayout.LayoutParams(MATCH, MATCH));
            pager.addView(feedPage, new ViewGroup.LayoutParams(MATCH, MATCH));
            page.addView(pager, new FrameLayout.LayoutParams(MATCH, MATCH));
            window.show(activity);

            window.settle(activity);
            assertEquals("the feed's pager went down under the bar", 2181 - BAR, pager.getBottom());
            assertEquals(0, ((ViewGroup.MarginLayoutParams) pager.getLayoutParams()).bottomMargin);
            assertEquals(0, grid.getPaddingBottom());
            assertTrue(page.getClipToPadding());
            assertEquals(Color.BLACK, window.strip());
        }
    }

    /**
     * Profile's grids sit side by side in a pager: the one in front takes the pager down under the
     * bar, and the one beside it gets the same room, so its last row clears the bar once it's
     * swiped in.
     */
    @Test
    public void theListBesideThePageInFrontGetsTheRoomToo() {
        Settings.PAGES_UNDER_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Window window = new Window(activity);
            FrameLayout page = window.page(activity, BAR);
            SidePager pager = new SidePager(activity);
            View[] lastShown = new View[1];
            View[] lastBeside = new View[1];
            ScrollView shown = list(activity, lastShown);
            ScrollView beside = list(activity, lastBeside);
            pager.addView(shown, new ViewGroup.LayoutParams(MATCH, MATCH));
            pager.addView(beside, new ViewGroup.LayoutParams(MATCH, MATCH));
            page.addView(pager, new FrameLayout.LayoutParams(MATCH, MATCH));
            window.show(activity);

            window.settle(activity);
            int rise = window.rise();
            int barTop = window.bar.getTop();
            assertEquals(2181, pager.getBottom());
            assertEquals(2181 - (barTop - rise), shown.getPaddingBottom());
            assertEquals("the list beside it got no room", 2181 - (barTop - rise), beside.getPaddingBottom());
            assertEquals(barTop - rise, lastRowBottomScrolledDown(beside, lastBeside[0]));

            Settings.PAGES_UNDER_BOTTOM_BAR.save(false);
            window.settle(activity);
            assertEquals(2181 - BAR, pager.getBottom());
            assertEquals(0, shown.getPaddingBottom());
            assertEquals(0, beside.getPaddingBottom());
            assertTrue(beside.getClipToPadding());
        }
    }

    /**
     * Home's Community grid on 48.0.2 (Login2, 2026-11-26): the page is a column of the top tabs'
     * room, the body, and a plain band as tall as the bar, so the room under the body is that band
     * and not a margin or a padding. In the body a white backdrop fills the holder behind the
     * refresh layout, and the grid hangs under the refresh header's room, scrolled up over it.
     * The switch changed nothing there: the backdrop read as something over the grid that would
     * go down with it, the band stayed white under the bar, and the pill's strip was painted over
     * the grid's last row.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void communitysColumnRunsOnUnderTheBarPastTheBandUnderIt() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Window window = new Window(activity);
            LinearLayout page = new LinearLayout(activity);
            page.setOrientation(LinearLayout.VERTICAL);
            page.addView(new View(activity), new LinearLayout.LayoutParams(MATCH, TOP_TABS));
            FrameLayout body = new FrameLayout(activity);
            page.addView(body, new LinearLayout.LayoutParams(MATCH, 0, 1f));
            View band = new View(activity);
            band.setBackgroundColor(Color.WHITE);
            page.addView(band, new LinearLayout.LayoutParams(MATCH, BAR));
            window.frame.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));

            FrameLayout holder = new FrameLayout(activity);
            FrameLayout.LayoutParams under = new FrameLayout.LayoutParams(MATCH, MATCH);
            under.topMargin = 8;
            body.addView(holder, under);
            View backdrop = new View(activity);
            backdrop.setBackgroundColor(Color.WHITE);
            holder.addView(backdrop, new FrameLayout.LayoutParams(MATCH, MATCH));
            Refresh refresh = new Refresh(activity);
            holder.addView(refresh, new FrameLayout.LayoutParams(MATCH, MATCH));
            View header = new View(activity);
            header.setVisibility(View.INVISIBLE);
            refresh.addView(header);
            View[] last = new View[1];
            ScrollView grid = list(activity, last);
            grid.getChildAt(0).setBackgroundColor(Color.RED);
            refresh.addView(grid);
            window.show(activity);

            // The switch off: TikTok's page, the band white under the bar and the strip painted.
            window.settle(activity);
            int rise = window.rise();
            int barTop = window.bar.getTop();
            assertEquals(2181 - BAR, body.getBottom());
            assertEquals(Color.WHITE, pixel(window, 20, 2181 - 10));
            assertEquals(Color.BLACK, window.strip());

            Settings.PAGES_UNDER_BOTTOM_BAR.save(true);
            window.settle(activity);
            assertEquals("the grid's page didn't go down under the bar", 2181, body.getBottom());
            assertEquals("the band under the page still covers the grid", Color.RED,
                    pixel(window, 20, 2181 - 10));
            assertEquals("the strip still covers the grid", 0, window.strip());
            assertEquals("the last row can't scroll up clear of the pill", barTop - rise,
                    bottomOnScreen(window, grid, last[0]));

            // Off again: TikTok's page as it was.
            Settings.PAGES_UNDER_BOTTOM_BAR.save(false);
            window.settle(activity);
            assertEquals(2181 - BAR, body.getBottom());
            assertEquals(0, ((ViewGroup.MarginLayoutParams) body.getLayoutParams()).bottomMargin);
            assertEquals(View.VISIBLE, band.getVisibility());
            assertEquals(0, grid.getPaddingBottom());
            assertTrue(grid.getClipToPadding());
            assertEquals(Color.WHITE, pixel(window, 20, 2181 - 10));
            assertEquals(Color.BLACK, window.strip());
        }
    }

    /** A list that ends well above the bar has nothing under it, and stays as it is. */
    @Test
    public void aListThatStopsAboveTheBarIsLeftAlone() {
        Settings.PAGES_UNDER_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Window window = new Window(activity);
            FrameLayout page = window.page(activity, BAR + 300);
            ScrollView list = list(activity, new View[1]);
            page.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));
            window.show(activity);

            window.settle(activity);
            assertEquals(2181 - BAR - 300, list.getBottom());
            assertEquals(0, list.getPaddingBottom());
            assertTrue(list.getClipToPadding());
            assertTrue(page.getClipToPadding());
            assertEquals(Color.BLACK, window.strip());
        }
    }

    /** The feed's own pager holds its cells: it keeps its place and the pill stays clear over it. */
    @Test
    public void theFeedsPagerIsNeverTakenForAPage() {
        Settings.PAGES_UNDER_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Window window = new Window(activity);
            FrameLayout page = window.page(activity, BAR);
            ScrollView feed = new ScrollView(activity);
            LinearLayout cells = new LinearLayout(activity);
            cells.setOrientation(LinearLayout.VERTICAL);
            FrameLayout cell = new FrameLayout(activity);
            cell.setId(CELL_ID);
            cells.addView(cell, new LinearLayout.LayoutParams(MATCH, 4000));
            feed.addView(cells, new FrameLayout.LayoutParams(MATCH, WRAP));
            page.addView(feed, new FrameLayout.LayoutParams(MATCH, MATCH));
            window.show(activity);

            window.settle(activity);
            assertEquals(2181 - BAR, feed.getBottom());
            assertEquals(0, feed.getPaddingBottom());
            assertTrue(feed.getClipToPadding());
            assertEquals("the strip covers the video", 0, window.strip());
        }
    }

    /**
     * A button over the list's bottom would go down under the bar with the view holding both, so
     * that page is left as TikTok has it.
     */
    @Test
    public void aButtonOverTheListKeepsItsPageWhereItIs() {
        Settings.PAGES_UNDER_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Window window = new Window(activity);
            FrameLayout page = window.page(activity, BAR);
            FrameLayout holder = new FrameLayout(activity);
            ScrollView list = list(activity, new View[1]);
            holder.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));
            View button = new View(activity);
            holder.addView(button, new FrameLayout.LayoutParams(100, 100, Gravity.BOTTOM | Gravity.END));
            page.addView(holder, new FrameLayout.LayoutParams(MATCH, MATCH));
            window.show(activity);

            window.settle(activity);
            assertEquals(2181 - BAR, holder.getBottom());
            assertEquals(2181 - BAR, button.getBottom() + holder.getTop());
            assertEquals(0, list.getPaddingBottom());
            assertTrue(page.getClipToPadding());
            assertEquals(Color.BLACK, window.strip());
        }
    }

    /**
     * A page whose layout ignores margins keeps its list where it was, so the room is put back on
     * the next pass and the page is left alone from then on: no padding under a list that never
     * went down.
     */
    @Test
    public void aLayoutThatIgnoresTheMarginIsPutBackForGood() {
        Settings.PAGES_UNDER_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Window window = new Window(activity);
            IgnoresMargins page = new IgnoresMargins(activity);
            page.setPadding(0, 0, 0, BAR);
            window.frame.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));
            ScrollView list = list(activity, new View[1]);
            page.addView(list, new FrameLayout.LayoutParams(MATCH, MATCH));
            window.show(activity);

            window.settle(activity);
            window.settle(activity);
            assertEquals(2181 - BAR, list.getBottom());
            assertEquals(0, ((ViewGroup.MarginLayoutParams) list.getLayoutParams()).bottomMargin);
            assertEquals(0, list.getPaddingBottom());
            assertTrue(list.getClipToPadding());
            assertTrue(page.getClipToPadding());
            assertEquals(Color.BLACK, window.strip());
        }
    }

    /** Where {@code last} ends on screen with {@code list} scrolled all the way down, scrolls above it included. */
    private static int bottomOnScreen(Window window, ScrollView list, View last) {
        list.scrollTo(0, 1_000_000);
        int[] at = new int[2];
        int[] host = new int[2];
        last.getLocationInWindow(at);
        window.host.getLocationInWindow(host);
        return at[1] - host[1] + last.getHeight();
    }

    /** The color the window draws at {@code x}, {@code y}. */
    private static int pixel(Window window, int x, int y) {
        Bitmap bitmap = Bitmap.createBitmap(1080, 2181, Bitmap.Config.ARGB_8888);
        window.host.draw(new Canvas(bitmap));
        int color = bitmap.getPixel(x, y);
        bitmap.recycle();
        return color;
    }

    private static void layOut(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2181, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2181);
    }

    /** Lays its pages side by side over its whole height, the first one in front. */
    private static final class SidePager extends ViewGroup {
        SidePager(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).layout(i * (r - l), 0, (i + 1) * (r - l), b - t);
            }
        }
    }

    /**
     * TikTok's pull to refresh on Community's grid: the header's room on top, the list under it as
     * tall as the layout itself, scrolled up over the header.
     */
    private static final class Refresh extends ViewGroup {
        static final int HEADER = 88;

        Refresh(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = MeasureSpec.getSize(heightMeasureSpec);
            getChildAt(0).measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(HEADER, MeasureSpec.EXACTLY));
            getChildAt(1).measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            getChildAt(0).layout(0, 0, r - l, HEADER);
            getChildAt(1).layout(0, HEADER, r - l, HEADER + b - t);
            scrollTo(0, HEADER);
        }
    }

    /** Lays its child out above its padding whatever the child's margins say. */
    private static final class IgnoresMargins extends FrameLayout {
        IgnoresMargins(Context context) {
            super(context);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).layout(0, 0, r - l, b - t - getPaddingBottom());
            }
        }
    }
}

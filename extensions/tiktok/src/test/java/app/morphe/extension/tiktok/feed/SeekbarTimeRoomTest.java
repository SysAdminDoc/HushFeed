package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeRawAd;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.seekbar.SeekbarTime;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The time over the bar makes room for itself on the main feed (#173). TikTok's music disc sits
 * on the bar's right end (S22, 2026-10-21), so with the time set Above it never found the spot
 * free and always stayed beside the bar. Now the time asks for the room the disc leaves short and
 * every cell's widget layer, caption, rail and disc together, goes up by that much. Beside, off
 * and Pause give it back, a swipe keeps it, and anything outside the layer still keeps the time
 * beside the bar.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SeekbarTimeRoomTest {
    private static final int CELL_ID = 0x7f0a0d01;
    private static final int LAYER_ID = 0x7f0a0d02;
    private static final int STRIP_ID = 0x7f0a0d03;
    private static final int COLUMN_ID = 0x7f0a0d04;
    private static final int TABS_ID = 0x7f0a0d05;
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 2181;
    /** The bar's row, 79 tall at the window's foot, under a 200 px box that holds the spot over it. */
    private static final int ROW = 79;
    private static final int ROW_TOP = HEIGHT - ROW;
    private static final int SPOT_TOP = ROW_TOP - ROW;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        VideoOverlayHider.resolveForTests("view_rootview", CELL_ID);
        VideoOverlayHider.resolveForTests("widget_container", LAYER_ID);
        SeeThroughBottomBar.reset();
        SettingsStatus.videoOverlaysEnabled = true;
    }

    @After
    public void tearDown() {
        Settings.SEEKBAR_TIME.save(false);
        Settings.SEEKBAR_TIME_PLACEMENT.save("beside");
        Settings.SEE_THROUGH_BOTTOM_BAR.save(false);
        PausedProcess.set(false);
        SeeThroughBottomBar.reset();
        SettingsStatus.videoOverlaysEnabled = false;
        VideoOverlayHider.resolveForTests("view_rootview", 0);
        VideoOverlayHider.resolveForTests("widget_container", 0);
        VideoOverlayHider.resolveForTests("47.6.3:cot", 0);
        VideoOverlayHider.resolveForTests("viewpager_container", 0);
        VideoOverlayHider.resolveForTests("47.6.3:oo3", 0);
    }

    /** TikTok's pager in short: the cells stacked a page apart and scrolled to the current one. */
    private static final class StackedPager extends FrameLayout {
        /** A height the cells keep whatever the pager's own, as TikTok's do until a later layout; 0 follows the pager. */
        int cellHeight;

        StackedPager(Context context) {
            super(context);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int page = bottom - top;
            int cell = cellHeight > 0 ? cellHeight : page;
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).layout(0, i * page, right - left, i * page + cell);
            }
        }
    }

    /**
     * A feed: cells in a pager, each with its widget layer holding a caption on the left and the
     * music disc on the right, [discGaps] px above the cell's bottom; and the bar's row outside
     * the cells, the way TikTok shares one bar between them.
     */
    private static final class Feed {
        final FrameLayout root;
        final StackedPager pager;
        final FrameLayout[] layers;
        final ImageView[] discs;
        final LinearLayout bar;
        /** The view outside the cells that holds the bar's row and the spot over it. */
        final FrameLayout strip;

        Feed(Activity activity, ViewGroup pagerParent, int... discGaps) {
            root = new FrameLayout(activity);
            pager = new StackedPager(activity);
            layers = new FrameLayout[discGaps.length];
            discs = new ImageView[discGaps.length];
            for (int i = 0; i < discGaps.length; i++) {
                FrameLayout cell = new FrameLayout(activity);
                cell.setId(CELL_ID);
                layers[i] = new FrameLayout(activity);
                layers[i].setId(LAYER_ID);
                cell.addView(layers[i]);
                TextView caption = new TextView(activity);
                caption.setText("a caption that stops short of the rail");
                FrameLayout.LayoutParams captionAt = new FrameLayout.LayoutParams(700, 120, Gravity.BOTTOM | Gravity.START);
                captionAt.bottomMargin = 60;
                layers[i].addView(caption, captionAt);
                discs[i] = new ImageView(activity);
                discs[i].setImageDrawable(new ColorDrawable(Color.WHITE));
                // Icon-sized against Robolectric's 320 px screen, where a third is 106.
                FrameLayout.LayoutParams discAt = new FrameLayout.LayoutParams(100, 100, Gravity.BOTTOM | Gravity.END);
                discAt.bottomMargin = discGaps[i];
                layers[i].addView(discs[i], discAt);
                pager.addView(cell);
            }
            if (pagerParent == null) {
                root.addView(pager);
            } else {
                pagerParent.addView(pager, 0, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
                root.addView(pagerParent);
            }
            bar = new LinearLayout(activity);
            FrameLayout seekFrame = new FrameLayout(activity);
            seekFrame.addView(new SeekBar(activity), new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            bar.addView(seekFrame, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout box = new FrameLayout(activity);
            box.addView(bar, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            strip = new FrameLayout(activity);
            strip.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ROW, Gravity.BOTTOM));
            root.addView(strip, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200, Gravity.BOTTOM));
            activity.setContentView(root);
            layOut();
        }

        void layOut() {
            root.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, WIDTH, HEIGHT);
        }

        /** A progress tick for video [aid] at 30 of 60 seconds, which places the time. */
        TextView tick(String aid) {
            SeekbarTime.onPlayTick(50f, 30_000L, new Post(aid));
            SeekbarTime.onBarProgress(bar, 50f);
            layOut();
            TextView label = (TextView) bar.getChildAt(1);
            ((ViewTreeObserver.OnPreDrawListener) label).onPreDraw();
            return label;
        }

        boolean above() {
            View label = bar.getChildAt(1);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) label.getLayoutParams();
            return params.getMarginStart() == -params.width && params.width > 0;
        }
    }

    private static final class Post extends Aweme {
        private final String aid;

        Post(String aid) {
            this.aid = aid;
        }

        @Override public String getAid() { return aid; }
        @Override public boolean isAd() { return false; }
        @Override public AwemeRawAd getAwemeRawAd() { return null; }
        @Override public int getAwemeType() { return 0; }
    }

    @Test
    public void theDiscMakesWayAndTheTimeGoesAboveThenBesideAndOffPutItBack() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            // The disc ends 40 px above the cell's bottom, at 2141: 118 into the spot.
            Feed feed = new Feed(activity, null, 40);

            feed.tick("1");
            assertEquals("the layer goes up by what the disc reached into the spot",
                    -(HEIGHT - 40 - SPOT_TOP), feed.layers[0].getTranslationY(), 0f);
            assertTrue("and the time is over the bar", feed.above());
            assertFalse(feed.bar.getClipChildren());
            assertFalse("the strip holding the spot would cut the time off", feed.strip.getClipChildren());
            assertEquals("the disc ends where the spot starts", SPOT_TOP,
                    feed.discs[0].getBottom() + feed.layers[0].getTranslationY(), 0f);

            Settings.SEEKBAR_TIME_PLACEMENT.save("beside");
            feed.tick("2");
            assertFalse(feed.above());
            assertEquals("beside gives the room back", 0f, feed.layers[0].getTranslationY(), 0f);
            assertTrue(feed.bar.getClipChildren());
            assertTrue(feed.strip.getClipChildren());

            Settings.SEEKBAR_TIME_PLACEMENT.save("above");
            feed.tick("3");
            assertEquals(-118f, feed.layers[0].getTranslationY(), 0f);

            Settings.SEEKBAR_TIME.save(false);
            SeekbarTime.onBarProgress(feed.bar, 50f);
            assertEquals(1, feed.bar.getChildCount());
            assertEquals("off gives the room back", 0f, feed.layers[0].getTranslationY(), 0f);
        }
    }

    @Test
    public void pauseGivesTheRoomBackOnTheNextPass() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Feed feed = new Feed(activity, null, 40);
            feed.tick("1");
            assertEquals(-118f, feed.layers[0].getTranslationY(), 0f);

            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0f, feed.layers[0].getTranslationY(), 0f);
        }
    }

    @Test
    public void everyCellGoesUpSoTheNextVideoArrivesLiftedAndASwipeKeepsTheRoom() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            // The next video's disc sits lower, 10 px above its cell's bottom.
            Feed feed = new Feed(activity, null, 40, 10);
            feed.tick("1");
            assertEquals(-118f, feed.layers[0].getTranslationY(), 0f);
            assertEquals("the next cell is lifted before it arrives", -118f, feed.layers[1].getTranslationY(), 0f);

            // Halfway up to the next video, that cell's top at 701: nothing is settled to measure.
            feed.pager.scrollTo(0, HEIGHT - 701);
            feed.tick("2");
            assertTrue("the time stays where it was", feed.above());
            assertEquals(-118f, feed.layers[1].getTranslationY(), 0f);

            feed.pager.scrollTo(0, HEIGHT);
            feed.tick("3");
            assertEquals("the settled video's own room", -148f, feed.layers[1].getTranslationY(), 0f);
            assertEquals(-148f, feed.layers[0].getTranslationY(), 0f);
            assertTrue(feed.above());
        }
    }

    @Test
    public void textOutsideTheLayerStillKeepsTheTimeBesideTheBarWithNoLift() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Feed feed = new Feed(activity, null, 40);
            TextView banner = new TextView(activity);
            banner.setText("something TikTok put on the bar");
            FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(WIDTH, 40, Gravity.TOP);
            at.topMargin = SPOT_TOP + 20;
            feed.root.addView(banner, at);
            feed.layOut();

            feed.tick("1");
            assertFalse(feed.above());
            assertEquals(0f, feed.layers[0].getTranslationY(), 0f);
        }
    }

    @Test
    public void aDiscBelowTheSpotLeavesTheTimeAboveWithNoLift() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            // The disc hangs 29 px past the cell's bottom, at 2110 to 2210: on the bar, just under
            // the spot, so it never reaches it and no room is asked for.
            Feed feed = new Feed(activity, null, -29);

            feed.tick("1");
            assertTrue(feed.above());
            assertEquals(0f, feed.layers[0].getTranslationY(), 0f);
        }
    }

    @Test
    public void withoutTheOverlayPatchNoRoomIsAskedForAndTheTimeStaysBeside() {
        SettingsStatus.videoOverlaysEnabled = false;
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Feed feed = new Feed(activity, null, 40);

            feed.tick("1");
            assertFalse("the disc is still in the spot", feed.above());
            assertEquals("and nothing lifts it", 0f, feed.layers[0].getTranslationY(), 0f);
        }
    }

    @Test
    public void moreThanFourRowsOfRoomIsNotAskedForAndTheTimeStaysBeside() {
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            // Text in the layer over the spot that hangs 200 px past the cell's bottom: clearing
            // the spot would take 358 px, past four rows' 316.
            Feed feed = new Feed(activity, null, 40);
            TextView hanging = new TextView(activity);
            hanging.setText("a column of text running off the bottom");
            FrameLayout.LayoutParams hangingAt = new FrameLayout.LayoutParams(100, 400, Gravity.BOTTOM | Gravity.END);
            hangingAt.bottomMargin = -200;
            feed.layers[0].addView(hanging, hangingAt);
            feed.layOut();

            feed.tick("1");
            assertFalse(feed.above());
            assertEquals(0f, feed.layers[0].getTranslationY(), 0f);
        }
    }

    /**
     * See-through bottom bar lifts the layer by the strip's 138 px already (#145). The time's room
     * is measured from TikTok's own layout, so it counts that lift: the layer takes the larger of
     * the two, never their sum, and Beside leaves the see-through lift as it was.
     */
    @Test
    public void withSeeThroughBottomBarTheLayerTakesTheLargerLift() {
        VideoOverlayHider.resolveForTests("47.6.3:cot", STRIP_ID);
        VideoOverlayHider.resolveForTests("viewpager_container", COLUMN_ID);
        VideoOverlayHider.resolveForTests("47.6.3:oo3", TABS_ID);
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        Settings.SEEKBAR_TIME.save(true);
        Settings.SEEKBAR_TIME_PLACEMENT.save("above");
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setId(COLUMN_ID);
            column.setOrientation(LinearLayout.VERTICAL);
            View strip = new View(activity);
            strip.setId(STRIP_ID);
            column.addView(strip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 138));
            // The disc sits on the cell's bottom edge.
            Feed feed = new Feed(activity, column, 0);
            View tabs = new FrameLayout(activity);
            tabs.setId(TABS_ID);
            tabs.setBackgroundColor(Color.BLACK);
            feed.root.addView(tabs, 1, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 138, Gravity.BOTTOM));
            feed.layOut();
            VideoOverlayHider.applyTo(activity);
            feed.layOut();
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, strip.getVisibility());
            assertEquals(-138f, feed.layers[0].getTranslationY(), 0f);

            feed.tick("1");
            assertEquals("158 from TikTok's layout, not 138 more", -(HEIGHT - SPOT_TOP),
                    feed.layers[0].getTranslationY(), 0f);
            assertTrue(feed.above());

            Settings.SEEKBAR_TIME_PLACEMENT.save("beside");
            feed.tick("2");
            assertEquals("the see-through lift stays", -138f, feed.layers[0].getTranslationY(), 0f);
            assertEquals(View.GONE, strip.getVisibility());
        }
    }

    /**
     * TikTok shows the strip again as each new video settles (#232). The pass measured the lift
     * while the strip still showed, took the layer down, then hid the strip and gave the pager its
     * room back, so the caption, the buttons and the search bar sat a strip's height low over the
     * tabs until some later layout (Login2, 2026-10-23: about half a second on every swipe).
     */
    @Test
    public void aStripTikTokShowsAgainOnASwipeLeavesTheLayerLiftedAfterOnePass() {
        VideoOverlayHider.resolveForTests("47.6.3:cot", STRIP_ID);
        VideoOverlayHider.resolveForTests("viewpager_container", COLUMN_ID);
        VideoOverlayHider.resolveForTests("47.6.3:oo3", TABS_ID);
        Settings.SEE_THROUGH_BOTTOM_BAR.save(true);
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            LinearLayout column = new LinearLayout(activity);
            column.setId(COLUMN_ID);
            column.setOrientation(LinearLayout.VERTICAL);
            View strip = new View(activity);
            strip.setId(STRIP_ID);
            column.addView(strip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 138));
            Feed feed = new Feed(activity, column, 0, 0);
            View tabs = new FrameLayout(activity);
            tabs.setId(TABS_ID);
            tabs.setBackgroundColor(Color.BLACK);
            feed.root.addView(tabs, 1, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 138, Gravity.BOTTOM));
            feed.layOut();
            VideoOverlayHider.applyTo(activity);
            feed.layOut();
            VideoOverlayHider.applyTo(activity);
            assertEquals(-138f, feed.layers[0].getTranslationY(), 0f);
            assertEquals(-138f, feed.layers[1].getTranslationY(), 0f);

            for (int swipe = 0; swipe < 3; swipe++) {
                strip.setVisibility(View.VISIBLE);
                feed.layOut();
                assertEquals(HEIGHT - 138, feed.pager.getHeight());
                VideoOverlayHider.applyTo(activity);
                assertEquals(View.GONE, strip.getVisibility());
                assertEquals("swipe " + swipe + ": the pager didn't get the room back", HEIGHT, feed.pager.getHeight());
                for (FrameLayout layer : feed.layers) {
                    assertEquals("swipe " + swipe + ": the caption sat a strip's height low until the next layout",
                            -138f, layer.getTranslationY(), 0f);
                }
            }

            // TikTok shows the strip again from its own layout of the column, the pass's included,
            // and the cells keep their full height until a later layout (Login2, 2026-10-23).
            feed.pager.cellHeight = HEIGHT;
            View.OnLayoutChangeListener showAgain = (view, l, t, r, b, oldL, oldT, oldR, oldB) ->
                    strip.setVisibility(View.VISIBLE);
            column.addOnLayoutChangeListener(showAgain);
            strip.setVisibility(View.VISIBLE);
            feed.layOut();
            assertEquals(HEIGHT - 138, feed.pager.getHeight());
            assertEquals(HEIGHT, feed.pager.getChildAt(0).getHeight());
            VideoOverlayHider.applyTo(activity);
            assertEquals("TikTok's layout didn't show the strip again", View.VISIBLE, strip.getVisibility());
            for (FrameLayout layer : feed.layers) {
                assertEquals("the lift followed the strip while the cells still reached over its place",
                        -138f, layer.getTranslationY(), 0f);
            }
        }
    }
}

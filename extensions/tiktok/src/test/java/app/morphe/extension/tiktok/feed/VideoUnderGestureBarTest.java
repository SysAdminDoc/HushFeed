package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * TikTok pads a host near the top of each page by the gesture bar on 47.7.3, so the feed under its
 * tabs and an opened video under its comment bar stopped over a strip of the window's black (#408).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class VideoUnderGestureBarTest {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    @Test
    public void theFeedRunsOnUnderTheGestureBarWhileTheTabsStayAboveIt() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout host = new FrameLayout(activity);
            host.setPadding(0, 0, 0, 48);
            FrameLayout mask = new FrameLayout(activity);
            host.addView(mask, new FrameLayout.LayoutParams(MATCH, MATCH));
            FrameLayout feed = new FrameLayout(activity);
            mask.addView(feed, new FrameLayout.LayoutParams(MATCH, MATCH));
            View caption = child(feed);
            View tabs = new View(activity);
            mask.addView(tabs, new FrameLayout.LayoutParams(MATCH, 98, Gravity.BOTTOM));
            activity.setContentView(host);
            layOut(host);
            assertEquals(1552, feed.getBottom());

            assertEquals("the caption moved before the feed grew", 0,
                    VideoUnderGestureBar.FEED.apply(activity, tabs, true));
            layOut(host);
            assertEquals("the feed stops over the gesture bar", 1600, feed.getBottom());
            assertEquals("the tabs went down under the gesture bar", 1552, tabs.getBottom());
            assertEquals("TikTok's padding was changed", 48, host.getPaddingBottom());
            assertFalse("the feed is cut at the host's padding", host.getClipToPadding());
            assertFalse("the feed is cut at the mask's padding", mask.getClipToPadding());
            assertEquals(48, VideoUnderGestureBar.FEED.apply(activity, tabs, true));
            assertEquals("the caption stays where it was", 48, VideoUnderGestureBar.reachOver(caption));
            assertEquals("the tabs went up", 0, VideoUnderGestureBar.reachOver(tabs));

            // Another tab in front: everything as TikTok had it.
            assertEquals(0, VideoUnderGestureBar.FEED.apply(activity, tabs, false));
            layOut(host);
            assertEquals(48, host.getPaddingBottom());
            assertTrue(host.getClipToPadding());
            assertEquals(0, mask.getPaddingBottom());
            assertTrue(mask.getClipToPadding());
            assertEquals(1552, mask.getBottom());
            assertEquals(1552, feed.getBottom());
            assertEquals(1552, tabs.getBottom());
            assertFalse(VideoUnderGestureBar.FEED.holds());
        }
    }

    @Test
    public void theKeyboardsPaddingIsLeftToTikTok() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout host = new FrameLayout(activity);
            host.setPadding(0, 0, 0, 48);
            FrameLayout mask = new FrameLayout(activity);
            host.addView(mask, new FrameLayout.LayoutParams(MATCH, MATCH));
            FrameLayout feed = new FrameLayout(activity);
            mask.addView(feed, new FrameLayout.LayoutParams(MATCH, MATCH));
            View tabs = new View(activity);
            mask.addView(tabs, new FrameLayout.LayoutParams(MATCH, 98, Gravity.BOTTOM));
            activity.setContentView(host);
            layOut(host);
            VideoUnderGestureBar.FEED.apply(activity, tabs, true);
            layOut(host);

            host.setPadding(0, 0, 0, 900);
            assertEquals(0, VideoUnderGestureBar.FEED.apply(activity, tabs, true));
            layOut(host);
            assertEquals("the keyboard's room was taken", 900, host.getPaddingBottom());
            assertEquals(0, mask.getPaddingBottom());
            assertEquals(700, mask.getBottom());
            assertEquals(700, feed.getBottom());
            assertFalse(VideoUnderGestureBar.FEED.holds());
        }
    }

    /**
     * Login2, 47.7.3, a video opened from search: the padded frame holds the pager four levels
     * down, and the pager gives its page plain layout params.
     */
    @Test
    public void anOpenedVideoRunsOnUnderTheGestureBarWhileItsCommentBarStaysAboveIt() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout host = new FrameLayout(activity);
            host.setPadding(0, 0, 0, 48);
            View sibling = new View(activity);
            host.addView(sibling, new FrameLayout.LayoutParams(MATCH, MATCH));
            FrameLayout pagerFrame = new FrameLayout(activity);
            host.addView(pagerFrame, new FrameLayout.LayoutParams(MATCH, MATCH));
            FrameLayout inner = new FrameLayout(activity);
            pagerFrame.addView(inner, new FrameLayout.LayoutParams(MATCH, MATCH));
            Pager pager = new Pager(activity);
            inner.addView(pager, new FrameLayout.LayoutParams(MATCH, MATCH));
            FrameLayout seekMask = new FrameLayout(activity);
            pager.addView(seekMask, new ViewGroup.LayoutParams(MATCH, MATCH));
            FrameLayout mask = new FrameLayout(activity);
            seekMask.addView(mask, new FrameLayout.LayoutParams(MATCH, MATCH));
            FrameLayout videos = new FrameLayout(activity);
            mask.addView(videos, new FrameLayout.LayoutParams(MATCH, MATCH));
            View layer = new View(activity);
            videos.addView(layer, new FrameLayout.LayoutParams(MATCH, MATCH));
            FrameLayout slot = new FrameLayout(activity);
            mask.addView(slot, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
            FrameLayout above = new FrameLayout(activity);
            slot.addView(above, new FrameLayout.LayoutParams(MATCH, WRAP));
            FrameLayout row = new FrameLayout(activity);
            slot.addView(row, new FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM));
            View commentBar = new View(activity);
            row.addView(commentBar, new FrameLayout.LayoutParams(MATCH, 100));
            View seekRow = new View(activity);
            mask.addView(seekRow, new FrameLayout.LayoutParams(MATCH, 128, Gravity.BOTTOM));
            activity.setContentView(host);
            layOut(host);
            assertEquals(1552, videos.getBottom());
            assertEquals(1552, commentBar.getBottom() + row.getTop() + slot.getTop());

            assertEquals(0, VideoUnderGestureBar.OPENED.apply(activity, commentBar, true));
            layOut(host);
            assertEquals("the video stops over the gesture bar", 1600, videos.getBottom() + mask.getTop());
            assertEquals("the comment bar went down", 1552,
                    commentBar.getBottom() + row.getTop() + slot.getTop() + mask.getTop());
            assertEquals("the seek row went down", 1552, seekRow.getBottom() + mask.getTop());
            assertEquals("the frame's other pages moved", 1552, sibling.getBottom());
            assertEquals("TikTok's padding was changed", 48, host.getPaddingBottom());
            assertEquals(48, VideoUnderGestureBar.OPENED.apply(activity, commentBar, true));
            assertTrue(VideoUnderGestureBar.holdsAny());
            assertEquals("the caption stays where it was", 48, VideoUnderGestureBar.reachOver(layer));
            assertEquals("the comment bar went up", 0, VideoUnderGestureBar.reachOver(commentBar));

            // Fill turned off.
            assertEquals(0, VideoUnderGestureBar.OPENED.apply(activity, null, false));
            layOut(host);
            assertTrue(host.getClipToPadding());
            assertEquals(1552, pagerFrame.getBottom());
            assertEquals(0, mask.getPaddingBottom());
            assertTrue(mask.getClipToPadding());
            assertEquals(1552, videos.getBottom());
            assertEquals(1552, commentBar.getBottom() + row.getTop() + slot.getTop());
            assertEquals(0, VideoUnderGestureBar.reachOver(layer));
            assertFalse(VideoUnderGestureBar.holdsAny());
        }
    }

    /** No frame beside the bar, or no padded frame above it: nothing moves. */
    @Test
    public void aBarWithNoVideoFrameLeavesEverythingAlone() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout host = new FrameLayout(activity);
            FrameLayout mask = new FrameLayout(activity);
            host.addView(mask, new FrameLayout.LayoutParams(MATCH, MATCH));
            FrameLayout feed = new FrameLayout(activity);
            mask.addView(feed, new FrameLayout.LayoutParams(MATCH, MATCH));
            View tabs = new View(activity);
            mask.addView(tabs, new FrameLayout.LayoutParams(MATCH, 98, Gravity.BOTTOM));
            activity.setContentView(host);
            layOut(host);

            assertEquals(0, VideoUnderGestureBar.FEED.apply(activity, tabs, true));
            assertFalse(VideoUnderGestureBar.FEED.holds());
            assertEquals(0, VideoUnderGestureBar.FEED.apply(activity, feed, true));
            assertFalse(VideoUnderGestureBar.FEED.holds());
            assertTrue(mask.getClipToPadding());
        }
    }

    private static View child(ViewGroup group) {
        View view = new View(group.getContext());
        group.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
        return view;
    }

    private static void layOut(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 720, 1600);
    }

    /** Lays every page out over its whole size with plain layout params, as TikTok's pager does. */
    private static final class Pager extends ViewGroup {
        Pager(Context context) {
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
                getChildAt(i).layout(0, 0, r - l, b - t);
            }
        }
    }
}

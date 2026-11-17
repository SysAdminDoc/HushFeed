/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import app.morphe.extension.tiktok.settings.L10n;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** How the split finds TikTok's type pager and tells its own lists from TikTok's. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ActivitySplitTest {
    /** Host-shaped PowerViewPager: a FrameLayout that keeps its ViewPager2 in a field named viewPager2. */
    public static final class FakePowerViewPager extends FrameLayout {
        public final Object viewPager2 = new Object();
        public FakePowerViewPager(Context context) { super(context); }
    }

    public static final class FakeList {
        private final Bundle arguments;
        FakeList(Bundle arguments) { this.arguments = arguments; }
        public Bundle getArguments() { return arguments; }
    }

    public static final class FakeAdapter {
        int changedStart = -1, changedCount = -1;
        public int getItemCount() { return 7; }
        public void notifyItemRangeChanged(int start, int count) { changedStart = start; changedCount = count; }
    }

    /** List-shaped like TikTok's PowerList: a ViewGroup with a RecyclerView-style adapter. */
    public static final class FakeRecycler extends FrameLayout {
        public final FakeAdapter adapter = new FakeAdapter();
        public FakeRecycler(Context context) { super(context); }
        public FakeAdapter getAdapter() { return adapter; }
    }

    @Test public void anEmojiCatalogRedrawMarksEveryRowOfTheListsInsideTheBoxesChanged() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout box = new FrameLayout(context);
        LinearLayout fragmentView = new LinearLayout(context);
        FakeRecycler list = new FakeRecycler(context);
        box.addView(fragmentView);
        fragmentView.addView(list);
        ActivitySplit.redrawListsUnder(box);
        assertEquals(0, list.adapter.changedStart);
        assertEquals(7, list.adapter.changedCount);
    }

    @Test public void thePagerIsFoundAnywhereUnderThePageAndNothingElseIsTakenForIt() {
        Context context = RuntimeEnvironment.getApplication();
        LinearLayout page = new LinearLayout(context);
        FrameLayout header = new FrameLayout(context);
        FrameLayout body = new FrameLayout(context);
        FakePowerViewPager pager = new FakePowerViewPager(context);
        page.addView(header);
        page.addView(body);
        body.addView(pager);
        assertSame(pager, ActivitySplit.findPager(page));

        LinearLayout withoutPager = new LinearLayout(context);
        withoutPager.addView(new FrameLayout(context));
        assertNull(ActivitySplit.findPager(withoutPager));
    }

    @Test public void theBarBetweenTheListsStaysWhileTheListsAndTitlesTradePlaces() {
        Context context = RuntimeEnvironment.getApplication();
        ActivitySplit.Panel panel = new ActivitySplit.Panel(null, null, null);
        panel.build(context, false, false);
        assertOrder(panel, panel.topHeader, panel.commentsBox, panel.middleBar, panel.likesBox, panel.restore);
        assertEquals(L10n.t(context, "Comments"), panel.topTitle.getText().toString());
        assertEquals(L10n.t(context, "Likes"), panel.middleTitle.getText().toString());
        assertEquals(L10n.t(context, "Hide likes"), panel.closeButton.getContentDescription().toString());

        panel.arrange(true);
        assertOrder(panel, panel.topHeader, panel.likesBox, panel.middleBar, panel.commentsBox, panel.restore);
        assertEquals(L10n.t(context, "Likes"), panel.topTitle.getText().toString());
        assertEquals(L10n.t(context, "Comments"), panel.middleTitle.getText().toString());
        assertEquals(L10n.t(context, "Hide comments"), panel.closeButton.getContentDescription().toString());
    }

    /** Counts the times it was told it left the window. */
    public static final class DetachCounter extends View {
        int detached;
        public DetachCounter(Context context) { super(context); }
        @Override protected void onDetachedFromWindow() { detached++; super.onDetachedFromWindow(); }
    }

    @Test public void swappingNeverTakesTheListsOffTheWindow() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ActivitySplit.Panel panel = new ActivitySplit.Panel(null, null, null);
        panel.build(activity, false, false);
        activity.setContentView(panel.root);
        DetachCounter inComments = new DetachCounter(activity), inLikes = new DetachCounter(activity);
        ((FrameLayout) panel.commentsBox).addView(inComments);
        ((FrameLayout) panel.likesBox).addView(inLikes);
        assertTrue(inComments.isAttachedToWindow());

        panel.arrange(true);
        panel.arrange(false);

        assertOrder(panel, panel.topHeader, panel.commentsBox, panel.middleBar, panel.likesBox, panel.restore);
        // A search on either list (Search loaded activity) closes when its list leaves the window.
        assertEquals(0, inComments.detached);
        assertEquals(0, inLikes.detached);
        assertTrue(inComments.isAttachedToWindow());
        assertTrue(inLikes.isAttachedToWindow());
    }

    @Test public void closingHidesWhicheverListIsBelowTheBarAndTheBarWithIt() {
        Context context = RuntimeEnvironment.getApplication();
        ActivitySplit.Panel panel = new ActivitySplit.Panel(null, null, null);
        panel.build(context, true, false);
        panel.applyBottomHidden(true);
        assertEquals(View.VISIBLE, panel.likesBox.getVisibility());
        assertEquals(View.GONE, panel.middleBar.getVisibility());
        assertEquals(View.GONE, panel.commentsBox.getVisibility());
        assertEquals(View.VISIBLE, panel.restore.getVisibility());
        assertTrue(panel.restore.getText().toString().endsWith(L10n.t(context, "Show comments")));

        panel.applyBottomHidden(false);
        assertEquals(View.VISIBLE, panel.middleBar.getVisibility());
        assertEquals(View.VISIBLE, panel.commentsBox.getVisibility());
        assertEquals(View.GONE, panel.restore.getVisibility());

        // A page that opens with the lower list closed: comments on top, so likes are the closed one.
        ActivitySplit.Panel reopened = new ActivitySplit.Panel(null, null, null);
        reopened.build(context, false, true);
        assertEquals(View.VISIBLE, reopened.commentsBox.getVisibility());
        assertEquals(View.GONE, reopened.likesBox.getVisibility());
        assertEquals(View.GONE, reopened.middleBar.getVisibility());
        assertTrue(reopened.restore.getText().toString().endsWith(L10n.t(context, "Show likes")));
    }

    @Test public void thePanelTakesTheNearestOpaqueColorAboveThePagerAndTheBarSitsAShadeOff() {
        Context context = RuntimeEnvironment.getApplication();
        FrameLayout page = new FrameLayout(context);
        page.setBackgroundColor(0xFF121212);
        FrameLayout tinted = new FrameLayout(context);
        tinted.setBackgroundColor(0x80FF0000); // see-through, so it isn't the page's color
        FakePowerViewPager pager = new FakePowerViewPager(context);
        page.addView(tinted);
        tinted.addView(pager);
        assertEquals(0xFF121212, ActivitySplit.pageColor(pager, true));

        page.setBackgroundColor(0xFF0A1B2C); // AMOLED dark theme's picked color reaches the page
        assertEquals(0xFF0A1B2C, ActivitySplit.pageColor(pager, true));

        assertEquals(0xFF2E2E2E, ActivitySplit.blend(0xFF121212, 0xFFFFFFFF, 0.12f));
        assertEquals(0xFFE0E0E0, ActivitySplit.blend(0xFFFFFFFF, 0xFF000000, 0.12f));
    }

    private static void assertOrder(ActivitySplit.Panel panel, View... expected) {
        assertEquals(expected.length, panel.root.getChildCount());
        for (int i = 0; i < expected.length; i++) assertSame("child " + i, expected[i], panel.root.getChildAt(i));
    }

    @Test public void onlyListsCarryingTheMarkerCountAsTheSplitsOwn() {
        Bundle own = new Bundle();
        own.putInt(ActivitySplit.MARKER, ActivitySplit.COMMENTS_GROUP);
        assertEquals(Integer.valueOf(ActivitySplit.COMMENTS_GROUP), ActivitySplit.groupMark(new FakeList(own)));
        assertNull(ActivitySplit.groupMark(new FakeList(new Bundle())));
        assertNull(ActivitySplit.groupMark(new FakeList(null)));
    }
}

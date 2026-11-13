/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Runs a video on under the gesture bar (#408): the main feed's with See-through bottom bar, and a
 * video opened from search, a profile or the Liked grid with Fill the screen with the video.
 *
 * <p>TikTok targets SDK 36, so its window is edge to edge, and a host near the top of each page pads
 * its bottom by the navigation bar's height: the feed's tab host, and the frame an opened video's
 * pager sits in (Login2, 47.7.3: 1552 of 1600 in both). With the bar over the video clear, the
 * gesture bar sat on a strip of the host's black under the picture.
 *
 * <p>The bar on top of the video (the feed's tabs, an opened video's comment bar) hangs in a mask
 * whose first child is the video's frame. The host's child that holds the mask goes down over the
 * padding with a negative bottom margin, and the host stops clipping to its padding, so everything
 * between them grows by the strip. The mask takes the strip as padding of its own, so the bar, the
 * seek row and the rest of its children stay where they were, and the frame takes it back with a
 * negative margin of its own, so only the picture reaches the gesture bar. The host's padding is
 * never touched, so TikTok's next insets pass has nothing to undo, and a padding taller than the
 * navigation bar is the keyboard's, which puts everything back.
 */
final class VideoUnderGestureBar {
    /** The main feed under its tab bar. */
    static final VideoUnderGestureBar FEED = new VideoUnderGestureBar();
    /** A video opened in the main activity or the detail pager, under its comment bar. */
    static final VideoUnderGestureBar OPENED = new VideoUnderGestureBar();

    /** How far up from the bar the mask is looked for, and from the mask the padded host. */
    private static final int MAX_HOPS = 8;

    private VideoUnderGestureBar() {
    }

    /** What was moved for one mask, to put back. */
    private static final class Moved {
        final WeakReference<ViewGroup> host;
        final WeakReference<View> top;
        final WeakReference<View> frame;
        final int strip;
        final boolean hostClips;
        final int topMargin;
        final int maskPadding;
        final boolean maskClips;
        final int frameMargin;
        /** How far the frame reaches under the gesture bar as last laid out. */
        int reach;

        Moved(ViewGroup host, View top, ViewGroup mask, View frame, int strip) {
            this.host = new WeakReference<>(host);
            this.top = new WeakReference<>(top);
            this.frame = new WeakReference<>(frame);
            this.strip = strip;
            this.hostClips = host.getClipToPadding();
            this.topMargin = ((ViewGroup.MarginLayoutParams) top.getLayoutParams()).bottomMargin;
            this.maskPadding = mask.getPaddingBottom();
            this.maskClips = mask.getClipToPadding();
            this.frameMargin = ((ViewGroup.MarginLayoutParams) frame.getLayoutParams()).bottomMargin;
        }
    }

    /** Masks whose frame runs on under the gesture bar, with what to put back. */
    private final Map<ViewGroup, Moved> moved = new WeakHashMap<>();

    /** Whether anything is moved and a pass has to come by to put it back. */
    boolean holds() {
        return !moved.isEmpty();
    }

    static boolean holdsAny() {
        return FEED.holds() || OPENED.holds();
    }

    /**
     * How far the frame holding {@code view} reaches under the gesture bar as last laid out, which
     * the caption and the buttons laid out against the cell's bottom go up by to stay where they
     * were. 0 for a view in no frame that was moved.
     */
    static int reachOver(View view) {
        return Math.max(FEED.reachFor(view), OPENED.reachFor(view));
    }

    private int reachFor(View view) {
        if (moved.isEmpty()) return 0;
        for (Moved entry : moved.values()) {
            View frame = entry.frame.get();
            if (frame == null || entry.reach <= 0) continue;
            for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
                if (parent == frame) return entry.reach;
            }
        }
        return 0;
    }

    /**
     * Runs the video under {@code bar} on under the gesture bar while {@code on}, and puts back
     * whatever this window had moved otherwise. Another window's views are left as they are.
     * Returns how far the video reaches under the gesture bar as laid out now: 0 until the layout
     * has run.
     */
    int apply(Activity activity, @Nullable View bar, boolean on) {
        ViewGroup mask = on ? maskOf(bar) : null;
        View frame = mask != null ? mask.getChildAt(0) : null;
        View top = mask != null ? topOf(mask) : null;
        ViewGroup host = top != null ? (ViewGroup) top.getParent() : null;
        View decor = activity.getWindow().getDecorView();
        for (Map.Entry<ViewGroup, Moved> entry : new ArrayList<>(moved.entrySet())) {
            ViewGroup held = entry.getKey();
            if (host != null && held == mask) continue;
            if (held.isAttachedToWindow() && held.getRootView() != decor) continue;
            putBack(held, entry.getValue());
            moved.remove(held);
        }
        if (host == null) return 0;

        int strip = host.getPaddingBottom();
        Moved entry = moved.get(mask);
        boolean fits = navigationBarSized(host, strip);
        if (entry != null && (!fits || entry.strip != strip || entry.host.get() != host
                || entry.top.get() != top || entry.frame.get() != frame)) {
            putBack(mask, entry);
            moved.remove(mask);
            entry = null;
        }
        if (!fits) return 0;
        if (entry == null) {
            entry = new Moved(host, top, mask, frame, strip);
            moved.put(mask, entry);
        }

        if (host.getClipToPadding()) host.setClipToPadding(false);
        setBottomMargin(top, entry.topMargin - strip);
        if (mask.getPaddingBottom() != entry.maskPadding + strip) {
            mask.setPadding(mask.getPaddingLeft(), mask.getPaddingTop(), mask.getPaddingRight(),
                    entry.maskPadding + strip);
        }
        if (mask.getClipToPadding()) mask.setClipToPadding(false);
        setBottomMargin(frame, entry.frameMargin - strip);

        // Measured against the host, whose size none of this changes, so the lift follows the
        // video only once it has been laid out over the strip.
        int bottom = frame.getBottom();
        for (View at = mask; at != host; at = (View) at.getParent()) {
            bottom += at.getTop();
        }
        entry.reach = Math.max(0, Math.min(strip, bottom - (host.getHeight() - strip)));
        return entry.reach;
    }

    /**
     * The nearest parent of {@code bar} whose first child is a full-height frame beside the bar's
     * branch: the feed's mask holds the feed frame and the tab bar, an opened video's holds its
     * pager and the comment bar's slot.
     */
    @Nullable
    private static ViewGroup maskOf(@Nullable View bar) {
        View branch = bar;
        for (int hop = 0; branch != null && hop < MAX_HOPS; hop++) {
            if (!(branch.getParent() instanceof ViewGroup)) return null;
            ViewGroup parent = (ViewGroup) branch.getParent();
            View first = parent.getChildCount() > 0 ? parent.getChildAt(0) : null;
            if (first != null && first != branch && fullHeight(first)
                    && first.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                return parent instanceof FrameLayout ? parent : null;
            }
            branch = parent;
        }
        return null;
    }

    /**
     * The child of the padded host that {@code mask} hangs from, through parents that each fill
     * theirs: the mask itself on the feed, the pager's frame four levels up on an opened video. The
     * pager between them gives its pages plain layout params, so only the host's own child needs
     * a margin.
     */
    @Nullable
    private static View topOf(ViewGroup mask) {
        View top = mask;
        for (int hop = 0; hop < MAX_HOPS; hop++) {
            if (!(top.getParent() instanceof ViewGroup) || !fullHeight(top)) return null;
            ViewGroup parent = (ViewGroup) top.getParent();
            ViewGroup.LayoutParams params = top.getLayoutParams();
            if (parent.getPaddingBottom() > 0) {
                return parent instanceof FrameLayout && params instanceof ViewGroup.MarginLayoutParams ? top : null;
            }
            if (params instanceof ViewGroup.MarginLayoutParams
                    && ((ViewGroup.MarginLayoutParams) params).bottomMargin != 0) return null;
            top = parent;
        }
        return null;
    }

    private static boolean fullHeight(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        return params != null && params.height == ViewGroup.LayoutParams.MATCH_PARENT;
    }

    private static void setBottomMargin(@Nullable View view, int margin) {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params.bottomMargin == margin) return;
        params.bottomMargin = margin;
        view.setLayoutParams(params);
    }

    private static void putBack(ViewGroup mask, Moved entry) {
        ViewGroup host = entry.host.get();
        if (host != null && host.getClipToPadding() != entry.hostClips) host.setClipToPadding(entry.hostClips);
        setBottomMargin(entry.top.get(), entry.topMargin);
        if (mask.getPaddingBottom() != entry.maskPadding) {
            mask.setPadding(mask.getPaddingLeft(), mask.getPaddingTop(), mask.getPaddingRight(), entry.maskPadding);
        }
        if (mask.getClipToPadding() != entry.maskClips) mask.setClipToPadding(entry.maskClips);
        setBottomMargin(entry.frame.get(), entry.frameMargin);
    }

    /**
     * Whether {@code padding} is the navigation bar's and not the keyboard's. Where the window
     * doesn't say, a strip under an eighth of the host's height is taken for the bar.
     */
    @SuppressWarnings("deprecation")
    static boolean navigationBarSized(View host, int padding) {
        if (padding <= 0) return false;
        WindowInsets insets = host.getRootWindowInsets();
        int bar = 0;
        if (insets != null) {
            bar = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                    : insets.getStableInsetBottom();
        }
        return bar > 0 ? padding <= bar : padding * 8 < host.getHeight();
    }
}

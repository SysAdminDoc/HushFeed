/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Pages under the bar (#436): with See-through bottom bar, a page that stops at the bar's top
 * (Home's Community grid, another tab's list) runs on under the bar, so the clear bar or the pill
 * shows the page through it instead of the black or white band TikTok leaves there.
 *
 * <p>TikTok's tab pages hang in the frame under the tab bar (the mask's first child, see
 * {@link VideoUnderGestureBar}) and keep the bar's height free at their bottom, with a margin or a
 * padding somewhere between the frame and the page's list. The list in front (a vertical list
 * more than half the window wide that ends in the bar's top band and holds no feed cell) is
 * followed up to the topmost view that still ends where it ends, through views that hold nothing
 * else beside it a move down could carry under the bar. That anchor takes a negative bottom margin
 * as tall as the room under it, and its parent stops clipping to its padding, so it reaches the
 * parent's bottom. A plain band that makes up that room (Community's column keeps the bar's room
 * with one) goes while the anchor is down: it would draw over the page, and a column shared by
 * weight gives the anchor its room only once it's gone. Every list in it, the pages beside the one in front included, takes the part
 * of it now under the bar and the pill's rise as bottom padding and stops clipping to it, so its
 * rows draw on under the bar and its last row still scrolls up clear of the tabs. While such a page
 * is in front, TikTok's paint under the gesture bar is hidden too, so it shows down to the bottom.
 *
 * <p>A layout that ignores the margin is put back on the next pass and left alone from then on. A
 * value TikTok sets again is taken as its own and the room is added to it; past a few of those
 * the page is left alone too, so the two never trade a value back and forth a layout at a time.
 * Everything goes back when the switch goes off, the see-through bar goes, or Hushfeed is paused.
 */
final class PageUnderBar {
    private static final int MAX_DEPTH = 24;
    /** How far down a list is looked into for a feed cell, which rules it out. */
    private static final int CELL_DEPTH = 4;
    /** How far above the bar's top a page may stop and still count as stopping at it. */
    private static final int ABOVE_DP = 8;
    /** How many values TikTok may set back before the page is left alone. */
    private static final int MAX_RETAKES = 3;

    private PageUnderBar() {
    }

    /** A view that goes down under the bar, with what to put back. */
    private static final class Grown {
        final WeakReference<ViewGroup> parent;
        /** How far it goes down: the room that was between it and its parent's bottom. */
        final int grow;
        final boolean parentClips;
        /** Its bottom margin as TikTok has it. */
        int margin;
        /** Whether a layout has shown it reaching its parent's bottom. */
        boolean checked;
        int retakes;
        /** The bands that made up the room under it, GONE while it's down. */
        final List<WeakReference<View>> bands = new ArrayList<>();

        Grown(View anchor, ViewGroup parent, int grow) {
            this.parent = new WeakReference<>(parent);
            this.grow = grow;
            this.parentClips = parent.getClipToPadding();
            this.margin = ((ViewGroup.MarginLayoutParams) anchor.getLayoutParams()).bottomMargin;
        }
    }

    /** A list padded so its last row clears the bar, with what to put back. */
    private static final class Padded {
        final WeakReference<View> anchor;
        final boolean clips;
        /** Its bottom padding as TikTok has it, and what it was set to. */
        int padding;
        int paddingSet;
        int retakes;

        Padded(ViewGroup list, View anchor) {
            this.anchor = new WeakReference<>(anchor);
            this.clips = list.getClipToPadding();
            this.padding = list.getPaddingBottom();
            this.paddingSet = padding;
        }
    }

    private static final Map<View, Grown> GROWN = new WeakHashMap<>();
    private static final Map<ViewGroup, Padded> PADDED = new WeakHashMap<>();
    /** Anchors whose layout wouldn't take the room, left as TikTok has them. */
    private static final Map<View, Boolean> REFUSED = new WeakHashMap<>();
    /** TikTok's paint under the gesture bar, hidden while a page in front runs under the bar. */
    private static WeakReference<View> shade = new WeakReference<>(null);
    private static int shadeRetakes;

    /** Whether a page runs under the bar and a pass has to come by to put it back. */
    static boolean holds() {
        return !GROWN.isEmpty() || !PADDED.isEmpty() || shade.get() != null;
    }

    /**
     * Runs the page in front on under {@code bar} while {@code on}, looking for a new one only
     * with {@code look} (the feed's video isn't in front), and puts back whatever this window had
     * moved otherwise. Another window's views are left as they are. {@code rise} is how far the
     * pill stands above the bar, and {@code cellId} the feed cell's id: with none resolved nothing
     * new is taken, since the feed's own pager couldn't be told from a page. Returns whether a page
     * that runs under the bar is on screen now.
     */
    static boolean apply(Activity activity, @Nullable View bar, int rise, int cellId, boolean on, boolean look) {
        if (!on && !holds()) return false;
        ViewGroup mask = on && bar != null && bar.isShown() && bar.getHeight() > 0
                ? VideoUnderGestureBar.maskOf(bar) : null;
        View first = mask != null ? mask.getChildAt(0) : null;
        ViewGroup frame = first instanceof ViewGroup && first.isShown() ? (ViewGroup) first : null;
        View decor = activity.getWindow().getDecorView();
        for (Map.Entry<ViewGroup, Padded> entry : new ArrayList<>(PADDED.entrySet())) {
            if (!wanted(entry.getKey(), frame, decor)) unpad(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<View, Grown> entry : new ArrayList<>(GROWN.entrySet())) {
            if (!wanted(entry.getKey(), frame, decor)) ungrow(entry.getKey(), entry.getValue());
        }
        if (frame == null) {
            keepShade(decor, null, 0, false);
            return false;
        }

        int[] at = new int[2];
        bar.getLocationInWindow(at);
        int barTop = at[1];
        int barBottom = barTop + bar.getHeight();
        int above = Math.round(ABOVE_DP * bar.getResources().getDisplayMetrics().density);
        if (look && cellId != 0) {
            List<ViewGroup> lists = new ArrayList<>();
            collectLists(frame, cellId, 0, lists);
            for (ViewGroup list : lists) {
                if (PADDED.containsKey(list)) continue;
                View anchor = grownAround(list, frame);
                if (anchor == null && onScreen(list, mask)) {
                    anchor = grow(list, frame, barTop, barBottom, above, cellId);
                }
                if (anchor != null) pad(list, anchor, barTop, rise);
            }
        }

        for (Map.Entry<View, Grown> entry : new ArrayList<>(GROWN.entrySet())) {
            View anchor = entry.getKey();
            if (anchor.getRootView() != decor || !anchor.isShown()) continue;
            if (!keepGrown(anchor, entry.getValue(), cellId)) ungrow(anchor, entry.getValue());
        }
        boolean inFront = false;
        for (Map.Entry<ViewGroup, Padded> entry : new ArrayList<>(PADDED.entrySet())) {
            ViewGroup list = entry.getKey();
            if (list.getRootView() != decor || !list.isShown()) continue;
            if (!keepPadded(list, entry.getValue(), barTop, rise)) {
                if (PADDED.containsKey(list)) unpad(list, entry.getValue());
                continue;
            }
            if (onScreen(list, mask)) inFront = true;
        }
        keepShade(decor, mask, barBottom, inFront);
        return inFront;
    }

    /**
     * Hides TikTok's paint under the gesture bar while {@code hide}, and puts it back otherwise.
     * On 48.0.2 it's a plain view ({@code h0r}) at the bottom of the main window's root, drawn over
     * the pages, clear on the feed and white on a light page like Community's, so a page that ran
     * on under the bar still ended in a white strip (Login2, 2026-11-26). Another window's is left
     * as it is.
     */
    private static void keepShade(View decor, @Nullable ViewGroup mask, int barBottom, boolean hide) {
        View held = shade.get();
        if (held != null && held.isAttachedToWindow() && held.getRootView() != decor) return;
        if (held != null && (!hide || !held.isAttachedToWindow())) {
            if (held.getVisibility() == View.INVISIBLE) held.setVisibility(View.VISIBLE);
            shade = new WeakReference<>(null);
            held = null;
        }
        if (!hide || mask == null) return;
        if (held == null) {
            held = shadeUnder(mask, barBottom);
            if (held == null) return;
            shade = new WeakReference<>(held);
            shadeRetakes = 0;
            held.setVisibility(View.INVISIBLE);
            Logger.printDebug(() -> "Pages under the bar: hid the paint under the gesture bar");
            return;
        }
        if (held.getVisibility() != View.VISIBLE) return;
        if (++shadeRetakes > MAX_RETAKES) {
            REFUSED.put(held, true);
            shade = new WeakReference<>(null);
            Logger.printDebug(() -> "Pages under the bar: left the paint under the gesture bar, TikTok keeps showing it");
            return;
        }
        held.setVisibility(View.INVISIBLE);
    }

    /**
     * A plain view drawn after the branch holding {@code mask}, up to the activity's content, that
     * spans its parent's width, ends at its parent's bottom and starts at or below the bar's bottom:
     * the paint under the gesture bar, and nothing a page holds.
     */
    @Nullable
    private static View shadeUnder(ViewGroup mask, int barBottom) {
        View branch = mask;
        int[] at = new int[2];
        for (ViewParent parent = mask.getParent(); parent instanceof ViewGroup; parent = parent.getParent()) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = group.indexOfChild(branch) + 1; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getClass() != View.class || child.getVisibility() != View.VISIBLE
                        || child.getHeight() == 0 || REFUSED.containsKey(child)) {
                    continue;
                }
                child.getLocationInWindow(at);
                if (child.getLeft() <= 0 && child.getRight() >= group.getWidth()
                        && child.getBottom() >= group.getHeight() - 1 && at[1] >= barBottom - 1) {
                    return child;
                }
            }
            if (group.getId() == android.R.id.content) return null;
            branch = group;
        }
        return null;
    }

    /** Whether a view this class changed stays changed: it's another window's, or in {@code frame}. */
    private static boolean wanted(View view, @Nullable ViewGroup frame, View decor) {
        if (view.isAttachedToWindow() && view.getRootView() != decor) return true;
        return frame != null && view.isAttachedToWindow() && inside(view, frame);
    }

    /**
     * Sends {@code list}'s page down under the bar when the list ends at the bar, and returns the
     * view that went down, or null where it doesn't or its room can't be taken. The list may end
     * anywhere from just above the bar's top to its bottom: once the feed's frame reaches under the
     * gesture bar, a page that kept the bar's height free ends that bar lower, which on three-button
     * navigation is about the tab bar's own height.
     */
    @Nullable
    private static View grow(ViewGroup list, ViewGroup frame, int barTop, int barBottom, int above, int cellId) {
        int bottom = bottomInWindow(list);
        if (bottom < barTop - above || bottom > barBottom + above) return null;
        ViewGroup anchor = list;
        while (anchor != frame) {
            ViewGroup parent = (ViewGroup) anchor.getParent();
            if (parent.getHeight() - anchor.getBottom() > 1) break;
            // The parent would go down too, and whatever it holds over the list with it.
            if (!alone(parent, anchor)) return null;
            anchor = parent;
        }
        if (anchor == frame || REFUSED.containsKey(anchor)
                || !(anchor.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return null;
        }
        // A pager holding the feed beside the page would take the feed's video down with it.
        if (holdsCell(anchor, cellId, 0, MAX_DEPTH)) {
            refuse(anchor, "it holds the feed");
            return null;
        }
        ViewGroup parent = (ViewGroup) anchor.getParent();
        int grow = parent.getHeight() - anchor.getBottom();
        if (grow <= 0 || bottomInWindow(parent) < barBottom - 1) return null;
        for (Grown other : GROWN.values()) {
            if (other.parent.get() == parent) return null;
        }

        Grown grown = new Grown(anchor, parent, grow);
        GROWN.put(anchor, grown);
        setBottomMargin(anchor, grown.margin - grow);
        if (parent.getClipToPadding()) parent.setClipToPadding(false);
        for (View band : bandsUnder(parent, anchor)) {
            grown.bands.add(new WeakReference<>(band));
            band.setVisibility(View.GONE);
        }
        String name = anchor.getClass().getSimpleName();
        int bands = grown.bands.size();
        Logger.printDebug(() -> "Pages under the bar: " + name + " goes " + grow + " px down under the bar"
                + (bands > 0 ? ", " + bands + " band(s) under it hidden" : ""));
        return anchor;
    }

    /**
     * Whether {@code group} holds nothing but {@code branch} where the branch is: a header above
     * it stays where it is when the group goes down, an overlay on it (a button, a pill, an empty
     * page's note) would go down under the bar. A backdrop drawn behind the branch that fills the
     * group (Community's white sheet behind its grid) goes down with the group and covers nothing.
     */
    private static boolean alone(ViewGroup group, View branch) {
        int branchIndex = group.indexOfChild(branch);
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == branch || child.getVisibility() != View.VISIBLE
                    || child.getWidth() == 0 || child.getHeight() == 0) {
                continue;
            }
            if (i < branchIndex && child.getZ() <= branch.getZ() && fills(group, child)) continue;
            boolean across = child.getLeft() < branch.getRight() && child.getRight() > branch.getLeft();
            if (across && child.getBottom() > branch.getTop()) return false;
        }
        return true;
    }

    private static boolean fills(ViewGroup group, View child) {
        return child.getLeft() <= 0 && child.getTop() <= 0
                && child.getRight() >= group.getWidth() && child.getBottom() >= group.getHeight();
    }

    /**
     * The plain views that make up {@code parent}'s room under {@code anchor}: a band TikTok keeps
     * the bar's room with (Community's column ends in one), which would draw over the page and,
     * in a column shared by weight, keep its room from it. Only bare {@link View}s, which hold
     * nothing a hidden band could take away, and none at all when anything else is in the room.
     */
    private static List<View> bandsUnder(ViewGroup parent, View anchor) {
        List<View> bands = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == anchor || child.getVisibility() != View.VISIBLE
                    || child.getWidth() == 0 || child.getHeight() == 0) {
                continue;
            }
            boolean across = child.getLeft() < anchor.getRight() && child.getRight() > anchor.getLeft();
            if (!across || child.getBottom() <= anchor.getBottom()) continue;
            if (child.getClass() != View.class || child.getTop() < anchor.getBottom() - 1) {
                return new ArrayList<>();
            }
            bands.add(child);
        }
        return bands;
    }

    /** The view this class sent down that {@code list} is in, or is. */
    @Nullable
    private static View grownAround(View list, ViewGroup frame) {
        for (View at = list; at != frame && at != null;
             at = at.getParent() instanceof View ? (View) at.getParent() : null) {
            if (GROWN.containsKey(at)) return at;
        }
        return null;
    }

    private static void pad(ViewGroup list, View anchor, int barTop, int rise) {
        Padded padded = new Padded(list, anchor);
        PADDED.put(list, padded);
        Grown grown = GROWN.get(anchor);
        // Just sent down, it's laid out where it was until the next layout.
        int bottom = bottomInWindow(list) + (anchor.isLayoutRequested() && grown != null ? grown.grow : 0);
        fit(list, padded, Math.max(0, bottom - (barTop - rise)));
    }

    /**
     * Gives {@code list} {@code extra} more bottom padding than TikTok's, drawn into while it has
     * any, so rows show under the bar and the last one still scrolls up clear of it.
     */
    private static void fit(ViewGroup list, Padded padded, int extra) {
        padded.paddingSet = padded.padding + extra;
        setBottomPadding(list, padded.paddingSet);
        boolean clips = extra > 0 ? false : padded.clips;
        if (list.getClipToPadding() != clips) list.setClipToPadding(clips);
    }

    /**
     * Keeps {@code anchor} down: takes a margin TikTok set again as its own, and checks after a
     * layout that the room was taken. False when it has to go back.
     */
    private static boolean keepGrown(View anchor, Grown grown, int cellId) {
        ViewGroup parent = grown.parent.get();
        if (parent == null || anchor.getParent() != parent
                || !(anchor.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return false;
        }
        // The feed came in beside the page (a pager built its page late): the feed keeps its place.
        if (anchor instanceof ViewGroup && holdsCell((ViewGroup) anchor, cellId, 0, MAX_DEPTH)) {
            refuse(anchor, "the feed came into it");
            return false;
        }
        int margin = ((ViewGroup.MarginLayoutParams) anchor.getLayoutParams()).bottomMargin;
        if (margin != grown.margin - grown.grow) {
            if (++grown.retakes > MAX_RETAKES) {
                refuse(anchor, "TikTok keeps setting it back");
                return false;
            }
            grown.margin = margin;
            grown.checked = false;
            setBottomMargin(anchor, margin - grown.grow);
        }
        for (WeakReference<View> reference : grown.bands) {
            View band = reference.get();
            if (band == null || band.getVisibility() != View.VISIBLE) continue;
            if (++grown.retakes > MAX_RETAKES) {
                refuse(anchor, "TikTok keeps showing the band under it");
                return false;
            }
            grown.checked = false;
            band.setVisibility(View.GONE);
        }
        if (parent.getClipToPadding()) parent.setClipToPadding(false);
        if (anchor.isLayoutRequested()) return true;
        // Laid out as before, the layout ignores margins (ConstraintLayout's older releases do).
        if (parent.getHeight() - anchor.getBottom() > 1) {
            if (!grown.checked) refuse(anchor, "its layout didn't take the room");
            return false;
        }
        grown.checked = true;
        return true;
    }

    /**
     * Keeps {@code list}'s padding: takes a padding TikTok set again as its own, and fits the room
     * to where the list ends now. False when it has to go back.
     */
    private static boolean keepPadded(ViewGroup list, Padded padded, int barTop, int rise) {
        View anchor = padded.anchor.get();
        Grown grown = anchor != null ? GROWN.get(anchor) : null;
        if (grown == null) return false;
        if (list.getPaddingBottom() != padded.paddingSet) {
            if (++padded.retakes > MAX_RETAKES) {
                refuse(anchor, "TikTok keeps setting a list's padding back");
                ungrow(anchor, grown);
                return false;
            }
            int extra = padded.paddingSet - padded.padding;
            padded.padding = list.getPaddingBottom();
            fit(list, padded, extra);
        }
        int extra = padded.paddingSet - padded.padding;
        if (!anchor.isLayoutRequested() && !list.isLayoutRequested()) {
            extra = Math.max(0, bottomInWindow(list) - (barTop - rise));
        }
        fit(list, padded, extra);
        return true;
    }

    private static void refuse(View anchor, String why) {
        REFUSED.put(anchor, true);
        Logger.printDebug(() -> "Pages under the bar: left " + anchor.getClass().getSimpleName()
                + " as it was, " + why);
    }

    /** Puts {@code anchor}'s margin back, and the padding of every list in it. */
    private static void ungrow(View anchor, Grown grown) {
        GROWN.remove(anchor);
        for (Map.Entry<ViewGroup, Padded> entry : new ArrayList<>(PADDED.entrySet())) {
            if (entry.getValue().anchor.get() == anchor || entry.getValue().anchor.get() == null) {
                unpad(entry.getKey(), entry.getValue());
            }
        }
        if (anchor.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                && ((ViewGroup.MarginLayoutParams) anchor.getLayoutParams()).bottomMargin == grown.margin - grown.grow) {
            setBottomMargin(anchor, grown.margin);
        }
        ViewGroup parent = grown.parent.get();
        if (parent != null && grown.parentClips && !parent.getClipToPadding()) parent.setClipToPadding(true);
        for (WeakReference<View> reference : grown.bands) {
            View band = reference.get();
            if (band != null && band.getVisibility() == View.GONE) band.setVisibility(View.VISIBLE);
        }
    }

    private static void unpad(ViewGroup list, Padded padded) {
        PADDED.remove(list);
        if (list.getPaddingBottom() == padded.paddingSet) setBottomPadding(list, padded.padding);
        if (padded.clips && !list.getClipToPadding()) list.setClipToPadding(true);
    }

    /** The first vertical list down each shown branch of {@code group}, past feed cells. */
    private static void collectLists(ViewGroup group, int cellId, int depth, List<ViewGroup> out) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!(child instanceof ViewGroup) || child.getVisibility() != View.VISIBLE || child.getId() == cellId) {
                continue;
            }
            ViewGroup inner = (ViewGroup) child;
            if (isList(inner)) {
                if (!holdsCell(inner, cellId, 0, CELL_DEPTH)) out.add(inner);
                continue;
            }
            if (depth < MAX_DEPTH) collectLists(inner, cellId, depth + 1, out);
        }
    }

    /**
     * A list that scrolls up and down, or one of Android's lists that doesn't scroll sideways: a
     * short one still has to keep its last row clear of the bar. A web page scrolls inside
     * itself, so padding gives its last line no room.
     */
    private static boolean isList(ViewGroup view) {
        boolean known = false;
        for (Class<?> type = view.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
            String name = type.getSimpleName();
            if (name.contains("WebView")) return false;
            if (name.equals("RecyclerView") || name.equals("AbsListView") || name.equals("ScrollView")
                    || name.equals("NestedScrollView")) {
                known = true;
            }
        }
        if (view.canScrollVertically(1) || view.canScrollVertically(-1)) return true;
        return known && !view.canScrollHorizontally(1) && !view.canScrollHorizontally(-1);
    }

    /** Whether a feed cell is in {@code group} within {@code limit} levels down. */
    private static boolean holdsCell(ViewGroup group, int cellId, int depth, int limit) {
        if (cellId == 0) return false;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getId() == cellId) return true;
            if (child instanceof ViewGroup && depth < limit
                    && holdsCell((ViewGroup) child, cellId, depth + 1, limit)) {
                return true;
            }
        }
        return false;
    }

    /** Shown over more than half of {@code mask}'s shown width: the page in front, not one beside it. */
    private static boolean onScreen(View list, View mask) {
        Rect shown = new Rect();
        Rect room = new Rect();
        return list.isShown() && list.getGlobalVisibleRect(shown) && shown.height() > 0
                && mask.getGlobalVisibleRect(room) && shown.width() * 2 > room.width();
    }

    private static boolean inside(View view, View ancestor) {
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent == ancestor) return true;
        }
        return false;
    }

    private static int bottomInWindow(View view) {
        int[] at = new int[2];
        view.getLocationInWindow(at);
        return at[1] + view.getHeight();
    }

    private static void setBottomMargin(View view, int margin) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params.bottomMargin == margin) return;
        params.bottomMargin = margin;
        view.setLayoutParams(params);
    }

    private static void setBottomPadding(View view, int padding) {
        if (view.getPaddingBottom() == padding) return;
        view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(), padding);
    }
}

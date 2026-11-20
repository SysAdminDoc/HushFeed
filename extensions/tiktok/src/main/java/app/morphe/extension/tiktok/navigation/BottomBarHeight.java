/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Makes the bottom bar shorter or taller. TikTok sizes the bar from a 47 dp dimension, keeps the
 * space every screen leaves above it in a static it works out once a launch (49 dp on most
 * phones), and places a divider and a stub over the bar with 47 and 45 dp literals. Each of
 * those passes through here, so the bar and everything sitting on it move together. Inside each
 * tab, TikTok pins the icon to the top and the name to the bottom. On a taller bar those two move
 * half the difference each and keep the gap TikTok's own bar gives them. A shorter bar has no room
 * for both inside the selected tab's outline at TikTok's size, so they shrink with the tab, each
 * toward its own edge. TikTok also keeps a standard room
 * for the bar and pads its tabs differently once the room stops matching it, so the standard moves
 * along with the room.
 */
public final class BottomBarHeight {
    public static final String TIKTOK = "tiktok";
    public static final String SHORTER = "40";
    public static final String SHORT = "44";
    public static final String TALL = "52";
    public static final String TALLER = "56";
    /** TikTok's own bar, in dp. */
    static final int TIKTOK_DP = 47;
    /** The room TikTok leaves for its bar on most phones, in dp. */
    static final int TIKTOK_NAV_DP = 49;

    /** The picked height in dp, read once a launch like TikTok reads its own. */
    private static volatile int pickedDp;

    /** TikTok's bar in pixels, as its dimension last read before scaling. */
    private static volatile float tikTokBarPixels;

    /** The room TikTok worked out for the bar this launch, in dp, or -1 before it has. */
    private static volatile int tikTokNavDp = -1;

    /** The tab views already moved, so one put back into its tab doesn't move twice. */
    private static final Set<ViewGroup.LayoutParams> moved =
            Collections.newSetFromMap(new WeakHashMap<>());

    private BottomBarHeight() {
    }

    /** The height a choice stands for, or TikTok's for anything that isn't one of the choices. */
    static int heightFor(String choice) {
        if (SHORTER.equals(choice) || SHORT.equals(choice) || TALL.equals(choice) || TALLER.equals(choice)) {
            return Integer.parseInt(choice);
        }
        return TIKTOK_DP;
    }

    static int heightDp() {
        int dp = pickedDp;
        if (dp != 0) return dp;
        // TikTok can work out its static while its Application is still attaching. The settings
        // aren't readable before the context arrives, and that launch keeps TikTok's bar.
        if (!Utils.hasContext()) return TIKTOK_DP;
        try {
            dp = heightFor(Settings.BOTTOM_BAR_HEIGHT.get());
        } catch (Throwable problem) {
            Logger.printException(() -> "Bottom bar height: left it to TikTok", problem);
            dp = TIKTOK_DP;
        }
        pickedDp = dp;
        return dp;
    }

    static void resetForTests() {
        pickedDp = 0;
        tikTokBarPixels = 0;
        tikTokNavDp = -1;
        moved.clear();
    }

    /** A size read from the bar's dimension, in pixels. */
    @SuppressWarnings("unused")
    public static float barPixels(float pixels) {
        tikTokBarPixels = pixels;
        int dp = heightDp();
        return dp == TIKTOK_DP ? pixels : pixels * dp / TIKTOK_DP;
    }

    /** The same size where TikTok reads it as whole pixels. */
    @SuppressWarnings("unused")
    public static int barPixels(int pixels) {
        tikTokBarPixels = pixels;
        int dp = heightDp();
        return dp == TIKTOK_DP ? pixels : Math.round((float) pixels * dp / TIKTOK_DP);
    }

    /**
     * How far a taller bar's tab icon and name move apart, in pixels, as less than nothing: half of
     * what the bar gained. Before the bar's dimension is read it goes by dp. A shorter bar shrinks
     * them instead, so it moves nothing.
     */
    static int contentShift(float density) {
        int dp = heightDp();
        if (dp <= TIKTOK_DP) return 0;
        float bar = tikTokBarPixels > 0 ? tikTokBarPixels : TIKTOK_DP * density;
        return Math.round(bar * (TIKTOK_DP - dp) / (2f * TIKTOK_DP));
    }

    /**
     * How much a shorter bar shrinks its tabs' icons and names: as much as the tab itself shrank,
     * since each tab is as tall as the room TikTok leaves for the bar. A taller bar keeps them at
     * TikTok's size.
     */
    static float contentScale() {
        if (heightDp() >= TIKTOK_DP) return 1f;
        int nav = tikTokNavDp > 0 ? tikTokNavDp : TIKTOK_NAV_DP;
        return (float) roomFor(nav) / nav;
    }

    /** A bottom tab, as it's made. Each view put in it from then on is moved as it goes in. */
    @SuppressWarnings("unused")
    public static void onBarCell(View cell) {
        try {
            if (!(cell instanceof ViewGroup)) return;
            int shift = contentShift(cell.getResources().getDisplayMetrics().density);
            float scale = contentScale();
            if (shift == 0 && scale == 1f) return;
            ViewGroup tab = (ViewGroup) cell;
            for (int i = 0; i < tab.getChildCount(); i++) moveInTab(tab.getChildAt(i), shift, scale);
            tab.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    moveInTab(child, shift, scale);
                }

                @Override
                public void onChildViewRemoved(View parent, View child) {
                }
            });
        } catch (Throwable problem) {
            Logger.printException(() -> "Bottom bar height: left a tab's icon where TikTok put it", problem);
        }
    }

    /**
     * Moves a view pinned to the tab's top up by the shift, and one pinned to its bottom down by
     * it, or shrinks it by the scale toward the edge it's pinned to. A centered view, and one as
     * tall as the tab, already sits right.
     */
    static void moveInTab(View child, int shift, float scale) {
        ViewGroup.LayoutParams params = child.getLayoutParams();
        if (!(params instanceof FrameLayout.LayoutParams) || params.height == ViewGroup.LayoutParams.MATCH_PARENT) return;
        FrameLayout.LayoutParams frame = (FrameLayout.LayoutParams) params;
        // FrameLayout lays out anything not centered or at the bottom from the top.
        int vertical = frame.gravity & Gravity.VERTICAL_GRAVITY_MASK;
        if (frame.gravity != -1 && vertical == Gravity.CENTER_VERTICAL) return;
        if (!moved.add(frame)) return;
        boolean atBottom = frame.gravity != -1 && vertical == Gravity.BOTTOM;
        if (scale != 1f) {
            child.setScaleX(scale);
            child.setScaleY(scale);
            pinToEdge(child, atBottom);
            child.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    pinToEdge(view, atBottom));
            return;
        }
        if (atBottom) {
            frame.bottomMargin -= shift;
        } else {
            frame.topMargin -= shift;
        }
        child.requestLayout();
    }

    /**
     * Shrinks a view toward the tab's top or bottom edge and its middle, so it lands where TikTok's
     * own tab would put it if the whole tab were drawn smaller.
     */
    private static void pinToEdge(View child, boolean atBottom) {
        if (!(child.getParent() instanceof View)) return;
        View tab = (View) child.getParent();
        child.setPivotX(tab.getWidth() / 2f - child.getLeft());
        child.setPivotY(atBottom ? tab.getHeight() - child.getTop() : -child.getTop());
    }

    /** A distance up from the screen's bottom edge, in dp, that clears the bar. */
    @SuppressWarnings("unused")
    public static float aboveBar(float dp) {
        return dp + heightDp() - TIKTOK_DP;
    }

    /** The room TikTok's screens leave at the bottom for the bar, in dp. */
    @SuppressWarnings("unused")
    public static int navHeight(int dp) {
        tikTokNavDp = dp;
        return roomFor(dp);
    }

    /**
     * TikTok's standard room for the bar, in dp, which the tab bar compares the room with: the same,
     * and each tab gets no padding at the top and 1.5 dp at the bottom; different, and 4 dp and 6 dp,
     * which pushes the icon and the name into each other. It moves with the room when the two matched,
     * so they still match, and stays put when they didn't.
     */
    @SuppressWarnings("unused")
    public static int standardNavHeight(int dp) {
        return dp == tikTokNavDp ? roomFor(dp) : dp;
    }

    private static int roomFor(int dp) {
        return Math.max(0, dp + heightDp() - TIKTOK_DP);
    }
}

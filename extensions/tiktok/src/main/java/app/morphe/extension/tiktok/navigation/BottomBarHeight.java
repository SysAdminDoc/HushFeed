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
 * tab, TikTok pins the icon to the top and the name to the bottom, so those two move half the
 * difference each and keep the gap TikTok's own bar gives them.
 */
public final class BottomBarHeight {
    public static final String TIKTOK = "tiktok";
    public static final String SHORTER = "40";
    public static final String SHORT = "44";
    public static final String TALL = "52";
    public static final String TALLER = "56";
    /** TikTok's own bar, in dp. */
    static final int TIKTOK_DP = 47;

    /** The picked height in dp, read once a launch like TikTok reads its own. */
    private static volatile int pickedDp;

    /** TikTok's bar in pixels, as its dimension last read before scaling. */
    private static volatile float tikTokBarPixels;

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
     * How far a tab's icon and name move toward each other, in pixels: half of what the bar gave
     * up, or less than nothing for a taller bar. Before the bar's dimension is read it goes by dp.
     */
    static int contentShift(float density) {
        int dp = heightDp();
        if (dp == TIKTOK_DP) return 0;
        float bar = tikTokBarPixels > 0 ? tikTokBarPixels : TIKTOK_DP * density;
        return Math.round(bar * (TIKTOK_DP - dp) / (2f * TIKTOK_DP));
    }

    /** A bottom tab, as it's made. Each view put in it from then on is moved as it goes in. */
    @SuppressWarnings("unused")
    public static void onBarCell(View cell) {
        try {
            if (!(cell instanceof ViewGroup)) return;
            int shift = contentShift(cell.getResources().getDisplayMetrics().density);
            if (shift == 0) return;
            ViewGroup tab = (ViewGroup) cell;
            for (int i = 0; i < tab.getChildCount(); i++) moveInTab(tab.getChildAt(i), shift);
            tab.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    moveInTab(child, shift);
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
     * it. A centered view, and one as tall as the tab, already sits right.
     */
    static void moveInTab(View child, int shift) {
        ViewGroup.LayoutParams params = child.getLayoutParams();
        if (!(params instanceof FrameLayout.LayoutParams) || params.height == ViewGroup.LayoutParams.MATCH_PARENT) return;
        FrameLayout.LayoutParams frame = (FrameLayout.LayoutParams) params;
        // FrameLayout lays out anything not centered or at the bottom from the top.
        int vertical = frame.gravity & Gravity.VERTICAL_GRAVITY_MASK;
        if (frame.gravity != -1 && vertical == Gravity.CENTER_VERTICAL) return;
        if (!moved.add(frame)) return;
        if (frame.gravity != -1 && vertical == Gravity.BOTTOM) {
            frame.bottomMargin -= shift;
        } else {
            frame.topMargin -= shift;
        }
        child.requestLayout();
    }

    /** A distance up from the screen's bottom edge, in dp, that clears the bar. */
    @SuppressWarnings("unused")
    public static float aboveBar(float dp) {
        return dp + heightDp() - TIKTOK_DP;
    }

    /** The room TikTok's screens leave at the bottom for the bar, in dp. */
    @SuppressWarnings("unused")
    public static int navHeight(int dp) {
        return Math.max(0, dp + heightDp() - TIKTOK_DP);
    }
}

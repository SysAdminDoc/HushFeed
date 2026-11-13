/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings;

import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;

/**
 * Padding for the system bars on the injected settings screen.
 *
 * <p>TikTok 46.2.3 targets SDK 36, so the app is drawn edge to edge whether it asks to be or not,
 * and there is no opting out from target 35. The two calls that used to colour the bars,
 * {@link android.view.Window#setStatusBarColor} and {@link
 * android.view.Window#setNavigationBarColor}, do nothing from that target either. Left alone, the
 * settings screen puts its first row under the status bar and its last under the navigation bar.
 *
 * <p>The top and side padding goes on the root view rather than the list inside it, because a
 * view paints its background across its padding: the screen still fills the display, and only the
 * rows move. The bottom is left to the pages ({@link #padUnderBottomBar}), so the rows scroll on
 * under the gesture bar instead of stopping at a band of the root's own colour (#408). Only the
 * keyboard pads the root's bottom, since nothing shows through it.
 */
public final class SystemBarInsets {
    private SystemBarInsets() {
    }

    /** Keeps {@code view} padded by the status bar, any display cutout and an open keyboard. */
    public static void applyTo(View view) {
        if (view == null) return;
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            Rect bars = systemBarsAndCutout(insets);
            target.setPadding(bars.left, bars.top, bars.right, keyboard(insets));
            return insets;
        });
        view.requestApplyInsets();
    }

    /**
     * Keeps {@code view}'s bottom padding at {@code base} plus the navigation bar under it, and
     * lets it draw into that padding, so a scrolling page runs on under the gesture bar while its
     * last row still scrolls clear of it.
     */
    public static void padUnderBottomBar(View view, int base) {
        if (view == null) return;
        if (view instanceof android.view.ViewGroup) ((android.view.ViewGroup) view).setClipToPadding(false);
        view.setPadding(view.getPaddingLeft(), view.getPaddingTop(), view.getPaddingRight(),
                base + underBottomBar(view.getRootWindowInsets()));
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            target.setPadding(target.getPaddingLeft(), target.getPaddingTop(), target.getPaddingRight(),
                    base + underBottomBar(insets));
            return insets;
        });
        requestWhenAttached(view);
    }

    /**
     * Keeps {@code view}'s bottom margin at {@code base} plus the navigation bar under it: a card
     * along the bottom of the screen sits above the gesture bar rather than behind it.
     */
    public static void liftAboveBottomBar(View view, int base) {
        if (view == null) return;
        setBottomMargin(view, base + underBottomBar(view.getRootWindowInsets()));
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            setBottomMargin(target, base + underBottomBar(insets));
            return insets;
        });
        requestWhenAttached(view);
    }

    /**
     * Asks the window to hand its insets out again once {@code view} is on screen. A view built
     * before its page is attached asks into nothing, and a page pushed onto the open settings
     * screen, like the Lab, never gets the insets the window already handed out, so its selection
     * bar sat behind the gesture bar (Login2, 2026-11-13, #408).
     */
    public static void requestWhenAttached(View view) {
        if (view == null) return;
        if (view.isAttachedToWindow()) {
            view.requestApplyInsets();
            return;
        }
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View attached) {
                attached.removeOnAttachStateChangeListener(this);
                attached.requestApplyInsets();
            }

            @Override
            public void onViewDetachedFromWindow(View detached) {
            }
        });
    }

    private static void setBottomMargin(View view, int margin) {
        if (!(view.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams)) return;
        android.view.ViewGroup.MarginLayoutParams params =
                (android.view.ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params.bottomMargin == margin) return;
        params.bottomMargin = margin;
        view.setLayoutParams(params);
    }

    /**
     * How far the navigation bar reaches over the settings screen's bottom: all of it, less
     * whatever an open keyboard already took off the root ({@link #applyTo}).
     */
    public static int underBottomBar(WindowInsets insets) {
        if (insets == null) return 0;
        return Math.max(0, systemBarsAndCutout(insets).bottom - keyboard(insets));
    }

    /** The open keyboard's height, or nothing where the typed insets API isn't there to ask. */
    static int keyboard(WindowInsets insets) {
        if (insets == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0;
        return insets.getInsets(WindowInsets.Type.ime()).bottom;
    }

    /** Current bars and cutout for an app-owned overlay attached to {@code view}'s window. */
    public static Rect current(View view) {
        return view == null ? new Rect() : systemBarsAndCutout(view.getRootWindowInsets());
    }

    /**
     * The space the bars and the cutout take, in pixels.
     *
     * <p>Both are asked for. A cutout is not always inside the status bar: held sideways, it sits
     * on an edge that the system window insets on their own do not cover.
     */
    static Rect systemBarsAndCutout(WindowInsets insets) {
        if (insets == null) return new Rect();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new Rect(bars.left, bars.top, bars.right, bars.bottom);
        }
        Rect rect = new Rect(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                rect.left = Math.max(rect.left, cutout.getSafeInsetLeft());
                rect.top = Math.max(rect.top, cutout.getSafeInsetTop());
                rect.right = Math.max(rect.right, cutout.getSafeInsetRight());
                rect.bottom = Math.max(rect.bottom, cutout.getSafeInsetBottom());
            }
        }
        return rect;
    }
}

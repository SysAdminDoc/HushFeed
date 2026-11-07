/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Keeps the start screen up until the tab a start opens on is showing (#360).
 *
 * <p>TikTok builds Home first on every start. A start on Friends, Inbox or Profile goes through
 * the handler TikTok uses when a notification opens that tab, which shows Home and waits for its
 * feed pager before it switches, and a start on Discover switches once Home is up. Either way For
 * You showed for a second or two before the tab the reader chose. A cover drawn like the start
 * screen sits over the window from the start until that tab's button in the bottom bar is the
 * selected one, and it comes off by itself after {@link #LONGEST_MS} whatever happens, so a start
 * that never reaches the tab still ends up on TikTok's own screen.
 */
final class StartCover {
    /** The longest the cover stays up, from the moment it goes on. */
    static final long LONGEST_MS = 8_000;

    private StartCover() {
    }

    /** Covers [activity]'s window until the bottom tab TikTok tags [tag] is the selected one. */
    static void show(Activity activity, String tag) {
        try {
            // The cover comes off when the tab's button is selected, and only a build whose tab
            // buttons the patch found reports them.
            if (!SettingsStatus.bottomTabLabelsEnabled) return;
            Window window = activity == null ? null : activity.getWindow();
            if (window == null || tag == null) return;
            String key = BottomNavigationTabOptions.normalizeRuntimeTag(tag);
            if (window.peekDecorView() != null) {
                attach(window, key);
                return;
            }
            // The window has no view yet this early in the activity's creation, and asking for one
            // now would build it before TikTok has set up its window. It exists once creation is
            // done, which is still long before Home draws.
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    attach(window, key);
                } catch (Throwable failure) {
                    Logger.printException(() -> "Start page could not cover Home", failure);
                }
            });
        } catch (Throwable failure) {
            Logger.printException(() -> "Start page could not cover Home", failure);
        }
    }

    private static void attach(Window window, String key) {
        View decor = window.peekDecorView();
        if (!(decor instanceof ViewGroup)) return;
        Cover cover = new Cover(decor.getContext(), key);
        cover.setBackground(lookOf(decor));
        ((ViewGroup) decor).addView(cover, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        HookStatus.bound(StartPage.FAMILY, "start cover");
    }

    /** The window's own background, which on a start is TikTok's start screen; black without one. */
    private static Drawable lookOf(View decor) {
        Drawable background = decor.getBackground();
        Drawable.ConstantState state = background == null ? null : background.getConstantState();
        Drawable copy = state == null ? null : state.newDrawable(decor.getResources());
        return copy != null ? copy : new ColorDrawable(Color.BLACK);
    }

    /** The cover itself: takes the touches meant for the Home under it, and checks before each frame. */
    static final class Cover extends View implements ViewTreeObserver.OnPreDrawListener {
        final String key;
        private final Runnable timeout = () -> lift("timed out");
        private boolean lifting;

        Cover(Context context, String key) {
            super(context);
            this.key = key;
            setClickable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnPreDrawListener(this);
            postDelayed(timeout, LONGEST_MS);
        }

        @Override
        protected void onDetachedFromWindow() {
            getViewTreeObserver().removeOnPreDrawListener(this);
            removeCallbacks(timeout);
            super.onDetachedFromWindow();
        }

        @Override
        public boolean onPreDraw() {
            try {
                // Taken off on the next turn of the main thread: a frame being drawn keeps its views.
                if (!lifting && BottomTabLabels.tabSelected(key)) {
                    lifting = true;
                    post(() -> lift("tab up"));
                }
            } catch (Throwable failure) {
                lifting = true;
                post(() -> lift("failed"));
                Logger.printException(() -> "Start page could not read the bottom bar", failure);
            }
            return true;
        }

        void lift(String why) {
            ViewParent parent = getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(this);
            HookStatus.bound(StartPage.FAMILY, "start cover off, " + why);
        }
    }
}

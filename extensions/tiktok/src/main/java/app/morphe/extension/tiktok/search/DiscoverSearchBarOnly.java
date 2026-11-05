/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.search;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.HorizontalScrollView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Show only the search bar on Discover (#338). TikTok's Discover tab, which some regions get in
 * place of Friends, is a search box over a pager of trending hashtags and banners, with a strip
 * of tabs between them. With the switch on, the pager and the strip go and the box stays, so
 * the tab is a way into search and nothing to scroll.
 *
 * <p>The page hands its view over once it's built. The switch is checked before every draw from
 * then on, so turning it on or off shows on the next frame, and whatever TikTok shows again
 * meanwhile (a pager it brings back after loading) goes again. Off, every view goes back to the
 * visibility it had when it was taken.
 */
@SuppressWarnings("unused")
public final class DiscoverSearchBarOnly {
    private static final Map<View, Keeper> PAGES = new WeakHashMap<>();

    private DiscoverSearchBarOnly() {
    }

    public static boolean isOn() {
        return SettingsStatus.hideSearchSuggestionsEnabled && Settings.DISCOVER_SEARCH_BAR_ONLY.get();
    }

    /** DiscoverAndSearchFragment.onViewCreated, with the page's view, before TikTok's own work. */
    public static void onViewCreated(View page) {
        try {
            if (!(page instanceof ViewGroup)) return;
            Keeper keeper = PAGES.get(page);
            if (keeper == null) {
                keeper = new Keeper((ViewGroup) page);
                PAGES.put(page, keeper);
                // Drawn only while it's on a window, and the window's observer lets go of it
                // when it leaves, so a closed page isn't held for the activity's life.
                page.addOnAttachStateChangeListener(keeper);
                if (page.isAttachedToWindow()) keeper.onViewAttachedToWindow(page);
            }
            keeper.apply();
        } catch (Throwable error) {
            Logger.printException(() -> "Could not leave Discover to its search bar", error);
        }
    }

    /** The views that go: the content pager and the tab strip, never anything inside them. */
    static List<View> contentOf(ViewGroup page) {
        List<View> found = new ArrayList<>();
        collect(page, found);
        return found;
    }

    private static void collect(ViewGroup group, List<View> found) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            if (isPager(child) || child instanceof HorizontalScrollView) {
                found.add(child);
            } else if (child instanceof ViewGroup) {
                collect((ViewGroup) child, found);
            }
        }
    }

    /** androidx's ViewPager or ViewPager2, under whatever subclass name TikTok gave it. */
    private static boolean isPager(View view) {
        for (Class<?> type = view.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
            String name = type.getSimpleName();
            if ("ViewPager".equals(name) || "ViewPager2".equals(name)) return true;
        }
        return false;
    }

    static void resetForTests() {
        PAGES.clear();
    }

    private static final class Keeper
            implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        // Weak: PAGES is keyed weakly on the page, and a strong value would keep its key.
        private final WeakReference<ViewGroup> page;
        /** Each view taken, with the visibility it had then. */
        private final Map<View, Integer> taken = new WeakHashMap<>();
        private ViewTreeObserver observer;

        Keeper(ViewGroup page) {
            this.page = new WeakReference<>(page);
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            if (observer != null && observer.isAlive()) return;
            observer = view.getViewTreeObserver();
            observer.addOnPreDrawListener(this);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
        }

        /** Whether anything changed, so the frame about to be drawn is stale. */
        boolean apply() {
            ViewGroup page = this.page.get();
            if (page == null) return false;
            boolean changed = false;
            if (isOn()) {
                for (View view : contentOf(page)) {
                    if (!taken.containsKey(view)) taken.put(view, view.getVisibility());
                    if (view.getVisibility() != View.GONE) {
                        view.setVisibility(View.GONE);
                        changed = true;
                    }
                }
            } else if (!taken.isEmpty()) {
                for (Map.Entry<View, Integer> entry : taken.entrySet()) {
                    View view = entry.getKey();
                    if (view != null && view.getVisibility() != entry.getValue()) {
                        view.setVisibility(entry.getValue());
                        changed = true;
                    }
                }
                taken.clear();
            }
            return changed;
        }

        @Override
        public boolean onPreDraw() {
            try {
                // A change lays the page out again first, so the frame drawn is the new one.
                return !apply();
            } catch (Throwable error) {
                Logger.printException(() -> "Could not keep Discover to its search bar", error);
                return true;
            }
        }
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hides Jam Sound and Use sound, the two buttons that float at the bottom of a sound's page (the
 * page the spinning record on a video opens), #535.
 *
 * <p>The patch calls {@link #onResume(Activity)} from the page's own onResume
 * ({@code MusicDetailActivity}). Their view ids are renamed in every TikTok build and their labels
 * are in the viewer's language, so they're found by what they are: buttons in the lower part of
 * the page that aren't in its list of videos or the header's row of buttons. What's hidden is each
 * button's own tappable box, so Use sound's icon goes with it. TikTok may show them again once the
 * sound loads, so a layout listener hides them again, and switching it off shows them as they were.
 */
public final class SoundPageButtons {
    /** A button whose top edge is below this share of the page's height is a bottom button. */
    static final float LOWER_PART = 0.55f;
    /** A page that never shows the buttons costs one tree walk per this long, not one per layout. */
    private static final long SEARCH_GAP_MS = 250;
    /** How far above the button its tappable box may start, in views. */
    private static final int BOX_LEVELS = 3;

    private static final Map<Activity, Watcher> WATCHERS = new WeakHashMap<>();

    private SoundPageButtons() {
    }

    public static boolean enabled() {
        return SettingsStatus.hideSoundPageButtonsEnabled && Settings.HIDE_SOUND_PAGE_BUTTONS.get()
                && !Setting.isPaused();
    }

    /** Called at the start of the sound page's onResume, on the main thread. */
    public static void onResume(Activity activity) {
        try {
            if (activity == null) return;
            Watcher watcher = WATCHERS.get(activity);
            if (!enabled()) {
                if (watcher != null) {
                    WATCHERS.remove(activity);
                    watcher.stop();
                }
                return;
            }
            if (watcher == null) {
                watcher = new Watcher(activity);
                WATCHERS.put(activity, watcher);
                watcher.listen();
            }
            watcher.hide();
        } catch (Throwable error) {
            Logger.printException(() -> "Hide sound page buttons failed", error);
        }
    }

    /** One page's buttons: hides them, keeps them hidden, and puts them back when it stops. */
    private static final class Watcher implements ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<Activity> page;
        /** Each box this hid and the visibility it had before. */
        private final Map<View, Integer> hidden = new WeakHashMap<>();
        private long lastSearch;
        private boolean listening;

        Watcher(Activity activity) {
            page = new WeakReference<>(activity);
        }

        void listen() {
            Activity activity = page.get();
            if (activity == null || listening) return;
            activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(this);
            listening = true;
        }

        void stop() {
            Activity activity = page.get();
            if (activity != null && listening) {
                ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            }
            listening = false;
            for (Map.Entry<View, Integer> box : new ArrayList<>(hidden.entrySet())) {
                if (box.getKey().getVisibility() == View.GONE) box.getKey().setVisibility(box.getValue());
            }
            hidden.clear();
        }

        @Override
        public void onGlobalLayout() {
            try {
                if (!enabled()) return;
                // A box TikTok showed again goes straight back, without a search.
                for (View box : new ArrayList<>(hidden.keySet())) {
                    if (box.getVisibility() != View.GONE) box.setVisibility(View.GONE);
                }
                long now = SystemClock.uptimeMillis();
                if (now - lastSearch < SEARCH_GAP_MS) return;
                lastSearch = now;
                hide();
            } catch (Throwable error) {
                Logger.printException(() -> "Hide sound page buttons failed", error);
            }
        }

        void hide() {
            Activity activity = page.get();
            if (activity == null || activity.isFinishing() || !enabled()) return;
            View content = activity.findViewById(android.R.id.content);
            if (content == null || content.getHeight() <= 0) return;
            int count = 0;
            for (View box : bottomButtons(content)) {
                if (hidden.containsKey(box)) continue;
                hidden.put(box, box.getVisibility());
                box.setVisibility(View.GONE);
                count++;
            }
            if (count > 0) {
                int hiddenNow = count;
                Logger.printDebug(() -> "Sound page: hid " + hiddenNow + " bottom button(s)");
            }
        }
    }

    /** Each bottom button's tappable box: buttons low on the page that aren't in a scrolling list. */
    static List<View> bottomButtons(View content) {
        List<View> boxes = new ArrayList<>();
        int[] origin = new int[2];
        content.getLocationInWindow(origin);
        collect(content, content, origin[1], content.getHeight(), boxes);
        return boxes;
    }

    private static void collect(View view, View content, int contentTop, int height, List<View> into) {
        if (view.getVisibility() != View.VISIBLE || isHushfeeds(view)) return;
        if (view != content && scrollsItsContent(view)) return;
        boolean button = isButton(view);
        if (button || view instanceof TextView) {
            int[] at = new int[2];
            view.getLocationInWindow(at);
            int top = at[1] - contentTop;
            if (view.getHeight() > 0 && top >= height * LOWER_PART && top + view.getHeight() <= height
                    && (button || announcesButton(view))) {
                View box = boxOf(view);
                if (!into.contains(box)) into.add(box);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), content, contentTop, height, into);
            }
        }
    }

    /** TikTok draws Jam Sound as its own button type, which reports itself to accessibility as a Button. */
    private static boolean isButton(View view) {
        return view instanceof Button || isButtonRole(view.getAccessibilityClassName());
    }

    /**
     * Use sound's label is a text view that only calls itself a Button while it takes taps, and it
     * doesn't (its box does), so its role comes from its accessibility delegate. That's read from
     * the node it gives accessibility, as a screen reader would, and only for text low on the page.
     */
    private static boolean announcesButton(View view) {
        AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        try {
            return isButtonRole(node.getClassName());
        } finally {
            if (Build.VERSION.SDK_INT < 33) node.recycle();
        }
    }

    private static boolean isButtonRole(CharSequence role) {
        return role != null && Button.class.getName().contentEquals(role);
    }

    /** The button's own tappable box: itself when it takes the tap, else the small parent that does. */
    private static View boxOf(View button) {
        if (button.isClickable()) return button;
        View at = button;
        for (int level = 0; level < BOX_LEVELS; level++) {
            ViewParent parent = at.getParent();
            if (!(parent instanceof View)) break;
            at = (View) parent;
            // Taller than the button by much means it's the page's layer, not the button's box.
            if (at.getHeight() > button.getHeight() * 2) break;
            if (at.isClickable()) return at;
        }
        return button;
    }

    /** The list of videos, the header's row of buttons, and anything else that scrolls. */
    private static boolean scrollsItsContent(View view) {
        if (view instanceof ScrollView || view instanceof HorizontalScrollView || view instanceof AbsListView) {
            return true;
        }
        for (Class<?> type = view.getClass(); type != null && type != View.class; type = type.getSuperclass()) {
            String name = type.getName();
            if (name.endsWith("RecyclerView") || name.endsWith("NestedScrollView") || name.endsWith("ViewPager")
                    || name.endsWith("ViewPager2")) return true;
        }
        return false;
    }

    /** Hushfeed's own rows, like Block sound under Save, are tagged and never hidden here. */
    private static boolean isHushfeeds(View view) {
        Object tag = view.getTag();
        return tag instanceof String && ((String) tag).startsWith("hushfeed_");
    }
}

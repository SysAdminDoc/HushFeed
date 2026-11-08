/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.HorizontalScrollView;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceIdCache;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Only receives the three dedicated native header roots, never a sheet or comment row. */
public final class CompactCommentHeader {
    private static final WeakHashMap<View, WeakReference<Binding>> BINDINGS = new WeakHashMap<>();
    /**
     * A child of the row that holds the panel's close and translate buttons. Since 47.7.2 that
     * row is laid over the bar's container instead of sitting in the bar, so collapsing the bar
     * alone leaves both buttons floating over the first comment (#358).
     */
    private static final String BUTTON_ROW_ANCHOR = "translate_intro_anchor";
    /** How many of the bar's ancestors are searched for the row: bar slot, column, container. */
    private static final int BUTTON_ROW_DEPTH = 4;
    private static final ResourceIdCache RESOURCE_IDS = new ResourceIdCache();

    public static void bindCountBar(View view) { bind(view, "count bar"); }
    public static void bindLegacyBanner(View view) { bind(view, "legacy banner"); }
    public static void bindCommonBanner(View view) { bind(view, "common banner"); }

    private static void bind(View view, String component) {
        if (view == null) return;
        HookStatus.bound("compact comment header", component);
        // A folded comment search puts its button in this bar, compact or not (#254).
        if ("count bar".equals(component)) {
            CommentSearch.onHeaderBound(view);
            CommentHeaderButtons.onHeaderBound(view);
        }
        if (!Settings.COMPACT_COMMENT_HEADER.get()) return;
        try {
            WeakReference<Binding> existing = BINDINGS.get(view);
            Binding binding = existing == null ? null : existing.get();
            if (binding == null) {
                binding = new Binding(view, "count bar".equals(component));
                BINDINGS.put(view, new WeakReference<>(binding));
                view.addOnAttachStateChangeListener(binding);
            }
            binding.watch();
            // The hook runs before native initialization. Apply after it has populated the root.
            view.post(binding);
        } catch (Exception error) {
            Logger.printException(() -> "Could not bind compact comment header", error);
        }
    }

    static void resolveForTests(String name, int id) {
        RESOURCE_IDS.putForTests(Utils.getContext().getPackageName(), name, id);
    }

    private static final class Binding implements Runnable, View.OnAttachStateChangeListener,
            ViewTreeObserver.OnGlobalLayoutListener {
        final WeakReference<View> root;
        /** The count bar keeps native list tabs and takes the close and translate row with it. */
        final boolean countBar;
        ViewTreeObserver observer;
        boolean hidden;
        int height, minimumHeight, visibility, accessibility, topMargin, bottomMargin;
        WeakReference<View> buttonRow;
        boolean buttonRowSearched, buttonRowHidden;
        int buttonRowVisibility;

        Binding(View view, boolean countBar) {
            root = new WeakReference<>(view);
            this.countBar = countBar;
        }

        void watch() {
            View view = root.get();
            if (view == null || !view.isAttachedToWindow() || observer != null) return;
            observer = view.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(this);
        }

        void unwatch() {
            if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            observer = null;
        }

        @Override public void run() {
            View view = root.get();
            if (view == null) return;
            try {
                // Own-post viewer lists and other tabbed panels still need their native navigation.
                if (!Settings.COMPACT_COMMENT_HEADER.get() || (countBar && hasListTabs(view, 3))) {
                    restore(view);
                    return;
                }
                ViewGroup.LayoutParams params = view.getLayoutParams();
                if (params == null) return;
                if (!hidden) {
                    height = params.height;
                    minimumHeight = view.getMinimumHeight();
                    visibility = view.getVisibility();
                    accessibility = view.getImportantForAccessibility();
                    if (params instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                        topMargin = margins.topMargin;
                        bottomMargin = margins.bottomMargin;
                    }
                    hidden = true;
                }
                boolean changed = params.height != 0;
                params.height = 0;
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    changed |= margins.topMargin != 0 || margins.bottomMargin != 0;
                    margins.topMargin = margins.bottomMargin = 0;
                }
                if (changed) view.setLayoutParams(params);
                if (view.getMinimumHeight() != 0) view.setMinimumHeight(0);
                if (view.getVisibility() != View.GONE) view.setVisibility(View.GONE);
                if (view.getImportantForAccessibility() != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                if (countBar) hideButtonRow(view);
            } catch (Exception error) {
                unwatch();
                restore(view);
                Logger.printException(() -> "Could not collapse compact comment header", error);
            }
        }

        /** Hides the close and translate row when it sits beside the bar, never one holding it. */
        private void hideButtonRow(View bar) {
            View row = buttonRow == null ? null : buttonRow.get();
            if (row == null && !buttonRowSearched && bar.isAttachedToWindow()) {
                // The row is inflated with the panel, before the bar is bound, so one search
                // per attach finds it; searching every layout would walk the comment list too.
                buttonRowSearched = true;
                row = findButtonRow(bar);
                buttonRow = row == null ? null : new WeakReference<>(row);
            }
            if (row == null) return;
            if (!buttonRowHidden) {
                buttonRowVisibility = row.getVisibility();
                buttonRowHidden = true;
            }
            if (row.getVisibility() != View.GONE) row.setVisibility(View.GONE);
        }

        private static View findButtonRow(View bar) {
            int id = RESOURCE_IDS.resolve(bar.getResources(), bar.getContext().getPackageName(),
                    BUTTON_ROW_ANCHOR, false);
            if (id == 0) return null;
            ViewParent parent = bar.getParent();
            for (int depth = 0; depth < BUTTON_ROW_DEPTH && parent instanceof ViewGroup; depth++) {
                View anchor = ((ViewGroup) parent).findViewById(id);
                if (anchor != null) {
                    // Only a row laid straight over the container counts, so a panel that
                    // keeps the anchor anywhere else, or in the bar itself, is left alone.
                    ViewParent row = anchor.getParent();
                    if (!(row instanceof ViewGroup) || row.getParent() != parent
                            || holds((View) row, bar)) return null;
                    return (View) row;
                }
                parent = parent.getParent();
            }
            return null;
        }

        private static boolean holds(View group, View view) {
            for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent()) {
                if (parent == group) return true;
            }
            return false;
        }

        private void restoreButtonRow() {
            View row = buttonRow == null ? null : buttonRow.get();
            if (buttonRowHidden && row != null) row.setVisibility(buttonRowVisibility);
            buttonRowHidden = false;
        }

        void restore(View view) {
            restoreButtonRow();
            if (!hidden) return;
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) {
                params.height = height;
                if (params instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                    margins.topMargin = topMargin;
                    margins.bottomMargin = bottomMargin;
                }
                view.setLayoutParams(params);
            }
            view.setMinimumHeight(minimumHeight);
            view.setVisibility(visibility);
            view.setImportantForAccessibility(accessibility);
            hidden = false;
        }

        @Override public void onGlobalLayout() { run(); }
        @Override public void onViewAttachedToWindow(View view) {
            buttonRowSearched = false;
            watch();
            view.post(this);
        }
        @Override public void onViewDetachedFromWindow(View view) {
            unwatch();
            view.removeCallbacks(this);
            restore(view);
        }

        private boolean hasListTabs(View view, int depth) {
            if (!(view instanceof ViewGroup) || depth == 0) return false;
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = group.getChildAt(index);
                if (child.getVisibility() != View.VISIBLE) continue;
                if (child instanceof HorizontalScrollView || hasListTabs(child, depth - 1)) return true;
            }
            return false;
        }
    }

    private CompactCommentHeader() { }
}

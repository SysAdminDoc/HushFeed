/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Split activity list: the Activity page's "all activity" view becomes two of TikTok's own lists,
 * Comments on top and Likes below, each with its own scrolling, paging, rows and taps. The bar
 * between them swaps the two lists or closes the lower one.
 *
 * <p>The page keeps one native list per entry of its type dropdown in a pager (All, Likes,
 * Comments, Mentions). This adds a panel over that pager's area that hosts two more of the same
 * fragments, one per group, and shows the panel only while the pager is on its first page, the
 * one the dropdown calls Activity. Picking any other entry hides the panel and TikTok's own list
 * shows as before, so the dropdown, its title and Mentions keep working.
 *
 * <p>androidx's FragmentManager is renamed in TikTok; the patch adds three static methods with
 * fixed names to NotificationFragmentV2 (list, add and remove child fragments) that this calls.
 */
public final class ActivitySplit {
    static final int COMMENTS_GROUP = 44;
    static final int LIKES_GROUP = 3;
    private static final String RESULT_FRAGMENT = "com.ss.android.ugc.aweme.notification.v2.NotificationResultFragment";
    private static final String ARGUMENT_KEY = "power_viewpager_default_key";
    /** Marks the lists this adds, so a restored state's copies can be told from TikTok's own. */
    static final String MARKER = "hushfeed_split_group";
    private static final int ATTACH_ATTEMPTS = 40;
    private static final long ATTACH_RETRY_MS = 100;
    private static final int SELECT_ATTEMPTS = 30;

    /**
     * The panel over each type pager. Held weakly: a panel's views live in the pager's own tree,
     * so a strong value would keep its key, and the whole page, alive after the page is gone. The
     * panel's buttons keep it alive while the page is shown.
     */
    private static final Map<Object, WeakReference<Panel>> PANELS = Collections.synchronizedMap(new WeakHashMap<>());

    private ActivitySplit() {}

    /** Called at the end of NotificationFragmentV2.onViewCreated(view, state). */
    public static void onPageViewCreated(Object page, View root) {
        try {
            if (page == null || root == null) return;
            // Copies a restored state brought back would look for containers this view no longer
            // has. The child views are created only after onViewCreated returns, so removing them
            // here is in time.
            removeOwnLists(page);
            if (!Settings.SPLIT_ACTIVITY_LIST.get()) return;
            View pager = findPager(root);
            if (pager == null || !(pager.getParent() instanceof FrameLayout)) return;
            Panel panel = new Panel(page, (FrameLayout) pager.getParent(), pager);
            PANELS.put(pager, new WeakReference<>(panel));
            panel.install();
        } catch (Throwable failure) {
            Logger.printException(() -> "Split activity list: page skipped", failure);
        }
    }

    /** Called first in the type pager's setCurrentItem(index, smooth). */
    public static void onPagerSelect(Object pager, int index) {
        try {
            Panel panel = panelOf(pager);
            if (panel != null) panel.showFor(index);
        } catch (Throwable failure) {
            Logger.printException(() -> "Split activity list: page switch skipped", failure);
        }
    }

    /**
     * Called at the end of TikTok's emoji catalog publish, on its loader thread. Rows bound before
     * the catalog arrived show emoji codes as text and TikTok doesn't redraw them, so the split's
     * lists are redrawn once it's there.
     */
    public static void onEmojiCatalogReady() {
        try {
            if (PANELS.isEmpty()) return;
            new Handler(Looper.getMainLooper()).post(() -> {
                List<WeakReference<Panel>> panels;
                synchronized (PANELS) { panels = new ArrayList<>(PANELS.values()); }
                for (WeakReference<Panel> reference : panels) {
                    Panel panel = reference.get();
                    if (panel != null) panel.redrawLists();
                }
            });
        } catch (Throwable failure) {
            Logger.printException(() -> "Split activity list: emoji redraw skipped", failure);
        }
    }

    static Panel panelOf(Object pager) {
        WeakReference<Panel> reference = PANELS.get(pager);
        return reference == null ? null : reference.get();
    }

    /** Marks every row of each list under [view] changed, so TikTok binds them again. */
    static void redrawListsUnder(View view) {
        Object adapter = view instanceof ViewGroup ? Reflect.invoke(view, "getAdapter") : null;
        if (adapter != null) {
            try {
                Object count = Reflect.invoke(adapter, "getItemCount");
                if (count instanceof Integer && (Integer) count > 0) {
                    adapter.getClass().getMethod("notifyItemRangeChanged", int.class, int.class).invoke(adapter, 0, count);
                }
            } catch (ReflectiveOperationException notAList) {
                // An adapter without RecyclerView's change calls: not one of TikTok's lists.
            }
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) redrawListsUnder(group.getChildAt(i));
    }

    /** The type pager: TikTok's FrameLayout that keeps a ViewPager2 in a field named viewPager2. */
    static View findPager(View view) {
        if (Reflect.field(view.getClass(), "viewPager2") != null && view instanceof FrameLayout) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findPager(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /**
     * The color TikTok paints behind its Activity lists: the nearest opaque color background at
     * or above [from], else the theme's window background. AMOLED dark theme recolors both, so
     * the panel follows it.
     */
    static int pageColor(View from, boolean dark) {
        for (View view = from; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            Drawable background = view.getBackground();
            if (background instanceof ColorDrawable && Color.alpha(((ColorDrawable) background).getColor()) == 0xFF) {
                return ((ColorDrawable) background).getColor();
            }
        }
        TypedValue value = new TypedValue();
        if (from.getContext().getTheme().resolveAttribute(android.R.attr.windowBackground, value, true)
                && value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT
                && Color.alpha(value.data) == 0xFF) {
            return value.data;
        }
        return dark ? Color.BLACK : Color.WHITE;
    }

    /** [base] moved [amount] of the way to [toward], opaque. */
    static int blend(int base, int toward, float amount) {
        int r = Math.round(Color.red(base) + (Color.red(toward) - Color.red(base)) * amount);
        int g = Math.round(Color.green(base) + (Color.green(toward) - Color.green(base)) * amount);
        int b = Math.round(Color.blue(base) + (Color.blue(toward) - Color.blue(base)) * amount);
        return Color.rgb(r, g, b);
    }

    private static void removeOwnLists(Object page) throws ReflectiveOperationException {
        for (Object child : children(page)) {
            if (groupMark(child) != null) bridge(page, "hushSplitRemove").invoke(null, page, child);
        }
    }

    static List<?> children(Object page) throws ReflectiveOperationException {
        Object list = bridge(page, "hushSplitFragments").invoke(null, page);
        return list instanceof List ? new ArrayList<>((List<?>) list) : Collections.emptyList();
    }

    static Integer groupMark(Object fragment) {
        Object arguments = Reflect.invoke(fragment, "getArguments");
        if (!(arguments instanceof Bundle) || !((Bundle) arguments).containsKey(MARKER)) return null;
        return ((Bundle) arguments).getInt(MARKER);
    }

    /** One of the static methods the patch added to NotificationFragmentV2 (they take arguments). */
    private static Method bridge(Object page, String name) throws NoSuchMethodException {
        for (Class<?> type = page.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    /** The panel's column, which can reorder its rows without them leaving the window. */
    static final class Rows extends LinearLayout {
        Rows(Context context) { super(context); }

        /**
         * Puts [order] first, in that order. A row already here moves with detach/attachViewToParent,
         * which skips onDetachedFromWindow: removeView would tell every view in a moved list it left
         * the window, and Search loaded activity takes that as its list being closed.
         */
        void reorder(View... order) {
            for (int i = 0; i < order.length; i++) {
                View view = order[i];
                if (view.getParent() != this) {
                    addView(view, i);
                } else if (indexOfChild(view) != i) {
                    detachViewFromParent(view);
                    attachViewToParent(view, i, view.getLayoutParams());
                }
            }
            requestLayout();
            invalidate();
        }
    }

    /**
     * The panel over one page's pager, top to bottom: the upper list's title, the upper list, the
     * bar between the lists (the lower list's title with the swap and close buttons), the lower
     * list, and the bar that brings a closed lower list back. The bars stay where they are; a
     * swap only trades the lists' places and the two titles.
     */
    static final class Panel {
        private final WeakReference<Object> page;
        private final WeakReference<FrameLayout> host;
        private final WeakReference<View> pager;
        Rows root;
        View topHeader, middleBar, commentsBox, likesBox;
        TextView topTitle, middleTitle, closeButton, restore;
        private int commentsId, likesId, text;
        private boolean attached, likesFirst, bottomHidden, dark;

        Panel(Object page, FrameLayout host, View pager) {
            this.page = new WeakReference<>(page);
            this.host = new WeakReference<>(host);
            this.pager = new WeakReference<>(pager);
        }

        void install() {
            FrameLayout frame = host.get();
            View list = pager.get();
            if (frame == null || list == null) return;
            build(frame.getContext(), Settings.SPLIT_ACTIVITY_LIKES_FIRST.get(),
                    Settings.SPLIT_ACTIVITY_BOTTOM_HIDDEN.get());
            frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
            // Only an attached view sees the whole chain up to the window's own background.
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { paint(pageColor(list, dark)); }
                @Override public void onViewDetachedFromWindow(View view) {}
            });
            if (root.isAttachedToWindow()) paint(pageColor(list, dark));
            Object current = Reflect.invoke(list, "getCurrentItem");
            showFor(current instanceof Integer ? (Integer) current : 0);
            attachLists(0);
        }

        /** Creates the panel's views in the given order and state, without any list in them yet. */
        void build(Context context, boolean likesFirst, boolean bottomHidden) {
            dark = SettingsUi.isDarkContext(context);
            text = dark ? Color.WHITE : Color.BLACK;
            int touch = SettingsUi.dp(context, 48);

            root = new Rows(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setClickable(true);

            topTitle = title(context, text);
            topHeader = header(context, topTitle, null);
            topHeader.setLayoutParams(wrap());

            LinearLayout controls = new LinearLayout(context);
            controls.setOrientation(LinearLayout.HORIZONTAL);
            controls.addView(control(context, "⇅", L10n.t(context, "Swap lists"), text, touch,
                    view -> setLikesFirst(!this.likesFirst)));
            closeButton = control(context, "✕", "", text, touch, view -> setBottomHidden(true));
            controls.addView(closeButton);
            middleTitle = title(context, text);
            middleBar = header(context, middleTitle, controls);
            middleBar.setLayoutParams(wrap());

            commentsId = View.generateViewId();
            commentsBox = box(context, commentsId);
            likesId = View.generateViewId();
            likesBox = box(context, likesId);

            restore = new TextView(context);
            restore.setTextSize(15);
            restore.setTypeface(Typeface.DEFAULT_BOLD);
            restore.setTextColor(text);
            restore.setGravity(Gravity.CENTER);
            restore.setMinHeight(touch);
            restore.setOnClickListener(view -> setBottomHidden(false));
            LinearLayout.LayoutParams restoreParams = wrap();
            int margin = SettingsUi.dp(context, 12);
            restoreParams.setMargins(margin, margin / 2, margin, margin);
            root.addView(restore, restoreParams);

            paint(dark ? Color.BLACK : Color.WHITE);
            this.bottomHidden = bottomHidden;
            arrange(likesFirst);
        }

        /** The panel takes the page's color; the bar that brings a list back sits a shade off it. */
        void paint(int background) {
            root.setBackgroundColor(background);
            restore.setBackgroundColor(blend(background, text, 0.12f));
        }

        void showFor(int index) {
            if (root != null) root.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        }

        private void setLikesFirst(boolean likesFirst) {
            Settings.SPLIT_ACTIVITY_LIKES_FIRST.save(likesFirst);
            arrange(likesFirst);
        }

        /**
         * Puts the lists in order around the bar between them and names them. Each list stays in
         * its own container and the containers never leave the window, so a list keeps its
         * fragment, scroll position, loaded pages and anything attached to it, like a search.
         */
        void arrange(boolean likesFirst) {
            this.likesFirst = likesFirst;
            root.reorder(topHeader, upper(), middleBar, lower());
            Context context = root.getContext();
            topTitle.setText(L10n.t(context, likesFirst ? "Likes" : "Comments"));
            middleTitle.setText(L10n.t(context, likesFirst ? "Comments" : "Likes"));
            closeButton.setContentDescription(L10n.t(context, likesFirst ? "Hide comments" : "Hide likes"));
            restore.setText((likesFirst ? "💬  " : "♥  ") + L10n.t(context, likesFirst ? "Show comments" : "Show likes"));
            applyBottomHidden(bottomHidden);
        }

        void redrawLists() {
            if (root == null) return;
            redrawListsUnder(commentsBox);
            redrawListsUnder(likesBox);
        }

        View upper() { return likesFirst ? likesBox : commentsBox; }

        View lower() { return likesFirst ? commentsBox : likesBox; }

        private void setBottomHidden(boolean hidden) {
            Settings.SPLIT_ACTIVITY_BOTTOM_HIDDEN.save(hidden);
            applyBottomHidden(hidden);
        }

        /** A closed lower list takes the bar between the lists with it; the upper list fills the page. */
        void applyBottomHidden(boolean hidden) {
            bottomHidden = hidden;
            upper().setVisibility(View.VISIBLE);
            middleBar.setVisibility(hidden ? View.GONE : View.VISIBLE);
            lower().setVisibility(hidden ? View.GONE : View.VISIBLE);
            restore.setVisibility(hidden ? View.VISIBLE : View.GONE);
        }

        /**
         * The pager's own first list is the template: its arguments carry the group filter's
         * texts and placeholder. It may not exist yet when the view is created, so this retries.
         */
        private void attachLists(int attempt) {
            FrameLayout frame = host.get();
            Object owner = page.get();
            if (frame == null || owner == null || attached) return;
            try {
                Parcelable template = null;
                for (Object child : children(owner)) {
                    if (!child.getClass().getName().equals(RESULT_FRAGMENT) || groupMark(child) != null) continue;
                    Object arguments = Reflect.invoke(child, "getArguments");
                    if (arguments instanceof Bundle) {
                        Parcelable data = ((Bundle) arguments).getParcelable(ARGUMENT_KEY);
                        if (data != null && Boolean.TRUE.equals(Reflect.readField(data, "isAllTab"))) template = data;
                    }
                }
                if (template == null) {
                    if (attempt < ATTACH_ATTEMPTS) frame.postDelayed(() -> attachLists(attempt + 1), ATTACH_RETRY_MS);
                    else uninstall("no Activity list to copy");
                    return;
                }
                if (Boolean.TRUE.equals(Reflect.readField(template, "isCreator"))) {
                    uninstall("creator accounts have their own tabs");
                    return;
                }
                attached = true;
                Object comments = newList(template, COMMENTS_GROUP);
                Object likes = newList(template, LIKES_GROUP);
                bridge(owner, "hushSplitAdd").invoke(null, owner, commentsId, comments, "hushfeed_split_comments");
                bridge(owner, "hushSplitAdd").invoke(null, owner, likesId, likes, "hushfeed_split_likes");
                select(comments, 0);
                select(likes, 0);
            } catch (Throwable failure) {
                Logger.printException(() -> "Split activity list: lists not added", failure);
                uninstall("lists not added");
            }
        }

        /** A NotificationResultFragment for [group], built like the pager builds its own. */
        private Object newList(Parcelable template, int group) throws ReflectiveOperationException {
            Object filter = Reflect.readField(template, "groupFilter");
            Constructor<?> filterConstructor = filter.getClass().getConstructor(
                    int.class, int.class, int.class, int.class, int.class, Integer.class);
            Object groupFilter = filterConstructor.newInstance(group,
                    Reflect.readField(filter, "titleResId"), Reflect.readField(filter, "groupIconId"),
                    Reflect.readField(filter, "titleId"), Reflect.readField(filter, "descId"),
                    Reflect.readField(filter, "placeHolderResId"));
            Class<?> dataType = template.getClass();
            Constructor<?> dataConstructor = null;
            for (Constructor<?> candidate : dataType.getConstructors()) {
                Class<?>[] types = candidate.getParameterTypes();
                if (types.length == 5 && types[0] == boolean.class && types[1] == boolean.class
                        && types[3].isInstance(groupFilter) && types[4] == Integer.class) dataConstructor = candidate;
            }
            if (dataConstructor == null) throw new NoSuchMethodException("ResultFragmentData(5)");
            Object data = dataConstructor.newInstance(false, false, null, groupFilter,
                    Reflect.readField(template, "collapsedListType"));
            Object fragment = Class.forName(RESULT_FRAGMENT, true, template.getClass().getClassLoader())
                    .getConstructor().newInstance();
            Bundle arguments = new Bundle();
            arguments.putParcelable(ARGUMENT_KEY, (Parcelable) data);
            arguments.putInt(MARKER, group);
            fragment.getClass().getMethod("setArguments", Bundle.class).invoke(fragment, arguments);
            return fragment;
        }

        /** The pager tells its lists they are on screen; these lists hear it from here, once they have a view. */
        private void select(Object fragment, int attempt) {
            FrameLayout frame = host.get();
            if (frame == null) return;
            if (!(Reflect.invoke(fragment, "getView") instanceof View)) {
                if (attempt < SELECT_ATTEMPTS) frame.postDelayed(() -> select(fragment, attempt + 1), ATTACH_RETRY_MS);
                return;
            }
            // After the list's own setup in the same frame, so its widget container exists.
            frame.post(() -> {
                try {
                    fragment.getClass().getMethod("onPageSelected", int.class).invoke(fragment, 0);
                } catch (Throwable failure) {
                    Logger.printException(() -> "Split activity list: list not started", failure);
                }
            });
        }

        private void uninstall(String why) {
            Logger.printInfo(() -> "Split activity list: " + why);
            FrameLayout frame = host.get();
            if (frame != null && root != null) frame.removeView(root);
            root = null;
        }

        private static TextView title(Context context, int color) {
            TextView label = new TextView(context);
            label.setTextSize(15);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setTextColor(color);
            return label;
        }

        private static View header(Context context, TextView label, View trailing) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int side = SettingsUi.dp(context, 16);
            row.setPadding(side, SettingsUi.dp(context, 8), trailing == null ? side : 0, SettingsUi.dp(context, 4));
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            if (trailing != null) row.addView(trailing, new LinearLayout.LayoutParams(-2, -2));
            return row;
        }

        /** The container one list's fragment is added to. */
        private static View box(Context context, int id) {
            FrameLayout box = new FrameLayout(context);
            box.setId(id);
            box.setLayoutParams(weighted());
            return box;
        }

        private static TextView control(Context context, String glyph, String description, int color, int touch,
                                    View.OnClickListener action) {
            TextView button = new TextView(context);
            button.setText(glyph);
            button.setTextSize(16);
            button.setTextColor(color);
            button.setGravity(Gravity.CENTER);
            button.setMinWidth(touch);
            button.setMinHeight(touch);
            button.setContentDescription(description);
            button.setOnClickListener(action);
            return button;
        }

        private static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }
        private static LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(-1, 0, 1); }
    }
}

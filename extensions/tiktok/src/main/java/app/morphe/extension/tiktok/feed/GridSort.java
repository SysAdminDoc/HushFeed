/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * Sorts the videos a profile or Liked grid has loaded (#180). The patch hands this the list
 * TikTok is about to give the grid's adapter, and the adapter keeps the list it gets back. TikTok
 * hands over its whole list again with each page, so a new page is sorted in with the rest and
 * nothing repeats.
 *
 * <p>TikTok's video page plays that same list, so as a cell opens its video the list gets the
 * order the grid shows, and swiping goes on in the sorted order ({@link #beforeOpen}). From then
 * on TikTok's own order is rebuilt from the copy kept before that write, with each later video
 * added where TikTok put it, which is how Original order, switching the setting off and pausing
 * Hushfeed still bring TikTok's order back.
 *
 * <p>Pinned videos keep their place at the front in the order TikTok has them. A video with no
 * value for the chosen order (no length, no counts, no date) goes after the ones that have one,
 * in the order TikTok had them, and ties between equal values keep TikTok's order too, so a
 * sort gives the same grid every time.
 *
 * <p>While an order is chosen, a grid on screen asks for its next page as each one comes in,
 * the way scrolling to its end would, until TikTok has no more, a page adds nothing, or the grid
 * holds {@link #LOAD_CAP} videos. That way the order covers the profile rather than the first
 * screen of it. A chip over the grid's top corner names the order and changes it in place.
 *
 * <p>That chain used to play out in front of the reader, a fresh list or a pull to refresh showing
 * TikTok's first page and then reshuffling page by page, which read as a slow sort and as the order
 * being lost. A grid now stays out of sight while the chain runs and comes back once, sorted
 * ({@link Hold}).
 *
 * <p>The Liked tab has its own order, which follows the profile grids until one is chosen for it
 * there or from its chip ({@link #orderFor}).
 */
@SuppressWarnings("unused")
public final class GridSort {
    static final String OFF = "off";
    static final String NEWEST = "newest";
    static final String OLDEST = "oldest";
    static final String LONGEST = "longest";
    static final String SHORTEST = "shortest";
    static final String MOST_VIEWED = "most_viewed";
    static final String LEAST_VIEWED = "least_viewed";
    static final String MOST_LIKED = "most_liked";
    static final String LEAST_LIKED = "least_liked";

    /** Every choice, as the settings row and the grid's chip list them. */
    public static final String[] ORDERS = {
            OFF, NEWEST, OLDEST, LONGEST, SHORTEST, MOST_VIEWED, LEAST_VIEWED, MOST_LIKED, LEAST_LIKED,
    };
    /** The label of each of {@link #ORDERS}, in English: the lookup keys of the l10n tables. */
    public static final String[] LABELS = {
            "Original order", "Newest first", "Oldest first", "Longest first", "Shortest first",
            "Most viewed first", "Least viewed first", "Most liked first", "Least liked first",
    };
    /**
     * TikTok's number for a grid's tab, as its adapter is built (#180): 0 posts, 1 Liked, 4 and
     * 25 Favorites, 14 private. Both of TikTok's profile tab fragments use these numbers.
     */
    static final int TAB_POSTS = 0;
    static final int TAB_LIKED = 1;
    static final int TAB_FAVORITES = 4;
    static final int TAB_PRIVATE = 14;
    static final int TAB_FAVORITE_VIDEOS = 25;

    /** The most videos a sorted grid loads by itself. Past this the reader's scrolling loads more. */
    static final int LOAD_CAP = 300;
    /** The pause before asking for the next page, so a long profile isn't fetched in one burst. */
    static final long NEXT_PAGE_DELAY_MS = 100;
    /** How many videos a page asks for. The server answers 50 with about 30, up from about 16 for 18. */
    static final int PAGE_SIZE = 50;
    /** The source TikTok's footer names when it asks for a page. */
    static final String PAGE_SOURCE = "load_more_bind_footer";
    /**
     * The longest a grid stays out of sight, so a slow or dead request can't leave it hidden. A
     * profile past {@link #LOAD_CAP} took 18 s on Login2, and a limit under that shows the last
     * pages reshuffling.
     */
    static final long HOLD_LIMIT_MS = 20_000;
    /**
     * How long a hold waits on one page. The Liked tab keeps its listener past its last video and
     * just never answers the ask, so this is what ends that hold. Pages came within 3 s on Login2.
     */
    static final long HOLD_QUIET_MS = 5_000;
    /** A hold shows nothing this early, so a profile with few videos doesn't flash a spinner. */
    static final long HOLD_SPINNER_DELAY_MS = 250;

    static final String CHIP_TAG = "hushfeed_grid_sort_chip";
    static final String HOLD_TAG = "hushfeed_grid_sort_hold";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Per adapter, TikTok's own list as last handed over, so a new order applies without a reload. */
    private static final Map<Object, List<?>> ORIGINALS = new WeakHashMap<>();
    /** Per adapter, how many videos it held after its last list, to spot a page that added nothing. */
    private static final Map<Object, Integer> LOADED = new WeakHashMap<>();
    /** Adapters whose next page was asked for and hasn't come in. */
    private static final Map<Object, Boolean> ASKED = new WeakHashMap<>();
    /** Per adapter, the list object TikTok last handed over, which its video page plays. */
    private static final Map<Object, List<?>> HANDED = new WeakHashMap<>();
    /** Adapters whose handed list was given the grid's order, so it no longer holds TikTok's. */
    private static final Map<Object, Boolean> REWRITTEN = new WeakHashMap<>();
    /** Per adapter, TikTok's number for the tab it draws, where the patch passed one on. */
    private static final Map<Object, Integer> TABS = new WeakHashMap<>();
    /** Per grid fragment class, the field that holds its adapter. */
    private static final Map<Class<?>, Field> ADAPTER_FIELDS = new WeakHashMap<>();
    /** Per grid, its hold while its pages load. Main thread only. */
    private static final Map<View, Hold> HOLDS = new WeakHashMap<>();

    private GridSort() {
    }

    /** Called as TikTok builds a grid adapter, with the number of the tab it's for. */
    public static void tab(Object adapter, int type) {
        if (adapter == null) return;
        synchronized (ORIGINALS) {
            TABS.put(adapter, type);
        }
    }

    /**
     * The order the grid this adapter draws follows. The Liked tab has its own setting, which
     * follows the profile grids ("profile", its default) until an order is chosen for it. Posts, Favorites and the private
     * tab follow the profile setting, and a tab TikTok numbers some other way keeps TikTok's
     * order. An adapter whose tab never came in (a build the patch couldn't hook) follows the
     * profile setting, as every grid did before Liked got its own.
     */
    static String orderFor(Object adapter) {
        Integer type;
        synchronized (ORIGINALS) {
            type = adapter == null ? null : TABS.get(adapter);
        }
        if (type == null) return Settings.PROFILE_GRID_SORT.get();
        switch (type) {
            case TAB_LIKED:
                String liked = Settings.LIKED_GRID_SORT.get();
                return indexOf(liked) >= 0 ? liked : Settings.PROFILE_GRID_SORT.get();
            case TAB_POSTS:
            case TAB_FAVORITES:
            case TAB_PRIVATE:
            case TAB_FAVORITE_VIDEOS:
                return Settings.PROFILE_GRID_SORT.get();
            default:
                return OFF;
        }
    }

    /** The setting a choice on this adapter's chip saves to. */
    static StringSetting settingFor(Object adapter) {
        Integer type;
        synchronized (ORIGINALS) {
            type = adapter == null ? null : TABS.get(adapter);
        }
        return type != null && type == TAB_LIKED ? Settings.LIKED_GRID_SORT : Settings.PROFILE_GRID_SORT;
    }

    /**
     * The adapter's setData: the list it should keep. Anything this can't sort comes back as it
     * came. A fresh list starts the loading over.
     */
    public static List<?> data(Object adapter, List<?> list) {
        String chosen = orderFor(adapter);
        HookStatus.bound("profile grid sort", OFF.equals(chosen) ? "order left" : "order sorted");
        List<?> order = adapter == null ? list : tiktokOrder(adapter, list);
        List<?> out = sorted(order, chosen);
        if (adapter != null) {
            int before = remember(adapter, list, order);
            int size = list == null ? 0 : list.size();
            // A first list or a shorter one is a fresh load or a pull to refresh. TikTok handing the
            // same list back stays in sight. This has to happen here: a post runs after the frame.
            boolean fresh = before == 0 || size < before;
            if (fresh) startHold(adapter, size);
            MAIN.post(() -> afterTikTok(adapter, 0, size, fresh));
        }
        return out;
    }

    /**
     * The adapter's setDataAfterLoadMore: the list it should keep. The adapter announces the page
     * as new cells at the end of the grid, which is wrong for a list that was reordered, so a list
     * that moved anything also gets a full refresh once TikTok's own call is through.
     */
    public static List<?> page(Object adapter, List<?> list) {
        String chosen = orderFor(adapter);
        HookStatus.bound("profile grid sort", OFF.equals(chosen) ? "order left" : "order sorted");
        List<?> order = adapter == null ? list : tiktokOrder(adapter, list);
        List<?> out = sorted(order, chosen);
        if (adapter != null) {
            int before = remember(adapter, list, order);
            int size = list == null ? 0 : list.size();
            boolean moved = moved(list, out);
            MAIN.post(() -> {
                if (moved && !holdTakesRefresh(adapter)) refresh(adapter);
                afterTikTok(adapter, before, size, false);
            });
        }
        return out;
    }

    /**
     * Whether a grid that now holds {@code size} videos, {@code before} before this list came in,
     * should ask for its next page. Only for a real order, only on screen, under the cap, and only
     * while pages still add videos, so a profile with no more ends the chain.
     */
    static boolean wantsNextPage(String order, int before, int size, boolean onScreen) {
        if (order == null || OFF.equals(order) || comparator(order) == null) return false;
        return onScreen && size < LOAD_CAP && size > before;
    }

    /**
     * Puts {@code order} on the grid this adapter draws, from the list TikTok last gave it, and
     * saves it as the order its kind of grid follows: the Liked tab's own, or the profile grids'.
     */
    static void apply(Object adapter, String order) {
        apply(adapter, order, null);
    }

    private static void apply(Object adapter, String order, View chosenFrom) {
        if (chosenFrom != null && !currentControl(adapter, chosenFrom)) return;
        View grid = gridView(adapter);
        SortChip control = controlOn(grid);
        boolean saved;
        synchronized (Setting.class) {
            boolean retainedOriginal = control != null && control.originalChosen;
            if (control != null) control.stopRetainingOriginal();
            saved = settingFor(adapter).save(order);
            if (control != null && (saved ? control == chosenFrom && OFF.equals(order) : retainedOriginal)) {
                control.retainOriginal();
            }
        }
        if (!saved) {
            Utils.showToastShort(L10n.t("Couldn't save the change. Try again."));
            return;
        }
        List<?> original;
        synchronized (ORIGINALS) {
            original = ORIGINALS.get(adapter);
        }
        if (original != null) {
            List<?> out = sorted(original, orderFor(adapter));
            Field items = Reflect.field(adapter.getClass(), "mmItems");
            if (items != null) {
                try {
                    items.set(adapter, out);
                    refresh(adapter);
                } catch (Throwable ex) {
                    Logger.printException(() -> "Could not sort a grid in place", ex);
                }
            }
        }
        showChip(adapter, grid);
        int size = original == null ? 0 : original.size();
        if (wantsNextPage(order, 0, size, onScreen(grid)) && loadMoreListener(adapter) != null) {
            startHold(adapter, size);
            askForNextPage(adapter);
        } else {
            endHold(grid);
        }
    }

    /**
     * Notes the list TikTok handed over and its own order of it, and returns how many videos the
     * adapter held before it.
     */
    private static int remember(Object adapter, List<?> list, List<?> order) {
        synchronized (ORIGINALS) {
            ORIGINALS.put(adapter, order);
            HANDED.put(adapter, list);
            Integer before = LOADED.put(adapter, list == null ? 0 : list.size());
            ASKED.remove(adapter);
            return before == null ? 0 : before;
        }
    }

    /**
     * TikTok's own order of the list it handed over. That's the list itself unless a video was
     * opened from the sorted grid, which wrote the grid's order into it. Then the order is the
     * one kept from before, without the videos that left, and the new ones after it as TikTok
     * added them. With no order chosen, TikTok's order goes back into its list, so the grid and
     * the video page share it again.
     */
    static List<?> tiktokOrder(Object adapter, List<?> list) {
        List<?> earlier;
        synchronized (ORIGINALS) {
            if (!REWRITTEN.containsKey(adapter)) return list;
            earlier = ORIGINALS.get(adapter);
        }
        List<Object> order = list == null ? null : rebuilt(earlier, list);
        if (order == null) {
            // A list with none of the earlier videos is a fresh load in TikTok's order.
            synchronized (ORIGINALS) {
                REWRITTEN.remove(adapter);
            }
            return list;
        }
        String chosen = orderFor(adapter);
        if ((OFF.equals(chosen) || comparator(chosen) == null) && follow(list, order)) {
            synchronized (ORIGINALS) {
                REWRITTEN.remove(adapter);
            }
            return list;
        }
        return order;
    }

    /**
     * The videos of {@code earlier} still in {@code list}, in their earlier order, then the ones
     * {@code list} added, in its order. Null when none of the earlier ones is left, or when
     * {@code list} holds a video twice and no order would keep every entry.
     */
    static List<Object> rebuilt(List<?> earlier, List<?> list) {
        if (earlier == null) return null;
        Map<Object, Boolean> now = new IdentityHashMap<>();
        for (Object item : list) now.put(item, Boolean.TRUE);
        Map<Object, Boolean> placed = new IdentityHashMap<>();
        List<Object> order = new ArrayList<>(list.size());
        for (Object item : earlier) {
            if (now.containsKey(item) && placed.put(item, Boolean.TRUE) == null) order.add(item);
        }
        if (order.isEmpty()) return null;
        for (Object item : list) {
            if (placed.put(item, Boolean.TRUE) == null) order.add(item);
        }
        return order.size() == list.size() ? order : null;
    }

    /**
     * Called as a grid cell opens its video (#180), with the grid's fragment. TikTok's video page
     * plays the fragment's own list, the one it handed the adapter, so swiping there went on in
     * TikTok's order while the grid showed another. This writes the grid's order into that list
     * first. A list that doesn't hold the same videos as the grid is left alone.
     */
    public static void beforeOpen(Object fragment) {
        try {
            Object adapter = adapterOf(fragment);
            if (adapter == null) return;
            List<?> handed;
            synchronized (ORIGINALS) {
                handed = HANDED.get(adapter);
            }
            Object shown = Reflect.readField(adapter, "mmItems");
            if (handed == null || !(shown instanceof List) || shown == handed) return;
            List<?> grid = (List<?>) shown;
            if (sameOrder(handed, grid)) return;
            List<Object> before = new ArrayList<>(handed);
            if (!follow(handed, grid)) {
                Logger.printDebug(() -> "Grid sort: the opened video's list doesn't hold the grid's videos, left as is");
                return;
            }
            synchronized (ORIGINALS) {
                if (ORIGINALS.get(adapter) == handed) ORIGINALS.put(adapter, before);
                REWRITTEN.put(adapter, Boolean.TRUE);
            }
            Logger.printDebug(() -> "Grid sort: the video page follows the grid's order, " + grid.size() + " videos");
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not give the video page the grid's order", ex);
        }
    }

    /**
     * Puts {@code order} into {@code target} in place and says whether {@code target} now holds
     * it. Only when both hold the same videos, each as often, so nothing is lost or repeated.
     */
    @SuppressWarnings("unchecked")
    static boolean follow(List<?> target, List<?> order) {
        if (target == null || order == null || target.size() != order.size()) return false;
        if (sameOrder(target, order)) return true;
        Map<Object, Integer> counts = new IdentityHashMap<>();
        for (Object item : target) {
            Integer count = counts.get(item);
            counts.put(item, count == null ? 1 : count + 1);
        }
        for (Object item : order) {
            Integer count = counts.get(item);
            if (count == null || count == 0) return false;
            counts.put(item, count - 1);
        }
        List<Object> copy = new ArrayList<>(order);
        List<Object> writable = (List<Object>) target;
        try {
            for (int i = 0; i < copy.size(); i++) writable.set(i, copy.get(i));
            return true;
        } catch (RuntimeException ex) {
            Logger.printDebug(() -> "Grid sort: TikTok's list can't be reordered (" + ex + ")");
            return false;
        }
    }

    private static boolean sameOrder(List<?> a, List<?> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) != b.get(i)) return false;
        }
        return true;
    }

    /** The adapter a grid fragment holds, found by the lists handed over, the field kept per class. */
    private static Object adapterOf(Object fragment) {
        if (fragment == null) return null;
        Class<?> type = fragment.getClass();
        Field known;
        synchronized (ADAPTER_FIELDS) {
            known = ADAPTER_FIELDS.get(type);
        }
        if (known != null) {
            try {
                return known.get(fragment);
            } catch (IllegalAccessException ex) {
                return null;
            }
        }
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (field.getType().isPrimitive() || Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(fragment);
                    boolean adapter;
                    synchronized (ORIGINALS) {
                        adapter = value != null && HANDED.containsKey(value);
                    }
                    if (adapter) {
                        synchronized (ADAPTER_FIELDS) {
                            ADAPTER_FIELDS.put(type, field);
                        }
                        return value;
                    }
                } catch (Throwable ignored) {
                    // A field this can't read isn't the adapter.
                }
            }
        }
        return null;
    }

    private static void afterTikTok(Object adapter, int before, int size, boolean fresh) {
        View grid = null;
        try {
            grid = gridView(adapter);
            View gridShown = grid;
            showChip(adapter, grid);
            boolean shown = onScreen(grid);
            // TikTok takes the listener away once it knows it has no more. It decides that after it
            // hands the list over, so this post is the first point that can tell.
            boolean next = wantsNextPage(orderFor(adapter), before, size, shown) && loadMoreListener(adapter) != null;
            Logger.printDebug(() -> "Grid sort: page " + before + " -> " + size
                    + (gridShown == null ? ", no grid" : shown ? "" : ", off screen") + (next ? ", asking for more" : ""));
            if (next) {
                // The grid can be laid out only now, which is too late for the hook's own start.
                if (fresh) startHold(adapter, size);
                showHoldCount(grid, size);
                askForNextPage(adapter);
            } else {
                endHold(grid);
            }
        } catch (Throwable ex) {
            endHold(grid);
            Logger.printException(() -> "Could not finish a sorted grid page", ex);
        }
    }

    /** The listener TikTok's footer calls, which it clears once a grid has no more pages. */
    private static Object loadMoreListener(Object adapter) {
        Object listener = Reflect.invoke(adapter, "getLoadMoreListener");
        return listener != null ? listener : Reflect.readField(adapter, "mmLoadMoreListener");
    }

    private static void askForNextPage(Object adapter) {
        Hold hold = HOLDS.get(gridView(adapter));
        if (hold != null) {
            MAIN.removeCallbacks(hold.quiet);
            MAIN.postDelayed(hold.quiet, HOLD_QUIET_MS);
        }
        synchronized (ORIGINALS) {
            if (ASKED.containsKey(adapter)) return;
            ASKED.put(adapter, Boolean.TRUE);
        }
        MAIN.postDelayed(() -> loadMore(adapter), NEXT_PAGE_DELAY_MS);
    }

    /**
     * Asks the grid for its next page, through TikTok's page loader where the patch found one and
     * otherwise through the listener its own footer calls.
     */
    private static void loadMore(Object adapter) {
        try {
            String order = orderFor(adapter);
            if (OFF.equals(order) || comparator(order) == null || !onScreen(gridView(adapter))) {
                synchronized (ORIGINALS) {
                    ASKED.remove(adapter);
                }
                endHold(gridView(adapter));
                Logger.printDebug(() -> "Grid sort: next page dropped, order " + order + " or the grid left the screen");
                return;
            }
            Object listener = loadMoreListener(adapter);
            if (listener != null && loadPage(listener, 0, PAGE_SIZE, PAGE_SOURCE)) {
                Object loaderShown = listener;
                Logger.printDebug(() -> "Grid sort: asked " + loaderShown.getClass().getName() + "'s page loader for the next page");
                return;
            }
            Method next = listener == null ? null : Reflect.method(listener.getClass(), "loadMore");
            if (next == null) {
                synchronized (ORIGINALS) {
                    ASKED.remove(adapter);
                }
                endHold(gridView(adapter));
                Logger.printDebug(() -> "Grid sort: no load more listener on " + adapter.getClass().getName());
                return;
            }
            Object listenerShown = listener;
            Logger.printDebug(() -> "Grid sort: asking " + listenerShown.getClass().getName() + " for the next page");
            next.invoke(listener);
        } catch (Throwable ex) {
            endHold(gridView(adapter));
            Logger.printException(() -> "Could not load the next page of a sorted grid", ex);
        }
    }

    /**
     * A grid out of sight while its pages load. It stays laid out and shown, since {@link #onScreen}
     * is what keeps the pages coming, and only its alpha drops. A cover over it takes the taps that
     * would otherwise open a cell nobody can see, and shows a spinner with how many videos are in.
     * The sort chip sits above the cover by its elevation, so the order can still change.
     */
    private static final class Hold {
        final View cover;
        final TextView count;
        final Runnable limit;
        final Runnable quiet;
        final View.OnAttachStateChangeListener leaving;
        Object adapter;
        boolean refreshDue;

        Hold(View cover, TextView count, Runnable limit, Runnable quiet, View.OnAttachStateChangeListener leaving, Object adapter) {
            this.cover = cover;
            this.count = count;
            this.limit = limit;
            this.quiet = quiet;
            this.leaving = leaving;
            this.adapter = adapter;
        }
    }

    /** A grid gone from the screen can't load, so its cover steps aside rather than eat taps until the limit. */
    private static final class HoldCover extends FrameLayout {
        private final View grid;

        HoldCover(Context context, View grid) {
            super(context);
            this.grid = grid;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            if (onScreen(grid)) return super.dispatchTouchEvent(event);
            letGoOfGrid(grid, this);
            return false;
        }
    }

    /** Called from the middle of the parent's own removal or touch pass, so the removal waits for the post. */
    private static void letGoOfGrid(View grid, View cover) {
        cover.setVisibility(View.GONE);
        MAIN.post(() -> endHold(grid));
    }

    /** Takes the grid out of sight if it's about to load pages on its own. */
    private static void startHold(Object adapter, int size) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper() || size <= 0 || size >= LOAD_CAP) return;
            if (comparator(orderFor(adapter)) == null) return;
            View grid = gridView(adapter);
            if (!onScreen(grid) || !(grid.getParent() instanceof FrameLayout)) return;
            Object currentAdapter = Reflect.invoke(grid, "getAdapter");
            if (currentAdapter != null && currentAdapter != adapter) return;
            Hold held = HOLDS.get(grid);
            if (held != null) {
                held.adapter = adapter;
                // A pull to refresh during a hold has every page ahead of it again.
                MAIN.removeCallbacks(held.limit);
                MAIN.postDelayed(held.limit, HOLD_LIMIT_MS);
                return;
            }
            Context context = grid.getContext();
            FrameLayout cover = new HoldCover(context, grid);
            cover.setTag(HOLD_TAG);
            cover.setClickable(true);

            LinearLayout box = new LinearLayout(context);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setBackground(SettingsUi.overlayChip(context, SettingsUi.RADIUS_OVERLAY));
            int paddingX = SettingsUi.dp(context, 12);
            int paddingY = SettingsUi.dp(context, 8);
            box.setPadding(paddingX, paddingY, paddingX, paddingY);
            box.setVisibility(View.INVISIBLE);
            ProgressBar spinner = new ProgressBar(context, null, android.R.attr.progressBarStyleSmall);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(SettingsUi.OVERLAY_TEXT));
            int spinnerSize = SettingsUi.dp(context, 18);
            box.addView(spinner, new LinearLayout.LayoutParams(spinnerSize, spinnerSize));
            TextView count = new TextView(context);
            count.setTextColor(SettingsUi.OVERLAY_TEXT);
            count.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_CAPTION);
            count.setPadding(SettingsUi.dp(context, 8), 0, 0, 0);
            count.setText(String.valueOf(size));
            box.addView(count);
            FrameLayout.LayoutParams boxAt = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            boxAt.topMargin = SettingsUi.dp(context, 64);
            cover.addView(box, boxAt);
            ((FrameLayout) grid.getParent()).addView(cover, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            grid.setAlpha(0f);

            View.OnAttachStateChangeListener leaving = new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    letGoOfGrid(grid, cover);
                }
            };
            grid.addOnAttachStateChangeListener(leaving);
            Hold hold = new Hold(cover, count, () -> endHold(grid), () -> endHold(grid), leaving, adapter);
            HOLDS.put(grid, hold);
            MAIN.postDelayed(hold.limit, HOLD_LIMIT_MS);
            MAIN.postDelayed(() -> box.setVisibility(View.VISIBLE), HOLD_SPINNER_DELAY_MS);
            Logger.printDebug(() -> "Grid sort: holding the grid at " + size);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not hold a sorted grid", ex);
        }
    }

    private static void showHoldCount(View grid, int size) {
        Hold hold = HOLDS.get(grid);
        if (hold != null) hold.count.setText(String.valueOf(size));
    }

    /** Whether a page's refresh can wait for the end of the hold: one refresh, not one per page. */
    private static boolean holdTakesRefresh(Object adapter) {
        Hold hold = HOLDS.get(gridView(adapter));
        if (hold == null) return false;
        hold.refreshDue = true;
        return true;
    }

    /** Brings the grid back, with the one refresh the pages it hid were owed. */
    private static void endHold(View grid) {
        Hold hold = grid == null ? null : HOLDS.remove(grid);
        if (hold == null) return;
        MAIN.removeCallbacks(hold.limit);
        MAIN.removeCallbacks(hold.quiet);
        grid.removeOnAttachStateChangeListener(hold.leaving);
        grid.setAlpha(1f);
        ViewParent parent = hold.cover.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(hold.cover);
        if (hold.refreshDue) refresh(hold.adapter);
        Logger.printDebug(() -> "Grid sort: hold over");
    }

    /**
     * Asks the grid's fragment for its next page through TikTok's own page loader, and says
     * whether it could. The footer's listener runs a reach-bottom check first that only loads
     * within eight rows of the end, so on its own a sorted grid stopped after its third page. The
     * patch replaces this body with the loader call, made only when {@code fragment} is a grid
     * fragment, with TikTok's load-more message in {@code message}. The loader drops an ask while
     * the same load is queued or running. Where the patch found no loader this stays false.
     */
    @SuppressWarnings("unused")
    static boolean loadPage(Object fragment, int message, int count, String source) {
        return false;
    }

    /** The RecyclerView the adapter is attached to, or null. */
    private static View gridView(Object adapter) {
        Object view = Reflect.readField(adapter, "mmRecyclerView");
        return view instanceof View ? (View) view : null;
    }

    /** Shown and at least partly on screen: a grid on a tab the reader isn't on doesn't load. */
    private static boolean onScreen(View grid) {
        return grid != null && grid.isShown() && grid.getGlobalVisibleRect(new Rect());
    }

    /** Original selected here keeps only this control, until its grid leaves the screen. */
    private static final class SortChip extends TextView {
        Object adapter;
        View grid;
        volatile boolean originalChosen;
        private OriginalWatch originalWatch;

        SortChip(Context context) {
            super(context);
        }

        void retainOriginal() {
            synchronized (Setting.class) {
                stopRetainingOriginal();
                originalChosen = true;
                originalWatch = new OriginalWatch(Setting.preferences.preferences);
                originalWatch.preferences.registerOnSharedPreferenceChangeListener(originalWatch);
            }
        }

        void stopRetainingOriginal() {
            synchronized (Setting.class) {
                originalChosen = false;
                if (originalWatch == null) return;
                originalWatch.preferences.unregisterOnSharedPreferenceChangeListener(originalWatch);
                originalWatch = null;
            }
        }

        private final class OriginalWatch implements SharedPreferences.OnSharedPreferenceChangeListener {
            final SharedPreferences preferences;

            OriginalWatch(SharedPreferences preferences) {
                this.preferences = preferences;
            }

            @Override
            public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
                if (key != null && !Settings.PROFILE_GRID_SORT.key.equals(key)
                        && !Settings.LIKED_GRID_SORT.key.equals(key)) return;
                synchronized (Setting.class) {
                    if (originalWatch != this || !originalChosen) return;
                    originalChosen = false;
                    this.preferences.unregisterOnSharedPreferenceChangeListener(this);
                }
                MAIN.post(() -> {
                    synchronized (Setting.class) {
                        // A callback already queued before unregister belongs to the old choice.
                        if (originalWatch != this) return;
                        originalWatch = null;
                    }
                    if (controlOn(grid) == SortChip.this) showChip(adapter, grid);
                });
            }
        }

        void expireOriginal() {
            synchronized (Setting.class) {
                if (!originalChosen && originalWatch == null) return;
                stopRetainingOriginal();
            }
            setVisibility(View.GONE);
            // Window detach can be iterating the parent's children. Remove after that traversal.
            MAIN.post(() -> {
                ViewParent parent = getParent();
                if (getVisibility() == View.GONE && parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(this);
                }
            });
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (visibility != View.VISIBLE) expireOriginal();
        }

        @Override
        protected void onDetachedFromWindow() {
            stopRetainingOriginal();
            super.onDetachedFromWindow();
        }
    }

    private static SortChip controlOn(View grid) {
        if (grid == null || !(grid.getParent() instanceof FrameLayout)) return null;
        FrameLayout frame = (FrameLayout) grid.getParent();
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (child instanceof SortChip && CHIP_TAG.equals(child.getTag())) return (SortChip) child;
        }
        return null;
    }

    private static boolean currentControl(Object adapter, View anchor) {
        View grid = gridView(adapter);
        if (grid == null) return false;
        Object currentAdapter = Reflect.invoke(grid, "getAdapter");
        if (currentAdapter != null && currentAdapter != adapter) return false;
        SortChip control = controlOn(grid);
        return control == anchor && control != null && control.adapter == adapter
                && control.grid == grid && control.isAttachedToWindow() && control.isShown()
                && !Setting.isPaused() && onScreen(control.grid);
    }

    /**
     * The chip over the grid's top corner while an order is chosen, or while Original was chosen
     * from this control. A default Original grid and an explicit disable keep TikTok's stock grid.
     * TikTok's FrameLayout lets the chip float over the corner without moving a cell.
     */
    private static void showChip(Object adapter, View grid) {
        if (grid == null) return;
        Object currentAdapter = Reflect.invoke(grid, "getAdapter");
        if (currentAdapter != null && currentAdapter != adapter) return;
        ViewParent parent = grid.getParent();
        if (!(parent instanceof FrameLayout)) return;
        FrameLayout frame = (FrameLayout) parent;
        SortChip chip = controlOn(grid);
        // Late reads from an inactive old grid cannot take over the current grid's control.
        // A newly attached grid can still bind its control before its first layout.
        if (chip != null && chip.grid != grid
                && (!grid.isShown() || (grid.isLaidOut() && !onScreen(grid)))) return;
        View existing = chip;
        boolean differentControl = chip != null && (chip.grid != grid || chip.adapter != adapter);
        boolean shown = onScreen(grid);
        int index;
        boolean hideChip;
        synchronized (Setting.class) {
            index = indexOf(orderFor(adapter));
            hideChip = Setting.isPaused() || index < 0 || (index == 0
                    && (chip == null || differentControl || !chip.originalChosen || !shown));
            if (chip != null && (differentControl || index > 0 || hideChip)) chip.stopRetainingOriginal();
        }
        if (chip != null && chip.grid != grid) {
            frame.removeView(chip);
            chip = null;
            existing = null;
        }
        if (hideChip) {
            if (existing != null) frame.removeView(existing);
            return;
        }
        if (chip == null) {
            Context context = frame.getContext();
            chip = new SortChip(context);
            chip.setTag(CHIP_TAG);
            chip.setTextColor(SettingsUi.OVERLAY_TEXT);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_CAPTION);
            chip.setMinWidth(SettingsUi.dp(context, 48));
            chip.setMinHeight(SettingsUi.dp(context, 48));
            chip.setGravity(Gravity.CENTER);
            int paddingX = SettingsUi.dp(context, 10);
            int paddingY = SettingsUi.dp(context, 5);
            chip.setPadding(paddingX, paddingY, paddingX, paddingY);
            chip.setBackground(SettingsUi.overlayChip(context, SettingsUi.RADIUS_OVERLAY));
            chip.setContentDescription(L10n.t("Sort this grid"));
            chip.setElevation(SettingsUi.dp(context, 2));
            FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            int margin = SettingsUi.dp(context, 8);
            layout.setMargins(margin, margin, margin, margin);
            frame.addView(chip, layout);
            followHeader(chip, grid);
        }
        chip.adapter = adapter;
        chip.grid = grid;
        chip.setVisibility(View.VISIBLE);
        chip.setText(L10n.t(LABELS[index]));
        // Set every time: the parent can outlive one adapter and get the next one.
        chip.setOnClickListener(view -> choose(adapter, view));
        TextView placed = chip;
        chip.post(() -> placeChip(placed, grid));
    }

    /**
     * Keeps the chip under a full-width row at the top of the grid as the grid lays out and
     * scrolls. The listener drops itself once the chip is taken away.
     */
    private static void followHeader(SortChip chip, View grid) {
        final class Follow implements ViewTreeObserver.OnScrollChangedListener,
                ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
            private ViewTreeObserver observer;

            private void removeFrom(ViewTreeObserver from) {
                if (from == null || !from.isAlive()) return;
                from.removeOnScrollChangedListener(this);
                from.removeOnGlobalLayoutListener(this);
            }

            private void unobserve() {
                removeFrom(observer);
                observer = null;
            }

            void observe() {
                ViewTreeObserver current = grid.getViewTreeObserver();
                if (observer == current && current.isAlive()) return;
                removeFrom(observer);
                // A floating observer's listeners already merged into the window on attach.
                removeFrom(current);
                observer = current;
                if (!current.isAlive()) return;
                current.addOnScrollChangedListener(this);
                current.addOnGlobalLayoutListener(this);
            }

            @Override
            public void onScrollChanged() {
                update();
            }

            @Override
            public void onGlobalLayout() {
                update();
            }

            @Override
            public void onViewAttachedToWindow(View view) {
                observe();
                MAIN.post(this::update);
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                unobserve();
                chip.setVisibility(View.GONE);
                chip.expireOriginal();
                MAIN.post(this::update);
            }

            private void remove() {
                chip.stopRetainingOriginal();
                ViewParent parent = chip.getParent();
                if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(chip);
            }

            private void update() {
                Object currentAdapter = Reflect.invoke(grid, "getAdapter");
                if (Setting.isPaused() || (currentAdapter != null && currentAdapter != chip.adapter)
                        || (chip.originalChosen && !onScreen(grid))) remove();
                if (chip.getParent() != null && grid.isAttachedToWindow()
                        && chip.getParent() != grid.getParent()) {
                    remove();
                    showChip(chip.adapter, grid);
                }
                if (chip.getParent() != null) {
                    if (!grid.isAttachedToWindow()) chip.setVisibility(View.GONE);
                    else if (indexOf(orderFor(chip.adapter)) > 0) chip.setVisibility(View.VISIBLE);
                    placeChip(chip, grid);
                    return;
                }
                grid.removeOnAttachStateChangeListener(this);
                unobserve();
            }
        }
        Follow follow = new Follow();
        grid.addOnAttachStateChangeListener(follow);
        follow.observe();
    }

    private static void placeChip(View chip, View grid) {
        try {
            if (!(grid instanceof ViewGroup)) return;
            ViewGroup rows = (ViewGroup) grid;
            int count = rows.getChildCount();
            int[] tops = new int[count];
            int[] bottoms = new int[count];
            int[] widths = new int[count];
            for (int i = 0; i < count; i++) {
                View row = rows.getChildAt(i);
                tops[i] = row.getTop();
                bottoms[i] = row.getBottom();
                widths[i] = row.getWidth();
            }
            float y = grid.getTop() + chipOffset(grid.getWidth(), tops, bottoms, widths);
            if (chip.getTranslationY() != y) chip.setTranslationY(y);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not place the grid sort chip", ex);
        }
    }

    /**
     * How far down the chip goes to clear the full-width rows at the top of the grid, such as the
     * Liked tab's note about making liked videos public, which it covered: the bottom of the last
     * row spanning the grid that starts at its top, or right under one that does. A row scrolled
     * partly away pulls the chip up with it. Cells are narrower than the grid and never move it,
     * and neither does a full-width footer further down.
     */
    static int chipOffset(int gridWidth, int[] tops, int[] bottoms, int[] widths) {
        if (gridWidth <= 0) return 0;
        int offset = 0;
        for (int i = 0; i < tops.length; i++) {
            if (widths[i] * 10 < gridWidth * 9 || bottoms[i] <= 0 || tops[i] > offset) continue;
            offset = Math.max(offset, bottoms[i]);
        }
        return offset;
    }

    /** The orders as a list to pick from, the current one marked. */
    private static void choose(Object adapter, View anchor) {
        try {
            Activity activity = activityOf(anchor.getContext());
            if (activity == null) activity = Utils.getVisibleActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            SettingsUi.syncDarkMode(activity);
            String[] labels = new String[LABELS.length];
            for (int i = 0; i < labels.length; i++) labels[i] = L10n.t(LABELS[i]);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t("Sort this grid"))
                    .setSingleChoiceItems(labels, indexOf(orderFor(adapter)), (shown, which) -> {
                        shown.dismiss();
                        apply(adapter, ORDERS[which], anchor);
                    })
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .create();
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            dialog.show();
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not open the grid sort choices", ex);
        }
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    static int indexOf(String order) {
        for (int i = 0; i < ORDERS.length; i++) {
            if (ORDERS[i].equals(order)) return i;
        }
        return -1;
    }

    private static boolean moved(List<?> before, List<?> after) {
        if (before == null || after == null || before.size() != after.size()) return before != after;
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i) != after.get(i)) return true;
        }
        return false;
    }

    private static void refresh(Object adapter) {
        try {
            adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not refresh a sorted grid", ex);
        }
    }

    static List<?> sorted(List<?> list, String order) {
        try {
            if (list == null || list.size() < 2 || OFF.equals(order)) return list;
            Comparator<Object> by = comparator(order);
            if (by == null) return list;
            return sort(list, by);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not sort a grid", ex);
            return list;
        }
    }

    /** A new list: the pinned videos first as TikTok has them, then the rest by [by]. */
    static List<Object> sort(List<?> list, Comparator<Object> by) {
        List<Object> pinned = new ArrayList<>();
        List<Object> rest = new ArrayList<>();
        for (Object item : list) {
            (isPinned(item) ? pinned : rest).add(item);
        }
        // List.sort is stable, so equal values and missing ones stay in TikTok's order.
        Collections.sort(rest, by);
        pinned.addAll(rest);
        return pinned;
    }

    static Comparator<Object> comparator(String order) {
        if (NEWEST.equals(order)) return byLong(GridSort::created, true);
        if (OLDEST.equals(order)) return byLong(GridSort::created, false);
        if (LONGEST.equals(order)) return byLong(GridSort::length, true);
        if (SHORTEST.equals(order)) return byLong(GridSort::length, false);
        if (MOST_VIEWED.equals(order)) return byLong(GridSort::views, true);
        if (LEAST_VIEWED.equals(order)) return byLong(GridSort::views, false);
        if (MOST_LIKED.equals(order)) return byLong(GridSort::likes, true);
        if (LEAST_LIKED.equals(order)) return byLong(GridSort::likes, false);
        return null;
    }

    private interface Key {
        long of(Object item);
    }

    /**
     * Orders by [key] and puts every item whose key is 0 or less after the ones that have one. For
     * the least viewed and least liked orders too: a grid cell with no counts at all reads as 0, and
     * those would otherwise crowd the front.
     */
    private static Comparator<Object> byLong(Key key, boolean descending) {
        return (a, b) -> {
            long x = key.of(a);
            long y = key.of(b);
            boolean hasX = x > 0;
            boolean hasY = y > 0;
            if (hasX != hasY) return hasX ? -1 : 1;
            if (!hasX) return 0;
            return descending ? Long.compare(y, x) : Long.compare(x, y);
        };
    }

    static boolean isPinned(Object item) {
        Object top = Reflect.property(item, "getIsTop", "isTop");
        return top instanceof Number && ((Number) top).intValue() == 1;
    }

    static long created(Object item) {
        return item instanceof Aweme ? ((Aweme) item).getCreateTime() : 0;
    }

    static long length(Object item) {
        return GridVideoLength.lengthMs(item);
    }

    static long views(Object item) {
        AwemeStatistics statistics = item instanceof Aweme ? ((Aweme) item).getStatistics() : null;
        return statistics == null ? 0 : statistics.getPlayCount();
    }

    static long likes(Object item) {
        AwemeStatistics statistics = item instanceof Aweme ? ((Aweme) item).getStatistics() : null;
        return statistics == null ? 0 : statistics.getDiggCount();
    }

    static void resetForTests() {
        synchronized (ORIGINALS) {
            ORIGINALS.clear();
            LOADED.clear();
            ASKED.clear();
            HANDED.clear();
            REWRITTEN.clear();
            TABS.clear();
        }
        HOLDS.clear();
        synchronized (ADAPTER_FIELDS) {
            ADAPTER_FIELDS.clear();
        }
    }
}

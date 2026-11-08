/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * Jump to a date on a Liked grid (#350). TikTok pages a Liked list with a cursor that is the time
 * of a like, newest first, and starts a fresh list from cursor 0. While a jump is on, the first page
 * TikTok asks for starts at the chosen date instead, and TikTok's own paging carries on from the
 * cursor its server sends back, so older likes load without the newer ones in front of them.
 *
 * <p>A chip in the Liked grid's top corner (the start side, the sort chip has the end) opens a year
 * and then a month to jump to, or Newest likes to go back. The jump is kept for the profile whose
 * list it first went to, so another profile's Liked tab still starts at its newest, and it lasts
 * until Newest likes or TikTok restarts. A pull to refresh stays at the chosen date.
 */
@SuppressWarnings("unused")
public final class LikedJump {
    /** The first year the picker offers: musical.ly accounts carried their likes into TikTok. */
    static final int FIRST_YEAR = 2014;
    static final String CHIP_TAG = "hushfeed_liked_jump_chip";
    private static final String HOOK = "liked jump";

    private static final Object LOCK = new Object();
    /** The like time the next fresh list starts at, or 0 for no jump. */
    private static long cursor;
    /** The profile the jump is for, taken from the first list asked for after it was chosen. */
    private static String owner;
    /** The chip's name for the jump, like "Mar 2022". */
    private static String label;
    /** The jump the latest fresh list was asked for with, or null. */
    private static String lastFresh;

    /** The jump each Liked grid's current list came from, by its adapter. */
    private static final Map<Object, String> SHOWN = new WeakHashMap<>();
    /** Each Liked grid's fragment, kept while TikTok still had it as the load-more listener. */
    private static final Map<Object, Object> FRAGMENTS = new WeakHashMap<>();

    private LikedJump() {
    }

    /**
     * The {@code max_cursor} TikTok's Liked list request goes out with. Called first thing in that
     * request with the profile's ids and the cursor TikTok chose. A later page (any cursor but 0)
     * goes as TikTok asked, and so does a fresh list with no jump, or for another profile.
     */
    public static long cursor(String userId, String secUserId, int type, long maxCursor) {
        try {
            if (maxCursor != 0) return maxCursor;
            boolean on = Settings.LIKED_JUMP_TO_DATE.get() && !Setting.isPaused();
            String who = userId != null && !userId.isEmpty() ? userId : secUserId == null ? "" : secUserId;
            synchronized (LOCK) {
                if (!on || cursor == 0) {
                    lastFresh = null;
                    return maxCursor;
                }
                if (owner == null) owner = who;
                if (!owner.equals(who)) {
                    lastFresh = null;
                    return maxCursor;
                }
                lastFresh = label;
                long jump = cursor;
                HookStatus.bound(HOOK, "fresh list sent to a date");
                Logger.printDebug(() -> "Liked jump: fresh list starts at " + jump);
                return jump;
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not send a Liked list to a date", ex);
            return maxCursor;
        }
    }

    /**
     * Asks the grid's fragment for a fresh list, the way TikTok refreshes it, and says whether it
     * could. The patch replaces this body with the fragment's own refresh, made only when
     * {@code fragment} is a profile grid fragment. Where the patch found none this stays false.
     * {@code spare} is always 0: the patched body uses it as its working register, since the
     * compiled stub may have no register of its own.
     */
    static boolean refreshGrid(Object fragment, int spare) {
        return false;
    }

    /**
     * A Liked grid's adapter got a list. A fresh one is marked with the jump it was asked for, and
     * the chip is put on the grid or taken off once TikTok's call is through.
     */
    static void listIn(Object adapter, boolean fresh) {
        if (adapter == null) return;
        if (!Settings.LIKED_JUMP_TO_DATE.get()) {
            Utils.runOnMainThread(() -> showChip(adapter));
            return;
        }
        Object fragment = GridSort.loadMoreListener(adapter);
        synchronized (LOCK) {
            if (fragment != null) FRAGMENTS.put(adapter, fragment);
            if (fresh) {
                if (lastFresh == null) SHOWN.remove(adapter);
                else SHOWN.put(adapter, lastFresh);
            }
        }
        Utils.runOnMainThread(() -> showChip(adapter));
    }

    /** The jump this grid's list came from, or null. */
    static String shownFor(Object adapter) {
        synchronized (LOCK) {
            return SHOWN.get(adapter);
        }
    }

    /** Whether a jump is chosen at all. */
    static boolean chosen() {
        synchronized (LOCK) {
            return cursor != 0;
        }
    }

    /** Starts the next fresh list at {@code at}, for whichever profile asks first. */
    static void choose(long at, String name) {
        synchronized (LOCK) {
            cursor = at;
            label = name;
            owner = null;
        }
        Logger.printDebug(() -> "Liked jump: chose " + name + " (" + at + ")");
    }

    /** Back to TikTok's newest-first list. */
    static void clear() {
        synchronized (LOCK) {
            cursor = 0;
            label = null;
            owner = null;
        }
        Logger.printDebug(() -> "Liked jump: back to the newest likes");
    }

    /**
     * The like time a jump to {@code month} (0 to 11) of {@code year} starts at: the first moment
     * of the month after it, in the phone's time zone, so the list starts with that month's last
     * likes. A month below 0 is the whole year, which starts at the next January.
     */
    static long startOf(int year, int month) {
        Calendar at = Calendar.getInstance();
        at.clear();
        if (month < 0) at.set(year + 1, Calendar.JANUARY, 1, 0, 0, 0);
        else {
            at.set(year, month, 1, 0, 0, 0);
            at.add(Calendar.MONTH, 1);
        }
        return at.getTimeInMillis();
    }

    /** The chip's name for a jump: the year alone, or the month's short name and the year. */
    static String nameOf(int year, int month, Locale locale) {
        if (month < 0) return String.valueOf(year);
        Calendar at = Calendar.getInstance();
        at.clear();
        at.set(year, month, 1);
        return new SimpleDateFormat("LLL yyyy", locale).format(at.getTime());
    }

    /** The years the picker lists, this one first. */
    static List<Integer> years(int thisYear) {
        List<Integer> out = new ArrayList<>();
        for (int year = thisYear; year >= FIRST_YEAR; year--) out.add(year);
        return out;
    }

    /** How many months of {@code year} the picker lists: the ones already begun. */
    static int monthsOf(int year, int thisYear, int thisMonth) {
        return year < thisYear ? 12 : thisMonth + 1;
    }

    private static final class Chip extends TextView implements ViewTreeObserver.OnScrollChangedListener,
            ViewTreeObserver.OnGlobalLayoutListener {
        Object adapter;
        View grid;

        Chip(Context context) {
            super(context);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            ViewTreeObserver observer = getViewTreeObserver();
            observer.addOnScrollChangedListener(this);
            observer.addOnGlobalLayoutListener(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            ViewTreeObserver observer = getViewTreeObserver();
            observer.removeOnScrollChangedListener(this);
            observer.removeOnGlobalLayoutListener(this);
            super.onDetachedFromWindow();
        }

        @Override
        public void onScrollChanged() {
            place();
        }

        @Override
        public void onGlobalLayout() {
            place();
        }

        void place() {
            if (grid == null) return;
            Object current = Reflect.invoke(grid, "getAdapter");
            boolean stale = Setting.isPaused() || (current != null && current != adapter)
                    || grid.getParent() != getParent();
            if (stale) {
                setVisibility(View.GONE);
                return;
            }
            setVisibility(grid.isShown() ? View.VISIBLE : View.GONE);
            GridSort.placeChip(this, grid);
        }
    }

    private static Chip chipOn(View grid) {
        if (grid == null || !(grid.getParent() instanceof FrameLayout)) return null;
        FrameLayout frame = (FrameLayout) grid.getParent();
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (child instanceof Chip && CHIP_TAG.equals(child.getTag())) return (Chip) child;
        }
        return null;
    }

    /** The chip over a Liked grid's top start corner while the switch is on, named for the grid's jump. */
    static void showChip(Object adapter) {
        try {
            View grid = GridSort.gridView(adapter);
            if (grid == null || !(grid.getParent() instanceof FrameLayout)) return;
            Object current = Reflect.invoke(grid, "getAdapter");
            if (current != null && current != adapter) return;
            FrameLayout frame = (FrameLayout) grid.getParent();
            Chip chip = chipOn(grid);
            boolean wanted = Settings.LIKED_JUMP_TO_DATE.get() && !Setting.isPaused();
            if (!wanted) {
                if (chip != null) frame.removeView(chip);
                return;
            }
            if (chip == null) {
                Context context = frame.getContext();
                chip = new Chip(context);
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
                chip.setElevation(SettingsUi.dp(context, 2));
                FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.START);
                int margin = SettingsUi.dp(context, 8);
                layout.setMargins(margin, margin, margin, margin);
                frame.addView(chip, layout);
            }
            chip.adapter = adapter;
            chip.grid = grid;
            String shown = shownFor(adapter);
            chip.setText(shown == null ? L10n.t("Jump to a date") : L10n.f("Up to %1$s", shown));
            chip.setContentDescription(L10n.t("Jump to a date"));
            chip.setOnClickListener(view -> pickYear(adapter, view));
            Chip placed = chip;
            chip.post(placed::place);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not show the Liked jump chip", ex);
        }
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static Activity shownActivity(View anchor) {
        Activity activity = activityOf(anchor.getContext());
        if (activity == null) activity = Utils.getVisibleActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        SettingsUi.syncDarkMode(activity);
        return activity;
    }

    /** Newest likes when a jump is on, then the years, this one first. */
    private static void pickYear(Object adapter, View anchor) {
        try {
            Activity activity = shownActivity(anchor);
            if (activity == null) return;
            Calendar now = Calendar.getInstance();
            List<Integer> years = years(now.get(Calendar.YEAR));
            boolean newest = chosen();
            String[] items = new String[years.size() + (newest ? 1 : 0)];
            int at = 0;
            if (newest) items[at++] = L10n.t("Newest likes");
            for (int year : years) items[at++] = String.valueOf(year);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t("Jump to a date"))
                    .setItems(items, (shown, which) -> {
                        shown.dismiss();
                        if (newest && which == 0) {
                            clear();
                            reload(adapter);
                            return;
                        }
                        pickMonth(adapter, anchor, years.get(which - (newest ? 1 : 0)));
                    })
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .create();
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            dialog.show();
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not open the Liked jump years", ex);
        }
    }

    /** Whole year, then the months of {@code year} that have begun. */
    private static void pickMonth(Object adapter, View anchor, int year) {
        try {
            Activity activity = shownActivity(anchor);
            if (activity == null) return;
            Locale locale = L10n.shownLocale(activity);
            Calendar now = Calendar.getInstance();
            int months = monthsOf(year, now.get(Calendar.YEAR), now.get(Calendar.MONTH));
            String[] items = new String[months + 1];
            items[0] = L10n.t("Whole year");
            SimpleDateFormat monthName = new SimpleDateFormat("LLLL", locale);
            Calendar month = Calendar.getInstance();
            for (int i = 0; i < months; i++) {
                month.clear();
                month.set(year, i, 1);
                String name = monthName.format(month.getTime());
                items[i + 1] = name.isEmpty() ? name : name.substring(0, 1).toUpperCase(locale) + name.substring(1);
            }
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(String.valueOf(year))
                    .setItems(items, (shown, which) -> {
                        shown.dismiss();
                        int chosenMonth = which - 1;
                        choose(startOf(year, chosenMonth), nameOf(year, chosenMonth, locale));
                        reload(adapter);
                    })
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .create();
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            dialog.show();
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not open the Liked jump months", ex);
        }
    }

    /** A fresh list for the grid through its fragment, or a note to pull down where there's none. */
    static void reload(Object adapter) {
        Object fragment;
        synchronized (LOCK) {
            fragment = FRAGMENTS.get(adapter);
        }
        if (fragment == null) fragment = GridSort.loadMoreListener(adapter);
        boolean asked = false;
        try {
            asked = fragment != null && refreshGrid(fragment, 0);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not refresh a Liked grid", ex);
        }
        Object fragmentShown = fragment;
        boolean askedShown = asked;
        Logger.printDebug(() -> "Liked jump: refresh through "
                + (fragmentShown == null ? "no fragment" : fragmentShown.getClass().getName()) + ", " + askedShown);
        if (!asked) Utils.showToastShort(L10n.t("Pull down on the grid to load them"));
    }

    static void resetForTests() {
        synchronized (LOCK) {
            cursor = 0;
            label = null;
            owner = null;
            lastFresh = null;
            SHOWN.clear();
            FRAGMENTS.clear();
        }
    }
}

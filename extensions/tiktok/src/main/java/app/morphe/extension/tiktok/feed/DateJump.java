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
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * A profile grid that starts at a chosen date: Liked (#350) and Favorites (#508). TikTok pages
 * those lists with a cursor that is the time of a like or a save, newest first, and starts a
 * fresh list from cursor 0. While a jump is on, the first page TikTok asks for starts at the
 * chosen date instead, and TikTok's own paging carries on from the cursor its server sends back,
 * so older videos load without the newer ones in front of them.
 *
 * <p>A chip in the grid's top corner (the start side, the sort chip has the end) opens a year and
 * then a month to jump to, or the newest to go back. A jump is kept for the profile whose list it
 * first went to, so another profile's tab still starts at its newest, and it lasts until the
 * newest is picked or TikTok restarts. A pull to refresh stays at the chosen date.
 */
final class DateJump {
    /** Asks a grid's fragment for a fresh list, and says whether it could. */
    interface Refresher {
        boolean refresh(Object fragment);
    }

    /** The first year the picker offers: musical.ly accounts carried their likes into TikTok. */
    static final int FIRST_YEAR = 2014;

    private static final Object LOCK = new Object();

    private final String kind;
    private final String hook;
    private final BooleanSetting setting;
    private final String chipTag;
    private final String newest;
    private final Refresher refresher;

    /** The time the next fresh list starts at, or 0 for no jump. */
    private long cursor;
    /** The profile the jump is for, taken from the first list asked for after it was chosen. */
    private String owner;
    /** The chip's name for the jump, like "Mar 2022". */
    private String label;
    /** The jump the latest fresh list was asked for with, or null. */
    private String lastFresh;

    /** The jump each grid's current list came from, by its adapter. */
    private final Map<Object, String> shown = new WeakHashMap<>();
    /** Each grid's fragment, kept while TikTok still had it as the load-more listener. */
    private final Map<Object, Object> fragments = new WeakHashMap<>();

    /**
     * @param kind     the grid's name in the log, like "Liked".
     * @param hook     the hook's name in the diagnostic report.
     * @param newest   the picker's way back, an l10n key like "Newest likes".
     */
    DateJump(String kind, String hook, BooleanSetting setting, String chipTag, String newest, Refresher refresher) {
        this.kind = kind;
        this.hook = hook;
        this.setting = setting;
        this.chipTag = chipTag;
        this.newest = newest;
        this.refresher = refresher;
    }

    /**
     * The cursor a list request goes out with, for the profile {@code who}. A later page (any
     * cursor but 0) goes as TikTok asked, and so does a fresh list with no jump, or for another
     * profile.
     */
    long cursor(String who, long maxCursor) {
        try {
            if (maxCursor != 0) return maxCursor;
            boolean on = setting.get() && !Setting.isPaused();
            String profile = who == null ? "" : who;
            synchronized (LOCK) {
                if (!on || cursor == 0) {
                    lastFresh = null;
                    return maxCursor;
                }
                if (owner == null) owner = profile;
                if (!owner.equals(profile)) {
                    lastFresh = null;
                    return maxCursor;
                }
                lastFresh = label;
                long jump = cursor;
                HookStatus.bound(hook, "fresh list sent to a date");
                Logger.printDebug(() -> kind + " jump: fresh list starts at " + jump);
                return jump;
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not send a " + kind + " list to a date", ex);
            return maxCursor;
        }
    }

    /**
     * A grid's adapter got a list. A fresh one is marked with the jump it was asked for, and the
     * chip is put on the grid or taken off once TikTok's call is through.
     */
    void listIn(Object adapter, boolean fresh) {
        if (adapter == null) return;
        if (!setting.get()) {
            Utils.runOnMainThread(() -> showChip(adapter));
            return;
        }
        Object fragment = GridSort.loadMoreListener(adapter);
        synchronized (LOCK) {
            if (fragment != null) fragments.put(adapter, fragment);
            if (fresh) {
                if (lastFresh == null) shown.remove(adapter);
                else shown.put(adapter, lastFresh);
            }
        }
        Utils.runOnMainThread(() -> showChip(adapter));
    }

    /** The jump this grid's list came from, or null. */
    String shownFor(Object adapter) {
        synchronized (LOCK) {
            return shown.get(adapter);
        }
    }

    /** Whether a jump is chosen at all. */
    boolean chosen() {
        synchronized (LOCK) {
            return cursor != 0;
        }
    }

    /** Starts the next fresh list at {@code at}, for whichever profile asks first. */
    void choose(long at, String name) {
        synchronized (LOCK) {
            cursor = at;
            label = name;
            owner = null;
        }
        Logger.printDebug(() -> kind + " jump: chose " + name + " (" + at + ")");
    }

    /** Back to TikTok's newest-first list. */
    void clear() {
        synchronized (LOCK) {
            cursor = 0;
            label = null;
            owner = null;
        }
        Logger.printDebug(() -> kind + " jump: back to the newest");
    }

    void reset() {
        synchronized (LOCK) {
            cursor = 0;
            label = null;
            owner = null;
            lastFresh = null;
            shown.clear();
            fragments.clear();
        }
    }

    /**
     * The time a jump to {@code month} (0 to 11) of {@code year} starts at: the first moment of
     * the month after it, in the phone's time zone, so the list starts with that month's last
     * videos. A month below 0 is the whole year, which starts at the next January.
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
                    || !GridSort.overGrid(this, grid);
            if (stale) {
                setVisibility(View.GONE);
                return;
            }
            setVisibility(grid.isShown() ? View.VISIBLE : View.GONE);
            GridSort.placeChip(this, grid);
        }
    }

    private Chip chipOn(View grid) {
        FrameLayout frame = GridSort.overlayFrame(grid);
        if (frame == null) return null;
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (child instanceof Chip && chipTag.equals(child.getTag())) return (Chip) child;
        }
        return null;
    }

    /** The chip over the grid's top start corner while the switch is on, named for the grid's jump. */
    void showChip(Object adapter) {
        try {
            View grid = GridSort.gridView(adapter);
            FrameLayout frame = GridSort.overlayFrame(grid);
            if (frame == null) return;
            Object current = Reflect.invoke(grid, "getAdapter");
            if (current != null && current != adapter) return;
            Chip chip = chipOn(grid);
            boolean wanted = setting.get() && !Setting.isPaused();
            if (!wanted) {
                if (chip != null) frame.removeView(chip);
                return;
            }
            if (chip == null) {
                Context context = frame.getContext();
                chip = new Chip(context);
                chip.setTag(chipTag);
                SettingsUi.markAsButton(chip);
                chip.setTextColor(SettingsUi.OVERLAY_TEXT);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_CAPTION);
                chip.setMinWidth(SettingsUi.dp(context, 48));
                chip.setMinHeight(SettingsUi.dp(context, 48));
                chip.setGravity(Gravity.CENTER);
                int paddingX = SettingsUi.dp(context, 10);
                int paddingY = SettingsUi.dp(context, 5);
                chip.setPadding(paddingX, paddingY, paddingX, paddingY);
                // Words over thumbnails take the text scrim, the same as the grid's Top/Back/End.
                chip.setBackground(SettingsUi.overlayBanner(context));
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
            String jump = shownFor(adapter);
            String name = L10n.t("Jump to a date");
            String upTo = jump == null ? null : L10n.f("Up to %1$s", jump);
            chip.setText(upTo == null ? name : upTo);
            // A description replaces the text for a screen reader, so it carries the date too.
            chip.setContentDescription(upTo == null ? name : L10n.f("%1$s: %2$s", name, upTo));
            chip.setOnClickListener(view -> pickYear(adapter, view));
            Chip placed = chip;
            chip.post(placed::place);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not show the " + kind + " jump chip", ex);
        }
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    static Activity shownActivity(View anchor) {
        Activity activity = activityOf(anchor.getContext());
        if (activity == null) activity = Utils.getVisibleActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        SettingsUi.syncDarkMode(activity);
        return activity;
    }

    /** The newest when a jump is on, then the years, this one first. */
    private void pickYear(Object adapter, View anchor) {
        try {
            Activity activity = shownActivity(anchor);
            if (activity == null) return;
            Calendar now = Calendar.getInstance();
            List<Integer> years = years(now.get(Calendar.YEAR));
            boolean back = chosen();
            String[] items = new String[years.size() + (back ? 1 : 0)];
            int at = 0;
            if (back) items[at++] = L10n.t(newest);
            for (int year : years) items[at++] = String.valueOf(year);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t("Jump to a date"))
                    .setItems(items, (picker, which) -> {
                        picker.dismiss();
                        if (back && which == 0) {
                            clear();
                            reload(adapter);
                            return;
                        }
                        pickMonth(adapter, anchor, years.get(which - (back ? 1 : 0)));
                    })
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .create();
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            dialog.show();
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not open the " + kind + " jump years", ex);
        }
    }

    /** Whole year, then the months of {@code year} that have begun. */
    private void pickMonth(Object adapter, View anchor, int year) {
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
                    .setItems(items, (picker, which) -> {
                        picker.dismiss();
                        int chosenMonth = which - 1;
                        choose(startOf(year, chosenMonth), nameOf(year, chosenMonth, locale));
                        reload(adapter);
                    })
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .create();
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            dialog.show();
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not open the " + kind + " jump months", ex);
        }
    }

    /** A fresh list for the grid through its fragment, or a note to pull down where there's none. */
    void reload(Object adapter) {
        Object fragment;
        synchronized (LOCK) {
            fragment = fragments.get(adapter);
        }
        if (fragment == null) fragment = GridSort.loadMoreListener(adapter);
        boolean asked = false;
        try {
            asked = fragment != null && refresher.refresh(fragment);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not refresh a " + kind + " grid", ex);
        }
        Object fragmentShown = fragment;
        boolean askedShown = asked;
        Logger.printDebug(() -> kind + " jump: refresh through "
                + (fragmentShown == null ? "no fragment" : fragmentShown.getClass().getName()) + ", " + askedShown);
        if (!asked) Utils.showToastShort(L10n.t("Pull down on the grid to load them"));
    }
}

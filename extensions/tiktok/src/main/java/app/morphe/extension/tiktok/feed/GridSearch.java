/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.feedfilter.KeywordRules;
import app.morphe.extension.tiktok.feedfilter.SoundIdentity;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * Search on Liked and Favorites grids. A chip at the top of the grid opens a box that narrows what
 * the grid draws to the loaded videos whose caption, creator or sound holds every word typed, in
 * any case. TikTok keeps the whole list, so its paging, the order chip and the video page go on
 * over every loaded video, and a cleared search puts the sorted grid back. While a search is on the
 * grid loads on by itself up to the sort's cap, so it looks through more than the first screen.
 * TikTok's own footer can load past that while it's in sight, as it does at the end of any grid.
 */
public final class GridSearch {
    static final String CHIP_TAG = "hushfeed_grid_search_chip";
    /** Any run of spaces, the ideographic and no-break ones a CJK or phone keyboard types too. */
    private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}]+");

    /** The search on each grid, by its adapter, trimmed and never blank. */
    private static final Map<Object, String> QUERIES = new WeakHashMap<>();

    /**
     * What each searched grid's loaded videos hold, by video, so a page that comes in only reads
     * the videos it brought. Dropped with the search.
     */
    private static final Map<Object, Map<Object, String>> HAYSTACKS = new WeakHashMap<>();

    /**
     * The open search box's count line, tagged with the grid it counts. Held weakly, since a box
     * still open when its activity goes never hears its dismiss. Main thread only.
     */
    private static WeakReference<TextView> openCount;

    private GridSearch() {
    }

    static boolean enabled() {
        return Settings.GRID_SEARCH.get() && !Setting.isPaused();
    }

    /** Whether this adapter's grid takes a search: a Liked or Favorites tab. */
    static boolean searchable(Object adapter) {
        int tab = GridSort.tabOf(adapter);
        return tab == GridSort.TAB_LIKED || tab == GridSort.TAB_FAVORITES || tab == GridSort.TAB_FAVORITE_VIDEOS;
    }

    static String queryOf(Object adapter) {
        if (adapter == null) return null;
        synchronized (QUERIES) {
            return QUERIES.get(adapter);
        }
    }

    /** Whether this adapter's grid is narrowed to a search right now. */
    static boolean searching(Object adapter) {
        return queryOf(adapter) != null && enabled() && searchable(adapter);
    }

    /** The words of a search, lower cased the way the word lists are. */
    static List<String> words(String query) {
        List<String> out = new ArrayList<>();
        if (query == null) return out;
        for (String word : SPACES.split(KeywordRules.fold(query))) {
            if (!word.isEmpty()) out.add(word);
        }
        return out;
    }

    /** What a search looks through on a video: its caption, its creator's name and handle, and its sound's name and author. */
    static String haystack(Object video) {
        StringBuilder text = new StringBuilder();
        // The caption's line is there even when it's empty, so a read made before TikTok filled
        // the caption in can be told apart from one that had it.
        String caption = Reflect.string(video, "getDesc", "desc");
        text.append(caption == null ? "" : caption).append('\n');
        Object author = Reflect.property(video, "getAuthor", "author");
        if (author != null) {
            add(text, Reflect.string(author, "getNickname", "nickname"));
            add(text, Reflect.string(author, "getUniqueId", "uniqueId"));
        }
        Object music = Reflect.property(video, "getMusic", "music");
        if (music != null) {
            add(text, SoundIdentity.nameOf(music));
            add(text, SoundIdentity.authorOf(music));
        }
        return KeywordRules.fold(text.toString());
    }

    private static void add(StringBuilder text, String part) {
        if (part != null) text.append(part).append('\n');
    }

    /** The videos of {@code list} that hold every word of {@code query}, in the list's order. */
    static List<?> matching(List<?> list, String query) {
        return matching(list, query, null);
    }

    /** The same, reading each video once per grid while its search stays on. */
    private static List<?> matching(List<?> list, String query, Object adapter) {
        List<String> words = words(query);
        if (list == null || words.isEmpty()) return list;
        Map<Object, String> known = null;
        if (adapter != null) {
            synchronized (QUERIES) {
                known = HAYSTACKS.get(adapter);
            }
        }
        // Rebuilt from this list, so a video TikTok let go isn't held on to.
        Map<Object, String> texts = new IdentityHashMap<>(list.size());
        List<Object> out = new ArrayList<>();
        for (Object video : list) {
            String text = texts.get(video);
            if (text == null && known != null) text = known.get(video);
            // A video read before TikTok filled in its caption is read again.
            if (text == null || text.startsWith("\n")) text = haystack(video);
            texts.put(video, text);
            boolean all = true;
            for (String word : words) {
                if (!text.contains(word)) {
                    all = false;
                    break;
                }
            }
            if (all) out.add(video);
        }
        if (adapter != null) {
            synchronized (QUERIES) {
                if (QUERIES.containsKey(adapter)) HAYSTACKS.put(adapter, texts);
            }
        }
        return out;
    }

    /** {@code list} as this adapter's grid draws it: narrowed to its search while one is on. */
    static List<?> narrowed(Object adapter, List<?> list) {
        return searching(adapter) ? matching(list, queryOf(adapter), adapter) : list;
    }

    /**
     * {@code matches} and then the videos of {@code all} that aren't among them, in {@code all}'s
     * order: the list a narrowed grid's video page plays, so a cell opens its own video.
     */
    static List<?> withRest(List<?> matches, List<?> all) {
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        List<Object> out = new ArrayList<>(all.size());
        for (Object video : matches) {
            out.add(video);
            seen.put(video, Boolean.TRUE);
        }
        for (Object video : all) {
            if (!seen.containsKey(video)) out.add(video);
        }
        return out;
    }

    /** Puts {@code query} on this adapter's grid, or takes the search off for a blank one. */
    static void search(Object adapter, String query) {
        String trimmed = query == null ? "" : query.trim();
        // trim() keeps the ideographic and no-break spaces, so a box holding only those is blank too.
        if (words(trimmed).isEmpty()) trimmed = "";
        synchronized (QUERIES) {
            String before = QUERIES.get(adapter);
            if (trimmed.equals(before == null ? "" : before)) return;
            if (trimmed.isEmpty()) {
                QUERIES.remove(adapter);
                HAYSTACKS.remove(adapter);
            } else {
                QUERIES.put(adapter, trimmed);
            }
        }
        GridSort.searchChanged(adapter);
    }

    /**
     * The switch or Pause changed. Each searched grid is drawn again from its whole list, narrowed
     * only while search is on. A chip waiting off its grid for the grid's next frame does this on
     * its own only when the grid moves, so a grid left where it was stayed narrowed. Off drops each
     * search here too, the same as a chip in its frame does, so turning it back on finds every grid
     * whole wherever its chip was.
     */
    public static void onSettingChanged() {
        List<Object> searched;
        boolean off = !enabled();
        synchronized (QUERIES) {
            searched = new ArrayList<>(QUERIES.keySet());
            if (off) {
                QUERIES.clear();
                HAYSTACKS.clear();
            }
        }
        for (Object adapter : searched) {
            if (off) {
                Chip chip = chipOn(GridSort.gridView(adapter));
                if (chip != null) label(chip, null);
            }
            GridSort.reshow(adapter);
        }
    }

    /** Takes the search off this adapter's grid, and says whether it had one. */
    private static boolean forget(Object adapter) {
        synchronized (QUERIES) {
            HAYSTACKS.remove(adapter);
            return QUERIES.remove(adapter) != null;
        }
    }

    private static final class Chip extends TextView implements ViewTreeObserver.OnScrollChangedListener,
            ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
        Object adapter;
        View grid;

        Chip(Context context) {
            super(context);
        }

        /** Watches {@code next} coming back to a window, or nothing for null. */
        void follow(View next) {
            if (grid == next) return;
            if (grid != null) grid.removeOnAttachStateChangeListener(this);
            grid = next;
            if (next != null) next.addOnAttachStateChangeListener(this);
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            // TikTok can throw the old frame away with this chip in it, which quiets the chip's own
            // listeners, so the grid arriving in its new frame is what brings the chip along.
            view.post(this::place);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
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
            boolean off = !enabled();
            // A switch turned off or a pause gives the grid back its whole list.
            if (off && forget(adapter)) {
                label(this, null);
                Object narrowed = adapter;
                // Not this chip's own queue: one taken out of its frame to wait for the grid's
                // next one never runs what it was handed.
                Utils.runOnMainThread(() -> GridSort.reshow(narrowed));
            }
            boolean sameAdapter = current == null || current == adapter;
            if (!off && sameAdapter && grid.isAttachedToWindow() && !GridSort.overGrid(this, grid)) {
                // TikTok moved the grid to another frame, so the chip goes with it.
                ViewParent parent = getParent();
                if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(this);
                showChip(adapter);
                // A parent that can't hold a chip leaves this one watching the grid, so its next
                // move can still bring one along.
                if (chipOn(grid) != null || GridSort.overlayFrame(grid) != null) follow(null);
                return;
            }
            if (off || !sameAdapter || !GridSort.overGrid(this, grid)) {
                setVisibility(View.GONE);
                return;
            }
            setVisibility(grid.isShown() ? View.VISIBLE : View.GONE);
            GridSort.placeChip(this, grid);
        }
    }

    private static Chip chipOn(View grid) {
        FrameLayout frame = GridSort.overlayFrame(grid);
        if (frame == null) return null;
        for (int i = 0; i < frame.getChildCount(); i++) {
            View child = frame.getChildAt(i);
            if (child instanceof Chip && CHIP_TAG.equals(child.getTag())) return (Chip) child;
        }
        return null;
    }

    /**
     * The chip over the top middle of a Liked or Favorites grid while the switch is on, between the
     * date chip and the order chip. It reads Search, or the search the grid is narrowed to.
     */
    static void showChip(Object adapter) {
        try {
            View grid = GridSort.gridView(adapter);
            FrameLayout frame = GridSort.overlayFrame(grid);
            if (frame == null) return;
            Object current = Reflect.invoke(grid, "getAdapter");
            if (current != null && current != adapter) return;
            Chip chip = chipOn(grid);
            if (!enabled() || !searchable(adapter)) {
                // TikTok's list went on whole, but a page counts its new cells from the narrowed
                // grid, so the grid needs telling all over again.
                if (!enabled() && forget(adapter)) GridSort.reshow(adapter);
                if (chip != null) {
                    frame.removeView(chip);
                    chip.follow(null);
                }
                return;
            }
            if (chip == null) {
                Context context = frame.getContext();
                chip = new Chip(context);
                chip.setTag(CHIP_TAG);
                SettingsUi.markAsButton(chip);
                chip.setTextColor(SettingsUi.OVERLAY_TEXT);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_CAPTION);
                chip.setMinWidth(SettingsUi.dp(context, 48));
                chip.setMinHeight(SettingsUi.dp(context, 48));
                // A long search shortens rather than run under the chips on either side.
                chip.setMaxWidth(SettingsUi.dp(context, 120));
                chip.setSingleLine(true);
                chip.setEllipsize(TextUtils.TruncateAt.END);
                chip.setGravity(Gravity.CENTER);
                int paddingX = SettingsUi.dp(context, 10);
                int paddingY = SettingsUi.dp(context, 5);
                chip.setPadding(paddingX, paddingY, paddingX, paddingY);
                // Words over thumbnails take the text scrim, the same as the grid's Top/Back/End.
                chip.setBackground(SettingsUi.overlayBanner(context));
                chip.setElevation(SettingsUi.dp(context, 2));
                FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                int margin = SettingsUi.dp(context, 8);
                layout.setMargins(margin, margin, margin, margin);
                frame.addView(chip, layout);
            }
            chip.adapter = adapter;
            chip.follow(grid);
            label(chip, queryOf(adapter));
            chip.setOnClickListener(view -> open(adapter, view));
            Chip placed = chip;
            chip.post(placed::place);
            TextView count = openCount == null ? null : openCount.get();
            if (count != null && count.getTag() == adapter) showCount(count, adapter);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not show the grid search chip", ex);
        }
    }

    private static void label(TextView chip, String query) {
        String name = L10n.t("Search");
        chip.setText(query == null ? name : query);
        // A description replaces the text for a screen reader, so it says what the words are.
        chip.setContentDescription(query == null ? name : L10n.f("%1$s: %2$s", name, query));
    }

    /** How many of the loaded videos the grid shows, or that none match. Blank with no search. */
    static void showCount(TextView count, Object adapter) {
        List<?> loaded = GridSort.originalOf(adapter);
        Object shown = Reflect.readField(adapter, "mmItems");
        if (!searching(adapter) || loaded == null || !(shown instanceof List)) {
            count.setText("");
            return;
        }
        int matches = ((List<?>) shown).size();
        count.setText(matches == 0 ? L10n.t("No videos match")
                : L10n.f("%1$s of %2$s", String.valueOf(matches), String.valueOf(loaded.size())));
    }

    /** The search box, narrowing the grid as it's typed in. Done keeps the search, Clear takes it off. */
    private static void open(Object adapter, View anchor) {
        try {
            Activity activity = LikedJump.shownActivity(anchor);
            if (activity == null) return;
            int gap = SettingsUi.dp(activity, 20);
            EditText box = new EditText(activity);
            box.setHint(L10n.t("Caption, creator or sound"));
            box.setSingleLine(true);
            box.setImeOptions(EditorInfo.IME_ACTION_DONE);
            SettingsUi.styleEditText(box);
            String before = queryOf(adapter);
            if (before != null) {
                box.setText(before);
                box.setSelection(before.length());
            }
            TextView count = SettingsUi.text(activity, "", SettingsUi.TEXT_BODY_SMALL,
                    SettingsUi.textSecondary(), Typeface.NORMAL);
            count.setPadding(0, SettingsUi.dp(activity, 8), 0, 0);

            LinearLayout page = new LinearLayout(activity);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(gap, 0, gap, 0);
            page.addView(box, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            page.addView(count, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t("Search"))
                    .setView(page)
                    .setPositiveButton(L10n.t("Done"), null)
                    .setNeutralButton(L10n.t("Clear"), null)
                    .create();
            box.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence text, int start, int replaced, int added) {
                }

                @Override
                public void onTextChanged(CharSequence text, int start, int replaced, int added) {
                }

                @Override
                public void afterTextChanged(Editable text) {
                    search(adapter, text.toString());
                    Chip chip = chipOn(GridSort.gridView(adapter));
                    if (chip != null) label(chip, queryOf(adapter));
                    showCount(count, adapter);
                }
            });
            box.setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_DONE) return false;
                dialog.dismiss();
                return true;
            });
            dialog.setOnShowListener(ignored -> {
                SettingsUi.styleStandardAlertDialog(dialog);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                    box.setText("");
                    dialog.dismiss();
                });
            });
            dialog.setOnDismissListener(ignored -> {
                if (openCount != null && openCount.get() == count) openCount = null;
            });
            Window window = dialog.getWindow();
            if (window != null) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            count.setTag(adapter);
            openCount = new WeakReference<>(count);
            dialog.show();
            showCount(count, adapter);
            box.requestFocus();
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not open the grid search", ex);
        }
    }

    static void resetForTests() {
        synchronized (QUERIES) {
            QUERIES.clear();
            HAYSTACKS.clear();
        }
        openCount = null;
    }
}

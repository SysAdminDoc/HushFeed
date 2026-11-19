/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Hides the tabs you pick from the strip over a profile's videos: Videos, Private, Reposts,
 * Favorites, Liked, Shop and the rest. The strip always keeps one tab, and with Videos gone the
 * profile opens on the first tab left, which TikTok's pager already copes with (#399). A tab you
 * move up or down in the checklist moves on the strip too, and the rest stay where TikTok put
 * them (#466).
 *
 * <p>TikTok's profile pager asks for the tab types it is about to lay out, a list of numbers, and
 * builds one tab for each. The numbers are TikTok's own and can move between builds, so they are
 * matched through a name that stays put, {@code profile_tab_<kind>}: the patch reads which
 * number TikTok gives each name and writes the pairs into {@link #tabNamesNative()}. One row of
 * the checklist can cover several names, like Shop's showcase and store tabs.
 */
public final class ProfileTabs {
    /** TikTok's name for the Videos tab. */
    static final String VIDEOS = "profile_tab_public_post";

    private ProfileTabs() {
    }

    /** One row of the checklist: the key it saves, its English label and the tabs it hides. */
    public static final class Row {
        public final String key;
        public final String label;
        final List<String> names;

        Row(String key, String label, String... names) {
            this.key = key;
            this.label = label;
            this.names = Arrays.asList(names);
        }
    }

    /**
     * The checklist's rows, the first seven in the order your own profile shows them on 47.9.2,
     * so the list you reorder starts out as the strip you see.
     */
    public static final List<Row> ROWS = List.of(
            new Row("videos", "Videos", VIDEOS),
            new Row("sticker", "Stickers", "profile_tab_sticker_store"),
            new Row("cast", "AI theater", "profile_tab_cast"),
            new Row("private", "Private videos", "profile_tab_private"),
            new Row("repost", "Reposts", "profile_tab_repost"),
            new Row("favorite", "Favorites", "profile_tab_favorite"),
            new Row("like", "Liked videos", "profile_tab_like"),
            new Row("shop", "Shop", "profile_tab_ecommerce_showcase", "profile_tab_ecommerce_store"),
            new Row("music", "Music", "profile_tab_music"),
            new Row("effect", "Effects", "profile_tab_effect"),
            new Row("series", "Series", "profile_tab_short_drama", "profile_tab_drama_download",
                    "profile_tab_pine_drama_watch_history"));

    private static volatile Map<Integer, String> tabNames;

    /**
     * Replaced with the pager's number for each tab name, as the patch read them from TikTok:
     * {@code 0=profile_tab_public_post,2=profile_tab_like,...}.
     */
    public static String tabNamesNative() {
        return null;
    }

    /** From the profile pager, with the tab types it is about to lay out. */
    public static List<?> filter(List<?> types) {
        try {
            if (types == null || types.isEmpty()) return types;
            Set<String> hidden = hiddenNames();
            List<String> order = order(Settings.PROFILE_TAB_ORDER.get());
            if (hidden.isEmpty() && order.isEmpty()) return types;
            Map<Integer, String> names = tabNames;
            if (names == null) tabNames = names = parseTabNames(tabNamesNative());
            return arrange(filter(types, names, hidden), names, order);
        } catch (Throwable error) {
            Logger.printException(() -> "Profile tabs: could not filter the tabs", error);
            return types;
        }
    }

    /** The pairs the patch wrote, read back into a map. A pair that doesn't read is left out. */
    static Map<Integer, String> parseTabNames(String encoded) {
        Map<Integer, String> names = new HashMap<>();
        if (encoded == null) return names;
        for (String pair : encoded.split(",")) {
            int split = pair.indexOf('=');
            if (split <= 0 || split == pair.length() - 1) continue;
            try {
                names.put(Integer.valueOf(pair.substring(0, split).trim()), pair.substring(split + 1).trim());
            } catch (NumberFormatException ignored) {
                // Not a number before the '=', so not a pair the patch wrote.
            }
        }
        return names;
    }

    static List<?> filter(List<?> types, Map<?, ?> names, Set<String> hidden) {
        if (types == null || names == null || hidden.isEmpty()) return types;
        List<Object> kept = new ArrayList<>(types.size());
        for (Object type : types) {
            Object name = names.get(type);
            boolean hide = name instanceof String && hidden.contains(name);
            Logger.printDebug(() -> "Profile tabs: " + type + " " + name + (hide ? " hidden" : " kept"));
            if (!hide) kept.add(type);
        }
        // Never an empty strip, and the pager's own list when nothing was taken out. A new
        // ArrayList otherwise, since the pager swaps entries in the list it gets.
        if (kept.isEmpty() || kept.size() == types.size()) return types;
        return kept;
    }

    /**
     * Puts the tabs in the saved order (#466), moving only the rows you moved. The order is read
     * the way the checklist shows it, every row with the ones it doesn't name after it, and the
     * rows you left alone are the longest run of it still in {@link #ROWS} order. Their tabs keep
     * TikTok's places, a tab with no row too. Each row you moved goes in front of the next row of
     * your order the strip has, or after the one before it when none follows. Sorting every tab
     * by the whole list instead moved tabs you never touched, like a creator's Shop, which TikTok
     * puts ahead of Reposts while the checklist lists it after Liked. A new list when anything
     * moved, for the same reason {@link #filter(List, Map, Set)} hands one back.
     */
    static List<?> arrange(List<?> types, Map<?, ?> names, List<String> order) {
        if (types == null || names == null || order.isEmpty()) return types;
        List<String> shown = complete(order);
        Set<String> moved = moved(shown);
        if (moved.isEmpty()) return types;
        List<Object> arranged = new ArrayList<>(types.size());
        Map<String, List<Object>> lifted = new HashMap<>();
        for (Object type : types) {
            String key = rowKey(names.get(type));
            if (key == null || !moved.contains(key)) {
                arranged.add(type);
            } else {
                // In TikTok's order, so the two Shop tabs, or a drama's three, stay as they were.
                // Not computeIfAbsent: Map's default methods are API 24.
                List<Object> group = lifted.get(key);
                if (group == null) {
                    group = new ArrayList<>();
                    lifted.put(key, group);
                }
                group.add(type);
            }
        }
        // From the end of the order back, so the row after a moved one is already in place.
        for (int rank = shown.size() - 1; rank >= 0; rank--) {
            List<Object> tabs = lifted.get(shown.get(rank));
            if (tabs != null) arranged.addAll(placeFor(arranged, names, shown, rank), tabs);
        }
        if (arranged.equals(types)) return types;
        Logger.printDebug(() -> "Profile tabs: " + types + " arranged as " + arranged);
        return arranged;
    }

    /**
     * The rows of a whole order you moved: every row outside its longest run still in
     * {@link #ROWS} order. Of two runs as long, the one that starts higher in your order stays.
     */
    static Set<String> moved(List<String> shown) {
        int size = shown.size();
        int[] place = new int[size];
        int[] length = new int[size];
        int[] before = new int[size];
        int end = -1;
        for (int at = 0; at < size; at++) {
            place[at] = rowIndex(shown.get(at));
            length[at] = 1;
            before[at] = -1;
            for (int earlier = 0; earlier < at; earlier++) {
                if (place[earlier] < place[at] && length[earlier] + 1 > length[at]) {
                    length[at] = length[earlier] + 1;
                    before[at] = earlier;
                }
            }
            if (end < 0 || length[at] > length[end]) end = at;
        }
        Set<String> moved = new HashSet<>(shown);
        for (int at = end; at >= 0; at = before[at]) moved.remove(shown.get(at));
        return moved;
    }

    /**
     * Where a moved row's tabs go: in front of the first tab of the next row in the order the
     * strip has, else after the last tab of the row before it, else at the end.
     */
    private static int placeFor(List<Object> arranged, Map<?, ?> names, List<String> shown, int rank) {
        for (int next = rank + 1; next < shown.size(); next++) {
            for (int index = 0; index < arranged.size(); index++) {
                if (shown.get(next).equals(rowKey(names.get(arranged.get(index))))) return index;
            }
        }
        for (int previous = rank - 1; previous >= 0; previous--) {
            for (int index = arranged.size() - 1; index >= 0; index--) {
                if (shown.get(previous).equals(rowKey(names.get(arranged.get(index))))) return index + 1;
            }
        }
        return arranged.size();
    }

    /** The key of the row a tab's name belongs to, or null when no row covers it. */
    private static String rowKey(Object name) {
        if (!(name instanceof String)) return null;
        for (Row row : ROWS) {
            if (row.names.contains(name)) return row.key;
        }
        return null;
    }

    private static int rowIndex(String key) {
        for (int index = 0; index < ROWS.size(); index++) {
            if (ROWS.get(index).key.equals(key)) return index;
        }
        return -1;
    }

    /** The row keys in a saved order, each once, without any the checklist no longer offers. */
    public static List<String> order(String stored) {
        List<String> keys = new ArrayList<>();
        if (stored == null) return keys;
        for (String token : stored.split("[,\\n]")) {
            String key = canonical(token);
            if (!keys.contains(key) && isRow(key)) keys.add(key);
        }
        return keys;
    }

    /**
     * Every row's key in the order the checklist shows them: the saved order first, then any
     * row it doesn't name, in the list's own order.
     */
    public static List<String> shownOrder(String stored) {
        return complete(order(stored));
    }

    private static List<String> complete(List<String> order) {
        List<String> keys = new ArrayList<>(order);
        for (Row row : ROWS) {
            if (!keys.contains(row.key)) keys.add(row.key);
        }
        return keys;
    }

    private static boolean isRow(String key) {
        return rowIndex(key) >= 0;
    }

    /** TikTok's names for the tabs the picks hide. Paused, the default, so none. */
    static Set<String> hiddenNames() {
        return namesFor(Settings.PROFILE_TAB_PICKS.get());
    }

    static Set<String> namesFor(String stored) {
        Set<String> picked = picks(stored);
        Set<String> names = new HashSet<>();
        for (Row row : ROWS) {
            if (picked.contains(row.key)) names.addAll(row.names);
        }
        return names;
    }

    /** The keys in a stored list of picks. */
    public static Set<String> picks(String stored) {
        Set<String> keys = new HashSet<>();
        if (stored == null) return keys;
        for (String token : stored.split("[,\\n]")) {
            String key = canonical(token);
            if (!key.isEmpty()) keys.add(key);
        }
        return keys;
    }

    public static String canonical(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }
}

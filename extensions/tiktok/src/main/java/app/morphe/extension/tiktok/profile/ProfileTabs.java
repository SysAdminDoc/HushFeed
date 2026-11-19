/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
 * profile opens on the first tab left, which TikTok's pager already copes with (#399). The tabs
 * that stay can also be put in an order of your own (#466).
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

    public static final List<Row> ROWS = List.of(
            new Row("videos", "Videos", VIDEOS),
            new Row("private", "Private videos", "profile_tab_private"),
            new Row("repost", "Reposts", "profile_tab_repost"),
            new Row("favorite", "Favorites", "profile_tab_favorite"),
            new Row("like", "Liked videos", "profile_tab_like"),
            new Row("shop", "Shop", "profile_tab_ecommerce_showcase", "profile_tab_ecommerce_store"),
            new Row("music", "Music", "profile_tab_music"),
            new Row("effect", "Effects", "profile_tab_effect"),
            new Row("series", "Series", "profile_tab_short_drama", "profile_tab_drama_download",
                    "profile_tab_pine_drama_watch_history"),
            new Row("sticker", "Stickers", "profile_tab_sticker_store"),
            new Row("cast", "AI theater", "profile_tab_cast"));

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
     * Puts the tabs in the saved order (#466). Only tabs the order names move, and they move
     * among the places those tabs held, so a tab it doesn't name keeps its spot. A new list when
     * anything moved, for the same reason {@link #filter(List, Map, Set)} hands one back.
     */
    static List<?> arrange(List<?> types, Map<?, ?> names, List<String> order) {
        if (types == null || names == null || order.isEmpty()) return types;
        List<Integer> places = new ArrayList<>();
        List<Object> named = new ArrayList<>();
        for (int index = 0; index < types.size(); index++) {
            if (rank(names.get(types.get(index)), order) < 0) continue;
            places.add(index);
            named.add(types.get(index));
        }
        List<Object> sorted = new ArrayList<>(named);
        // Stable, so the two Shop tabs, or a drama's three, keep TikTok's order between them.
        sorted.sort(Comparator.comparingInt((Object type) -> rank(names.get(type), order)));
        if (sorted.equals(named)) return types;
        List<Object> arranged = new ArrayList<>(types);
        for (int index = 0; index < places.size(); index++) {
            arranged.set(places.get(index), sorted.get(index));
        }
        Logger.printDebug(() -> "Profile tabs: " + types + " arranged as " + arranged);
        return arranged;
    }

    /** Where a tab's row sits in the saved order, or -1 when the order doesn't name it. */
    private static int rank(Object name, List<String> order) {
        if (!(name instanceof String)) return -1;
        for (Row row : ROWS) {
            if (row.names.contains(name)) return order.indexOf(row.key);
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
        List<String> keys = order(stored);
        for (Row row : ROWS) {
            if (!keys.contains(row.key)) keys.add(row.key);
        }
        return keys;
    }

    private static boolean isRow(String key) {
        for (Row row : ROWS) {
            if (row.key.equals(key)) return true;
        }
        return false;
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

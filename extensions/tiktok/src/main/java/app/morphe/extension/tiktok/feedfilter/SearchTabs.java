/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The tabs along the top of the search results (Top, Users, Videos, Shop, LIVE, Places and the
 * rest), which a reader can hide one by one. TikTok serves the strip as a list of tabs, each with
 * a key that stays the same in every language and a name that follows TikTok's language. The tabs
 * served on this phone are kept as {@code key TAB name} lines, so the checklist offers them by the
 * name on screen and saves their keys. Top is the tab a search opens on, so it is never offered
 * and never hidden; an Ask Tako tab belongs to Hide the Tako bubble and is never offered either.
 */
public final class SearchTabs {
    /** TikTok's key for Top. */
    static final String TOP = "general";
    private static final int MAX_ENTRIES = 24;
    private static final int MAX_LABEL_LENGTH = 40;

    private SearchTabs() {
    }

    public static final class Entry {
        public final String key;
        public final String label;

        Entry(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    /**
     * TikTok's English names for the keys its strip uses, for a tab served without a name
     * (47.7.3 serves every tab nameless). The checklist shows them through L10n.
     */
    private static final Map<String, String> KNOWN_NAMES = Map.of(
            "user", "Users",
            "video", "Videos",
            "photos", "Photos",
            "music", "Sounds",
            "live", "LIVE",
            "shop", "Shop",
            "place", "Places",
            "hashtag", "Hashtags");

    /** The tab's key as the picks hold it, or "" for a tab without one. */
    static String key(Object tab) {
        return canonical(Reflect.string(tab, "getTabKey", "tabKey"));
    }

    /** The key of a tab in TikTok's default strip, which keeps it in its name field. */
    static String defaultKey(Object tab) {
        return canonical(Reflect.string(tab, "getTabName", "tabName"));
    }

    /** The English name for a key TikTok's strip uses, or null for one it doesn't. */
    public static String knownName(String key) {
        return KNOWN_NAMES.get(canonical(key));
    }

    public static String canonical(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** The keys to hide. Paused, the default, so nothing is. Top never is. */
    static Set<String> hiddenKeys() {
        Set<String> keys = picks(Settings.SEARCH_TAB_PICKS.get());
        keys.remove(TOP);
        return keys;
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

    /**
     * Records the tabs a strip was served with. A name TikTok now gives in another language
     * replaces the old one, and nothing is written when nothing changed.
     */
    static synchronized void observe(List<?> tabs) {
        if (tabs == null || tabs.isEmpty()) return;
        LinkedHashMap<String, String> catalog = read();
        boolean changed = false;
        for (Object tab : tabs) {
            String key = key(tab);
            if (key.isEmpty() || TOP.equals(key) || TakoAiFilter.isTakoTab(tab)) continue;
            String label = cleanLabel(Reflect.string(tab, "getTabName", "tabName"));
            if (label.isEmpty()) label = key;
            String known = catalog.get(key);
            if (known == null) {
                if (catalog.size() >= MAX_ENTRIES) continue;
                catalog.put(key, label);
                changed = true;
            } else if (!known.equals(label) && !label.equals(key)) {
                catalog.put(key, label);
                changed = true;
            }
        }
        if (changed) Settings.SEARCH_TAB_CATALOG.save(write(catalog));
    }

    public static synchronized List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : read().entrySet()) {
            result.add(new Entry(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private static LinkedHashMap<String, String> read() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        String stored = Settings.SEARCH_TAB_CATALOG.get();
        if (stored == null || stored.trim().isEmpty()) return result;
        for (String line : stored.split("\\n")) {
            int separator = line.indexOf('\t');
            if (separator <= 0) continue;
            String key = canonical(line.substring(0, separator));
            String label = cleanLabel(line.substring(separator + 1));
            if (!key.isEmpty() && !TOP.equals(key) && !label.isEmpty() && result.size() < MAX_ENTRIES) {
                result.put(key, label);
            }
        }
        return result;
    }

    private static String write(Map<String, String> catalog) {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : catalog.entrySet()) {
            if (result.length() > 0) result.append('\n');
            result.append(entry.getKey()).append('\t').append(cleanLabel(entry.getValue()));
        }
        return result.toString();
    }

    private static String cleanLabel(String value) {
        if (value == null) return "";
        String clean = value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > MAX_LABEL_LENGTH) clean = clean.substring(0, MAX_LABEL_LENGTH).trim();
        return clean;
    }
}

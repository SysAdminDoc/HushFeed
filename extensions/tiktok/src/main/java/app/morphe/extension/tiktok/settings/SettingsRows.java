/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The rows of TikTok's Settings and privacy page a reader can hide (#244). TikTok builds the page
 * from one sorted list of enum items, an enum per section, each section led by its SECTION_HEADER
 * item. The patch hands that list over before the Hushfeed row goes in, so a pick comes out of it
 * here by its enum name, which is the same in every language. A section left with no rows loses
 * its header too. The names listed on this phone are kept, so the checklist offers those.
 */
public final class SettingsRows {
    static final String FAMILY = "settings rows";
    private static final String HEADER = "section_header";
    /** The Hushfeed row, which has a switch of its own. */
    private static final String OPEN_DEBUG = "open_debug";
    private static final int MAX_ENTRIES = 120;
    private static final int MAX_NAME_LENGTH = 60;

    private SettingsRows() {
    }

    public static final class Row {
        public final String key;
        public final String label;

        Row(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }

    /**
     * TikTok's English labels for the rows its 47.x page lists, in the page's order. The checklist
     * shows them through L10n.
     */
    public static final List<Row> ROWS = List.of(
            new Row("analytics_center", "Activity center"),
            new Row("manage_posts", "Manage posts"),
            new Row("content_preference", "Content preferences"),
            new Row("live_settings", "LIVE"),
            new Row("notification_settings", "Notifications"),
            new Row("screen_time", "Time and well-being"),
            new Row("family_pairing", "Family Pairing"),
            new Row("signup", "Sign up"),
            new Row("account_item", "Account"),
            new Row("privacy", "Privacy"),
            new Row("security_and_permissions", "Security & permissions"),
            new Row("analytics", "Analytics"),
            new Row("orders", "Orders"),
            new Row("wallet", "Balance"),
            new Row("qrcode", "QR code"),
            new Row("share_profile", "Share profile"),
            new Row("tiktok_plus", "TikTok Plus"),
            new Row("referral_invite_friends", "Invite friends"),
            new Row("favorite_settings", "Favorites"),
            new Row("music_settings", "Music"),
            new Row("switch_inbox_mode", "Inbox mode"),
            new Row("audience_control", "Audience controls"),
            new Row("background_audio", "Background audio"),
            new Row("ads_settings", "Ads"),
            new Row("playback_settings", "Playback"),
            new Row("display", "Display"),
            new Row("commercial_content", "Commercial content library"),
            new Row("language", "Language"),
            new Row("accessibility", "Accessibility"),
            new Row("shortcut", "Home screen shortcuts"),
            new Row("join_amplify", "Join Amplify"),
            new Row("contacts_and_location", "Contacts and location"),
            new Row("shake", "Shake to report"),
            new Row("offline_videos", "Offline videos"),
            new Row("clear_cache", "Free up space"),
            new Row("data_saver", "Data Saver"),
            new Row("zero_rating", "Free data"),
            new Row("local_live_wallpaper", "Live wallpaper"),
            new Row("check_update", "Check for updates"),
            new Row("lite_mode", "Lite mode"),
            new Row("base_settings", "TikTok Beta"),
            new Row("feedback", "Report a problem"),
            new Row("safety_center", "Safety Center"),
            new Row("privacy_center", "Privacy Center"),
            new Row("privacy_checkup", "Privacy checkup"),
            new Row("support", "Support"),
            new Row("account_deletion", "Deactivate or delete account"),
            new Row("about", "Terms and Policies"),
            new Row("policy_update", "Policy updates"),
            // Not "What's new", which names Hushfeed's own page.
            new Row("whats_new", "What's new on TikTok"),
            new Row("switch_account", "Switch account"),
            new Row("logout", "Log out"));

    /**
     * Records the rows TikTok listed, then takes the picked ones out, and the header of a section
     * that has none left. Paused, nothing is picked. Anything that fails leaves the list whole.
     */
    public static void filter(List<Object> rows) {
        try {
            if (rows == null || rows.isEmpty()) return;
            List<String> listed = new ArrayList<>();
            for (Object item : rows) {
                String name = name(item);
                if (!name.isEmpty() && !isHeader(name) && !OPEN_DEBUG.equals(name)) listed.add(name);
            }
            observe(listed);

            Set<String> picked = picks(Settings.SETTINGS_ROW_PICKS.get());
            picked.remove(OPEN_DEBUG);
            if (picked.isEmpty()) return;
            Set<Class<?>> emptied = new HashSet<>();
            Set<Class<?>> kept = new HashSet<>();
            for (Object item : rows) {
                String name = name(item);
                if (name.isEmpty() || isHeader(name)) continue;
                (picked.contains(name) ? emptied : kept).add(section(item));
            }
            emptied.removeAll(kept);
            int removed = 0;
            for (Iterator<Object> each = rows.iterator(); each.hasNext(); ) {
                Object item = each.next();
                String name = name(item);
                if (name.isEmpty()) continue;
                if (isHeader(name) ? emptied.contains(section(item)) : picked.contains(name)) {
                    each.remove();
                    removed++;
                }
            }
            HookStatus.bound(FAMILY, removed > 0 ? "picked rows hidden" : "picked rows not listed");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "settings rows", failure);
            Logger.printException(() -> "SettingsRows.filter failure", failure);
        }
    }

    /** The rows the checklist offers: the table's rows this phone listed or that were picked, in
     * the page's order, then any listed row the table doesn't know. Nothing listed yet offers the
     * whole table. */
    public static List<Row> offered(Set<String> picked) {
        Set<String> listed = catalog();
        List<Row> result = new ArrayList<>();
        Set<String> known = new HashSet<>();
        for (Row row : ROWS) {
            known.add(row.key);
            if (listed.isEmpty() || listed.contains(row.key) || picked.contains(row.key)) result.add(row);
        }
        for (String name : listed) {
            if (!known.contains(name)) result.add(new Row(name, readable(name)));
        }
        return result;
    }

    /** The names in a stored list of picks. */
    public static Set<String> picks(String stored) {
        Set<String> names = new HashSet<>();
        if (stored == null) return names;
        for (String token : stored.split("[,\\n]")) {
            String name = canonical(token);
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    public static String canonical(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    /** "SOME_ROW" as "Some row", for a row the table has no label for. */
    static String readable(String name) {
        String words = name.replace('_', ' ').trim();
        if (words.isEmpty()) return name;
        return Character.toUpperCase(words.charAt(0)) + words.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String name(Object item) {
        if (!(item instanceof Enum)) return "";
        return canonical(((Enum<?>) item).name());
    }

    private static Class<?> section(Object item) {
        return ((Enum<?>) item).getDeclaringClass();
    }

    private static boolean isHeader(String name) {
        return name.endsWith(HEADER);
    }

    /** Adds the names not seen before, and writes only when something was added. */
    static synchronized void observe(List<String> listed) {
        if (listed.isEmpty()) return;
        LinkedHashSet<String> catalog = catalog();
        boolean changed = false;
        for (String name : listed) {
            if (catalog.size() >= MAX_ENTRIES) break;
            if (name.length() <= MAX_NAME_LENGTH && catalog.add(name)) changed = true;
        }
        if (changed) Settings.SETTINGS_ROW_CATALOG.save(String.join(",", catalog));
    }

    static synchronized LinkedHashSet<String> catalog() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String stored = Settings.SETTINGS_ROW_CATALOG.get();
        if (stored == null) return result;
        for (String token : stored.split(",")) {
            String name = canonical(token);
            if (!name.isEmpty() && !isHeader(name) && !OPEN_DEBUG.equals(name)
                    && name.length() <= MAX_NAME_LENGTH && result.size() < MAX_ENTRIES) {
                result.add(name);
            }
        }
        return result;
    }
}

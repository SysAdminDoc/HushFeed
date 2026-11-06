/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * A small dot after the title of a setting an update added, and after the title of the closed
 * section it's in, so new options are easy to find (#349).
 *
 * <p>There is no list of which release added what. The keys of the saved settings the person has
 * already been shown are kept in a file of their own, apart from the settings, so a backup neither
 * exports nor imports them. The first time the settings open with none stored, every setting
 * there is counts as seen: a fresh install never shows a dot. A row whose setting isn't in the
 * set keeps its dot for the visit that shows it, and it's gone by the next one. A section row
 * loses its dot as soon as every new row in it has been on screen.
 *
 * <p>Anything that goes wrong shows no dot.
 */
final class NewSettingDots {
    static final String FILE = "hushfeed_new_dots";
    static final String SEEN = "seen";
    /** Marks a title that wears the dot, so a recycled row can lose it and a test can find it. */
    static final int TAG = 0x7f7f4008;

    /** One row the settings can show: the section row that opens it, its key and the setting it saves. */
    static final class Row {
        /** The key of the section row it sits under, or null for a row on the home page. */
        @Nullable final String section;
        final String row;
        final String setting;

        Row(@Nullable String section, String row, String setting) {
            this.section = section;
            this.row = row;
            this.setting = setting;
        }
    }

    /** Lists every row. Only asked when some setting is new, since it builds every section. */
    interface Rows {
        List<Row> list();
    }

    /** The settings showing now, which their rows ask while they bind. Null when there are none. */
    @Nullable private static volatile NewSettingDots current;

    private final SharedPreferences store;
    private final Set<String> seen = new HashSet<>();
    /** The new settings on each row. Fixed for the visit, so a dot doesn't go out under a finger. */
    private final Map<String, Set<String>> newByRow = new HashMap<>();
    /** The rows with a new setting under each section row. */
    private final Map<String, Set<String>> newBySection = new HashMap<>();
    /** The new settings whose row has been on screen with its dot. */
    private final Set<String> shown = new HashSet<>();

    NewSettingDots(SharedPreferences store, Collection<String> registered, Rows rows) {
        this.store = store;
        Set<String> stored = null;
        try {
            stored = store.getStringSet(SEEN, null);
        } catch (RuntimeException unreadable) {
            // A value of another kind is no set: start again, as on a first open.
        }
        if (stored == null) {
            seen.addAll(registered);
            save();
            return;
        }
        seen.addAll(stored);
        Set<String> unseen = new HashSet<>(registered);
        unseen.removeAll(seen);
        if (unseen.isEmpty()) return;
        for (Row row : rows.list()) {
            if (row.row == null || row.setting == null || !unseen.contains(row.setting)) continue;
            add(newByRow, row.row, row.setting);
            if (row.section != null) add(newBySection, row.section, row.row);
        }
        // A setting no row shows (a count, a saved position, the last search) would read as new
        // on every visit and keep the rows above built each time: it's seen the moment it's found.
        for (Set<String> settings : newByRow.values()) unseen.removeAll(settings);
        if (!unseen.isEmpty()) {
            seen.addAll(unseen);
            save();
        }
    }

    private static void add(Map<String, Set<String>> map, String key, String value) {
        Set<String> values = map.get(key);
        if (values == null) {
            values = new HashSet<>();
            map.put(key, values);
        }
        values.add(value);
    }

    /** Starts the dots for the settings opening now, or none when that can't be done. */
    static void start(Context context, Rows rows) {
        try {
            List<String> registered = new ArrayList<>();
            for (Setting<?> setting : Setting.allLoadedSettings()) registered.add(setting.key);
            current = new NewSettingDots(context.getApplicationContext()
                    .getSharedPreferences(FILE, Context.MODE_PRIVATE), registered, rows);
        } catch (RuntimeException unavailable) {
            current = null;
        }
    }

    /** The settings are going to the background or closing: every dot shown so far is seen. */
    static void commit() {
        NewSettingDots dots = current;
        if (dots == null) return;
        try {
            int before = dots.seen.size();
            dots.seen.addAll(dots.shown);
            if (dots.seen.size() != before) dots.save();
        } catch (RuntimeException ignored) {
            // The dots stay a visit longer.
        }
    }

    private void save() {
        try {
            store.edit().putStringSet(SEEN, new HashSet<>(seen)).apply();
        } catch (RuntimeException ignored) {
            // The dots come back next time.
        }
    }

    boolean fresh(Preference preference) {
        String key = preference.getKey();
        if (key == null) return false;
        Set<String> sectionRows = newBySection.get(key);
        if (sectionRows != null) {
            // An open section shows its own rows' dots right under it.
            if (preference instanceof SettingsMenuPreference
                    && ((SettingsMenuPreference) preference).isExpanded()) return false;
            for (String row : sectionRows) {
                Set<String> settings = newByRow.get(row);
                if (settings != null && !shown.containsAll(settings)) return true;
            }
            return false;
        }
        Set<String> settings = newByRow.get(key);
        if (settings == null) return false;
        shown.addAll(settings);
        return true;
    }

    /** Paints [row]'s dot, or takes an old one off, as the settings showing now say. */
    static void paint(View row, Preference preference, int accent) {
        boolean fresh = false;
        try {
            NewSettingDots dots = current;
            fresh = dots != null && dots.fresh(preference);
        } catch (RuntimeException failed) {
            // No dot.
        }
        paint(row, fresh, accent);
    }

    /**
     * Puts the dot after [row]'s title when [fresh], and takes any earlier one off. The dot is part
     * of the title's text line, so it follows the reading direction, wraps with the title at a
     * large text size and can't leave the row. A drawable the row put at the title's start stays.
     */
    static void paint(View row, boolean fresh, int accent) {
        try {
            TextView title = row.findViewById(android.R.id.title);
            if (title == null) return;
            boolean had = title.getTag(TAG) != null;
            if (!fresh && !had) return;
            Drawable[] drawables = title.getCompoundDrawablesRelative();
            if (!fresh) {
                title.setCompoundDrawablesRelative(drawables[0], drawables[1], null, drawables[3]);
                title.setTag(TAG, null);
                title.setContentDescription(null);
                return;
            }
            float density = row.getResources().getDisplayMetrics().density;
            int size = Math.round(8 * density);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(accent);
            dot.setBounds(0, 0, size, size);
            title.setCompoundDrawablesRelative(drawables[0], drawables[1], dot, drawables[3]);
            // A start mark keeps the gap its row gave it.
            if (drawables[0] == null) title.setCompoundDrawablePadding(Math.round(8 * density));
            title.setTag(TAG, Boolean.TRUE);
            title.setContentDescription(L10n.f(row.getContext(), "%1$s, new", title.getText()));
        } catch (RuntimeException ignored) {
            // No dot.
        }
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateFormat;

import androidx.annotation.Nullable;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * What changed on a profile since you last opened it: its followers, following, likes and
 * videos, under the @username ("Since Nov 18: +12 followers, +1 video").
 *
 * <p>TikTok binds each count as the header draws it. The follower, following and like counts
 * come from the relation bar's own number formatter, with the profile's ID beside them, and the
 * video count from the header's common info. Each profile keeps the counts it showed last time
 * and when, on this phone only. A visit's starting point is what was kept before it began, and it
 * holds while the profile keeps binding: a visit ends after {@link #VISIT_GAP_MS} without one, so
 * Back and forth through a profile's videos is still the same visit.</p>
 *
 * <p>A zero is never kept. TikTok draws a header from the short account it already has before the
 * profile itself loads, and those counts can be zero, which would read as a jump next time.</p>
 */
public final class ProfileChanges {
    public static final String PREFS_NAME = "hushfeed_profile_changes";
    static final long VISIT_GAP_MS = 15L * 60 * 1000;
    static final long WRITE_GAP_MS = 60L * 1000;
    /** Past this many visits in memory, the ones that are over get dropped. */
    private static final int OPEN_VISITS = 64;
    /** The most profiles kept; the ones seen longest ago go first. */
    static final int MAX_PROFILES = 2000;

    public enum Metric { FOLLOWERS, FOLLOWING, LIKES, VIDEOS }

    /** So a test can set the clock. */
    static volatile Long nowForTests;

    /** The counts a profile showed, and when they were last seen. */
    static final class Snapshot {
        final EnumMap<Metric, Long> counts = new EnumMap<>(Metric.class);
        long at;

        Snapshot copy() {
            Snapshot copy = new Snapshot();
            copy.counts.putAll(counts);
            copy.at = at;
            return copy;
        }

        String encode() {
            StringBuilder out = new StringBuilder().append(at);
            for (Metric metric : Metric.values()) {
                out.append('|');
                Long value = counts.get(metric);
                if (value != null) out.append(value);
            }
            return out.toString();
        }

        @Nullable
        static Snapshot decode(@Nullable Object saved) {
            if (!(saved instanceof String)) return null;
            String[] parts = ((String) saved).split("\\|", -1);
            if (parts.length != Metric.values().length + 1) return null;
            try {
                Snapshot snapshot = new Snapshot();
                snapshot.at = Long.parseLong(parts[0]);
                for (Metric metric : Metric.values()) {
                    String part = parts[metric.ordinal() + 1];
                    if (!part.isEmpty()) snapshot.counts.put(metric, Long.parseLong(part));
                }
                return snapshot;
            } catch (NumberFormatException unreadable) {
                return null;
            }
        }
    }

    /** One visit: what was kept before it began, and what this visit has shown so far. */
    private static final class Visit {
        @Nullable final Snapshot before;
        final Snapshot now;
        long lastSeen;

        Visit(@Nullable Snapshot before, Snapshot now) {
            this.before = before;
            this.now = now;
        }
    }

    private static final Map<String, Visit> visits = new HashMap<>();
    private static final Map<String, Snapshot> kept = new HashMap<>();
    private static boolean loaded;

    private ProfileChanges() {
    }

    public static boolean enabled() {
        return SettingsStatus.followStatusEnabled && Settings.SHOW_PROFILE_CHANGES.get()
                && !HushfeedPause.isPaused();
    }

    /**
     * From the relation bar's number formatter, with the profile's ID, the count's component
     * ({@code relation_info_follower} and the rest) and the number TikTok is about to draw.
     * Never throws.
     */
    public static void count(@Nullable String uid, @Nullable String component, @Nullable Number value) {
        try {
            if (!enabled() || component == null || value == null) return;
            LocalProfileCounts.Metric which = LocalProfileCounts.Metric.from(component);
            if (which == null) return;
            Metric metric = which == LocalProfileCounts.Metric.FOLLOWERS ? Metric.FOLLOWERS
                    : which == LocalProfileCounts.Metric.FOLLOWING ? Metric.FOLLOWING : Metric.LIKES;
            observe(uid, metric, value.longValue());
        } catch (Throwable unread) {
            Logger.printDebug(() -> "Profile changes: could not read a count: " + unread);
        }
    }

    /** A count a profile shows. Tells the header when the line it should show changes. */
    static void observe(@Nullable String uid, Metric metric, long value) {
        if (uid == null || uid.isEmpty() || uid.equals("0") || value <= 0) return;
        boolean changed;
        synchronized (ProfileChanges.class) {
            load();
            long now = now();
            Visit visit = visits.get(uid);
            if (visit == null || now - visit.lastSeen > VISIT_GAP_MS) {
                if (visits.size() >= OPEN_VISITS) endOldVisits(now);
                Snapshot before = kept.get(uid);
                visit = new Visit(before == null ? null : before.copy(), new Snapshot());
                visits.put(uid, visit);
            }
            visit.lastSeen = now;
            Long was = visit.now.counts.put(metric, value);
            changed = was == null || was != value;
            Snapshot saved = kept.get(uid);
            if (saved == null) {
                saved = new Snapshot();
                kept.put(uid, saved);
            }
            Long savedBefore = saved.counts.put(metric, value);
            // Every text item in a header binds the same counts, so an unchanged one is written
            // at most once a minute, just to keep its time current.
            boolean write = savedBefore == null || savedBefore != value || now - saved.at >= WRITE_GAP_MS;
            if (write) saved.at = now;
            SharedPreferences preferences = write ? preferences() : null;
            if (preferences != null) {
                SharedPreferences.Editor editor = preferences.edit().putString(uid, saved.encode());
                trim(editor);
                editor.apply();
            }
        }
        if (changed) FollowStatus.refreshChanges(uid);
    }

    /**
     * The line for a profile's header in the phone's language, or null when it has nothing to
     * say: the switch is off, the profile is new to this phone, or no count is known both times.
     */
    @Nullable
    public static String line(Context context, @Nullable String uid) {
        if (!enabled() || uid == null) return null;
        Snapshot before;
        Snapshot now;
        synchronized (ProfileChanges.class) {
            Visit visit = visits.get(uid);
            if (visit == null || visit.before == null) return null;
            before = visit.before.copy();
            now = visit.now.copy();
        }
        return line(context, before, now, Locale.getDefault(), TimeZone.getDefault(), now());
    }

    @Nullable
    static String line(Context context, Snapshot before, Snapshot now, Locale locale, TimeZone zone, long nowMillis) {
        List<String> parts = new ArrayList<>();
        boolean compared = false;
        NumberFormat format = NumberFormat.getIntegerInstance(locale);
        for (Metric metric : Metric.values()) {
            Long was = before.counts.get(metric);
            Long is = now.counts.get(metric);
            if (was == null || is == null) continue;
            compared = true;
            long delta = is - was;
            if (delta == 0) continue;
            String signed = (delta > 0 ? "+" : String.valueOf((char) 0x2212)) + format.format(Math.abs(delta));
            parts.add(part(context, metric, Math.abs(delta), signed));
        }
        if (!compared) return null;
        String when = when(before.at, nowMillis, locale, zone);
        if (parts.isEmpty()) return L10n.f(context, "No changes since %1$s", when);
        String joined = parts.get(0);
        for (int index = 1; index < parts.size(); index++) {
            joined = L10n.f(context, "%1$s, %2$s", joined, parts.get(index));
        }
        return L10n.f(context, "Since %1$s: %2$s", when, joined);
    }

    private static String part(Context context, Metric metric, long size, String signed) {
        switch (metric) {
            case FOLLOWERS:
                return L10n.quantity(context, size, "%1$s follower", "%1$s followers", signed);
            case FOLLOWING:
                return L10n.f(context, "%1$s following", signed);
            case LIKES:
                return L10n.quantity(context, size, "%1$s like", "%1$s likes", signed);
            default:
                return L10n.quantity(context, size, "%1$s video", "%1$s videos", signed);
        }
    }

    /** The time when it was the same day, else the date, with the year once it isn't this year. */
    static String when(long at, long nowMillis, Locale locale, TimeZone zone) {
        Calendar then = Calendar.getInstance(zone, locale);
        then.setTimeInMillis(at);
        Calendar today = Calendar.getInstance(zone, locale);
        today.setTimeInMillis(nowMillis);
        String skeleton;
        if (then.get(Calendar.YEAR) != today.get(Calendar.YEAR)) skeleton = "MMMdy";
        else if (then.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR)) skeleton = "MMMd";
        else skeleton = "jmm";
        SimpleDateFormat format = new SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale);
        format.setTimeZone(zone);
        return format.format(new Date(at));
    }

    /** Reads the kept profiles once a process. */
    private static void load() {
        if (loaded) return;
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        for (Map.Entry<String, ?> saved : preferences.getAll().entrySet()) {
            Snapshot snapshot = Snapshot.decode(saved.getValue());
            if (snapshot != null) kept.put(saved.getKey(), snapshot);
        }
        loaded = true;
        if (kept.size() > MAX_PROFILES) {
            SharedPreferences.Editor editor = preferences.edit();
            trim(editor);
            editor.apply();
        }
    }

    /** Drops the visits that are over, so a long session doesn't hold every profile it opened. */
    private static void endOldVisits(long now) {
        for (Iterator<Visit> open = visits.values().iterator(); open.hasNext(); ) {
            if (now - open.next().lastSeen > VISIT_GAP_MS) open.remove();
        }
    }

    /** Forgets the profiles seen longest ago once there are more than {@link #MAX_PROFILES}. */
    private static void trim(SharedPreferences.Editor editor) {
        int over = kept.size() - MAX_PROFILES;
        if (over <= 0) return;
        List<Map.Entry<String, Snapshot>> oldest = new ArrayList<>(kept.entrySet());
        Collections.sort(oldest, (a, b) -> Long.compare(a.getValue().at, b.getValue().at));
        for (int index = 0; index < over; index++) {
            String uid = oldest.get(index).getKey();
            kept.remove(uid);
            editor.remove(uid);
        }
    }

    @Nullable
    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static long now() {
        Long forTests = nowForTests;
        return forTests != null ? forTests : System.currentTimeMillis();
    }

    /** Ends every visit, keeping the saved file, as a restart does. For tests. */
    static void forgetVisitsForTests() {
        synchronized (ProfileChanges.class) {
            visits.clear();
            kept.clear();
            loaded = false;
        }
    }

    /** Forgets everything, for tests. */
    static void clearForTests() {
        synchronized (ProfileChanges.class) {
            visits.clear();
            kept.clear();
            loaded = false;
            SharedPreferences preferences = preferences();
            if (preferences != null) preferences.edit().clear().commit();
        }
    }
}

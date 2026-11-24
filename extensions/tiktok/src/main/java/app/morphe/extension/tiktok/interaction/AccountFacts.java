/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.publishdate.AlwaysShowPublishDatePatch;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * Account facts: what TikTok's own account model already holds about a profile, shown from a
 * button on the profile's share sheet beside the copy buttons. Nothing is fetched. The fields
 * are filled from the profile response (create_time, region, account_region, language,
 * pay_grade, aweme_count, favoriting_count, collect_count, forward_count, unique_id_modify_time,
 * nick_name_modify_ts, secret, has_open_favorite and sec_uid, has_open_favorite being what the
 * profile's own tab pager reads to show the liked videos tab) and read by their real names. pay_grade becomes the model's userHonor, whose level is the
 * gifter level LIVE shows; nothing in TikTok's own screens reads it off this model (#487).
 * A date, name or level the server didn't send says Unknown rather than guessing. secret and
 * has_open_favorite can't say that, so they only get a line when they're true. When the
 * profile's model lacks the join date, region or language, the same account's author model on
 * one of the videos behind the sheet gives it. A join date no model holds comes from the
 * account's ID, whose top 32 bits are the time TikTok made it.
 */
public final class AccountFacts {
    static final String SHOW_FACTS = "Account facts";
    static final String FACTS_COPIED = "Account facts copied";
    static final String UNKNOWN = "Unknown (TikTok didn't send it)";
    /** A seconds value past this is really milliseconds; it's the year 5138 in seconds. */
    private static final long LARGEST_SECONDS = 100_000_000_000L;
    static final String AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme";
    /** Enough other models of one account, about two pages of its grid; the first that holds a fact gives it. */
    private static final int MOST_COPIES = 64;

    private AccountFacts() {
    }

    static boolean enabled() {
        return SettingsStatus.copyIdsEnabled && Settings.ACCOUNT_FACTS.get();
    }

    /** One line of the sheet: its label, which is a key, and its value in the phone's words. */
    static final class Fact {
        final String label;
        final String value;

        Fact(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    /** The sheet's lines for {@code user}, in the order they show. */
    static List<Fact> of(Object user, Locale locale, TimeZone zone) {
        return of(user, Collections.emptyList(), locale, zone);
    }

    /**
     * The sheet's lines for {@code user}, with a join date, region or language it lacks taken
     * from the first of {@code copies} that has it. Copies are other models of the same account.
     */
    static List<Fact> of(Object user, List<Object> copies, Locale locale, TimeZone zone) {
        List<Object> sources = new ArrayList<>(copies.size() + 1);
        sources.add(user);
        sources.addAll(copies);
        List<Fact> facts = new ArrayList<>();
        // 47.4.3 renamed the boxed join time to _createTime.
        Long joined = seconds(sources, "_createTime", "createTime", "registerTime");
        if (joined == null) joined = madeAt(Reflect.string(user, "getUid", "uid"));
        facts.add(new Fact("Joined", date(joined, locale, zone)));
        String region = text(sources, "region");
        facts.add(new Fact("Region", country(region, locale)));
        String accountRegion = text(sources, "accountRegion");
        if (accountRegion != null && !accountRegion.equalsIgnoreCase(region)) {
            facts.add(new Fact("Account region", country(accountRegion, locale)));
        }
        facts.add(new Fact("Language", language(text(sources, "language"), locale)));
        facts.add(new Fact("Gifter level", level(gifterLevel(sources), locale)));
        // The counts are plain ints on the model, so one the server left out reads 0 and gets no
        // line. They come from the profile's own model only: a video's author model carries the
        // counts of whenever that video was served, or none (#487).
        addCount(facts, "Posts", Reflect.readField(user, "awemeCount"), locale);
        String liked = count(Reflect.readField(user, "favoritingCount"), locale);
        boolean likesPublic = Boolean.TRUE.equals(Reflect.readField(user, "hasOpenFavorite"));
        if (liked != null || likesPublic) {
            String isPublic = L10n.t("Public");
            facts.add(new Fact("Liked videos",
                    liked == null ? isPublic : likesPublic ? liked + " (" + isPublic + ")" : liked));
        }
        addCount(facts, "Favorites", Reflect.readField(user, "collectCount"), locale);
        // forward_count, which the model calls repostCount.
        addCount(facts, "Reposts", Reflect.readField(user, "repostCount"), locale);
        // A video's author model has unique_id_modify_time set to when the response was made
        // (Login2 47.4.3: 73 models of different accounts shared one second), so the change
        // dates come from the profile's own model only. Nothing here or in TikTok's client drops
        // an old change: the JSON adapter copies the two keys as they came, so a change date
        // missing for an older rename is the profile response leaving it out (#487).
        facts.add(new Fact("Username last changed",
                date(seconds(Reflect.readField(user, "uniqueIdModifyTime")), locale, zone)));
        facts.add(new Fact("Display name last changed",
                date(seconds(Reflect.readField(user, "nickNameModifyTs")), locale, zone)));
        // A plain boolean on the model, so one the server left out reads false. Only true is a
        // fact, and false leaves its line out. 47.4.3 renamed secret to isSecret.
        Object secret = Reflect.readField(user, "isSecret");
        if (secret == null) secret = Reflect.readField(user, "secret");
        if (Boolean.TRUE.equals(secret)) {
            facts.add(new Fact("Private account", L10n.t("Yes")));
        }
        // Last, as it's long and only useful copied.
        String secUid = text(sources, "secUid");
        facts.add(new Fact("SecUID", secUid == null ? L10n.t(UNKNOWN) : secUid));
        return facts;
    }

    /** The sheet as text, one "label: value" line per fact, which is also what Copy copies. */
    static String format(List<Fact> facts) {
        StringBuilder text = new StringBuilder();
        for (Fact fact : facts) {
            if (text.length() > 0) text.append('\n');
            text.append(L10n.t(fact.label)).append(": ").append(fact.value);
        }
        return text.toString();
    }

    /** From the facts button: the sheet over whatever screen is showing. */
    static void show(Object user) {
        try {
            Activity activity = Utils.getVisibleActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            List<Object> copies = copiesOnScreen(activity.getWindow().getDecorView(), user);
            String text = format(of(user, copies, Locale.getDefault(), TimeZone.getDefault()));
            String handle = Reflect.string(user, "getUniqueId", "uniqueId");
            SettingsUi.syncDarkMode(activity);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(handle == null ? L10n.t(SHOW_FACTS) : "@" + handle)
                    .setMessage(text)
                    .setPositiveButton(L10n.t("Close"), null)
                    .setNeutralButton(L10n.t("Copy"), (ignored, which) -> {
                        if (GestureActions.copyToClipboard(L10n.t("TikTok account facts"), text)) {
                            Utils.showToastShort(L10n.t(FACTS_COPIED));
                        }
                    })
                    .create();
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            dialog.show();
        } catch (Throwable error) {
            Logger.printException(() -> "Could not show the account facts", error);
        }
    }

    /** A Unix time in seconds, or null for nothing or zero. A milliseconds value is turned into seconds. */
    @Nullable
    static Long seconds(@Nullable Object value) {
        if (!(value instanceof Number)) return null;
        long raw = ((Number) value).longValue();
        if (raw <= 0) return null;
        return raw > LARGEST_SECONDS ? raw / 1000L : raw;
    }

    /** When TikTok made the account with this ID, in Unix seconds, or null for an ID that carries no time. */
    @Nullable
    static Long madeAt(@Nullable String uid) {
        long seconds = AlwaysShowPublishDatePatch.fromId(uid, System.currentTimeMillis());
        return seconds > 0 ? seconds : null;
    }

    /**
     * The gifter level in the first model holding pay_grade, or null when none does. A level of 0
     * is what the server sent and is shown as it came.
     */
    @Nullable
    static Integer gifterLevel(List<Object> sources) {
        for (Object source : sources) {
            Object level = Reflect.readField(Reflect.readField(source, "userHonor"), "level");
            if (level instanceof Number) return ((Number) level).intValue();
        }
        return null;
    }

    /** A count above zero in the phone's digits, or null for zero, below zero or no number. */
    @Nullable
    static String count(@Nullable Object value, Locale locale) {
        if (!(value instanceof Number)) return null;
        long count = ((Number) value).longValue();
        return count > 0 ? NumberFormat.getIntegerInstance(locale).format(count) : null;
    }

    private static void addCount(List<Fact> facts, String label, @Nullable Object value, Locale locale) {
        String count = count(value, locale);
        if (count != null) facts.add(new Fact(label, count));
    }

    static String level(@Nullable Integer level, Locale locale) {
        return level == null ? L10n.t(UNKNOWN) : NumberFormat.getIntegerInstance(locale).format(level);
    }

    static String date(@Nullable Long seconds, Locale locale, TimeZone zone) {
        if (seconds == null) return L10n.t(UNKNOWN);
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale);
        format.setTimeZone(zone);
        return format.format(new Date(seconds * 1000L));
    }

    /** A region code with its country's name in the phone's language: "Germany (DE)". */
    static String country(@Nullable String code, Locale locale) {
        if (code == null) return L10n.t(UNKNOWN);
        String upper = code.toUpperCase(Locale.ROOT);
        String name = new Locale("", upper).getDisplayCountry(locale);
        // A code with no country behind it (ZZ, or a user-assigned one) gets the same generic
        // "Unknown Region" the platform gives ZZ, in the phone's language. That says nothing
        // about the account, so the code is shown as it came.
        String generic = new Locale("", "ZZ").getDisplayCountry(locale);
        return name.isEmpty() || name.equalsIgnoreCase(upper) || name.equals(generic)
                ? upper : name + " (" + upper + ")";
    }

    /** A language tag with its name in the phone's language: "German (de)". */
    static String language(@Nullable String tag, Locale locale) {
        if (tag == null) return L10n.t(UNKNOWN);
        String name = Locale.forLanguageTag(tag.replace('_', '-')).getDisplayName(locale);
        return name.isEmpty() || name.equalsIgnoreCase(tag) ? tag : name + " (" + tag + ")";
    }

    @Nullable
    private static String text(@Nullable Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** The first time any source holds under any of the names, the names tried in order. */
    @Nullable
    private static Long seconds(List<Object> sources, String... names) {
        for (String name : names) {
            for (Object source : sources) {
                Long seconds = seconds(Reflect.readField(source, name));
                if (seconds != null) return seconds;
            }
        }
        return null;
    }

    @Nullable
    private static String text(List<Object> sources, String name) {
        for (Object source : sources) {
            String text = text(Reflect.readField(source, name));
            if (text != null) return text;
        }
        return null;
    }

    /**
     * Other models of {@code user}'s account on the screen behind the share sheet: the authors of
     * the videos in its lists, matched by uid. On 47.4.3 the profile response leaves out
     * create_time, region and language, so the profile's own model (the one the share sheet
     * holds) has none of them, while the author model on each video in the profile's grid has
     * the language, and a feed video's author also has the region (Login2 heap, 2026-10-20).
     */
    static List<Object> copiesOnScreen(@Nullable View root, Object user) {
        List<Object> copies = new ArrayList<>();
        String uid = Reflect.string(user, "getUid", "uid");
        if (root == null || uid == null) return copies;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(user);
        ArrayDeque<View> views = new ArrayDeque<>();
        views.add(root);
        while (!views.isEmpty() && copies.size() < MOST_COPIES) {
            View view = views.poll();
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) views.add(group.getChildAt(i));
                // RecyclerView's adapter, read by name so nothing here needs RecyclerView itself.
                Object adapter = Reflect.readField(view, "mAdapter");
                if (adapter != null) addAuthors(adapter, uid, seen, copies);
            }
        }
        return copies;
    }

    private static boolean isVideo(Class<?> type) {
        for (; type != null && type != Object.class; type = type.getSuperclass()) {
            if (AWEME.equals(type.getName())) return true;
        }
        return false;
    }

    /** The authors matching {@code uid} in every list of videos the adapter holds. */
    private static void addAuthors(Object adapter, String uid, Set<Object> seen, List<Object> copies) {
        for (Class<?> type = adapter.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (Throwable error) {
                continue;
            }
            for (Field field : fields) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                List<Object> items = snapshot(adapter, field, seen);
                if (items == null) continue;
                for (Object item : items) {
                    if (item == null || !isVideo(item.getClass())) continue;
                    Object author = Reflect.property(item, "getAuthor", "author");
                    if (author != null && seen.add(author)
                            && uid.equals(Reflect.string(author, "getUid", "uid"))) {
                        copies.add(author);
                        if (copies.size() >= MOST_COPIES) return;
                    }
                }
            }
        }
    }

    /**
     * A copy of the list a field holds, or null when it holds none, can't be read or was already
     * read through another adapter or field.
     */
    @Nullable
    private static List<Object> snapshot(Object owner, Field field, Set<Object> seen) {
        try {
            field.setAccessible(true);
            Object list = field.get(owner);
            return list instanceof List && seen.add(list) ? new ArrayList<>((List<?>) list) : null;
        } catch (Throwable error) {
            Logger.printDebug(() -> "Account facts could not read " + field + ": " + error);
            return null;
        }
    }
}

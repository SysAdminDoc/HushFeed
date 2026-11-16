/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import android.content.Context;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.feed.AuthorRegion;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * Asks TikTok which country an account is based in (#417), from the comment menu's Show account
 * country row and a profile share sheet's Account country button.
 *
 * <p>A comment's account often comes without its country. The profile reply TikTok's own
 * assistant card reads, {@code /aweme/v1/user/profile/other/}, is asked first and its
 * {@code region} or {@code account_region} wins, but on 47.7 that reply leaves both out for
 * anyone but the viewer (66 user keys and no country on @tiktok, 2026-11-14). So the account's
 * newest videos from {@code /aweme/v1/aweme/post/} come next, and the country most of them were
 * posted from is the answer, worded as where the videos were posted from rather than where the
 * account is based. A private account's videos stay hidden, so it gets no country. The request
 * goes out through TikTok's own
 * client the way its block action's does: {@code ServiceManager} hands out the
 * {@code IRetrofitService}, whose {@code createNewRetrofit} builds a client for the API host in
 * {@code Api}, and TTNet's {@code INetworkApi} on that client fetches the raw reply, so it is
 * signed and routed like any other TikTok call. Every name used here survives obfuscation except
 * the host's field, which is found by its value.
 */
public final class AccountCountry {
    private static final String SERVICE_MANAGER = "com.ss.android.ugc.aweme.framework.services.ServiceManager";
    private static final String RETROFIT_SERVICE = "com.ss.android.ugc.aweme.framework.services.IRetrofitService";
    private static final String RETROFIT = "com.ss.android.ugc.aweme.framework.services.IRetrofit";
    private static final String API = "com.ss.android.ugc.aweme.app.api.Api";
    private static final String NETWORK_API = "com.bytedance.ttnet.INetworkApi";
    private static final String TYPED_INPUT = "com.bytedance.retrofit2.mime.TypedInput";
    static final String PATH = "/aweme/v1/user/profile/other/";
    /** The account's own videos, newest first, as its profile grid asks for them. */
    static final String POSTS_PATH = "/aweme/v1/aweme/post/";
    /** How many of the newest videos are read for where they were posted from. */
    static final int POSTS = 6;
    /**
     * The reply size TikTok's own raw GETs allow. A profile reply is under 10 KB and the post
     * list about 100 KB a video (496 KB for five on 2026-11-14).
     */
    private static final int MAX_BODY = 5 * 1024 * 1024;

    private static volatile Object cachedApi;
    private static volatile String cachedHost;

    private AccountCountry() {}

    /**
     * FOUND is the account's own country from its profile record, POSTED the country most of its
     * newest videos were posted from, the same one the feed shows next to a creator's name.
     */
    public enum Outcome { FOUND, POSTED, NONE, FAILED }

    /** What the lookup came back with: a two letter country code when it found one. */
    public static final class Answer {
        public final Outcome outcome;
        public final String code;

        Answer(Outcome outcome, String code) {
            this.outcome = outcome;
            this.code = code;
        }
    }

    public interface Callback {
        void onAnswer(Answer answer);
    }

    /** Whether a profile's share sheet gets the Account country button. */
    public static boolean onProfiles() {
        return SettingsStatus.copyIdsEnabled && Settings.COUNTRY_ON_PROFILES.get();
    }

    /** From the profile share sheet's button: looks the account up and toasts what came back. */
    public static void tell(String uid, String secUid) {
        lookup(uid, secUid, answer -> Utils.showToastLong(answered(uid, answer)));
    }

    static Answer failed() {
        return new Answer(Outcome.FAILED, null);
    }

    /**
     * A lookup's answer as the line its toast shows. A country found is remembered for the
     * account first, so Show where commenters are from draws it on that account's comments too.
     */
    static String answered(String uid, Answer answer) {
        if (answer.outcome == Outcome.FOUND || answer.outcome == Outcome.POSTED) {
            AuthorRegion.rememberSeen(uid, null, answer.code);
            CommenterDetails.onSettingChanged();
            return L10n.f(answer.outcome == Outcome.FOUND ? "Account based in %1$s" : "Videos posted from %1$s",
                    countryName(answer.code));
        }
        if (answer.outcome == Outcome.NONE) return L10n.t("TikTok gives no country for this account");
        return L10n.t("Couldn't get the country. Try again.");
    }

    /** The country's name in the language Hushfeed shows, or the code when there's none. */
    static String countryName(String code) {
        Context context = Utils.getContext();
        Locale locale = context == null ? Locale.getDefault() : L10n.shownLocale(context);
        String name = new Locale("", code).getDisplayCountry(locale);
        return name == null || name.isEmpty() || name.equalsIgnoreCase(code) ? code : name;
    }

    /** Looks up the account on a background thread and answers on the main thread. */
    public static void lookup(String uid, String secUid, Callback callback) {
        try {
            Utils.submitOnBackgroundThread(() -> {
                Answer answer = failed();
                try {
                    answer = find(uid, secUid);
                } catch (UnsupportedOperationException missing) {
                    Logger.printInfo(() -> "Account country lookup unavailable: " + missing.getMessage());
                } catch (Throwable failure) {
                    Logger.printException(() -> "Account country lookup failed", failure);
                }
                Answer done = answer;
                Utils.runOnMainThread(() -> callback.onAnswer(done));
                return null;
            });
        } catch (RejectedExecutionException failure) {
            Logger.printException(() -> "Account country lookup could not be queued", failure);
            Utils.runOnMainThread(() -> callback.onAnswer(failed()));
        }
    }

    /**
     * The profile record first. TikTok often leaves another account's country out of it, so
     * then the account's newest videos say where they were posted from.
     */
    private static Answer find(String uid, String secUid) throws Exception {
        Answer account = parse(fetch(PATH, query(uid, secUid)));
        if (account.outcome == Outcome.FOUND) return account;
        Map<String, String> posts = query(uid, secUid);
        posts.put("max_cursor", "0");
        posts.put("count", String.valueOf(POSTS));
        return either(account, parsePosts(fetch(POSTS_PATH, posts), uid));
    }

    /** The profile's country, else the videos' one; no country when either reply had none. */
    static Answer either(Answer account, Answer posted) {
        if (account.outcome == Outcome.FOUND) return account;
        if (posted.outcome == Outcome.POSTED) return posted;
        if (account.outcome == Outcome.NONE || posted.outcome == Outcome.NONE) return new Answer(Outcome.NONE, null);
        return failed();
    }

    /**
     * Where an account's newest videos were posted from: the country most of them carry, the
     * newest winning a tie. Videos by anyone else are skipped. No videos, as a private
     * account's list comes back, is no country; a refused reply is a failure.
     */
    static Answer parsePosts(String body, String uid) {
        if (body == null || body.isEmpty()) return failed();
        try {
            JSONObject root = new JSONObject(body);
            int status = root.optInt("status_code", 0);
            if (status != 0) {
                Logger.printInfo(() -> "Account country posts refused with status " + status);
                return failed();
            }
            JSONArray items = root.optJSONArray("aweme_list");
            Map<String, Integer> counts = new LinkedHashMap<>();
            String best = null;
            int bestCount = 0;
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                JSONObject author = item.optJSONObject("author");
                String by = author == null ? null : author.optString("uid", null);
                if (uid != null && by != null && !by.isEmpty() && !uid.equals(by)) continue;
                String code = twoLetters(item.optString("region", null));
                if (code == null) continue;
                Integer before = counts.get(code);
                int count = before == null ? 1 : before + 1;
                counts.put(code, count);
                if (count > bestCount) {
                    best = code;
                    bestCount = count;
                }
            }
            int seen = items == null ? 0 : items.length();
            Logger.printDebug(() -> "Account country posts: " + seen + " videos, countries " + counts);
            return best == null ? new Answer(Outcome.NONE, null) : new Answer(Outcome.POSTED, best);
        } catch (Exception unreadable) {
            Logger.printInfo(() -> "Account country posts were not readable: " + unreadable.getMessage());
            return failed();
        }
    }

    /**
     * The account's country from a profile reply: the user record's region, else its account
     * region. A reply TikTok refused, or one without a user record, is a failure; a record with
     * neither field is an account TikTok gives no country for.
     */
    static Answer parse(String body) {
        if (body == null || body.isEmpty()) return new Answer(Outcome.FAILED, null);
        try {
            JSONObject root = new JSONObject(body);
            int status = root.optInt("status_code", 0);
            JSONObject user = root.optJSONObject("user");
            if (status != 0 || user == null) {
                Logger.printInfo(() -> "Account country reply refused with status " + status);
                return new Answer(Outcome.FAILED, null);
            }
            Logger.printDebug(() -> "Account country reply: region=" + user.optString("region")
                    + " account_region=" + user.optString("account_region"));
            for (String key : new String[]{"region", "account_region"}) {
                String code = twoLetters(user.optString(key, null));
                if (code != null) return new Answer(Outcome.FOUND, code);
            }
            return new Answer(Outcome.NONE, null);
        } catch (Exception unreadable) {
            Logger.printInfo(() -> "Account country reply was not readable: " + unreadable.getMessage());
            return new Answer(Outcome.FAILED, null);
        }
    }

    /** A two letter country code in upper case, or null for anything else. */
    static String twoLetters(String raw) {
        return AuthorRegion.countryCode(raw);
    }

    /** Whether {@code value} is a bare https origin, the way {@code Api} keeps its host. */
    static boolean isHost(String value) {
        return value != null && value.startsWith("https://") && value.length() > 8
                && value.indexOf('/', 8) < 0;
    }

    // ---- the request ------------------------------------------------------------------

    private static Map<String, String> query(String uid, String secUid) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("user_id", uid == null ? "" : uid);
        if (secUid != null && !secUid.isEmpty()) query.put("sec_user_id", secUid);
        return query;
    }

    private static String fetch(String path, Map<String, String> query) throws Exception {
        Object api = api();
        Class<?> networkApi = Class.forName(NETWORK_API);
        Method get = networkApi.getMethod("doGetBytes", boolean.class, int.class, String.class,
                Map.class, List.class, Object.class);
        // True asks TikTok's client to add its usual device parameters, as its own raw GETs do.
        Object call = get.invoke(api, true, MAX_BODY, cachedHost + path, query, new ArrayList<>(), null);
        if (call == null) throw new UnsupportedOperationException("no call from INetworkApi");
        Method execute = call.getClass().getMethod("execute");
        execute.setAccessible(true);
        Object response = execute.invoke(call);
        Object body = bodyOf(response);
        if (body == null) throw new UnsupportedOperationException("no reply body");
        Method in = Class.forName(TYPED_INPUT).getMethod("in");
        try (InputStream stream = (InputStream) in.invoke(body)) {
            return read(stream);
        }
    }

    /**
     * The reply's body. The response keeps it in its one generic field, declared Object once
     * erased, next to an error body declared as the input type itself, so only the field
     * declared Object counts.
     */
    private static Object bodyOf(Object response) throws Exception {
        if (response == null) return null;
        Class<?> input = Class.forName(TYPED_INPUT);
        for (Class<?> at = response.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != Object.class) continue;
                field.setAccessible(true);
                Object value = field.get(response);
                if (input.isInstance(value)) return value;
            }
        }
        return null;
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = stream.read(buffer)) > 0) {
            out.write(buffer, 0, count);
            if (out.size() > MAX_BODY) throw new UnsupportedOperationException("reply too large");
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** TTNet's raw request API on a client for TikTok's API host, built once. */
    private static Object api() throws Exception {
        Object cached = cachedApi;
        if (cached != null) return cached;
        String host = host();
        Class<?> managerType = Class.forName(SERVICE_MANAGER);
        Object manager = managerType.getMethod("get").invoke(null);
        Class<?> retrofitService = Class.forName(RETROFIT_SERVICE);
        Object factory = managerType.getMethod("getService", Class.class).invoke(manager, retrofitService);
        if (factory == null) throw new UnsupportedOperationException("no IRetrofitService");
        Object retrofit = retrofitService.getMethod("createNewRetrofit", String.class).invoke(factory, host);
        if (retrofit == null) throw new UnsupportedOperationException("no client for " + host);
        Object api = Class.forName(RETROFIT).getMethod("create", Class.class)
                .invoke(retrofit, Class.forName(NETWORK_API));
        if (api == null) throw new UnsupportedOperationException("no INetworkApi");
        cachedHost = host;
        cachedApi = api;
        return api;
    }

    /**
     * TikTok's API host. {@code Api} keeps it as a bare origin next to the image upload URL
     * built from it, so it is the one static string there with no path.
     */
    private static String host() throws Exception {
        String found = null;
        for (Field field : Class.forName(API).getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
            field.setAccessible(true);
            Object value = field.get(null);
            if (!(value instanceof String) || !isHost((String) value)) continue;
            if (found != null && !found.equals(value)) throw new UnsupportedOperationException("two API hosts");
            found = (String) value;
        }
        if (found == null) throw new UnsupportedOperationException("no API host");
        return found;
    }
}

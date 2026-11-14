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
 * <p>A comment's account often comes without its country, but the profile reply TikTok's own
 * assistant card reads, {@code /aweme/v1/user/profile/other/}, carries the account's full user
 * record, region included, private accounts too. The request goes out through TikTok's own
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
    /** The reply size TikTok's own raw GETs allow. A profile reply is a few dozen kilobytes. */
    private static final int MAX_BODY = 5 * 1024 * 1024;

    private static volatile Object cachedApi;
    private static volatile String cachedHost;

    private AccountCountry() {}

    public enum Outcome { FOUND, NONE, FAILED }

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
        if (answer.outcome == Outcome.FOUND) {
            AuthorRegion.rememberSeen(uid, null, answer.code);
            CommenterDetails.onSettingChanged();
            return L10n.f("Account based in %1$s", countryName(answer.code));
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
                    answer = parse(fetch(uid, secUid));
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
        String code = AuthorRegion.regionCode(raw);
        if (code == null || code.length() != 2) return null;
        for (int i = 0; i < 2; i++) {
            char c = code.charAt(i);
            if (c < 'A' || c > 'Z') return null;
        }
        return code;
    }

    /** Whether {@code value} is a bare https origin, the way {@code Api} keeps its host. */
    static boolean isHost(String value) {
        return value != null && value.startsWith("https://") && value.length() > 8
                && value.indexOf('/', 8) < 0;
    }

    // ---- the request ------------------------------------------------------------------

    private static String fetch(String uid, String secUid) throws Exception {
        Object api = api();
        Class<?> networkApi = Class.forName(NETWORK_API);
        Method get = networkApi.getMethod("doGetBytes", boolean.class, int.class, String.class,
                Map.class, List.class, Object.class);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("user_id", uid == null ? "" : uid);
        if (secUid != null && !secUid.isEmpty()) query.put("sec_user_id", secUid);
        // True asks TikTok's client to add its usual device parameters, as its own raw GETs do.
        Object call = get.invoke(api, true, MAX_BODY, cachedHost + PATH, query, new ArrayList<>(), null);
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

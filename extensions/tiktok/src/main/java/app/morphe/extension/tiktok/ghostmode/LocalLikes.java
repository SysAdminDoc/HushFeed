/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.ghostmode;

import android.content.Context;
import android.content.SharedPreferences;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.follow.FollowDiagnostics;
import app.morphe.extension.tiktok.seen.LocalWatchHistory;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keep likes on this phone. While Ghost mode and its Keep likes on this phone switch are on, a
 * like you give a video is answered in TikTok's network layer, right where it would be sent, the
 * way TikTok's server answers one it took. The heart turns red and nothing goes out, so the
 * creator isn't told and the video stays off your profile's Liked tab. The video is kept on the
 * phone for the signed-in account, and TikTok's own read of a video's like
 * ({@code Aweme.getUserDigg}) answers liked for it from then on, so the heart is still red when
 * TikTok loads the video again.
 *
 * <p>Unliking a kept video takes it off the phone, and that unlike is answered here too. Any
 * other unlike goes out, since TikTok has that like. So does a like while the switch is off,
 * which takes the video off the phone, since TikTok has its like now.
 *
 * <p>The hook fails open: with a switch off, Hushfeed paused, or a request or a response this
 * can't make sense of, the request goes out as it always does.
 */
public final class LocalLikes {
    public static final String PREFS_NAME = "hushfeed_local_likes";
    /** TikTok's video like and unlike. Comments and stories' own likes use other paths. */
    static final String LIKE_PATH = "/aweme/v1/commit/item/digg/";
    /** What TikTok's server answers a like it took. */
    static final String ANSWER = "{\"status_code\":0,\"status_msg\":\"\"}";
    static final String ANSWER_TYPE = "application/json; charset=utf-8";
    static final String TYPED_BYTE_ARRAY = "com.bytedance.retrofit2.mime.TypedByteArray";
    static final int MAX_PER_ACCOUNT = 5_000;
    /** How many liked videos' creator and caption are held until their like is answered. */
    private static final int DETAILS_KEPT = 64;
    private static final String HOOK_FAMILY = LiveUnseen.HOOK_FAMILY;

    /** Account, a slash and the video's id, mapped to the kept like, oldest first. */
    private static final Map<String, Like> liked = new LinkedHashMap<>();
    /** Every kept video's id, for any account, so the feed's reads skip the rest at once. */
    private static final Set<String> ids = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** Kept likes TikTok was told were taken back, whose unlike it is about to send. */
    private static final Set<String> unliking = Collections.newSetFromMap(new ConcurrentHashMap<>());
    /** The creator and caption of videos just liked, by id, for the list. */
    private static final Map<String, String[]> details = new LinkedHashMap<String, String[]>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String[]> eldest) {
            return size() > DETAILS_KEPT;
        }
    };
    private static volatile boolean loaded;
    /** TikTok's raw response and the body it carries, found once. */
    private static volatile Constructor<?>[] makers;
    /** So a test can set the clock. */
    static volatile Long nowForTests;

    /** One kept like. */
    static final class Like {
        final String aid;
        final String creator;
        final String caption;
        final long likedMs;

        Like(String aid, String creator, String caption, long likedMs) {
            this.aid = aid;
            this.creator = creator == null ? "" : creator;
            this.caption = caption == null ? "" : caption;
            this.likedMs = likedMs;
        }
    }

    private LocalLikes() {
    }

    /** Whether the switch is on, read at each like, so flipping it needs no restart. */
    public static boolean enabled() {
        try {
            return Utils.getContext() != null && SettingsStatus.ghostModeEnabled
                    && Settings.GHOST_MODE.get() && Settings.GHOST_LOCAL_LIKES.get();
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /**
     * Called by TikTok's network interceptor right before it sends a request, with itself and its
     * chain. Returns TikTok's raw response for a like or unlike kept on this phone, and null for
     * everything else, which goes out as usual. Never throws.
     */
    public static Object answer(Object interceptor, Object chain) {
        try {
            boolean on = enabled();
            if (!on) {
                // Off, a like or unlike only matters here for a video kept from before.
                load();
                if (ids.isEmpty()) return null;
            }
            Object request = Reflect.requiredField(interceptor, "mOriginalRequest", HOOK_FAMILY);
            Object path = Reflect.required(request, "getPath", HOOK_FAMILY);
            if (!isLike(path instanceof String ? (String) path : null)) return null;
            String aid = FollowDiagnostics.requestValue(request, "aweme_id");
            String type = FollowDiagnostics.requestValue(request, "type");
            String account = SignedInUser.id();
            if (aid == null || account == null || !("1".equals(type) || "0".equals(type))) {
                if (on) HookStatus.bound(HOOK_FAMILY, "like not read");
                return null;
            }
            load();
            String key = key(account, aid);
            if ("1".equals(type)) {
                Object response = GhostMode.keepLike(on) ? response(interceptor, request) : null;
                // The like goes out when there's no answer to give it.
                if (response == null) {
                    // TikTok gets this like, so it isn't one to keep here any more.
                    unliking.remove(key);
                    forget(key);
                    return null;
                }
                unliking.remove(key);
                keep(account, aid);
                return response;
            }
            boolean kept = unliking.contains(key) || isKept(key);
            if (!kept) return null;
            Object response = on ? response(interceptor, request) : null;
            unliking.remove(key);
            forget(key);
            // Off, or with no answer to give, the unlike goes out. TikTok never had the like, so
            // it changes nothing there.
            return response;
        } catch (Throwable unreadable) {
            HookStatus.threw(HOOK_FAMILY, GhostMode.LOCAL_LIKE, unreadable);
            return null;
        }
    }

    /**
     * Called by Aweme.getUserDigg with the video and what TikTok holds for it. Answers liked for
     * a video kept on this phone while the switch is on, and what TikTok holds otherwise. Runs
     * for every video the feed draws, so it leaves at the first answer it can give.
     */
    public static int userDigg(Object aweme, int value) {
        if (value != 0) return value;
        try {
            if (!loaded) {
                if (!enabled()) return value;
                load();
            }
            if (ids.isEmpty() || !(aweme instanceof Aweme)) return value;
            String aid = ((Aweme) aweme).getAid();
            if (aid == null || !ids.contains(aid) || !enabled()) return value;
            String account = SignedInUser.id();
            return account != null && isKept(key(account, aid)) ? 1 : value;
        } catch (Throwable unreadable) {
            return value;
        }
    }

    /**
     * Called by Aweme.setUserDigg with the video and the value TikTok sets, before it's set. A
     * like keeps the video's creator and caption for the list. Taking a kept like back takes the
     * video off the phone at once, so the heart doesn't come back red while the unlike waits to
     * go out. Never throws.
     */
    public static void setUserDigg(Object aweme, int value) {
        try {
            if (!(aweme instanceof Aweme) || !enabled()) return;
            String aid = ((Aweme) aweme).getAid();
            if (aid == null || aid.isEmpty()) return;
            if (value != 0) {
                noteDetails(aid, aweme);
                return;
            }
            load();
            if (!ids.contains(aid)) return;
            String account = SignedInUser.id();
            if (account == null) return;
            String key = key(account, aid);
            if (forget(key)) unliking.add(key);
        } catch (Throwable unreadable) {
            HookStatus.threw(HOOK_FAMILY, GhostMode.LOCAL_LIKE, unreadable);
        }
    }

    /** The signed-in account's kept likes, newest first, for the list. */
    public static void load(LocalWatchHistory.Listener listener) {
        List<LocalWatchHistory.Entry> rows = entries();
        Utils.runOnMainThread(() -> listener.onRows(Collections.unmodifiableList(rows)));
    }

    /** Takes back every like the signed-in account kept here, and hands them over for an undo. */
    public static void clear(LocalWatchHistory.Listener listener) {
        String account = SignedInUser.id();
        List<LocalWatchHistory.Entry> rows = entries();
        if (account != null) {
            for (LocalWatchHistory.Entry row : rows) forget(key(account, row.aid));
        }
        Utils.runOnMainThread(() -> listener.onRows(Collections.unmodifiableList(rows)));
    }

    /** Puts cleared likes back. */
    public static void restore(List<LocalWatchHistory.Entry> rows) {
        String account = SignedInUser.id();
        if (account == null || rows == null) return;
        load();
        List<LocalWatchHistory.Entry> oldestFirst = new ArrayList<>(rows);
        Collections.reverse(oldestFirst);
        for (LocalWatchHistory.Entry row : oldestFirst) {
            String key = key(account, row.aid);
            if (isKept(key)) continue;
            save(key, new Like(row.aid, row.creator, row.caption, row.watchedMs));
        }
    }

    /** The signed-in account's kept likes, newest first. */
    static List<LocalWatchHistory.Entry> entries() {
        String account = SignedInUser.id();
        List<LocalWatchHistory.Entry> rows = new ArrayList<>();
        if (account == null) return rows;
        load();
        String prefix = account + "/";
        synchronized (liked) {
            for (Map.Entry<String, Like> kept : liked.entrySet()) {
                if (!kept.getKey().startsWith(prefix)) continue;
                Like like = kept.getValue();
                rows.add(new LocalWatchHistory.Entry(like.aid, like.creator, like.caption, like.likedMs));
            }
        }
        Collections.reverse(rows);
        return rows;
    }

    static boolean isLike(String path) {
        return path != null && path.endsWith(LIKE_PATH);
    }

    /**
     * TikTok's raw response for a like its server took: status 200 and the JSON body the server
     * sends, built from the types TikTok's own send returns, for the request's own URL.
     */
    private static Object response(Object interceptor, Object request) {
        try {
            Constructor<?>[] found = makers(interceptor.getClass());
            Object url = Reflect.invoke(request, "getUrl");
            Object body = found[1].newInstance(ANSWER_TYPE, ANSWER.getBytes(StandardCharsets.UTF_8), new String[0]);
            return found[0].newInstance(url instanceof String ? url : LIKE_PATH, 200, "OK", new ArrayList<>(), body);
        } catch (Throwable unbuilt) {
            HookStatus.threw(HOOK_FAMILY, GhostMode.LOCAL_LIKE, unbuilt);
            return null;
        }
    }

    private static Constructor<?>[] makers(Class<?> interceptorType) throws Exception {
        Constructor<?>[] found = makers;
        if (found != null) return found;
        Method send = LiveUnseen.twin(interceptorType);
        if (send == null) throw new NoSuchMethodException(LiveUnseen.TWIN);
        Class<?> bodyType = Class.forName(TYPED_BYTE_ARRAY, false, interceptorType.getClassLoader());
        Constructor<?> response = null;
        for (Constructor<?> candidate : send.getReturnType().getDeclaredConstructors()) {
            Class<?>[] types = candidate.getParameterTypes();
            if (types.length == 5 && types[0] == String.class && types[1] == int.class
                    && types[2] == String.class && types[3] == List.class && types[4].isAssignableFrom(bodyType)) {
                response = candidate;
            }
        }
        if (response == null) {
            throw new NoSuchMethodException(send.getReturnType().getName() + "(String, int, String, List, body)");
        }
        response.setAccessible(true);
        Constructor<?> body = bodyType.getConstructor(String.class, byte[].class, String[].class);
        found = new Constructor<?>[]{response, body};
        makers = found;
        return found;
    }

    private static void noteDetails(String aid, Object aweme) {
        Object user = Reflect.property(aweme, "getAuthor", "author");
        String creator = user == null ? null : Reflect.firstNonBlank(
                Reflect.string(user, "getNickname", "nickname"),
                Reflect.string(user, "getUniqueId", "uniqueId"));
        String caption = LocalWatchHistory.shorten(Reflect.string(aweme, "getDesc", "desc"));
        synchronized (details) {
            details.put(aid, new String[]{creator == null ? "" : creator, caption});
        }
    }

    private static void keep(String account, String aid) {
        String[] known;
        synchronized (details) {
            known = details.get(aid);
        }
        String key = key(account, aid);
        save(key, new Like(aid, known == null ? "" : known[0], known == null ? "" : known[1], now()));
        trim(account);
        Logger.printInfo(() -> "Kept a like on this phone");
    }

    private static void save(String key, Like like) {
        synchronized (liked) {
            liked.remove(key);
            liked.put(key, like);
        }
        ids.add(like.aid);
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putString(key, encode(like)).apply();
    }

    /** Takes a video off the phone. True when it was kept. */
    private static boolean forget(String key) {
        Like gone;
        synchronized (liked) {
            gone = liked.remove(key);
            if (gone != null) {
                String tail = "/" + gone.aid;
                boolean other = false;
                for (String left : liked.keySet()) {
                    if (left.endsWith(tail)) {
                        other = true;
                        break;
                    }
                }
                if (!other) ids.remove(gone.aid);
            }
        }
        if (gone == null) return false;
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().remove(key).apply();
        return true;
    }

    private static boolean isKept(String key) {
        synchronized (liked) {
            return liked.containsKey(key);
        }
    }

    /** Drops the account's oldest kept likes past the cap. */
    private static void trim(String account) {
        String prefix = account + "/";
        List<String> mine = new ArrayList<>();
        synchronized (liked) {
            for (String key : liked.keySet()) {
                if (key.startsWith(prefix)) mine.add(key);
            }
        }
        for (int extra = mine.size() - MAX_PER_ACCOUNT, i = 0; i < extra; i++) forget(mine.get(i));
    }

    /** Reads the saved likes once a process, oldest first. */
    private static void load() {
        if (loaded) return;
        synchronized (liked) {
            if (loaded) return;
            SharedPreferences preferences = preferences();
            if (preferences == null) return;
            List<Map.Entry<String, Like>> read = new ArrayList<>();
            for (Map.Entry<String, ?> saved : preferences.getAll().entrySet()) {
                Like like = saved.getValue() instanceof String ? decode((String) saved.getValue()) : null;
                if (like != null) read.add(new AbstractMap.SimpleEntry<>(saved.getKey(), like));
            }
            read.sort((a, b) -> Long.compare(a.getValue().likedMs, b.getValue().likedMs));
            for (Map.Entry<String, Like> entry : read) {
                liked.put(entry.getKey(), entry.getValue());
                ids.add(entry.getValue().aid);
            }
            loaded = true;
        }
    }

    private static String encode(Like like) {
        try {
            return new JSONObject().put("aid", like.aid).put("creator", like.creator)
                    .put("caption", like.caption).put("liked", like.likedMs).toString();
        } catch (Exception unwritable) {
            return "{\"aid\":\"" + like.aid + "\"}";
        }
    }

    private static Like decode(String saved) {
        try {
            JSONObject read = new JSONObject(saved);
            String aid = read.optString("aid", "");
            if (aid.isEmpty()) return null;
            return new Like(aid, read.optString("creator", ""), read.optString("caption", ""),
                    read.optLong("liked", 0L));
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String key(String account, String aid) {
        return account + "/" + aid;
    }

    private static long now() {
        Long forTests = nowForTests;
        return forTests != null ? forTests : System.currentTimeMillis();
    }

    /** Forgets what this process read, keeping the saved file, as a restart does. For tests. */
    static void forgetLoadedForTests() {
        synchronized (liked) {
            liked.clear();
            ids.clear();
            unliking.clear();
            loaded = false;
        }
        synchronized (details) {
            details.clear();
        }
    }

    /** Forgets everything, for tests. */
    static void clearForTests() {
        forgetLoadedForTests();
        makers = null;
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().clear().commit();
    }
}

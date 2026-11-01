/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import android.content.Context;
import android.content.SharedPreferences;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.L10n;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Recently used on the basic comment sticker panel (#156).
 *
 * <p>TikTok's full sticker panel lives in an on-demand module that a patched install can't
 * download, so the app falls back to its basic panel, whose Saved page has no Recently used
 * row. The store behind that row still fills as stickers are sent, so {@link #addRow} puts its
 * newest stickers above Saved each time the page draws its rows.
 *
 * <p>The app keeps that list in memory only, and its sticker store empties it the first time the
 * store is used in each process, since it takes the account it finds as a new one. So every
 * restart, the ones Hushfeed's settings ask for included, leaves the list empty. The patch hands
 * {@link #save} each new list as a sticker goes on it, and {@link #restore} the list's flow
 * right after the store empties it, which puts back what this account had.
 *
 * <p>The stickers are written with the app's own Gson, one list per account, and read back only
 * on the app version that wrote them, since the classes inside a sticker are renamed between
 * builds. A sticker whose image links have all expired is left out when the list comes back.
 */
@SuppressWarnings("unused")
public final class StickerRecents {
    public static final String PREFS_NAME = "hushfeed_sticker_recents";
    static final String VERSION_KEY = "app_version";
    /** More than the store ever holds (12, or the server's page size when that's larger). */
    static final int MOST = 50;
    /** How many recent stickers the row shows: one line of the Saved grid, as on TikTok's panel. */
    static final int ROW = 4;
    private static final String GSON = "com.google.gson.Gson";
    private static final Pattern EXPIRES = Pattern.compile("x-expires=(\\d{1,12})");
    /** How Gson writes '=' in a string, since it escapes it for HTML by default. */
    private static final String ESCAPED_EQUALS = "\\" + "u003d";

    /** Turns a sticker into text and back. */
    interface Codec {
        String write(Object item) throws Exception;

        Object read(String text, Class<?> type) throws Exception;
    }

    /** So a test can stand in for the app's Gson, which tests don't have. */
    static volatile Codec codecForTests;
    /** So a test can change the app version the lists are tied to. */
    static volatile String versionForTests;

    private static volatile Codec codec;
    private static volatile boolean noCodec;
    private static volatile Method setValue;
    private static volatile Class<?> setValueOwner;
    /** How many rows {@link #addRow} put above the Saved heading the last time a Saved page drew. */
    private static volatile int rowsAbove;

    private StickerRecents() {
    }

    /**
     * Called with the Saved page and its rows (the Saved heading, then the saved stickers) right
     * before the page draws them. Puts a Recently used heading and the newest stickers on top.
     * Never throws.
     */
    public static void addRow(Object page, Object rows) {
        int added = 0;
        try {
            if (HushfeedPause.isPaused() || !(rows instanceof List)) return;
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) rows;
            added = prepend(list, recentStickers(page), BRIDGES, L10n.t("Recently used"));
        } catch (Throwable ignored) {
            // The page shows Saved alone, as it would without the patch.
        } finally {
            rowsAbove = added;
        }
    }

    /** Makes the row's cells: the page's own heading and sticker cells in the app, fakes in tests. */
    interface RowCells {
        Object item(Object sticker);

        Object title(String heading);
    }

    private static final RowCells BRIDGES = new RowCells() {
        @Override
        public Object item(Object sticker) {
            return recentItem(sticker);
        }

        @Override
        public Object title(String heading) {
            return recentTitle(heading);
        }
    };

    /**
     * Puts the heading and a cell for each of the ROW newest stickers on top of the rows, and
     * answers how many went on. Leaves the rows as they were when a cell can't be made.
     */
    static int prepend(List<Object> rows, Object recents, RowCells cells, String heading) {
        if (!(recents instanceof List)) return 0;
        List<Object> row = new ArrayList<>();
        for (Object sticker : (List<?>) recents) {
            if (row.size() == ROW) break;
            if (sticker == null) continue;
            Object item = cells.item(sticker);
            if (item == null) return 0;
            row.add(item);
        }
        if (row.isEmpty()) return 0;
        Object title = cells.title(heading);
        if (title == null) return 0;
        row.add(0, title);
        rows.addAll(0, row);
        return row.size();
    }

    /** The rows above the Saved heading, so Create can stay level with that heading. */
    static int rowsAbove() {
        return rowsAbove;
    }

    /** So a test can stand in for a Saved page that drew Recently used. */
    static void rowsAboveForTests(int rows) {
        rowsAbove = rows;
    }

    /** Called with the row's new list each time a sticker goes on it. Never throws. */
    public static void save(Object list) {
        try {
            if (HushfeedPause.isPaused() || !(list instanceof List) || !Utils.isMainProcess()) return;
            Context context = Utils.getContext();
            String self = SignedInUser.id();
            Codec found = codec();
            if (context == null || self == null || found == null) return;
            String saved = encode((List<?>) list, found);
            if (saved != null) write(context, self, saved);
        } catch (Throwable ignored) {
            // A list that can't be kept stays in memory, as it would without the patch.
        }
    }

    /** Called with the row's flow right after the store empties it for the signed-in account. Never throws. */
    public static void restore(Object flow) {
        try {
            if (HushfeedPause.isPaused() || flow == null) return;
            Context context = Utils.getContext();
            String self = SignedInUser.id();
            Codec found = codec();
            if (context == null || self == null || found == null) return;
            List<Object> items = read(context, self, found, System.currentTimeMillis());
            if (items.isEmpty()) return;
            setter(flow.getClass()).invoke(flow, Collections.unmodifiableList(items));
        } catch (Throwable ignored) {
            // The row stays empty, as it would without the patch.
        }
    }

    /** The list as stored: each sticker's class and Gson text. Null when no sticker could be written. */
    static String encode(List<?> list, Codec found) throws Exception {
        JSONArray items = new JSONArray();
        for (Object item : list) {
            if (item == null) continue;
            if (items.length() >= MOST) break;
            String text;
            try {
                text = found.write(item);
            } catch (Throwable unwritable) {
                // A sticker Gson can't write is left out; the rest are still kept.
                continue;
            }
            if (text == null) continue;
            items.put(new JSONObject().put("type", item.getClass().getName()).put("json", text));
        }
        return items.length() == 0 && !list.isEmpty() ? null : items.toString();
    }

    /** The stickers kept for this account on this app version, without the expired ones. */
    static List<Object> decode(String saved, Codec found, ClassLoader loader, long now) throws Exception {
        List<Object> items = new ArrayList<>();
        JSONArray stored = new JSONArray(saved);
        for (int index = 0; index < stored.length() && items.size() < MOST; index++) {
            JSONObject entry = stored.optJSONObject(index);
            if (entry == null) continue;
            String text = entry.optString("json", "");
            if (text.isEmpty() || expired(text, now)) continue;
            try {
                Object item = found.read(text, Class.forName(entry.optString("type"), false, loader));
                if (item != null) items.add(item);
            } catch (Throwable unreadable) {
                // A sticker this build can't read back is dropped.
            }
        }
        return items;
    }

    /** True when the sticker has image links with an expiry and every one of them has passed. */
    static boolean expired(String text, long now) {
        Matcher matcher = EXPIRES.matcher(text.replace(ESCAPED_EQUALS, "="));
        long latest = 0;
        while (matcher.find()) latest = Math.max(latest, Long.parseLong(matcher.group(1)));
        return latest > 0 && latest * 1000L < now;
    }

    private static void write(Context context, String self, String saved) {
        SharedPreferences preferences = preferences(context);
        String version = appVersion();
        boolean sameVersion = version.equals(preferences.getString(VERSION_KEY, null));
        if (sameVersion && saved.equals(preferences.getString(self, null))) return;
        SharedPreferences.Editor editor = preferences.edit();
        // Lists from another app version can't be read back, so they go as soon as this one writes.
        if (!sameVersion) editor.clear().putString(VERSION_KEY, version);
        editor.putString(self, saved).apply();
    }

    private static List<Object> read(Context context, String self, Codec found, long now) throws Exception {
        SharedPreferences preferences = preferences(context);
        if (!appVersion().equals(preferences.getString(VERSION_KEY, null))) return new ArrayList<>();
        String saved = preferences.getString(self, null);
        if (saved == null) return new ArrayList<>();
        return decode(saved, found, StickerRecents.class.getClassLoader(), now);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String appVersion() {
        String forTests = versionForTests;
        if (forTests != null) return forTests;
        String name = Utils.getAppVersionName();
        return name == null ? "" : name;
    }

    private static Codec codec() {
        Codec forTests = codecForTests;
        if (forTests != null) return forTests;
        Codec found = codec;
        if (found != null || noCodec) return found;
        try {
            found = gson(Class.forName(GSON));
        } catch (Throwable missing) {
            found = null;
        }
        if (found == null) noCodec = true;
        codec = found;
        return found;
    }

    /**
     * The app's Gson, found by shape since R8 renames its methods: the one public method taking an
     * Object and returning a String is toJson, the one taking a String and a Class is fromJson.
     * Null when either shape isn't there exactly once.
     */
    static Codec gson(Class<?> type) throws ReflectiveOperationException {
        Method toJson = null;
        Method fromJson = null;
        int writers = 0;
        int readers = 0;
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() == Object.class) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getReturnType() == String.class && parameters.length == 1 && parameters[0] == Object.class) {
                toJson = method;
                writers++;
            } else if (method.getReturnType() == Object.class && parameters.length == 2
                    && parameters[0] == String.class && parameters[1] == Class.class) {
                fromJson = method;
                readers++;
            }
        }
        if (writers != 1 || readers != 1) return null;
        Object gson = type.getConstructor().newInstance();
        Method writer = toJson;
        Method reader = fromJson;
        return new Codec() {
            @Override
            public String write(Object item) throws Exception {
                return (String) writer.invoke(gson, item);
            }

            @Override
            public Object read(String text, Class<?> itemType) throws Exception {
                return reader.invoke(gson, text, itemType);
            }
        };
    }

    private static Method setter(Class<?> owner) throws NoSuchMethodException {
        Method found = setValue;
        if (found == null || setValueOwner != owner) {
            found = owner.getMethod("setValue", Object.class);
            setValue = found;
            setValueOwner = owner;
        }
        return found;
    }

    /** So a test starts without what an earlier one found. */
    static void resetForTests() {
        codec = null;
        noCodec = false;
        setValue = null;
        setValueOwner = null;
        rowsAbove = 0;
    }

    // The bridges below are stubs. The patch writes each body from the Saved page the app
    // draws, and an unpatched build answers with nothing.

    /** The page's sticker store's Recently used list, newest first. */
    static Object recentStickers(Object page) {
        return null;
    }

    /** A grid heading, like the page's own Saved heading. */
    static Object recentTitle(String title) {
        return null;
    }

    /** A grid cell for a sticker, like the page's own saved stickers. */
    static Object recentItem(Object sticker) {
        return null;
    }
}

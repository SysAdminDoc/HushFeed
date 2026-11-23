/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.content.Context;
import android.content.SharedPreferences;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Where a TikTok Lite video was posted from (#502). Lite's feed JSON carries each video's
 * {@code region}, but Lite's Aweme has no field for it, so Gson skips the value. Show author
 * region catches it there: Gson tells this class about every Aweme it makes and where in the JSON
 * it made it, and when Gson is about to skip a {@code region} right inside one of those, this
 * reads the value instead and keeps it for that video.
 *
 * <p>After a restart Lite first shows a few videos from its own cache, which it fetches as it
 * goes to the background and writes from its model, so without the region. Each country is
 * also saved by the video's id, so a cached video gets the one it came with.
 */
public final class LiteVideoRegion {
    /** Countries kept past a restart, by video id. */
    public static final String PREFS_NAME = "hushfeed_lite_regions";

    private static final String SAVED_KEY = "by_id";

    private static final String REGION = ".region";

    /** Places in the JSON remembered per thread. A feed page holds a few dozen videos. */
    private static final int MAX_PLACES = 64;

    /** Videos whose country is kept, newest last. A few feed pages' worth. */
    private static final int MAX_KEPT = 400;

    /** Countries saved by video id. Lite's cache holds a page or two. */
    private static final int MAX_SAVED = 300;

    /** Lookups that may try to read a kept video's id before giving up on it. */
    private static final int ID_TRIES = 8;

    /** Gson reads a page in milliseconds; the save waits this long past the first region kept. */
    private static final long SAVE_DELAY_MS = 1500;

    /**
     * The calls into Gson's JsonReader that the patch writes into the stubs below. Tests stand
     * in a reader of their own here.
     */
    interface ReaderCalls {
        String path(Object reader) throws Exception;

        Object peek(Object reader) throws Exception;

        String nextString(Object reader) throws Exception;
    }

    static ReaderCalls calls = new ReaderCalls() {
        @Override
        public String path(Object reader) {
            return readerPath(reader);
        }

        @Override
        public Object peek(Object reader) {
            return readerPeek(reader);
        }

        @Override
        public String nextString(Object reader) {
            return readerNextString(reader);
        }
    };

    /** This thread's Awemes by the JSON path Gson made each at, like {@code $.aweme_list[3]}. */
    private static final ThreadLocal<Map<String, Object>> MADE = new ThreadLocal<Map<String, Object>>() {
        @Override
        protected Map<String, Object> initialValue() {
            return new LinkedHashMap<String, Object>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                    return size() > MAX_PLACES;
                }
            };
        }
    };

    private static final class Kept {
        final WeakReference<Object> video;
        final String region;
        /** The video's id, read by a later lookup, once Gson is done with the video. */
        String aid;
        int idTries;

        Kept(Object video, String region) {
            this.video = new WeakReference<>(video);
            this.region = region;
        }
    }

    private static final ArrayDeque<Kept> KEPT = new ArrayDeque<>();

    /** Saved countries by video id, least recently used first. Guarded by {@link #KEPT}. */
    private static LinkedHashMap<String, String> saved;

    /** A save is waiting to run. Guarded by {@link #KEPT}. */
    private static boolean savePending;

    private static volatile Class<?> awemeClass;

    private static boolean failureLogged;

    private LiteVideoRegion() {
    }

    private static boolean on() {
        return SettingsStatus.authorRegionEnabled && Settings.SHOW_AUTHOR_REGION.get();
    }

    /** Lite has two Aweme models, the feed's and its own minilite one. */
    static boolean isAweme(Class<?> type) {
        if (type == awemeClass) {
            return true;
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().endsWith(".feed.model.Aweme")) {
                awemeClass = type;
                return true;
            }
        }
        return false;
    }

    /** Gson just made {@code model} for the place {@code reader} is at, before reading into it. */
    public static void made(Object model, Object reader) {
        if (model == null || !on() || !isAweme(model.getClass())) {
            return;
        }
        try {
            String path = calls.path(reader);
            if (path != null) {
                MADE.get().put(path, model);
            }
        } catch (Throwable ex) {
            logOnce("Could not read where Gson made a Lite video", ex);
        }
    }

    /**
     * Called first thing in Gson's skipValue. True when the value about to be skipped was a
     * video's region and this read it instead, so the reader already sits past it and skipValue
     * must not run. Anything else, a JSON null included, is left for skipValue.
     */
    public static boolean skipping(Object reader) {
        if (!on()) {
            return false;
        }
        Map<String, Object> made = MADE.get();
        if (made.isEmpty()) {
            return false;
        }
        Object video;
        try {
            String path = calls.path(reader);
            if (path == null || !path.endsWith(REGION)) {
                return false;
            }
            video = made.get(path.substring(0, path.length() - REGION.length()));
            if (video == null || !isString(calls.peek(reader))) {
                return false;
            }
        } catch (Throwable ex) {
            logOnce("Could not look at the value Gson was skipping", ex);
            return false;
        }
        String region;
        try {
            region = calls.nextString(reader);
        } catch (Throwable ex) {
            // The value was a string and the read failed partway, so the JSON is broken there.
            // skipValue would fail on it the same way.
            logOnce("Could not read a Lite video's region", ex);
            return true;
        }
        keep(video, region);
        return true;
    }

    private static boolean isString(Object token) {
        return token instanceof Enum && "STRING".equals(((Enum<?>) token).name());
    }

    static void keep(Object video, String region) {
        boolean schedule;
        synchronized (KEPT) {
            KEPT.addLast(new Kept(video, region));
            while (KEPT.size() > MAX_KEPT) {
                KEPT.removeFirst();
            }
            schedule = !savePending;
            savePending = true;
        }
        if (schedule) {
            saveLater();
        }
    }

    /**
     * Lite refills its cache right after it goes to the background, where no row asks for a
     * country, so the save can't wait for the next lookup.
     */
    private static void saveLater() {
        try {
            Utils.runOnMainThreadDelayed(() -> {
                synchronized (KEPT) {
                    savePending = false;
                    saveIds();
                }
            }, SAVE_DELAY_MS);
        } catch (Throwable ex) {
            synchronized (KEPT) {
                savePending = false;
            }
            logOnce("Could not save Lite's video countries", ex);
        }
    }

    /**
     * The region Lite's JSON gave this video, as sent, or null. The row's video is usually the
     * object Gson made. A copy of it, or one from Lite's cache after a restart, is matched by its
     * id.
     */
    public static String region(Object video) {
        if (video == null) {
            return null;
        }
        synchronized (KEPT) {
            saveIds();
            for (Iterator<Kept> it = KEPT.descendingIterator(); it.hasNext(); ) {
                Kept kept = it.next();
                if (kept.video.get() == video) {
                    return kept.region;
                }
            }
        }
        String aid = Reflect.string(video, "getAid", "aid");
        if (aid == null) {
            return null;
        }
        synchronized (KEPT) {
            for (Iterator<Kept> it = KEPT.descendingIterator(); it.hasNext(); ) {
                Kept kept = it.next();
                if (aid.equals(kept.aid)) {
                    return kept.region;
                }
            }
            return saved().get(aid);
        }
    }

    /**
     * Reads the id of each kept video Gson is done with and saves its country under it. A
     * video still being read has no id yet and is tried again on a later lookup.
     */
    private static void saveIds() {
        boolean changed = false;
        for (Kept kept : KEPT) {
            if (kept.aid != null || kept.idTries >= ID_TRIES) {
                continue;
            }
            Object made = kept.video.get();
            if (made == null) {
                kept.idTries = ID_TRIES;
                continue;
            }
            kept.idTries++;
            String aid = Reflect.string(made, "getAid", "aid");
            if (aid == null) {
                continue;
            }
            kept.aid = aid;
            if (savable(aid) && savable(kept.region) && !kept.region.equals(saved().put(aid, kept.region))) {
                changed = true;
            }
        }
        if (changed) {
            writeSaved();
        }
    }

    /** Ids and regions are short codes; anything holding the separators isn't saved. */
    private static boolean savable(String text) {
        return text != null && !text.isEmpty() && text.indexOf(';') < 0 && text.indexOf('=') < 0;
    }

    private static Map<String, String> saved() {
        if (saved == null) {
            saved = new LinkedHashMap<String, String>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_SAVED;
                }
            };
            SharedPreferences preferences = preferences();
            String text = preferences == null ? null : preferences.getString(SAVED_KEY, null);
            if (text != null) {
                for (String pair : text.split(";")) {
                    int at = pair.indexOf('=');
                    if (at > 0 && at < pair.length() - 1) {
                        saved.put(pair.substring(0, at), pair.substring(at + 1));
                    }
                }
            }
        }
        return saved;
    }

    private static void writeSaved() {
        SharedPreferences preferences = preferences();
        if (preferences == null) {
            return;
        }
        StringBuilder text = new StringBuilder(saved.size() * 24);
        for (Map.Entry<String, String> entry : saved.entrySet()) {
            if (text.length() > 0) {
                text.append(';');
            }
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        preferences.edit().putString(SAVED_KEY, text.toString()).apply();
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Forgets every place and kept region, as a restart does, for tests. Saved countries stay
     * on disk and are read again on the next lookup.
     */
    static void clear() {
        MADE.get().clear();
        synchronized (KEPT) {
            KEPT.clear();
            saved = null;
            savePending = false;
        }
    }

    private static void logOnce(String what, Throwable ex) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        Logger.printException(() -> what, ex);
    }

    // The three below are stubs. Show author region writes each body on TikTok Lite with the
    // matching call on Gson's JsonReader, found by what each method does, since Lite's names
    // for them are obfuscated. Elsewhere they stay as they are and nothing calls them.

    /** Gson's JsonReader.getPath(): where the reader is, like {@code $.aweme_list[3].region}. */
    @SuppressWarnings("unused")
    static String readerPath(Object reader) {
        return null;
    }

    /** Gson's JsonReader.peek(): the JsonToken coming next. */
    @SuppressWarnings("unused")
    static Object readerPeek(Object reader) {
        return null;
    }

    /** Gson's JsonReader.nextString(). */
    @SuppressWarnings("unused")
    static String readerNextString(Object reader) {
        return null;
    }
}

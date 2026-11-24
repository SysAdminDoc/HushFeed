/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.CoverSaver;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * What the videos in the reader's own Favorites looked like while they could still be watched
 * (#516): who posted each one, its caption, when it was posted and a small copy of its cover.
 * TikTok keeps a saved video's place in the grid after its creator deletes it or makes it
 * private, but hands the place back empty, so the grid draws a grey tile nobody can tell from
 * the next one. With Remember saved videos on, each list the Favorites grid gets is read as it
 * comes in, and a video that later comes back unavailable is marked as gone with what was kept.
 *
 * <p>Only the signed-in account's own Favorites are read: the list request names whose profile
 * it's for, and a list for anyone else is left alone. The database and the covers go by
 * Hushfeed's name, which the fresh device identity wipe keeps.
 */
public final class SavedVideoMemory {
    static final String FAMILY = "saved video memory";
    /** The most videos one account's memory holds. Past it, the longest remembered that are still there go first. */
    private static final int MAX_PER_ACCOUNT = 20_000;
    /** A caption is kept whole up to here, which is more than TikTok let most of them run. */
    static final int CAPTION_CHARS = 1_000;
    /** A cover is kept about as wide as a grid tile draws it. */
    static final int COVER_WIDTH = 360;

    private static final String DATABASE_NAME = "hushfeed_saved_videos.db";
    private static final int DATABASE_VERSION = 1;
    static final String COVER_FOLDER = "hushfeed_saved_covers";
    private static final String TABLE = "saved";
    private static final String COLUMN_ACCOUNT = "account";
    private static final String COLUMN_AID = "aid";
    private static final String COLUMN_CREATOR = "creator";
    private static final String COLUMN_HANDLE = "handle";
    private static final String COLUMN_CAPTION = "caption";
    private static final String COLUMN_POSTED = "posted_ms";
    private static final String COLUMN_REMEMBERED = "remembered_ms";
    private static final String COLUMN_GONE = "gone_ms";
    private static final String[] COLUMNS = {
            COLUMN_AID, COLUMN_CREATOR, COLUMN_HANDLE, COLUMN_CAPTION, COLUMN_POSTED, COLUMN_REMEMBERED, COLUMN_GONE,
    };

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Morphe-SavedVideoMemory");
        thread.setDaemon(true);
        return thread;
    });

    static volatile int cap = MAX_PER_ACCOUNT;
    /** Stands in for the network in tests: writes a cover's bytes to the file, or throws. */
    static volatile CoverFetcher fetcherForTests;

    /** Whether the Favorites list last asked for is the signed-in account's own. */
    private static volatile boolean ownRequest;
    /** Each Favorites grid's adapter, with whether its list is the signed-in account's own. */
    private static final Map<Object, Boolean> OWN = new WeakHashMap<>();
    /** The videos already read from the list an adapter holds, so a longer list reads only its new ones. */
    private static final Map<Object, Set<String>> READ = new WeakHashMap<>();
    /** The gone videos the grid has been handed, by account and id, for a tile to draw from. */
    private static final Map<String, Entry> GONE = new ConcurrentHashMap<>();
    private static Database database;

    private SavedVideoMemory() {
    }

    /** One remembered video. */
    public static final class Entry {
        public final String aid;
        /** The creator's display name, or empty. */
        public final String creator;
        /** The creator's handle without the @, or empty. */
        public final String handle;
        public final String caption;
        /** When the video was posted, or 0 when TikTok didn't say. */
        public final long postedMs;
        /** When this phone first saw it in Favorites. */
        public final long rememberedMs;
        /** When Favorites first handed it back unavailable, or 0 while it's still there. */
        public final long goneMs;

        public Entry(String aid, String creator, String handle, String caption,
                long postedMs, long rememberedMs, long goneMs) {
            this.aid = aid;
            this.creator = creator;
            this.handle = handle;
            this.caption = caption;
            this.postedMs = postedMs;
            this.rememberedMs = rememberedMs;
            this.goneMs = goneMs;
        }

        /** The name a row or a tile shows: the display name, else the handle with its @, else empty. */
        public String shownName() {
            if (!creator.isEmpty()) return creator;
            return handle.isEmpty() ? "" : "@" + handle;
        }
    }

    public interface Listener {
        void onRows(List<Entry> rows);
    }

    interface CoverFetcher {
        void fetch(List<String> urls, File target) throws Exception;
    }

    /** A video as a Favorites list carried it. */
    static final class Seen {
        final String aid;
        final boolean unavailable;
        final String creator;
        final String handle;
        final String caption;
        final long postedMs;
        final List<String> coverUrls;

        Seen(String aid, boolean unavailable, String creator, String handle, String caption,
                long postedMs, List<String> coverUrls) {
            this.aid = aid;
            this.unavailable = unavailable;
            this.creator = creator;
            this.handle = handle;
            this.caption = caption;
            this.postedMs = postedMs;
            this.coverUrls = coverUrls;
        }

        /** Whether there's anything worth keeping: a video that names nobody and says nothing isn't. */
        boolean hasDetails() {
            return !creator.isEmpty() || !handle.isEmpty() || !caption.isEmpty();
        }
    }

    public static boolean enabled() {
        return SettingsStatus.favoritesJumpEnabled && Settings.REMEMBER_SAVED_VIDEOS.get() && !Setting.isPaused();
    }

    /**
     * A profile list request is about to go out, for this profile and tab. From the Favorites
     * request hook, on TikTok's thread, so it only notes whose Favorites were asked for.
     */
    static void requested(String userId, String secUserId, int type) {
        if (type != GridSort.TAB_FAVORITES && type != GridSort.TAB_FAVORITE_VIDEOS) return;
        try {
            String mine = SignedInUser.id();
            String mineSec = SignedInUser.secId();
            ownRequest = mine != null && mine.equals(userId)
                    || mineSec != null && !mineSec.isEmpty() && mineSec.equals(secUserId);
        } catch (Throwable ex) {
            ownRequest = false;
            Logger.printException(() -> "Could not tell whose Favorites were asked for", ex);
        }
    }

    /**
     * A Favorites grid's adapter got a list: a fresh one, or the same one grown by a page. The
     * videos not read from it yet are read here and stored off the main thread.
     */
    static void listIn(Object adapter, List<?> list, boolean fresh) {
        try {
            if (adapter == null || list == null || !enabled()) return;
            int tab = GridSort.tabOf(adapter);
            if (tab != GridSort.TAB_FAVORITES && tab != GridSort.TAB_FAVORITE_VIDEOS) return;
            String account = SignedInUser.id();
            if (account == null) return;
            Set<String> read;
            synchronized (OWN) {
                // TikTok's second Favorites tab number is only ever the reader's own list.
                if (fresh || !OWN.containsKey(adapter)) {
                    OWN.put(adapter, tab == GridSort.TAB_FAVORITE_VIDEOS || ownRequest);
                    READ.remove(adapter);
                }
                if (!Boolean.TRUE.equals(OWN.get(adapter))) {
                    Logger.printDebug(() -> "Saved video memory: left another profile's Favorites alone");
                    return;
                }
                read = READ.get(adapter);
                if (read == null) {
                    read = new HashSet<>();
                    READ.put(adapter, read);
                }
            }
            List<Seen> seen = new ArrayList<>();
            for (Object aweme : list) {
                String aid = Reflect.string(aweme, "getAid", "aid");
                if (aid == null || !read.add(aid)) continue;
                Seen one = read(aweme, aid);
                if (one != null) seen.add(one);
            }
            if (seen.isEmpty()) return;
            HookStatus.bound(FAMILY, "favorites list read");
            long now = System.currentTimeMillis();
            int size = list.size();
            Logger.printDebug(() -> "Saved video memory: read " + seen.size() + " new of " + size
                    + " in tab " + tab + (fresh ? ", a fresh list" : ", a longer list"));
            run(() -> {
                if (store(account, seen, now)) Utils.runOnMainThread(() -> GridSort.refresh(adapter));
            });
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "favorites list read", ex);
            Logger.printException(() -> "Could not read a Favorites list", ex);
        }
    }

    /** What a list's video says about itself, or null when it isn't a video with an id. */
    static Seen read(Object aweme, String aid) {
        Object author = Reflect.property(aweme, "getAuthor", "author");
        String creator = author == null ? null : Reflect.string(author, "getNickname", "nickname");
        String handle = author == null ? null : Reflect.string(author, "getUniqueId", "uniqueId");
        Object posted = Reflect.property(aweme, "getCreateTime", "createTime");
        long postedSeconds = posted instanceof Number ? ((Number) posted).longValue() : 0;
        List<String> covers = CoverSaver.gridCoverUrls(aweme);
        return new Seen(aid, unavailable(aweme), creator == null ? "" : creator, handle == null ? "" : handle,
                shorten(Reflect.string(aweme, "getDesc", "desc")), Math.max(0, postedSeconds) * 1000, covers);
    }

    /**
     * Whether TikTok handed this video over as one that can't be watched any more: marked deleted
     * or taken down, or marked as unable to play. A video that only its poster can see still
     * plays for its poster, so that mark alone doesn't count.
     */
    public static boolean unavailable(Object aweme) {
        if (aweme == null) return false;
        Object status = Reflect.property(aweme, "getStatus", "status");
        if (status != null) {
            if (Boolean.TRUE.equals(Reflect.property(status, "isDelete", "isDelete"))) return true;
            if (Boolean.TRUE.equals(Reflect.property(status, "isProhibited", "isProhibited"))) return true;
        }
        return Boolean.FALSE.equals(Reflect.property(aweme, "isCanPlay", "canPlay"));
    }

    /**
     * Stores what a list said. A video with details is remembered, or brought up to date, and is
     * no longer gone if it was. One that came back unavailable is marked gone from now, where it
     * was remembered. True when the grid has gone videos to draw that it didn't have before.
     */
    static boolean store(String account, List<Seen> seen, long nowMs) {
        SQLiteDatabase db = database().getWritableDatabase();
        List<Seen> coverless = new ArrayList<>();
        boolean drawn = false;
        boolean added = false;
        db.beginTransaction();
        try {
            for (Seen one : seen) {
                String[] key = {account, one.aid};
                if (one.unavailable) {
                    ContentValues gone = new ContentValues();
                    gone.put(COLUMN_GONE, nowMs);
                    int marked = db.update(TABLE, gone, COLUMN_ACCOUNT + " = ? AND " + COLUMN_AID + " = ? AND "
                            + COLUMN_GONE + " = 0", key);
                    // TikTok sometimes still says who posted a video it won't play. Seen for the
                    // first time like that, it's remembered and gone at once. A row that's
                    // already there, gone since earlier, stays as it is.
                    if (marked == 0 && one.hasDetails()) {
                        db.insertWithOnConflict(TABLE, null,
                                values(account, new Entry(one.aid, one.creator, one.handle, one.caption,
                                        one.postedMs, nowMs, nowMs)),
                                SQLiteDatabase.CONFLICT_IGNORE);
                        if (!one.coverUrls.isEmpty() && !coverFile(one.aid).isFile()) coverless.add(one);
                    }
                    continue;
                }
                if (!one.hasDetails()) continue;
                ContentValues values = new ContentValues();
                values.put(COLUMN_CREATOR, one.creator);
                values.put(COLUMN_HANDLE, one.handle);
                values.put(COLUMN_CAPTION, one.caption);
                values.put(COLUMN_POSTED, one.postedMs);
                values.put(COLUMN_GONE, 0);
                if (db.update(TABLE, values, COLUMN_ACCOUNT + " = ? AND " + COLUMN_AID + " = ?", key) == 0) {
                    values.put(COLUMN_ACCOUNT, account);
                    values.put(COLUMN_AID, one.aid);
                    values.put(COLUMN_REMEMBERED, nowMs);
                    db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
                    added = true;
                }
                GONE.remove(key(account, one.aid));
                if (!one.coverUrls.isEmpty() && !coverFile(one.aid).isFile()) coverless.add(one);
            }
            // A gone video is what all of this is kept for, so those are the last to go.
            if (added) {
                db.execSQL("DELETE FROM " + TABLE + " WHERE " + COLUMN_ACCOUNT + " = ? AND " + COLUMN_AID
                                + " IN (SELECT " + COLUMN_AID + " FROM " + TABLE + " WHERE " + COLUMN_ACCOUNT
                                + " = ? ORDER BY " + COLUMN_GONE + " != 0 DESC, " + COLUMN_REMEMBERED + " DESC, "
                                + COLUMN_AID + " DESC LIMIT -1 OFFSET " + cap + ")",
                        new Object[]{account, account});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        for (Seen one : seen) {
            if (!one.unavailable) continue;
            Entry entry = find(account, one.aid);
            if (entry != null && GONE.put(key(account, one.aid), entry) == null) drawn = true;
        }
        int covers = coverless.size();
        boolean redraw = drawn;
        Logger.printDebug(() -> "Saved video memory: stored " + seen.size() + ", " + covers
                + " covers to fetch, gone tiles to draw: " + redraw);
        for (Seen one : coverless) keepCover(one);
        return drawn;
    }

    /** The gone video a grid tile shows in place of TikTok's grey one, or null. Main thread, no disk. */
    static Entry goneFor(String aid) {
        String account = SignedInUser.id();
        return account == null || aid == null ? null : GONE.get(key(account, aid));
    }

    /** The signed-in account's gone videos, the latest to go first, handed over on the main thread. */
    public static void load(Listener listener) {
        String account = account();
        run(() -> deliver(listener, readGone(account)));
    }

    /** Forgets the signed-in account's gone videos and hands over what was held, for an undo. */
    public static void clear(Listener listener) {
        String account = account();
        run(() -> {
            List<Entry> rows = readGone(account);
            database().getWritableDatabase().delete(TABLE,
                    COLUMN_ACCOUNT + " = ? AND " + COLUMN_GONE + " != 0", new String[]{account});
            for (Entry row : rows) GONE.remove(key(account, row.aid));
            deliver(listener, rows);
        });
    }

    /** Puts cleared rows back. Their covers were never taken away: the next sweep does that. */
    public static void restore(List<Entry> rows) {
        String account = account();
        run(() -> {
            SQLiteDatabase db = database().getWritableDatabase();
            db.beginTransaction();
            try {
                for (Entry row : rows) {
                    db.insertWithOnConflict(TABLE, null, values(account, row), SQLiteDatabase.CONFLICT_IGNORE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        });
    }

    /**
     * Deletes the covers no remembered video goes with any more: a cleared list's once its undo
     * is out of reach, and those of videos past the cap. Run when the list is opened.
     */
    public static void sweepCovers() {
        run(() -> {
            File[] files = coverFolder().listFiles();
            if (files == null || files.length == 0) return;
            Set<String> kept = new HashSet<>();
            try (Cursor cursor = database().getReadableDatabase().query(true, TABLE,
                    new String[]{COLUMN_AID}, null, null, null, null, null, null)) {
                while (cursor.moveToNext()) kept.add(cursor.getString(0));
            }
            for (File file : files) {
                String name = file.getName();
                int dot = name.lastIndexOf('.');
                if (!kept.contains(dot < 0 ? name : name.substring(0, dot)) && !file.delete()) {
                    Logger.printDebug(() -> "Saved video memory: a cover stayed behind");
                }
            }
        });
    }

    /** The kept cover of a video, or null when none was kept or it can't be read. */
    public static Bitmap cover(String aid) {
        File file = coverFile(aid);
        return file.isFile() ? BitmapFactory.decodeFile(file.getPath()) : null;
    }

    /** Line breaks and all, cut without splitting an emoji. */
    static String shorten(String caption) {
        if (caption == null) return "";
        String text = caption.trim();
        if (text.length() <= CAPTION_CHARS) return text;
        int end = CAPTION_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    static Entry find(String account, String aid) {
        try (Cursor cursor = database().getReadableDatabase().query(TABLE, COLUMNS,
                COLUMN_ACCOUNT + " = ? AND " + COLUMN_AID + " = ?", new String[]{account, aid},
                null, null, null)) {
            return cursor.moveToNext() ? entry(cursor) : null;
        }
    }

    static List<Entry> readGone(String account) {
        List<Entry> rows = new ArrayList<>();
        try (Cursor cursor = database().getReadableDatabase().query(TABLE, COLUMNS,
                COLUMN_ACCOUNT + " = ? AND " + COLUMN_GONE + " != 0", new String[]{account},
                null, null, COLUMN_GONE + " DESC, " + COLUMN_AID + " DESC")) {
            while (cursor.moveToNext()) rows.add(entry(cursor));
        }
        return rows;
    }

    static File coverFile(String aid) {
        // An id is digits, but it came from TikTok's server, so nothing else gets into a file name.
        return new File(coverFolder(), aid.replaceAll("[^0-9A-Za-z_-]", "_") + ".jpg");
    }

    private static File coverFolder() {
        return new File(Utils.getContext().getFilesDir(), COVER_FOLDER);
    }

    /**
     * Fetches a video's grid cover and keeps a small copy. A cover that can't be fetched or read
     * is tried again the next time Favorites hands the video over in a new session.
     */
    private static void keepCover(Seen one) {
        File folder = coverFolder();
        if (!folder.isDirectory() && !folder.mkdirs()) return;
        File fetched = new File(folder, one.aid.replaceAll("[^0-9A-Za-z_-]", "_") + ".part");
        try {
            CoverFetcher fetcher = fetcherForTests;
            if (fetcher != null) fetcher.fetch(one.coverUrls, fetched);
            else CoverSaver.fetchTo(Utils.getContext(), one.coverUrls, fetched);
            Bitmap small = shrink(fetched);
            if (small == null) return;
            File target = coverFile(one.aid);
            try (FileOutputStream out = new FileOutputStream(target)) {
                small.compress(Bitmap.CompressFormat.JPEG, 80, out);
            } finally {
                small.recycle();
            }
        } catch (Throwable ex) {
            Logger.printDebug(() -> "Saved video memory: a cover wasn't kept: " + ex);
        } finally {
            if (fetched.exists() && !fetched.delete()) fetched.deleteOnExit();
        }
    }

    /** The picture at about a grid tile's width, decoded no larger than it needs to be. */
    private static Bitmap shrink(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleFor(bounds.outWidth);
        Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), options);
        if (decoded == null || decoded.getWidth() <= COVER_WIDTH) return decoded;
        int height = Math.max(1, Math.round(decoded.getHeight() * (COVER_WIDTH / (float) decoded.getWidth())));
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, COVER_WIDTH, height, true);
        if (scaled != decoded) decoded.recycle();
        return scaled;
    }

    /** The largest power of two that still leaves the picture at least the kept width. */
    static int sampleFor(int width) {
        int sample = 1;
        while (width / (sample * 2) >= COVER_WIDTH) sample *= 2;
        return sample;
    }

    /** Waits for what's queued, for tests. */
    static void drainForTests() throws Exception {
        IO.submit(() -> { }).get();
    }

    static void resetForTests() throws Exception {
        drainForTests();
        database().getWritableDatabase().delete(TABLE, null, null);
        File[] covers = coverFolder().listFiles();
        if (covers != null) for (File cover : covers) cover.delete();
        synchronized (OWN) {
            OWN.clear();
            READ.clear();
        }
        GONE.clear();
        ownRequest = false;
        fetcherForTests = null;
        cap = MAX_PER_ACCOUNT;
    }

    private static String account() {
        String id = SignedInUser.id();
        return id == null ? "" : id;
    }

    private static String key(String account, String aid) {
        return account + '/' + aid;
    }

    private static Entry entry(Cursor cursor) {
        return new Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                cursor.getLong(4), cursor.getLong(5), cursor.getLong(6));
    }

    private static ContentValues values(String account, Entry row) {
        ContentValues values = new ContentValues();
        values.put(COLUMN_ACCOUNT, account);
        values.put(COLUMN_AID, row.aid);
        values.put(COLUMN_CREATOR, row.creator);
        values.put(COLUMN_HANDLE, row.handle);
        values.put(COLUMN_CAPTION, row.caption);
        values.put(COLUMN_POSTED, row.postedMs);
        values.put(COLUMN_REMEMBERED, row.rememberedMs);
        values.put(COLUMN_GONE, row.goneMs);
        return values;
    }

    private static void deliver(Listener listener, List<Entry> rows) {
        List<Entry> handed = Collections.unmodifiableList(rows);
        Utils.runOnMainThread(() -> listener.onRows(handed));
    }

    private static void run(Runnable job) {
        IO.execute(() -> {
            try {
                job.run();
            } catch (Throwable ex) {
                Logger.printException(() -> "Could not reach the saved video memory", ex);
            }
        });
    }

    private static synchronized Database database() {
        if (database == null) database = new Database(Utils.getContext());
        return database;
    }

    private static final class Database extends SQLiteOpenHelper {
        Database(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + COLUMN_ACCOUNT + " TEXT NOT NULL, "
                    + COLUMN_AID + " TEXT NOT NULL, "
                    + COLUMN_CREATOR + " TEXT NOT NULL, "
                    + COLUMN_HANDLE + " TEXT NOT NULL, "
                    + COLUMN_CAPTION + " TEXT NOT NULL, "
                    + COLUMN_POSTED + " INTEGER NOT NULL, "
                    + COLUMN_REMEMBERED + " INTEGER NOT NULL, "
                    + COLUMN_GONE + " INTEGER NOT NULL DEFAULT 0, "
                    + "PRIMARY KEY (" + COLUMN_ACCOUNT + ", " + COLUMN_AID + "))");
            db.execSQL("CREATE INDEX IF NOT EXISTS saved_account_gone ON " + TABLE
                    + " (" + COLUMN_ACCOUNT + ", " + COLUMN_GONE + ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            onCreate(db);
        }

        /** Going back to an older bundle is no reason to lose what was remembered. The default here throws. */
        @Override
        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            onCreate(db);
        }
    }
}

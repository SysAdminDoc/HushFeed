/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.seen;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * A watch history kept on the phone (#172): who posted each video the reader watched, its caption
 * and when. TikTok's own list comes from its server and leaves videos out, and the record behind
 * the seen filter holds bare ids nobody can read.
 *
 * <p>A database of its own, since {@link SeenVideoHistory} keeps its record in memory and caps it
 * for the feed's sake. The name starts with {@code seen_videos} so the fresh device identity wipe
 * keeps it, as it does that record.
 */
public final class LocalWatchHistory {
    /** The most videos one account's list holds. The oldest go first. */
    private static final int MAX_PER_ACCOUNT = 5_000;
    /** Longer captions are cut, since a row shows two lines and the search needs no more. */
    static final int CAPTION_CHARS = 200;

    private static final String DATABASE_NAME = "seen_videos_details.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE = "watched";
    private static final String COLUMN_ACCOUNT = "account";
    private static final String COLUMN_AID = "aid";
    private static final String COLUMN_CREATOR = "creator";
    private static final String COLUMN_CAPTION = "caption";
    private static final String COLUMN_WATCHED = "watched_ms";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Morphe-LocalWatchHistory");
        thread.setDaemon(true);
        return thread;
    });

    static volatile int cap = MAX_PER_ACCOUNT;

    /** The video this list last took, so a video that plays on takes one row and no more reads. */
    private static volatile String listedAid;
    private static volatile String listedAccount;
    private static Database database;

    private LocalWatchHistory() {
    }

    public static final class Entry {
        public final String aid;
        public final String creator;
        public final String caption;
        public final long watchedMs;

        public Entry(String aid, String creator, String caption, long watchedMs) {
            this.aid = aid;
            this.creator = creator;
            this.caption = caption;
            this.watchedMs = watchedMs;
        }
    }

    public interface Listener {
        void onRows(List<Entry> rows);
    }

    /**
     * From the player's progress, several times a second, so it leaves on the switch or the listed
     * video before it reads anything. A video counts at the same point it counts as seen. Its
     * details wait for the feed's selection to catch up with the player, which later ticks see.
     */
    static void onProgress(String account, String aid, long positionMs, long durationMs, long nowMs) {
        if (!Settings.LOCAL_WATCH_HISTORY.get()) return;
        if (aid.equals(listedAid) && account.equals(listedAccount)) return;
        if (!SeenVideoHistory.hasReachedSeenThreshold(positionMs, durationMs)) return;
        try {
            Object aweme = CurrentVideoAuthor.getAweme();
            if (aweme == null || !aid.equals(Reflect.string(aweme, "getAid", "aid"))) return;
            listedAid = aid;
            listedAccount = account;
            Object user = Reflect.property(aweme, "getAuthor", "author");
            String creator = user == null ? null : Reflect.firstNonBlank(
                    Reflect.string(user, "getNickname", "nickname"),
                    Reflect.string(user, "getUniqueId", "uniqueId"));
            record(account, aid, creator, Reflect.string(aweme, "getDesc", "desc"), nowMs);
        } catch (Throwable ex) {
            // The player names this video again in a moment, and this isn't worth a line each time.
            listedAid = aid;
            listedAccount = account;
            Logger.printException(() -> "Could not read the watched video", ex);
        }
    }

    /** A video watched again moves back to the top. */
    static void record(String account, String aid, String creator, String caption, long nowMs) {
        String shortCaption = shorten(caption);
        run(() -> {
            SQLiteDatabase db = database().getWritableDatabase();
            db.insertWithOnConflict(TABLE, null,
                    values(account, new Entry(aid, creator == null ? "" : creator, shortCaption, nowMs)),
                    SQLiteDatabase.CONFLICT_REPLACE);
            db.execSQL("DELETE FROM " + TABLE + " WHERE " + COLUMN_ACCOUNT + " = ? AND " + COLUMN_AID
                            + " IN (SELECT " + COLUMN_AID + " FROM " + TABLE + " WHERE " + COLUMN_ACCOUNT
                            + " = ? ORDER BY " + COLUMN_WATCHED + " DESC LIMIT -1 OFFSET " + cap + ")",
                    new Object[]{account, account});
        });
    }

    /** The signed-in account's list, newest first, handed over on the main thread. */
    public static void load(Listener listener) {
        String account = SeenVideoHistory.account();
        run(() -> deliver(listener, read(account)));
    }

    /** Empties the signed-in account's list and hands over what it held, for an undo. */
    public static void clear(Listener listener) {
        String account = SeenVideoHistory.account();
        run(() -> {
            List<Entry> rows = read(account);
            database().getWritableDatabase().delete(TABLE, COLUMN_ACCOUNT + " = ?", new String[]{account});
            deliver(listener, rows);
        });
    }

    /** Puts cleared rows back. A video watched since the clear keeps its newer row. */
    public static void restore(List<Entry> rows) {
        String account = SeenVideoHistory.account();
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

    /** The rows whose creator or caption holds the text, in any case. Empty text keeps them all. */
    public static List<Entry> filter(List<Entry> rows, String text) {
        String wanted = text == null ? "" : text.trim();
        if (wanted.isEmpty()) return rows;
        List<Entry> found = new ArrayList<>();
        for (Entry row : rows) {
            if (holds(row.creator, wanted) || holds(row.caption, wanted)) found.add(row);
        }
        return found;
    }

    /** {@code regionMatches} rather than lower casing both, which a Turkish phone gets wrong for I. */
    private static boolean holds(String value, String wanted) {
        for (int at = 0; at <= value.length() - wanted.length(); at++) {
            if (value.regionMatches(true, at, wanted, 0, wanted.length())) return true;
        }
        return false;
    }

    /** One line, cut without splitting an emoji. */
    static String shorten(String caption) {
        if (caption == null) return "";
        String line = caption.replaceAll("\\s+", " ").trim();
        if (line.length() <= CAPTION_CHARS) return line;
        int end = CAPTION_CHARS;
        if (Character.isHighSurrogate(line.charAt(end - 1))) end--;
        return line.substring(0, end);
    }

    static List<Entry> read(String account) {
        List<Entry> rows = new ArrayList<>();
        try (Cursor cursor = database().getReadableDatabase().query(TABLE,
                new String[]{COLUMN_AID, COLUMN_CREATOR, COLUMN_CAPTION, COLUMN_WATCHED},
                COLUMN_ACCOUNT + " = ?", new String[]{account},
                null, null, COLUMN_WATCHED + " DESC")) {
            while (cursor.moveToNext()) {
                rows.add(new Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)));
            }
        }
        return rows;
    }

    /** Waits for what's queued, for tests. */
    static void drainForTests() throws Exception {
        IO.submit(() -> { }).get();
    }

    static void resetForTests() throws Exception {
        drainForTests();
        database().getWritableDatabase().delete(TABLE, null, null);
        listedAid = null;
        listedAccount = null;
        cap = MAX_PER_ACCOUNT;
    }

    private static void deliver(Listener listener, List<Entry> rows) {
        List<Entry> handed = Collections.unmodifiableList(rows);
        Utils.runOnMainThread(() -> listener.onRows(handed));
    }

    private static ContentValues values(String account, Entry row) {
        ContentValues values = new ContentValues();
        values.put(COLUMN_ACCOUNT, account);
        values.put(COLUMN_AID, row.aid);
        values.put(COLUMN_CREATOR, row.creator);
        values.put(COLUMN_CAPTION, row.caption);
        values.put(COLUMN_WATCHED, row.watchedMs);
        return values;
    }

    private static void run(Runnable job) {
        IO.execute(() -> {
            try {
                job.run();
            } catch (Throwable ex) {
                Logger.printException(() -> "Could not reach the watch history", ex);
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
                    + COLUMN_CAPTION + " TEXT NOT NULL, "
                    + COLUMN_WATCHED + " INTEGER NOT NULL, "
                    + "PRIMARY KEY (" + COLUMN_ACCOUNT + ", " + COLUMN_AID + "))");
            db.execSQL("CREATE INDEX IF NOT EXISTS watched_account_time ON " + TABLE
                    + " (" + COLUMN_ACCOUNT + ", " + COLUMN_WATCHED + ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            onCreate(db);
        }

        /** Going back to an older bundle is no reason to lose the list. The default here throws. */
        @Override
        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            onCreate(db);
        }
    }
}

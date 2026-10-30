/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Utils;

/**
 * The messages Keep unsent messages has left in a chat, saved on the phone so they can be marked
 * as unsent and removed later, even after TikTok has been closed.
 *
 * <p>Each entry holds what TikTok's recall updater was handed: the server id of the message, the
 * uid of whoever recalled it, the message body as TikTok encoded it, and the message type. That
 * is everything needed to hand the same recall to an updater again, plus how to make that updater. The newest 500 are kept.
 */
final class KeptUnsentStore {
    static final class Entry {
        final String serverId;
        final String recallUid;
        final int type;
        /** The message body as base64, or empty when it could not be encoded. */
        final String body;
        final long keptAt;
        /** What it takes to make TikTok's recall updater again, as JSON, or empty when it was not read. */
        final String updater;

        Entry(String serverId, String recallUid, int type, String body, long keptAt) {
            this(serverId, recallUid, type, body, keptAt, "");
        }

        Entry(String serverId, String recallUid, int type, String body, long keptAt, String updater) {
            this.serverId = serverId;
            this.recallUid = recallUid;
            this.type = type;
            this.body = body;
            this.keptAt = keptAt;
            this.updater = updater;
        }
    }

    private static final String FILE_NAME = "hushfeed_kept_unsent.json";
    private static final int LIMIT = 500;
    private static final Object LOCK = new Object();

    /** So a test can keep the file in a folder of its own. */
    static volatile File fileForTests;

    private static List<Entry> entries;
    /** The ids the chat checks on every draw, so it never waits for the lock. */
    private static volatile Set<Long> ids = Collections.emptySet();

    private KeptUnsentStore() {}

    /** The server ids of the kept messages, as numbers. Never null. */
    static Set<Long> ids() {
        ensureLoaded();
        return ids;
    }

    static int count() {
        ensureLoaded();
        synchronized (LOCK) {
            return entries.size();
        }
    }

    /** A copy of the entries, oldest first. */
    static List<Entry> all() {
        ensureLoaded();
        synchronized (LOCK) {
            return new ArrayList<>(entries);
        }
    }

    static void add(Entry entry) {
        ensureLoaded();
        synchronized (LOCK) {
            for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
                if (it.next().serverId.equals(entry.serverId)) it.remove();
            }
            entries.add(entry);
            while (entries.size() > LIMIT) entries.remove(0);
            publish();
            save();
        }
    }

    static void remove(String serverId) {
        ensureLoaded();
        synchronized (LOCK) {
            for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
                if (it.next().serverId.equals(serverId)) it.remove();
            }
            publish();
            save();
        }
    }

    /** Forgets what was loaded, so the next call reads the file again. For tests. */
    static void resetForTests() {
        synchronized (LOCK) {
            entries = null;
            ids = Collections.emptySet();
        }
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (entries != null) return;
            entries = new ArrayList<>();
            File file = file();
            if (file == null || !file.isFile()) return;
            try {
                JSONArray array = new JSONArray(read(file));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) continue;
                    String serverId = item.optString("id", "");
                    if (serverId.isEmpty()) continue;
                    entries.add(new Entry(serverId, item.optString("uid", ""), item.optInt("type", 0),
                            item.optString("body", ""), item.optLong("at", 0L), item.optString("updater", "")));
                }
            } catch (JSONException | IOException error) {
                // A damaged file is the same as none: nothing is marked, and the next kept message starts a new one.
                entries.clear();
            }
            publish();
        }
    }

    private static void publish() {
        Set<Long> numbers = new HashSet<>();
        for (Entry entry : entries) {
            try {
                numbers.add(Long.parseLong(entry.serverId));
            } catch (NumberFormatException ignored) {
                // An id that is not a number can't be matched to a message, so it is not marked.
            }
        }
        ids = Collections.unmodifiableSet(numbers);
    }

    private static void save() {
        File file = file();
        if (file == null) return;
        try {
            JSONArray array = new JSONArray();
            for (Entry entry : entries) {
                array.put(new JSONObject()
                        .put("id", entry.serverId)
                        .put("uid", entry.recallUid)
                        .put("type", entry.type)
                        .put("body", entry.body)
                        .put("at", entry.keptAt)
                        .put("updater", entry.updater));
            }
            File temporary = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(array.toString().getBytes("UTF-8"));
            }
            if (!temporary.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                temporary.renameTo(file);
            }
        } catch (JSONException | IOException error) {
            // The message is still kept for this run. Marking it after a restart is what is lost.
        }
    }

    private static File file() {
        File forTests = fileForTests;
        if (forTests != null) return forTests;
        Context context = Utils.getContext();
        return context == null ? null : new File(context.getFilesDir(), FILE_NAME);
    }

    private static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = in.read(buffer)) > 0) out.write(buffer, 0, length);
            return out.toString("UTF-8");
        }
    }
}

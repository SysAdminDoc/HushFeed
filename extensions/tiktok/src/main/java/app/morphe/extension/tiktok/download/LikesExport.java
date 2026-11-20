/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.MalformedJsonException;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import app.morphe.extension.tiktok.seen.WatchHistoryImport;

/**
 * The liked and favorited videos a TikTok data export lists, read off its JSON file
 * ({@code user_data_tiktok.json}, from TikTok's Download your data with JSON chosen), or straight
 * from the zip TikTok sends it in.
 *
 * <p>The file also holds the whole watch history and every comment, so a heavy account's runs to
 * tens of megabytes. It's read as a stream and only the two lists are kept. TikTok has moved the
 * lists between exports: the reviewed 2024-12 file nests {@code Your Activity > Like List >
 * ItemFavoriteList} with lowercase {@code date} and {@code link}, and others name the section
 * {@code Activity} or {@code Likes and Favorites}, the groups {@code Liked Videos} or
 * {@code Favorites}, and the keys {@code Date} and {@code Link} (tiktok-favorites-archiver's
 * export.py). Every one of those is read. A link counts only as a video link on tiktok.com or
 * tiktokv.com, and no link is ever opened: only the id in it is kept.
 */
public final class LikesExport {
    /** Bigger than any export of likes alone, so a whole account's file still reads. */
    public static final int MAX_BYTES = 256 * 1024 * 1024;
    /** More rows than this in one list and the file isn't an export. */
    static final int MAX_ROWS = 200_000;
    /** A smaller cap for the tests, which can't write 256 MB. */
    static volatile int maxBytesForTests = -1;

    private static final Set<String> SECTIONS = new LinkedHashSet<>(Arrays.asList(
            "Your Activity", "Activity", "Likes and Favorites"));
    private static final Set<String> LIKE_GROUPS = new LinkedHashSet<>(Arrays.asList("Like List", "Liked Videos"));
    private static final Set<String> LIKE_LISTS = new LinkedHashSet<>(Arrays.asList("ItemFavoriteList", "LikedVideoList"));
    private static final Set<String> FAVORITE_GROUPS = new LinkedHashSet<>(Arrays.asList("Favorite Videos", "Favorites"));
    private static final Set<String> FAVORITE_LISTS = Collections.singleton("FavoriteVideoList");

    public enum Reason { TOO_LARGE, UNSUPPORTED, DAMAGED, UNREADABLE }

    public static final class Rejected extends IOException {
        public final Reason reason;

        Rejected(Reason reason) {
            this(reason, null);
        }

        Rejected(Reason reason, Throwable cause) {
            super("Data export refused: " + reason, cause);
            this.reason = reason;
        }
    }

    /** What the file lists, each list newest first as TikTok writes it, each video once. */
    public static final class Videos {
        public final List<String> liked;
        public final List<String> favorites;
        /** Rows whose link isn't a TikTok video link. */
        public final int skipped;

        Videos(List<String> liked, List<String> favorites, int skipped) {
            this.liked = Collections.unmodifiableList(liked);
            this.favorites = Collections.unmodifiableList(favorites);
            this.skipped = skipped;
        }

        /** The picked lists as one, liked first, each video once. */
        public List<String> pick(boolean likes, boolean favorited) {
            Set<String> ids = new LinkedHashSet<>();
            if (likes) ids.addAll(liked);
            if (favorited) ids.addAll(favorites);
            return Collections.unmodifiableList(new ArrayList<>(ids));
        }
    }

    private LikesExport() {
    }

    /**
     * Reads the export's JSON file, or the zip TikTok sends it in: there the first
     * {@code user_data*.json} entry is read.
     */
    public static Videos read(InputStream input) throws IOException {
        if (input == null) throw new Rejected(Reason.UNREADABLE);
        try (InputStream owned = new BufferedInputStream(input, 64 * 1024)) {
            owned.mark(4);
            byte[] head = new byte[4];
            int read = 0;
            while (read < head.length) {
                int count = owned.read(head, read, head.length - read);
                if (count < 0) break;
                read += count;
            }
            owned.reset();
            if (read < 4 || head[0] != 'P' || head[1] != 'K' || head[2] != 3 || head[3] != 4) return parse(owned);
            ZipInputStream zip = new ZipInputStream(owned);
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                String base = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                if (!entry.isDirectory() && base.startsWith("user_data") && base.endsWith(".json")) return parse(zip);
            }
            throw new Rejected(Reason.UNSUPPORTED);
        } catch (Rejected refused) {
            throw refused;
        } catch (ZipException | IllegalArgumentException damaged) {
            // A broken zip, or an entry name that isn't text.
            throw new Rejected(Reason.DAMAGED, damaged);
        } catch (IOException failure) {
            throw new Rejected(Reason.UNREADABLE, failure);
        }
    }

    private static Videos parse(InputStream input) throws IOException {
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        Set<String> liked = new LinkedHashSet<>();
        Set<String> favorites = new LinkedHashSet<>();
        int[] skipped = {0};
        boolean[] found = {false};
        try (Capped capped = new Capped(input); JsonReader json = new JsonReader(new InputStreamReader(capped, utf8))) {
            skipByteOrderMark(capped);
            json.beginObject();
            while (json.hasNext()) {
                String section = json.nextName();
                if (!SECTIONS.contains(section) || json.peek() != JsonToken.BEGIN_OBJECT) {
                    json.skipValue();
                    continue;
                }
                json.beginObject();
                while (json.hasNext()) {
                    String group = json.nextName();
                    boolean likes = LIKE_GROUPS.contains(group);
                    if ((!likes && !FAVORITE_GROUPS.contains(group)) || json.peek() != JsonToken.BEGIN_OBJECT) {
                        json.skipValue();
                        continue;
                    }
                    json.beginObject();
                    while (json.hasNext()) {
                        String list = json.nextName();
                        if (!(likes ? LIKE_LISTS : FAVORITE_LISTS).contains(list)) {
                            json.skipValue();
                            continue;
                        }
                        // An empty list is written as null.
                        found[0] = true;
                        if (json.peek() == JsonToken.NULL) {
                            json.nextNull();
                            continue;
                        }
                        readRows(json, likes ? liked : favorites, skipped);
                    }
                    json.endObject();
                }
                json.endObject();
            }
            json.endObject();
        } catch (Rejected refused) {
            throw refused;
        } catch (IllegalStateException | NumberFormatException damaged) {
            // JsonReader's answer to a value of the wrong kind, such as a list where an object goes.
            throw new Rejected(Reason.DAMAGED, damaged);
        } catch (MalformedJsonException | EOFException | CharacterCodingException damaged) {
            throw new Rejected(Reason.DAMAGED, damaged);
        } catch (IOException failure) {
            throw new Rejected(Reason.UNREADABLE, failure);
        }
        if (!found[0]) throw new Rejected(Reason.UNSUPPORTED);
        return new Videos(new ArrayList<>(liked), new ArrayList<>(favorites), skipped[0]);
    }

    private static void readRows(JsonReader json, Set<String> into, int[] skipped) throws IOException {
        json.beginArray();
        int rows = 0;
        while (json.hasNext()) {
            if (++rows > MAX_ROWS) throw new Rejected(Reason.UNSUPPORTED);
            if (json.peek() != JsonToken.BEGIN_OBJECT) {
                json.skipValue();
                skipped[0]++;
                continue;
            }
            String link = null;
            json.beginObject();
            while (json.hasNext()) {
                String key = json.nextName();
                if (("link".equals(key) || "Link".equals(key)) && json.peek() == JsonToken.STRING) {
                    link = json.nextString();
                } else {
                    json.skipValue();
                }
            }
            json.endObject();
            String id = link == null ? null : WatchHistoryImport.videoId(link);
            if (id == null) skipped[0]++;
            else into.add(id);
        }
        json.endArray();
    }

    /** A byte order mark some editors leave at the start would read as a stray character. */
    private static void skipByteOrderMark(Capped capped) throws IOException {
        capped.mark(3);
        byte[] head = new byte[3];
        int read = 0;
        while (read < 3) {
            int count = capped.read(head, read, 3 - read);
            if (count < 0) break;
            read += count;
        }
        if (read == 3 && (head[0] & 0xff) == 0xef && (head[1] & 0xff) == 0xbb && (head[2] & 0xff) == 0xbf) return;
        capped.reset();
    }

    /** Stops the read past {@link #MAX_BYTES}. */
    private static final class Capped extends FilterInputStream {
        private long count;
        private long marked;

        Capped(InputStream in) {
            super(new java.io.BufferedInputStream(in, 64 * 1024));
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) counted(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) counted(read);
            return read;
        }

        @Override
        public synchronized void mark(int limit) {
            super.mark(limit);
            marked = count;
        }

        @Override
        public synchronized void reset() throws IOException {
            super.reset();
            count = marked;
        }

        private void counted(int bytes) throws Rejected {
            count += bytes;
            int limit = maxBytesForTests > 0 ? maxBytesForTests : MAX_BYTES;
            if (count > limit) throw new Rejected(Reason.TOO_LARGE);
        }
    }
}

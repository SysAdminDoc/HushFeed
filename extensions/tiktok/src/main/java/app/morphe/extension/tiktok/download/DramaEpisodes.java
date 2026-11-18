/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.comment.AccountCountry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A short drama's episodes (#444), from the episode list TikTok's own player pages through.
 *
 * <p>A drama episode carries its drama's id in {@code paid_content_info.paid_collection_id}.
 * TikTok answers {@link #PATH} for that id with a page of episodes in order, each one a whole
 * post ({@code aweme}) next to its place in the drama ({@code seq_id}), and says in
 * {@code page_info} whether more pages follow. The request goes out signed through TikTok's own
 * client, the way its raw GETs do, and each post is read by TikTok's own API Gson, so a saved
 * episode is the same post TikTok would play.
 *
 * <p>An episode the account can't watch yet comes back as a short preview with
 * {@code is_partial_preview} set. Where the reply also carries the whole episode it is used;
 * otherwise the row says it's a preview, and saving it saves what TikTok sent.
 */
final class DramaEpisodes {
    static final String PATH = "/tiktok/v1/paid_content/collection/video";
    static final int PAGE_SIZE = 20;
    /** At most this many pages a drama: 600 episodes. */
    static final int MAX_PAGES = 30;
    /** TikTok's API Gson, with the type adapters its network replies are read with. */
    private static final String GSON_HOLDER = "com.ss.android.ugc.aweme.utils.GsonHolder";
    private static final String GSON = "com.google.gson.Gson";
    private static final String AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme";

    /** One request. The tests answer with canned pages. */
    interface Source {
        String get(String path, Map<String, String> query) throws Exception;
    }

    /** Turns one episode's post JSON into TikTok's post. */
    interface Reader {
        Object post(String json) throws Exception;
    }

    static volatile Source sourceForTests;
    static volatile Reader readerForTests;
    private static volatile Reader reader;

    private DramaEpisodes() {
    }

    /** One episode: the post, its number in the drama, and whether TikTok sent only a preview. */
    static final class Episode {
        final Object post;
        final String id;
        final int number;
        final boolean preview;

        Episode(Object post, String id, int number, boolean preview) {
            this.post = post;
            this.id = id;
            this.number = number;
            this.preview = preview;
        }
    }

    /** One reply: its episodes, whether more follow, the page it says it is, and the drama's size. */
    static final class Page {
        final List<Episode> episodes;
        final boolean hasNext;
        final int page;
        final int total;

        Page(List<Episode> episodes, boolean hasNext, int page, int total) {
            this.episodes = episodes;
            this.hasNext = hasNext;
            this.page = page;
            this.total = total;
        }
    }

    /** The drama's id on an episode of it, or 0 for any other post. */
    static long collectionId(Object post) {
        Object info = Reflect.property(post, "getMPaidContentInfo", "mPaidContentInfo");
        Object id = Reflect.property(info, "getPaidCollectionId", "paidCollectionId");
        return id instanceof Number ? ((Number) id).longValue() : 0L;
    }

    /** The drama's name on an episode of it, or null. */
    static String name(Object post) {
        Object info = Reflect.property(post, "getMPaidContentInfo", "mPaidContentInfo");
        String name = Reflect.string(info, "getCollectionName", "collectionName");
        return name == null || name.trim().isEmpty() ? null : name.trim();
    }

    /**
     * Every episode TikTok lists for the drama, in episode order. Pages are asked for in turn
     * until TikTok says there are no more, a page brings nothing new, or {@link #MAX_PAGES}.
     *
     * @throws IOException when the first page can't be had; a later page that fails ends the
     *     list with what came before it.
     */
    static List<Episode> load(long collectionId) throws Exception {
        List<Episode> episodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int requested = 0;
        for (int pages = 0; pages < MAX_PAGES; pages++) {
            Page page;
            try {
                page = page(source().get(PATH, query(collectionId, requested)), reader());
            } catch (Exception failure) {
                if (episodes.isEmpty()) throw failure;
                break;
            }
            int added = 0;
            for (Episode episode : page.episodes) {
                if (seen.add(episode.id)) {
                    episodes.add(episode);
                    added++;
                }
            }
            if (!page.hasNext) break;
            // TikTok's list starts at page 1 now: page 0 is empty and says more follow (#463).
            // Any later page that adds nothing ends the list.
            if (added == 0 && pages > 0) break;
            // TikTok says which page it sent; the next is the one after, whichever way it counts.
            requested = Math.max(requested, page.page) + 1;
        }
        Collections.sort(episodes, (left, right) -> Integer.compare(left.number, right.number));
        return episodes;
    }

    static Map<String, String> query(long collectionId, int page) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("collection_id", String.valueOf(collectionId));
        query.put("requested_page", String.valueOf(page));
        // VideoListType.VIDEO_LIST_TYPE_SDP, the drama's own page with every episode in order.
        query.put("video_list_type", "0");
        query.put("page_size", String.valueOf(PAGE_SIZE));
        query.put("is_trailer_paginated", "false");
        query.put("need_collection_detail", "false");
        return query;
    }

    /** One reply read. A refusal from TikTok (a non-zero status) throws with its message. */
    static Page page(String json, Reader reader) throws Exception {
        if (json == null) throw new IOException("no reply");
        JSONObject root = new JSONObject(json);
        int status = root.optInt("status_code", 0);
        if (status != 0) throw new IOException("TikTok said " + status + " " + root.optString("status_msg", ""));
        List<Episode> episodes = new ArrayList<>();
        StringBuilder leftOut = new StringBuilder();
        JSONArray videos = root.optJSONArray("paid_videos");
        int rows = videos == null ? 0 : videos.length();
        for (int index = 0; index < rows; index++) {
            JSONObject item = videos.optJSONObject(index);
            if (item == null || item.optBoolean("is_intro", false)) continue;
            Episode episode = null;
            // The whole episode first, then the post as listed: a locked episode's whole-episode
            // slot can come back without a post in it (#463).
            for (String slot : POST_SLOTS) {
                episode = episode(item.optJSONObject(slot), item, index, reader);
                if (episode != null) break;
            }
            if (episode != null) {
                episodes.add(episode);
            } else {
                leftOut.append(" #").append(item.optInt("seq_id", index + 1)).append(' ').append(keys(item));
            }
        }
        JSONObject info = root.optJSONObject("page_info");
        boolean hasNext = info != null && info.optBoolean("has_next", false);
        int page = info == null ? -1 : info.optInt("curr_page", -1);
        int kept = episodes.size();
        Logger.printDebug(() -> "Drama episodes page " + page + ": " + kept + " of " + rows + " rows"
                + (leftOut.length() == 0 ? "" : ", left out" + leftOut));
        return new Page(episodes, hasNext, page, root.optInt("num_videos", 0));
    }

    /** Where an episode's post can be in its row, in the order they're tried. */
    private static final String[] POST_SLOTS = {"full_paid_videos_for_partial_preview", "aweme"};

    /** The row's episode from one post in it, or null when that slot has no readable post. */
    private static Episode episode(JSONObject post, JSONObject item, int index, Reader reader) throws Exception {
        String id = post == null ? "" : post.optString("aweme_id", "");
        if (id.isEmpty()) return null;
        Object read = reader.post(post.toString());
        if (read == null) return null;
        JSONObject paid = post.optJSONObject("paid_content_info");
        boolean preview = paid != null && paid.optBoolean("is_partial_preview", false);
        return new Episode(read, id, number(item, paid, index), preview);
    }

    /** A row's field names, for the log line about rows left out. */
    private static String keys(JSONObject item) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = item.keys(); it.hasNext(); ) names.add(it.next());
        Collections.sort(names);
        return names.toString();
    }

    /** The episode's number: its place in the drama, else the number on it, else its row. */
    private static int number(JSONObject item, JSONObject paid, int index) {
        int seq = item.optInt("seq_id", 0);
        if (seq > 0) return seq;
        if (paid != null) {
            try {
                int number = Integer.parseInt(paid.optString("episode_num", "").trim());
                if (number > 0) return number;
            } catch (NumberFormatException notANumber) {
                // Fall through to the row.
            }
        }
        return index + 1;
    }

    private static Source source() {
        Source forTests = sourceForTests;
        return forTests != null ? forTests : AccountCountry::fetch;
    }

    private static Reader reader() throws ReflectiveOperationException {
        Reader forTests = readerForTests;
        if (forTests != null) return forTests;
        Reader found = reader;
        if (found != null) return found;
        Object gson = tiktokGson();
        Method fromJson = fromJson(gson.getClass());
        Class<?> aweme = Class.forName(AWEME);
        found = json -> fromJson.invoke(gson, json, aweme);
        reader = found;
        return found;
    }

    /** TikTok's API Gson, or a plain one where this build has no holder. */
    private static Object tiktokGson() throws ReflectiveOperationException {
        try {
            Class<?> holder = Class.forName(GSON_HOLDER);
            Object gson = holder.getMethod("getGson").invoke(holder.getConstructor().newInstance());
            if (gson != null) return gson;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError missing) {
            // A plain Gson still reads every named field.
        }
        return Class.forName(GSON).getConstructor().newInstance();
    }

    /** Gson's fromJson(String, Class), found by shape since its name is shortened. */
    static Method fromJson(Class<?> type) throws NoSuchMethodException {
        Method found = null;
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getReturnType() != Object.class || parameters.length != 2
                    || parameters[0] != String.class || parameters[1] != Class.class) continue;
            if (found != null) throw new NoSuchMethodException(type.getName() + " has two fromJson shapes");
            found = method;
        }
        if (found == null) throw new NoSuchMethodException(type.getName() + " has no fromJson");
        return found;
    }

    /** So a test starts without what an earlier one found. */
    static void resetForTests() {
        reader = null;
    }
}

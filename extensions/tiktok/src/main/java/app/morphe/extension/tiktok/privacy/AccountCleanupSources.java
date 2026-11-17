/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.privacy.CleanupRunner.End;
import app.morphe.extension.tiktok.privacy.CleanupRunner.Failure;
import app.morphe.extension.tiktok.privacy.CleanupRunner.Page;
import app.morphe.extension.tiktok.privacy.CleanupRunner.Source;

/**
 * The account's reposts, likes and Favorites as lists a {@link CleanupRunner} can empty, read
 * and changed through TikTok's own request services so every request is signed and sent the
 * way TikTok's own screens send it.
 *
 * <p>The repost and like services have real names. The liked-videos list and the Favorites
 * service are renamed in every build, so the patch fills {@link #likedListService()} and
 * {@link #favoritesService()} with the code TikTok itself uses to make them.</p>
 *
 * <p>Comments have no service of their own in the app: the comment history is a web page. Its
 * list and the comment panel's delete go out through TikTok's general request service instead,
 * made on the same client as the liked-videos list (#442).</p>
 */
public final class AccountCleanupSources {
    /** Items asked for a page, what TikTok's own lists ask for. */
    static final int PAGE = 20;

    static final String REPOST_LIST = "tiktok/v1/upvote/item/list";
    static final String REPOST_DELETE = "tiktok/v1/upvote/delete";
    static final String LIKED_LIST = "/aweme/v1/aweme/favorite/";
    static final String DIGG = "/aweme/v1/commit/item/digg/";
    static final String FAVORITES_LIST = "/aweme/v1/aweme/listcollection/";
    static final String COLLECT = "/aweme/v1/aweme/collect/";
    static final String REMOVE_UNAVAILABLE = "/aweme/v1/aweme/collect/removeinvalid";
    /** The comment history page's list of the comments you've posted. */
    static final String COMMENT_HISTORY = "/tiktok/comment/history/list/v2/";
    /** What the comment panel's Delete sends. */
    static final String COMMENT_DELETE = "/aweme/v1/comment/delete/";
    /** Comments asked for a page, what the comment history page asks for. */
    static final int COMMENT_PAGE = 15;

    static final String UPVOTE_API = "com.ss.android.ugc.aweme.upvote.api.UpvoteApi";
    static final String STORY_API = "com.ss.android.ugc.aweme.story.api.StoryApi";

    /** What a run empties. */
    public enum Kind { REPOSTS, LIKES, FAVORITES, COMMENTS }

    /** The services a run talks to, so a test can stand in for TikTok's. */
    interface Services {
        Object reposts() throws Failure;

        Object likedList() throws Failure;

        Object likes() throws Failure;

        Object favorites() throws Failure;

        Object unavailableFavorites() throws Failure;

        /** TikTok's general request service, for requests no service of the app declares. */
        Object network() throws Failure;
    }

    /** One of your comments as the comment history lists it. */
    public static final class Comment {
        public final String cid;
        public final String text;
        /** When it was posted, in seconds, or 0 when the list didn't say. */
        public final long postedSeconds;

        Comment(String cid, String text, long postedSeconds) {
            this.cid = cid;
            this.text = text;
            this.postedSeconds = postedSeconds;
        }
    }

    /** One page of the comment history: its comments, where the next page starts and whether there is one. */
    public static final class Comments {
        public final List<Comment> comments;
        public final long next;
        public final boolean more;

        Comments(List<Comment> comments, long next, boolean more) {
            this.comments = comments;
            this.next = next;
            this.more = more;
        }
    }

    private AccountCleanupSources() {
    }

    /**
     * Filled by the patch: TikTok's liked-videos list service, made the way TikTok makes it.
     * Null on an unpatched build.
     */
    public static Object likedListService() {
        return null;
    }

    /** Filled by the patch: TikTok's Favorites service, which lists and removes. Null unpatched. */
    public static Object favoritesService() {
        return null;
    }

    /**
     * Filled by the patch: the service behind the Favorites page's "unavailable" notice, whose
     * Remove clears saved videos that were deleted since. Null unpatched.
     */
    public static Object unavailableFavoritesService() {
        return null;
    }

    /**
     * Filled by the patch: TikTok's general request service ({@code doGet}, {@code doPost}) on
     * the API host, made the way the liked-videos list service is. Null unpatched.
     */
    public static Object networkService() {
        return null;
    }

    static final Services TIKTOK = new Services() {
        @Override
        public Object reposts() throws Failure {
            return TikTokCalls.singleton(UPVOTE_API);
        }

        @Override
        public Object likedList() throws Failure {
            return built(likedListService(), "liked videos");
        }

        @Override
        public Object likes() throws Failure {
            return TikTokCalls.singleton(STORY_API);
        }

        @Override
        public Object favorites() throws Failure {
            return built(favoritesService(), "Favorites");
        }

        @Override
        public Object unavailableFavorites() throws Failure {
            return built(unavailableFavoritesService(), "unavailable Favorites");
        }

        @Override
        public Object network() throws Failure {
            return built(networkService(), "general request");
        }
    };

    private static Object built(Object service, String what) throws Failure {
        if (service == null) throw new Failure(End.UNSUPPORTED, "no " + what + " service");
        return service;
    }

    /**
     * The list {@code kind} names for the account signed in now, or a failure when nobody is.
     * Every request first checks the account is still the same one, so a switch mid-run stops it.
     */
    static Source sourceFor(Kind kind, Services services) throws Failure {
        String uid = SignedInUser.id();
        if (uid == null) throw new Failure(End.STOPPED, "signed out");
        Source source;
        switch (kind) {
            case REPOSTS:
                source = reposts(services, uid);
                break;
            case LIKES:
                String secUid = SignedInUser.secId();
                if (secUid == null) throw new Failure(End.UNSUPPORTED, "no secure uid");
                source = likes(services, secUid);
                break;
            case COMMENTS:
                source = comments(services);
                break;
            default:
                source = favorites(services);
                break;
        }
        return sameAccount(uid, source);
    }

    /** The comments picked from the history, for the account signed in now. */
    static Source pickedCommentsFor(Collection<String> cids, Services services) throws Failure {
        String uid = SignedInUser.id();
        if (uid == null) throw new Failure(End.STOPPED, "signed out");
        return sameAccount(uid, pickedComments(services, cids));
    }

    static Source sameAccount(String uid, Source source) {
        return new Source() {
            @Override
            public Page fetch(long cursor) throws Failure {
                check();
                return source.fetch(cursor);
            }

            @Override
            public void remove(String id) throws Failure {
                check();
                source.remove(id);
            }

            @Override
            public CleanupRunner.Request last() {
                CleanupRunner.Request last = source.last();
                if (last == null) return null;
                return () -> {
                    check();
                    last.send();
                };
            }

            private void check() throws Failure {
                if (!uid.equals(SignedInUser.id())) throw new Failure(End.STOPPED, "the account changed");
            }
        };
    }

    /**
     * Reposts are listed by offset, as the profile's Reposts tab reads them, and taken back with
     * the delete TikTok's own Remove repost sends for a video.
     */
    static Source reposts(Services services, String uid) {
        return new Source() {
            @Override
            public Page fetch(long offset) throws Failure {
                Map<String, Object> query = new HashMap<>();
                query.put("user_id", uid);
                query.put("offset", offset);
                query.put("count", PAGE);
                query.put("scene", 0);
                Object body = TikTokCalls.await(TikTokCalls.call(
                        services.reposts(), REPOST_LIST, "getRepostVideoList", query));
                TikTokCalls.checkStatus(body);
                return new Page(ids(TikTokCalls.listIn(body, "repostList")), offset + PAGE,
                        TikTokCalls.flagIn(body, "hasMore"));
            }

            @Override
            public void remove(String id) throws Failure {
                Map<String, Object> form = new HashMap<>();
                form.put("item_id", id);
                // A video, as opposed to a comment (1).
                form.put("carrier_type", 0);
                Object body = TikTokCalls.await(TikTokCalls.call(
                        services.reposts(), REPOST_DELETE, "deleteUpvote", form));
                TikTokCalls.checkStatus(body);
            }
        };
    }

    /**
     * Liked videos are listed by cursor the way the profile's Liked tab reads them, with the
     * videos that are gone left out (they can't be unliked from here either), and unliked with
     * the same request the heart sends.
     */
    static Source likes(Services services, String secUid) {
        return new Source() {
            @Override
            public Page fetch(long cursor) throws Failure {
                Map<String, Object> query = new HashMap<>();
                query.put("max_cursor", cursor);
                query.put("sec_user_id", secUid);
                query.put("count", PAGE);
                query.put("invalid_item_count", 0);
                query.put("is_hiding_invalid_item", 1);
                Object body = TikTokCalls.await(TikTokCalls.call(
                        services.likedList(), LIKED_LIST, null, query));
                TikTokCalls.checkStatus(body);
                return new Page(ids(TikTokCalls.listIn(body, "items")),
                        TikTokCalls.longIn(body, "maxCursor", cursor), TikTokCalls.flagIn(body, "hasMore"));
            }

            @Override
            public void remove(String id) throws Failure {
                Map<String, Object> query = new HashMap<>();
                query.put("aweme_id", id);
                // 1 likes, 0 takes the like back.
                query.put("type", 0);
                Object body = TikTokCalls.await(TikTokCalls.call(services.likes(), DIGG, "likeFeed", query));
                TikTokCalls.checkStatus(body);
            }
        };
    }

    /**
     * Favorites are listed and removed through the one service the Favorites page uses. Saved
     * videos that were deleted since never come back from that list, and the page shows them as
     * a notice with a Remove button instead, so the run ends with the request that Remove sends.
     */
    static Source favorites(Services services) {
        return new Source() {
            @Override
            public Page fetch(long cursor) throws Failure {
                Map<String, Object> query = new HashMap<>();
                query.put("cursor", cursor);
                query.put("count", PAGE);
                Object body = TikTokCalls.await(TikTokCalls.call(
                        services.favorites(), FAVORITES_LIST, null, query));
                TikTokCalls.checkStatus(body);
                return new Page(ids(TikTokCalls.listIn(body, "items")),
                        TikTokCalls.longIn(body, "maxCursor", cursor), TikTokCalls.flagIn(body, "hasMore"));
            }

            @Override
            public void remove(String id) throws Failure {
                Map<String, Object> query = new HashMap<>();
                query.put("aweme_id", id);
                // 1 adds to Favorites, 0 removes.
                query.put("action", 0);
                Object body = TikTokCalls.await(TikTokCalls.call(services.favorites(), COLLECT, null, query));
                TikTokCalls.checkStatus(body);
            }

            @Override
            public CleanupRunner.Request last() {
                return () -> {
                    Map<String, Object> query = new HashMap<>();
                    // Where TikTok's own Remove starts once it has cleared them: the whole list.
                    query.put("cursor", 0L);
                    Object body = TikTokCalls.await(TikTokCalls.call(
                            services.unavailableFavorites(), REMOVE_UNAVAILABLE, null, query));
                    TikTokCalls.checkStatus(body);
                };
            }
        };
    }

    /**
     * Comments are listed by offset the way the comment history page reads them, newest first,
     * and deleted with the request the comment panel's Delete sends. Comments TikTok already
     * took down are listed apart and left alone.
     */
    static Source comments(Services services) {
        return new Source() {
            @Override
            public Page fetch(long offset) throws Failure {
                Comments page = commentPage(services, offset);
                List<String> cids = new ArrayList<>();
                for (Comment comment : page.comments) cids.add(comment.cid);
                return new Page(cids, page.next, page.more);
            }

            @Override
            public void remove(String cid) throws Failure {
                deleteComment(services, cid);
            }
        };
    }

    /** The comments picked from the history, as one page that's gone once each is deleted. */
    static Source pickedComments(Services services, Collection<String> cids) {
        List<String> picked = Collections.unmodifiableList(new ArrayList<>(cids));
        return new Source() {
            @Override
            public Page fetch(long cursor) {
                return new Page(picked, cursor, false);
            }

            @Override
            public void remove(String cid) throws Failure {
                deleteComment(services, cid);
            }
        };
    }

    /** One page of the comment history from {@code offset}, newest first. */
    static Comments commentPage(Services services, long offset) throws Failure {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("offset", Long.toString(offset));
        query.put("count", Integer.toString(COMMENT_PAGE));
        // Newest first, as the history page opens.
        query.put("sort_type", "0");
        JSONObject body = answered(get(services.network(), COMMENT_HISTORY, query));
        List<Comment> comments = new ArrayList<>();
        JSONArray list = body.optJSONArray("comment_list");
        if (list != null) {
            for (int index = 0; index < list.length(); index++) {
                JSONObject entry = list.optJSONObject(index);
                if (entry == null) continue;
                String cid = entry.optString("cid", "");
                if (cid.isEmpty()) continue;
                comments.add(new Comment(cid, entry.optString("text", ""), entry.optLong("create_time", 0L)));
            }
        }
        long next = body.has("offset") ? body.optLong("offset", offset) : offset + COMMENT_PAGE;
        return new Comments(comments, next, flag(body.opt("has_more")));
    }

    static void deleteComment(Services services, String cid) throws Failure {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("cid", cid);
        answered(post(services.network(), COMMENT_DELETE, form));
    }

    /** A GET through the general request service, with the app's usual query added. */
    private static Object get(Object network, String path, Map<String, String> query) throws Failure {
        // addCommonParam, maxLength (none), url, query, headers, extra info.
        return TikTokCalls.await(invoke(network, "doGet", true, -1, path, query, null, null));
    }

    /** A form POST through the general request service. */
    private static Object post(Object network, String path, Map<String, String> form) throws Failure {
        // maxLength (none), url, query, form fields, headers, extra info.
        return TikTokCalls.await(invoke(network, "doPost", -1, path,
                new HashMap<String, String>(), form, null, null));
    }

    private static Object invoke(Object service, String name, Object... arguments) throws Failure {
        Method method = null;
        for (Method candidate : service.getClass().getMethods()) {
            if (candidate.getName().equals(name) && candidate.getParameterTypes().length == arguments.length) {
                method = candidate;
                break;
            }
        }
        if (method == null) throw new Failure(End.UNSUPPORTED, "no " + name + " on " + service.getClass().getName());
        try {
            method.setAccessible(true);
            return method.invoke(service, arguments);
        } catch (InvocationTargetException thrown) {
            throw TikTokCalls.failureOf(thrown.getTargetException());
        } catch (IllegalAccessException | IllegalArgumentException wrongShape) {
            throw new Failure(End.UNSUPPORTED, "couldn't call " + name + ": " + wrongShape);
        }
    }

    /** A JSON answer whose status code is zero, or the refusal it says instead. */
    static JSONObject answered(Object body) throws Failure {
        JSONObject json;
        try {
            json = new JSONObject(String.valueOf(body));
        } catch (JSONException notJson) {
            throw new Failure(End.REFUSED, "the answer wasn't JSON");
        }
        int status = json.optInt("status_code", 0);
        if (status != 0) {
            throw new Failure(End.REFUSED, "status code " + status + " " + json.optString("status_msg", ""));
        }
        return json;
    }

    private static boolean flag(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return value instanceof String && ("true".equals(value) || "1".equals(value));
    }

    static List<String> ids(List<?> items) {
        List<String> ids = new ArrayList<>();
        if (items == null) return ids;
        for (Object item : items) {
            String id = TikTokCalls.idOf(item);
            if (id != null) ids.add(id);
        }
        return ids;
    }
}

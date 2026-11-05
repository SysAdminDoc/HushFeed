/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import java.util.ArrayList;
import java.util.HashMap;
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

    static final String UPVOTE_API = "com.ss.android.ugc.aweme.upvote.api.UpvoteApi";
    static final String STORY_API = "com.ss.android.ugc.aweme.story.api.StoryApi";

    /** What a run empties. */
    public enum Kind { REPOSTS, LIKES, FAVORITES }

    /** The services a run talks to, so a test can stand in for TikTok's. */
    interface Services {
        Object reposts() throws Failure;

        Object likedList() throws Failure;

        Object likes() throws Failure;

        Object favorites() throws Failure;

        Object unavailableFavorites() throws Failure;
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
            default:
                source = favorites(services);
                break;
        }
        return sameAccount(uid, source);
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

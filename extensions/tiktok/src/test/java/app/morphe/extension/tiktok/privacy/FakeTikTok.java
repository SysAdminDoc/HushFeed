package app.morphe.extension.tiktok.privacy;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TikTok's request services the way a test stands them in: interfaces whose methods carry the
 * request path and every parameter's name in annotations with a {@code value()}, answered by a
 * proxy, with answers shaped as a call to execute or an Rx source to subscribe to.
 */
final class FakeTikTok {
    private FakeTikTok() {
    }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface Get { String value(); }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface Post { String value(); }

    /** A marker with no value(), as a form body's is. */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD)
    public @interface FormUrlEncoded { }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
    public @interface Query { String value(); }

    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
    public @interface Field { String value(); }

    /** A parameter that has no name, such as a header map. */
    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER)
    public @interface HeaderMap { }

    public interface UpvoteApi {
        @Get("tiktok/v1/upvote/item/list")
        Object getRepostVideoList(@Query("user_id") String userId, @Query("offset") long offset,
                @Query("count") int count, @Query("scene") int scene);

        /** A longer variant on the same path, which a call without a preferred name passes over. */
        @Get("/tiktok/v1/upvote/item/list")
        Object getRepostVideoListForCreator(@Query("user_id") String userId, @Query("offset") long offset,
                @Query("count") int count, @Query("scene") int scene, @Query("creator_id") String creator);

        @FormUrlEncoded
        @Post("tiktok/v1/upvote/delete")
        Object deleteUpvote(@Field("item_id") String itemId, @Field("carrier_type") int carrierType,
                @HeaderMap Map<String, String> headers);
    }

    public interface LikedListApi {
        @Get("/aweme/v1/aweme/favorite/")
        Object likedVideos(@Query("max_cursor") long maxCursor, @Query("sec_user_id") String secUid,
                @Query("count") int count, @Query("invalid_item_count") int invalid,
                @Query("is_hiding_invalid_item") int hiding);
    }

    public interface DiggApi {
        @Get("/aweme/v1/commit/item/digg/")
        Object likeFeed(@Query("aweme_id") String awemeId, @Query("type") int type);

        @Get("/aweme/v1/commit/item/digg/")
        Object likeFeedFromChannel(@Query("aweme_id") String awemeId, @Query("type") int type,
                @Query("channel_id") int channel);
    }

    public interface FavoritesApi {
        @Get("/aweme/v1/aweme/listcollection/")
        Object list(@Query("cursor") long cursor, @Query("count") int count);

        @FormUrlEncoded
        @Post("/aweme/v1/aweme/collect/")
        Object collect(@Field("aweme_id") String awemeId, @Field("action") int action);
    }

    /** The Favorites page's "unavailable" notice's Remove, on its own service as in TikTok. */
    public interface UnavailableFavoritesApi {
        @FormUrlEncoded
        @Post("/aweme/v1/aweme/collect/removeinvalid")
        Object removeInvalid(@Field("cursor") long cursor);
    }

    /**
     * The service a post's own Delete goes through, answering with a future as TikTok's
     * ListenableFuture does. The longer variant beside it is the one Explore sends.
     */
    public interface FeedActionApi {
        @Get("/aweme/v1/aweme/delete/")
        Object deleteItem(@Query("aweme_id") String awemeId);

        @Get("/aweme/v1/aweme/delete/")
        Object deleteItemOfType(@Query("aweme_id") String awemeId, @Query("delete_type") int type);
    }

    /** A future that already holds {@code value}, the way an answered request's does. */
    static java.util.concurrent.Future<Object> future(Object value) {
        java.util.concurrent.FutureTask<Object> task = new java.util.concurrent.FutureTask<>(() -> value);
        task.run();
        return task;
    }

    /** What a fake service answers a request with, by method name and named arguments. */
    interface Answer {
        Object answer(String method, Map<String, Object> arguments) throws Throwable;
    }

    /** A service object answering through {@code answer}, logging each request it gets. */
    static <T> T service(Class<T> api, List<String> log, Answer answer) {
        return api.cast(Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, arguments);
                    Map<String, Object> named = new LinkedHashMap<>();
                    Annotation[][] annotations = method.getParameterAnnotations();
                    for (int index = 0; index < annotations.length; index++) {
                        for (Annotation annotation : annotations[index]) {
                            if (annotation instanceof Query) named.put(((Query) annotation).value(), arguments[index]);
                            if (annotation instanceof Field) named.put(((Field) annotation).value(), arguments[index]);
                        }
                    }
                    synchronized (log) {
                        log.add(method.getName() + " " + named);
                    }
                    return answer.answer(method.getName(), named);
                }));
    }

    private static Object objectMethod(Object proxy, Method method, Object[] arguments) {
        switch (method.getName()) {
            case "equals":
                return proxy == arguments[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            default:
                return "fake service";
        }
    }

    /** A Retrofit-like call: execute() answers the response. */
    public static final class Call {
        private final Object response;

        Call(Object response) {
            this.response = response;
        }

        public Object execute() {
            return response;
        }
    }

    /** A response that hands its body out through body(). */
    public static final class Response {
        private final Object body;

        Response(Object body) {
            this.body = body;
        }

        public Object body() {
            return body;
        }
    }

    static Call call(Object body) {
        return new Call(new Response(body));
    }

    /**
     * The response as TikTok's general request service hands it back: no body(), its fields
     * renamed, the body the one held as a plain Object.
     */
    public static final class RenamedResponse {
        private final int LIZ = 200;
        private final Object LIZIZ;

        RenamedResponse(Object body) {
            this.LIZIZ = body;
        }
    }

    /**
     * TikTok's general request service (com.bytedance.ttnet.INetworkApi): any path, with the
     * query and form as maps, its answer's body a JSON string. It carries no request
     * annotations, so each request logs its path and maps.
     */
    public static final class Network {
        private final Account account;

        Network(Account account) {
            this.account = account;
        }

        public Call doGet(boolean addCommonParam, int maxLength, String url, Map<String, String> query,
                List<Object> headers, Object extraInfo) {
            account.log("doGet " + url + " " + query + (addCommonParam ? "" : " without the app's query"));
            return new Call(new RenamedResponse(account.commentHistory(url, query)));
        }

        public Call doPost(int maxLength, String url, Map<String, String> query, Map<String, String> fields,
                List<Object> headers, Object extraInfo) {
            account.log("doPost " + url + " " + fields);
            if ("/aweme/v1/commit/follow/user/".equals(url)) return new Call(new RenamedResponse(account.unfollow(fields)));
            return new Call(new RenamedResponse(account.deleteComment(url, fields)));
        }
    }

    /** Rx's observer shape: the cleanup subscribes with a proxy of it. */
    public interface Observer {
        void onSubscribe(Object disposable);

        void onNext(Object value);

        void onError(Throwable error);

        void onComplete();
    }

    public interface ObservableSource {
        void subscribe(Observer observer);
    }

    /** An Rx source that answers {@code value} or fails with {@code error}, now or from another thread. */
    public static final class Single implements ObservableSource {
        private final Object value;
        private final Throwable error;
        private final long laterMs;

        Single(Object value, Throwable error, long laterMs) {
            this.value = value;
            this.error = error;
            this.laterMs = laterMs;
        }

        @Override
        public void subscribe(Observer observer) {
            observer.onSubscribe(null);
            Runnable answer = () -> {
                if (error != null) {
                    observer.onError(error);
                    return;
                }
                if (value != null) observer.onNext(value);
                observer.onComplete();
            };
            if (laterMs <= 0) {
                answer.run();
                return;
            }
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(laterMs);
                } catch (InterruptedException ignored) {
                    return;
                }
                answer.run();
            });
            thread.setDaemon(true);
            thread.start();
        }
    }

    static Single single(Object value) {
        return new Single(value, null, 0);
    }

    /** A video as list bodies hold it. */
    public static final class Video {
        public final String aid;

        Video(String aid) {
            this.aid = aid;
        }
    }

    /** An entry that holds a video rather than being one. */
    public static final class Entry {
        private final Video aweme;

        Entry(String aid) {
            this.aweme = new Video(aid);
        }

        public Video getAweme() {
            return aweme;
        }
    }

    /** The reposts list body. */
    public static final class RepostList {
        public int status_code;
        public List<Object> repostList;
        public boolean hasMore;
    }

    /** The liked and Favorites list bodies. */
    public static final class VideoList {
        public int status_code;
        public List<Object> items;
        public long maxCursor;
        public int hasMore;
    }

    public static final class Status {
        public int status_code;

        Status(int code) {
            status_code = code;
        }
    }

    /**
     * An account with reposts, likes, Favorites and comments behind the services a cleanup uses. Lists
     * are read by offset; a removal takes the item off. Removing {@link #refuse} answers a
     * non-zero status code instead. Unavailable Favorites never show in the Favorites list and
     * only go with the request that clears them all.
     */
    static final class Account implements AccountCleanupSources.Services {
        final List<String> reposts = new ArrayList<>();
        final List<String> likes = new ArrayList<>();
        final List<String> favorites = new ArrayList<>();
        final List<String> unavailable = new ArrayList<>();
        /** Your own posts, which a post's Delete takes off the account. */
        final List<String> posts = new ArrayList<>();
        /** Your comments, newest first, as the comment history lists them. */
        final List<String> comments = new ArrayList<>();
        /** The accounts you follow, by the id an unfollow names them with. */
        final List<String> following = new ArrayList<>();
        /** The follow status the unfollow answers with; 0 is the unfollow taking. */
        volatile int followStatusAfterUnfollow;
        final List<String> requests = new ArrayList<>();
        volatile String refuse;
        volatile boolean refuseUnavailable;
        /** The status code the comment history answers with; zero lists. */
        volatile int commentHistoryStatus;

        Account withReposts(int count) {
            for (int index = 0; index < count; index++) reposts.add("r" + index);
            return this;
        }

        Account withLikes(int count) {
            for (int index = 0; index < count; index++) likes.add("l" + index);
            return this;
        }

        Account withFavorites(int count) {
            for (int index = 0; index < count; index++) favorites.add("f" + index);
            return this;
        }

        Account withUnavailableFavorites(int count) {
            for (int index = 0; index < count; index++) unavailable.add("u" + index);
            return this;
        }

        Account following(String... ids) {
            for (String id : ids) following.add(id);
            return this;
        }

        Account withComments(int count) {
            for (int index = 0; index < count; index++) comments.add("c" + index);
            return this;
        }

        Account withPosts(int count) {
            for (int index = 0; index < count; index++) posts.add("p" + index);
            return this;
        }

        void log(String request) {
            synchronized (requests) {
                requests.add(request);
            }
        }

        @Override
        public Object network() {
            return new Network(this);
        }

        /**
         * A page of the comment history by offset, as JSON. Each page also lists one comment
         * TikTok took down, apart from the rest, which is never yours to delete.
         */
        String commentHistory(String url, Map<String, String> query) {
            if (!"/tiktok/comment/history/list/v2/".equals(url)) throw new AssertionError("asked " + url);
            if (commentHistoryStatus != 0) {
                return "{\"status_code\":" + commentHistoryStatus + ",\"status_msg\":\"no\"}";
            }
            int offset = Integer.parseInt(query.get("offset"));
            int count = Integer.parseInt(query.get("count"));
            StringBuilder list = new StringBuilder();
            for (String cid : page(comments, offset, count)) {
                if (list.length() > 0) list.append(',');
                list.append("{\"cid\":\"").append(cid).append("\",\"text\":\"said ").append(cid)
                        .append("\",\"create_time\":1763000000}");
            }
            return "{\"status_code\":0,\"comment_list\":[" + list + "],"
                    + "\"illegal_comment_list\":[{\"cid\":\"taken-down\",\"text\":\"gone\"}],"
                    + "\"offset\":" + (offset + count) + ",\"has_more\":"
                    + (offset + count < comments.size() ? 1 : 0) + "}";
        }

        /** TikTok's unfollow: the account named by its secure id, or its plain id when it has none. */
        String unfollow(Map<String, String> fields) {
            if (!"0".equals(fields.get("type"))) throw new AssertionError("not an unfollow: " + fields);
            String who = fields.containsKey("sec_user_id") ? fields.get("sec_user_id") : fields.get("user_id");
            Status status = remove(following, who);
            return "{\"status_code\":" + status.status_code + ",\"follow_status\":" + followStatusAfterUnfollow + "}";
        }

        String deleteComment(String url, Map<String, String> fields) {
            if (!"/aweme/v1/comment/delete/".equals(url)) throw new AssertionError("posted " + url);
            Status status = remove(comments, fields.get("cid"));
            return "{\"status_code\":" + status.status_code + "}";
        }

        @Override
        public Object reposts() {
            return service(UpvoteApi.class, requests, (method, arguments) -> {
                if (method.equals("getRepostVideoList")) {
                    int offset = (int) (long) (Long) arguments.get("offset");
                    int count = (Integer) arguments.get("count");
                    RepostList body = new RepostList();
                    body.repostList = new ArrayList<>();
                    for (String id : page(reposts, offset, count)) body.repostList.add(new Entry(id));
                    body.hasMore = offset + count < reposts.size();
                    return call(body);
                }
                if (method.equals("deleteUpvote")) return call(remove(reposts, (String) arguments.get("item_id")));
                throw new AssertionError("asked " + method);
            });
        }

        @Override
        public Object likedList() {
            return service(LikedListApi.class, requests, (method, arguments) ->
                    single(videoList(likes, (int) (long) (Long) arguments.get("max_cursor"),
                            (Integer) arguments.get("count"))));
        }

        @Override
        public Object likes() {
            return service(DiggApi.class, requests, (method, arguments) ->
                    single(remove(likes, (String) arguments.get("aweme_id"))));
        }

        @Override
        public Object favorites() {
            return service(FavoritesApi.class, requests, (method, arguments) -> {
                if (method.equals("list")) {
                    return call(videoList(favorites, (int) (long) (Long) arguments.get("cursor"),
                            (Integer) arguments.get("count")));
                }
                return call(remove(favorites, (String) arguments.get("aweme_id")));
            });
        }

        @Override
        public Object unavailableFavorites() {
            return service(UnavailableFavoritesApi.class, requests, (method, arguments) -> {
                if (refuseUnavailable) return single(new Status(2149));
                synchronized (unavailable) {
                    unavailable.clear();
                }
                return single(new Status(0));
            });
        }

        /** What a post's Delete fails with instead of answering, as a failed future does. */
        volatile Exception postFailure;

        @Override
        public Object posts() {
            return service(FeedActionApi.class, requests, (method, arguments) -> {
                Exception failure = postFailure;
                if (failure == null) return future(remove(posts, (String) arguments.get("aweme_id")));
                java.util.concurrent.FutureTask<Object> failed = new java.util.concurrent.FutureTask<>(() -> {
                    throw failure;
                });
                failed.run();
                return failed;
            });
        }

        private Status remove(List<String> list, String id) {
            if (id != null && id.equals(refuse)) return new Status(2149);
            synchronized (list) {
                list.remove(id);
            }
            return new Status(0);
        }

        private static List<String> page(List<String> list, int offset, int count) {
            synchronized (list) {
                if (offset >= list.size()) return new ArrayList<>();
                return new ArrayList<>(list.subList(offset, Math.min(list.size(), offset + count)));
            }
        }

        private static VideoList videoList(List<String> list, int cursor, int count) {
            VideoList body = new VideoList();
            body.items = new ArrayList<>();
            for (String id : page(list, cursor, count)) body.items.add(new Video(id));
            body.maxCursor = cursor + count;
            body.hasMore = cursor + count < list.size() ? 1 : 0;
            return body;
        }
    }
}

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
     * An account with reposts, likes and Favorites behind the five services a cleanup uses. Lists
     * are read by offset; a removal takes the item off. Removing {@link #refuse} answers a
     * non-zero status code instead. Unavailable Favorites never show in the Favorites list and
     * only go with the request that clears them all.
     */
    static final class Account implements AccountCleanupSources.Services {
        final List<String> reposts = new ArrayList<>();
        final List<String> likes = new ArrayList<>();
        final List<String> favorites = new ArrayList<>();
        final List<String> unavailable = new ArrayList<>();
        final List<String> requests = new ArrayList<>();
        volatile String refuse;
        volatile boolean refuseUnavailable;

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

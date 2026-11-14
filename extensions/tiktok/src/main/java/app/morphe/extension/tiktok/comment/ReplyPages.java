/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.comment;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads the replies a comment sheet hasn't shown, a page at a time, through the call TikTok
 * makes itself when "View more replies" is tapped, so an export holds whole threads.
 *
 * <p>That call is a Kotlin suspend method on TikTok's comment API interface, found by its
 * endpoint path and its query names rather than by its obfuscated name. A suspend method takes
 * its caller's continuation as its last argument, then either answers at once or hands back
 * COROUTINE_SUSPENDED and answers later through the continuation's {@code resumeWith}. The
 * continuation and the empty coroutine context it reports are built here as proxies, so a plain
 * worker thread can make the call and wait for the answer.
 *
 * <p>Pages are spaced {@link #PAGE_GAP_MS} apart, the whole load stops at
 * {@link #MAX_FETCHED_REPLIES}, and a stop ends it after the page in flight. What didn't load
 * is reported as incomplete, never filled in.
 */
final class ReplyPages {
    /** About 150 pages: past it the export goes ahead with what it has and says so. */
    static final int MAX_FETCHED_REPLIES = 3000;
    static final int PAGE_SIZE = 20;
    static final long PAGE_GAP_MS = 250L;
    static final long PAGE_TIMEOUT_MS = 15_000L;
    /** A page that keeps moving its cursor without ending can't hold the load forever. */
    private static final int MAX_PAGES = 2 * MAX_FETCHED_REPLIES / PAGE_SIZE;

    static final String REPLY_PATH = "/aweme/v1/comment/list/reply/";
    private static final String API_CLASS = "com.ss.android.ugc.aweme.commentv2.commentlist.CommentApiV2";

    private ReplyPages() {}

    /** One page of a thread's replies. */
    static final class Page {
        final List<?> items;
        final long cursor;
        final boolean hasMore;

        Page(List<?> items, long cursor, boolean hasMore) {
            this.items = items == null ? Collections.emptyList() : items;
            this.cursor = cursor;
            this.hasMore = hasMore;
        }
    }

    /** Where pages come from: TikTok's own call, or a test's. */
    interface Fetcher {
        /**
         * Blocks the calling worker thread. Null when no page came back, because TikTok failed,
         * took longer than {@link #PAGE_TIMEOUT_MS} or the load was stopped while it waited.
         */
        Page page(String threadId, String itemId, long cursor, int count, Load load)
                throws InterruptedException;
    }

    /** A thread the sheet holds fewer replies of than TikTok's count for it. */
    static final class Missing {
        final String threadId;
        final long expected;
        final Set<String> known;

        Missing(String threadId, long expected, Set<String> known) {
            this.threadId = threadId;
            this.expected = expected;
            this.known = known;
        }
    }

    /** What a load brought back, per thread in the order the threads were asked for. */
    static final class Outcome {
        final Map<String, List<Object>> byThread = new LinkedHashMap<>();
        int fetched;
        /** Every thread was paged to its end. */
        boolean complete = true;
    }

    interface Listener {
        /** Main thread, after every page. */
        void progress(int fetched, int wanted);

        /** Main thread, once. */
        void finished(Outcome outcome);
    }

    /** A running load. {@link #stop} may come from any thread. */
    static final class Load {
        private volatile boolean stopped;

        void stop() {
            stopped = true;
        }

        boolean stopped() {
            return stopped;
        }
    }

    /** The replies still to load, as TikTok counts them. */
    static int wanted(List<Missing> threads) {
        long wanted = 0;
        for (Missing thread : threads) wanted += Math.max(0, thread.expected - thread.known.size());
        return (int) Math.min(Integer.MAX_VALUE, wanted);
    }

    /** Starts a load on a thread of its own, or returns null when no thread could start. */
    static Load start(Fetcher fetcher, String itemId, List<Missing> threads, Listener listener) {
        Load load = new Load();
        boolean started = Utils.runOnOwnThread("Hushfeed reply pages", () -> {
            Outcome outcome = new Outcome();
            try {
                run(fetcher, itemId, threads, load, outcome,
                        (fetched, wanted) -> Utils.runOnMainThread(() -> listener.progress(fetched, wanted)));
            } catch (InterruptedException interrupted) {
                outcome.complete = false;
                Thread.currentThread().interrupt();
            } catch (RuntimeException | LinkageError error) {
                outcome.complete = false;
                Logger.printException(() -> "Loading comment replies failed", error);
            } finally {
                Utils.runOnMainThread(() -> listener.finished(outcome));
            }
        });
        return started ? load : null;
    }

    interface Progress {
        void update(int fetched, int wanted);
    }

    /** The paging itself, on the calling thread. */
    static void run(Fetcher fetcher, String itemId, List<Missing> threads, Load load, Outcome outcome,
            Progress progress) throws InterruptedException {
        int wanted = wanted(threads);
        int pages = 0;
        for (Missing thread : threads) {
            if (load.stopped() || outcome.fetched >= MAX_FETCHED_REPLIES || pages >= MAX_PAGES) {
                outcome.complete = false;
                return;
            }
            Set<String> seen = new HashSet<>(thread.known);
            List<Object> got = new ArrayList<>();
            outcome.byThread.put(thread.threadId, got);
            long cursor = 0;
            while (true) {
                if (pages > 0) Thread.sleep(PAGE_GAP_MS);
                Page page = fetcher.page(thread.threadId, itemId, cursor, PAGE_SIZE, load);
                pages++;
                if (page == null) {
                    outcome.complete = false;
                    break;
                }
                for (Object reply : page.items) {
                    if (reply == null) continue;
                    String id = Reflect.string(reply, "getCid", "cid");
                    if (id == null || !seen.add(id)) continue;
                    got.add(reply);
                    outcome.fetched++;
                }
                progress.update(outcome.fetched, Math.max(wanted, outcome.fetched));
                if (!page.hasMore) break;
                // A page that doesn't move on, or a load told to end, leaves the thread short.
                if (page.items.isEmpty() || page.cursor <= cursor || load.stopped()
                        || outcome.fetched >= MAX_FETCHED_REPLIES || pages >= MAX_PAGES) {
                    outcome.complete = false;
                    break;
                }
                cursor = page.cursor;
            }
        }
    }

    // ---- TikTok's own call --------------------------------------------------------------

    private static volatile Fetcher fetcherForTests;

    static void setFetcherForTests(Fetcher fetcher) {
        fetcherForTests = fetcher;
    }

    /** TikTok's reply call, or null when this build doesn't have it in the shape read here. */
    static Fetcher tiktok() {
        Fetcher forTests = fetcherForTests;
        if (forTests != null) return forTests;
        try {
            return TikTokCall.find(Class.forName(API_CLASS));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            Logger.printInfo(() -> "Comment replies can't be loaded on this build: " + unavailable);
            return null;
        }
    }

    /** The {@code value()} of the first annotation that has a String one, or null. */
    private static String value(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            try {
                Object value = annotation.annotationType().getMethod("value").invoke(annotation);
                if (value instanceof String) return (String) value;
            } catch (ReflectiveOperationException | RuntimeException noValue) {
                // An annotation with no value, like an encoding marker.
            }
        }
        return null;
    }

    /** {@code page} when it's a reply list, or null for anything else (a failure, mostly). */
    static Page asPage(Object value) {
        if (value == null) return null;
        Class<?> type = value.getClass();
        if (Reflect.method(type, "getItems") == null && Reflect.field(type, "items") == null) return null;
        Object items = Reflect.property(value, "getItems", "items");
        Object cursor = Reflect.property(value, "getCursor", "cursor");
        Object more = Reflect.property(value, "isHasMore", "isHasMore");
        if (more == null) more = Reflect.readField(value, "hasMore");
        boolean hasMore = more instanceof Boolean ? (Boolean) more
                : more instanceof Number && ((Number) more).longValue() != 0;
        return new Page(items instanceof List ? (List<?>) items : null,
                cursor instanceof Number ? ((Number) cursor).longValue() : 0L, hasMore);
    }

    /** What a failed answer says, for the log: Kotlin wraps the throwable in a Result.Failure. */
    private static String describe(Object answer) {
        if (answer == null) return "no answer";
        for (Class<?> type = answer.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Throwable.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object error = field.get(answer);
                    if (error != null) return error.toString();
                } catch (ReflectiveOperationException | RuntimeException unreadable) {
                    break;
                }
            }
        }
        return answer.getClass().getName();
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return args != null && args.length == 1 && proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            default:
                return "HushfeedReplyPage";
        }
    }

    private static Object zero(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == int.class) return 0;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        return (char) 0;
    }

    /**
     * Kotlin's EmptyCoroutineContext, as far as a suspend call asks it anything: no elements,
     * so {@code get} finds nothing and the call resumes on whichever thread finished it,
     * {@code fold} hands back its start, {@code plus} is the other context and {@code minusKey}
     * is itself.
     */
    private static final class EmptyContext implements InvocationHandler {
        private final Class<?> type;

        EmptyContext(Class<?> type) {
            this.type = type;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            int count = args == null ? 0 : args.length;
            if (count == 2) return args[0];
            if (count == 1 && method.getReturnType() == type) {
                return method.getParameterTypes()[0] == type ? args[0] : proxy;
            }
            return zero(method.getReturnType());
        }
    }

    static final class TikTokCall implements Fetcher {
        private final Object api;
        private final Method method;
        private final String[] names;
        private final Class<?> continuationType;
        private final Class<?> contextType;

        private TikTokCall(Object api, Method method, String[] names, Class<?> continuationType,
                Class<?> contextType) {
            this.api = api;
            this.method = method;
            this.names = names;
            this.continuationType = continuationType;
            this.contextType = contextType;
        }

        /** The reply call on {@code apiType}'s shared instance; throws when anything is missing. */
        static TikTokCall find(Class<?> apiType) throws ReflectiveOperationException {
            Object api = null;
            for (Field field : apiType.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !apiType.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                api = field.get(null);
                if (api != null) break;
            }
            if (api == null) throw new NoSuchFieldException(apiType.getName() + " has no shared instance");
            List<Class<?>> types = new ArrayList<>(java.util.Arrays.asList(apiType.getInterfaces()));
            types.add(apiType);
            for (Class<?> type : types) {
                for (Method method : type.getMethods()) {
                    if (!REPLY_PATH.equals(value(method.getAnnotations()))) continue;
                    TikTokCall call = shape(api, method);
                    if (call != null) return call;
                }
            }
            throw new NoSuchMethodException(apiType.getName() + " has no paged " + REPLY_PATH + " call");
        }

        /** The call when {@code method} is a suspend method with the four queries paging needs. */
        private static TikTokCall shape(Object api, Method method) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length < 5 || method.getReturnType() != Object.class) return null;
            Class<?> continuation = types[types.length - 1];
            if (!continuation.isInterface()) return null;
            Class<?> context = null;
            for (Method member : continuation.getMethods()) {
                if (member.getParameterTypes().length == 0 && member.getReturnType().isInterface()) {
                    context = member.getReturnType();
                }
            }
            if (context == null) return null;
            Annotation[][] notes = method.getParameterAnnotations();
            String[] names = new String[types.length];
            for (int index = 0; index < types.length - 1; index++) names[index] = value(notes[index]);
            if (!takes(names, types, "comment_id", String.class) || !takes(names, types, "item_id", String.class)
                    || !takes(names, types, "cursor", long.class, Long.class, int.class, Integer.class)
                    || !takes(names, types, "count", int.class, Integer.class, long.class, Long.class)) {
                return null;
            }
            return new TikTokCall(api, method, names, continuation, context);
        }

        private static boolean takes(String[] names, Class<?>[] types, String name, Class<?>... allowed) {
            for (int index = 0; index < names.length; index++) {
                if (!name.equals(names[index])) continue;
                for (Class<?> type : allowed) if (types[index] == type) return true;
                return false;
            }
            return false;
        }

        private static Object number(Class<?> type, long value) {
            return type == int.class || type == Integer.class ? (Object) (int) value : (Object) value;
        }

        Object[] arguments(String threadId, String itemId, long cursor, int count, Object continuation) {
            Class<?>[] types = method.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int index = 0; index < types.length - 1; index++) {
                String name = names[index];
                if ("comment_id".equals(name)) args[index] = threadId;
                else if ("item_id".equals(name)) args[index] = itemId;
                else if ("cursor".equals(name)) args[index] = number(types[index], cursor);
                else if ("count".equals(name)) args[index] = number(types[index], count);
                // TikTok's own call sends an empty list here rather than leaving it out.
                else if ("insert_ids".equals(name) && types[index] == String.class) args[index] = "";
                else args[index] = zero(types[index]);
            }
            args[types.length - 1] = continuation;
            return args;
        }

        @Override
        public Page page(String threadId, String itemId, long cursor, int count, Load load)
                throws InterruptedException {
            CountDownLatch answered = new CountDownLatch(1);
            AtomicReference<Object> answer = new AtomicReference<>();
            Object context = Proxy.newProxyInstance(contextType.getClassLoader(),
                    new Class<?>[]{contextType}, new EmptyContext(contextType));
            Object continuation = Proxy.newProxyInstance(continuationType.getClassLoader(),
                    new Class<?>[]{continuationType}, (proxy, called, args) -> {
                        if (called.getDeclaringClass() == Object.class) return objectMethod(proxy, called, args);
                        if (args == null || args.length == 0) {
                            return called.getReturnType() == contextType ? context : zero(called.getReturnType());
                        }
                        // resumeWith: the list, or a Result.Failure holding why there isn't one.
                        answer.set(args[0]);
                        answered.countDown();
                        return zero(called.getReturnType());
                    });
            Object returned;
            try {
                returned = method.invoke(api, arguments(threadId, itemId, cursor, count, continuation));
            } catch (InvocationTargetException failed) {
                Logger.printInfo(() -> "A reply page failed: " + failed.getCause());
                return null;
            } catch (IllegalAccessException | IllegalArgumentException refused) {
                Logger.printInfo(() -> "A reply page couldn't be asked for: " + refused);
                return null;
            }
            Page now = asPage(returned);
            if (now != null) return now;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PAGE_TIMEOUT_MS);
            while (!answered.await(200, TimeUnit.MILLISECONDS)) {
                if (load.stopped()) return null;
                if (System.nanoTime() > deadline) {
                    Logger.printInfo(() -> "A reply page took longer than " + PAGE_TIMEOUT_MS + " ms");
                    return null;
                }
            }
            Object result = answer.get();
            Page page = asPage(result);
            if (page == null) Logger.printInfo(() -> "A reply page came back without replies: " + describe(result));
            return page;
        }
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.privacy.CleanupRunner.End;
import app.morphe.extension.tiktok.privacy.CleanupRunner.Failure;

/**
 * Calls TikTok's own request services by what their annotations say, so the request goes out
 * signed and routed the way TikTok's own screens send it.
 *
 * <p>A TikTok service is an interface whose methods carry the request path in an annotation's
 * {@code value()} and each parameter's name the same way. The annotation classes are renamed in
 * every build, but what they hold isn't, so a method is found by its path and its arguments are
 * put in by name. The answer is either a call with {@code execute()} or an Rx source, which is
 * subscribed with an observer of its own interface and waited on.</p>
 */
final class TikTokCalls {
    /** How long one request may take before the run gives up on it. */
    static final long ANSWER_MS = 30_000L;

    private TikTokCalls() {
    }

    /**
     * The service method whose path is {@code path}, preferring one named {@code preferred} and
     * then the one with the fewest parameters, since a path can have a longer variant beside it.
     */
    static Method endpoint(Object service, String path, String preferred) {
        if (service == null) return null;
        String wanted = trimSlash(path);
        Method best = null;
        for (Method method : candidates(service.getClass())) {
            if (!wanted.equals(trimSlash(pathOf(method)))) continue;
            if (preferred != null && preferred.equals(method.getName())) return method;
            if (best == null || method.getParameterTypes().length < best.getParameterTypes().length) {
                best = method;
            }
        }
        return best;
    }

    /** The interface methods first, where TikTok's services keep their annotations, then the class's own. */
    private static List<Method> candidates(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            for (Class<?> face : at.getInterfaces()) Collections.addAll(methods, face.getMethods());
        }
        Collections.addAll(methods, type.getMethods());
        return methods;
    }

    /** The request path a service method declares, or null. */
    static String pathOf(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            String value = valueOf(annotation);
            if (value != null && !value.isEmpty()) return value;
        }
        return null;
    }

    /** The name a parameter goes out under, or null for one with none (a header list, say). */
    static String nameOf(Annotation[] annotations) {
        for (Annotation annotation : annotations) {
            String value = valueOf(annotation);
            if (value != null && !value.isEmpty()) return value;
        }
        return null;
    }

    private static String valueOf(Annotation annotation) {
        try {
            Method value = annotation.annotationType().getMethod("value");
            Object answer = value.invoke(annotation);
            return answer instanceof String ? (String) answer : null;
        } catch (Throwable notThisOne) {
            // An annotation without a value(), such as the one marking a form body.
            return null;
        }
    }

    private static String trimSlash(String path) {
        if (path == null) return "";
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /**
     * Calls the method at {@code path} with {@code arguments} put in by parameter name. A
     * parameter not named in them gets zero, false or null, as TikTok's own callers pass when
     * they have nothing for it.
     */
    static Object call(Object service, String path, String preferred, Map<String, Object> arguments)
            throws Failure {
        Method method = endpoint(service, path, preferred);
        if (method == null) throw new Failure(End.UNSUPPORTED, "no service method for " + path);
        Class<?>[] types = method.getParameterTypes();
        Annotation[][] names = method.getParameterAnnotations();
        Object[] values = new Object[types.length];
        for (int index = 0; index < types.length; index++) {
            String name = nameOf(names[index]);
            Object given = name == null ? null : arguments.get(name);
            values[index] = coerce(given, types[index]);
        }
        try {
            method.setAccessible(true);
            return method.invoke(service, values);
        } catch (InvocationTargetException thrown) {
            throw failureOf(thrown.getTargetException());
        } catch (IllegalAccessException | IllegalArgumentException wrongShape) {
            throw new Failure(End.UNSUPPORTED, "couldn't call " + path + ": " + wrongShape);
        }
    }

    static Object coerce(Object given, Class<?> type) {
        if (type == int.class || type == Integer.class) {
            if (given instanceof Number) return ((Number) given).intValue();
            return type.isPrimitive() ? 0 : null;
        }
        if (type == long.class || type == Long.class) {
            if (given instanceof Number) return ((Number) given).longValue();
            return type.isPrimitive() ? 0L : null;
        }
        if (type == boolean.class || type == Boolean.class) {
            if (given instanceof Boolean) return given;
            return type.isPrimitive() ? Boolean.FALSE : null;
        }
        if (type == String.class) return given == null ? null : given.toString();
        if (type.isPrimitive()) return defaultFor(type);
        return type.isInstance(given) ? given : null;
    }

    private static Object defaultFor(Class<?> type) {
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        return null;
    }

    /**
     * Waits for what a service method answered: runs a call, or subscribes to an Rx source and
     * takes its first value. Answers the response body.
     */
    static Object await(Object answer) throws Failure {
        if (answer == null) throw new Failure(End.UNSUPPORTED, "the service answered nothing");
        Method execute = Reflect.method(answer.getClass(), "execute");
        if (execute != null) return bodyOf(run(execute, answer));
        Method subscribe = subscribeMethod(answer.getClass());
        if (subscribe == null) {
            throw new Failure(End.UNSUPPORTED, "can't wait on " + answer.getClass().getName());
        }
        return first(subscribe, answer, ANSWER_MS);
    }

    private static Object run(Method execute, Object call) throws Failure {
        try {
            return execute.invoke(call);
        } catch (InvocationTargetException thrown) {
            throw failureOf(thrown.getTargetException());
        } catch (IllegalAccessException | IllegalArgumentException wrongShape) {
            throw new Failure(End.UNSUPPORTED, "couldn't run the call: " + wrongShape);
        }
    }

    /**
     * The body out of a call's response. The wrapper's fields are renamed in every build, but
     * the body is the one held as a plain Object.
     */
    static Object bodyOf(Object response) throws Failure {
        if (response == null) throw new Failure(End.NETWORK, "no response");
        Object body = Reflect.invoke(response, "body");
        if (body != null) return body;
        for (Class<?> at = response.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != Object.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(response);
                    if (value != null) return value;
                } catch (Throwable unreadable) {
                    // Try the next one.
                }
            }
        }
        throw new Failure(End.NETWORK, "the response had no body");
    }

    /**
     * The subscribe method of an Rx source: the one method of one of its interfaces whose single
     * parameter is an observer, an interface with {@code onError(Throwable)}.
     */
    static Method subscribeMethod(Class<?> type) {
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            for (Class<?> face : at.getInterfaces()) {
                for (Method method : face.getMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (parameters.length == 1 && parameters[0].isInterface() && isObserver(parameters[0])) {
                        return method;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isObserver(Class<?> type) {
        try {
            type.getMethod("onError", Throwable.class);
            return true;
        } catch (NoSuchMethodException no) {
            return false;
        }
    }

    private static Object first(Method subscribe, Object source, long timeoutMs) throws Failure {
        Class<?> observerType = subscribe.getParameterTypes()[0];
        AtomicReference<Object> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicReference<Object> subscription = new AtomicReference<>();
        CountDownLatch answered = new CountDownLatch(1);
        InvocationHandler handler = (proxy, method, arguments) -> {
            Object argument = arguments == null || arguments.length == 0 ? null : arguments[0];
            switch (method.getName()) {
                case "onSubscribe":
                    subscription.set(argument);
                    return null;
                case "onNext":
                case "onSuccess":
                    if (argument != null) value.compareAndSet(null, argument);
                    answered.countDown();
                    return null;
                case "onError":
                    if (argument instanceof Throwable) error.compareAndSet(null, (Throwable) argument);
                    answered.countDown();
                    return null;
                case "onComplete":
                    answered.countDown();
                    return null;
                case "equals":
                    return proxy == argument;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "Hushfeed account cleanup observer";
                default:
                    return defaultFor(method.getReturnType());
            }
        };
        Object observer = Proxy.newProxyInstance(observerType.getClassLoader(),
                new Class<?>[]{observerType}, handler);
        try {
            subscribe.setAccessible(true);
            subscribe.invoke(source, observer);
        } catch (InvocationTargetException thrown) {
            throw failureOf(thrown.getTargetException());
        } catch (IllegalAccessException | IllegalArgumentException wrongShape) {
            throw new Failure(End.UNSUPPORTED, "couldn't subscribe: " + wrongShape);
        }
        boolean inTime;
        try {
            inTime = answered.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            dispose(subscription.get());
            throw new Failure(End.STOPPED, "interrupted");
        }
        if (!inTime) {
            dispose(subscription.get());
            throw new Failure(End.NETWORK, "no answer in " + timeoutMs + " ms");
        }
        if (value.get() != null) return value.get();
        if (error.get() != null) throw failureOf(error.get());
        throw new Failure(End.NETWORK, "an empty answer");
    }

    private static void dispose(Object subscription) {
        Method dispose = subscription == null ? null : Reflect.method(subscription.getClass(), "dispose");
        if (dispose == null) return;
        try {
            dispose.invoke(subscription);
        } catch (Throwable ignored) {
            // The request is left to finish on its own; nothing waits for it now.
        }
    }

    /**
     * What a thrown request error means for the run. A captcha or verify check, an error code
     * from TikTok's server and a 401, 403 or 429 are TikTok saying no; a missing member is a
     * build this doesn't fit; the rest is the network.
     */
    static Failure failureOf(Throwable thrown) {
        Throwable at = thrown;
        for (int depth = 0; at != null && depth < 8; depth++) {
            if (at instanceof Failure) return (Failure) at;
            String name = at.getClass().getName().toLowerCase(Locale.ROOT);
            if (name.contains("captcha") || name.contains("verify")) {
                return new Failure(End.REFUSED, at.getClass().getName());
            }
            Integer code = number(Reflect.invoke(at, "getErrorCode"));
            if (code != null && code != 0) return new Failure(End.REFUSED, "error code " + code);
            Integer status = number(Reflect.invoke(at, "getStatusCode"));
            if (status != null && (status == 401 || status == 403 || status == 429)) {
                return new Failure(End.REFUSED, "HTTP " + status);
            }
            if (at instanceof NoSuchMethodException || at instanceof NoSuchFieldException
                    || at instanceof ClassNotFoundException || at instanceof NoSuchMethodError
                    || at instanceof ClassCastException) {
                return new Failure(End.UNSUPPORTED, String.valueOf(at));
            }
            if (at.getCause() == at) break;
            at = at instanceof InvocationTargetException
                    ? ((InvocationTargetException) at).getTargetException() : at.getCause();
        }
        return new Failure(End.NETWORK, String.valueOf(thrown));
    }

    /** Stops the run on a body whose status code isn't zero, the way TikTok reports a refusal. */
    static void checkStatus(Object body) throws Failure {
        Object status = Reflect.property(body, "getStatusCode", "status_code");
        if (status == null) status = Reflect.readField(body, "statusCode");
        Integer code = number(status);
        if (code != null && code != 0) throw new Failure(End.REFUSED, "status code " + code);
    }

    private static Integer number(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString()).intValueExact();
        } catch (RuntimeException notANumber) {
            return null;
        }
    }

    /**
     * The list a body keeps as {@code name}, empty when the answer left it out. A body without
     * the field at all is a build whose answers changed shape, which mustn't read as an empty list.
     */
    static List<?> listIn(Object body, String name) throws Failure {
        if (body == null || Reflect.field(body.getClass(), name) == null) {
            throw new Failure(End.UNSUPPORTED, "no " + name + " in "
                    + (body == null ? "the answer" : body.getClass().getName()));
        }
        Object value = Reflect.readField(body, name);
        return value instanceof List ? (List<?>) value : Collections.emptyList();
    }

    /** A yes/no a body keeps as a boolean or a number, false when it has none. */
    static boolean flagIn(Object body, String name) {
        Object value = Reflect.readField(body, name);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        return false;
    }

    /** A number a body keeps, or {@code fallback} when it has none. */
    static long longIn(Object body, String name, long fallback) {
        Object value = Reflect.readField(body, name);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    /** A video's id, off the video or off the one an entry holds. */
    static String idOf(Object item) {
        if (item == null) return null;
        String id = Reflect.string(item, "getAid", "aid");
        if (id != null) return id;
        Object video = Reflect.property(item, "getAweme", "aweme");
        return video == null ? null : Reflect.string(video, "getAid", "aid");
    }

    /** The static instance a named API class keeps of itself. */
    static Object singleton(String className) throws Failure {
        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (Throwable missing) {
            throw new Failure(End.UNSUPPORTED, "no " + className);
        }
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != type) continue;
            try {
                field.setAccessible(true);
                Object instance = field.get(null);
                if (instance != null) return instance;
            } catch (Throwable unreadable) {
                // Try the next one.
            }
        }
        throw new Failure(End.UNSUPPORTED, "no instance of " + className);
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.spoof.sim;

import android.net.Uri;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Which image hosts TikTok's network fetcher asked for, and which of those fetches failed and
 * how, for the diagnostic export. A report for #415 had every cover and avatar stay grey with
 * SIM spoof on, in three different mods at once, from a country the presets don't cover, and
 * nothing in a report could say whether the images were refused by a CDN, never resolved, or
 * turned down on the phone as expired before a request went out. Every fetch and every failure
 * of Fresco's network producer passes through the two hooks here.
 *
 * <p>Only the host goes in. An image URL's path and query carry signed tokens, so they never
 * reach the report, and a URL inside a failure's message is cut down to its host as well. The
 * export keeps those bare host names, which every other section loses to the redactor.
 */
public final class ImageLoads implements LogBufferManager.ServerNamingSection {
    static final String TITLE = "IMAGE LOADS";
    static final int HOST_LIMIT = 24;
    static final int KIND_LIMIT = 4;
    static final int MESSAGE_LIMIT = 140;
    static final String OTHER_HOSTS = "(other hosts)";
    static final String NO_HOST = "(no host)";
    static final String OTHER_KINDS = "other failures";

    private static final ImageLoads INSTANCE = new ImageLoads();
    private static final Object NO_URI_METHOD = new Object();
    private static final Map<Class<?>, Object> URI_METHODS = new ConcurrentHashMap<>();
    // Compiled where a refusal can't throw out of the class's initializer into Fresco's call.
    private static final Pattern URL = compiled(
        "(?i)\\b[a-z][a-z0-9+.-]*://([^/\\s?#:,;)\\}\\]\"']+)[^\\s,;)\\}\\]\"']*");
    private static final Pattern SPACES = compiled("\\s+");
    /** Guarded by itself. */
    private static final Map<String, Host> HOSTS = new LinkedHashMap<>();
    private static volatile boolean installed;

    private static final class Host {
        long fetched;
        long failed;
        final Map<String, long[]> kinds = new LinkedHashMap<>();
    }

    private ImageLoads() {
    }

    private static Pattern compiled(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (RuntimeException refused) {
            return null;
        }
    }

    /** One image fetch the network producer is about to start. Never throws into Fresco. */
    public static void requested(Object fetchState) {
        try {
            count(host(fetchState), null);
        } catch (Throwable ignored) {
            // A report one fetch short beats an image load that throws.
        }
    }

    /** One image fetch that failed, before Fresco hands the failure on. Never throws into it. */
    public static void failed(Object fetchState, Throwable failure) {
        try {
            count(host(fetchState), kind(failure));
        } catch (Throwable ignored) {
            // A report one failure short beats an image load that throws.
        }
    }

    private static void count(String host, String kind) {
        if (!installed) {
            installed = true;
            LogBufferManager.registerReportSection(INSTANCE);
        }
        synchronized (HOSTS) {
            Host entry = HOSTS.get(host);
            if (entry == null) {
                if (HOSTS.size() >= HOST_LIMIT) host = OTHER_HOSTS;
                entry = HOSTS.get(host);
                if (entry == null) {
                    entry = new Host();
                    HOSTS.put(host, entry);
                }
            }
            if (kind == null) {
                entry.fetched++;
                return;
            }
            entry.failed++;
            long[] times = entry.kinds.get(kind);
            if (times == null) {
                if (entry.kinds.size() >= KIND_LIMIT) kind = OTHER_KINDS;
                times = entry.kinds.get(kind);
                if (times == null) {
                    times = new long[1];
                    entry.kinds.put(kind, times);
                }
            }
            times[0]++;
        }
    }

    /** The image's host, read through the fetch state's one public no-argument Uri getter. */
    static String host(Object fetchState) {
        if (fetchState == null) return NO_HOST;
        Class<?> type = fetchState.getClass();
        Object getter = URI_METHODS.get(type);
        if (getter == null) {
            getter = uriGetter(type);
            URI_METHODS.put(type, getter);
        }
        if (getter == NO_URI_METHOD) return NO_HOST;
        try {
            Object uri = ((Method) getter).invoke(fetchState);
            String host = uri instanceof Uri ? ((Uri) uri).getHost() : null;
            return host == null || host.isEmpty() ? NO_HOST : host.toLowerCase(Locale.ROOT);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return NO_HOST;
        }
    }

    private static Object uriGetter(Class<?> type) {
        Method found = null;
        for (Method method : type.getMethods()) {
            if (method.getParameterTypes().length != 0 || method.getReturnType() != Uri.class) continue;
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (found != null) return NO_URI_METHOD;
            found = method;
        }
        return found == null ? NO_URI_METHOD : found;
    }

    /** The failure's class and message, and its cause's when it has one, with URLs cut to hosts. */
    static String kind(Throwable failure) {
        if (failure == null) return "no reason given";
        StringBuilder kind = new StringBuilder(describe(failure));
        Throwable cause = failure.getCause();
        if (cause != null && cause != failure) kind.append(" < ").append(describe(cause));
        return kind.length() <= MESSAGE_LIMIT ? kind.toString() : kind.substring(0, MESSAGE_LIMIT - 3) + "...";
    }

    private static String describe(Throwable failure) {
        String name = failure.getClass().getSimpleName();
        if (name.isEmpty()) name = failure.getClass().getName();
        String message = failure.getMessage();
        // A message that can't be cut down to its hosts stays out, since it may hold a signed URL.
        if (message == null || URL == null || SPACES == null) return name;
        message = SPACES.matcher(URL.matcher(message).replaceAll("$1")).replaceAll(" ").trim();
        return message.isEmpty() ? name : name + ": " + message;
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public List<String> lines() {
        List<Map.Entry<String, Host>> hosts;
        long fetched = 0, failed = 0;
        List<String> lines = new ArrayList<>();
        synchronized (HOSTS) {
            if (HOSTS.isEmpty()) return lines;
            hosts = new ArrayList<>(HOSTS.entrySet());
            Collections.sort(hosts, (a, b) -> {
                int byFailed = Long.compare(b.getValue().failed, a.getValue().failed);
                return byFailed != 0 ? byFailed : Long.compare(b.getValue().fetched, a.getValue().fetched);
            });
            for (Map.Entry<String, Host> entry : hosts) {
                fetched += entry.getValue().fetched;
                failed += entry.getValue().failed;
            }
            lines.add("Since the app started: " + fetched + " image " + plural(fetched, "fetch", "fetches")
                + ", " + failed + " failed");
            for (Map.Entry<String, Host> entry : hosts) {
                Host host = entry.getValue();
                lines.add(entry.getKey() + ": " + host.fetched + " fetched, " + host.failed + " failed");
                for (Map.Entry<String, long[]> kind : host.kinds.entrySet()) {
                    lines.add(entry.getKey() + ", " + kind.getKey() + ": " + kind.getValue()[0]
                        + plural(kind.getValue()[0], " time", " times"));
                }
            }
        }
        return lines;
    }

    private static String plural(long count, String one, String many) {
        return count == 1 ? one : many;
    }

    static ImageLoads sectionForTests() {
        return INSTANCE;
    }

    static void resetForTests() {
        synchronized (HOSTS) {
            HOSTS.clear();
        }
        URI_METHODS.clear();
        installed = false;
    }
}

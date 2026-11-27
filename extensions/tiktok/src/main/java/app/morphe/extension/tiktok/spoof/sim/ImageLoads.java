/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.spoof.sim;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.settings.Settings;

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
    /** TikTok's network stack's own preferences file for the store region it was given. */
    static final String STORE_PREFS = "ttnet_store_region";

    private static final ImageLoads INSTANCE = new ImageLoads();
    private static final Object NO_URI_METHOD = new Object();
    private static final Map<Class<?>, Object> URI_METHODS = new ConcurrentHashMap<>();
    private static final Object NO_CODE_METHOD = new Object();
    private static final Map<Class<?>, Object> CODE_METHODS = new ConcurrentHashMap<>();
    /**
     * TikTok's image loader (com.ss.mediakit's MIO client) fails a fetch with an exception that
     * keeps these two names in every build: the loader's own error code, and a general code that
     * is always 9999 for the loader's failures. The message is only "native error" or "task
     * canceled", so without the code a report can't tell a lookup that failed from a timeout (#415).
     */
    private static final String[] CODE_GETTERS = {"getMIOErrorCode", "getErrorCode"};
    // Compiled where a refusal can't throw out of the class's initializer into Fresco's call.
    private static final Pattern URL = compiled(
        "(?i)\\b[a-z][a-z0-9+.-]*://([^/\\s?#:,;)\\}\\]\"']+)[^\\s,;)\\}\\]\"']*");
    private static final Pattern SPACES = compiled("\\s+");
    /** A path with no host in front, as TikTok's network stack writes its status line. */
    private static final Pattern PATH = compiled("(?<!\\S)/[^\\s,;)\\}\\]\"']*");
    /** A path that names an endpoint rather than one object: lowercase words only. */
    private static final Pattern ROUTE = compiled("(?:/[a-z_-]+)+/?");
    private static final Pattern HEADER_NAME = compiled("\"([^\"]+)\"\\s*:");
    private static final String HEADERS = "requestHeaders: {";
    static final String OBJECT_PATH = "(path)";
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

    /**
     * 47.9.3's network stack writes {@code statusCode: 403 /tos-.../abc~tplv-...webp, traceCode: ,
     * url: <url>, requestHeaders: {...}}. Each image's own path made its own kind, so four
     * covers filled a host's kinds and the other 191 failures said nothing (#415). An object's
     * path becomes {@link #OBJECT_PATH} while an endpoint like {@code /image/refresh/} stays, and
     * of the headers only the names stay, since a value may be a token.
     */
    private static String describe(Throwable failure) {
        String name = failure.getClass().getSimpleName();
        if (name.isEmpty()) name = failure.getClass().getName();
        String message = failure.getMessage();
        // A message that can't be cut down to its hosts stays out, since it may hold a signed URL.
        if (message == null || URL == null || SPACES == null || PATH == null || ROUTE == null
                || HEADER_NAME == null) return withCode(name, failure);
        message = paths(headerNames(URL.matcher(message).replaceAll("$1")));
        message = SPACES.matcher(message).replaceAll(" ").trim();
        return withCode(message.isEmpty() ? name : name + ": " + message, failure);
    }

    private static String withCode(String described, Throwable failure) {
        Integer code = code(failure);
        return code == null ? described : described + " (code " + code + ")";
    }

    /** The failure's own error code, through the first of {@link #CODE_GETTERS} its class has. */
    static Integer code(Throwable failure) {
        Class<?> type = failure.getClass();
        Object getter = CODE_METHODS.get(type);
        if (getter == null) {
            getter = codeGetter(type);
            CODE_METHODS.put(type, getter);
        }
        if (getter == NO_CODE_METHOD) return null;
        try {
            Object value = ((Method) getter).invoke(failure);
            return value instanceof Integer ? (Integer) value : null;
        } catch (ReflectiveOperationException | RuntimeException unreadable) {
            return null;
        }
    }

    private static Object codeGetter(Class<?> type) {
        for (String wanted : CODE_GETTERS) {
            try {
                Method method = type.getMethod(wanted);
                if (method.getReturnType() == int.class && !Modifier.isStatic(method.getModifiers())) return method;
            } catch (NoSuchMethodException | SecurityException missing) {
                // Try the next name.
            }
        }
        return NO_CODE_METHOD;
    }

    private static String headerNames(String message) {
        int at = message.indexOf(HEADERS);
        if (at < 0) return message;
        int open = at + HEADERS.length() - 1;
        int close = message.indexOf('}', open);
        Matcher header = HEADER_NAME.matcher(close < 0 ? message.substring(open) : message.substring(open, close + 1));
        StringBuilder names = new StringBuilder();
        while (header.find()) {
            if (names.length() > 0) names.append(' ');
            names.append(header.group(1));
        }
        return message.substring(0, at) + "requestHeaders: [" + names + "]"
            + (close < 0 ? "" : message.substring(close + 1));
    }

    private static String paths(String message) {
        Matcher path = PATH.matcher(message);
        StringBuffer out = new StringBuffer();
        while (path.find()) {
            String kept = ROUTE.matcher(path.group()).matches() ? path.group() : OBJECT_PATH;
            path.appendReplacement(out, Matcher.quoteReplacement(kept));
        }
        path.appendTail(out);
        return out.toString();
    }

    @Override
    public String title() {
        return TITLE;
    }

    /**
     * Where TikTok's network stack has put this phone, and the SIM country it's being shown.
     *
     * <p>TikTok keeps the store region it was given in its own preferences file, with where the
     * answer came from (the account, the device, or the phone itself) and the data center it
     * picks servers by. In #415 the refused images and TikTok's own image refresh came from
     * US-side servers while the phone was in Bangladesh with a US SIM preset, and nothing in the
     * report could say whether the preset had moved the phone's store region. The account id
     * stored beside them never goes in.
     */
    static List<String> placement(Context context) {
        List<String> lines = new ArrayList<>();
        // How many of TikTok's network stack answers went back to the real SIM (#415).
        long swapped = NetworkStackSim.swapped();
        lines.add("SIM spoof: " + (Settings.SIM_SPOOF.get()
                ? "on, " + code(Settings.SIM_SPOOF_ISO.get()) : "off")
                + (swapped > 0 ? ", network stack kept on the real SIM ("
                        + swapped + plural(swapped, " answer)", " answers)") : ""));
        if (context == null) return lines;
        try {
            SharedPreferences prefs = context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE);
            String region = code(prefs.getString("store_region", ""));
            String source = word(prefs.getString("store_region_src", ""));
            String idc = word(prefs.getString("store_idc", ""));
            if (region.isEmpty() && idc.isEmpty()) {
                lines.add("TikTok's store region: not set yet");
                return lines;
            }
            lines.add("TikTok's store region: " + (region.isEmpty() ? "none" : region)
                    + (source.isEmpty() ? "" : ", " + source(source))
                    + (idc.isEmpty() ? "" : ", data center " + idc));
        } catch (RuntimeException unreadable) {
            lines.add("TikTok's store region: couldn't be read");
        }
        return lines;
    }

    private static String source(String source) {
        switch (source.toLowerCase(Locale.ROOT)) {
            case "uid": return "from the account";
            case "did": return "from the device";
            case "local": return "worked out on the phone";
            default: return "from " + source;
        }
    }

    /** A country code as two capitals, or "" for anything else. */
    private static String code(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.matches("[A-Za-z]{2}") ? trimmed.toUpperCase(Locale.ROOT) : "";
    }

    /** A short plain word, or "" for anything longer or odder. */
    private static String word(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.matches("[A-Za-z0-9_-]{1,24}") ? trimmed : "";
    }

    @Override
    public List<String> lines() {
        List<Map.Entry<String, Host>> hosts;
        long fetched = 0, failed = 0;
        List<String> lines = new ArrayList<>();
        synchronized (HOSTS) {
            if (HOSTS.isEmpty()) return lines;
        }
        // Outside the lock: Fresco's fetches take it, and a first read of a preferences file waits on the disk.
        lines.addAll(placement(Utils.getContext()));
        synchronized (HOSTS) {
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
        NetworkStackSim.resetForTests();
    }
}

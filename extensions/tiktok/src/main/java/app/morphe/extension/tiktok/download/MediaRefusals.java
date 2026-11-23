/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Which servers turned a save down, how, and whether the signed link it asked with had already
 * run out, for the diagnostic export.
 *
 * <p>A report for #464 had every original photo of a story answer 403 from both mirrors, and all
 * it could say about them was "[url omitted]": the redactor takes a URL whole, host and all. So
 * nothing told whether the refusals came from the same servers TikTok's own image loads were
 * using, or whether the links had simply expired (TikTok's image links carry an {@code x-expires}
 * time, and TikTok's own loader won't even send one that has passed). Each mirror that fails for
 * good in {@link RemoteMedia} lands here as its bare host, the reason (an HTTP status or the
 * exception's class name) and the link's expiry against the phone's clock. The path and query,
 * which carry the signed tokens, never go in.
 */
final class MediaRefusals implements LogBufferManager.ServerNamingSection {
    static final String TITLE = "SAVE REFUSALS";
    static final int HOST_LIMIT = 16;
    static final int REASON_LIMIT = 4;
    static final String OTHER_HOSTS = "(other hosts)";
    static final String NO_HOST = "(no host)";
    static final String OTHER_REASONS = "other reasons";

    private static final MediaRefusals INSTANCE = new MediaRefusals();
    private static final Pattern STATUS = Pattern.compile("^Media server returned (\\d{3})$");
    private static final Pattern HOST = Pattern.compile("[a-z0-9.-]{1,253}");
    /** Guarded by itself. */
    private static final Map<String, Host> HOSTS = new LinkedHashMap<>();
    private static volatile boolean installed;
    /** Seconds since the epoch. Its own interface: java.util.function is API 24, the payload's floor 23. */
    interface Clock {
        long seconds();
    }

    /** A test sets its own clock. */
    static volatile Clock clockForTests;

    private static final class Host {
        long refused;
        long expired;
        long inDate;
        long undated;
        /** Refusals that went out with TikTok's sign-in, counted on TikTok's own API hosts only. */
        long signedIn;
        final Map<String, long[]> reasons = new LinkedHashMap<>();
    }

    private MediaRefusals() {
    }

    /** One mirror that failed for good: its link and what it failed with. Never throws. */
    static void record(String url, Throwable failure) {
        try {
            count(host(url), reason(failure), expiry(url), signedIn(url));
        } catch (Throwable ignored) {
            // A report one refusal short beats a save that stops on its bookkeeping.
        }
    }

    /**
     * Whether a save to this link carries TikTok's sign-in, so a refusal from one of TikTok's own
     * servers says if it was turned down with the sign-in or without one (#464). Only the yes or
     * no goes in the report, never a cookie.
     */
    private static boolean signedIn(String url) {
        try {
            return url != null && MediaTransport.signIn(new java.net.URL(url.trim())) != null;
        } catch (Exception unparsable) {
            return false;
        }
    }

    private static void count(String host, String reason, Long expires, boolean signedIn) {
        if (!installed) {
            installed = true;
            LogBufferManager.registerReportSection(INSTANCE);
        }
        long now = now();
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
            entry.refused++;
            if (expires == null) entry.undated++;
            else if (expires <= now) entry.expired++;
            else entry.inDate++;
            if (signedIn) entry.signedIn++;
            long[] times = entry.reasons.get(reason);
            if (times == null) {
                if (entry.reasons.size() >= REASON_LIMIT) reason = OTHER_REASONS;
                times = entry.reasons.get(reason);
                if (times == null) {
                    times = new long[1];
                    entry.reasons.put(reason, times);
                }
            }
            times[0]++;
        }
    }

    /** The link's host in lower case, or {@link #NO_HOST} for anything that doesn't parse as one. */
    static String host(String url) {
        if (url == null) return NO_HOST;
        try {
            String host = new URI(url.trim()).getHost();
            if (host == null) return NO_HOST;
            host = host.toLowerCase(Locale.ROOT);
            return HOST.matcher(host).matches() ? host : NO_HOST;
        } catch (Exception unparsable) {
            return NO_HOST;
        }
    }

    /** "HTTP 403" for a status the server answered, else the failure's class name. */
    static String reason(Throwable failure) {
        if (failure == null) return "unknown";
        String message = failure.getMessage();
        if (message != null) {
            Matcher status = STATUS.matcher(message);
            if (status.matches()) return "HTTP " + status.group(1);
        }
        return failure.getClass().getSimpleName();
    }

    /** The link's {@code x-expires} time in seconds since the epoch, or null when it carries none. */
    static Long expiry(String url) {
        if (url == null) return null;
        int query = url.indexOf('?');
        if (query < 0) return null;
        int fragment = url.indexOf('#', query);
        String params = fragment < 0 ? url.substring(query + 1) : url.substring(query + 1, fragment);
        for (String pair : params.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0 || !"x-expires".equalsIgnoreCase(pair.substring(0, equals))) continue;
            String value = pair.substring(equals + 1);
            if (!value.matches("\\d{1,12}")) return null;
            return Long.parseLong(value);
        }
        return null;
    }

    private static long now() {
        Clock clock = clockForTests;
        return clock != null ? clock.seconds() : System.currentTimeMillis() / 1000L;
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        synchronized (HOSTS) {
            if (HOSTS.isEmpty()) return lines;
            long refused = 0;
            for (Host host : HOSTS.values()) refused += host.refused;
            lines.add("Since the app started: " + refused + " save "
                    + (refused == 1 ? "address" : "addresses") + " refused for good");
            for (Map.Entry<String, Host> entry : HOSTS.entrySet()) {
                Host host = entry.getValue();
                lines.add(entry.getKey() + ": " + host.refused + " refused; link already expired "
                        + host.expired + ", still in date " + host.inDate + ", no expiry " + host.undated
                        + (MediaTransport.signInHost(entry.getKey())
                                ? "; sent TikTok's sign-in " + host.signedIn : ""));
                for (Map.Entry<String, long[]> reason : host.reasons.entrySet()) {
                    long times = reason.getValue()[0];
                    lines.add(entry.getKey() + ", " + reason.getKey() + ": " + times
                            + (times == 1 ? " time" : " times"));
                }
            }
        }
        return lines;
    }

    static MediaRefusals sectionForTests() {
        return INSTANCE;
    }

    static void resetForTests() {
        synchronized (HOSTS) {
            HOSTS.clear();
        }
        installed = false;
        clockForTests = null;
    }
}

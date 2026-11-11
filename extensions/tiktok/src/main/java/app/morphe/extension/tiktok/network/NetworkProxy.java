/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.network;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Sends TikTok's own traffic through a proxy the reader sets on the Region page.
 *
 * <p>Two routes, both put in place once as the process starts, so a change takes a restart:
 * <ul>
 *   <li>TTNet, ByteDance's Cronet fork that carries TikTok's API. Its engine builder reads a
 *   debug config TTNet looks for in the APK's assets and hands that config's {@code ttnet_proxy}
 *   field to the builder's proxy setter. Release builds ship no such file, so the patch hands
 *   the builder a config with the proxy in that field. The value is a Chromium proxy rule:
 *   {@code http=host:port;https=host:port} for an HTTP proxy (the shape TTNet's own
 *   TTNetInit.setProxy parses too) and {@code socks5://host:port} for SOCKS5.</li>
 *   <li>The JVM's default proxy selector, for plain Java connections: HttpURLConnection and any
 *   OkHttp client built on the default selector, plus raw sockets when the proxy is SOCKS5.</li>
 * </ul>
 *
 * <p>Videos and LIVE streams don't go through either: TikTok's media loader and player are
 * native code that open their own sockets. Hushfeed's own saves and short-link lookups skip the
 * selector on purpose too: they open with {@code Proxy.NO_PROXY} so a proxy can't pick the peer
 * after the address was checked (see {@code PinnedMediaConnection}). {@link NetworkRoutes} lists
 * all four routes in the exported report. Neither route can sign in to a proxy by itself, so with
 * a user name set both point at {@link ProxyRelay}, which signs in for them.
 *
 * <p>The Java side keeps this phone and its local network direct. TTNet's rule has no room for
 * a list of exceptions (Chromium reads one apart from the rule, and TTNet's builder takes the rule
 * alone), so there only Chromium's own exceptions apply: localhost, loopback and link-local
 * addresses. TikTok's API hosts are all public, so that difference only shows for a private
 * address TikTok's own stack is pointed at.
 *
 * <p>The user name and password are never logged, and they're left out of the rules, the
 * descriptions and every debug line here.
 */
@SuppressWarnings("unused")
public final class NetworkProxy {
    public static final String TYPE_HTTP = "http";
    public static final String TYPE_SOCKS5 = "socks5";

    /** TTNet's key for the proxy its Cronet engine starts with. */
    static final String TTNET_PROXY_KEY = "ttnet_proxy";

    /** Where the start-up check asks an HTTP proxy to open a tunnel to. */
    static final String PROBE_TARGET = "www.tiktok.com:443";

    static final int PROBE_TIMEOUT_MS = 10_000;

    /** How many tries a check gets before it says the proxy is down, since slow ones are common. */
    static final int PROBE_TRIES = 2;

    /** How long after a check TikTok coming back to the screen checks the proxy again. */
    static final long RECHECK_AFTER_MS = 5 * 60_000L;

    /** How often a failed connection can have the proxy checked, since they come in bursts. */
    static final long CONFIRM_AFTER_MS = 30_000L;

    private static final Executor OWN_THREAD = task -> Utils.runOnOwnThread("Hushfeed proxy check", task);

    /** Where a check runs: a thread of its own, since it waits on the network. Tests run it in place. */
    static Executor checker = OWN_THREAD;

    /** A label of a host name: letters, digits and hyphens, never a hyphen at either end. */
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+(?:%[A-Za-z0-9._-]+)?");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

    /** What the start-up check found. */
    enum Probe { REACHED, UNREACHABLE, NEEDS_PASSWORD, REFUSED_PASSWORD }

    /** The proxy as the settings describe it. Only a valid one is ever built. */
    static final class Config {
        final String type;
        final String host;
        final int port;
        final String user;
        final String password;

        Config(String type, String host, int port, String user, String password) {
            this.type = type;
            this.host = host;
            this.port = port;
            this.user = user;
            this.password = password;
        }

        boolean socks() {
            return TYPE_SOCKS5.equals(type);
        }

        boolean hasCredentials() {
            return !user.isEmpty();
        }

        /** The host the way a URL or a Chromium rule writes it: an IPv6 literal in brackets. */
        String hostForUrl() {
            return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        }

        String hostPort() {
            return hostForUrl() + ":" + port;
        }

        /** The value TTNet's engine builder gets. Never carries the user name or password. */
        String ttnetRules() {
            return socks()
                    ? "socks5://" + hostPort()
                    : "http=" + hostPort() + ";https=" + hostPort();
        }

        Proxy javaProxy() {
            return new Proxy(socks() ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                    InetSocketAddress.createUnresolved(host, port));
        }

        /**
         * For debug lines: the kind and the address, never the user name or password. The address
         * is the reader's own server, so it goes in the isolate pair a shared report leaves out.
         */
        String describe() {
            return (socks() ? "SOCKS5" : "HTTP") + " proxy at " + L10n.isolate(hostPort());
        }
    }

    /** What this process put in place at start-up, or null when the proxy is off. */
    private static volatile Config active;
    private static volatile boolean installed;
    /** Whether a problem was told and the proxy hasn't answered since. */
    private static final AtomicBoolean reported = new AtomicBoolean();
    /** When the last check started, on {@link SystemClock#elapsedRealtime()}. */
    private static volatile long checkedAt;
    private static volatile boolean following;
    /** The relay in front of a proxy with a password, once something has asked for it. */
    @Nullable private static ProxyRelay relay;

    private NetworkProxy() {
    }

    // -- What the settings say ----------------------------------------------------------------

    /**
     * The host as it should be kept, or null when it isn't a host name, an IPv4 address or an
     * IPv6 literal. A scheme, a port, a user name or a path is refused: each would end up inside
     * the proxy rule TTNet parses, where a ";" or "=" starts a rule of its own.
     */
    @Nullable
    static String normalizeHost(@Nullable String value) {
        if (value == null) return null;
        String host = value.trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.isEmpty() || host.length() > 253) return null;
        if (host.indexOf(':') >= 0) {
            // An IPv6 literal: hex digits and colons, an embedded IPv4 tail, at least two colons.
            int colons = 0;
            for (int index = 0; index < host.length(); index++) if (host.charAt(index) == ':') colons++;
            return colons >= 2 && IPV6.matcher(host).matches() ? host : null;
        }
        if (IPV4.matcher(host).matches()) {
            for (String part : host.split("\\.")) if (Integer.parseInt(part) > 255) return null;
            return host;
        }
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        if (name.isEmpty()) return null;
        for (String label : name.split("\\.", -1)) {
            if (!LABEL.matcher(label).matches()) return null;
        }
        return name;
    }

    /** The port, or -1 when it isn't a whole number from 1 to 65535. */
    static int parsePort(@Nullable String value) {
        if (value == null) return -1;
        String digits = value.trim();
        if (digits.isEmpty() || digits.length() > 5) return -1;
        for (int index = 0; index < digits.length(); index++) {
            char digit = digits.charAt(index);
            if (digit < '0' || digit > '9') return -1;
        }
        int port = Integer.parseInt(digits);
        return port >= 1 && port <= 65535 ? port : -1;
    }

    /**
     * What the host row's editor says about a typed value, or null when it's usable. An empty
     * field is usable: it leaves the proxy off, and refusing it kept a saved host for good.
     */
    @Nullable
    public static String hostProblem(String value) {
        return isBlank(value) || normalizeHost(value) != null
                ? null : L10n.t("Enter a server name or IP address, without http:// or a port "
                        + "number");
    }

    /** What the port row's editor says about a typed value, or null when it's usable. Empty is too. */
    @Nullable
    public static String portProblem(String value) {
        return isBlank(value) || parsePort(value) > 0
                ? null : L10n.t("A port is a whole number from 1 to 65535");
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.trim().isEmpty();
    }

    /** A proxy from the five values, or null when any of them makes it unusable. */
    @Nullable
    static Config parse(@Nullable String type, @Nullable String host, @Nullable String port,
                        @Nullable String user, @Nullable String password) {
        String kind = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (!TYPE_HTTP.equals(kind) && !TYPE_SOCKS5.equals(kind)) return null;
        String address = normalizeHost(host);
        int number = parsePort(port);
        if (address == null || number < 0) return null;
        String name = user == null ? "" : user.trim();
        // A password without a user name has nothing to sign in as.
        String secret = name.isEmpty() || password == null ? "" : password;
        return new Config(kind, address, number, name, secret);
    }

    /**
     * The proxy the settings turn on, or null. Off while the patch isn't in the bundle, before
     * the extension has a context (Settings isn't loaded that early), while Hushfeed is paused
     * (the switch answers off then), while the switch is off and while the host or port is empty.
     */
    @Nullable
    static Config configured() {
        if (!SettingsStatus.networkProxyEnabled || Utils.getContext() == null
                || !Settings.NETWORK_PROXY.get()) {
            return null;
        }
        Config config = parse(Settings.NETWORK_PROXY_TYPE.get(), Settings.NETWORK_PROXY_HOST.get(),
                Settings.NETWORK_PROXY_PORT.get(), Settings.NETWORK_PROXY_USER.get(),
                Settings.NETWORK_PROXY_PASSWORD.get());
        if (config == null) {
            Logger.printDebug(() -> "Network proxy: the switch is on, but the host or port is empty or unusable, so TikTok connects directly");
        }
        return config;
    }

    @Nullable
    private static Config current() {
        Config config = active;
        return config != null ? config : configured();
    }

    /**
     * What TikTok's stacks are pointed at: the proxy itself, or the relay in front of one with a
     * password. Should the relay fail to start, they go to the proxy with no sign-in, and the
     * proxy says no to that.
     */
    static synchronized Config route(Config config) {
        if (!config.hasCredentials()) return config;
        try {
            if (relay == null) relay = ProxyRelay.start(config);
            return relay.route();
        } catch (IOException | RuntimeException failure) {
            Logger.printException(() -> "Network proxy could not start its sign-in relay", failure);
            return config;
        }
    }

    // -- TTNet ----------------------------------------------------------------------------------

    /**
     * Called with TTNet's debug config as its engine builder reads it ("" on a release build),
     * just before TikTok parses it and hands its ttnet_proxy field to the builder. Off, it goes
     * back untouched. On, the same JSON comes back with the proxy rule in that field. A config
     * that doesn't parse goes back untouched too, since TikTok would refuse it either way.
     */
    public static String ttnetConfig(String original) {
        try {
            Config config = current();
            if (config == null) return original;
            JSONObject json;
            if (original == null || original.trim().isEmpty()) {
                json = new JSONObject();
            } else {
                try {
                    json = new JSONObject(original);
                } catch (JSONException unreadable) {
                    Logger.printInfo(() -> "Network proxy: TTNet's config file doesn't parse, so its network stack starts without the proxy");
                    return original;
                }
            }
            json.put(TTNET_PROXY_KEY, route(config).ttnetRules());
            Logger.printDebug(() -> "Network proxy: TikTok's network stack starts through the " + config.describe());
            return json.toString();
        } catch (JSONException | RuntimeException error) {
            Logger.printException(() -> "Network proxy could not hand TikTok's network stack the proxy", error);
            return original;
        }
    }

    // -- The JVM --------------------------------------------------------------------------------

    /**
     * Called once per process from the host application's attachBaseContext, after the
     * extension has its context and before TikTok builds a network client. Puts the selector in
     * place, and in TikTok's main process checks the proxy answers, then checks again as TikTok
     * comes back to the screen.
     */
    public static void install(Context context) {
        if (installed) return;
        installed = true;
        try {
            Config config = configured();
            if (config == null) return;
            active = config;
            Config route = route(config);
            ProxySelector.setDefault(new Selector(config, route, ProxySelector.getDefault()));
            Logger.printDebug(() -> "Network proxy: Java connections go through the " + config.describe()
                    + (route != config ? ", signing in through the relay" : ""));
            if (Utils.isMainProcess()) {
                check(config);
                // Queued, not now: during attachBaseContext the base context has no application yet.
                Utils.runOnMainThread(() -> follow(context, config));
            }
        } catch (RuntimeException error) {
            Logger.printException(() -> "Network proxy could not be put in place", error);
        }
    }

    /** Asks the proxy whether it answers, off the main thread, and says so if it doesn't. */
    static void check(Config config) {
        checkedAt = SystemClock.elapsedRealtime();
        checker.execute(() -> {
            Probe found = probe(config, PROBE_TIMEOUT_MS);
            for (int retry = 1; retry < PROBE_TRIES && found == Probe.UNREACHABLE; retry++) {
                found = probe(config, PROBE_TIMEOUT_MS);
            }
            report(config, found);
        });
    }

    /**
     * Asks the proxy itself after a connection through it failed. A failure there says little:
     * OkHttp reports one for any route that fails, a site the proxy can't reach or won't carry
     * included, and telling people their working proxy was down is how #304 read.
     */
    static void confirm(Config config) {
        if (!Utils.isMainProcess()) return;
        if (checkedAt != 0 && SystemClock.elapsedRealtime() - checkedAt < CONFIRM_AFTER_MS) return;
        check(config);
    }

    /**
     * Checks the proxy again whenever one of TikTok's screens comes back, at most once every
     * {@link #RECHECK_AFTER_MS}. TikTok's own stack never tells the JVM a connection failed, so
     * without this a proxy that stopped answering later in a session went unmentioned.
     */
    static void follow(Context context, Config config) {
        if (following) return;
        Context application = context.getApplicationContext();
        if (!(application instanceof Application)) {
            Logger.printDebug(() -> "Network proxy: no application to follow, so the proxy is only checked at start-up");
            return;
        }
        following = true;
        ((Application) application).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity resumed) {
                if (SystemClock.elapsedRealtime() - checkedAt >= RECHECK_AFTER_MS) check(config);
            }

            @Override public void onActivityCreated(Activity created, Bundle state) { }
            @Override public void onActivityStarted(Activity started) { }
            @Override public void onActivityPaused(Activity paused) { }
            @Override public void onActivityStopped(Activity stopped) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity destroyed) { }
        });
    }

    /** Whether a host is this phone or its local network, which the proxy never carries. */
    static boolean bypassed(@Nullable String rawHost, Config config) {
        if (rawHost == null) return true;
        String host = rawHost.startsWith("[") && rawHost.endsWith("]")
                ? rawHost.substring(1, rawHost.length() - 1) : rawHost;
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || lower.equals("localhost") || lower.endsWith(".localhost")
                || lower.endsWith(".local")) {
            return true;
        }
        if (lower.equalsIgnoreCase(config.host)) return true;
        // Only literals are looked at, so this never makes a DNS query.
        if (!IPV4.matcher(lower).matches() && lower.indexOf(':') < 0) return false;
        try {
            InetAddress address = InetAddress.getByName(lower);
            return address.isLoopbackAddress() || address.isAnyLocalAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                    || (address.getAddress().length == 16 && (address.getAddress()[0] & 0xFE) == 0xFC);
        } catch (IOException | RuntimeException notAnAddress) {
            return false;
        }
    }

    /** The JVM's default selector while the proxy is on. */
    static final class Selector extends ProxySelector {
        private final Config config;
        private final Config route;
        private final Proxy proxy;
        @Nullable private final ProxySelector previous;

        Selector(Config config, Config route, @Nullable ProxySelector previous) {
            this.config = config;
            this.route = route;
            this.proxy = route.javaProxy();
            this.previous = previous;
        }

        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null) throw new IllegalArgumentException("uri");
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            boolean carried = scheme.equals("http") || scheme.equals("https")
                    || scheme.equals("ws") || scheme.equals("wss")
                    // An HTTP proxy only carries HTTP; raw sockets go to a SOCKS one only.
                    || (scheme.equals("socket") && config.socks());
            if (!carried || bypassed(uri.getHost(), config)) return fallback(uri);
            return Collections.singletonList(proxy);
        }

        private List<Proxy> fallback(URI uri) {
            if (previous != null && !(previous instanceof Selector)) {
                try {
                    return previous.select(uri);
                } catch (RuntimeException ignored) {
                    // The platform's own selector refused it; a direct connection is its answer too.
                }
            }
            return Collections.singletonList(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            if (isProxy(address, route)) {
                confirm(config);
            } else if (previous != null && !(previous instanceof Selector)) {
                previous.connectFailed(uri, address, failure);
            }
        }
    }

    static boolean isProxy(@Nullable SocketAddress address, Config config) {
        if (!(address instanceof InetSocketAddress)) return false;
        InetSocketAddress socket = (InetSocketAddress) address;
        if (socket.getPort() != config.port) return false;
        if (config.host.equalsIgnoreCase(socket.getHostString())) return true;
        InetAddress resolved = socket.getAddress();
        return resolved != null && config.host.equalsIgnoreCase(resolved.getHostAddress());
    }

    // -- The start-up check ---------------------------------------------------------------------

    /**
     * Connects to the proxy straight, never through the selector, and does what the relay or
     * TikTok's stacks will do: a SOCKS5 greeting or a request for an HTTP tunnel, with the sign-in
     * when there's a user name. A proxy that wants a password nobody gave it, or turns down the one
     * given, is told apart from one that's down.
     */
    static Probe probe(Config config, int timeoutMs) {
        try (Socket socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(new InetSocketAddress(config.host, config.port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            if (config.socks()) return ProxyRelay.signInToSocks(config, in, out);
            int status = ProxyRelay.askForHttpTunnel(config, PROBE_TARGET, in, out);
            if (status < 0) return Probe.UNREACHABLE;
            if (status != 407) return Probe.REACHED;
            return config.hasCredentials() ? Probe.REFUSED_PASSWORD : Probe.NEEDS_PASSWORD;
        } catch (IOException | RuntimeException failure) {
            return Probe.UNREACHABLE;
        }
    }

    /**
     * Says when the proxy is down or won't take the sign-in, once until it answers again, so a
     * proxy that comes back and drops later is told about a second time.
     */
    static void report(Config config, Probe probe) {
        if (probe == Probe.REACHED) {
            reported.set(false);
            Logger.printDebug(() -> "Network proxy: the " + config.describe() + " answered");
            return;
        }
        if (!reported.compareAndSet(false, true)) return;
        // Every toast is written to the diagnostics buffer too, so the address is isolated there.
        String address = L10n.isolate(config.hostPort());
        if (probe == Probe.NEEDS_PASSWORD) {
            Logger.printInfo(() -> "Network proxy: the " + config.describe() + " wants a sign-in and none is set");
            Utils.showToastLong(L10n.f("Proxy %1$s needs a user name and password", address));
        } else if (probe == Probe.REFUSED_PASSWORD) {
            Logger.printInfo(() -> "Network proxy: the " + config.describe() + " turned down the sign-in");
            Utils.showToastLong(L10n.f("Proxy %1$s refused your user name or password", address));
        } else {
            Logger.printInfo(() -> "Network proxy: the " + config.describe() + " didn't answer");
            Utils.showToastLong(L10n.f("Proxy %1$s is down. Turn it off if TikTok won't load.", address));
        }
    }

    /** For tests: forget what this process put in place. */
    static synchronized void resetForTests() {
        installed = false;
        active = null;
        if (relay != null) relay.close();
        relay = null;
        reported.set(false);
        checkedAt = 0;
        following = false;
        checker = OWN_THREAD;
    }

    static boolean isInstalled() {
        return installed;
    }

    @Nullable
    static Config active() {
        return active;
    }
}

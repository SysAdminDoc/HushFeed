/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** HTTPS-only media transport with destination validation at every redirect hop. */
final class MediaTransport {
    /** Five redirects covers TikTok's ordinary CDN handoff without permitting an endless chain. */
    static final int MAX_REDIRECTS = 5;
    static final Client DEFAULT = new Client(InetAddress::getAllByName, PinnedMediaConnection::open);

    private MediaTransport() { }

    /**
     * Verdicts for the addresses asked about last. Playback quality asks about the same gears'
     * addresses from three player hooks on every video it opens, on the main thread, and parsing
     * a CDN address into a URI and a URL each time was most of that filter's cost there (#344).
     */
    private static final int SHAPE_MEMO = 64;
    private static final Map<String, Boolean> SHAPES =
            new LinkedHashMap<String, Boolean>(SHAPE_MEMO, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > SHAPE_MEMO;
                }
            };

    static boolean hasAllowedShape(String value) {
        if (value == null) return false;
        synchronized (SHAPES) {
            Boolean known = SHAPES.get(value);
            if (known != null) return known;
        }
        boolean allowed;
        try {
            target(value);
            allowed = true;
        } catch (IOException ignored) {
            allowed = false;
        }
        synchronized (SHAPES) {
            SHAPES.put(value, allowed);
        }
        return allowed;
    }

    static Response open(
            String value,
            MediaBudget.Deadline deadline,
            int connectTimeoutMs,
            int readTimeoutMs,
            String userAgent,
            boolean identityEncoding
    ) throws IOException {
        return DEFAULT.open(value, deadline, connectTimeoutMs, readTimeoutMs,
                userAgent, identityEncoding);
    }

    interface Resolver {
        InetAddress[] resolve(String host) throws IOException;
    }

    interface ConnectionOpener {
        URLConnection open(URL url, InetAddress address) throws IOException;
    }

    static final class Client {
        private final Resolver resolver;
        private final ConnectionOpener opener;

        Client(Resolver resolver, ConnectionOpener opener) {
            this.resolver = resolver;
            this.opener = opener;
        }

        Response open(
                String value,
                MediaBudget.Deadline deadline,
                int connectTimeoutMs,
                int readTimeoutMs,
                String userAgent,
                boolean identityEncoding
        ) throws IOException {
            if (deadline == null) deadline = MediaBudget.deadline();
            boolean gated = gated();
            MediaBudget.awaitRetry(deadline);
            Target current = target(value);
            Set<String> visited = new HashSet<>();
            int redirects = 0;
            while (true) {
                MediaBudget.awaitRetry(deadline);
                if (gated) checkNetwork(true);
                if (!visited.add(current.identity)) {
                    throw new IOException("Media redirect loop refused");
                }

                InetAddress[] addresses = publicAddresses(current.host, resolver);
                Response response = connect(current, addresses, deadline,
                        connectTimeoutMs, readTimeoutMs, userAgent, identityEncoding);
                HttpURLConnection connection = response.connection;
                boolean returned = false;
                try {
                    if (!isRedirect(response.statusCode)) {
                        returned = true;
                        return response;
                    }

                    if (redirects >= MAX_REDIRECTS) {
                        throw new IOException("Media redirect limit exceeded");
                    }
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        throw new IOException("Media redirect has no destination");
                    }
                    current = redirect(current.uri, location);
                    redirects++;
                } finally {
                    if (!returned) response.close();
                }
            }
        }

        /** Try only the checked snapshot, including IPv4 after an unreachable IPv6 answer. */
        private Response connect(Target target, InetAddress[] addresses,
                MediaBudget.Deadline deadline, int connectTimeoutMs, int readTimeoutMs,
                String userAgent, boolean identityEncoding) throws IOException {
            IOException failed = null;
            for (InetAddress address : addresses) {
                MediaBudget.check(deadline);
                HttpURLConnection connection = null;
                boolean connected = false;
                try {
                    URLConnection opened = opener.open(target.url, address);
                    if (!(opened instanceof HttpURLConnection)) {
                        throw new IOException("Media transport is not HTTP");
                    }
                    connection = (HttpURLConnection) opened;
                    connection.setInstanceFollowRedirects(false);
                    connection.setUseCaches(false);
                    connection.setConnectTimeout(MediaBudget.timeoutMillis(deadline, connectTimeoutMs));
                    connection.setReadTimeout(MediaBudget.timeoutMillis(deadline, readTimeoutMs));
                    if (identityEncoding) connection.setRequestProperty("Accept-Encoding", "identity");
                    if (userAgent != null && !userAgent.isEmpty()) {
                        connection.setRequestProperty("User-Agent", userAgent);
                    }
                    MediaBudget.check(deadline);
                    int statusCode = connection.getResponseCode();
                    MediaBudget.recordRetryAfter(connection.getHeaderField("Retry-After"), deadline);
                    connected = true;
                    return new Response(connection, statusCode, target.url, gated());
                } catch (MediaBudget.StopException stop) {
                    throw stop;
                } catch (IOException error) {
                    if (failed == null) failed = error;
                    else if (failed != error) failed.addSuppressed(error);
                } finally {
                    if (!connected && connection != null) connection.disconnect();
                }
            }
            if (failed != null) throw failed;
            throw new IOException("No checked media address is available");
        }
    }

    /**
     * Save only on Wi-Fi governs this thread's transfers: a network save runs on it and the
     * setting is on. A share link, a LIVE or a frame save is never held back.
     */
    static boolean gated() {
        return MediaJobScheduler.currentFetchesNetwork() && SaveNetwork.strict();
    }

    /** Refuses a transfer on a metered or unknown network, which ends a save of several files. */
    static void checkNetwork(boolean fresh) throws MediaBudget.StopException {
        if (!(fresh ? SaveNetwork.unmeteredNow() : SaveNetwork.unmetered())) {
            throw new MediaBudget.StopException("The network is metered or unknown",
                    MediaBudget.StopException.Reason.NETWORK);
        }
    }

    static final class Response implements AutoCloseable {
        private final HttpURLConnection connection;
        private boolean closed;
        final int statusCode;
        final URL url;
        /** Its body stops at the next read once the network turns metered. */
        private final boolean gated;

        private Response(HttpURLConnection connection, int statusCode, URL url, boolean gated) {
            this.connection = connection;
            this.statusCode = statusCode;
            this.url = url;
            this.gated = gated;
        }

        String header(String name) {
            return connection.getHeaderField(name);
        }

        InputStream inputStream() throws IOException {
            InputStream body = connection.getInputStream();
            return gated ? new GatedStream(body) : body;
        }

        String contentType() {
            return connection.getContentType();
        }

        @Override public void close() {
            if (!closed) {
                closed = true;
                connection.disconnect();
            }
        }
    }

    /** A body that checks the network before each read, which is a cached answer and costs nothing. */
    static final class GatedStream extends FilterInputStream {
        GatedStream(InputStream body) {
            super(body);
        }

        @Override public int read() throws IOException {
            checkNetwork(false);
            return super.read();
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            checkNetwork(false);
            return super.read(buffer, offset, length);
        }

        @Override public long skip(long count) throws IOException {
            checkNetwork(false);
            return super.skip(count);
        }
    }

    /**
     * The IPv4 blocks IANA's special-purpose registry marks as not globally reachable, as
     * {first address, prefix length}, with the deprecated 6to4 relay block and everything from
     * 224.0.0.0 up (multicast, reserved, broadcast). None can be a public CDN endpoint, and each
     * one accepted widens the local-network boundary. Checked 2026-09-27 against
     * https://www.iana.org/assignments/iana-ipv4-special-registry.
     */
    private static final int[][] NOT_GLOBAL_V4 = {
            {0x00000000, 8},    // this network
            {0x0A000000, 8},    // private
            {0x64400000, 10},   // shared address space, carrier NAT
            {0x7F000000, 8},    // loopback
            {0xA9FE0000, 16},   // link-local
            {0xAC100000, 12},   // private
            {0xC0000000, 24},   // IETF protocol assignments
            {0xC0000200, 24},   // documentation, TEST-NET-1
            {0xC0586300, 24},   // deprecated 6to4 relay anycast
            {0xC0A80000, 16},   // private
            {0xC6120000, 15},   // benchmarking
            {0xC6336400, 24},   // documentation, TEST-NET-2
            {0xCB007100, 24},   // documentation, TEST-NET-3
            {0xE0000000, 3},    // multicast, reserved and broadcast
    };

    static boolean isPublicAddress(InetAddress address) {
        if (address == null) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return isGlobalV4(ipv4At(bytes, 0));
        if (bytes.length == 16) return isGlobalV6(bytes);
        return false;
    }

    /**
     * The synthetic answers a fake-IP resolver hands out: 198.18.0.0/15, the pool Clash and mihomo
     * (198.18.0.1/16), Xray and sing-box use, and unique-local fc00::/7 for IPv6. Proxy apps that
     * run in fake-IP mode answer every proxied hostname from their pool and map each address back
     * to its hostname when the connection reaches them, so a TikTok CDN host comes back as
     * 198.18.x.x on those phones (#145). Xray and sing-box take fc00::/18, but mihomo has no
     * default IPv6 pool and its documented one is fdfe:dcba:9876::1/64, so with IPv6 on every save
     * failed (#387). Only HTTPS is opened, and TLS still authenticates the hostname.
     */
    static boolean isProxyMappedAddress(InetAddress address) {
        if (address == null) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) return (bytes[0] & 255) == 198 && (bytes[1] & 0xfe) == 18;
        if (bytes.length == 16) return (bytes[0] & 0xfe) == 0xfc;
        return false;
    }

    /**
     * The special block a refused address sits in, so a diagnostic report says why a save was
     * refused without carrying the address itself (#387).
     */
    static String blockOf(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int value = ipv4At(bytes, 0);
            for (int[] block : NOT_GLOBAL_V4) {
                int mask = -1 << (32 - block[1]);
                if ((value & mask) == (block[0] & mask)) {
                    return ((block[0] >>> 24) & 255) + "." + ((block[0] >>> 16) & 255) + "."
                            + ((block[0] >>> 8) & 255) + "." + (block[0] & 255) + "/" + block[1];
                }
            }
            return "IPv4";
        }
        if ((bytes[0] & 0xfe) == 0xfc) return "fc00::/7";
        if ((bytes[0] & 255) == 0xfe && (bytes[1] & 0xc0) == 0x80) return "fe80::/10";
        if ((bytes[0] & 255) == 0xff) return "ff00::/8";
        if (bytes[0] == 0 && bytes[1] == 0) return "::/16";
        return "a special IPv6 block";
    }

    /** An address a checked media connection may open: public, or from a fake-IP pool. */
    static boolean isMediaAddress(InetAddress address) {
        return isPublicAddress(address) || isProxyMappedAddress(address);
    }

    private static boolean isGlobalV4(int address) {
        for (int[] block : NOT_GLOBAL_V4) {
            int mask = -1 << (32 - block[1]);
            if ((address & mask) == (block[0] & mask)) return false;
        }
        return true;
    }

    /**
     * Global unicast (2000::/3) only, which leaves out the unspecified and loopback addresses, the
     * discard-only, SRv6, unique-local, link-local and multicast blocks at once, and then the
     * special blocks inside it. An IPv4 address carried in IPv6 is judged as that IPv4 address.
     * Checked 2026-09-27 against https://www.iana.org/assignments/iana-ipv6-special-registry.
     */
    private static boolean isGlobalV6(byte[] bytes) {
        boolean zeroTo80 = true;
        for (int i = 0; i < 10; i++) zeroTo80 &= bytes[i] == 0;
        // IPv4-mapped, ::ffff:0:0/96.
        if (zeroTo80 && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
            return isGlobalV4(ipv4At(bytes, 12));
        }
        // The NAT64 well-known prefix, 64:ff9b::/96, which DNS64 answers with on IPv6-only mobile
        // networks for a host that has only an IPv4 address: as global as the address inside it.
        if (bytes[0] == 0 && bytes[1] == 0x64 && bytes[2] == (byte) 0xff && bytes[3] == (byte) 0x9b) {
            boolean zeroTo96 = true;
            for (int i = 4; i < 12; i++) zeroTo96 &= bytes[i] == 0;
            if (zeroTo96) return isGlobalV4(ipv4At(bytes, 12));
        }
        if ((bytes[0] & 0xe0) != 0x20) return false;
        int first = ((bytes[0] & 255) << 8) | (bytes[1] & 255);
        int second = ((bytes[2] & 255) << 8) | (bytes[3] & 255);
        if (first == 0x2001 && (second & 0xfe00) == 0) return false;   // IETF protocol assignments, Teredo among them
        if (first == 0x2001 && second == 0x0db8) return false;         // documentation
        if (first == 0x2002) return false;                             // 6to4
        if (first == 0x3fff && (second & 0xf000) == 0) return false;   // documentation, 3fff::/20
        return true;
    }

    private static int ipv4At(byte[] bytes, int offset) {
        return ((bytes[offset] & 255) << 24) | ((bytes[offset + 1] & 255) << 16)
                | ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }

    private static InetAddress[] publicAddresses(String host, Resolver resolver) throws IOException {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (RuntimeException error) {
            throw new IOException("Media hostname could not be resolved safely", error);
        }
        if (addresses == null || addresses.length == 0) {
            throw new IOException("Media hostname has no address");
        }
        // Validate the same snapshot that connection attempts will consume.
        addresses = addresses.clone();
        // A fake-IP resolver answers a proxied hostname from its pool alone. An answer from the
        // pool beside a real one isn't that, so a mixed set is judged address by address below.
        boolean proxyMapped = true;
        for (InetAddress address : addresses) proxyMapped &= isProxyMappedAddress(address);
        if (proxyMapped) return addresses;
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new IOException("Media URL resolves to a non-public address in " + blockOf(address));
            }
        }
        return addresses;
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 300 || statusCode == 301 || statusCode == 302
                || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private static Target redirect(URI base, String location) throws IOException {
        try {
            return target(base.resolve(new URI(location.trim())).toString());
        } catch (IllegalArgumentException | URISyntaxException error) {
            throw new IOException("Media redirect destination is invalid", error);
        }
    }

    private static Target target(String value) throws IOException {
        if (value == null) throw new IOException("Media URL is missing");
        try {
            URI uri = new URI(value.trim()).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Media URL must use HTTPS");
            }
            if (uri.getRawUserInfo() != null) {
                throw new IOException("Media URL cannot contain user information");
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                throw new IOException("Media URL has no hostname");
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            host = host.toLowerCase(Locale.ROOT);
            int port = uri.getPort() < 0 ? 443 : uri.getPort();
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            String identity = host + ":" + port + path
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            return new Target(uri, uri.toURL(), host, identity);
        } catch (URISyntaxException | IllegalArgumentException error) {
            throw new IOException("Media URL is invalid", error);
        }
    }

    private static final class Target {
        final URI uri;
        final URL url;
        final String host;
        final String identity;

        Target(URI uri, URL url, String host, String identity) {
            this.uri = uri;
            this.url = url;
            this.host = host;
            this.identity = identity;
        }
    }
}

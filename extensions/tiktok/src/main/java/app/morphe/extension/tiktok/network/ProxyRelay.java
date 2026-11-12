/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.network;

import android.util.Base64;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Signs in to a proxy on behalf of TikTok's network stacks, which can't do it themselves.
 *
 * <p>TTNet takes a Chromium proxy rule, a rule has no place for a user name or password, and
 * TikTok's own OkHttp clients never ask {@link java.net.Authenticator}. So a proxy with a password
 * turned away every API request and TikTok reported no connection (#304). With a user name set,
 * each process listens on a port of its own on the loopback address and speaks plain SOCKS5 to
 * those stacks, and this class opens the tunnel at the real proxy, sign-in included, over a socket
 * the proxy selector never sees.
 *
 * <p>Loopback isn't private to one app, so another app on the phone could reach the port while
 * TikTok runs. It never learns the password, and the relay only opens tunnels to the domains
 * TikTok's own stacks use, so it can't send its traffic anywhere else through the proxy. The user
 * name row says the port can be reached. The user name, password and tunnel targets are never
 * logged. A turned-down host is, once, so a domain TikTok starts using shows up in a debug log.
 */
final class ProxyRelay {
    private static final String THREAD_NAME = "Hushfeed proxy relay";
    private static final long THREAD_STACK_BYTES = 128 * 1024L;
    private static final int BACKLOG = 64;
    /** Two threads a connection, and TikTok holds a few dozen at most. */
    private static final int MAX_CONNECTIONS = 256;
    private static final int PUMP_BUFFER_BYTES = 16 * 1024;
    /** A slow proxy is why people notice a timeout, so these leave room for one. */
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int HANDSHAKE_TIMEOUT_MS = 30_000;
    private static final int LONGEST_HEADER_BYTES = 16 * 1024;

    private static final int SOCKS = 5;
    private static final int METHOD_NONE = 0;
    private static final int METHOD_PASSWORD = 2;
    private static final int METHOD_REFUSED = 0xFF;
    private static final int COMMAND_CONNECT = 1;
    private static final int ADDRESS_IPV4 = 1;
    private static final int ADDRESS_NAME = 3;
    private static final int ADDRESS_IPV6 = 4;
    private static final int ANSWER_OK = 0;
    private static final int ANSWER_FAILED = 1;
    private static final int ANSWER_NOT_ALLOWED = 2;
    private static final int ANSWER_COMMAND_UNSUPPORTED = 7;
    private static final int ANSWER_ADDRESS_UNSUPPORTED = 8;

    /**
     * Where TikTok's own stacks connect: ByteDance's API, CDN, effect, shop and short-link domains,
     * and the few outside services TikTok's screens call (GIF search, shop payments, Facebook and
     * Apple Music links, push sign-up). Read from 47.7.3's dex strings. A name matches an entry or
     * any name under it.
     */
    private static final Set<String> TIKTOK_DOMAINS = new HashSet<>(Arrays.asList(
            "braintreegateway.com", "bytedance.com", "bytedance.net", "byteeffecttos-g.com", "byteimg.com",
            "byteintl.com", "byteintlapi.com", "byteoversea.com", "byteoversea.net", "capcut.com",
            "firebaseinstallations.googleapis.com", "giphy.com", "graph.facebook.com", "helo-api.com",
            "ibytedtos.com", "ibyteimg.com", "ifdmimgs.com", "ifdmstatic.com", "ipstatp.com", "isnssdk.com",
            "lemon8-app.com", "letschattos3.com", "music.apple.com", "muscdn.com", "musical.ly",
            "oecsccdn.com", "oecstatic.com", "p16-tiktokcdn-com.akamaized.net", "paypal.com", "pipopay.com",
            "pipopayment.com", "pipopayment.us", "pstap.com", "pstatp.com", "resso.app", "resso.com",
            "sgpstatp.com", "sgsnssdk.com", "snssdk.com", "stripe.com", "tenor.com", "tik-global.com",
            "tik-lol.com", "tik-to.com", "tik-tokapi.com", "tik-tokcdn.com", "tikitoks.com", "tiklol.com",
            "tiktok.com", "tiktok-eu.net", "tiktok-minis.com", "tiktok-minis.us", "tiktok-row.net",
            "tiktok-row.org", "tiktok-shops.eu", "tiktok-shops.us", "tiktok-us.org", "tiktokapis.com",
            "tiktokcdn.com", "tiktokcdn-eu.com", "tiktokcdn-us.com", "tiktokv.com", "tiktokv.eu",
            "tiktokv.us", "tiktokw.eu", "tiktokw.us", "tokopedia.com", "tokopediax.com", "tt-best.com",
            "tt-in.com", "tt-lol.com", "tt-mov.com", "tt-video.com", "ttwstatic.com"));

    /** Turned-down hosts already logged. Capped, since whoever reaches the port picks the names. */
    private static final Set<String> REFUSALS_LOGGED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final int MOST_REFUSALS_LOGGED = 64;

    /** Starts each of a relay's threads. A test passes one that stands in for a phone out of threads. */
    interface ThreadStarter {
        void start(Thread thread);
    }

    private final NetworkProxy.Config upstream;
    private final ServerSocket server;
    private final ThreadStarter threads;
    private final AtomicInteger connections = new AtomicInteger();

    private ProxyRelay(NetworkProxy.Config upstream, ServerSocket server, ThreadStarter threads) {
        this.upstream = upstream;
        this.server = server;
        this.threads = threads;
    }

    /** Binds a free loopback port and starts answering on it. */
    static ProxyRelay start(NetworkProxy.Config upstream) throws IOException {
        return start(upstream, Thread::start);
    }

    static ProxyRelay start(NetworkProxy.Config upstream, ThreadStarter threads) throws IOException {
        // By address, so this can't turn into a lookup on the main thread during start-up.
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        ProxyRelay relay = new ProxyRelay(upstream, new ServerSocket(0, BACKLOG, loopback), threads);
        if (!relay.startThread(relay::acceptLoop)) {
            relay.close();
            throw new IOException("no thread to accept on");
        }
        return relay;
    }

    /** Connections being served now. Tests read it. */
    int openConnections() {
        return connections.get();
    }

    /** Where TikTok's stacks point instead of the proxy. It has no sign-in of its own. */
    NetworkProxy.Config route() {
        return new NetworkProxy.Config(NetworkProxy.TYPE_SOCKS5,
                server.getInetAddress().getHostAddress(), server.getLocalPort(), "", "");
    }

    void close() {
        closeQuietly(server);
    }

    /**
     * False when the phone couldn't start it. At the process's thread limit Android throws
     * OutOfMemoryError from start(). Let out of the accept loop or a connection's thread, it went
     * to Android's default handler, which ends TikTok.
     */
    private boolean startThread(Runnable task) {
        Thread thread = new Thread(null, task, THREAD_NAME, THREAD_STACK_BYTES);
        thread.setDaemon(true);
        try {
            threads.start(thread);
            return true;
        } catch (OutOfMemoryError noThread) {
            Logger.printDebug(() -> "Network proxy relay: couldn't start a thread, " + noThread.getMessage());
            return false;
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException failure) {
                if (server.isClosed()) return;
                Logger.printDebug(() -> "Network proxy relay: accept failed, " + failure.getClass().getSimpleName());
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    return;
                }
                continue;
            }
            if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                connections.decrementAndGet();
                closeQuietly(client);
                continue;
            }
            boolean started = startThread(() -> {
                try {
                    serve(client);
                } finally {
                    connections.decrementAndGet();
                }
            });
            if (!started) {
                // Nothing will serve it. TikTok retries, and threads free up as connections end.
                connections.decrementAndGet();
                closeQuietly(client);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    /** One connection from TikTok: the SOCKS5 greeting and request, the tunnel, then the bytes. */
    private void serve(Socket client) {
        Socket remote = null;
        try {
            client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            InputStream fromClient = client.getInputStream();
            OutputStream toClient = client.getOutputStream();
            if (!greet(fromClient, toClient)) return;
            Target target = readRequest(fromClient, toClient);
            if (target == null) return;

            int answer;
            try {
                remote = openTunnel(target);
                answer = ANSWER_OK;
            } catch (TunnelRefused refused) {
                answer = refused.answer;
                Logger.printDebug(() -> "Network proxy relay: the proxy refused a tunnel, " + refused.getMessage());
            } catch (IOException failure) {
                answer = ANSWER_FAILED;
                Logger.printDebug(() -> "Network proxy relay: couldn't open a tunnel, " + failure.getClass().getSimpleName());
            }
            answer(toClient, answer);
            if (answer != ANSWER_OK) return;

            client.setSoTimeout(0);
            remote.setSoTimeout(0);
            Socket tunnel = remote;
            boolean started = startThread(() -> {
                pump(tunnel, client);
                closeQuietly(tunnel);
                closeQuietly(client);
            });
            // With no thread for the way back the tunnel can't carry anything, so both ends close.
            if (!started) return;
            pump(client, tunnel);
        } catch (IOException | RuntimeException failure) {
            // TikTok hung up or timed out mid-handshake. It retries on its own.
            Logger.printDebug(() -> "Network proxy relay: a connection ended early, " + failure.getClass().getSimpleName());
        } finally {
            closeQuietly(remote);
            closeQuietly(client);
        }
    }

    /** Takes the client's greeting. Offering no sign-in is all TikTok's stacks ever need. */
    private static boolean greet(InputStream in, OutputStream out) throws IOException {
        if (in.read() != SOCKS) return false;
        int count = in.read();
        if (count < 0) return false;
        byte[] methods = readExactly(in, count);
        for (byte method : methods) {
            if (method == METHOD_NONE) {
                out.write(new byte[]{SOCKS, METHOD_NONE});
                out.flush();
                return true;
            }
        }
        out.write(new byte[]{SOCKS, (byte) METHOD_REFUSED});
        out.flush();
        return false;
    }

    @Nullable
    private static Target readRequest(InputStream in, OutputStream out) throws IOException {
        byte[] head = readExactly(in, 4);
        if (head[0] != SOCKS) return null;
        // A refusal waits until the whole request is read. A socket closed with bytes still
        // unread sends a reset rather than an end, and the reset can reach TikTok first and throw
        // the answer away.
        int refusal = head[1] == COMMAND_CONNECT ? ANSWER_OK : ANSWER_COMMAND_UNSUPPORTED;
        int type = head[3] & 0xFF;
        byte[] address;
        String host;
        if (type == ADDRESS_IPV4 || type == ADDRESS_IPV6) {
            // TikTok's stacks ask by name. An address comes from a caller that looked the name
            // up itself, and no list of domains can say where an address belongs.
            address = readExactly(in, type == ADDRESS_IPV4 ? 4 : 16);
            host = InetAddress.getByAddress(address).getHostAddress();
            if (refusal == ANSWER_OK) {
                refusal = ANSWER_NOT_ALLOWED;
                logRefusal(host);
            }
        } else if (type == ADDRESS_NAME) {
            int length = in.read();
            if (length < 0) return null;
            byte[] name = readExactly(in, length);
            ByteArrayOutputStream whole = new ByteArrayOutputStream(length + 1);
            whole.write(length);
            whole.write(name);
            address = whole.toByteArray();
            host = new String(name, StandardCharsets.US_ASCII);
            if ((length == 0 || !isHostName(name)) && refusal == ANSWER_OK) {
                // A line break, a space or a colon in a "name" would let whoever reached this port
                // put lines or a port of their own into the CONNECT request that carries the
                // user's sign-in.
                refusal = ANSWER_ADDRESS_UNSUPPORTED;
            } else if (!isTikTokHost(host) && refusal == ANSWER_OK) {
                refusal = ANSWER_NOT_ALLOWED;
                logRefusal(host);
            }
        } else {
            // No telling how long an address of an unknown type is, so this one can't wait.
            answer(out, refusal == ANSWER_OK ? ANSWER_ADDRESS_UNSUPPORTED : refusal);
            return null;
        }
        byte[] port = readExactly(in, 2);
        if (refusal != ANSWER_OK) {
            answer(out, refusal);
            return null;
        }
        return new Target(type, address, port, host, ((port[0] & 0xFF) << 8) | (port[1] & 0xFF));
    }

    /**
     * Letters, digits, dots, hyphens and underscores: what a host name is made of. An IPv6
     * literal by name has colons, and it's an address the domain list can't place either.
     */
    private static boolean isHostName(byte[] name) {
        for (byte b : name) {
            boolean digit = b >= '0' && b <= '9';
            boolean letter = (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z');
            if (!letter && !digit && b != '.' && b != '-' && b != '_') return false;
        }
        return true;
    }

    /** A name on {@link #TIKTOK_DOMAINS} or under one. A bare IPv4 address never is. */
    static boolean isTikTokHost(String name) {
        String rest = name.toLowerCase(Locale.ROOT);
        // A fully qualified name ends in a dot, and it's the same host.
        if (rest.endsWith(".")) rest = rest.substring(0, rest.length() - 1);
        while (true) {
            if (TIKTOK_DOMAINS.contains(rest)) return true;
            int dot = rest.indexOf('.');
            if (dot < 0) return false;
            rest = rest.substring(dot + 1);
        }
    }

    /** Once a host, and only so many, since another app could send any number of names. */
    private static void logRefusal(String host) {
        if (REFUSALS_LOGGED.size() >= MOST_REFUSALS_LOGGED || !REFUSALS_LOGGED.add(host)) return;
        Logger.printDebug(() -> "Network proxy relay: turned down " + host + ", which isn't a domain TikTok uses");
    }

    /** The reply TikTok's stack reads: it never looks at the bound address, so it's all zeros. */
    private static void answer(OutputStream out, int answer) throws IOException {
        out.write(new byte[]{SOCKS, (byte) answer, 0, ADDRESS_IPV4, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    /** Connects to the real proxy, signs in and asks for the tunnel. The socket is ready to carry bytes. */
    private Socket openTunnel(Target target) throws IOException {
        // NO_PROXY: with the selector on, a plain socket would come straight back to this relay.
        Socket remote = new Socket(Proxy.NO_PROXY);
        boolean opened = false;
        try {
            remote.connect(new InetSocketAddress(upstream.host, upstream.port), CONNECT_TIMEOUT_MS);
            remote.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            remote.setTcpNoDelay(true);
            InputStream in = remote.getInputStream();
            OutputStream out = remote.getOutputStream();
            if (upstream.socks()) {
                openSocksTunnel(in, out, target);
            } else {
                int status = askForHttpTunnel(upstream, target.hostPort(), in, out);
                if (status >= 0) skipHeaders(in);
                if (status < 200 || status >= 300) throw new TunnelRefused(ANSWER_FAILED, "HTTP " + status);
            }
            opened = true;
            return remote;
        } finally {
            if (!opened) closeQuietly(remote);
        }
    }

    private void openSocksTunnel(InputStream in, OutputStream out, Target target) throws IOException {
        NetworkProxy.Probe signedIn = signInToSocks(upstream, in, out);
        if (signedIn != NetworkProxy.Probe.REACHED) throw new TunnelRefused(ANSWER_FAILED, signedIn.name());
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        request.write(new byte[]{SOCKS, COMMAND_CONNECT, 0, (byte) target.type});
        request.write(target.address);
        request.write(target.port);
        out.write(request.toByteArray());
        out.flush();

        byte[] head = readExactly(in, 4);
        if (head[0] != SOCKS) throw new IOException("not SOCKS5");
        // The answer goes back as sent, so TikTok tells a dead site from a dead proxy as before.
        if (head[1] != ANSWER_OK) throw new TunnelRefused(head[1] & 0xFF, "SOCKS " + (head[1] & 0xFF));
        int type = head[3] & 0xFF;
        if (type == ADDRESS_IPV4) readExactly(in, 4);
        else if (type == ADDRESS_IPV6) readExactly(in, 16);
        else if (type == ADDRESS_NAME) readExactly(in, Math.max(in.read(), 0));
        else throw new IOException("address type " + type);
        readExactly(in, 2);
    }

    /**
     * Says hello to a SOCKS5 proxy and signs in if it asks. REACHED means it will take a request
     * on this connection now. Shared with the start-up check, so it tests what the relay does.
     */
    static NetworkProxy.Probe signInToSocks(NetworkProxy.Config config, InputStream in, OutputStream out)
            throws IOException {
        boolean credentials = config.hasCredentials();
        out.write(credentials
                ? new byte[]{SOCKS, 2, METHOD_NONE, METHOD_PASSWORD}
                : new byte[]{SOCKS, 1, METHOD_NONE});
        out.flush();
        int version = in.read();
        int method = in.read();
        if (version != SOCKS || method < 0) return NetworkProxy.Probe.UNREACHABLE;
        if (method == METHOD_NONE) return NetworkProxy.Probe.REACHED;
        if (!credentials) return NetworkProxy.Probe.NEEDS_PASSWORD;
        if (method != METHOD_PASSWORD) return NetworkProxy.Probe.REFUSED_PASSWORD;

        byte[] user = config.user.getBytes(StandardCharsets.UTF_8);
        byte[] password = config.password.getBytes(StandardCharsets.UTF_8);
        // RFC 1929 gives each one length byte, so a longer one can't be sent at all.
        if (user.length > 255 || password.length > 255) return NetworkProxy.Probe.REFUSED_PASSWORD;
        ByteArrayOutputStream login = new ByteArrayOutputStream(user.length + password.length + 3);
        login.write(1);
        login.write(user.length);
        login.write(user);
        login.write(password.length);
        login.write(password);
        out.write(login.toByteArray());
        out.flush();
        int answerVersion = in.read();
        int status = in.read();
        if (answerVersion < 0 || status < 0) return NetworkProxy.Probe.UNREACHABLE;
        return status == 0 ? NetworkProxy.Probe.REACHED : NetworkProxy.Probe.REFUSED_PASSWORD;
    }

    /**
     * Asks an HTTP proxy for a tunnel, signing in when there's a user name, and returns its status
     * code, or -1 when what answered isn't an HTTP proxy. Headers after the status line stay unread.
     */
    static int askForHttpTunnel(NetworkProxy.Config config, String target, InputStream in, OutputStream out)
            throws IOException {
        StringBuilder request = new StringBuilder()
                .append("CONNECT ").append(target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(target).append("\r\n");
        if (config.hasCredentials()) {
            String signIn = config.user + ":" + config.password;
            request.append("Proxy-Authorization: Basic ")
                    .append(Base64.encodeToString(signIn.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP))
                    .append("\r\n");
        }
        out.write(request.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
        String status = readLine(in);
        if (status == null || !status.startsWith("HTTP/")) return -1;
        String[] parts = status.split(" ");
        if (parts.length < 2) return -1;
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private static void skipHeaders(InputStream in) throws IOException {
        int total = 0;
        while (total < LONGEST_HEADER_BYTES) {
            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            total += line.length();
        }
        throw new IOException("headers too long");
    }

    @Nullable
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        while (line.length() < 8192) {
            int next = in.read();
            if (next < 0) return line.length() == 0 ? null : line.toString();
            if (next == '\n') break;
            if (next != '\r') line.append((char) next);
        }
        return line.toString();
    }

    private static byte[] readExactly(InputStream in, int count) throws IOException {
        byte[] bytes = new byte[count];
        int read = 0;
        while (read < count) {
            int got = in.read(bytes, read, count - read);
            if (got < 0) throw new IOException("closed mid-handshake");
            read += got;
        }
        return bytes;
    }

    /** Copies one way until either side ends. */
    private static void pump(Socket from, Socket to) {
        byte[] buffer = new byte[PUMP_BUFFER_BYTES];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int count;
            while ((count = in.read(buffer)) >= 0) {
                out.write(buffer, 0, count);
                out.flush();
            }
        } catch (IOException ended) {
            // The other side closed. Closing both ends is all that's left, and the caller does it.
        }
    }

    private static void closeQuietly(@Nullable Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Already gone.
        }
    }

    /** What TikTok asked to reach, in the shapes both kinds of proxy want it. */
    private static final class Target {
        final int type;
        final byte[] address;
        final byte[] port;
        final String host;
        final int portNumber;

        Target(int type, byte[] address, byte[] port, String host, int portNumber) {
            this.type = type;
            this.address = address;
            this.port = port;
            this.host = host;
            this.portNumber = portNumber;
        }

        String hostPort() {
            return host + ":" + portNumber;
        }
    }

    /** The proxy answered and said no. */
    private static final class TunnelRefused extends IOException {
        final int answer;

        TunnelRefused(int answer, String detail) {
            super(detail);
            this.answer = answer;
        }
    }
}

package app.morphe.extension.tiktok.network;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The relay against a proxy that really wants a password (#304): TikTok's stacks speak SOCKS5 with
 * no sign-in on one side, and the proxy sees the sign-in and the site they asked for on the other.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProxyRelayTest {
    private static final String USER = "alice";
    private static final String PASSWORD = "s3cret-pass";
    private static final int TIMEOUT_MS = 5000;

    private final List<ServerSocket> servers = new ArrayList<>();
    private final List<Socket> sockets = new ArrayList<>();
    private ProxyRelay relay;

    @After public void tearDown() throws Exception {
        if (relay != null) relay.close();
        for (Socket socket : sockets) socket.close();
        for (ServerSocket server : servers) server.close();
    }

    @Test public void theRelayIsAPlainSocksProxyOnLoopback() throws Exception {
        relay = ProxyRelay.start(NetworkProxy.parse("http", "proxy.example.com", "8080", USER, PASSWORD));
        NetworkProxy.Config route = relay.route();
        assertEquals("127.0.0.1", route.host);
        assertTrue(route.socks());
        assertFalse("nothing to sign in to", route.hasCredentials());
        assertEquals("socks5://127.0.0.1:" + route.port, route.ttnetRules());
    }

    @Test public void anHttpProxyGetsTheSignInAndTheSiteAndBytesFlowBothWays() throws Exception {
        StringBuilder request = new StringBuilder();
        CountDownLatch asked = new CountDownLatch(1);
        ServerSocket proxy = proxy(socket -> {
            request.append(readHead(socket.getInputStream()));
            asked.countDown();
            socket.getOutputStream().write(
                    "HTTP/1.1 200 Connection established\r\nVia: test\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            echo(socket);
        });
        relay = ProxyRelay.start(config("http", proxy, USER, PASSWORD));

        Socket client = askForSite("api16.example.test", 443);
        assertEquals("the tunnel opened", 0, answerTo(client));
        assertTrue(asked.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        String expected = Base64.getEncoder().encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        assertTrue(request.toString(), request.toString().startsWith("CONNECT api16.example.test:443 HTTP/1.1\r\n"));
        assertTrue(request.toString(), request.toString().contains("Proxy-Authorization: Basic " + expected + "\r\n"));
        assertEchoes(client);
    }

    @Test public void aSocksProxyGetsTheSignInAndTheSiteAndBytesFlowBothWays() throws Exception {
        List<String> seen = new ArrayList<>();
        CountDownLatch asked = new CountDownLatch(1);
        ServerSocket proxy = proxy(socket -> {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            seen.add(java.util.Arrays.toString(readExactly(in, 4)));
            out.write(new byte[]{5, 2});
            assertEquals(1, in.read());
            seen.add(new String(readExactly(in, in.read()), StandardCharsets.UTF_8));
            seen.add(new String(readExactly(in, in.read()), StandardCharsets.UTF_8));
            out.write(new byte[]{1, 0});
            byte[] head = readExactly(in, 4);
            seen.add(java.util.Arrays.toString(head));
            seen.add(new String(readExactly(in, in.read()), StandardCharsets.US_ASCII));
            seen.add(java.util.Arrays.toString(readExactly(in, 2)));
            asked.countDown();
            // An answer with a name as its bound address, which is longer than the usual one.
            out.write(new byte[]{5, 0, 0, 3, 1, 'x', 0, 80});
            echo(socket);
        });
        relay = ProxyRelay.start(config("socks5", proxy, USER, PASSWORD));

        Socket client = askForSite("api16.example.test", 443);
        assertEquals(0, answerTo(client));
        assertTrue(asked.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(List.of("[5, 2, 0, 2]", USER, PASSWORD, "[5, 1, 0, 3]", "api16.example.test", "[1, -69]"), seen);
        assertEchoes(client);
    }

    /** What TikTok's OkHttp clients do: java.net's own SOCKS code, pointed at the relay. */
    @Test public void javaSocketsReachTheProxyThroughTheRelayToo() throws Exception {
        ServerSocket proxy = proxy(socket -> {
            readHead(socket.getInputStream());
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            echo(socket);
        });
        relay = ProxyRelay.start(config("http", proxy, USER, PASSWORD));

        Socket client = new Socket(relay.route().javaProxy());
        sockets.add(client);
        client.setSoTimeout(TIMEOUT_MS);
        client.connect(InetSocketAddress.createUnresolved("api16.example.test", 443), TIMEOUT_MS);
        assertEchoes(client);
    }

    @Test public void addressesGetTheShapeAnHttpProxyExpects() throws Exception {
        List<String> targets = Collections.synchronizedList(new ArrayList<>());
        ServerSocket proxy = proxy(socket -> {
            targets.add(readHead(socket.getInputStream()).split("\r\n")[0]);
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        });
        relay = ProxyRelay.start(config("http", proxy, USER, PASSWORD));

        Socket v4 = open();
        request(v4, 1, InetAddress.getByName("192.0.2.7").getAddress(), 443);
        assertEquals(0, answerTo(v4));
        Socket v6 = open();
        request(v6, 4, InetAddress.getByName("2001:db8::1").getAddress(), 8443);
        assertEquals(0, answerTo(v6));

        assertEquals(List.of("CONNECT 192.0.2.7:443 HTTP/1.1", "CONNECT [2001:db8:0:0:0:0:0:1]:8443 HTTP/1.1"), targets);
    }

    /** A name with a line break in it would put lines of the client's choosing into the CONNECT request. */
    @Test public void aNameThatCouldWriteIntoTheConnectRequestIsTurnedDown() throws Exception {
        List<String> targets = Collections.synchronizedList(new ArrayList<>());
        ServerSocket proxy = proxy(socket -> {
            targets.add(readHead(socket.getInputStream()).split("\r\n")[0]);
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        });
        relay = ProxyRelay.start(config("http", proxy, USER, PASSWORD));

        Socket forged = askForSite("api16.example.test\r\nX-Forged: yes\r\n", 443);
        assertEquals(8, answerTo(forged));
        assertEquals("then it hangs up", -1, forged.getInputStream().read());
        Socket spaced = askForSite("api16.example.test HTTP/1.1", 443);
        assertEquals(8, answerTo(spaced));
        // Letters beside a colon aren't an IPv6 literal; in brackets they'd carry a port of their own.
        Socket ported = askForSite("evil.example:80", 443);
        assertEquals(8, answerTo(ported));
        Socket literal = askForSite("2001:db8::1", 443);
        assertEquals(0, answerTo(literal));

        assertEquals(List.of("CONNECT [2001:db8::1]:443 HTTP/1.1"), targets);
    }

    /**
     * A client's small writes can reach the relay apart. A relay that refused on the name and
     * hung up before the port came in had that port land on a closed socket, which answers with a
     * reset, and the reset threw away the refusal the client hadn't read yet. So a refused request
     * is read to its end first.
     */
    @Test public void aRefusalIsStillThereToReadWhenTheRequestEndsLate() throws Exception {
        relay = ProxyRelay.start(NetworkProxy.parse("http", "proxy.example.com", "8080", USER, PASSWORD));
        byte[] name = "evil.example:80".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream named = new ByteArrayOutputStream();
        named.write(new byte[]{5, 1, 0, 3, (byte) name.length});
        named.write(name);
        assertEquals(8, answerToLateEnd(named.toByteArray(), new byte[]{1, (byte) 187}));
        // BIND, with its port late.
        assertEquals(7, answerToLateEnd(new byte[]{5, 2, 0, 1, 1, 2, 3, 4}, new byte[]{0, 80}));
    }

    /** An empty name is no site to tunnel to, and it's still a request that gets an answer. */
    @Test public void anEmptyNameIsTurnedDownWithAnAnswer() throws Exception {
        relay = ProxyRelay.start(NetworkProxy.parse("http", "proxy.example.com", "8080", USER, PASSWORD));
        assertEquals(8, answerToLateEnd(new byte[]{5, 1, 0, 3, 0}, new byte[]{1, (byte) 187}));
        // BIND by an empty name is still a command the relay doesn't carry.
        assertEquals(7, answerToLateEnd(new byte[]{5, 2, 0, 3, 0}, new byte[]{0, 80}));
    }

    /** Sends a request without its last bytes, waits, sends those, and reads the relay's answer. */
    private int answerToLateEnd(byte[] start, byte[] end) throws Exception {
        Socket client = open();
        OutputStream out = client.getOutputStream();
        out.write(new byte[]{5, 1, 0});
        out.flush();
        assertArrayEquals(new byte[]{5, 0}, readExactly(client.getInputStream(), 2));
        out.write(start);
        out.flush();
        // Long enough for a relay that answers early to have hung up already.
        Thread.sleep(300);
        out.write(end);
        out.flush();
        // And for the reset a closed socket sends back to arrive.
        Thread.sleep(200);
        int answer = answerTo(client);
        assertEquals("then it hangs up", -1, client.getInputStream().read());
        return answer;
    }

    @Test public void aRefusedSignInIsAFailedTunnelAndNeverAHang() throws Exception {
        ServerSocket proxy = proxy(socket -> {
            readHead(socket.getInputStream());
            socket.getOutputStream().write(
                    "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
        });
        relay = ProxyRelay.start(config("http", proxy, USER, "wrong"));

        Socket client = askForSite("api16.example.test", 443);
        assertNotEquals(0, answerTo(client));
        assertEquals("then it hangs up", -1, client.getInputStream().read());
    }

    @Test public void aSocksProxysOwnRefusalReachesTikTokAsSent() throws Exception {
        ServerSocket proxy = proxy(socket -> {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            readExactly(in, 4);
            out.write(new byte[]{5, 0});
            readExactly(in, 4);
            readExactly(in, in.read());
            readExactly(in, 2);
            out.write(new byte[]{5, 4, 0, 1, 0, 0, 0, 0, 0, 0});
        });
        relay = ProxyRelay.start(config("socks5", proxy, USER, PASSWORD));

        assertEquals("host unreachable, from the proxy", 4, answerTo(askForSite("gone.example.test", 443)));
    }

    @Test public void aProxyThatIsDownIsAFailedTunnelAndNeverAHang() throws Exception {
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = closed.getLocalPort();
        }
        relay = ProxyRelay.start(NetworkProxy.parse("http", "127.0.0.1", String.valueOf(port), USER, PASSWORD));

        assertEquals(1, answerTo(askForSite("api16.example.test", 443)));
    }

    /** A web page can point at a loopback port. It gets nowhere, and the proxy never hears of it. */
    @Test public void somethingThatDoesntSpeakSocksIsHungUpOn() throws Exception {
        boolean[] reached = {false};
        ServerSocket proxy = proxy(socket -> reached[0] = true);
        relay = ProxyRelay.start(config("http", proxy, USER, PASSWORD));

        Socket client = open();
        client.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
        int read;
        try {
            read = client.getInputStream().read();
        } catch (IOException reset) {
            read = -1;
        }
        assertEquals(-1, read);
        assertFalse(reached[0]);
    }

    @Test public void aRequestForAnythingButATunnelIsTurnedDown() throws Exception {
        relay = ProxyRelay.start(NetworkProxy.parse("http", "proxy.example.com", "8080", USER, PASSWORD));
        Socket client = open();
        OutputStream out = client.getOutputStream();
        out.write(new byte[]{5, 1, 0});
        assertArrayEquals(new byte[]{5, 0}, readExactly(client.getInputStream(), 2));
        // BIND, which needs a listening socket at the proxy and which nothing here uses.
        out.write(new byte[]{5, 2, 0, 1, 1, 2, 3, 4, 0, 80});
        out.flush();
        assertEquals(7, readExactly(client.getInputStream(), 10)[1]);
    }

    /** At Android's thread limit Thread.start throws OutOfMemoryError, and the relay has to outlive it. */
    @Test public void aConnectionThePhoneHasNoThreadForIsClosedAndTheRelayKeepsAccepting() throws Exception {
        ServerSocket proxy = echoingHttpProxy(new CountDownLatch(1));
        // Start 1 is the accept loop, start 2 the first connection.
        relay = ProxyRelay.start(config("http", proxy, USER, PASSWORD), failingThreadStart(2));

        Socket refused = open();
        assertEquals("the refused client is hung up on", -1, readOrEnd(refused));
        awaitOpenConnections(0);

        Socket client = askForSite("api16.example.test", 443);
        assertEquals("the next connection still gets its tunnel", 0, answerTo(client));
        assertEchoes(client);
        assertNothingEscaped();
    }

    @Test public void aTunnelWithNoThreadForTheWayBackClosesAndGivesItsSlotBack() throws Exception {
        CountDownLatch firstTunnelEnded = new CountDownLatch(1);
        ServerSocket proxy = echoingHttpProxy(firstTunnelEnded);
        // Start 3 is the first connection's pump from the proxy back to TikTok.
        relay = ProxyRelay.start(config("http", proxy, USER, PASSWORD), failingThreadStart(3));

        Socket first = askForSite("api16.example.test", 443);
        assertEquals(0, answerTo(first));
        assertEquals("a tunnel that can't carry bytes is closed", -1, readOrEnd(first));
        // The proxy's side ends cleanly, well before its own read would time out.
        assertTrue("the relay left the proxy's side of the tunnel open",
                firstTunnelEnded.await(TIMEOUT_MS / 3, TimeUnit.MILLISECONDS));
        awaitOpenConnections(0);

        Socket client = askForSite("api16.example.test", 443);
        assertEquals(0, answerTo(client));
        assertEchoes(client);
        // Before, the error went on out of the first connection's thread, which on a phone ends
        // TikTok. Read last, so that thread has long finished.
        assertNothingEscaped();
    }

    @Test public void aRelayWithNoThreadToAcceptOnFailsToStartWithAnIoError() throws Exception {
        try {
            relay = ProxyRelay.start(NetworkProxy.parse("http", "proxy.example.com", "8080", USER, PASSWORD),
                    failingThreadStart(1));
        } catch (IOException expected) {
            // NetworkProxy.route sends TikTok to the proxy itself on an IOException; an Error got past it.
            return;
        }
        throw new AssertionError("start() returned a relay nothing accepts on");
    }

    // -- Helpers --------------------------------------------------------------------------------

    /** Answers every CONNECT and echoes the tunnel. {@code ended} counts down when one ends in a clean close. */
    private ServerSocket echoingHttpProxy(CountDownLatch ended) throws IOException {
        return proxy(socket -> {
            readHead(socket.getInputStream());
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            echo(socket);
            ended.countDown();
        });
    }

    /** Errors that got out of a relay thread, which Android's default handler would end TikTok with. */
    private final List<Throwable> escaped = Collections.synchronizedList(new ArrayList<>());

    /**
     * Thread start number {@code failing} of the one relay it's given to throws the way Android's
     * does at its limit. Every thread it does start reports an error that gets out of it.
     */
    private ProxyRelay.ThreadStarter failingThreadStart(int failing) {
        AtomicInteger starts = new AtomicInteger();
        return thread -> {
            if (starts.incrementAndGet() == failing) {
                throw new OutOfMemoryError("pthread_create (1040KB stack) failed: Try again");
            }
            thread.setUncaughtExceptionHandler((dead, error) -> escaped.add(error));
            thread.start();
        };
    }

    private void assertNothingEscaped() {
        assertTrue("an error got out of a relay thread: " + escaped, escaped.isEmpty());
    }

    /** -1 when the relay closed the connection. A read that times out means it was left open. */
    private static int readOrEnd(Socket client) throws IOException {
        try {
            return client.getInputStream().read();
        } catch (SocketTimeoutException leftOpen) {
            throw leftOpen;
        } catch (IOException reset) {
            return -1;
        }
    }

    private void awaitOpenConnections(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (relay.openConnections() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals("connections being served", expected, relay.openConnections());
    }

    private interface Handler {
        void handle(Socket socket) throws IOException;
    }

    /** A proxy that serves every connection it gets with the handler, on its own thread. */
    private ServerSocket proxy(Handler handler) throws IOException {
        ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        servers.add(server);
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(TIMEOUT_MS);
                    handler.handle(socket);
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    // The test reads what the relay made of it.
                }
            }
        }, "proxy-relay-test-proxy");
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    private static NetworkProxy.Config config(String type, ServerSocket proxy, String user, String password) {
        return NetworkProxy.parse(type, "127.0.0.1", String.valueOf(proxy.getLocalPort()), user, password);
    }

    private Socket open() throws IOException {
        Socket client = new Socket(java.net.Proxy.NO_PROXY);
        sockets.add(client);
        client.setSoTimeout(TIMEOUT_MS);
        client.connect(new InetSocketAddress("127.0.0.1", relay.route().port), TIMEOUT_MS);
        return client;
    }

    /** What Chromium sends for a SOCKS5 rule: one method offered, and the site by name. */
    private Socket askForSite(String name, int port) throws IOException {
        Socket client = open();
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream address = new ByteArrayOutputStream();
        address.write(bytes.length);
        address.write(bytes);
        request(client, 3, address.toByteArray(), port);
        return client;
    }

    private static void request(Socket client, int type, byte[] address, int port) throws IOException {
        OutputStream out = client.getOutputStream();
        out.write(new byte[]{5, 1, 0});
        out.flush();
        assertArrayEquals(new byte[]{5, 0}, readExactly(client.getInputStream(), 2));
        out.write(new byte[]{5, 1, 0, (byte) type});
        out.write(address);
        out.write(new byte[]{(byte) (port >> 8), (byte) port});
        out.flush();
    }

    private static int answerTo(Socket client) throws IOException {
        byte[] reply = readExactly(client.getInputStream(), 10);
        assertEquals(5, reply[0]);
        return reply[1];
    }

    private static void assertEchoes(Socket client) throws IOException {
        byte[] sent = "client hello".getBytes(StandardCharsets.US_ASCII);
        client.getOutputStream().write(sent);
        client.getOutputStream().flush();
        assertArrayEquals(sent, readExactly(client.getInputStream(), sent.length));
    }

    private static void echo(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        byte[] buffer = new byte[256];
        int count;
        while ((count = in.read(buffer)) >= 0) {
            out.write(buffer, 0, count);
            out.flush();
        }
    }

    private static byte[] readExactly(InputStream in, int count) throws IOException {
        byte[] bytes = new byte[count];
        int read = 0;
        while (read < count) {
            int got = in.read(bytes, read, count - read);
            if (got < 0) throw new EOFException();
            read += got;
        }
        return bytes;
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (head.indexOf("\r\n\r\n") < 0) {
            int next = in.read();
            if (next < 0) break;
            head.append((char) next);
        }
        return head.toString();
    }
}

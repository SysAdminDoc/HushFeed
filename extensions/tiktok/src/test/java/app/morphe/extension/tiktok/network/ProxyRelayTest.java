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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        List<String> targets = new ArrayList<>();
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

    // -- Helpers --------------------------------------------------------------------------------

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

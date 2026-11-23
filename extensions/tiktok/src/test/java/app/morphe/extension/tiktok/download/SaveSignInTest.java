/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * A save to TikTok's own API domains carries TikTok's sign-in, as TikTok's own image loads do.
 * Friends-only story photos on image-sg.tiktokv.com answered every save with 403 while TikTok
 * showed them (#464): a save connects to a checked IP literal, so the platform's own cookie
 * lookup saw that literal and sent nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SaveSignInTest {
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=");

    private CookieHandler before;
    private final List<URI> asked = new ArrayList<>();
    private final List<HttpURLConnection> opened = new ArrayList<>();

    /** Answers every address, the way a cookie store that also holds browser cookies could. */
    private final class AnyHostCookies extends CookieHandler {
        @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
            asked.add(uri);
            return Map.of("Cookie", List.of("sessionid=abc", "tt_csrf_token=xyz"));
        }

        @Override public void put(URI uri, Map<String, List<String>> headers) { }
    }

    @Before public void remember() {
        before = CookieHandler.getDefault();
    }

    @After public void restore() {
        CookieHandler.setDefault(before);
    }

    private MediaTransport.Client client(String redirectTo) {
        return new MediaTransport.Client(host -> new InetAddress[]{
                InetAddress.getByAddress(host, new byte[]{8, 8, 8, 8})
        }, (url, address) -> {
            boolean redirect = redirectTo != null && opened.isEmpty();
            HttpURLConnection connection = MediaTransportFixtures.response(url,
                    redirect ? HTTP_MOVED_TEMP : HTTP_OK, PNG, "image/png",
                    redirect ? Map.of("Location", redirectTo) : Map.of());
            opened.add(connection);
            return connection;
        });
    }

    private int save(MediaTransport.Client client, String address) throws IOException {
        try (MediaTransport.Response response = client.open(address,
                MediaBudget.deadline(), 1000, 1000, null, false)) {
            return response.statusCode;
        }
    }

    @Test public void aPhotoOnTikToksPrivateImageHostCarriesTikToksSignIn() throws Exception {
        CookieHandler.setDefault(new AnyHostCookies());
        assertEquals(HTTP_OK, save(client(null),
                "https://image-sg.tiktokv.com/obj/tos-alisg-i-0000/photo~tplv-photomode-image.jpeg"));
        assertEquals("sessionid=abc; tt_csrf_token=xyz", opened.get(0).getRequestProperty("Cookie"));
        // Asked about the photo's own address, never the IP literal the connection dials.
        assertEquals(1, asked.size());
        assertEquals("image-sg.tiktokv.com", asked.get(0).getHost());
    }

    @Test public void tiktoksOtherApiDomainsCarryItToo() throws Exception {
        CookieHandler.setDefault(new AnyHostCookies());
        MediaTransport.Client client = client(null);
        save(client, "https://image-va.tiktokv.us/obj/a.jpeg");
        save(client, "https://p0-tiktok-dm-private.tiktokv.eu/obj/b.jpeg");
        assertEquals("sessionid=abc; tt_csrf_token=xyz", opened.get(0).getRequestProperty("Cookie"));
        assertEquals("sessionid=abc; tt_csrf_token=xyz", opened.get(1).getRequestProperty("Cookie"));
    }

    /**
     * A followed private account's photos on TikTok's US host answered every save with 403
     * (#504), through the fetch a video of the photos makes for each photo.
     */
    @Test public void aPrivateAccountsPhotoOnTheUsHostCarriesItThroughThePhotoVideoFetch() throws Exception {
        CookieHandler.setDefault(new AnyHostCookies());
        File photo = File.createTempFile("signin-504", ".tmp");
        try {
            assertEquals("png", RemoteMedia.fetch(List.of(
                    "https://p0-pu-private-useast8.tiktokv.us/tos-useast8-i-photomode-tx2/photo~tplv-photomode-image.jpeg"),
                    photo, RemoteMedia.Kind.IMAGE, client(null)));
            assertEquals("sessionid=abc; tt_csrf_token=xyz", opened.get(0).getRequestProperty("Cookie"));
            assertEquals("p0-pu-private-useast8.tiktokv.us", asked.get(0).getHost());
        } finally {
            assertTrue(photo.delete());
        }
    }

    @Test public void aCdnOrAnotherSiteGetsNoCookieAndIsNeverAskedAbout() throws Exception {
        CookieHandler.setDefault(new AnyHostCookies());
        MediaTransport.Client client = client(null);
        save(client, "https://p16-sign-sg.tiktokcdn.com/obj/a.jpeg?x-expires=1");
        save(client, "https://media.tenor.com/a.gif");
        save(client, "https://image-sg.tiktokv.com.cdn.example/a.jpeg");
        save(client, "https://eviltiktokv.com/a.jpeg");
        for (HttpURLConnection connection : opened) assertNull(connection.getRequestProperty("Cookie"));
        assertTrue(asked.isEmpty());
    }

    @Test public void aRedirectOffTikToksDomainDropsTheSignIn() throws Exception {
        CookieHandler.setDefault(new AnyHostCookies());
        assertEquals(HTTP_OK, save(client("https://p16-sg.tiktokcdn.com/obj/a.jpeg"),
                "https://image-sg.tiktokv.com/obj/a.jpeg"));
        assertEquals(2, opened.size());
        assertEquals("sessionid=abc; tt_csrf_token=xyz", opened.get(0).getRequestProperty("Cookie"));
        assertNull(opened.get(1).getRequestProperty("Cookie"));
    }

    @Test public void withoutTikToksCookieStoreTheSaveStillGoes() throws Exception {
        CookieHandler.setDefault(null);
        assertEquals(HTTP_OK, save(client(null), "https://image-sg.tiktokv.com/obj/a.jpeg"));
        assertNull(opened.get(0).getRequestProperty("Cookie"));
    }

    @Test public void aCookieStoreThatFailsOrHasLineBreaksSendsNothingBroken() throws Exception {
        CookieHandler.setDefault(new CookieHandler() {
            @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers)
                    throws IOException {
                throw new IOException("store closed");
            }

            @Override public void put(URI uri, Map<String, List<String>> headers) { }
        });
        assertEquals(HTTP_OK, save(client(null), "https://image-sg.tiktokv.com/obj/a.jpeg"));
        assertNull(opened.get(0).getRequestProperty("Cookie"));

        CookieHandler.setDefault(new CookieHandler() {
            @Override public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
                return Map.of("Cookie", List.of("a=1", "b=2" + (char) 13 + (char) 10 + "X-Evil: 1", ""));
            }

            @Override public void put(URI uri, Map<String, List<String>> headers) { }
        });
        save(client(null), "https://image-sg.tiktokv.com/obj/b.jpeg");
        assertEquals("a=1", opened.get(1).getRequestProperty("Cookie"));
    }
}

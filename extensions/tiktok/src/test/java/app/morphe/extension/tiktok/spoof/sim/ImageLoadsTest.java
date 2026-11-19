package app.morphe.extension.tiktok.spoof.sim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The export says which image hosts failed and how. #415's reporter saw every cover and avatar
 * stay grey with SIM spoof on, and a report couldn't tell a CDN refusal from a lookup that never
 * resolved or a URL turned down on the phone as expired.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ImageLoadsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** Stands in for Fresco's FetchState: the Uri comes from its one no-argument Uri getter. */
    public static class FetchState {
        private final Uri uri;

        FetchState(String url) {
            uri = url == null ? null : Uri.parse(url);
        }

        public Uri LIZJ() {
            return uri;
        }

        public String LIZ() {
            return "request-id";
        }

        public static Uri ignoredStatic() {
            return Uri.parse("https://static.example/");
        }
    }

    /** A subclass a fetcher might hand over, which inherits the getter. */
    public static class TtnetFetchState extends FetchState {
        TtnetFetchState(String url) {
            super(url);
        }
    }

    public static class NoUri {
    }

    public static class TwoUris {
        public Uri first() {
            return Uri.parse("https://one.example/");
        }

        public Uri second() {
            return Uri.parse("https://two.example/");
        }
    }

    private static final String SG = "https://p16-sign-sg.tiktokcdn.com/tos-alisg-p-0037/abc~tplv-photomode.jpeg"
        + "?x-expires=1763337600&x-signature=SECRETSIG%3D";

    @Before public void setUp() {
        ImageLoads.resetForTests();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
    }

    @After public void tearDown() {
        ImageLoads.resetForTests();
    }

    @Test public void eachHostCountsItsFetchesAndHowItsFailuresWentWithoutThePath() {
        for (int i = 0; i < 3; i++) ImageLoads.requested(new FetchState(SG));
        ImageLoads.requested(new TtnetFetchState("https://P16-Sign-VA.tiktokcdn.com/obj/x.webp?x-signature=VA"));
        ImageLoads.failed(new FetchState(SG), new IOException(
            "Unexpected HTTP code Response{protocol=h2, code=403, message=, url=" + SG + "}"));
        ImageLoads.failed(new FetchState(SG), new IOException("unexpected end of stream",
            new UnknownHostException("Unable to resolve host \"p16-sign-sg.tiktokcdn.com\": No address")));
        ImageLoads.failed(new FetchState(SG), new IOException("unexpected end of stream",
            new UnknownHostException("Unable to resolve host \"p16-sign-sg.tiktokcdn.com\": No address")));
        ImageLoads.failed(new FetchState(SG), new Throwable("The expired url does not initiate the request"));

        List<String> lines = ImageLoads.sectionForTests().lines();
        assertEquals(List.of(
            "Since the app started: 4 image fetches, 4 failed",
            "p16-sign-sg.tiktokcdn.com: 3 fetched, 4 failed",
            "p16-sign-sg.tiktokcdn.com, IOException: Unexpected HTTP code Response{protocol=h2, code=403, message=,"
                + " url=p16-sign-sg.tiktokcdn.com}: 1 time",
            "p16-sign-sg.tiktokcdn.com, IOException: unexpected end of stream < UnknownHostException: Unable to "
                + "resolve host \"p16-sign-sg.tiktokcdn.com\": No address: 2 times",
            "p16-sign-sg.tiktokcdn.com, Throwable: The expired url does not initiate the request: 1 time",
            "p16-sign-va.tiktokcdn.com: 1 fetched, 0 failed"), lines);
        for (String line : lines) {
            assertFalse(line, line.contains("x-signature") || line.contains("SECRETSIG") || line.contains("tos-alisg"));
        }

        LogBufferManager.appendEvent(DiagnosticCategory.FEED_AND_NAVIGATION, "Test", "INFO", "something worth reporting");
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("[" + ImageLoads.TITLE + "]\n"
            + "Since the app started: 4 image fetches, 4 failed\n"
            + "p16-sign-sg.tiktokcdn.com: 3 fetched, 4 failed\n"));
        assertTrue(report, report.contains("\np16-sign-va.tiktokcdn.com: 1 fetched, 0 failed\n"));
        assertTrue(report, report.contains("\np16-sign-sg.tiktokcdn.com, IOException: unexpected end of stream"
            + " < UnknownHostException: Unable to resolve host \"[host omitted]\": No address: 2 times\n"));
        assertFalse(report, report.contains("SECRETSIG"));
    }

    @Test public void aUrlInAFailureIsCutToItsHost() {
        assertEquals("IOException: 403 from p16-sign-sg.tiktokcdn.com for avatar",
            ImageLoads.kind(new IOException("403 from " + SG + " for avatar")));
        assertEquals("IllegalStateException", ImageLoads.kind(new IllegalStateException()));
        assertEquals("no reason given", ImageLoads.kind(null));
    }

    /** Stands in for 47.9.1's network failure, an obfuscated exception with a status line. */
    static class HttpFailure extends Exception {
        HttpFailure(String message) {
            super(message);
        }
    }

    private static HttpFailure status(String path, String url) {
        return new HttpFailure("statusCode: 403 " + path + ", traceCode: , url: " + url
            + ", requestHeaders: {\"oec-cs-sdk-version\":\"v10.03.03-ov-android\",\"x-tt-token\":\"TOKENVALUE\"}");
    }

    /**
     * #415's report: every one of 317 failures was a 403, but each cover's own path made its own
     * kind, four of them filled the host, and 191 more read "other failures".
     */
    @Test public void oneStatusFromOneHostIsOneKindWhateverTheImage() {
        String host = "https://p16-common-sign.tiktokcdn.com";
        ImageLoads.failed(new FetchState(SG), status(
            "/tos-alisg-i-vo4twfpge7-sg/bfbb88bd2ccd49cfafb1b9746fc05111~tplv-vo4twfpge7-blurred-cover:200:400:1:q100.webp",
            host + "/tos-alisg-i-vo4twfpge7-sg/bfbb.webp?x-expires=1763337600&x-signature=SECRETSIG"));
        ImageLoads.failed(new FetchState(SG), status(
            "/tos-maliva-i-photomode-us/a7c277947ff94be493350d374bd997bd~tplv-photomode-sr-vqe4:1440:0:1080:0:q80.heic",
            host + "/tos-maliva-i-photomode-us/a7c2.heic?x-signature=SECRETSIG"));
        ImageLoads.failed(new FetchState(SG), status("/aweme/200x200/tos-alisg-v-2774/d1880cc7bdb646f690e05b42e872d62f.jpeg",
            host + "/aweme/200x200/d188.jpeg"));
        ImageLoads.failed(new FetchState(SG), status("/tiktok-obj/share_platform_imo_circle.png~tplv-tiktokx-origin.image",
            host + "/tiktok-obj/share_platform_imo_circle.png"));
        ImageLoads.failed(new FetchState(SG), status("/tos-alisg-avt-0068/79ea917e7342b656762033c0af7b51c8~tplv-tiktokx-cropcenter-q:100:100:q70.heic",
            host + "/tos-alisg-avt-0068/79ea.heic"));
        ImageLoads.failed(new FetchState(SG), status("/image/refresh/", "https://api19-va.tiktokv.com/image/refresh/?aid=1233"));

        List<String> lines = ImageLoads.sectionForTests().lines();
        assertEquals(List.of(
            "Since the app started: 0 image fetches, 6 failed",
            "p16-sign-sg.tiktokcdn.com: 0 fetched, 6 failed",
            "p16-sign-sg.tiktokcdn.com, HttpFailure: statusCode: 403 (path), traceCode: , url: p16-common-sign.tiktokcdn.com,"
                + " requestHeaders: [oec-cs-sdk-version x-tt-token]: 5 times",
            "p16-sign-sg.tiktokcdn.com, HttpFailure: statusCode: 403 /image/refresh/, traceCode: , url: api19-va.tiktokv.com,"
                + " requestHeaders: [oec-cs-sdk-version x-tt-token]: 1 time"), lines);
        for (String line : lines) {
            assertFalse(line, line.contains("TOKENVALUE") || line.contains("SECRETSIG") || line.contains("v10.03"));
        }
    }

    @Test public void aLongFailureIsCutShort() {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 400; i++) message.append('x');
        String kind = ImageLoads.kind(new IOException(message.toString()));
        assertEquals(ImageLoads.MESSAGE_LIMIT, kind.length());
        assertTrue(kind, kind.endsWith("..."));
    }

    @Test public void aFetchStateWithoutOneUriGetterCountsAsNoHost() {
        assertEquals(ImageLoads.NO_HOST, ImageLoads.host(null));
        assertEquals(ImageLoads.NO_HOST, ImageLoads.host(new NoUri()));
        assertEquals(ImageLoads.NO_HOST, ImageLoads.host(new TwoUris()));
        assertEquals(ImageLoads.NO_HOST, ImageLoads.host(new FetchState(null)));
        assertEquals(ImageLoads.NO_HOST, ImageLoads.host(new FetchState("file:///sdcard/a.jpg")));
        assertEquals("p16-sign-sg.tiktokcdn.com", ImageLoads.host(new FetchState(SG)));
    }

    @Test public void theHostsAndFailureKindsStayBounded() {
        for (int i = 0; i < ImageLoads.HOST_LIMIT + 6; i++) {
            ImageLoads.requested(new FetchState("https://h" + i + ".example/a.jpg"));
        }
        for (int i = 0; i < ImageLoads.KIND_LIMIT + 3; i++) {
            ImageLoads.failed(new FetchState("https://h0.example/a.jpg"), new IOException("code " + i));
        }

        List<String> lines = ImageLoads.sectionForTests().lines();
        assertEquals("Since the app started: 30 image fetches, 7 failed", lines.get(0));
        assertEquals("h0.example: 1 fetched, 7 failed", lines.get(1));
        assertTrue(lines.toString(), lines.contains("h0.example, other failures: 3 times"));
        assertTrue(lines.toString(), lines.contains(ImageLoads.OTHER_HOSTS + ": 6 fetched, 0 failed"));
        assertFalse(lines.toString(), lines.toString().contains("h" + ImageLoads.HOST_LIMIT + ".example"));
    }

    @Test public void nothingFetchedSaysNothing() {
        assertTrue(ImageLoads.sectionForTests().lines().isEmpty());
    }
}

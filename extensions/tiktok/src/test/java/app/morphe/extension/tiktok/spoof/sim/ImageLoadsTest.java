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
            + "Since the app started: 4 image fetches, 4 failed"));
        assertFalse(report, report.contains("SECRETSIG"));
    }

    @Test public void aUrlInAFailureIsCutToItsHost() {
        assertEquals("IOException: 403 from p16-sign-sg.tiktokcdn.com for avatar",
            ImageLoads.kind(new IOException("403 from " + SG + " for avatar")));
        assertEquals("IllegalStateException", ImageLoads.kind(new IllegalStateException()));
        assertEquals("no reason given", ImageLoads.kind(null));
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

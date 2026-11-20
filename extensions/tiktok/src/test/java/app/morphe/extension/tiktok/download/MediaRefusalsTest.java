/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #464: a story's original photos all came back 403 and the report could only say "[url omitted]"
 * for each one. The report now names the servers that refused a save, how, and whether each link
 * had already expired, without the signed path or query.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MediaRefusalsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private static final long NOW = 1_763_500_000L;
    private static final String EXPIRED =
            "https://p16-sign-sg.tiktokcdn.com/tos-alisg-i-0068/abc~tplv-photomode-image.webp?x-expires="
                    + (NOW - 600) + "&x-signature=SECRETSIG";
    private static final String IN_DATE =
            "https://P16-Sign-SG.tiktokcdn.com/tos-alisg-i-0068/def~tplv-photomode-image.heic?x-expires="
                    + (NOW + 3600) + "&x-signature=SECRETSIG";
    private static final String UNREACHABLE = "https://p19-common-sign.tiktokcdn.com/obj/ghi.jpeg";

    @Before public void setUp() {
        MediaRefusals.resetForTests();
        MediaRefusals.clockForTests = () -> NOW;
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
    }

    @After public void tearDown() {
        MediaRefusals.resetForTests();
    }

    @Test public void aRefusedSaveNamesItsServerHowAndWhetherTheLinkHadExpired() throws Exception {
        MediaTransport.Client network = MediaTransportFixtures.publicClient(url -> {
            if (url.getHost().startsWith("p19-")) throw new UnknownHostException("Unable to resolve host");
            return MediaTransportFixtures.response(url, 403, new byte[0]);
        });
        File target = temporary.newFile();
        try {
            RemoteMedia.fetch(List.of(EXPIRED, IN_DATE, UNREACHABLE), target, RemoteMedia.Kind.IMAGE, network);
            fail("every mirror refused, nothing should land");
        } catch (IOException expected) {
            assertEquals("No media URL succeeded", expected.getMessage());
        }

        List<String> lines = MediaRefusals.sectionForTests().lines();
        assertEquals(List.of(
                "Since the app started: 3 save addresses refused for good",
                "p16-sign-sg.tiktokcdn.com: 2 refused; link already expired 1, still in date 1, no expiry 0",
                "p16-sign-sg.tiktokcdn.com, HTTP 403: 2 times",
                "p19-common-sign.tiktokcdn.com: 1 refused; link already expired 0, still in date 0, no expiry 1",
                "p19-common-sign.tiktokcdn.com, UnknownHostException: 1 time"), lines);
        for (String line : lines) {
            assertFalse(line, line.contains("SECRETSIG") || line.contains("tos-alisg") || line.contains("x-expires"));
        }

        LogBufferManager.appendEvent(DiagnosticCategory.FEED_AND_NAVIGATION, "Test", "INFO", "something worth reporting");
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("[" + MediaRefusals.TITLE + "]\n"
                + "Since the app started: 3 save addresses refused for good\n"
                + "p16-sign-sg.tiktokcdn.com: 2 refused; link already expired 1, still in date 1, no expiry 0\n"
                + "p16-sign-sg.tiktokcdn.com, HTTP 403: 2 times\n"));
        assertFalse(report, report.contains("SECRETSIG"));
    }

    @Test public void aSaveThatLandsLeavesTheSectionOutOfTheReport() throws Exception {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1};
        MediaTransport.Client network = MediaTransportFixtures.publicClient(
                url -> MediaTransportFixtures.response(url, 200, jpeg));
        assertEquals("jpg", RemoteMedia.fetch(List.of(IN_DATE), temporary.newFile(), RemoteMedia.Kind.IMAGE, network));
        assertTrue(MediaRefusals.sectionForTests().lines().isEmpty());
        LogBufferManager.appendEvent(DiagnosticCategory.FEED_AND_NAVIGATION, "Test", "INFO", "something worth reporting");
        assertFalse(LogBufferManager.buildExportText().contains("[" + MediaRefusals.TITLE + "]"));
    }

    @Test public void onlyAPlainNumberAfterXExpiresCountsAsTheLinksExpiry() {
        assertEquals(Long.valueOf(NOW), MediaRefusals.expiry("https://a.tiktokcdn.com/x.webp?a=1&X-Expires=" + NOW + "#f"));
        assertNull(MediaRefusals.expiry("https://a.tiktokcdn.com/x.webp?x-expires=soon"));
        assertNull(MediaRefusals.expiry("https://a.tiktokcdn.com/x-expires=1/x.webp"));
        assertNull(MediaRefusals.expiry(null));
        assertEquals(MediaRefusals.NO_HOST, MediaRefusals.host("not a link"));
        assertEquals("HTTP 404", MediaRefusals.reason(new IOException("Media server returned 404")));
        assertEquals("IOException", MediaRefusals.reason(new IOException("Media download is incomplete")));
    }
}

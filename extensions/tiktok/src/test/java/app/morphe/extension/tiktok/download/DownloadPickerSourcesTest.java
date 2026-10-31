/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.download.AdvancedDownloadsTest.Address;
import app.morphe.extension.tiktok.download.AdvancedDownloadsTest.Gear;
import app.morphe.extension.tiktok.settings.Settings;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * #240: when every gear at 720p or taller is ByteVC2, the Download choice stopped at 540p while
 * another site served a better file. The Video model also carries its own H.264 and HEVC play
 * addresses, which are offered when the gears top out low, and the report says what was left out.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class DownloadPickerSourcesTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;
    private boolean previousMuted;

    /** 47.5.3's Video, reduced to what the choice reads: raw gears plus the video's own play addresses. */
    public static final class SourcedVideo {
        public final List<Gear> bitRateList;
        public Address downloadNoWatermarkAddr = new Address("https://example.com/clean", 5000).frame(576, 1024);
        public Address playAddrValue, h264PlayAddrValue, playAddrBytevc1Value;
        public Address newDownloadAddr;
        public String miscDownloadAddrs;
        public boolean dash;
        SourcedVideo(Gear... gears) { bitRateList = List.of(gears); }
        public List<Gear> getRawBitRate() { return bitRateList; }
        public boolean hasDashBitrate() { return dash; }
    }

    private static final Gear G540 = new Gear("normal_540_0", 200, "https://example.com/540");
    private static final Gear G720_BVC2 = new Gear("adapt_lower_720_2", 300, "https://example.com/720-bvc2").codec(2);
    private static final Gear G1080_BVC2 = new Gear("adapt_1080_2", 400, "https://example.com/1080-bvc2").codec(2);

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        previousMuted = Settings.DOWNLOAD_WITHOUT_SOUND.get();
        DownloadPickerReport.resetForTests();
        Settings.DOWNLOAD_WITHOUT_SOUND.save(false);
    }

    @After public void tearDown() {
        DownloadPickerReport.resetForTests();
        Settings.DOWNLOAD_WITHOUT_SOUND.save(previousMuted);
    }

    private static Address frame(String url, int width, int height) {
        return new Address(url, 7000).frame(width, height);
    }

    private static List<String> labels(List<QualityChoice.Option> options) {
        List<String> labels = new ArrayList<>();
        for (QualityChoice.Option option : options) labels.add(option.label);
        return labels;
    }

    @Test public void aTallerH264SourceIsOfferedWhenTheGearsTopOutBelow720p() {
        SourcedVideo video = new SourcedVideo(G540, G720_BVC2, G1080_BVC2);
        video.playAddrValue = frame("https://example.com/play-720", 720, 1280);
        List<QualityChoice.Option> options = QualityChoice.options(video, context);
        assertEquals(3, options.size());
        assertTrue(options.get(1).label, options.get(1).label.startsWith("720p H.264"));
        assertTrue(options.get(2).label, options.get(2).label.startsWith("540p H.264"));
        QualityChoice.Option extra = options.get(1);
        assertTrue(extra.pick instanceof QualitySelector.SourceGear);
        assertEquals("the pick carries the address the save fetches", "https://example.com/play-720",
                ((com.ss.android.ugc.aweme.base.model.UrlModel) ((QualitySelector.SourceGear) extra.pick).getPlayAddr())
                        .getUrlList().get(0));
        for (QualityChoice.Option option : options) {
            assertFalse("ByteVC2 stays out: " + option.label, option.label.contains("ByteVC2"));
        }
    }

    @Test public void anHevcSourceIsOfferedToo() {
        SourcedVideo video = new SourcedVideo(G540);
        video.playAddrBytevc1Value = frame("https://example.com/play-hevc", 1080, 1920);
        List<String> labels = labels(QualityChoice.options(video, context));
        assertEquals(3, labels.size());
        assertTrue(labels.get(1), labels.get(1).startsWith("1080p HEVC"));
    }

    @Test public void aSourceIsNotOfferedWhenAGearAlreadyReaches720p() {
        SourcedVideo video = new SourcedVideo(G540, new Gear("normal_720_0", 250, "https://example.com/720"));
        video.playAddrValue = frame("https://example.com/play-1080", 1080, 1920);
        assertEquals(3, QualityChoice.options(video, context).size());
        assertTrue(DownloadPickerReport.sectionForTests().lines().toString(),
                DownloadPickerReport.sectionForTests().lines().contains(
                        "source play_addr_h264 1080p H.264: not needed, a gear at 720p is offered"));
    }

    @Test public void aSourceNoTallerThanTheGearsIsLeftOut() {
        SourcedVideo video = new SourcedVideo(G540);
        video.playAddrValue = frame("https://example.com/play-540", 540, 960);
        assertEquals(2, QualityChoice.options(video, context).size());
        assertTrue(DownloadPickerReport.sectionForTests().lines().contains(
                "source play_addr_h264 540p H.264: left out, no taller than the gears (540p)"));
    }

    @Test public void twoSourcesThatReadTheSameAreOfferedOnce() {
        SourcedVideo video = new SourcedVideo(G540);
        Address same = frame("https://example.com/play-720", 720, 1280);
        video.playAddrValue = same;
        video.h264PlayAddrValue = same;
        assertEquals(3, QualityChoice.options(video, context).size());
    }

    @Test public void aVideoWithSeparateTracksTakesNoSource() {
        SourcedVideo video = new SourcedVideo(G540);
        video.dash = true;
        video.playAddrValue = frame("https://example.com/play-720", 720, 1280);
        Settings.DOWNLOAD_WITHOUT_SOUND.save(true);
        assertEquals("Automatic and the one gear", 2, QualityChoice.options(video, context).size());
        assertTrue(DownloadPickerReport.sectionForTests().lines().contains(
                "source play_addr_h264 720p H.264: left out, the video has a separate sound track"));
    }

    @Test public void anAddressWithoutAUsableUrlIsNotOffered() {
        SourcedVideo video = new SourcedVideo(G540);
        video.playAddrValue = new Address(null, 0).frame(720, 1280);
        assertEquals(2, QualityChoice.options(video, context).size());
        assertTrue(DownloadPickerReport.sectionForTests().lines().contains(
                "source play_addr_h264 720p H.264: left out, no usable address"));
    }

    @Test public void theReportNamesEveryGearAndWhyItWasLeftOut() {
        SourcedVideo video = new SourcedVideo(G540, G720_BVC2, G1080_BVC2,
                new Gear("normal_360_0", 100, null));
        QualityChoice.options(video, context);
        List<String> lines = DownloadPickerReport.sectionForTests().lines();
        assertEquals("TikTok's own file: 576p, gears listed: 4", lines.get(0));
        assertTrue(lines.toString(), lines.contains("gear adapt_lower_720_2 720p ByteVC2: left out, no other player opens ByteVC2"));
        assertTrue(lines.toString(), lines.contains("gear adapt_1080_2 1080p ByteVC2: left out, no other player opens ByteVC2"));
        assertTrue(lines.toString(), lines.contains("gear normal_360_0 360p H.264: left out, no usable address"));
        assertTrue(lines.toString(), lines.contains("gear normal_540_0 540p H.264: offered"));
        assertTrue(lines.toString(), lines.contains("source play_addr_h264: not on this video"));
        assertTrue(lines.toString(), lines.contains("source play_addr_bytevc1: not on this video"));
        for (String line : lines) assertFalse("no addresses in the report: " + line, line.contains("example.com"));
    }

    @Test public void theReportListsTikToksOtherAddressesWithoutTheirUrls() {
        SourcedVideo video = new SourcedVideo(G540);
        video.newDownloadAddr = new Address("https://example.com/new", 7000).frame(1080, 1920);
        video.miscDownloadAddrs = "{\"suffix_scene\":{\"url_list\":[\"https://example.com/misc\"]}}";
        QualityChoice.options(video, context);
        List<String> lines = DownloadPickerReport.sectionForTests().lines();
        assertEquals("TikTok's own file: 576p, gears listed: 1", lines.get(0));
        assertTrue(lines.toString(), lines.contains("address downloadNoWatermarkAddr: 576p, 5000 bytes"));
        assertTrue(lines.toString(), lines.contains("address newDownloadAddr: 1080p, 7000 bytes"));
        assertTrue(lines.toString(), lines.contains("address miscDownloadAddrs: [\"suffix_scene\"]"));
        for (String line : lines) assertFalse("no addresses in the report: " + line, line.contains("example.com"));

        video.miscDownloadAddrs = "not json";
        QualityChoice.options(video, context);
        lines = DownloadPickerReport.sectionForTests().lines();
        assertTrue(lines.toString(), lines.contains("address miscDownloadAddrs: not JSON, 8 characters"));
    }

    @Test public void theReportKeepsOnlyTheLastVideoAndIsBounded() {
        List<Gear> many = new ArrayList<>();
        for (int i = 0; i < 80; i++) many.add(new Gear("normal_540_0", 100 + i, "https://example.com/g" + i));
        QualityChoice.options(new SourcedVideo(many.toArray(new Gear[0])), context);
        List<String> lines = DownloadPickerReport.sectionForTests().lines();
        assertEquals(DownloadPickerReport.MAX_LINES + 1, lines.size());
        assertTrue(lines.get(lines.size() - 1), lines.get(lines.size() - 1).endsWith("more lines left out"));

        QualityChoice.options(new SourcedVideo(G540), context);
        lines = DownloadPickerReport.sectionForTests().lines();
        assertTrue("the first video is gone: " + lines, lines.size() < 10);
        assertTrue(lines.contains("gear normal_540_0 540p H.264: offered"));
    }

    @Test public void theExportedReportCarriesTheSection() {
        QualityChoice.options(new SourcedVideo(G540), context);
        LogBufferManager.appendEvent(DiagnosticCategory.FEED_AND_NAVIGATION, "Test", "INFO", "something worth reporting");
        String report = LogBufferManager.buildExportText();
        assertNotNull(report);
        assertTrue(report, report.contains("[" + DownloadPickerReport.TITLE + "]\nTikTok's own file: 576p"));
    }
}

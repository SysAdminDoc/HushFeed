package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class OtherBundlesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private static final String[] TIKTOK = {
            "LX/0abc;", "Lcom/ss/android/ugc/aweme/main/MainActivity;", "Ljava/lang/String;", "run",
    };
    private static final String[] HUSHFEED = {
            "Lapp/morphe/extension/R;", "Lapp/morphe/extension/shared/Utils;",
            "Lapp/morphe/extension/tiktok/feedfilter/FeedFilter;",
    };

    @After
    public void forgetTheScan() {
        OtherBundles.useApksForTests(null);
    }

    @Test
    public void aHushfeedOnlyApkSaysNone() throws Exception {
        File apk = apk(dex(concat(TIKTOK, HUSHFEED)));
        assertEquals(Collections.emptyMap(), OtherBundles.scan(Collections.singletonList(apk)));
    }

    @Test
    public void anotherBundlesExtensionIsNamedOnceAcrossDexFiles() throws Exception {
        // The bundle's classes sit in one dex file and the hooks that call them in another, so
        // the same name turns up twice and must be counted once.
        File apk = apk(
                dex(concat(TIKTOK, HUSHFEED, new String[]{
                        "Lapp/ftl/extension/dpi/DensityPatch$1;", "Lapp/ftl/extension/dpi/DensityPatch;",
                        "Lapp/ftl/extension/addsave/SaveDataPatch;",
                })),
                dex(concat(TIKTOK, new String[]{"Lapp/ftl/extension/dpi/DensityPatch;"})));
        Map<String, Integer> found = OtherBundles.scan(Collections.singletonList(apk));
        assertEquals("[app.ftl.extension.addsave, app.ftl.extension.dpi]", found.keySet().toString());
        assertEquals(2, (int) found.get("app.ftl.extension.dpi"));
        assertEquals("app.ftl.extension.addsave (1 class), app.ftl.extension.dpi (2 classes)",
                OtherBundles.describe(new OtherBundles.Scan(found, null)));
    }

    @Test
    public void theReadStopsOncePastTheAppNames() throws Exception {
        // The last id points past the end of the file. A read that went on past "Lapp/" would
        // run off the end and fail, so finding the bundle at all proves it stopped in time.
        byte[] cut = dex(concat(TIKTOK, new String[]{"Lapp/morphe/extension/youtube/Shorts;"}));
        byte[] broken = withLastStringOffset(cut, cut.length + 100);
        Set<String> classes = new HashSet<>();
        OtherBundles.scanDex(new ByteArrayInputStream(broken), classes);
        assertEquals(Collections.singleton("Lapp/morphe/extension/youtube/Shorts;"), classes);
    }

    @Test
    public void aStringStartingWithNulDoesNotEndTheReadEarly() throws Exception {
        // Modified UTF-8 writes U+0000 as two high bytes, yet a dex sorts it first. Read as bytes
        // it looks like it comes after "Lapp/", and stopping there would miss every bundle.
        List<byte[]> strings = new ArrayList<>();
        strings.add(new byte[]{(byte) 0xC0, (byte) 0x80, 'x'});
        for (String name : new String[]{"LX/0abc;", "Lapp/rvx/extension/Patch;", "Lcom/ss/A;"}) {
            strings.add(name.getBytes("ISO-8859-1"));
        }
        Set<String> classes = new HashSet<>();
        OtherBundles.scanDex(new ByteArrayInputStream(dexOf(strings)), classes);
        assertEquals(Collections.singleton("Lapp/rvx/extension/Patch;"), classes);
    }

    @Test
    public void onlyNumberedClassesDexEntriesAreRead() {
        assertTrue(OtherBundles.isDexEntry("classes.dex"));
        assertTrue(OtherBundles.isDexEntry("classes61.dex"));
        assertFalse(OtherBundles.isDexEntry("assets/classes.dex.bak"));
        assertFalse(OtherBundles.isDexEntry("classesX.dex"));
        assertFalse(OtherBundles.isDexEntry("lib/classes2.dex"));
    }

    @Test
    public void aCrashsTopAppFrameNamesWhoseCodeItIs() {
        String crash = "schema: 1\nexception: java.lang.IllegalStateException\n\n[STACK TRACE]\n"
                + "java.lang.IllegalStateException: boom\n"
                + "\tat java.util.Objects.requireNonNull(Objects.java:220)\n"
                + "\tat app.ftl.extension.dpi.DensityPatch.apply(DensityPatch.kt:20)\n"
                + "\tat X.0abc.run(Unknown Source:4)\n"
                + "Caused by: java.lang.NullPointerException\n"
                + "\tat app.ftl.extension.dpi.DensityPatch.apply(DensityPatch.kt:20)\n"
                + "\tat app.ftl.extension.addsave.SaveDataPatch.save(SaveDataPatch.kt:9)\n"
                + "[RECENT MORPHE EVENTS]\n"
                + "\tat app.ftl.extension.other.NotInTheTrace.run(Other.kt:1)\n";
        assertEquals(Arrays.asList(
                "latest_crash_top_app_frame: at app.ftl.extension.dpi.DensityPatch.apply(DensityPatch.kt:20)",
                "latest_crash_top_app_frame_from: another patch bundle (app.ftl.extension.dpi)",
                "latest_crash_frames_from_other_bundles: 2"),
                OtherBundles.crashLines(crash));

        String ours = "[STACK TRACE]\njava.lang.RuntimeException\n"
                + "\tat android.os.Handler.dispatchMessage(Handler.java:106)\n"
                + "\tat app.morphe.extension.tiktok.feedfilter.FeedFilter.filter(FeedFilter.java:9)\n";
        assertEquals("latest_crash_top_app_frame_from: Hushfeed", OtherBundles.crashLines(ours).get(1));

        String tiktok = "[STACK TRACE]\njava.lang.RuntimeException\n"
                + "\tat com.android.billingclient.api.BillingClientImpl.a(Unknown Source:2)\n"
                + "\tat com.ss.android.ugc.aweme.live.LivePlayActivity.onCreate(Unknown Source:8)\n";
        List<String> lines = OtherBundles.crashLines(tiktok);
        assertEquals("latest_crash_top_app_frame_from: TikTok's own classes, which a bundle may have rewritten",
                lines.get(1));
        assertEquals("latest_crash_frames_from_other_bundles: 0", lines.get(2));
        assertEquals(Collections.emptyList(), OtherBundles.crashLines(""));
    }

    @Test
    public void aLineStartingWithABracketInsideTheTraceDoesNotEndIt() {
        // The redactor keeps a message's own line breaks and puts its markers at the start of a
        // line, so "[url omitted]" or "[circular throwable omitted]" can open one.
        String crash = "[STACK TRACE]\njava.io.IOException: load failed\n[url omitted]\n"
                + "[circular throwable omitted]\n"
                + "\tat app.ftl.extension.dpi.DensityPatch.load(DensityPatch.kt:31)\n"
                + "[RECENT MORPHE EVENTS]\n";
        assertEquals(Arrays.asList(
                "latest_crash_top_app_frame: at app.ftl.extension.dpi.DensityPatch.load(DensityPatch.kt:31)",
                "latest_crash_top_app_frame_from: another patch bundle (app.ftl.extension.dpi)",
                "latest_crash_frames_from_other_bundles: 1"),
                OtherBundles.crashLines(crash));
    }

    @Test
    public void hushfeedsOwnMp3EncoderCountsAsHushfeed() {
        String crash = "[STACK TRACE]\njava.lang.ArrayIndexOutOfBoundsException\n"
                + "\tat de.sciss.jump3r.mp3.Lame.lame_encode_buffer(Lame.java:120)\n"
                + "\tat app.morphe.extension.tiktok.download.Mp3Transcoder.encode(Mp3Transcoder.java:80)\n";
        assertEquals("latest_crash_top_app_frame_from: Hushfeed", OtherBundles.crashLines(crash).get(1));
    }

    @Test
    public void aWrapperOverACauseInAnotherBundleNamesBoth() {
        String crash = "[STACK TRACE]\nio.reactivex.exceptions.OnErrorNotImplementedException: boom\n"
                + "\tat X.0rx.accept(Unknown Source:6)\n"
                + "Caused by: java.lang.RuntimeException: wrapped\n"
                + "\tat X.0rx.onError(Unknown Source:2)\n"
                + "Caused by: java.lang.IllegalStateException: boom\n"
                + "\tat java.util.Objects.requireNonNull(Objects.java:220)\n"
                + "\tat app.ftl.extension.dpi.DensityPatch.apply(DensityPatch.kt:20)\n";
        assertEquals(Arrays.asList(
                "latest_crash_top_app_frame: at X.0rx.accept(Unknown Source:6)",
                "latest_crash_top_app_frame_from: TikTok's own classes, which a bundle may have rewritten",
                "latest_crash_cause_app_frame: at app.ftl.extension.dpi.DensityPatch.apply(DensityPatch.kt:20)",
                "latest_crash_cause_app_frame_from: another patch bundle (app.ftl.extension.dpi)",
                "latest_crash_frames_from_other_bundles: 1"),
                OtherBundles.crashLines(crash));
    }

    @Test
    public void aSuppressedExceptionsCauseIsNotTakenForTheChain() {
        // Breadth first, a suppressed exception's own cause can come after the crash's real one.
        String crash = "[STACK TRACE]\njava.lang.RuntimeException: outer\n"
                + "\tat X.0abc.run(Unknown Source:4)\n"
                + "Caused by: java.lang.IllegalStateException: inner\n"
                + "\tat app.ftl.extension.dpi.DensityPatch.apply(DensityPatch.kt:20)\n"
                + "Suppressed: java.io.IOException: close\n"
                + "\tat X.0def.close(Unknown Source:1)\n"
                + "Caused by: java.io.IOException: disk\n"
                + "\tat app.rvx.extension.cache.Cache.close(Cache.java:3)\n";
        List<String> lines = OtherBundles.crashLines(crash);
        assertEquals("latest_crash_cause_app_frame: at app.ftl.extension.dpi.DensityPatch.apply(DensityPatch.kt:20)",
                lines.get(2));
        assertEquals("latest_crash_frames_from_other_bundles: 2", lines.get(4));
    }

    @Test
    public void aScanThatFailedForWantOfAnApkIsTriedAgainNextTime() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        HookStatus.missingViewId("comments", "jlk");
        // A null file fails before any APK is opened, as a missing context does on a real phone.
        OtherBundles.useApksForTests(Collections.singletonList((File) null));
        OtherBundles.scanForTests();
        String report = LogBufferManager.buildExportText();
        Utils.awaitBackgroundTasksForTests();
        assertTrue(report, report.contains("extension_packages: still being read"));
    }

    @Test
    public void theReportNamesTheBundleAndTheFrameItDiedIn() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        OtherBundles.useApksForTests(Collections.singletonList(apk(dex(concat(TIKTOK, HUSHFEED,
                new String[]{"Lapp/ftl/extension/dpi/DensityPatch;"})))));
        LogBufferManager.persistCrashReport(context, "schema: 1\ncomplete: true\n\n[STACK TRACE]\n"
                + "java.lang.IllegalStateException: boom\n"
                + "\tat app.ftl.extension.dpi.DensityPatch.apply(DensityPatch.kt:20)\n");

        // A quick copy is made on the main thread, which never waits for the scan.
        String early = LogBufferManager.buildExportText();
        assertTrue(early, early.contains("[OTHER PATCH BUNDLES]\nextension_packages: still being read"));
        assertTrue("the crash frame waited on the scan: " + early,
                early.contains("latest_crash_top_app_frame_from: another patch bundle (app.ftl.extension.dpi)"));

        Utils.awaitBackgroundTasksForTests();
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("[OTHER PATCH BUNDLES]\n"
                + "extension_packages: app.ftl.extension.dpi (1 class)\n"
                + "latest_crash_top_app_frame: at app.ftl.extension.dpi.DensityPatch.apply(DensityPatch.kt:20)\n"));
    }

    @Test
    public void aHushfeedOnlyReportSaysNoneAndAnUnreadableOneSaysWhy() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        HookStatus.missingViewId("comments", "jlk");

        OtherBundles.useApksForTests(Collections.singletonList(apk(dex(concat(TIKTOK, HUSHFEED)))));
        OtherBundles.scanForTests();
        assertTrue(LogBufferManager.buildExportText(),
                LogBufferManager.buildExportText().contains("[OTHER PATCH BUNDLES]\nextension_packages: none\n"));

        OtherBundles.useApksForTests(Collections.singletonList(new File(temporary.getRoot(), "gone.apk")));
        OtherBundles.scanForTests();
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("extension_packages: could not be read ("));

        // Left out with the hook table when the reader chose other categories.
        OtherBundles.useApksForTests(Collections.singletonList(apk(dex(concat(TIKTOK, HUSHFEED)))));
        OtherBundles.scanForTests();
        BaseSettings.DEBUG_LOG_FILTERS.save(DiagnosticCategory.FEED_AND_NAVIGATION.value);
        LogBufferManager.appendEvent(DiagnosticCategory.FEED_AND_NAVIGATION, "Test", "INFO", "feed moved");
        String other = LogBufferManager.buildExportText();
        assertFalse(other, other.contains("[OTHER PATCH BUNDLES]"));
    }

    private static String[] concat(String[]... parts) {
        List<String> all = new ArrayList<>();
        for (String[] part : parts) all.addAll(Arrays.asList(part));
        return all.toArray(new String[0]);
    }

    /** A dex file holding only a sorted string table, the one part the scan reads. */
    private static byte[] dex(String[] names) throws IOException {
        List<String> sorted = new ArrayList<>(new HashSet<>(Arrays.asList(names)));
        Collections.sort(sorted);
        List<byte[]> strings = new ArrayList<>();
        for (String name : sorted) strings.add(name.getBytes("ISO-8859-1"));
        return dexOf(strings);
    }

    private static byte[] dexOf(List<byte[]> strings) {
        int count = strings.size();
        int dataStart = 0x70 + count * 4;
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = dataStart + data.size();
            byte[] bytes = strings.get(i);
            data.write(bytes.length); // every test string is under 128 units
            data.write(bytes, 0, bytes.length);
            data.write(0);
        }
        byte[] file = new byte[dataStart + data.size()];
        System.arraycopy("dex\n035\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0, file, 0, 8);
        putInt(file, 0x20, file.length);
        putInt(file, 0x38, count);
        putInt(file, 0x3C, 0x70);
        putInt(file, 0x6C, dataStart);
        for (int i = 0; i < count; i++) putInt(file, 0x70 + i * 4, offsets[i]);
        byte[] body = data.toByteArray();
        System.arraycopy(body, 0, file, dataStart, body.length);
        return file;
    }

    private static byte[] withLastStringOffset(byte[] dex, int offset) {
        byte[] copy = dex.clone();
        int count = (copy[0x38] & 0xFF) | (copy[0x39] & 0xFF) << 8;
        putInt(copy, 0x70 + (count - 1) * 4, offset);
        return copy;
    }

    private static void putInt(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >> 8);
        bytes[at + 2] = (byte) (value >> 16);
        bytes[at + 3] = (byte) (value >> 24);
    }

    private File apk(byte[]... dexFiles) throws IOException {
        File apk = temporary.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) {
            zip.putNextEntry(new ZipEntry("AndroidManifest.xml"));
            zip.write(new byte[]{3, 0, 8, 0});
            zip.closeEntry();
            for (int i = 0; i < dexFiles.length; i++) {
                zip.putNextEntry(new ZipEntry(i == 0 ? "classes.dex" : "classes" + (i + 1) + ".dex"));
                zip.write(dexFiles[i]);
                zip.closeEntry();
            }
        }
        return apk;
    }
}

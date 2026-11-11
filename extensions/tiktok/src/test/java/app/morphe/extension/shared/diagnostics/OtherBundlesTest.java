package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.ExportDiagnosticReportPreference;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
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
        OtherBundles.useOwnClassesForTests(null);
    }

    @Test
    public void aHushfeedOnlyApkSaysNone() throws Exception {
        File apk = apk(dex(concat(TIKTOK, HUSHFEED)));
        assertEquals(Collections.emptyMap(), OtherBundles.scan(Collections.singletonList(apk)));
    }

    @Test
    public void aClassUnderHushfeedsPackagesThatIsNotOnItsListIsAnotherBundles() throws Exception {
        // Another TikTok bundle built from the same template keeps Hushfeed's package names.
        File apk = apk(dex(concat(TIKTOK, HUSHFEED, new String[]{
                "Lapp/morphe/extension/shared/Extra;", "Lapp/morphe/extension/tiktok/other/Foreign;",
        })));
        String crash = "[STACK TRACE]\njava.lang.IllegalStateException\n"
                + "\tat app.morphe.extension.tiktok.other.Foreign.run(Foreign.java:3)\n"
                + "Caused by: java.lang.NullPointerException\n"
                + "\tat app.morphe.extension.tiktok.feedfilter.FeedFilter.filter(FeedFilter.java:9)\n";

        OtherBundles.useOwnClassesForTests(new HashSet<>(Arrays.asList(HUSHFEED)));
        assertEquals("{app.morphe.extension.shared=1, app.morphe.extension.tiktok=1}",
                OtherBundles.scan(Collections.singletonList(apk)).toString());
        assertEquals(Arrays.asList(
                "latest_crash_top_app_frame: at app.morphe.extension.tiktok.other.Foreign.run(Foreign.java:3)",
                "latest_crash_top_app_frame_from: another patch bundle (app.morphe.extension.tiktok)",
                "latest_crash_cause_app_frame: at app.morphe.extension.tiktok.feedfilter.FeedFilter.filter(FeedFilter.java:9)",
                "latest_crash_cause_app_frame_from: Hushfeed",
                "latest_crash_frames_from_other_bundles: 1"),
                OtherBundles.crashLines(crash, null));

        // An APK patched before the list existed goes by package name and can't tell them apart.
        OtherBundles.useOwnClassesForTests(Collections.emptySet());
        assertEquals(Collections.emptyMap(), OtherBundles.scan(Collections.singletonList(apk)));
        assertEquals("latest_crash_top_app_frame_from: Hushfeed", OtherBundles.crashLines(crash, null).get(1));
    }

    @Test
    public void aSavedCrashInAClassAnEarlierHushfeedHadIsStillHushfeeds() throws Exception {
        // Saved before an update that renamed the class, so the new list doesn't hold it.
        String crash = "[STACK TRACE]\njava.lang.IllegalStateException\n"
                + "\tat app.morphe.extension.tiktok.renamed.Gone.run(Gone.java:3)\n";
        OtherBundles.useOwnClassesForTests(new HashSet<>(Arrays.asList(HUSHFEED)));
        File alone = apk(dex(concat(TIKTOK, HUSHFEED)));
        List<String> lines = OtherBundles.crashLines(crash,
                new OtherBundles.Scan(OtherBundles.scan(Collections.singletonList(alone)), null));
        assertEquals("latest_crash_top_app_frame_from: Hushfeed (an earlier build)", lines.get(1));
        assertEquals("latest_crash_frames_from_other_bundles: 0", lines.get(2));

        // Another bundle under the same names in this APK could own it, so it isn't told apart.
        File shared = apk(dex(concat(TIKTOK, HUSHFEED, new String[]{"Lapp/morphe/extension/tiktok/other/Foreign;"})));
        lines = OtherBundles.crashLines(crash,
                new OtherBundles.Scan(OtherBundles.scan(Collections.singletonList(shared)), null));
        assertEquals("latest_crash_top_app_frame_from: another patch bundle (app.morphe.extension.tiktok)", lines.get(1));
        // An APK that couldn't be read says nothing either way.
        lines = OtherBundles.crashLines(crash, new OtherBundles.Scan(Collections.emptyMap(), "IOException"));
        assertEquals("latest_crash_top_app_frame_from: another patch bundle (app.morphe.extension.tiktok)", lines.get(1));
    }

    @Test
    public void theOwnClassListReadsOneDescriptorALine() throws Exception {
        byte[] list = "Lapp/morphe/extension/R;\nLapp/morphe/extension/tiktok/A$1;\n\nnot a class\nLX/0abc;\n"
                .getBytes("ISO-8859-1");
        assertEquals(new HashSet<>(Arrays.asList("Lapp/morphe/extension/R;", "Lapp/morphe/extension/tiktok/A$1;")),
                OtherBundles.readOwnClasses(new ByteArrayInputStream(list)));
        assertTrue(OtherBundles.readOwnClasses(new ByteArrayInputStream(new byte[0])).isEmpty());
        byte[] huge = new byte[(4 << 20) + 1];
        Arrays.fill(huge, (byte) 'L');
        assertTrue(OtherBundles.readOwnClasses(new ByteArrayInputStream(huge)).isEmpty());
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
        OtherBundles.scanDex(() -> new ByteArrayInputStream(broken), classes);
        assertEquals(Collections.singleton("Lapp/morphe/extension/youtube/Shorts;"), classes);
    }

    @Test
    public void aStringTablePointingIntoTheHeaderOrPast2GbIsRefused() throws Exception {
        byte[] good = dex(concat(TIKTOK, new String[]{"Lapp/morphe/extension/youtube/Shorts;"}));
        for (int offset : new int[]{0x10, -8}) {
            // Out of id order, so the read sorts the ids and the bad one comes first.
            byte[] broken = withLastStringOffset(good, offset);
            try {
                OtherBundles.scanDex(() -> new ByteArrayInputStream(broken), new HashSet<>());
                throw new AssertionError("read a string at " + offset);
            } catch (IOException expected) {
                assertEquals("bad string table", expected.getMessage());
            }
        }
    }

    @Test
    public void stringDataBeforeTheIdsTableIsReadAgainFromTheStart() throws Exception {
        List<byte[]> strings = new ArrayList<>();
        for (String name : new String[]{"LX/0abc;", "Lapp/rvx/extension/Patch;", "Lcom/ss/A;"}) {
            strings.add(name.getBytes("ISO-8859-1"));
        }
        byte[] dataFirst = dexWithDataFirst(strings);
        int[] opens = {0};
        Set<String> classes = new HashSet<>();
        OtherBundles.scanDex(() -> {
            opens[0]++;
            return new ByteArrayInputStream(dataFirst);
        }, classes);
        assertEquals(Collections.singleton("Lapp/rvx/extension/Patch;"), classes);
        assertEquals(2, opens[0]);

        // The usual layout is still read in one pass.
        byte[] usual = dexOf(strings);
        opens[0] = 0;
        classes.clear();
        OtherBundles.scanDex(() -> {
            opens[0]++;
            return new ByteArrayInputStream(usual);
        }, classes);
        assertEquals(Collections.singleton("Lapp/rvx/extension/Patch;"), classes);
        assertEquals(1, opens[0]);

        // And through the APK, which opens the entry twice.
        assertEquals("{app.rvx.extension=1}",
                OtherBundles.scan(Collections.singletonList(apk(dataFirst))).toString());
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
        byte[] dex = dexOf(strings);
        OtherBundles.scanDex(() -> new ByteArrayInputStream(dex), classes);
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
                OtherBundles.crashLines(crash, null));

        String ours = "[STACK TRACE]\njava.lang.RuntimeException\n"
                + "\tat android.os.Handler.dispatchMessage(Handler.java:106)\n"
                + "\tat app.morphe.extension.tiktok.feedfilter.FeedFilter.filter(FeedFilter.java:9)\n";
        assertEquals("latest_crash_top_app_frame_from: Hushfeed", OtherBundles.crashLines(ours, null).get(1));

        String tiktok = "[STACK TRACE]\njava.lang.RuntimeException\n"
                + "\tat com.android.billingclient.api.BillingClientImpl.a(Unknown Source:2)\n"
                + "\tat com.ss.android.ugc.aweme.live.LivePlayActivity.onCreate(Unknown Source:8)\n";
        List<String> lines = OtherBundles.crashLines(tiktok, null);
        assertEquals("latest_crash_top_app_frame_from: TikTok's own classes, which a bundle may have rewritten",
                lines.get(1));
        assertEquals("latest_crash_frames_from_other_bundles: 0", lines.get(2));
        assertEquals(Collections.emptyList(), OtherBundles.crashLines("", null));
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
                OtherBundles.crashLines(crash, null));
    }

    @Test
    public void hushfeedsOwnMp3EncoderCountsAsHushfeed() {
        String crash = "[STACK TRACE]\njava.lang.ArrayIndexOutOfBoundsException\n"
                + "\tat de.sciss.jump3r.mp3.Lame.lame_encode_buffer(Lame.java:120)\n"
                + "\tat app.morphe.extension.tiktok.download.Mp3Transcoder.encode(Mp3Transcoder.java:80)\n";
        assertEquals("latest_crash_top_app_frame_from: Hushfeed", OtherBundles.crashLines(crash, null).get(1));
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
                OtherBundles.crashLines(crash, null));
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
        List<String> lines = OtherBundles.crashLines(crash, null);
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

    @Test
    public void theExportChoiceReadsTheApkOnlyForAReportThatShowsTheSection() throws Exception {
        File apk = apk(dex(concat(TIKTOK, HUSHFEED)));
        OtherBundles.useApksForTests(Collections.singletonList(apk));
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            activity.setTheme(android.R.style.Theme_Material_Light);
            Utils.setContext(activity);
            HookStatus.clear();
            LogBufferManager.clearLogBuffer();
            BaseSettings.DEBUG_LOG_FILTERS.save(DiagnosticCategory.FEED_AND_NAVIGATION.value);
            ExportDiagnosticReportPreference row = new ExportDiagnosticReportPreference(activity);
            assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
            Utils.awaitBackgroundTasksForTests();
            assertFalse("a report without the section started a scan", OtherBundles.startedForTests());

            // Turned back on, the section's first read starts it.
            BaseSettings.DEBUG_LOG_FILTERS.save("all");
            HookStatus.missingViewId("comments", "jlk");
            String early = LogBufferManager.buildExportText();
            assertTrue(early, early.contains("[OTHER PATCH BUNDLES]\nextension_packages: still being read"));
            Utils.awaitBackgroundTasksForTests();
            String report = LogBufferManager.buildExportText();
            assertTrue(report, report.contains("[OTHER PATCH BUNDLES]\nextension_packages: none\n"));

            // And so does opening the choice, once the section is in the report.
            OtherBundles.useApksForTests(Collections.singletonList(apk));
            assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
            assertTrue(OtherBundles.startedForTests());
            Utils.awaitBackgroundTasksForTests();
        } finally {
            BaseSettings.DEBUG_LOG_FILTERS.save("all");
        }
    }

    @Test
    public void aQuickCopyOfALongReportKeepsTheOtherBundles() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        OtherBundles.useApksForTests(Collections.singletonList(apk(dex(concat(TIKTOK, HUSHFEED,
                new String[]{"Lapp/ftl/extension/dpi/DensityPatch;"})))));
        OtherBundles.scanForTests();
        try {
            LogBufferManager.appendEvent(DiagnosticCategory.DOWNLOADS, "Fixture", "INFO", "phase=oldest");
            for (int index = 0; index < 180; index++) {
                LogBufferManager.appendEvent(DiagnosticCategory.DOWNLOADS, "Fixture", "INFO",
                        "phase=ready " + "x".repeat(500));
            }
            LogBufferManager.appendEvent(DiagnosticCategory.DOWNLOADS, "Fixture", "INFO", "phase=newest");
            assertTrue(LogBufferManager.buildExportText().length() > 60_000);
            LogBufferManager.exportToClipboard();
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            String copied = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
            assertTrue(copied, copied.contains("clipboard_note:"));
            assertTrue(copied, copied.contains("[OTHER PATCH BUNDLES]\n"
                    + "extension_packages: app.ftl.extension.dpi (1 class)\n"));
            // Only the oldest events go. The head of the report stays, and so does the newest event.
            assertTrue(copied.length() <= 60_000);
            assertTrue(copied, copied.startsWith("MORPHE DIAGNOSTIC REPORT\nschema: 1\n"));
            assertTrue(copied, copied.contains("[SELECTED EVENTS]\ncategory | timestamp | thread | source | level | message\n"
                    + "clipboard_note: older events omitted"));
            assertTrue(copied.contains("phase=newest"));
            assertFalse(copied.contains("phase=oldest"));
        } finally {
            LogBufferManager.clearLogBuffer();
        }
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

    /** The same strings with their data between the header and the ids table. */
    private static byte[] dexWithDataFirst(List<byte[]> strings) {
        int count = strings.size();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = 0x70 + data.size();
            byte[] bytes = strings.get(i);
            data.write(bytes.length);
            data.write(bytes, 0, bytes.length);
            data.write(0);
        }
        while (data.size() % 4 != 0) data.write(0);
        int idsOffset = 0x70 + data.size();
        byte[] file = new byte[idsOffset + count * 4];
        System.arraycopy("dex\n035\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 0, file, 0, 8);
        putInt(file, 0x20, file.length);
        putInt(file, 0x38, count);
        putInt(file, 0x3C, idsOffset);
        putInt(file, 0x6C, 0x70);
        byte[] body = data.toByteArray();
        System.arraycopy(body, 0, file, 0x70, body.length);
        for (int i = 0; i < count; i++) putInt(file, idsOffset + i * 4, offsets[i]);
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

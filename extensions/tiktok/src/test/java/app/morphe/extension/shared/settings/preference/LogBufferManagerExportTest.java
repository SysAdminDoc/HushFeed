package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.StallingMediaProvider;
import android.content.Context;
import android.os.Environment;
import android.os.Looper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class LogBufferManagerExportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void aTrimmedReportKeepsWholeEventsStackTracesIncluded() {
        // An exception event carries its stack trace on the lines below it. Wherever the limit
        // falls, the first event kept starts at its own first line, never at one of its frames.
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        try {
            for (int index = 0; index < 40; index++) {
                StringBuilder trace = new StringBuilder("phase=failed " + index);
                for (int frame = 0; frame < 12; frame++) {
                    trace.append("\n\tat app.Fixture.step").append(frame).append("(Fixture.java)");
                }
                LogBufferManager.appendEvent(app.morphe.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                        "Fixture", "ERROR", trace.toString());
            }
            String whole = LogBufferManager.buildExportText();
            String note = "clipboard_note: older events omitted; use Save full report for everything\n";
            for (int limit = whole.length() - 1; limit > whole.length() - 12_000; limit -= 37) {
                String trimmed = LogBufferManager.buildExportText(limit);
                assertTrue("over the limit of " + limit, trimmed.length() <= limit);
                int cut = trimmed.indexOf(note);
                assertTrue("no note at a limit of " + limit, cut >= 0);
                String after = trimmed.substring(cut + note.length());
                assertTrue("an event cut in half at a limit of " + limit + ": "
                                + after.substring(0, Math.min(80, after.length())),
                        after.startsWith("downloads | ") || after.startsWith("\n["));
            }
        } finally {
            LogBufferManager.clearLogBuffer();
        }
    }

    @Test public void sectionsThatFitWithoutTheEventsStayWholeWithinTheLimit() {
        // Between the length of the sections around the events and that plus the note, no event
        // fits. The report still has to fit, or the quick copy cuts off its first lines.
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        try {
            StringBuilder message = new StringBuilder("phase=failed");
            while (message.length() < 500) message.append(" x");
            LogBufferManager.appendEvent(app.morphe.extension.shared.diagnostics.DiagnosticCategory.DOWNLOADS,
                    "Fixture", "ERROR", message.toString());
            // Built on the main thread, a report starts the other-bundles scan and says it's
            // still being read, so a scan landing between two builds changed one of them.
            settleOtherBundles();
            String whole = LogBufferManager.buildExportText();
            int section = whole.indexOf("\n\n[SELECTED EVENTS]\n");
            assertTrue(section > 0);
            int next = whole.indexOf("\n[", section + 2);
            assertTrue(next > section);
            int around = whole.length() - (next - section);
            String gone = "\nclipboard_note: events omitted; use Save full report for everything\n";
            for (int limit = around; limit < whole.length(); limit++) {
                String trimmed = LogBufferManager.buildExportText(limit);
                assertTrue("over the limit of " + limit + ": " + trimmed.length(), trimmed.length() <= limit);
                // The note goes in from the first limit it fits under. The two builds can be a
                // millisecond apart, and the stamp is the same length either way.
                String kept = limit >= around + gone.length() ? gone : "";
                assertEquals("at a limit of " + limit,
                        unstamped(whole.substring(0, section) + kept + whole.substring(next)), unstamped(trimmed));
            }
        } finally {
            LogBufferManager.clearLogBuffer();
        }
    }

    @Test public void aQuickCopyOfACrashTooLongOnItsOwnStaysWithinTheLimit() throws Exception {
        // No events to drop, so the copy keeps the report's end behind a short head of its own,
        // and the head counts toward the limit.
        Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        try {
            StringBuilder crash = new StringBuilder("complete: true\njava.lang.IllegalStateException: fixture");
            for (int frame = 0; crash.length() < 70_000; frame++) {
                crash.append("\n\tat app.Fixture.step").append(frame).append("(Fixture.java)");
            }
            LogBufferManager.persistCrashReport(context, crash.toString());
            assertTrue(LogBufferManager.buildExportText().length() > 60_000);
            LogBufferManager.exportToClipboard();
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            String copied = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
            assertTrue("copied " + copied.length() + " characters", copied.length() <= 60_000);
            assertTrue(copied, copied.startsWith("MORPHE DIAGNOSTIC REPORT\n"
                    + "clipboard_note: older content omitted"));
            assertTrue(copied, copied.contains("[BUILD DETAILS]"));
        } finally {
            // Takes the saved crash with it.
            LogBufferManager.clearLogBuffer();
        }
    }

    @Test public void anOlderAndroidSaysNothingAboutTheLastExit() {
        // The history arrived in API 30 and this bundle runs from 23, so the section has to be
        // absent rather than empty or guessed at.
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        app.morphe.extension.shared.diagnostics.HookStatus.missingViewId("comments", "jlk");

        String report = LogBufferManager.buildExportText();
        assertNotEquals("nothing was reported at all", "", report);
        assertTrue("an API 28 build claimed to know why the process went away: " + report,
                report.indexOf("[LAST EXIT") < 0);
    }

    @Test @Config(sdk = 30)
    public void theReportListsTheLastFiveExitsNewestFirst() throws Exception {
        // One exit can't show a pattern, such as a memory kill every time a LIVE opens.
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        android.app.ActivityManager manager = (android.app.ActivityManager) RuntimeEnvironment
                .getApplication().getSystemService(Context.ACTIVITY_SERVICE);
        String main = RuntimeEnvironment.getApplication().getPackageName();
        // Added oldest first, with helpers between, so neither the order they were recorded in
        // nor a short list of sixteen can decide which five are shown.
        for (int i = 1; i <= 6; i++) {
            org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(
                    org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                            .setReason(13)
                            .setTimestamp(1757260800000L + i * 60_000L)
                            .setProcessName(main)
                            .setDescription("MemoryLimiter:AnonSwap kill " + i)
                            .build());
            for (int helper = 0; helper < 4; helper++) {
                org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(
                        org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                                .setReason(10)
                                .setTimestamp(1757260800000L + i * 60_000L + helper + 1)
                                .setProcessName(main + ":push")
                                .setDescription("helper")
                                .build());
            }
        }
        app.morphe.extension.shared.diagnostics.HookStatus.missingViewId("comments", "jlk");

        String report = LogBufferManager.buildExportText();
        int start = report.indexOf("[LAST EXITS]\n");
        assertTrue(report, start >= 0);
        String[] lines = report.substring(start + "[LAST EXITS]\n".length()).split("\n");
        for (int shown = 0; shown < 5; shown++) {
            assertTrue("exit " + shown + " is not the next newest: " + report,
                    lines[shown].startsWith("at: ")
                            && lines[shown].endsWith("description: MemoryLimiter:AnonSwap kill " + (6 - shown)));
        }
        assertEquals("a sixth exit was listed: " + report, "", lines[5]);
        assertTrue("the oldest exit was listed: " + report, report.indexOf("kill 1") < 0);
        assertTrue("a helper's exit was listed: " + report, report.indexOf("description: helper") < 0);
    }

    @Test @Config(sdk = 30)
    public void theReportSaysWhyTheProcessWentAwayLastTime() throws Exception {
        // A Java crash handler sees none of the ways the system ends an app. Android 17 kills one
        // that goes over a RAM-proportional limit and leaves only this behind.
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();

        android.app.ApplicationExitInfo exit =
                org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setReason(13)
                .setTimestamp(1757260800000L)
                .setProcessName(RuntimeEnvironment.getApplication().getPackageName())
                .setDescription("MemoryLimiter:AnonSwap")
                .build();

        // TikTok runs several processes, and the most recent record is routinely a background
        // helper the system reaped. Reporting that as why the app went away is worse than saying
        // nothing, so the newer of the two here must be passed over.
        android.app.ApplicationExitInfo helper = org.robolectric.shadows.ShadowActivityManager
                .ApplicationExitInfoBuilder.newBuilder()
                .setReason(10)
                .setTimestamp(1757260900000L)
                .setProcessName(RuntimeEnvironment.getApplication().getPackageName() + ":push")
                .setDescription("a background helper nobody asked about")
                .build();
        android.app.ActivityManager manager = (android.app.ActivityManager) RuntimeEnvironment
                .getApplication().getSystemService(Context.ACTIVITY_SERVICE);
        org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(exit);
        org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(helper);

        // The line rides along with a report that was already worth making. On its own it must
        // not make one, because every process has a last exit and most of them are ordinary.
        assertEquals("a last exit alone made a report", "", LogBufferManager.buildExportText());

        app.morphe.extension.shared.diagnostics.HookStatus.missingViewId("comments", "jlk");
        String report = LogBufferManager.buildExportText();
        assertTrue("the report does not say why the process went away: " + report,
                report.contains("[LAST EXITS]\n"));
        assertTrue("the reason was not named: " + report, report.contains("reason: OTHER"));
        assertTrue("the description the kill carried was dropped: " + report,
                report.contains("MemoryLimiter:AnonSwap"));
        assertTrue("a background helper's exit was reported as the app's: " + report,
                report.indexOf("a background helper nobody asked about") < 0);
        java.text.SimpleDateFormat utc =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String when = utc.format(new java.util.Date(1757260800000L));
        assertTrue("the time it happened was left out or in another format: " + report,
                report.contains(when));
    }

    @Test @Config(sdk = 30)
    public void anAnrShowsTheMainThreadOfArtsJavaDump() throws Exception {
        // A freeze leaves no exception behind. The dump Android took is the only stack there is,
        // and the main thread is the one that held the app.
        StringBuilder dump = new StringBuilder()
                .append("----- pid 12345 at 2026-11-19 10:20:30.123456789-0500 -----\n")
                .append("Cmd line: com.zhiliaoapp.musically\n")
                .append("ABI: 'arm64'\n")
                .append("DALVIK THREADS (3):\n")
                .append("\"Signal Catcher\" daemon prio=10 tid=7 Runnable\n")
                .append("  | group=\"system\" sCount=0 ucsCount=0 flags=0 obj=0x12c80000 self=0xb400007a1c2d3e40\n")
                .append("  at java.lang.Object.wait(Native method)\n")
                .append("\n")
                .append("\"main\" prio=5 tid=1 Blocked\n")
                .append("  | group=\"main\" sCount=1 ucsCount=0 flags=1 obj=0x72a1b2c8 self=0xb400007a1c2d3e40\n")
                .append("  | sysTid=12345 nice=-10 cgrp=top-app sched=0/0 handle=0x7b5c6d7e88\n")
                .append("  | held mutexes=\n")
                .append("  native: #00 pc 000000000009c5b8  /apex/com.android.runtime/lib64/bionic/libc.so (__epoll_pwait+8) (BuildId: 3e8a1b2c)\n")
                .append("  at android.os.MessageQueue.nativePollOnce(Native method)\n")
                .append("  at com.ss.android.ugc.aweme.feed.FeedHolder.bind(FeedHolder.java:100)\n")
                .append("  - waiting to lock <0x0a1b2c3d> (a java.lang.Object) held by thread 23\n");
        for (int frame = 2; frame < 60; frame++) {
            dump.append("  at com.ss.android.ugc.aweme.feed.Frame").append(frame)
                    .append(".run(Frame").append(frame).append(".java:").append(frame).append(")\n");
        }
        dump.append("\n\"RenderThread\" daemon prio=7 tid=12 Native\n")
                .append("  at com.ss.android.ugc.aweme.render.Other.loop(Other.java:9)\n")
                .append("----- end 12345 -----\n");
        addExit(6, 12345, dump.toString().getBytes(StandardCharsets.UTF_8));

        String section = lastExits(LogBufferManager.buildExportText());
        assertTrue(section, section.contains("\n  trace: main thread, java dump (40 of 61 frames)\n"));
        assertTrue("ART's lowercase native frame was redacted: " + section,
                section.contains("\n  at android.os.MessageQueue.nativePollOnce(Native method)\n"));
        assertTrue(section, section.contains("\n  \"main\" prio=5 tid=1 Blocked\n"));
        assertTrue("a native frame was not shortened to its library and symbol: " + section,
                section.contains("\n  native: #00 libc.so (__epoll_pwait+8)\n"));
        assertTrue(section, section.contains("\n  at com.ss.android.ugc.aweme.feed.FeedHolder.bind(FeedHolder.java:100)\n"));
        assertTrue("the lock the main thread waits on was dropped: " + section,
                section.contains("\n  - waiting to lock <0x0a1b2c3d> (a java.lang.Object) held by thread 23\n"));
        assertTrue("the 40th frame is missing: " + section, section.contains("Frame38.run(Frame38.java:38)"));
        assertFalse("a 41st frame was shown: " + section, section.contains("Frame39.run"));
        assertFalse("another thread's frame was shown: " + section, section.contains("Other.loop"));
        assertFalse(section, section.contains("Object.wait"));
    }

    @Test @Config(sdk = 30)
    public void anAnrShowsTheMainThreadOfDebuggerdsNativeBacktrace() throws Exception {
        // When ART can't dump a frozen process, Android keeps debuggerd's backtrace instead. Its
        // headers say sysTid= and never tid=, so the main thread is the one with the exit's pid.
        java.io.ByteArrayOutputStream dump = new java.io.ByteArrayOutputStream();
        writeAscii(dump, "----- pid 4321 at 2026-11-19 10:20:30 -----\nCmd line: com.zhiliaoapp.musically\n\n"
                + "\"Signal Catcher\" sysTid=4330\n"
                + "    #00 pc 00000000000a1b2c  /apex/com.android.runtime/lib64/bionic/libc.so (__rt_sigtimedwait+8) (BuildId: 0f1e)\n"
                + "    #01 pc 0000000000001234  /data/app/x/lib/arm64/libbroken.so (bad_");
        // A dump cut in the middle of a character still reads.
        dump.write(0xe2);
        dump.write(0x82);
        writeAscii(dump, ")\n\n\"zhiliaoapp.mus\" sysTid=4321\n"
                + "    #00 pc 000000000009c5b8  /apex/com.android.runtime/lib64/bionic/libc.so (__epoll_pwait+8) (BuildId: 3e8a)\n"
                + "    #01 pc 0000000000012345  /system/lib64/libutils.so (android::Looper::pollOnce(int, int*, int*, void**)+256) (BuildId: abcd)\n"
                + "    #02 pc 0000000000054321  /data/app/~~a1==/com.zhiliaoapp.musically-b2==/lib/arm64/libtiktok.so (BuildId: 77)\n"
                + "    #03 pc 00000000002a0000  /memfd:jit-cache (deleted) (offset 0x2000000) (art_jni_trampoline+112)\n");
        for (int frame = 4; frame < 45; frame++) {
            writeAscii(dump, "    #" + frame + " pc 00000000000000ff  /system/lib64/libart.so (art::Frame" + frame + "()+4)\n");
        }
        writeAscii(dump, "\n\"HeapTaskDaemon\" sysTid=4335\n    #00 pc 0000000000000010  /system/lib64/libart.so (art::gc::Other()+1)\n");
        addExit(6, 4321, dump.toByteArray());

        String section = lastExits(LogBufferManager.buildExportText());
        assertTrue(section, section.contains("\n  trace: main thread, native backtrace (40 of 45 frames)\n"));
        assertTrue(section, section.contains("\n  \"zhiliaoapp.mus\" sysTid=4321\n"));
        assertTrue(section, section.contains("\n  #00 libc.so (__epoll_pwait+8)\n"));
        assertTrue("a symbol with parentheses of its own was cut: " + section,
                section.contains("\n  #01 libutils.so (android::Looper::pollOnce(int, int*, int*, void**)+256)\n"));
        assertTrue("a frame with no symbol lost its pc: " + section, section.contains("\n  #02 libtiktok.so pc 54321\n"));
        assertTrue("a deleted mapping's offset and flag were taken for its symbol: " + section,
                section.contains("\n  #03 memfd:jit-cache (art_jni_trampoline+112)\n"));
        assertTrue(section, section.contains("art::Frame39()+4"));
        assertFalse("a 41st frame was shown: " + section, section.contains("art::Frame40()"));
        assertFalse("another thread's frame was shown: " + section, section.contains("__rt_sigtimedwait"));
        assertFalse(section, section.contains("art::gc::Other"));
    }

    @Test @Config(sdk = 33)
    public void aNativeCrashShowsItsSignalAndTheCrashingThread() throws Exception {
        // From Android 12 a native crash record carries its tombstone, as tombstone.proto.
        java.io.ByteArrayOutputStream crashed = new java.io.ByteArrayOutputStream();
        field(crashed, 1, 777);
        field(crashed, 2, "RenderThread");
        for (int frame = 0; frame < 45; frame++) {
            java.io.ByteArrayOutputStream backtraceFrame = new java.io.ByteArrayOutputStream();
            field(backtraceFrame, 1, 0x1000 + frame);
            field(backtraceFrame, 2, 0x7000001000L + frame);
            if (frame != 1) {
                field(backtraceFrame, 4, "android::uirenderer::Step" + frame + "()");
                field(backtraceFrame, 5, 40 + frame);
            }
            field(backtraceFrame, 6, "/system/lib64/libhwui.so");
            field(backtraceFrame, 8, "ab12");
            field(crashed, 4, backtraceFrame.toByteArray());
        }
        java.io.ByteArrayOutputStream other = new java.io.ByteArrayOutputStream();
        field(other, 1, 100);
        field(other, 2, "main");
        java.io.ByteArrayOutputStream otherFrame = new java.io.ByteArrayOutputStream();
        field(otherFrame, 4, "OnlyOnMain()");
        field(otherFrame, 6, "/system/lib64/libmain.so");
        field(other, 4, otherFrame.toByteArray());

        java.io.ByteArrayOutputStream signal = new java.io.ByteArrayOutputStream();
        field(signal, 1, 11);
        field(signal, 2, "SIGSEGV");
        field(signal, 3, 1);
        field(signal, 4, "SEGV_MAPERR");
        field(signal, 8, 1);
        field(signal, 9, 0x10);
        java.io.ByteArrayOutputStream cause = new java.io.ByteArrayOutputStream();
        field(cause, 1, "null pointer dereference");

        java.io.ByteArrayOutputStream tombstone = new java.io.ByteArrayOutputStream();
        field(tombstone, 1, 1);
        field(tombstone, 2, "samsung/r0quew/r0q:14/UP1A/1:user/release-keys");
        field(tombstone, 5, 100);
        field(tombstone, 6, 777);
        field(tombstone, 10, signal.toByteArray());
        field(tombstone, 15, cause.toByteArray());
        for (java.io.ByteArrayOutputStream thread : new java.io.ByteArrayOutputStream[] {other, crashed}) {
            java.io.ByteArrayOutputStream entry = new java.io.ByteArrayOutputStream();
            field(entry, 1, thread == crashed ? 777 : 100);
            field(entry, 2, thread.toByteArray());
            field(tombstone, 16, entry.toByteArray());
        }
        field(tombstone, 20, 12345);
        addExit(5, 100, tombstone.toByteArray());

        String section = lastExits(LogBufferManager.buildExportText());
        assertTrue(section, section.contains("reason: CRASH_NATIVE"));
        assertTrue(section, section.contains("\n  trace: tombstone (40 of 45 frames)\n"));
        assertTrue(section, section.contains("\n  signal: SIGSEGV (11), code SEGV_MAPERR (1), fault address 0x10\n"));
        assertTrue(section, section.contains("\n  cause: null pointer dereference\n"));
        assertTrue(section, section.contains("\n  thread 777 \"RenderThread\"\n"));
        assertTrue(section, section.contains("\n  #00 libhwui.so (android::uirenderer::Step0()+40)\n"));
        assertTrue("a frame with no symbol lost its pc: " + section, section.contains("\n  #01 libhwui.so pc 1001\n"));
        assertTrue(section, section.contains("\n  #39 libhwui.so (android::uirenderer::Step39()+79)\n"));
        assertFalse("a 41st frame was shown: " + section, section.contains("Step40()"));
        assertFalse("a thread that didn't crash was shown: " + section, section.contains("OnlyOnMain"));
    }

    @Test @Config(sdk = 33)
    public void aBrokenTombstoneSaysSoInsteadOfDroppingTheSection() throws Exception {
        addExit(5, 100, new byte[] {(byte) 0x82, 0x01, 0x7f});
        String section = lastExits(LogBufferManager.buildExportText());
        assertTrue(section, section.contains("reason: CRASH_NATIVE"));
        assertTrue(section, section.contains("\n  trace: tombstone unreadable (3 bytes)"));
    }

    @Test @Config(sdk = 30)
    public void tracesStayUnderOneSizeCapForTheWholeSection() throws Exception {
        // Five freezes in a row, each with a long dump, must not push the rest of the report out.
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        reportAMissingView();
        android.app.ActivityManager manager = (android.app.ActivityManager) RuntimeEnvironment
                .getApplication().getSystemService(Context.ACTIVITY_SERVICE);
        String wide = new String(new char[150]).replace('\0', 'x');
        for (int exit = 0; exit < 5; exit++) {
            StringBuilder dump = new StringBuilder("\"main\" prio=5 tid=1 Native\n");
            for (int frame = 0; frame < 60; frame++) {
                dump.append("  at com.ss.android.ugc.aweme.").append(wide).append(".f").append(frame)
                        .append("(A.java:").append(frame).append(")\n");
            }
            org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(
                    org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                            .setReason(6)
                            .setPid(500 + exit)
                            .setTimestamp(1757260800000L + exit * 60_000L)
                            .setProcessName(RuntimeEnvironment.getApplication().getPackageName())
                            .setTraceInputStream(new java.io.ByteArrayInputStream(
                                    dump.toString().getBytes(StandardCharsets.UTF_8)))
                            .build());
        }

        String section = lastExits(LogBufferManager.buildExportText());
        int traceChars = 0;
        int exitLines = 0;
        for (String line : section.split("\n")) {
            if (line.startsWith("  ")) traceChars += line.length() + 1;
            else if (line.startsWith("at: ")) exitLines++;
        }
        assertEquals("an exit line was pushed out by the traces: " + section, 5, exitLines);
        assertTrue("the traces took " + traceChars + " characters: " + section,
                traceChars <= LogBufferManager.EXIT_TRACE_MAX_CHARS + 60);
        assertTrue("the cap was hit without saying so: " + section,
                section.contains("\n  trace: cut here, the section is at its size cap"));
        assertEquals(section, section.indexOf("size cap"), section.lastIndexOf("size cap"));
    }

    @Test @Config(sdk = 30)
    public void androidSeventeenExitReasonsHaveNames() throws Exception {
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        reportAMissingView();
        android.app.ActivityManager manager = (android.app.ActivityManager) RuntimeEnvironment
                .getApplication().getSystemService(Context.ACTIVITY_SERVICE);
        for (int reason = 17; reason <= 18; reason++) {
            org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(
                    org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                            .setReason(reason)
                            .setTimestamp(1757260800000L + reason)
                            .setProcessName(RuntimeEnvironment.getApplication().getPackageName())
                            .build());
        }
        String section = lastExits(LogBufferManager.buildExportText());
        assertTrue(section, section.contains("reason: MEMORY_LIMITER"));
        assertTrue(section, section.contains("reason: ANOMALY"));
        assertFalse(section, section.contains("REASON 1"));
        assertFalse("an exit with no trace to read got trace lines: " + section, section.contains("trace:"));
    }

    @Test @Config(sdk = 30)
    public void aFullSharedPoolNoLongerHoldsUpTheDiagnosticSave() throws Exception {
        // The save ran on the shared pool, so other work filling it turned the save away. It has
        // a thread of its own now and saves while every shared worker is busy.
        Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        reportAMissingView();
        StallingMediaProvider media = StallingMediaProvider.register(context, temporary.newFile());
        ShadowToast.reset();
        try (var saturation = app.morphe.extension.shared.BackgroundPoolSaturation.fill()) {
            LogBufferManager.exportToFile();
            awaitToast("Full report saved to ");
        }
        assertTrue(media.published);
        assertTrue(new String(Files.readAllBytes(media.file.toPath()), StandardCharsets.UTF_8)
                .startsWith("MORPHE DIAGNOSTIC REPORT\n"));
    }

    @Test @Config(sdk = 30)
    public void aSecondTapWhileTheMediaStoreStallsStartsNoSecondDiagnosticWriter() throws Exception {
        // One save is out at a time, and the slot comes back when a stalled one finally returns.
        Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        reportAMissingView();
        StallingMediaProvider media = StallingMediaProvider.register(context, temporary.newFile()).hold();
        ShadowToast.reset();
        LogBufferManager.exportToFile();
        assertTrue("the save never reached the media store", media.awaitInserting());

        LogBufferManager.exportToFile();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(String.valueOf(ShadowToast.getTextOfLatestToast()),
                String.valueOf(ShadowToast.getTextOfLatestToast()).startsWith("A diagnostic report is already being saved"));

        media.release();
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("a second tap started another writer", 1, media.inserts.get());
        assertTrue(String.valueOf(ShadowToast.getTextOfLatestToast()).startsWith("Full report saved to "));

        LogBufferManager.exportToFile();
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        assertEquals("the slot stayed taken after the stalled save returned", 2, media.inserts.get());
    }

    /** One exit of the app's own process carrying the trace Android kept, in a report worth making. */
    private static void addExit(int reason, int pid, byte[] trace) {
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        reportAMissingView();
        android.app.ActivityManager manager = (android.app.ActivityManager) RuntimeEnvironment
                .getApplication().getSystemService(Context.ACTIVITY_SERVICE);
        org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(
                org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                        .setReason(reason)
                        .setPid(pid)
                        .setTimestamp(1757260800000L)
                        .setProcessName(RuntimeEnvironment.getApplication().getPackageName())
                        .setTraceInputStream(new java.io.ByteArrayInputStream(trace))
                        .build());
    }

    /** The LAST EXITS section, up to the next section's header. */
    private static String lastExits(String report) {
        int start = report.indexOf("[LAST EXITS]\n");
        assertTrue("no last exits: " + report, start >= 0);
        int end = report.indexOf("\n[", start + 1);
        return report.substring(start, end < 0 ? report.length() : end + 1);
    }

    private static void writeAscii(java.io.ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }

    /** A protobuf varint field, the way tombstone.proto's numbers travel. */
    private static void field(java.io.ByteArrayOutputStream out, int number, long value) {
        varint(out, (long) number << 3);
        varint(out, value);
    }

    /** A protobuf length-delimited field: a string or a nested message. */
    private static void field(java.io.ByteArrayOutputStream out, int number, byte[] value) {
        varint(out, ((long) number << 3) | 2);
        varint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void field(java.io.ByteArrayOutputStream out, int number, String value) {
        field(out, number, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void varint(java.io.ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) {
            out.write((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    /** Off the main thread the section waits for the scan, so every report after this agrees. */
    private static void settleOtherBundles() {
        Thread scan = new Thread(() -> app.morphe.extension.shared.diagnostics.OtherBundles.lines(""));
        scan.start();
        try {
            scan.join(TimeUnit.SECONDS.toMillis(30));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        assertFalse("the other-bundles scan never finished", scan.isAlive());
    }

    private static String unstamped(String report) {
        return report.replaceAll("(?m)^generated_utc: .*$", "generated_utc: -");
    }

    private static void reportAMissingView() {
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        app.morphe.extension.shared.diagnostics.HookStatus.missingViewId("comments", "jlk");
    }

    private static String awaitToast(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            String said = String.valueOf(ShadowToast.getTextOfLatestToast());
            if (said.startsWith(prefix)) return said;
            Thread.sleep(10);
        }
        throw new AssertionError("no toast starting \"" + prefix + "\"; the last said: "
                + ShadowToast.getTextOfLatestToast());
    }

    @Test public void repeatedLegacyExportsUseTheRealUniqueDocumentsPaths() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String report = "MORPHE DIAGNOSTIC REPORT\nschema: 1\n";
        String first = LogBufferManager.writeToFile(context, report);
        String second = LogBufferManager.writeToFile(context, report);
        File firstFile = new File(first);
        File secondFile = new File(second);
        try {
            File documents = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            assertTrue(documents != null);
            assertTrue(first.startsWith(documents.getAbsolutePath()));
            assertTrue(second.startsWith(documents.getAbsolutePath()));
            assertNotEquals(first, second);
            assertEquals(report, new String(Files.readAllBytes(firstFile.toPath()), StandardCharsets.UTF_8));
            assertEquals(report, new String(Files.readAllBytes(secondFile.toPath()), StandardCharsets.UTF_8));
        } finally {
            firstFile.delete();
            secondFile.delete();
        }
    }
}

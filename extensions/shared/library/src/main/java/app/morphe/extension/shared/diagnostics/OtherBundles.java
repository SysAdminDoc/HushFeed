/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.diagnostics;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import app.morphe.extension.shared.Utils;

/**
 * Names the code other patch bundles added to this TikTok, and says whose code the latest crash
 * died in, for the diagnostic report.
 *
 * <p>Two reports so far crashed in code Hushfeed never touched: another bundle picked beside it
 * had rewritten or added to TikTok, and it took a round of questions to find that out. TikTok's
 * own dex holds no class under {@code app/} (none in any of 47.7.3's dex files), and every Morphe
 * or ReVanced style bundle puts its extension there ({@code app/morphe/extension},
 * {@code app/revanced/extension}, {@code app/ftl/extension}). So any {@code app/} class that is not
 * Hushfeed's came from another bundle. A bundle that only rewrites TikTok's own classes adds
 * nothing here and cannot be seen this way.
 *
 * <p>The scan reads each dex file's sorted string table out of the installed APK and stops once
 * it is past the {@code Lapp/} names, so it never holds a dex file in memory. It still inflates a
 * good part of a few hundred megabytes, so it never runs on the main thread: a report copied
 * from there before the scan finished says so instead of waiting.
 */
public final class OtherBundles {
    public static final String TITLE = "OTHER PATCH BUNDLES";

    /** Hushfeed's own extension packages, as dex type descriptor prefixes. */
    private static final String[] OWN_PACKAGES = {
            "Lapp/morphe/extension/shared/", "Lapp/morphe/extension/tiktok/",
    };
    /** Libraries Hushfeed's extension ships under their own names (the MP3 encoder). */
    private static final String[] OWN_LIBRARIES = {"de.sciss.jump3r."};
    private static final byte[] PREFIX = {'L', 'a', 'p', 'p', '/'};
    /** Longer than any class name a bundle ships; a longer string is not a class. */
    private static final int NAME_MAX_BYTES = 512;
    /** About ten times the strings in TikTok 47.7.3's largest dex file, so only a corrupt file trips it. */
    private static final int STRINGS_MAX = 1 << 20;
    /** A report section header such as "[RECENT MORPHE EVENTS]", which ends the stack trace. */
    private static final java.util.regex.Pattern SECTION = java.util.regex.Pattern.compile("\\[[A-Z][A-Z ]*\\]");

    private static final Object SCAN_LOCK = new Object();
    private static final AtomicBoolean SCAN_STARTED = new AtomicBoolean();
    private static volatile Scan result;
    private static volatile List<File> apksForTests;
    /** The scanning thread's id while it runs at background priority, else 0. */
    private static volatile int scanThread;

    private OtherBundles() {
    }

    /** What a scan found: package name to how many of its classes are in the APK. */
    static final class Scan {
        final Map<String, Integer> packages;
        final String failure;

        Scan(Map<String, Integer> packages, String failure) {
            this.packages = packages;
            this.failure = failure;
        }
    }

    /** Starts the scan on a thread of its own, once, so a report asked for later has it ready. */
    public static void prefetch() {
        if (result != null || !SCAN_STARTED.compareAndSet(false, true)) return;
        boolean started = Utils.runOnOwnThread("hushfeed-other-bundles", () -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                scanThread = android.os.Process.myTid();
            } catch (Throwable ignored) {
                // A thread left at its default priority still finishes.
            }
            try {
                scanNow();
            } finally {
                scanThread = 0;
            }
        });
        if (!started) SCAN_STARTED.set(false);
    }

    /**
     * The report section's lines. Off the main thread this waits for the scan; on it, a scan that
     * has not finished is started and the section says so.
     *
     * @param crashReport the latest Java crash report as the export already redacted it, or empty
     */
    public static List<String> lines(String crashReport) {
        Scan scan = result;
        if (scan == null && !Utils.isCurrentlyOnMainThread()) {
            // A saved report waits on the scan, so the scan stops yielding to everything else.
            int running = scanThread;
            if (running != 0) {
                try {
                    android.os.Process.setThreadPriority(running, android.os.Process.THREAD_PRIORITY_DEFAULT);
                } catch (Throwable ignored) {
                    // It finishes at background priority, only later.
                }
            }
            scan = scanNow();
        }
        if (scan == null) prefetch();
        List<String> lines = new ArrayList<>();
        lines.add("extension_packages: " + describe(scan));
        lines.addAll(crashLines(crashReport));
        return lines;
    }

    static String describe(Scan scan) {
        if (scan == null) return "still being read, copy the report again in a moment";
        if (scan.failure != null) return "could not be read (" + scan.failure + ")";
        if (scan.packages.isEmpty()) return "none";
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Integer> found : scan.packages.entrySet()) {
            if (text.length() > 0) text.append(", ");
            int count = found.getValue();
            text.append(found.getKey()).append(" (").append(count)
                    .append(count == 1 ? " class)" : " classes)");
        }
        return text.toString();
    }

    private static Scan scanNow() {
        synchronized (SCAN_LOCK) {
            Scan done = result;
            if (done != null) return done;
            // A failure is said in the report itself. Logging it would add an event to the very
            // buffer the report is being built from.
            try {
                done = new Scan(scan(apks()), null);
            } catch (IOException unreadable) {
                // The installed APK doesn't change while TikTok runs, so neither would the answer.
                done = new Scan(Collections.emptyMap(), unreadable.getClass().getSimpleName());
            } catch (Throwable failure) {
                // No context yet, or out of memory: the next report tries again.
                SCAN_STARTED.set(false);
                return new Scan(Collections.emptyMap(), failure.getClass().getSimpleName());
            }
            result = done;
            return done;
        }
    }

    private static List<File> apks() {
        List<File> forTests = apksForTests;
        if (forTests != null) return forTests;
        Context context = Utils.getContext();
        if (context == null) throw new IllegalStateException("no context yet");
        ApplicationInfo info = context.getApplicationInfo();
        List<File> apks = new ArrayList<>();
        apks.add(new File(info.sourceDir));
        if (info.splitSourceDirs != null) {
            for (String split : info.splitSourceDirs) apks.add(new File(split));
        }
        return apks;
    }

    /** Every non-Hushfeed {@code app/} package in these APKs' dex files, grouped four names deep. */
    static Map<String, Integer> scan(List<File> apks) throws IOException {
        Set<String> classes = new HashSet<>();
        for (File apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                    ZipEntry entry = entries.nextElement();
                    if (!isDexEntry(entry.getName())) continue;
                    try (InputStream input = zip.getInputStream(entry)) {
                        scanDex(input, classes);
                    }
                }
            }
        }
        Map<String, Integer> packages = new TreeMap<>();
        for (String descriptor : classes) {
            String group = group(descriptor);
            Integer count = packages.get(group);
            packages.put(group, count == null ? 1 : count + 1);
        }
        return packages;
    }

    static boolean isDexEntry(String name) {
        if (!name.startsWith("classes") || !name.endsWith(".dex")) return false;
        for (int i = "classes".length(); i < name.length() - ".dex".length(); i++) {
            char c = name.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Adds this dex file's foreign class descriptors to {@code classes}. */
    static void scanDex(InputStream raw, Set<String> classes) throws IOException {
        Reader in = new Reader(raw);
        byte[] header = in.read(0x70);
        if (header[0] != 'd' || header[1] != 'e' || header[2] != 'x' || header[3] != '\n') {
            throw new IOException("not a dex file");
        }
        int count = le(header, 0x38);
        int idsOffset = le(header, 0x3C);
        if (count == 0) return;
        if (count < 0 || count > STRINGS_MAX || idsOffset < 0x70) throw new IOException("bad string table");
        in.skipTo(idsOffset);
        int[] offsets = new int[count];
        byte[] chunk = new byte[4096];
        for (int i = 0; i < count; ) {
            int take = Math.min(chunk.length / 4, count - i);
            in.readFully(chunk, take * 4);
            for (int j = 0; j < take; j++) offsets[i++] = le(chunk, j * 4);
        }
        // The ids are sorted by content and the data is laid out in id order, which is what lets
        // the read stop once it is past "Lapp/". Data in any other order is read whole.
        boolean inIdOrder = true;
        for (int i = 1; i < count && inIdOrder; i++) inIdOrder = offsets[i] > offsets[i - 1];
        if (!inIdOrder) Arrays.sort(offsets);

        byte[] name = new byte[NAME_MAX_BYTES];
        for (int offset : offsets) {
            if (offset < in.position) continue;
            in.skipTo(offset);
            in.skipUleb128();
            int length = in.readUntilNul(name);
            int order = compareToPrefix(name, length);
            if (order == 0) {
                if (length > 0 && length < NAME_MAX_BYTES && name[length - 1] == ';') {
                    String descriptor = new String(name, 0, length, "ISO-8859-1");
                    if (!isOwn(descriptor) && descriptor.indexOf('/', PREFIX.length) > 0) {
                        classes.add(descriptor);
                    }
                }
            } else if (order > 0 && inIdOrder) {
                return;
            }
        }
    }

    /**
     * 0 when the string starts with "Lapp/", above 0 when it sorts after every such string, below
     * 0 otherwise. Only an ASCII difference decides "after": a dex sorts by UTF-16 unit and stores
     * Modified UTF-8, where U+0000 is two high bytes yet sorts first.
     */
    private static int compareToPrefix(byte[] name, int length) {
        for (int i = 0; i < PREFIX.length; i++) {
            if (i >= length) return -1;
            int have = name[i] & 0xFF;
            if (have == PREFIX[i]) continue;
            if (have >= 0x80) return -1;
            return have > PREFIX[i] ? 1 : -1;
        }
        return 0;
    }

    private static boolean isOwn(String descriptor) {
        for (String own : OWN_PACKAGES) {
            if (descriptor.startsWith(own)) return true;
        }
        // The extension's own R class sits directly in app/morphe/extension.
        return descriptor.startsWith("Lapp/morphe/extension/")
                && descriptor.indexOf('/', "Lapp/morphe/extension/".length()) < 0;
    }

    /** "Lapp/ftl/extension/dpi/DensityPatch;" groups as "app.ftl.extension.dpi". */
    static String group(String descriptor) {
        String path = descriptor.substring(1, descriptor.length() - 1);
        int end = path.lastIndexOf('/');
        String packagePath = path.substring(0, end);
        int depth = 0;
        for (int i = 0; i < packagePath.length(); i++) {
            if (packagePath.charAt(i) == '/' && ++depth == 4) {
                packagePath = packagePath.substring(0, i);
                break;
            }
        }
        return packagePath.replace('/', '.');
    }

    /** Whose code a class name is, for one stack frame. */
    static String owner(String className) {
        String descriptor = "L" + className.replace('.', '/') + ";";
        if (isOwn(descriptor)) return "Hushfeed";
        for (String library : OWN_LIBRARIES) {
            if (className.startsWith(library)) return "Hushfeed";
        }
        if (className.startsWith("app.") && className.indexOf('.', 4) > 0) {
            return "another patch bundle (" + group(descriptor) + ")";
        }
        return "TikTok's own classes, which a bundle may have rewritten";
    }

    private static boolean isPlatform(String className) {
        return className.startsWith("java.") || className.startsWith("javax.")
                || className.startsWith("jdk.") || className.startsWith("sun.")
                || className.startsWith("libcore.") || className.startsWith("dalvik.")
                || className.startsWith("android.") || className.startsWith("com.android.");
    }

    /**
     * The top app frame of the latest Java crash and whose code it is, or nothing without one.
     * When the exception wraps a cause whose top app frame is somewhere else, that frame is named
     * too: a wrapper in TikTok's own code (RxJava, coroutines) often sits over the real failure.
     *
     * <p>The redacted trace lists the exception, then its causes and suppressed exceptions breadth
     * first. Until the first suppressed one, each "Caused by" block is the next link of the chain,
     * so the deepest cause is the last of those blocks with an app frame.
     */
    static List<String> crashLines(String crashReport) {
        if (crashReport == null || crashReport.isEmpty()) return Collections.emptyList();
        int stack = crashReport.indexOf("[STACK TRACE]\n");
        if (stack < 0) return Collections.emptyList();
        String top = null;
        String topClass = null;
        String cause = null;
        String causeClass = null;
        boolean inCause = false;
        boolean causeTaken = false;
        boolean chainEnded = false;
        int foreign = 0;
        Map<String, Boolean> seen = new HashMap<>();
        for (String line : crashReport.substring(stack + "[STACK TRACE]\n".length()).split("\n")) {
            // Redaction markers and an exception message can start a line with "[" as well.
            if (SECTION.matcher(line).matches()) break;
            if (line.startsWith("Caused by: ")) {
                inCause = !chainEnded;
                causeTaken = false;
                continue;
            }
            if (line.startsWith("Suppressed: ")) {
                chainEnded = true;
                inCause = false;
                continue;
            }
            String frame = line.trim();
            if (!frame.startsWith("at ")) continue;
            int call = frame.indexOf('(');
            String method = call > 0 ? frame.substring(3, call) : frame.substring(3);
            int dot = method.lastIndexOf('.');
            if (dot <= 0) continue;
            String className = method.substring(0, dot);
            if (isPlatform(className)) continue;
            if (top == null) {
                top = frame;
                topClass = className;
            } else if (inCause && !causeTaken) {
                cause = frame;
                causeClass = className;
                causeTaken = true;
            }
            if (owner(className).startsWith("another") && seen.put(frame, Boolean.TRUE) == null) foreign++;
        }
        if (top == null) return Collections.emptyList();
        List<String> lines = new ArrayList<>();
        lines.add("latest_crash_top_app_frame: " + top);
        lines.add("latest_crash_top_app_frame_from: " + owner(topClass));
        if (cause != null && !cause.equals(top)) {
            lines.add("latest_crash_cause_app_frame: " + cause);
            lines.add("latest_crash_cause_app_frame_from: " + owner(causeClass));
        }
        lines.add("latest_crash_frames_from_other_bundles: " + foreign);
        return lines;
    }

    static void useApksForTests(List<File> apks) {
        synchronized (SCAN_LOCK) {
            apksForTests = apks;
            result = null;
            SCAN_STARTED.set(false);
        }
    }

    /** Runs the scan now, as a report saved to a file would. */
    static void scanForTests() {
        scanNow();
    }

    private static int le(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8
                | (bytes[at + 2] & 0xFF) << 16 | (bytes[at + 3] & 0xFF) << 24;
    }

    /**
     * A forward-only reader over one inflating stream with its own buffer. Skipping through
     * the stream's own skip would inflate 512 bytes per call.
     */
    private static final class Reader {
        private final InputStream input;
        private final byte[] buffer = new byte[64 * 1024];
        private int start;
        private int end;
        long position;

        Reader(InputStream input) {
            this.input = input;
        }

        private boolean fill() throws IOException {
            if (start < end) return true;
            int read = input.read(buffer, 0, buffer.length);
            if (read <= 0) return false;
            start = 0;
            end = read;
            return true;
        }

        private int next() throws IOException {
            if (!fill()) throw new IOException("dex file ended early");
            position++;
            return buffer[start++] & 0xFF;
        }

        byte[] read(int length) throws IOException {
            byte[] bytes = new byte[length];
            readFully(bytes, length);
            return bytes;
        }

        void readFully(byte[] into, int length) throws IOException {
            int at = 0;
            while (at < length) {
                if (!fill()) throw new IOException("dex file ended early");
                int take = Math.min(length - at, end - start);
                System.arraycopy(buffer, start, into, at, take);
                start += take;
                at += take;
                position += take;
            }
        }

        void skipTo(long target) throws IOException {
            while (position < target) {
                if (!fill()) throw new IOException("dex file ended early");
                int take = (int) Math.min(target - position, end - start);
                start += take;
                position += take;
            }
        }

        void skipUleb128() throws IOException {
            for (int i = 0; i < 5; i++) {
                if ((next() & 0x80) == 0) return;
            }
        }

        /** Reads up to the string's NUL into {@code into}; returns its length, or the cap when longer. */
        int readUntilNul(byte[] into) throws IOException {
            int length = 0;
            while (true) {
                int b = next();
                if (b == 0) return length;
                if (length < into.length) into[length] = (byte) b;
                length++;
                if (length > into.length) return into.length;
            }
        }
    }
}

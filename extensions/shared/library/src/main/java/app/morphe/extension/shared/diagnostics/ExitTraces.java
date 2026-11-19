/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.diagnostics;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The stack Android kept for an exit that left one, as report lines.
 *
 * <p>An ANR record carries the text dump Android took of the frozen process. Most of the time
 * that is ART's Java dump, where the main thread is {@code "main" ... tid=1}. When ART can't
 * answer (Android 15 does this for a process frozen hard enough), the record holds debuggerd's
 * native backtrace instead, whose headers say {@code sysTid=} and never {@code tid=}, so there
 * the main thread is the one whose system id is the process's own.
 *
 * <p>A native crash record (Android 12 and later) carries the tombstone as protobuf
 * (system/core/debuggerd/proto/tombstone.proto): the signal, the abort message, the causes
 * debuggerd worked out, and every thread's backtrace, of which the crashing thread's is shown.
 *
 * <p>Both read through a bound, keep at most {@link #MAX_FRAMES} frames and cut every line at
 * {@link #MAX_LINE} characters, so a giant dump or a mangled C++ name can't fill the report.
 * Nothing here needs an Android API: the caller hands over the stream.
 */
public final class ExitTraces {
    /** Frames shown per trace, enough to see past the looper into the code that held it. */
    public static final int MAX_FRAMES = 40;
    /** Longest line kept; the rest of a longer one is cut. */
    public static final int MAX_LINE = 200;
    /** Lines of an ANR dump read while looking for the main thread before giving up. */
    private static final int MAX_TEXT_LINES = 200_000;
    /** Bytes of a tombstone read; real ones are far smaller. */
    private static final int MAX_TOMBSTONE_BYTES = 4 * 1024 * 1024;

    private ExitTraces() {
    }

    /**
     * The main thread of an ANR dump in either format, header first, then its frames and the
     * lock lines between them. The first line always says what was found.
     *
     * @param pid the exit's process id, which names the main thread in a native dump
     */
    public static List<String> anr(InputStream trace, int pid) throws IOException {
        // InputStreamReader replaces malformed UTF-8 instead of failing, so a dump cut in the
        // middle of a character is still read.
        BufferedReader reader = new BufferedReader(new InputStreamReader(trace, StandardCharsets.UTF_8));
        String header = null;
        String shape = null;
        List<String> kept = new ArrayList<>();
        int frames = 0;
        int read = 0;
        String line;
        while (read < MAX_TEXT_LINES && (line = reader.readLine()) != null) {
            read++;
            if (line.startsWith("\"")) {
                if (header != null) break;
                if (isJavaMain(line)) {
                    shape = "java dump";
                } else if (pid > 0 && isNativeThread(line, pid)) {
                    shape = "native backtrace";
                } else {
                    continue;
                }
                header = cut(line.trim());
                continue;
            }
            if (header == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (frames > 0) break;
                continue;
            }
            if (trimmed.startsWith("at ")) {
                if (frames++ < MAX_FRAMES) kept.add(cut(trimmed));
            } else if (trimmed.startsWith("native: #")) {
                if (frames++ < MAX_FRAMES) kept.add(cut("native: " + nativeFrame(trimmed.substring(8))));
            } else if (trimmed.startsWith("#")) {
                if (frames++ < MAX_FRAMES) kept.add(cut(nativeFrame(trimmed)));
            } else if (trimmed.startsWith("- ") && frames <= MAX_FRAMES) {
                // A lock line names the thread holding what the main thread waits for.
                kept.add(cut(trimmed));
            }
        }
        List<String> lines = new ArrayList<>();
        if (header == null) {
            lines.add("trace: no main thread in " + read + " lines");
            return lines;
        }
        lines.add("trace: main thread, " + shape + " (" + Math.min(frames, MAX_FRAMES) + " of " + frames + " frames)");
        lines.add(header);
        lines.addAll(kept);
        return lines;
    }

    /** ART's header for the main thread: {@code "main" prio=5 tid=1 Blocked}. */
    private static boolean isJavaMain(String header) {
        if (!header.startsWith("\"main\" ")) return false;
        for (String token : header.split(" ")) {
            if (token.equals("tid=1")) return true;
        }
        return false;
    }

    /** debuggerd's header: {@code "name" sysTid=1234}. */
    private static boolean isNativeThread(String header, int pid) {
        String wanted = "sysTid=" + pid;
        for (String token : header.split(" ")) {
            if (token.equals(wanted)) return true;
        }
        return false;
    }

    /**
     * A native frame shortened to what a reader can use:
     * {@code #01 pc 0000000000012345  /system/lib64/libutils.so (android::Looper::pollOnce(int)+256) (BuildId: ab)}
     * becomes {@code #01 libutils.so (android::Looper::pollOnce(int)+256)}. Without a symbol the
     * relative pc stays, which is what a symbolizer needs.
     */
    static String nativeFrame(String frame) {
        String[] parts = frame.trim().split("\\s+", 4);
        if (parts.length < 4 || !parts[0].startsWith("#") || !parts[1].equals("pc")) return frame.trim();
        String rest = parts[3];
        int open = rest.indexOf(" (");
        String path = open < 0 ? rest : rest.substring(0, open);
        String symbol = null;
        if (open >= 0) {
            for (String group : groups(rest.substring(open))) {
                if (group.startsWith("BuildId:") || group.startsWith("offset ") || group.equals("deleted")) continue;
                symbol = group;
                break;
            }
        }
        String file = basename(path);
        if (symbol != null) return parts[0] + " " + file + " (" + symbol + ")";
        return parts[0] + " " + file + " pc " + stripZeros(parts[2]);
    }

    /** The top-level parenthesized groups of a frame's tail; symbols carry parentheses of their own. */
    private static List<String> groups(String tail) {
        List<String> groups = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c == '(') {
                if (depth++ == 0) start = i + 1;
            } else if (c == ')' && depth > 0) {
                if (--depth == 0) groups.add(tail.substring(start, i));
            }
        }
        return groups;
    }

    private static String basename(String path) {
        if (path.startsWith("[")) return path;
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String stripZeros(String hex) {
        int i = 0;
        while (i < hex.length() - 1 && hex.charAt(i) == '0') i++;
        return hex.substring(i);
    }

    private static String cut(String line) {
        return line.length() <= MAX_LINE ? line : line.substring(0, MAX_LINE) + "...";
    }

    /**
     * A native crash's tombstone: the signal, the abort message and causes when there are any,
     * then the crashing thread's frames. The first line always says what was found.
     */
    public static List<String> nativeCrash(InputStream tombstone) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int n;
        while (bytes.size() < MAX_TOMBSTONE_BYTES && (n = tombstone.read(buffer)) > 0) {
            bytes.write(buffer, 0, Math.min(n, MAX_TOMBSTONE_BYTES - bytes.size()));
        }
        List<String> lines = new ArrayList<>();
        Tombstone parsed;
        try {
            parsed = Tombstone.parse(bytes.toByteArray());
        } catch (RuntimeException malformed) {
            lines.add("trace: tombstone unreadable (" + bytes.size() + " bytes)");
            return lines;
        }
        TombstoneThread crashed = null;
        for (TombstoneThread thread : parsed.threads) {
            if (thread.id == parsed.tid) crashed = thread;
        }
        int frames = crashed == null ? 0 : crashed.frames.size();
        lines.add(crashed == null
                ? "trace: tombstone, no crashing thread " + parsed.tid
                : "trace: tombstone (" + Math.min(frames, MAX_FRAMES) + " of " + frames + " frames)");
        if (parsed.signal != null) lines.add(cut(parsed.signal));
        if (!parsed.abortMessage.isEmpty()) {
            lines.add(cut("abort: " + parsed.abortMessage.replace('\n', ' ')));
        }
        for (String cause : parsed.causes) {
            lines.add(cut("cause: " + cause.replace('\n', ' ')));
        }
        if (crashed != null) {
            lines.add(cut("thread " + crashed.id + " \"" + crashed.name + "\""));
            for (int i = 0; i < Math.min(frames, MAX_FRAMES); i++) {
                lines.add(cut(crashed.frames.get(i)));
            }
        }
        return lines;
    }

    private static final class TombstoneThread {
        int id;
        String name = "";
        final List<String> frames = new ArrayList<>();
    }

    /** The few tombstone.proto fields the report shows, read straight off the wire format. */
    private static final class Tombstone {
        long tid;
        String signal;
        String abortMessage = "";
        final List<String> causes = new ArrayList<>();
        final List<TombstoneThread> threads = new ArrayList<>();

        static Tombstone parse(byte[] data) {
            Tombstone tombstone = new Tombstone();
            Wire wire = new Wire(data, 0, data.length);
            while (wire.more()) {
                int key = (int) wire.varint();
                int field = key >>> 3;
                int type = key & 7;
                if (field == 6 && type == 0) {
                    tombstone.tid = wire.varint();
                } else if (field == 10 && type == 2) {
                    tombstone.signal = signal(wire.message());
                } else if (field == 14 && type == 2) {
                    tombstone.abortMessage = wire.string();
                } else if (field == 15 && type == 2) {
                    Wire cause = wire.message();
                    while (cause.more()) {
                        int causeKey = (int) cause.varint();
                        if (causeKey >>> 3 == 1 && (causeKey & 7) == 2) tombstone.causes.add(cause.string());
                        else cause.skip(causeKey & 7);
                    }
                } else if (field == 16 && type == 2) {
                    // map<uint32, Thread>: each entry is {1: key, 2: value}.
                    Wire entry = wire.message();
                    while (entry.more()) {
                        int entryKey = (int) entry.varint();
                        if (entryKey >>> 3 == 2 && (entryKey & 7) == 2) tombstone.threads.add(thread(entry.message()));
                        else entry.skip(entryKey & 7);
                    }
                } else {
                    wire.skip(type);
                }
            }
            return tombstone;
        }

        private static String signal(Wire wire) {
            long number = 0;
            long code = 0;
            String name = "";
            String codeName = "";
            boolean hasAddress = false;
            long address = 0;
            while (wire.more()) {
                int key = (int) wire.varint();
                int field = key >>> 3;
                int type = key & 7;
                if (field == 1 && type == 0) number = wire.varint();
                else if (field == 2 && type == 2) name = wire.string();
                else if (field == 3 && type == 0) code = wire.varint();
                else if (field == 4 && type == 2) codeName = wire.string();
                else if (field == 8 && type == 0) hasAddress = wire.varint() != 0;
                else if (field == 9 && type == 0) address = wire.varint();
                else wire.skip(type);
            }
            StringBuilder line = new StringBuilder("signal: ")
                    .append(name.isEmpty() ? "?" : name).append(" (").append((int) number).append(")")
                    .append(", code ").append(codeName.isEmpty() ? "?" : codeName)
                    .append(" (").append((int) code).append(")");
            if (hasAddress) line.append(", fault address 0x").append(Long.toHexString(address));
            return line.toString();
        }

        private static TombstoneThread thread(Wire wire) {
            TombstoneThread thread = new TombstoneThread();
            while (wire.more()) {
                int key = (int) wire.varint();
                int field = key >>> 3;
                int type = key & 7;
                if (field == 1 && type == 0) thread.id = (int) wire.varint();
                else if (field == 2 && type == 2) thread.name = wire.string();
                else if (field == 4 && type == 2) thread.frames.add(frame(thread.frames.size(), wire.message()));
                else wire.skip(type);
            }
            return thread;
        }

        private static String frame(int index, Wire wire) {
            long relPc = 0;
            long offset = 0;
            String function = "";
            String file = "";
            while (wire.more()) {
                int key = (int) wire.varint();
                int field = key >>> 3;
                int type = key & 7;
                if (field == 1 && type == 0) relPc = wire.varint();
                else if (field == 4 && type == 2) function = wire.string();
                else if (field == 5 && type == 0) offset = wire.varint();
                else if (field == 6 && type == 2) file = wire.string();
                else wire.skip(type);
            }
            String number = String.format(Locale.US, "#%02d ", index);
            if (function.isEmpty()) return number + basename(file) + " pc " + Long.toHexString(relPc);
            return number + basename(file) + " (" + function + "+" + offset + ")";
        }
    }

    /** A window of protobuf wire bytes. Anything malformed throws, and the caller reports it. */
    private static final class Wire {
        private final byte[] data;
        private int at;
        private final int end;

        Wire(byte[] data, int at, int end) {
            this.data = data;
            this.at = at;
            this.end = end;
        }

        boolean more() {
            return at < end;
        }

        long varint() {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (at >= end) throw new IllegalStateException("varint past the end");
                byte b = data[at++];
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) return value;
            }
            throw new IllegalStateException("varint too long");
        }

        Wire message() {
            int length = length();
            Wire inner = new Wire(data, at, at + length);
            at += length;
            return inner;
        }

        String string() {
            int length = length();
            String value = new String(data, at, length, StandardCharsets.UTF_8);
            at += length;
            return value;
        }

        private int length() {
            long length = varint();
            if (length < 0 || length > end - at) throw new IllegalStateException("length past the end");
            return (int) length;
        }

        void skip(int type) {
            switch (type) {
                case 0: varint(); break;
                case 1: advance(8); break;
                case 2: advance(length()); break;
                case 5: advance(4); break;
                default: throw new IllegalStateException("wire type " + type);
            }
        }

        private void advance(int bytes) {
            if (bytes > end - at) throw new IllegalStateException("field past the end");
            at += bytes;
        }
    }
}

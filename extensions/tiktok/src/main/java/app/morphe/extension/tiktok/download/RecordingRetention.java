/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Durable ownership of recording bytes until their exact destination is acknowledged. */
final class RecordingRetention {
    static final String DIRECTORY = "hushfeed-recordings";
    private static final Object LOCK = new Object();
    private static final int MAX_JOURNAL_BYTES = 32 * 1024;
    final File root;
    final File legacyRoot;

    RecordingRetention(File root) throws IOException {
        this(root, null);
    }

    RecordingRetention(File root, File legacyRoot) throws IOException {
        this.legacyRoot = legacyRoot == null ? null : legacyRoot.getCanonicalFile();
        this.root = root.getCanonicalFile();
        if (!this.root.isDirectory() && !this.root.mkdirs())
            throw new IOException("Recording recovery storage unavailable");
    }

    static RecordingRetention in(Context context) throws IOException {
        if (context == null || context.getNoBackupFilesDir() == null)
            throw new IOException("Recording recovery storage unavailable");
        return new RecordingRetention(
                new File(context.getNoBackupFilesDir(), DIRECTORY),
                MediaCache.cacheDirectory(context));
    }

    private interface IO<T> {
        T run() throws IOException;
    }

    private <T> T locked(IO<T> work) throws IOException {
        synchronized (LOCK) {
            checkDirect(root, new File(root, "retention.lock"));
            try (RandomAccessFile lock =
                            new RandomAccessFile(new File(root, "retention.lock"), "rw");
                    FileLock ignored = lock.getChannel().lock()) {
                return work.run();
            }
        }
    }

    private List<File> directories() throws IOException {
        File[] files = root.listFiles();
        if (files == null) throw new IOException("Cannot inspect retained recordings");
        List<File> result = new ArrayList<>();
        for (File file : files)
            if (file.isDirectory() && file.getName().matches("[a-f0-9]{32}")) {
                if (!file.getCanonicalFile().equals(new File(root, file.getName()))) continue;
                // No source can be allocated before the first metadata commit. Reclaim only
                // a failed initial metadata write, never a directory containing recording bytes.
                if (!new File(file, "record.json").exists()
                        && !new File(file, "record.json.bak").exists()
                        && emptyInitial(file)) continue;
                result.add(file);
            }
        return result;
    }

    private static boolean emptyInitial(File directory) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Cannot inspect recording initialization");
        for (File child : children)
            if (!child.isFile()
                    || (!child.getName().equals("record.json.new")
                            && !child.getName().equals("owner.lock"))) return false;
        for (File child : children)
            if (!child.delete())
                throw new IOException("Cannot remove empty recording initialization");
        if (!directory.delete())
            throw new IOException("Cannot remove empty recording ownership directory");
        return true;
    }

    List<String> ids() throws IOException {
        return locked(
                () -> {
                    List<String> result = new ArrayList<>();
                    for (File directory : directories()) result.add(directory.getName());
                    return result;
                });
    }

    private boolean hasLegacyDebt() throws IOException {
        if (legacyRoot == null) return false;
        File[] files = legacyRoot.listFiles();
        if (files == null) throw new IOException("Cannot inspect legacy recording debt");
        for (File file : files) if (legacyCandidate(file)) return true;
        return false;
    }

    Record begin(String format, String creator, long started) throws IOException {
        return create(format, creator, started, null, true);
    }

    Record adopt(File source, String format, String creator, long started) throws IOException {
        checkLegacy(source);
        final String path = source.getCanonicalPath();
        String existing =
                locked(
                        () -> {
                            for (File directory : directories()) {
                                try {
                                    if (ownsLegacy(read(directory), path))
                                        return directory.getName();
                                } catch (IOException | RuntimeException corrupt) {
                                    /* Unknown ownership cannot claim a legacy source. */
                                }
                            }
                            return null;
                        });
        Record record =
                existing == null ? create(format, creator, started, source, false) : open(existing);
        if (record != null) {
            try {
                record.moveLegacy();
            } catch (IOException | RuntimeException failure) {
                try {
                    record.close();
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
        return record;
    }

    static boolean legacyCandidate(File file) {
        return file != null
                && file.isFile()
                && file.getName()
                        .matches(
                                "live-(?:[0-9]{12,}-[A-Za-z0-9._]*-)?-?[0-9]+\\.(flv|ts|mp4|scr)|screen-work--?[0-9]+\\.mp4");
    }

    private void checkLegacy(File file) throws IOException {
        if (legacyRoot == null
                || !file.getCanonicalFile().getParentFile().equals(legacyRoot)
                || !file.getName()
                        .matches(
                                "live-(?:[0-9]{12,}-[A-Za-z0-9._]*-)?-?[0-9]+\\.(flv|ts|mp4|scr)|screen-work--?[0-9]+\\.mp4")) {
            throw new IOException("Legacy recording ownership mismatch");
        }
    }

    private Record create(
            String format, String creator, long started, File legacy, boolean admission)
            throws IOException {
        if (!format.matches("flv|ts|screen|mp4"))
            throw new IOException("Unknown retained recording format");
        return locked(
                () -> {
                    // One active recording or retained debt consumes admission.
                    // CaptureJournal checks byte/sample limits and remaining-copy headroom.
                    if (admission && (!directories().isEmpty() || hasLegacyDebt())) {
                        throw new IOException(
                                "Retained recordings must be retried or discarded before recording"
                                        + " again");
                    }
                    String id = UUID.randomUUID().toString().replace("-", "");
                    File directory = new File(root, id);
                    if (!directory.mkdir())
                        throw new IOException("Cannot claim recording recovery directory");
                    JSONObject data = new JSONObject();
                    try {
                        data.put("v", 1);
                        data.put("id", id);
                        data.put("format", format);
                        data.put(
                                "creator",
                                creator == null
                                        ? ""
                                        : creator.replaceAll("[^A-Za-z0-9._-]", "")
                                                .substring(
                                                        0,
                                                        Math.min(
                                                                40,
                                                                creator.replaceAll(
                                                                                "[^A-Za-z0-9._-]",
                                                                                "")
                                                                        .length())));
                        data.put("started", started);
                        data.put("source", "source." + ("screen".equals(format) ? "scr" : format));
                        data.put("screenAudio", "NOT_APPLICABLE");
                        data.put("state", legacy == null ? "CAPTURING" : "ADOPTING");
                        if (legacy != null) {
                            data.put("legacy", legacy.getCanonicalPath());
                            data.put("origin", legacy.getCanonicalPath());
                            data.put("legacyUnknown", true);
                            int extras = 0;
                            if (("flv".equals(format) || "ts".equals(format))
                                    && !"live-".equals(LiveSpoolRecovery.prefixOf(legacy))) {
                                File[] candidates = legacyRoot.listFiles();
                                if (candidates == null)
                                    throw new IOException(
                                            "Cannot inspect legacy recording rewrites");
                                for (File candidate : candidates)
                                    if (legacyCandidate(candidate)
                                            && candidate.getName().endsWith(".mp4")
                                            && LiveSpoolRecovery.prefixOf(candidate)
                                                    .equals(LiveSpoolRecovery.prefixOf(legacy))) {
                                        if (extras == 64)
                                            throw new IOException(
                                                    "Too many legacy recording rewrites");
                                        data.put("extra" + extras++, candidate.getCanonicalPath());
                                    }
                            }
                            data.put("extras", extras);
                        }
                        write(directory, data);
                    } catch (JSONException failure) {
                        throw new IOException(failure);
                    } catch (IOException | RuntimeException failure) {
                        // Fresh ownership failed before any caller could allocate source bytes.
                        try {
                            if (!new File(directory, "record.json").exists()
                                    && !new File(directory, "record.json.bak").exists())
                                emptyInitial(directory);
                        } catch (IOException cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                        throw failure;
                    }
                    return lease(directory, data, false);
                });
    }

    Record open(String id) throws IOException {
        if (id == null || !id.matches("[a-f0-9]{32}"))
            throw new IOException("Invalid recording id");
        return locked(
                () -> {
                    File directory = new File(root, id);
                    if (!directory.isDirectory()) return null;
                    if (!directory.getCanonicalFile().equals(new File(root, id)))
                        throw new IOException("Recording ownership directory changed");
                    JSONObject data;
                    boolean quarantined = false;
                    try {
                        data = read(directory);
                    } catch (IOException | RuntimeException corrupt) {
                        quarantined = true;
                        data = new JSONObject();
                        try {
                            data.put("id", id);
                            data.put("format", "quarantined");
                            data.put("state", "QUARANTINED");
                            data.put("started", directory.lastModified());
                        } catch (JSONException failure) {
                            throw new IOException(failure);
                        }
                    }
                    return lease(directory, data, quarantined);
                });
    }

    private Record lease(File directory, JSONObject data, boolean quarantined) throws IOException {
        checkDirect(directory, new File(directory, "owner.lock"));
        RandomAccessFile access = new RandomAccessFile(new File(directory, "owner.lock"), "rw");
        try {
            FileLock lock;
            try {
                lock = access.getChannel().tryLock();
            } catch (OverlappingFileLockException active) {
                access.close();
                return null;
            }
            if (lock == null) {
                access.close();
                return null;
            }
            return new Record(directory, data, access, lock, quarantined);
        } catch (IOException | RuntimeException failure) {
            access.close();
            throw failure;
        }
    }

    private static boolean ownsLegacy(JSONObject data, String path) {
        if (path.equals(data.optString("legacy")) || path.equals(data.optString("origin")))
            return true;
        for (int i = 0; i < data.optInt("extras"); i++)
            if (path.equals(data.optString("extra" + i))) return true;
        return false;
    }

    boolean protectsCache(File file) throws IOException {
        String path = file.getCanonicalPath();
        return locked(
                () -> {
                    for (File directory : directories()) {
                        try {
                            if (ownsLegacy(read(directory), path)) return true;
                        } catch (IOException | RuntimeException corrupt) {
                            /* Legacy candidates remain protected by their filename. */
                        }
                    }
                    return false;
                });
    }

    boolean protectsPending(String key) throws IOException {
        return locked(
                () -> {
                    for (File directory : directories()) {
                        JSONObject data;
                        try {
                            data = read(directory);
                        } catch (IOException | RuntimeException corrupt) {
                            return true;
                        }
                        if (key.equals(data.optString("intentKey"))
                                || key.equals(data.optString("uri"))) return true;
                    }
                    return false;
                });
    }

    private static void checkDirect(File directory, File file) throws IOException {
        File canonical = directory.getCanonicalFile();
        if (!file.getParentFile().getCanonicalFile().equals(canonical)
                || !file.getCanonicalFile().equals(new File(canonical, file.getName())))
            throw new IOException("Recording file ownership changed");
    }

    private static boolean metadata(String name) {
        return name.equals("record.json")
                || name.equals("record.json.new")
                || name.equals("record.json.bak");
    }

    private JSONObject read(File directory) throws IOException {
        File base = new File(directory, "record.json");
        for (String suffix : new String[] {"", ".bak", ".new"})
            checkDirect(directory, new File(directory, "record.json" + suffix));
        if (base.length() > MAX_JOURNAL_BYTES
                || new File(base + ".bak").length() > MAX_JOURNAL_BYTES)
            throw new IOException("Recording journal is too large");
        try (FileInputStream input = new AtomicFile(base).openRead()) {
            byte[] bytes = new byte[MAX_JOURNAL_BYTES + 1];
            int used = 0, read;
            while ((read = input.read(bytes, used, bytes.length - used)) > 0) {
                used += read;
                if (used > MAX_JOURNAL_BYTES)
                    throw new IOException("Recording journal is too large");
            }
            JSONObject data = new JSONObject(new String(bytes, 0, used, StandardCharsets.UTF_8));
            if (data.optInt("v") != 1 || !directory.getName().equals(data.optString("id")))
                throw new IOException("Unknown recording journal");
            String format = data.optString("format"),
                    source = data.optString("source"),
                    state = data.optString("state");
            String expected = "source." + ("screen".equals(format) ? "scr" : format);
            if (!format.matches("flv|ts|screen|mp4")
                    || !source.equals(expected)
                    || !state.matches(
                            "CAPTURING|ADOPTING|READY|READY_MP4|INSERT_INTENT|DESTINATION_LOCATED|FILE_INTENT|ACKNOWLEDGED|DISCARDED"))
                throw new IOException("Incomplete recording ownership schema");
            int extraCount = data.optInt("extras");
            if (extraCount < 0 || extraCount > 64)
                throw new IOException("Invalid legacy recording group");
            String finalized = data.optString("final");
            if (!finalized.isEmpty()
                    && (!finalized.matches("source\\.(flv|ts|scr|mp4)|screen\\.mp4|remux\\.mp4")
                            || data.optLong("bytes") < 1
                            || !data.optString("sha256").matches("[a-f0-9]{64}")))
                throw new IOException("Incomplete finalized recording receipt");
            File[] owned = directory.listFiles();
            if (owned == null) throw new IOException("Cannot inspect recording ownership files");
            for (File file : owned) {
                String name = file.getName();
                if (metadata(name) || name.equals("owner.lock")) continue;
                checkDirect(directory, file);
                boolean extra = false;
                for (int i = 0; i < extraCount; i++)
                    if (name.equals("legacy-" + i + ".mp4")) extra = true;
                if (!file.isFile()
                        || !name.equals(source)
                                && !name.equals("screen.mp4")
                                && !name.equals("remux.mp4")
                                && !extra)
                    throw new IOException("Unrecognized recording payload ownership");
            }
            String legacy = data.optString("legacy");
            if (!legacy.isEmpty()) checkLegacy(new File(legacy));
            if (data.optInt("extras") < 0 || data.optInt("extras") > 64)
                throw new IOException("Invalid legacy recording group");
            for (int i = 0; i < data.optInt("extras"); i++)
                checkLegacy(new File(data.optString("extra" + i)));
            if ("ACKNOWLEDGED".equals(data.optString("state"))
                    && (data.optString("savedName").isEmpty()
                            || (data.optString("uri").isEmpty()
                                    && data.optString("file").isEmpty())))
                throw new IOException("Incomplete recording acknowledgement");
            return data;
        } catch (JSONException failure) {
            throw new IOException("Unreadable recording journal", failure);
        }
    }

    private void write(File directory, JSONObject data) throws IOException {
        File base = new File(directory, "record.json");
        for (String suffix : new String[] {"", ".bak", ".new"})
            checkDirect(directory, new File(directory, "record.json" + suffix));
        AtomicFile atomic = new AtomicFile(base);
        FileOutputStream output = atomic.startWrite();
        try {
            output.write(data.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
            atomic.finishWrite(output);
        } catch (IOException | RuntimeException failure) {
            atomic.failWrite(output);
            throw failure;
        }
        if (!base.isFile()
                || new File(base + ".new").exists()
                || new File(base + ".bak").exists()
                || !same(data, read(directory))) {
            throw new IOException("Recording journal commit did not retain its state");
        }
    }

    private static boolean same(JSONObject a, JSONObject b) {
        if (a.length() != b.length()) return false;
        for (Iterator<String> keys = a.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!String.valueOf(a.opt(key)).equals(String.valueOf(b.opt(key)))) return false;
        }
        return true;
    }

    static String hash(File file) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (FileInputStream in = new FileInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) != -1) hash.update(buffer, 0, read);
            }
            StringBuilder text = new StringBuilder();
            for (byte b : hash.digest())
                text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    final class Record implements AutoCloseable {
        final File directory;
        final String id;
        private JSONObject data;
        private final RandomAccessFile access;
        private final FileLock lease;
        private boolean closed;
        final boolean quarantined;
        private boolean rawDiscarded;

        Record(
                File directory,
                JSONObject data,
                RandomAccessFile access,
                FileLock lease,
                boolean quarantined) {
            this.directory = directory;
            this.id = directory.getName();
            this.data = data;
            this.access = access;
            this.lease = lease;
            this.quarantined = quarantined;
        }

        synchronized String get(String key) {
            return data.optString(key);
        }

        synchronized long number(String key) {
            return data.optLong(key);
        }

        synchronized boolean acknowledged() {
            return "ACKNOWLEDGED".equals(get("state"));
        }

        private void requireOwner() throws IOException {
            if (closed || !lease.isValid())
                throw new IOException("Recording ownership lease is closed");
        }

        synchronized void update(Object... pairs) throws IOException {
            requireOwner();
            if (quarantined)
                throw new IOException("Damaged recording metadata cannot authorize an update");
            try {
                JSONObject next = new JSONObject(data.toString());
                for (int i = 0; i < pairs.length; i += 2)
                    next.put(
                            (String) pairs[i],
                            pairs[i + 1] == null ? JSONObject.NULL : pairs[i + 1]);
                locked(
                        () -> {
                            write(directory, next);
                            return null;
                        });
                data = next;
            } catch (JSONException failure) {
                throw new IOException(failure);
            }
        }

        synchronized File file(String name) throws IOException {
            requireOwner();
            if (!name.matches(
                    "source\\.(flv|ts|scr|mp4)|screen\\.mp4|remux\\.mp4|legacy-[0-9]+\\.mp4"))
                throw new IOException("Recording file ownership mismatch");
            File file = new File(directory, name);
            checkDirect(directory, file);
            return file.getCanonicalFile();
        }

        synchronized File source() throws IOException {
            requireOwner();
            if (quarantined)
                throw new IOException("Damaged recording metadata has no trusted source path");
            File source = file(get("source"));
            if (source.isFile()) return source;
            String legacy = get("legacy");
            if (legacy.isEmpty()) return source;
            File original = new File(legacy);
            checkLegacy(original);
            return original;
        }

        synchronized void moveLegacy() throws IOException {
            requireOwner();
            String original = get("legacy");
            if (original.isEmpty()) return;
            File legacy = new File(original), destination = file(get("source"));
            if (!destination.exists() && legacy.isFile()) {
                try (FileOutputStream sync = new FileOutputStream(legacy, true)) {
                    sync.getFD().sync();
                }
                if (!legacy.renameTo(destination))
                    throw new IOException("Could not retain the recording outside cache");
            }
            if (!destination.isFile())
                throw new IOException("Retained recording source is missing");
            for (int i = 0; i < number("extras"); i++) {
                File originalExtra = new File(get("extra" + i));
                File ownedExtra = file("legacy-" + i + ".mp4");
                if (!ownedExtra.exists()
                        && originalExtra.isFile()
                        && !originalExtra.renameTo(ownedExtra))
                    throw new IOException("Could not retain a legacy rewrite");
            }
            update("state", "READY", "legacy", "");
        }

        synchronized void finalized(File file, String audio) throws IOException {
            requireOwner();
            if (!file.isFile()
                    || file.length() == 0
                    || !file.getCanonicalFile().equals(file(file.getName())))
                throw new IOException("Finalized recording is missing or unowned");
            try (FileOutputStream sync = new FileOutputStream(file, true)) {
                sync.getFD().sync();
            }
            update(
                    "final",
                    file.getName(),
                    "bytes",
                    file.length(),
                    "sha256",
                    hash(file),
                    "screenAudio",
                    audio,
                    "state",
                    "READY_MP4");
        }

        synchronized File finalized() throws IOException {
            requireOwner();
            String name = get("final");
            if (name.isEmpty()) return null;
            File file = file(name);
            if (!file.isFile()
                    || file.length() != number("bytes")
                    || !hash(file).equals(get("sha256")))
                throw new IOException("Retained finalized recording changed");
            return file;
        }

        void intent(String key, String collection, String temporaryName, String folder)
                throws IOException {
            update(
                    "intentKey",
                    key,
                    "collection",
                    collection,
                    "temporaryName",
                    temporaryName,
                    "folder",
                    folder == null ? "" : folder,
                    "uri",
                    "",
                    "file",
                    "",
                    "state",
                    "INSERT_INTENT");
        }

        void located(String uri) throws IOException {
            update("uri", uri, "state", "DESTINATION_LOCATED");
        }

        void legacyIntent(File file) throws IOException {
            update("file", file.getCanonicalPath(), "state", "FILE_INTENT");
        }

        void legacyLocated(File file) throws IOException {
            update("file", file.getCanonicalPath(), "state", "DESTINATION_LOCATED");
        }

        void acknowledge(String name, String uri, File file) throws IOException {
            update(
                    "savedName",
                    name,
                    "uri",
                    uri == null ? "" : uri,
                    "file",
                    file == null ? "" : file.getCanonicalPath(),
                    "state",
                    "ACKNOWLEDGED");
        }

        void clearAttempt() throws IOException {
            update(
                    "intentKey",
                    "",
                    "temporaryName",
                    "",
                    "uri",
                    "",
                    "file",
                    "",
                    "state",
                    "READY_MP4");
        }

        synchronized File extra(int index) throws IOException {
            requireOwner();
            if (index < 0 || index >= number("extras"))
                throw new IOException("Unknown retained rewrite");
            File owned = file("legacy-" + index + ".mp4");
            if (owned.isFile()) return owned;
            File original = new File(get("extra" + index));
            checkLegacy(original);
            return original;
        }

        private synchronized void discardOwnedBytes() throws IOException {
            requireOwner();
            for (String name : new String[] {get("source"), "screen.mp4", "remux.mp4"})
                if (!name.isEmpty()) {
                    File owned = file(name);
                    if (owned.exists() && !owned.delete())
                        throw new IOException("Could not remove owned recording file");
                }
            for (int i = 0; i < number("extras"); i++) {
                File owned = extra(i);
                if (owned.exists() && !owned.delete())
                    throw new IOException("Could not remove owned legacy rewrite");
            }
            // A failed legacy handoff remains an exact private-cache source, not a directory sweep.
            String legacy = get("legacy");
            if (!legacy.isEmpty()) {
                File original = new File(legacy);
                if (original.exists() && !original.delete())
                    throw new IOException("Could not remove owned legacy recording");
            }
        }

        synchronized List<File> rawFiles() throws IOException {
            requireOwner();
            List<File> result = new ArrayList<>();
            File[] files = directory.listFiles();
            if (files == null) throw new IOException("Cannot inspect raw recording files");
            for (File file : files) {
                if (metadata(file.getName()) || file.getName().equals("owner.lock")) continue;
                checkDirect(directory, file);
                if (!file.isFile()) throw new IOException("Unexpected nested recording storage");
                if (file.length() > 0) result.add(file);
            }
            Collections.sort(result, (a, b) -> a.getName().compareTo(b.getName()));
            return result;
        }

        synchronized void discardQuarantined() throws IOException {
            requireOwner();
            if (!quarantined) throw new IOException("Recording metadata is still valid");
            locked(
                    () -> {
                        File[] files = directory.listFiles();
                        if (files == null)
                            throw new IOException("Cannot inspect damaged recording ownership");
                        // Validate every direct entry before removing any payload. Never traverse
                        // children.
                        for (File file : files)
                            if (!file.getName().equals("owner.lock")) {
                                if (!file.getParentFile()
                                        .getCanonicalFile()
                                        .equals(directory.getCanonicalFile()))
                                    throw new IOException("Recording child escaped its directory");
                                if (file.getCanonicalFile()
                                                .equals(
                                                        new File(
                                                                directory.getCanonicalFile(),
                                                                file.getName()))
                                        && file.isDirectory())
                                    throw new IOException("Unexpected nested recording storage");
                            }
                        for (File file : files)
                            if (!metadata(file.getName())
                                    && !file.getName().equals("owner.lock")
                                    && !file.delete())
                                throw new IOException("Could not delete owned raw recording file");
                        rawDiscarded = true;
                        return null;
                    });
        }

        synchronized long otherOwnedBytes() throws IOException {
            requireOwner();
            String ownSource = get("source");
            return locked(
                    () -> {
                        long total = 0;
                        for (File ownedDirectory : directories()) {
                            File[] files = ownedDirectory.listFiles();
                            if (files == null)
                                throw new IOException(
                                        "Cannot inspect retained recording footprint");
                            for (File file : files) {
                                if (metadata(file.getName())
                                        || file.getName().equals("owner.lock")
                                        || ownedDirectory.equals(directory)
                                                && (file.getName().equals(ownSource)
                                                        || file.getName().equals("remux.mp4")))
                                    continue;
                                checkDirect(ownedDirectory, file);
                                if (!file.isFile())
                                    throw new IOException("Unexpected nested recording storage");
                                try {
                                    total = Math.addExact(total, file.length());
                                } catch (ArithmeticException overflow) {
                                    throw new IOException(
                                            "Retained recording footprint overflow", overflow);
                                }
                            }
                        }
                        if (legacyRoot != null) {
                            File[] files = legacyRoot.listFiles();
                            if (files == null)
                                throw new IOException("Cannot inspect legacy recording footprint");
                            for (File file : files)
                                if (legacyCandidate(file)) {
                                    checkDirect(legacyRoot, file);
                                    try {
                                        total = Math.addExact(total, file.length());
                                    } catch (ArithmeticException overflow) {
                                        throw new IOException(
                                                "Legacy recording footprint overflow", overflow);
                                    }
                                }
                        }
                        return total;
                    });
        }

        /** No receipt or unrecognized payload may be inferred empty from the main source alone. */
        private synchronized boolean emptyCaptureSetup() throws IOException {
            if (quarantined || !"CAPTURING".equals(get("state"))) return false;
            if (!get("captureComplete").isEmpty() && !"false".equals(get("captureComplete")))
                return false;
            for (Iterator<String> keys = data.keys(); keys.hasNext(); )
                if (!keys.next()
                        .matches(
                                "v|id|format|creator|started|source|state|screenAudio|capture|captureComplete|captureConfig|captureWidth|captureHeight"))
                    return false;
            checkDirect(root, directory);
            try {
                if (!read(directory).toString().equals(data.toString())) return false;
            } catch (IOException | RuntimeException unreadable) {
                return false;
            }
            File expected = file(get("source"));
            File[] files = directory.listFiles();
            if (files == null) throw new IOException("Cannot inspect empty capture setup");
            for (File file : files) {
                checkDirect(directory, file);
                if (!file.isFile()) return false;
                if (metadata(file.getName()) || file.getName().equals("owner.lock")) continue;
                if (!file.equals(expected) || file.length() != 0) return false;
            }
            return true;
        }

        /** Record monitor always precedes the global/file lock, as it does in update(). */
        @Override
        public synchronized void close() throws IOException {
            if (closed) return;
            requireOwner();
            try {
                if (emptyCaptureSetup()) update("state", "DISCARDED");
                locked(
                        () -> {
                            IOException failure = null;
                            try {
                                if (!quarantined
                                        && (acknowledged() || "DISCARDED".equals(get("state"))))
                                    discardOwnedBytes();
                            } catch (IOException problem) {
                                failure = problem;
                            }
                            closed = true;
                            try {
                                lease.release();
                            } finally {
                                access.close();
                            }
                            if ((rawDiscarded
                                            || !quarantined
                                                    && (acknowledged()
                                                            || "DISCARDED".equals(get("state"))))
                                    && failure == null) {
                                File[] remaining = directory.listFiles();
                                if (remaining == null)
                                    throw new IOException("Cannot inspect recording cleanup");
                                for (File file : remaining)
                                    if (!file.getName().startsWith("record.json")
                                            && !file.getName().equals("owner.lock"))
                                        throw new IOException("Recording cleanup left owned bytes");
                                new AtomicFile(new File(directory, "record.json")).delete();
                                File owner = new File(directory, "owner.lock");
                                if (owner.exists() && !owner.delete())
                                    throw new IOException("Could not remove recording lease file");
                                if (!directory.delete())
                                    throw new IOException(
                                            "Could not remove recording ownership directory");
                            }
                            if (failure != null) throw failure;
                            return null;
                        });
            } finally {
                // Failure to acquire/commit the global journal must not strand the live lease.
                if (!closed) {
                    closed = true;
                    try {
                        lease.release();
                    } finally {
                        access.close();
                    }
                }
            }
        }
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import app.morphe.extension.shared.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Links a recording's durable owner to one exact publication attempt. */
final class RecordingPublication implements MediaFileWriter.Publication {
    final Context app;
    final RecordingRetention.Record record;
    private final CaptureSpace.Free free;

    RecordingPublication(Context app, RecordingRetention.Record record) {
        this(app, record, File::getUsableSpace);
    }

    RecordingPublication(Context app, RecordingRetention.Record record, CaptureSpace.Free free) {
        this.app = app;
        this.record = record;
        this.free = free;
    }

    @Override
    public void intent(String key, String collection, String name, String folder)
            throws IOException {
        record.intent(key, collection, name, folder);
    }

    @Override
    public void located(Uri uri) throws IOException {
        record.located(uri.toString());
    }

    @Override
    public String legacyName(String name) {
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot))
                + "_"
                + record.id
                + (dot < 0 ? "" : name.substring(dot));
    }

    @Override
    public void legacyIntent(File file) throws IOException {
        record.legacyIntent(file);
    }

    @Override
    public void legacyLocated(File file) throws IOException {
        record.legacyLocated(file);
    }

    @Override
    public void beforeCopy(long copied, int count, File destination) throws IOException {
        CaptureJournal.beforePublication(record, copied, count, destination, free);
    }

    @Override
    public void verify(MediaFileWriter.Saved saved) throws IOException {
        if (saved.uri != null) {
            if (Build.VERSION.SDK_INT < 29 || !saved.uri.toString().equals(record.get("uri")))
                throw new IOException("Recording destination identity changed");
            try (Cursor c = destination(saved.uri)) {
                if (c == null || !c.moveToFirst())
                    throw new IOException("The committed recording destination cannot be read");
                MediaFileWriter.Saved verified = verifyCommittedRow(saved.uri, c);
                if (!saved.name.equals(verified.name))
                    throw new IOException("Recording destination name changed");
            } catch (RuntimeException failure) {
                throw new IOException("Recording publication could not be verified", failure);
            }
        } else {
            if (saved.file == null || !saved.file.getCanonicalPath().equals(record.get("file")))
                throw new IOException("Recording destination identity changed");
            verifyLegacy(saved.file);
        }
    }

    @Override
    public void acknowledged(MediaFileWriter.Saved saved) throws IOException {
        record.acknowledge(saved.name, saved.uri == null ? null : saved.uri.toString(), saved.file);
    }

    MediaFileWriter.Saved alreadyPublished() throws IOException {
        if (record.acknowledged()) return savedFromAck();
        String uriText = record.get("uri");
        if ((!uriText.isEmpty() || !record.get("intentKey").isEmpty())
                && Build.VERSION.SDK_INT < 29)
            throw new IOException("MediaStore recording requires Android 10");
        if (uriText.isEmpty() && !record.get("intentKey").isEmpty()) {
            Uri collection = mediaUri(record.get("collection"));
            try (Cursor c =
                    MediaCache.queryIncludingPending(
                            app.getContentResolver(),
                            collection,
                            new String[] {
                                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME
                            },
                            MediaStore.MediaColumns.DISPLAY_NAME
                                    + " = ? AND "
                                    + MediaStore.MediaColumns.RELATIVE_PATH
                                    + " = ?",
                            new String[] {
                                record.get("temporaryName"), withSlash(record.get("folder"))
                            })) {
                if (c == null) throw new IOException("Publication intent cannot be read");
                if (c.moveToFirst()) {
                    Uri found = ContentUris.withAppendedId(collection, c.getLong(0));
                    if (!record.get("temporaryName").equals(c.getString(1)) || c.moveToNext())
                        throw new IOException("Publication intent is ambiguous");
                    record.located(found.toString());
                    uriText = found.toString();
                } else {
                    record.clearAttempt();
                }
            } catch (RuntimeException failure) {
                throw new IOException("Publication intent is unavailable", failure);
            }
        }
        if (!uriText.isEmpty()) return reconcileRow(mediaUri(uriText));
        String path = record.get("file");
        if (!path.isEmpty()) return reconcileLegacy(new File(path));
        return null;
    }

    @android.annotation.TargetApi(29)
    private MediaFileWriter.Saved reconcileRow(Uri uri) throws IOException {
        try (Cursor c = destination(uri)) {
            if (c == null) throw new IOException("The exact recording destination cannot be read");
            if (!c.moveToFirst()) {
                record.clearAttempt();
                return null;
            }
            if (!app.getPackageName().equals(c.getString(4)))
                throw new IOException("Recording destination owner changed");
            if (c.getInt(0) != 0) {
                if (!record.get("temporaryName").equals(c.getString(1)))
                    throw new IOException("Pending recording identity changed");
                if (app.getContentResolver().delete(uri, null, null) != 1)
                    throw new IOException("Incomplete recording destination could not be removed");
                MediaCache.clearPending(app, uri);
                record.clearAttempt();
                return null;
            }
            MediaFileWriter.Saved saved = verifyCommittedRow(uri, c);
            if (acknowledgeVerified(saved)) {
                try {
                    MediaCache.clearPending(app, uri);
                } catch (IOException failure) {
                    Logger.printException(
                            () -> "Recording publication journal cleanup is pending", failure);
                }
            }
            return saved;
        } catch (RuntimeException failure) {
            throw new IOException("The exact recording destination is unavailable", failure);
        }
    }

    @android.annotation.TargetApi(29)
    private Cursor destination(Uri uri) {
        return MediaCache.queryIncludingPending(
                app.getContentResolver(),
                uri,
                new String[] {
                    MediaStore.MediaColumns.IS_PENDING,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.MIME_TYPE,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME
                },
                null,
                null);
    }

    private MediaFileWriter.Saved verifyCommittedRow(Uri uri, Cursor c) throws IOException {
        if (!app.getPackageName().equals(c.getString(4)) || c.getInt(0) != 0)
            throw new IOException("Recording destination is not committed by this app");
        if (!"video/mp4".equals(c.getString(3)) || c.getLong(2) != record.number("bytes"))
            throw new IOException("Published recording size/type changed");
        try (InputStream input = app.getContentResolver().openInputStream(uri)) {
            if (input == null
                    || !digest(input, record.number("bytes")).equals(record.get("sha256")))
                throw new IOException("Published recording bytes changed");
        }
        return new MediaFileWriter.Saved(c.getString(1), uri);
    }

    private void verifyLegacy(File file) throws IOException {
        checkLegacy(file);
        if (!file.isFile()
                || file.length() != record.number("bytes")
                || !RecordingRetention.hash(file).equals(record.get("sha256")))
            throw new IOException("Legacy recording publication did not complete");
    }

    private MediaFileWriter.Saved reconcileLegacy(File file) throws IOException {
        checkLegacy(file);
        if (!file.exists()) {
            record.clearAttempt();
            return null;
        }
        if (file.length() == record.number("bytes")
                && RecordingRetention.hash(file).equals(record.get("sha256"))) {
            MediaFileWriter.Saved saved = new MediaFileWriter.Saved(file.getName(), null, file);
            acknowledgeVerified(saved);
            return saved;
        }
        if (!"DESTINATION_LOCATED".equals(record.get("state")))
            throw new IOException("Legacy destination claim was not confirmed");
        File source = record.finalized();
        if (source == null) throw new IOException("Finalized recording source is missing");
        MediaBudget.checkDiskSpace(
                file.getParentFile(),
                source.length(),
                null,
                Math.max(source.length(), MediaBudget.MAX_LIVE_RECORDING_BYTES));
        try (InputStream input = new FileInputStream(source);
                FileOutputStream output = new FileOutputStream(file)) {
            MediaFileWriter.copy(
                    input,
                    output,
                    record.number("bytes"),
                    null,
                    file.getParentFile(),
                    null,
                    (copied, count) -> beforeCopy(copied, count, file.getParentFile()));
            output.flush();
            output.getFD().sync();
        }
        verifyLegacy(file);
        MediaScannerConnection.scanFile(
                app, new String[] {file.getAbsolutePath()}, new String[] {"video/mp4"}, null);
        MediaFileWriter.Saved saved = new MediaFileWriter.Saved(file.getName(), null, file);
        acknowledged(saved);
        return saved;
    }

    /** Called only after byte/ownership verification; journal failure cannot undo that save. */
    private boolean acknowledgeVerified(MediaFileWriter.Saved saved) {
        try {
            acknowledged(saved);
            return true;
        } catch (IOException failure) {
            Logger.printException(() -> "Verified recording acknowledgement is pending", failure);
            return false;
        }
    }

    private void checkLegacy(File file) throws IOException {
        if (!file.getName().contains("_" + record.id)
                || !file.getName().endsWith(".mp4")
                || !file.getCanonicalPath().equals(file.getAbsolutePath())
                || !file.getCanonicalPath()
                        .startsWith(
                                Environment.getExternalStorageDirectory().getCanonicalPath()
                                        + File.separator)) {
            throw new IOException("Legacy recording destination ownership changed");
        }
    }

    private MediaFileWriter.Saved savedFromAck() throws IOException {
        String uri = record.get("uri"), file = record.get("file");
        return new MediaFileWriter.Saved(
                record.get("savedName"),
                uri.isEmpty() ? null : mediaUri(uri),
                file.isEmpty() ? null : new File(file));
    }

    void discard() throws IOException {
        if (record.quarantined) {
            record.discardQuarantined();
            return;
        }
        // Never delete a destination that already committed. Unavailable ownership stays journaled.
        String uri = record.get("uri");
        if (!uri.isEmpty()) {
            if (Build.VERSION.SDK_INT < 29)
                throw new IOException("MediaStore recording requires Android 10");
            Uri exact = mediaUri(uri);
            try (Cursor c =
                    MediaCache.queryIncludingPending(
                            app.getContentResolver(),
                            exact,
                            new String[] {
                                MediaStore.MediaColumns.IS_PENDING,
                                MediaStore.MediaColumns.DISPLAY_NAME,
                                MediaStore.MediaColumns.OWNER_PACKAGE_NAME
                            },
                            null,
                            null)) {
                if (c == null)
                    throw new IOException("Recording destination cannot be checked before discard");
                if (c.moveToFirst()) {
                    if (!app.getPackageName().equals(c.getString(2)))
                        throw new IOException("Recording destination owner changed");
                    if (c.getInt(0) != 0) {
                        if (!record.get("temporaryName").equals(c.getString(1))
                                || app.getContentResolver().delete(exact, null, null) != 1)
                            throw new IOException("Pending recording cleanup failed");
                    }
                }
                MediaCache.clearPending(app, exact);
            } catch (RuntimeException failure) {
                throw new IOException(
                        "Recording discard could not establish destination state", failure);
            }
        } else if (!record.get("intentKey").isEmpty()) {
            // First resolve an insert whose exact URI was not written. This either records it or
            // clears a conclusively empty intent, and cannot create a new destination.
            alreadyPublished();
            if (!record.get("uri").isEmpty()) {
                discard();
                return;
            }
        }
        String path = record.get("file");
        if (!path.isEmpty() && !record.acknowledged()) {
            File file = new File(path);
            checkLegacy(file);
            if (file.isFile() && file.length() != record.number("bytes") && !file.delete())
                throw new IOException("Owned partial file could not be removed");
        }
        record.update("state", "DISCARDED");
    }

    private static String withSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }

    private static Uri mediaUri(String text) throws IOException {
        Uri uri = Uri.parse(text);
        if (!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority()))
            throw new IOException("Recording destination is not MediaStore");
        return uri;
    }

    private static String digest(InputStream input, long expectedBytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            long count = 0;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > expectedBytes)
                    throw new IOException("Published recording length changed");
                digest.update(buffer, 0, read);
            }
            if (count != expectedBytes) throw new IOException("Published recording length changed");
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest())
                result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }
}

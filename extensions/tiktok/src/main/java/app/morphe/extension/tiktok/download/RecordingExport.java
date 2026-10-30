/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Explicit user export preserves legacy bytes without claiming their old publication state. */
public final class RecordingExport {
    private RecordingExport() {}

    static void open(Activity activity, String id) {
        if (activity.getFragmentManager().findFragmentByTag("hushfeed-recording-export") != null)
            return;
        Request request = new Request();
        Bundle arguments = new Bundle();
        arguments.putString("id", id);
        request.setArguments(arguments);
        activity.getFragmentManager()
                .beginTransaction()
                .add(request, "hushfeed-recording-export")
                .commitAllowingStateLoss();
    }

    public static final class Request extends Fragment {
        public Request() {}

        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            if (state != null) return;
            try {
                startActivityForResult(
                        new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("application/zip")
                                .putExtra(
                                        Intent.EXTRA_TITLE,
                                        "recording-" + getArguments().getString("id") + ".zip"),
                        0x4c52);
            } catch (RuntimeException failure) {
                leave();
                Utils.showToastLong(L10n.t("The export wasn't saved. Try again."));
            }
        }

        @Override
        public void onActivityResult(int request, int result, Intent data) {
            if (request != 0x4c52) return;
            Activity activity = getActivity();
            String id = getArguments().getString("id");
            leave();
            Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            if (uri == null || activity == null) return;
            Context app = activity.getApplicationContext();
            Utils.runOnOwnThread(
                    "hushfeed-recording-export",
                    () -> {
                        try {
                            exportTo(app, id, uri);
                            Utils.showToastLong(L10n.t("Saved"));
                        } catch (IOException | RuntimeException failure) {
                            Logger.printException(
                                    () -> "Retained recording export failed", failure);
                            Utils.showToastLong(failureMessage());
                        }
                    });
        }

        private void leave() {
            if (getFragmentManager() != null)
                getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }

    static List<File> files(RecordingRetention.Record record) throws IOException {
        if (record.quarantined) return record.rawFiles();
        List<File> files = new ArrayList<>();
        File source = record.source();
        if (source.isFile() && source.length() > 0) files.add(source);
        for (String name : new String[] {"screen.mp4", "remux.mp4"}) {
            File file = record.file(name);
            if (file.isFile() && file.length() > 0 && !files.contains(file)) files.add(file);
        }
        for (int i = 0; i < record.number("extras"); i++) {
            File file = record.extra(i);
            if (file.isFile()) files.add(file);
        }
        return files;
    }

    static void exportFiles(RecordingRetention.Record record, OutputStream output)
            throws IOException {
        List<File> files = files(record);
        if (files.isEmpty()) throw new IOException("No retained recording bytes");
        try (ZipOutputStream zip =
                new ZipOutputStream(
                        new FilterOutputStream(output) {
                            @Override
                            public void write(byte[] bytes, int offset, int length)
                                    throws IOException {
                                out.write(bytes, offset, length);
                            }

                            @Override
                            public void close() throws IOException {
                                flush();
                            }
                        })) {
            int index = 0;
            java.util.Set<String> reserved = new java.util.HashSet<>(),
                    used = new java.util.HashSet<>();
            for (File file : files)
                if (file.getName().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
                    reserved.add(file.getName().toLowerCase(java.util.Locale.ROOT));
            for (File file : files) {
                String name = file.getName();
                if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                        || !used.add(name.toLowerCase(java.util.Locale.ROOT))) {
                    do {
                        name = "recording-" + (++index) + ".bin";
                    } while (reserved.contains(name) || !used.add(name));
                }
                zip.putNextEntry(new ZipEntry(name));
                try (FileInputStream input = new FileInputStream(file)) {
                    MediaFileWriter.copy(input, zip);
                }
                zip.closeEntry();
            }
        }
    }

    static String failureMessage() {
        return L10n.t("Export may be incomplete. The recordings are kept.");
    }

    static void exportTo(Context app, String id, Uri destination) throws IOException {
        RecordingRetention.Record record = RecordingRetention.in(app).open(id);
        if (record == null) throw new IOException("Recording is still active");
        try {
            List<File> files = files(record);
            if (files.isEmpty()) throw new IOException("No retained recording bytes");
            try (OutputStream output =
                    app.getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("Export document cannot be opened");
                exportFiles(record, output);
            }
            // This is a user-requested copy, not a publication ACK. Originals remain until Delete.
        } finally {
            record.close();
        }
    }
}

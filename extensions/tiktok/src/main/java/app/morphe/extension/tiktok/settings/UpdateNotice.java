/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * Tells the reader, in TikTok, when a newer Hushfeed release is out (#377).
 *
 * <p>A patched TikTok gets no update prompt of its own, and Morphe Manager only looks at its
 * sources while it's open, so a release reached nobody who didn't go looking. With
 * {@link Settings#CHECK_FOR_UPDATES} on, an opening of TikTok (see {@link StartupMessage}) asks
 * GitHub for the latest release at most once a day, on a worker. A newer one shows a card with
 * the start of its notes, as the patches' own update card in YouTube does, and with
 * Update, which opens Morphe Manager (or the release page when Manager isn't installed), and
 * Dismiss, which keeps that release's card away for good. Back or a tap outside only closes it,
 * so the next day's check shows it again.
 *
 * <p>It asks a server the reader didn't pick without being asked to, so the switch starts off,
 * and Pause turns it off like every other switch. The request is one GET over HTTPS to api.github.com, with no
 * redirect followed, no cookie, short timeouts, a cap on the answer and a User-Agent naming
 * Hushfeed and its version. Only the release's tag and notes are read from the answer, and the
 * notes are shown as plain text. The time of each try
 * is kept before the request goes out, so a failed one waits its day too.
 */
public final class UpdateNotice {
    static final String LATEST_RELEASE = "https://api.github.com/repos/SysAdminDoc/HushFeed/releases/latest";
    /** The release page for a tag. Built from the tag, never taken from the answer. */
    static final String RELEASE_PAGE = "https://github.com/SysAdminDoc/HushFeed/releases/tag/";
    static final String MANAGER = "app.morphe.manager";
    static final long INTERVAL_MS = TimeUnit.DAYS.toMillis(1);
    static final int TIMEOUT_MS = 10_000;
    /** The latest release with its notes and assets is about 60 KB. Nothing past this is read. */
    static final int MAX_BODY_BYTES = 512 * 1024;
    /** How many of the release notes' bullets the card lists, each cut to its first sentence. */
    static final int NOTES_SHOWN = 5;
    static final int NOTE_LENGTH = 140;
    private static final Pattern TAG = Pattern.compile("v?[0-9]+(?:\\.[0-9]+)+(?:-[0-9A-Za-z.]+)?");
    /** A Markdown link, [text](address), which the card shows as its text. */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");

    /** The start of a release's notes as plain lines: section names and bullets, in order. */
    static final class Notes {
        final List<String> lines = new ArrayList<>();
        /** The bullets left off the card. */
        int more;
    }

    /** Asks GitHub and answers the body. Tests put a fake in. */
    interface Fetcher {
        String fetch(String userAgent) throws IOException;
    }

    /** Wall time in milliseconds. Its own type, since java.util.function needs Android 7. */
    interface Clock {
        long now();
    }

    static volatile Fetcher fetcher = UpdateNotice::fetchLatest;
    static volatile Clock clock = System::currentTimeMillis;

    private UpdateNotice() {
    }

    /**
     * Called when TikTok opens, on the main thread. Returns whether a check was started. A switch
     * that's off, Pause, or a check in the last day starts none.
     */
    static boolean onOpening(Activity activity) {
        // The patcher writes the bundle's version into this method; an unpatched build says "".
        return onOpening(activity, Utils.getPatchesReleaseVersion());
    }

    static boolean onOpening(Activity activity, @Nullable String current) {
        try {
            if (!Settings.CHECK_FOR_UPDATES.get()) return false;
            long now = clock.now();
            long last = Settings.UPDATE_CHECKED_AT.get();
            // A clock set back past the last try doesn't hold the check off for days.
            if (last <= now && now - last < INTERVAL_MS) return false;
            if (current == null || current.trim().isEmpty()) return false;
            Settings.UPDATE_CHECKED_AT.save(now);
            WeakReference<Activity> screen = new WeakReference<>(activity);
            return Utils.runOnBackgroundThread(() -> check(screen, current.trim()));
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not start the update check", failure);
            return false;
        }
    }

    /** On the worker: asks, and posts the card when the answer is a newer release not dismissed. */
    static void check(WeakReference<Activity> screen, String current) {
        String newer;
        Notes notes;
        try {
            String answer = fetcher.fetch("Hushfeed/" + current);
            newer = newerRelease(answer, current, Settings.UPDATE_DISMISSED.get());
            if (newer == null) return;
            notes = notesOf(notesText(answer));
        } catch (IOException | RuntimeException failure) {
            Logger.printInfo(() -> "Update check failed: " + failure.getClass().getSimpleName());
            return;
        }
        Utils.runOnMainThread(() -> {
            Activity activity = screen.get();
            if (activity != null) show(activity, newer, current, notes);
        });
    }

    /** The release's notes as written on GitHub, or "" when the answer has none. */
    static String notesText(@Nullable String answer) {
        if (answer == null) return "";
        try {
            return new JSONObject(answer).optString("body", "");
        } catch (JSONException failure) {
            return "";
        }
    }

    /**
     * The first {@link #NOTES_SHOWN} bullets of the notes, each cut to its first sentence, under
     * the section names they sit in ("New in this release", "Bug Fixes"). The paragraphs before
     * them are left out. A section name is a "#" heading, or a short line with no closing period
     * that a bullet follows.
     */
    static Notes notesOf(@Nullable String markdown) {
        Notes notes = new Notes();
        if (markdown == null) return notes;
        String[] lines = markdown.replace("\r", "").split("\n");
        String section = null;
        int shown = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("- ") || line.startsWith("* ")) {
                if (shown == NOTES_SHOWN) {
                    notes.more++;
                    continue;
                }
                if (section != null) {
                    if (!notes.lines.isEmpty()) notes.lines.add("");
                    notes.lines.add(section);
                    section = null;
                }
                notes.lines.add("• " + firstSentence(plain(line.substring(2))));
                shown++;
            } else if (line.startsWith("#") || isSectionName(line, nextLine(lines, i))) {
                section = plain(line.replaceFirst("^#+", ""));
                if (section.isEmpty()) section = null;
            } else {
                section = null;
            }
        }
        return notes;
    }

    private static String nextLine(String[] lines, int index) {
        for (int i = index + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.isEmpty()) return line;
        }
        return "";
    }

    private static boolean isSectionName(String line, String next) {
        return line.length() <= 40 && !line.endsWith(".")
                && (next.startsWith("- ") || next.startsWith("* "));
    }

    /** Markdown down to the words: links to their text, code and bold marks dropped. */
    static String plain(String text) {
        return LINK.matcher(text).replaceAll("$1")
                .replace("`", "").replace("**", "").replace("__", "")
                .replaceAll("\\s+", " ").trim();
    }

    /** Up to the first full stop that ends a sentence, and never longer than {@link #NOTE_LENGTH}. */
    static String firstSentence(String text) {
        for (int i = 0; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && text.charAt(i + 1) == ' ') {
                text = text.substring(0, i + 1);
                break;
            }
        }
        if (text.length() <= NOTE_LENGTH) return text;
        int cut = text.lastIndexOf(' ', NOTE_LENGTH - 1);
        return text.substring(0, cut > 0 ? cut : NOTE_LENGTH - 1) + "…";
    }

    /**
     * The latest release's tag when it's newer than {@code current} and isn't the one dismissed,
     * or null. An answer that isn't a published release with a version tag is null too.
     */
    @Nullable
    static String newerRelease(@Nullable String body, String current, String dismissed) {
        String tag = tagOf(body);
        if (tag == null || tag.equals(dismissed)) return null;
        return compareVersions(tag, current) > 0 ? tag : null;
    }

    @Nullable
    static String tagOf(@Nullable String body) {
        if (body == null) return null;
        try {
            JSONObject release = new JSONObject(body);
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null;
            String tag = release.optString("tag_name", "").trim();
            return TAG.matcher(tag).matches() ? tag : null;
        } catch (JSONException failure) {
            return null;
        }
    }

    /**
     * Compares release numbers part by part as integers, so 0.10.0 is newer than 0.9.0. A leading
     * "v" is ignored and a missing part counts as 0. A release sorts after its own pre-releases
     * ("0.82.0-dev.1" before "0.82.0"), and two pre-releases compare as text.
     */
    static int compareVersions(String a, String b) {
        String[] left = a.trim().replaceFirst("^v", "").split("-", 2);
        String[] right = b.trim().replaceFirst("^v", "").split("-", 2);
        String[] x = left[0].split("\\.");
        String[] y = right[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int diff = Long.compare(part(x, i), part(y, i));
            if (diff != 0) return diff;
        }
        if (left.length != right.length) return left.length > right.length ? -1 : 1;
        return left.length == 1 ? 0 : Integer.signum(left[1].compareTo(right[1]));
    }

    private static long part(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Long.parseLong(parts[index]);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /** The card's text: the version line, then the start of the release notes. */
    static String message(Activity activity, String current, Notes notes) {
        StringBuilder text = new StringBuilder(L10n.f(activity,
                "You have %1$s. Update the patches in Morphe Manager, then patch TikTok again.",
                current));
        if (notes.lines.isEmpty()) return text.toString();
        text.append("\n\n").append(TextUtils.join("\n", notes.lines));
        if (notes.more > 0) {
            text.append("\n\n").append(L10n.f(activity, "%1$s more in the release notes", notes.more));
        }
        return text.toString();
    }

    static void show(Activity activity, String tag, String current, Notes notes) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            SettingsUi.syncDarkMode(activity);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(L10n.f(activity, "Hushfeed %1$s is out", tag))
                    .setMessage(message(activity, current, notes))
                    .setPositiveButton(L10n.t(activity, "Update"), (ignored, which) -> openUpdate(activity, tag))
                    .setNegativeButton(L10n.t(activity, "Dismiss"), (ignored, which) ->
                            Settings.UPDATE_DISMISSED.save(tag))
                    .create();
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            dialog.show();
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not show the update card", failure);
        }
    }

    /**
     * Morphe Manager when it's installed, else the release page in the browser. TikTok sees Manager
     * only because the Settings patch names it in a manifest queries element; without that, Android
     * 11 and later hide an installed Manager too.
     *
     * <p>Manager starts by the component its launch intent names, the way a home screen starts it.
     * A start that names only the package is resolved with MATCH_DEFAULT_ONLY, and Manager's
     * launcher aliases carry no DEFAULT category, so on a phone that start found nothing (#377).
     */
    static void openUpdate(Activity activity, String tag) {
        try {
            Intent manager = activity.getPackageManager().getLaunchIntentForPackage(MANAGER);
            if (manager != null) {
                activity.startActivity(manager.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
        } catch (ActivityNotFoundException | SecurityException notThere) {
            // Manager went away or refused: the release page has the notes and the files.
        }
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE + tag))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException failure) {
            Logger.printException(() -> "Could not open the release page", failure);
        }
    }

    static String fetchLatest(String userAgent) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", userAgent);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("GitHub answered " + code);
            try (InputStream in = connection.getInputStream()) {
                return readCapped(in, MAX_BODY_BYTES);
            }
        } finally {
            connection.disconnect();
        }
    }

    static String readCapped(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read; (read = in.read(buffer)) != -1; ) {
            if (out.size() + read > cap) throw new IOException("The answer ran past " + cap + " bytes");
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static void resetForTests() {
        fetcher = UpdateNotice::fetchLatest;
        clock = System::currentTimeMillis;
    }
}

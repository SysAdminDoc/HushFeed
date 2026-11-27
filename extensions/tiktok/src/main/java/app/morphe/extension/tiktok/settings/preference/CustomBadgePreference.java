/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.profile.CustomBadge;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Two rows under Local verified badge (#549): one picks a picture through the system's file
 * picker and keeps a small copy in TikTok's files, the other deletes it. The picture stands in
 * for the blue check beside your own name, on this phone only.
 *
 * <p>The picker answers the settings page, as the font import's does, and the read runs on a
 * {@link DocumentOperation} worker so a file app that keeps it waiting can't hold the row.
 */
@SuppressWarnings("deprecation")
public final class CustomBadgePreference extends Preference {
    static final int REQUEST_PICK = 7361;
    static final String PICK_KEY = "action_pick_badge_picture";
    static final String REMOVE_KEY = "action_remove_badge_picture";

    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final CopyOnWriteArrayList<WeakReference<CustomBadgePreference>> ROWS =
            new CopyOnWriteArrayList<>();
    /** The picker is open. Main thread only. */
    private static boolean picking;
    /** The read the row waits on. Main thread only. */
    private static DocumentOperation reading;

    /** Keeping the checked picture failed, as opposed to reading the picked file. */
    private static final class NotSaved extends IOException {
        NotSaved(IOException cause) {
            super("Could not keep the badge picture", cause);
        }
    }

    private final boolean removes;

    private CustomBadgePreference(Context context, boolean removes) {
        super(context);
        this.removes = removes;
        setPersistent(false);
        setKey(removes ? REMOVE_KEY : PICK_KEY);
        setTitle(removes ? L10n.t(context, "Remove the badge picture") : L10n.t(context, "Badge picture"));
        ROWS.add(new WeakReference<>(this));
        refreshState();
        setOnPreferenceClickListener(preference -> {
            if (removes) removePicture(getContext());
            else if (offersStop()) stopWaiting(getContext());
            else if (!BUSY.get() && !picking && !heldAfterStop()) TikTokPreferenceFragment.openBadgePicturePicker();
            return true;
        });
    }

    public static CustomBadgePreference pickRow(Context context) {
        return new CustomBadgePreference(context, false);
    }

    public static CustomBadgePreference removeRow(Context context) {
        return new CustomBadgePreference(context, true);
    }

    static void pickFile(TikTokPreferenceFragment fragment) {
        if (BUSY.get() || picking) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .setType("image/*");
        picking = true;
        refreshRows();
        try {
            fragment.startActivityForResult(intent, REQUEST_PICK);
        } catch (RuntimeException failure) {
            picking = false;
            refreshRows();
            Logger.printException(() -> "Could not open the badge picture picker", failure);
            SettingsActionBanner.showNotice(fragment.getActivity(), L10n.t(
                    "This phone has no file picker, so there's no way to choose a file here"));
        }
    }

    static boolean onResult(TikTokPreferenceFragment fragment, int request, int result, Intent data) {
        if (request != REQUEST_PICK) return false;
        picking = false;
        Activity activity = fragment.getActivity();
        if (result != Activity.RESULT_OK || data == null || data.getData() == null
                || activity == null || !BUSY.compareAndSet(false, true)) {
            refreshRows();
            return true;
        }
        Context context = activity.getApplicationContext();
        WeakReference<Activity> window = new WeakReference<>(activity);
        Uri uri = data.getData();
        refreshRows();
        reading = DocumentOperation.start(DocumentOperation.Kind.BADGE_PICTURE, file -> {
            try {
                CustomBadge.Prepared picture = CustomBadge.prepare(file.openForRead(context.getContentResolver(), uri));
                // Stopped while the file app had it: the stop gave the row back and said so.
                if (!file.commit()) return;
                try {
                    CustomBadge.keep(context, picture);
                } catch (IOException failure) {
                    throw new NotSaved(failure);
                }
                Utils.runOnMainThread(() -> {
                    finish();
                    SettingsActionBanner.showNotice(TikTokPreferenceFragment.reportWindow(window, context),
                            Settings.LOCAL_PROFILE_VERIFIED_BADGE.get()
                                    ? L10n.t(context, "Badge picture saved. Open your profile to see it.")
                                    : L10n.t(context, "Badge picture saved. Turn on Local verified badge to see it."));
                });
            } catch (Exception failure) {
                if (file.isStopped()) return;
                if (failure instanceof CustomBadge.Rejected) {
                    Logger.printInfo(() -> "Badge picture refused: " + failure.getMessage());
                } else {
                    Logger.printException(() -> "Could not keep a badge picture", failure);
                }
                Utils.runOnMainThread(() -> {
                    finish();
                    SettingsActionBanner.showNotice(TikTokPreferenceFragment.reportWindow(window, context),
                            failureMessage(context, failure));
                });
            }
        }, CustomBadgePreference::refreshRows);
        if (reading == null) {
            boolean held = DocumentOperation.busy(DocumentOperation.Kind.BADGE_PICTURE);
            finish();
            SettingsActionBanner.showNotice(activity, held
                    ? L10n.t(context, "The file app still has the last file. Try again once it lets go, or restart TikTok.")
                    : L10n.t(context, "Couldn't start the import. Try again in a moment."));
        }
        return true;
    }

    static String failureMessage(Context context, Exception failure) {
        if (failure instanceof NotSaved) return L10n.t(context,
                "Couldn't save the picture in TikTok's storage. Free up some space and try again.");
        if (!(failure instanceof CustomBadge.Rejected)) return L10n.t(context,
                "Couldn't read that file. Choose it again.");
        if (((CustomBadge.Rejected) failure).tooLarge) {
            return L10n.f(context, "That picture is larger than %1$s MB. Choose a smaller one.",
                    NumberFormat.getInstance().format(CustomBadge.MAX_BYTES / (1024 * 1024)));
        }
        return L10n.t(context, "That file isn't a picture this phone can open. Choose a PNG, JPEG or WebP.");
    }

    private static void removePicture(Context context) {
        if (BUSY.get() || !CustomBadge.present(context)) {
            refreshRows();
            return;
        }
        boolean gone = CustomBadge.remove(context);
        refreshRows();
        SettingsActionBanner.showNotice(context, gone
                ? L10n.t(context, "Badge picture removed. Your profile shows the blue check again.")
                : L10n.t(context, "Couldn't remove the badge picture. Try again."));
    }

    private static boolean offersStop() {
        DocumentOperation acting = reading;
        return acting != null && acting.offersStop();
    }

    private static boolean heldAfterStop() {
        return DocumentOperation.heldAfterStop(DocumentOperation.Kind.BADGE_PICTURE);
    }

    private static void stopWaiting(Context window) {
        DocumentOperation acting = reading;
        if (acting == null || acting.stop() != DocumentOperation.Stage.STOPPED) {
            refreshRows();
            return;
        }
        finish();
        SettingsActionBanner.showNotice(window, L10n.t(window,
                "Stopped waiting for the file app. Nothing was imported."));
    }

    private static void finish() {
        BUSY.set(false);
        reading = null;
        refreshRows();
    }

    private static void refreshRows() {
        for (WeakReference<CustomBadgePreference> held : ROWS) {
            CustomBadgePreference row = held.get();
            if (row == null) ROWS.remove(held);
            else row.refreshState();
        }
    }

    private void refreshState() {
        Context context = getContext();
        boolean kept = CustomBadge.present(context);
        if (removes) {
            setEnabled(kept && !BUSY.get());
            setSummary(kept ? L10n.t(context, "Deletes the picture, and the blue check comes back.")
                    : L10n.t(context, "No badge picture is kept"));
            return;
        }
        boolean stoppable = offersStop();
        boolean held = !BUSY.get() && heldAfterStop();
        setEnabled(stoppable || !BUSY.get() && !picking && !held);
        setSummary(stoppable ? L10n.t(context, "Still waiting for the file app. Tap to stop waiting.")
                : BUSY.get() ? L10n.t(context, "Reading the picture")
                : held ? L10n.t(context,
                        "Waiting for the file app to let go of the last file. Restart TikTok if it doesn't.")
                : L10n.t(context, "Pick a picture to show in place of the blue check while Local verified "
                        + "badge is on. It's kept on this phone only and nobody else sees it."));
    }

    @Override protected void onBindView(View view) {
        refreshState();
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * A message of the reader's own, shown each time TikTok opens (#347): a title, a text, and
 * whether Back or a tap outside closes it.
 *
 * <p>"Opens" is a main activity with no live one before it. TikTok's launcher entry is an alias of
 * MainActivity, so a link can stack a second one on the first, and a theme or rotation change
 * recreates the one in front. Neither is an opening, and neither shows the message again.
 *
 * <p>Don't show again keeps that message away until TikTok is installed or updated again, so each
 * new download shows it once.
 */
public final class StartupMessage {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /**
     * Main activities the patched onCreate handed in that are alive, or destroyed for a
     * recreation that hasn't come in yet. Main thread.
     */
    private static final List<WeakReference<Activity>> SCREENS = new ArrayList<>();

    private StartupMessage() {
    }

    /** Called from the patched MainActivity.onCreate, before the host's own. Touches no window. */
    public static void onMainActivityCreate(Activity activity) {
        try {
            boolean opening = isOpening(activity);
            SCREENS.add(new WeakReference<>(activity));
            if (!opening || !Settings.STARTUP_MESSAGE.get()) return;
            String title = Settings.STARTUP_MESSAGE_TITLE.get().trim();
            String text = Settings.STARTUP_MESSAGE_TEXT.get().trim();
            if (title.isEmpty() && text.isEmpty()) return;
            String hidden = fingerprint(title, text, installedAt(activity));
            if (hidden.equals(Settings.STARTUP_MESSAGE_HIDDEN.get())) return;
            boolean dismissible = Settings.STARTUP_MESSAGE_DISMISSIBLE.get();
            // After the host's onCreate and the first resume, with its window in place.
            MAIN.post(() -> show(activity, title, text, dismissible, hidden));
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not prepare the startup message", failure);
        }
    }

    /**
     * The title's length leads, so a title and a text can't add up to another pair's. The install
     * time goes first, so a new download or update of TikTok shows the message once more (#347).
     */
    static String fingerprint(String title, String text, long installed) {
        return installed + "/" + title.length() + ":" + title + text;
    }

    /** When this TikTok was installed or last updated, or 0 when the system won't say. */
    static long installedAt(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException | RuntimeException failure) {
            return 0;
        }
    }

    static void resetForTests() {
        SCREENS.clear();
    }

    /** True when no other main activity is alive and this one replaces none being recreated. */
    static boolean isOpening(Activity activity) {
        boolean opening = true;
        boolean replaced = false;
        for (Iterator<WeakReference<Activity>> at = SCREENS.iterator(); at.hasNext(); ) {
            Activity other = at.next().get();
            if (other == null || (other.isDestroyed() && !other.isChangingConfigurations())) {
                at.remove();
            } else if (other == activity) {
                opening = false;
            } else if (!other.isDestroyed()) {
                // A link stacked this one on a main screen that's still there.
                opening = false;
            } else if (!replaced) {
                // A recreated activity's old self is destroyed by now, but it says it was
                // changing. This is its new self, so it's let go.
                replaced = true;
                opening = false;
                at.remove();
            }
        }
        return opening;
    }

    static void show(Activity activity, String title, String text, boolean dismissible, String hidden) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            SettingsUi.syncDarkMode(activity);
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setPositiveButton(L10n.t("OK"), null)
                    // Hides this message on this install, not the feature: a new title or text,
                    // or TikTok installed or updated again, shows it again.
                    .setNeutralButton(L10n.t("Don't show again"), (ignored, which) ->
                            Settings.STARTUP_MESSAGE_HIDDEN.save(hidden))
                    .setCancelable(dismissible);
            if (!title.isEmpty()) builder.setTitle(title);
            if (!text.isEmpty()) builder.setMessage(text);
            AlertDialog dialog = builder.create();
            dialog.setCanceledOnTouchOutside(dismissible);
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            dialog.show();
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not show the startup message", failure);
        }
    }
}

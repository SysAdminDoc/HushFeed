/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import android.app.Activity;
import android.app.AlertDialog;
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
            boolean dismissible = Settings.STARTUP_MESSAGE_DISMISSIBLE.get();
            // After the host's onCreate and the first resume, with its window in place.
            MAIN.post(() -> show(activity, title, text, dismissible));
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not prepare the startup message", failure);
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

    static void show(Activity activity, String title, String text, boolean dismissible) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            SettingsUi.syncDarkMode(activity);
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                    .setPositiveButton(L10n.t("OK"), null)
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

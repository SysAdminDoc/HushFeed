/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * "Open Hushfeed settings" on the app icon's long-press menu, in TikTok Lite (#48), whose feed has
 * none of the TikTok tabs a long press opens the page from. The launcher starts the shortcut as
 * the app itself, so {@link HushfeedSettingsActivity} stays unexported. Published each time the
 * app's main screen opens, which also renames it when the phone's language changes.
 */
public final class LauncherSettingsShortcut {
    static final String ID = "hushfeed_settings";
    /** The action {@link TikTokActivityHook#initialize} opens the page for. */
    static final String ACTION = "morphe_settings";

    private LauncherSettingsShortcut() {
    }

    /** Called from the app's main screen as it opens; the system call runs off the main thread. */
    public static void publish(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Utils.runOnBackgroundThread(() -> {
            try {
                publishNow(app);
            } catch (Throwable failure) {
                Logger.printException(() -> "Could not add Open Hushfeed settings to the app icon", failure);
            }
        });
    }

    /**
     * Called on the way out of every Lite method that clears or replaces the app's dynamic
     * shortcuts. Lite's start-up task clears them all when its own are switched off, and this
     * one went with them.
     */
    public static void republish() {
        publish(Utils.getContext());
    }

    @TargetApi(Build.VERSION_CODES.N_MR1)
    static void publishNow(Context context) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        Intent intent = new Intent(context, HushfeedSettingsActivity.class)
                .setAction(ACTION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        String label = L10n.t(context, "Open Hushfeed settings");
        ShortcutInfo.Builder shortcut = new ShortcutInfo.Builder(context, ID)
                .setShortLabel("Hushfeed")
                .setLongLabel(label)
                .setIntent(intent);
        int icon = context.getApplicationInfo().icon;
        if (icon != 0) shortcut.setIcon(Icon.createWithResource(context, icon));
        List<ShortcutInfo> shortcuts = new ArrayList<>();
        shortcuts.add(shortcut.build());
        boolean added = manager.addDynamicShortcuts(shortcuts);
        Logger.printDebug(() -> "Open Hushfeed settings on the app icon: " + (added ? "added" : "rate limited"));
    }
}

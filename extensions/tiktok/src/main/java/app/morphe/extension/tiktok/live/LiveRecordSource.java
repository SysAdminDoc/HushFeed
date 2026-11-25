/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.app.AlertDialog;
import android.content.Context;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/** What a LIVE recording is made from (#37): the room's own stream file, or the phone's screen. */
public final class LiveRecordSource {
    private LiveRecordSource() {
    }

    public static final String STREAM = "stream";
    public static final String SCREEN = "screen";

    public static final String[] LABELS = {"Stream only", "Screen"};
    public static final String[] VALUES = {STREAM, SCREEN};

    /** True when the stored choice is the screen; anything unknown is the stream, as before. */
    public static boolean screen(String value) {
        return SCREEN.equals(value);
    }

    /**
     * Whether the app's own sound can be part of a screen recording: playback capture exists from
     * Android 10, and it needs the microphone permission TikTok already declares to be granted.
     */
    public static boolean audioAllowed(int sdk, boolean microphoneGranted) {
        return sdk >= 29 && microphoneGranted;
    }

    /** The choice in effect now. */
    public static boolean screen() {
        return screen(Settings.LIVE_RECORD_SOURCE.get());
    }

    /**
     * Lists both choices with the one in effect marked, from holding the Record button in a room
     * (#529). A pick is saved and named in a toast at once, and the next recording is made from it.
     */
    public static void pick(Context context) {
        String[] labels = new String[LABELS.length];
        for (int i = 0; i < labels.length; i++) labels[i] = L10n.t(LABELS[i]);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(L10n.t("LIVE recording source"))
                .setSingleChoiceItems(labels, screen() ? 1 : 0, (shown, which) -> {
                    choose(which);
                    shown.dismiss();
                })
                .create();
        dialog.show();
        SettingsUi.styleStandardAlertDialog(dialog);
    }

    /** Saves choice number {@code which} and says what it is. */
    static void choose(int which) {
        if (which < 0 || which >= VALUES.length) return;
        Settings.LIVE_RECORD_SOURCE.save(VALUES[which]);
        Utils.showToastShort(L10n.t(LABELS[which]));
    }
}

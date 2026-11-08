/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The "Send to new group" button beside "Send separately" once two or more people are picked in
 * the share sheet (#375). One method shows and hides it, and it opens by reading the button and
 * returning when there is none. The patch hands that read through here, so a null answer ends the
 * method before it can show the button, and the button is put away first.
 */
public final class ShareNewGroupButton {
    private static final String FAMILY = "share new group button";

    private ShareNewGroupButton() {
    }

    /** Called with the button TikTok is about to show or hide. Null means leave it hidden. */
    public static View filter(View button) {
        try {
            HookStatus.bound(FAMILY, "button read");
            if (button == null || !Settings.HIDE_SHARE_NEW_GROUP.get()) return button;
            button.setVisibility(View.GONE);
            return null;
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "button read", ex);
            Logger.printException(() -> "Could not hide the share sheet's Send to new group button", ex);
            return button;
        }
    }
}

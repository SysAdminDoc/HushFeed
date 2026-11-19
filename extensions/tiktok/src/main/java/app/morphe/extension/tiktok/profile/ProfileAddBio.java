/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import android.view.View;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The "+ Add bio" prompt on your own profile (#466), the pill TikTok puts under your name while
 * the profile has no bio.
 *
 * <p>The header's Add bio component shows its view when the bio is empty and hides it when there
 * is one, every time the header binds. The patch asks here right after the view is shown, so with
 * the switch on it goes straight back to gone, and the rest of the bind (which reads the view's
 * visibility to decide whether it was shown) sees a profile with nothing to prompt for.
 */
public final class ProfileAddBio {
    private ProfileAddBio() {
    }

    /** Called with the prompt's view right after TikTok shows it. */
    public static void shown(View prompt) {
        boolean hide = Settings.HIDE_ADD_BIO.get();
        HookStatus.bound("profile add bio", hide ? "prompt hidden" : "prompt left");
        if (hide && prompt != null) prompt.setVisibility(View.GONE);
    }
}

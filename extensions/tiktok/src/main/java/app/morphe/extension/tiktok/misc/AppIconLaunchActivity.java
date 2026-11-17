/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import android.app.Activity;
import android.os.Bundle;

import app.morphe.extension.shared.Logger;

/**
 * What every picker icon opens (Discussion #114). It shows nothing and hands the tap to TikTok's
 * own launcher entry, so TikTok starts the way it always does from the home screen.
 *
 * <p>When TikTok is already open in the background, the launcher brings that back and starts this
 * on top only if the icon tapped isn't the one that opened it, which happens after the icon was
 * switched. Then there's nothing to forward: closing shows TikTok where it was, as a tap on the
 * old icon would have.
 */
public final class AppIconLaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (isTaskRoot()) {
            try {
                startActivity(AppIcons.forward(getIntent(), getPackageName()));
            } catch (RuntimeException failure) {
                Logger.printException(() -> "Could not open TikTok from the picked icon", failure);
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}

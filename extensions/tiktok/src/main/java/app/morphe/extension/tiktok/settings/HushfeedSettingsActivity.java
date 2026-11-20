/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import android.app.Activity;
import android.os.Bundle;

/**
 * Hushfeed's own screen for its settings page, in apps that have no TikTok screen to borrow for
 * it: TikTok Lite (#48) has no ad personalisation screen, so the Settings patch declares this
 * activity in Lite's manifest instead. The page itself is the same one TikTok shows.
 */
@SuppressWarnings("deprecation")
public final class HushfeedSettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TikTokActivityHook.initialize(this)) finish();
    }

    @Override
    public void onBackPressed() {
        if (!TikTokActivityHook.handleBackPressed(this)) super.onBackPressed();
    }
}

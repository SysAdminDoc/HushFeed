/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;

import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.ghostmode.LocalLikes;
import app.morphe.extension.tiktok.seen.LocalWatchHistory;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Opens the likes Ghost mode kept on the phone, newest first, in the same list as My watch
 * history. A tap opens the video; Clear takes every kept like back.
 */
@SuppressWarnings("deprecation")
public final class LocalLikesPreference extends Preference {
    /** A stable key, so the settings search can find the row. */
    public static final String KEY = "action_local_likes";

    private static final VideoListDialog.Source LIKES = new VideoListDialog.Source() {
        @Override
        public String title(Context context) {
            return L10n.t(context, "My likes on this phone");
        }

        @Override
        public String empty(Context context) {
            return L10n.t(context, "Videos you like on this phone show up here");
        }

        @Override
        public void cleared(Context context) {
            Utils.showToastShort(L10n.t(context, "Likes cleared"));
        }

        @Override
        public void load(LocalWatchHistory.Listener listener) {
            LocalLikes.load(listener);
        }

        @Override
        public void clear(LocalWatchHistory.Listener listener) {
            LocalLikes.clear(listener);
        }

        @Override
        public void restore(List<LocalWatchHistory.Entry> rows) {
            LocalLikes.restore(rows);
        }
    };

    public LocalLikesPreference(Context context) {
        super(context);
        setKey(KEY);
        setTitle(L10n.t(context, "My likes on this phone"));
        setSummary(L10n.t(context, "Find and reopen the videos you liked only on this phone"));
        setOnPreferenceClickListener(preference -> {
            VideoListDialog.show(context, KEY, LIKES, false);
            return true;
        });
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.seen.LocalWatchHistory;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Opens the watch history kept on the phone (#172): a search box over the videos, newest first. A
 * tap opens the video over the list, so Back returns to it with the search still typed. Thumbnails
 * aren't kept, since TikTok's links to them run out.
 */
@SuppressWarnings("deprecation")
public final class LocalWatchHistoryPreference extends Preference {
    /** A stable key, so the settings search can find the row. */
    public static final String KEY = "action_local_watch_history";

    /** TikTok's video page, which loads a video from its id alone. */
    private static final String VIDEO_PAGE = "com.ss.android.ugc.aweme.detail.ui.DetailActivity";

    /**
     * The app's own link to a video, for when the page can't be started. It works too, but it
     * brings the feed forward on the way and closes settings, so Back doesn't return here.
     */
    private static final String VIDEO_LINK = "snssdk1233://aweme/detail/";

    private static final VideoListDialog.Source HISTORY = new VideoListDialog.Source() {
        @Override
        public String title(Context context) {
            return L10n.t(context, "My watch history");
        }

        @Override
        public String empty(Context context) {
            return L10n.t(context, "Videos you watch show up here");
        }

        @Override
        public void cleared(Context context) {
            Utils.showToastShort(L10n.t(context, "Watch history cleared"));
        }

        @Override
        public void load(LocalWatchHistory.Listener listener) {
            LocalWatchHistory.load(listener);
        }

        @Override
        public void clear(LocalWatchHistory.Listener listener) {
            LocalWatchHistory.clear(listener);
        }

        @Override
        public void restore(List<LocalWatchHistory.Entry> rows) {
            LocalWatchHistory.restore(rows);
        }
    };

    public LocalWatchHistoryPreference(Context context) {
        super(context);
        setKey(KEY);
        setTitle(L10n.t(context, "My watch history"));
        setSummary(L10n.t(context, "Find and reopen the videos you've watched on this phone"));
        setOnPreferenceClickListener(preference -> {
            VideoListDialog.show(context, KEY, HISTORY, false);
            return true;
        });
    }

    /**
     * The same list over TikTok's feed, from a long press on For You (#172). That press is
     * otherwise a way into these settings, so the list carries a button to them.
     */
    public static void showOverFeed(Context context) {
        VideoListDialog.show(context, KEY, HISTORY, true);
    }

    static void open(Context context, String aid) {
        try {
            try {
                start(context, new Intent().setClassName(context.getPackageName(), VIDEO_PAGE)
                        .putExtra("id", aid));
            } catch (ActivityNotFoundException ex) {
                start(context, new Intent(Intent.ACTION_VIEW, Uri.parse(VIDEO_LINK + aid))
                        .setPackage(context.getPackageName()));
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not open a video from the watch history", ex);
            Utils.showToastShort(L10n.t(context, "Couldn't open that video. Try again."));
        }
    }

    private static void start(Context context, Intent intent) {
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}

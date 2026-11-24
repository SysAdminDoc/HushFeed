/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.graphics.Bitmap;
import android.preference.Preference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.feed.SavedVideoMemory;
import app.morphe.extension.tiktok.seen.LocalWatchHistory;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Opens the list of saved videos that are gone (#516): each with the cover and details Remember
 * saved videos kept while it could still be watched, the latest to go first, with a search box
 * over them. A tap shows everything kept of one, since the video itself no longer opens.
 */
@SuppressWarnings("deprecation")
public final class SavedVideoMemoryPreference extends Preference {
    /** A stable key, so the settings search can find the row. */
    public static final String KEY = "action_unavailable_saved_videos";

    /** The list's rows as the memory holds them, by video, for a tap and for an undo. */
    private static final Map<String, SavedVideoMemory.Entry> KEPT = new HashMap<>();

    private static final VideoListDialog.Source GONE = new VideoListDialog.Source() {
        @Override
        public String title(Context context) {
            return L10n.t(context, "Unavailable saved videos");
        }

        @Override
        public String empty(Context context) {
            return L10n.t(context, "Saved videos that go away show up here");
        }

        @Override
        public void cleared(Context context) {
            Utils.showToastShort(L10n.t(context, "Unavailable saved videos cleared"));
        }

        @Override
        public void load(LocalWatchHistory.Listener listener) {
            SavedVideoMemory.load(rows -> listener.onRows(listed(rows)));
        }

        @Override
        public void clear(LocalWatchHistory.Listener listener) {
            SavedVideoMemory.clear(rows -> listener.onRows(listed(rows)));
        }

        @Override
        public void restore(List<LocalWatchHistory.Entry> rows) {
            List<SavedVideoMemory.Entry> back = new ArrayList<>();
            for (LocalWatchHistory.Entry row : rows) {
                SavedVideoMemory.Entry kept = KEPT.get(row.aid);
                if (kept != null) back.add(kept);
            }
            SavedVideoMemory.restore(back);
        }

        @Override
        public boolean pictures() {
            return true;
        }

        @Override
        public Bitmap picture(LocalWatchHistory.Entry row) {
            return SavedVideoMemory.cover(row.aid);
        }

        @Override
        public void open(Context context, LocalWatchHistory.Entry row) {
            SavedVideoMemory.Entry kept = KEPT.get(row.aid);
            if (kept != null) SavedVideoDetailsDialog.show(context, kept);
        }
    };

    public SavedVideoMemoryPreference(Context context) {
        super(context);
        setKey(KEY);
        setTitle(L10n.t(context, "Unavailable saved videos"));
        setSummary(L10n.t(context, "See the saved videos that went away after Hushfeed remembered them"));
        setOnPreferenceClickListener(preference -> {
            SavedVideoMemory.sweepCovers();
            VideoListDialog.show(context, KEY, GONE, false);
            return true;
        });
    }

    /** The memory's rows as the list shows them: who, the caption, and when each went. Main thread. */
    static List<LocalWatchHistory.Entry> listed(List<SavedVideoMemory.Entry> rows) {
        List<LocalWatchHistory.Entry> listed = new ArrayList<>();
        for (SavedVideoMemory.Entry row : rows) {
            KEPT.put(row.aid, row);
            String who = row.creator.isEmpty() || row.handle.isEmpty()
                    ? row.shownName() : row.creator + " (@" + row.handle + ")";
            listed.add(new LocalWatchHistory.Entry(row.aid, who, row.caption, row.goneMs));
        }
        return listed;
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.download.SavedVideoMark;
import app.morphe.extension.tiktok.publishdate.AlwaysShowPublishDatePatch;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * What a profile grid cell shows for its view count. The grid adapter's bind and the cell holder's
 * own bind both ask here right after TikTok formats the count, and Show engagement rate,
 * Always show publish date and Advanced downloads each add their part while their switch is on:
 * the rate after the count ("12.3K · 4.2%"), the date and the video's length on a line of their
 * own under it ("Oct 7 · 0:42"), and a check mark before the count for a video saved here. The
 * count's view takes the cell's width less its icons and wraps in a 50dp bar, so the date and
 * length get their own line rather than pushing the count and the rate over it.
 *
 * <p>Hide view counts on profile grids (#449) drops the count and, through {@link #show}, the play
 * icon in front of it. Whatever else is on moves up to the first line, and a cell with nothing
 * else to show is left blank.
 */
public final class ProfileGridCount {
    private static final String SEPARATOR = " · ";

    private ProfileGridCount() {
    }

    public static String text(String count, Object item) {
        if (count == null) {
            return null;
        }
        boolean hide = hidesViews();
        String top = hide ? EngagementRate.gridRate(item) : EngagementRate.gridCount(count, item);
        String date = AlwaysShowPublishDatePatch.gridDate(item);
        String length = GridVideoLength.gridLength(item);
        String under = date == null ? length : length == null ? date : date + SEPARATOR + length;
        String text = top == null ? under : under == null ? top : top + "\n" + under;
        if (text == null) {
            // Nothing left but a saved video's mark, if it has one.
            return SavedVideoMark.gridText("", item);
        }
        return SavedVideoMark.gridText(text, item);
    }

    /**
     * Stands in for the holder bind's setText on its count view, right after TikTok put the play
     * icon in front of it. With the count hidden the icon goes too; otherwise the view is left as
     * TikTok set it, so a recycled cell gets its icon back on the next bind.
     */
    public static void show(TextView view, CharSequence text) {
        view.setText(text);
        if (!hidesViews()) {
            return;
        }
        try {
            view.setCompoundDrawablesRelative(null, null, null, null);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not take the play icon off a grid cell", ex);
        }
    }

    static boolean hidesViews() {
        return SettingsStatus.alwaysShowPublishDateEnabled && Settings.HIDE_GRID_VIEW_COUNT.get();
    }
}

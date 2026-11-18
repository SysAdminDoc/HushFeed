/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
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
 * count's view takes the cell's width less its icons, so the date and length get their own line
 * rather than pushing the count and the rate over it. TikTok's bar is a fixed 50dp and a long date
 * wraps to a third line, which it cut in half (#449, #462), so {@link #show} lets the bar grow.
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
     * TikTok set it, so a recycled cell gets its icon back on the next bind. A label that can run
     * past one line gets a bar tall enough for it.
     */
    public static void show(TextView view, CharSequence text) {
        view.setText(text);
        boolean hide = hidesViews();
        if (hide || (text != null && TextUtils.indexOf(text, '\n') >= 0)) {
            fitBar(view);
        }
        if (!hide) {
            return;
        }
        try {
            view.setCompoundDrawablesRelative(null, null, null, null);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not take the play icon off a grid cell", ex);
        }
    }

    /**
     * Turns the bar's fixed height into its minimum, so it looks the same until the label needs
     * more and then grows up the cell from the bottom edge it's pinned to. A recycled cell's bar is
     * already done, and a shorter label in it still sits at TikTok's height.
     */
    private static void fitBar(TextView view) {
        try {
            if (!(view.getParent() instanceof View)) {
                return;
            }
            View bar = (View) view.getParent();
            ViewGroup.LayoutParams params = bar.getLayoutParams();
            if (params == null || params.height <= 0) {
                return;
            }
            bar.setMinimumHeight(params.height);
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            bar.setLayoutParams(params);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not make room for a grid cell's label", ex);
        }
    }

    static boolean hidesViews() {
        return SettingsStatus.alwaysShowPublishDateEnabled && Settings.HIDE_GRID_VIEW_COUNT.get();
    }
}

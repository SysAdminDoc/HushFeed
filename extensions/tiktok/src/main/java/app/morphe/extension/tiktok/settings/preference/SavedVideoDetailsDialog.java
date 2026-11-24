/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.feed.SavedVideoMemory;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Everything Remember saved videos kept of one saved video that's gone (#516): its cover, who
 * posted it, the caption and the dates. Opened from the video's tile in Favorites and from its
 * row in the list of them, and the text can be selected, since a name or a caption is what
 * somebody would search for to find the video again.
 */
public final class SavedVideoDetailsDialog {
    private static final String KEY = "dialog_saved_video_details";

    private SavedVideoDetailsDialog() {
    }

    public static void show(Context context, SavedVideoMemory.Entry entry) {
        try {
            if (SettingsUi.dialogOpen(KEY, context)) return;
            int gap = SettingsUi.dp(context, 20);
            int small = SettingsUi.dp(context, 6);

            LinearLayout page = new LinearLayout(context);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(gap, small, gap, 0);

            Bitmap cover = SavedVideoMemory.cover(entry.aid);
            if (cover != null) {
                ImageView picture = new ImageView(context);
                picture.setImageBitmap(cover);
                picture.setAdjustViewBounds(true);
                picture.setMaxHeight(SettingsUi.dp(context, 260));
                picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.bottomMargin = small * 2;
                page.addView(picture, params);
            }
            if (!entry.creator.isEmpty()) {
                page.addView(line(context, entry.creator, SettingsUi.TEXT_BODY, SettingsUi.textPrimary(), Typeface.BOLD));
            }
            if (!entry.handle.isEmpty()) {
                page.addView(line(context, "@" + entry.handle, SettingsUi.TEXT_BODY_SMALL,
                        SettingsUi.textSecondary(), Typeface.NORMAL));
            }
            if (!entry.caption.isEmpty()) {
                TextView caption = line(context, entry.caption, SettingsUi.TEXT_BODY,
                        SettingsUi.textPrimary(), Typeface.NORMAL);
                caption.setPadding(0, small, 0, 0);
                page.addView(caption);
            }
            TextView dates = line(context, String.join("\n", dates(context, entry)), SettingsUi.TEXT_BODY_SMALL,
                    SettingsUi.textSecondary(), Typeface.NORMAL);
            dates.setPadding(0, small * 2, 0, 0);
            page.addView(dates);

            ScrollView scroll = new ScrollView(context);
            scroll.addView(page);
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(L10n.t(context, "Unavailable saved video"))
                    .setView(scroll)
                    .setPositiveButton(L10n.t(context, "Close"), (target, which) -> target.dismiss())
                    .create();
            dialog.show();
            SettingsUi.dialogOpened(KEY, context, dialog);
            SettingsUi.styleStandardAlertDialog(dialog);
            TextView heading = dialog.findViewById(android.R.id.title);
            if (heading != null) {
                heading.setGravity(Gravity.START);
                SettingsUi.markDialogHeading(heading);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not show a gone saved video's details", ex);
        }
    }

    /** The dates kept for a video, each on its own line: posted where TikTok said, remembered, gone. */
    static List<String> dates(Context context, SavedVideoMemory.Entry entry) {
        DateFormat format = DateFormat.getDateInstance(DateFormat.MEDIUM, L10n.shownLocale(context));
        List<String> lines = new ArrayList<>();
        if (entry.postedMs > 0) lines.add(L10n.f(context, "Posted %1$s", format.format(new Date(entry.postedMs))));
        lines.add(L10n.f(context, "Remembered %1$s", format.format(new Date(entry.rememberedMs))));
        if (entry.goneMs > 0) {
            lines.add(L10n.f(context, "Unavailable since %1$s", format.format(new Date(entry.goneMs))));
        }
        return lines;
    }

    private static TextView line(Context context, String value, float sizeSp, int color, int style) {
        TextView text = SettingsUi.text(context, value, sizeSp, color, style);
        text.setTextIsSelectable(true);
        return text;
    }
}

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.widget.ImageView;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The share sheet actions Hushfeed builds on TikTok's native Report carrier: Block this creator
 * (Share sheet tools), Save cover and Save episodes (Advanced downloads) and Make sticker (Create
 * stickers). The carrier's key, label, click and icon hooks land here once and each action answers only for the
 * carriers it built, so a carrier none of them owns keeps every original Report instruction.
 */
public final class OwnedShareActions {
    private OwnedShareActions() {}

    public static boolean owns(Object action) {
        return ShareBlockAuthor.owns(action) || ShareSaveCover.owns(action)
                || ShareLiveQuality.owns(action) || ShareMakeSticker.owns(action)
                || ShareSaveEpisodes.owns(action) || ShareLiveButtons.owns(action);
    }

    public static String key(Object action) {
        String key = ShareBlockAuthor.key(action);
        if (key == null) key = ShareSaveCover.key(action);
        if (key == null) key = ShareMakeSticker.key(action);
        if (key == null) key = ShareSaveEpisodes.key(action);
        if (key == null) key = ShareLiveButtons.key(action);
        return key != null ? key : ShareLiveQuality.key(action);
    }

    public static String label(Object action) {
        String label = ShareBlockAuthor.label(action);
        if (label == null) label = ShareSaveCover.label(action);
        if (label == null) label = ShareMakeSticker.label(action);
        if (label == null) label = ShareSaveEpisodes.label(action);
        if (label == null) label = ShareLiveButtons.label(action);
        return label != null ? label : ShareLiveQuality.label(action);
    }

    public static boolean click(Object action, Context context, Object sharePackage) {
        return ShareBlockAuthor.click(action, context, sharePackage)
                || ShareSaveCover.click(action, context, sharePackage)
                || ShareMakeSticker.click(action, context, sharePackage)
                || ShareSaveEpisodes.click(action, context, sharePackage)
                || ShareLiveButtons.click(action, context, sharePackage)
                || ShareLiveQuality.click(action, context, sharePackage);
    }

    /**
     * The key a native action binder looks up in its framed-icon list, read for that test alone.
     * TikTok draws its themed circle and icon only for a key on the list and a bare drawable for
     * any other, so an owned carrier answers Report's here: it gets Report's frame, and
     * {@link #bindIcon} puts its own glyph inside it. Every other read keeps the carrier's key.
     */
    public static String framedKey(Object action, String key) {
        return owns(action) ? FRAMED_KEY : key;
    }

    /** The native carrier's own key, which TikTok's framed-icon list holds. */
    static final String FRAMED_KEY = "report";

    public static void bindIcon(Object action, ImageView image) {
        ShareBlockAuthor.bindIcon(action, image);
        ShareSaveCover.bindIcon(action, image);
        ShareMakeSticker.bindIcon(action, image);
        ShareSaveEpisodes.bindIcon(action, image);
        ShareLiveButtons.bindIcon(action, image);
        ShareLiveQuality.bindIcon(action, image);
    }

    /** Replaced with the reviewed native Report carrier constructor by the patch. */
    static Object newAction(Activity activity, Object aweme) {
        return null;
    }

    /** A menu row model and the owned carrier it was copied from, both held weakly. */
    private static final class MenuRow {
        final WeakReference<Object> row;
        final WeakReference<Object> action;

        MenuRow(Object row, Object action) {
            this.row = new WeakReference<>(row);
            this.action = new WeakReference<>(action);
        }
    }

    private static final List<MenuRow> MENU_ROWS = new ArrayList<>();

    /**
     * LIVE's long-press menu copies each action into a row model of its own (#359): an icon and a
     * text resource read off the action, which for a carrier are Report's. Its row view draws only
     * that model, so none of the carrier's hooks run there and an owned row read "Report" under
     * Report's flag (S22, 2026-11-18). The patch calls this as each model is built, and a model
     * copied from an owned carrier is remembered by identity for {@link #bindMenuRow}.
     */
    public static void menuRow(Object action, Object row) {
        try {
            if (row == null || !owns(action)) return;
            synchronized (MENU_ROWS) {
                prune();
                MENU_ROWS.add(new MenuRow(row, action));
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not note a menu row", failure);
        }
    }

    /**
     * A row view finished drawing {@code row}. One copied from an owned carrier gets the carrier's own
     * label and glyph in place of Report's; any other row keeps what TikTok drew.
     */
    public static void bindMenuRow(Object row, ImageView icon, TextView text) {
        try {
            Object action = menuAction(row);
            if (action == null) return;
            String label = label(action);
            if (text != null && label != null) text.setText(label);
            if (icon == null) return;
            bindIcon(action, icon);
            // TikTok names each row's icon after its row ("Report"), and the carrier's said "Error"
            // on 48.0.2, which is what a screen reader read out for every Hushfeed row.
            if (label != null) icon.setContentDescription(label);
            // A bare glyph takes the theme's text color, which needn't be this menu's. The row's
            // own label color is, so the glyph matches the words beside it.
            Drawable drawn = icon.getDrawable();
            if (text != null && drawn != null && !(drawn instanceof LayerDrawable)) {
                drawn.setColorFilter(new PorterDuffColorFilter(text.getCurrentTextColor(), PorterDuff.Mode.SRC_IN));
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not draw a menu row", failure);
        }
    }

    static Object menuAction(Object row) {
        if (row == null) return null;
        synchronized (MENU_ROWS) {
            prune();
            for (MenuRow each : MENU_ROWS) {
                if (each.row.get() == row) return each.action.get();
            }
        }
        return null;
    }

    private static void prune() {
        for (Iterator<MenuRow> each = MENU_ROWS.iterator(); each.hasNext();) {
            MenuRow entry = each.next();
            if (entry.row.get() == null || entry.action.get() == null) each.remove();
        }
    }
}

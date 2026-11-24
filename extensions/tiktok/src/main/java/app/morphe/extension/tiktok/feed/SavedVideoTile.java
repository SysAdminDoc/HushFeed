/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SavedVideoDetailsDialog;

/**
 * What a Favorites tile shows for a saved video that's gone (#516): the cover Remember saved
 * videos kept, dimmed, with who posted it along the bottom and a mark that it's unavailable. It
 * goes over TikTok's grey tile as the cell's holder binds, as a layer of its own, and comes off
 * again when the cell is reused for a video that's still there. A tap shows everything kept.
 */
public final class SavedVideoTile {
    static final String LAYER_TAG = "hushfeed_saved_video_tile";
    private static final String COVER_TAG = "hushfeed_saved_video_cover";
    private static final String NAME_TAG = "hushfeed_saved_video_name";
    /** Behind a tile whose cover couldn't be kept, so the name still reads. */
    private static final int NO_COVER = Color.rgb(42, 42, 46);
    /** The kept covers on screen, so scrolling back over a gone tile doesn't read its file again. */
    private static final LruCache<String, Bitmap> COVERS = new LruCache<>(12);

    private SavedVideoTile() {
    }

    /** A profile grid cell's root view and the post its holder is about to draw. */
    public static void cell(View cell, Object aweme) {
        if (!SettingsStatus.favoritesJumpEnabled || !(cell instanceof ViewGroup)) return;
        try {
            ViewGroup group = (ViewGroup) cell;
            View layer = group.findViewWithTag(LAYER_TAG);
            SavedVideoMemory.Entry entry = null;
            if (SavedVideoMemory.enabled() && SavedVideoMemory.unavailable(aweme)) {
                entry = SavedVideoMemory.goneFor(Reflect.string(aweme, "getAid", "aid"));
            }
            if (entry == null) {
                if (layer != null) group.removeView(layer);
                return;
            }
            HookStatus.bound(SavedVideoMemory.FAMILY, "gone tile drawn");
            if (layer == null) {
                layer = build(cell.getContext());
                group.addView(layer, new ViewGroup.LayoutParams(-1, -1));
            }
            fill(layer, entry);
        } catch (Throwable ex) {
            HookStatus.threw(SavedVideoMemory.FAMILY, "gone tile drawn", ex);
            Logger.printException(() -> "Could not draw a gone saved video's tile", ex);
        }
    }

    private static View build(Context context) {
        FrameLayout layer = new FrameLayout(context);
        layer.setTag(LAYER_TAG);
        layer.setBackgroundColor(NO_COVER);
        layer.setClickable(true);

        ImageView cover = new ImageView(context);
        cover.setTag(COVER_TAG);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // Dimmed, so a gone video doesn't pass for one that plays.
        cover.setColorFilter(Color.argb(110, 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
        layer.addView(cover, new FrameLayout.LayoutParams(-1, -1));

        int small = dp(context, 4);
        int gap = dp(context, 6);
        TextView mark = label(context, 10, Typeface.BOLD);
        mark.setText(L10n.t(context, "Unavailable"));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.argb(150, 0, 0, 0));
        pill.setCornerRadius(dp(context, 8));
        mark.setBackground(pill);
        mark.setPadding(gap, small / 2, gap, small / 2);
        FrameLayout.LayoutParams markParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        markParams.setMargins(small, small, small, small);
        layer.addView(mark, markParams);

        TextView name = label(context, 11, Typeface.BOLD);
        name.setTag(NAME_TAG);
        name.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Color.argb(170, 0, 0, 0), Color.TRANSPARENT}));
        name.setPadding(gap, gap * 2, gap, gap);
        layer.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        return layer;
    }

    private static TextView label(Context context, int sizeSp, int style) {
        TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        text.setTextColor(Color.WHITE);
        text.setTypeface(Typeface.DEFAULT, style);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setShadowLayer(3f, 0f, 1f, Color.argb(160, 0, 0, 0));
        return text;
    }

    private static void fill(View layer, SavedVideoMemory.Entry entry) {
        ImageView cover = layer.findViewWithTag(COVER_TAG);
        TextView name = layer.findViewWithTag(NAME_TAG);
        Bitmap kept = COVERS.get(entry.aid);
        if (kept == null) {
            kept = SavedVideoMemory.cover(entry.aid);
            if (kept != null) COVERS.put(entry.aid, kept);
        }
        cover.setImageBitmap(kept);
        String shown = entry.handle.isEmpty() ? entry.creator : "@" + entry.handle;
        name.setText(shown);
        name.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        Context context = layer.getContext();
        String mark = L10n.t(context, "Unavailable");
        layer.setContentDescription(shown.isEmpty() ? mark : L10n.f(context, "%1$s, %2$s", mark, shown));
        layer.setOnClickListener(view -> SavedVideoDetailsDialog.show(view.getContext(), entry));
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }
}

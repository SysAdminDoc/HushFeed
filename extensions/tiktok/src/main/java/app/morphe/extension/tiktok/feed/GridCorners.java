/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.graphics.Outline;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Rounded corners on profile grid cells (#487): your videos, Liked, Favorites and anyone else's
 * profile grid, every cell the profile grid's holder binds, and the posts inside a Favorites
 * collection folder, which draws them with a holder of its own. Each cell's root view is clipped
 * to a rounded outline as its holder binds, so the cover and everything TikTok draws over it
 * share the corners. Turning the switch off gives a cell back the outline and clipping it had at
 * its next bind.
 */
public final class GridCorners {
    static final String FAMILY = "grid corners";
    /** About the rounding TikTok's own cards use. */
    static final float RADIUS_DP = 8f;

    private GridCorners() {
    }

    static boolean enabled() {
        return SettingsStatus.alwaysShowPublishDateEnabled && Settings.ROUNDED_GRID_CORNERS.get();
    }

    /**
     * A Favorites collection folder's cell holder, as it binds the post its adapter just set. The
     * hook sits past the bind's first instruction, which Advanced downloads reads, so it hands
     * over the holder itself and its root view is read here.
     */
    public static void collectionCell(Object holder) {
        HookStatus.bound(FAMILY, "collection cell bind");
        Object view = Reflect.readField(holder, "itemView");
        if (view instanceof View) round((View) view);
    }

    /** A profile grid cell's root view, as its holder binds the next post. */
    public static void cell(View cell) {
        HookStatus.bound(FAMILY, "grid cell bind");
        round(cell);
    }

    private static void round(View cell) {
        if (cell == null) return;
        try {
            ViewOutlineProvider current = cell.getOutlineProvider();
            if (enabled()) {
                if (current instanceof Rounded) return;
                float radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, RADIUS_DP,
                        cell.getResources().getDisplayMetrics());
                cell.setOutlineProvider(new Rounded(current, cell.getClipToOutline(), radius));
                cell.setClipToOutline(true);
            } else if (current instanceof Rounded) {
                Rounded rounded = (Rounded) current;
                cell.setOutlineProvider(rounded.previous);
                cell.setClipToOutline(rounded.previousClip);
            }
        } catch (RuntimeException failure) {
            HookStatus.threw(FAMILY, "grid cell corners", failure);
            Logger.printException(() -> "Could not round a grid cell's corners", failure);
        }
    }

    /** The cell's bounds with rounded corners, remembering what the cell had before. */
    static final class Rounded extends ViewOutlineProvider {
        final ViewOutlineProvider previous;
        final boolean previousClip;
        final float radius;

        Rounded(ViewOutlineProvider previous, boolean previousClip, float radius) {
            this.previous = previous;
            this.previousClip = previousClip;
            this.radius = radius;
        }

        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
        }
    }
}

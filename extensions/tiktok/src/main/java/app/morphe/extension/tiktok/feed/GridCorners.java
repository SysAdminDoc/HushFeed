/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.graphics.Outline;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Rounded corners on profile grid cells (#487): your videos, Liked, Favorites and anyone else's
 * profile grid, every cell the profile grid's holder binds, and the posts inside a Favorites
 * collection folder, which draws them with a holder of its own. Each cell's root view is clipped
 * to a rounded outline as its holder binds, so the cover and everything TikTok draws over it
 * share the corners. Turning the switch off gives a cell back the outline and clipping it had at
 * its next bind.
 *
 * The corners' size is a setting of its own, and so is a gap between the videos (#549): each cell
 * gets half the gap as extra margin on every side, on top of the margins TikTok gave it, so two
 * neighbors end up the whole gap further apart than TikTok's own thin line. Setting the gap back
 * to 0 gives a cell its own margins back at its next bind.
 */
public final class GridCorners {
    static final String FAMILY = "grid corners";

    /** Each cell's own margins, kept while a gap is added to them. */
    private static final Map<View, int[]> OWN_MARGINS = new WeakHashMap<>();

    private GridCorners() {
    }

    static boolean enabled() {
        return SettingsStatus.alwaysShowPublishDateEnabled && Settings.ROUNDED_GRID_CORNERS.get();
    }

    /** The gap to add between the videos, in dp; 0 leaves TikTok's own spacing. */
    static int gapDp() {
        return SettingsStatus.alwaysShowPublishDateEnabled ? Math.max(0, Settings.GRID_GAP.get()) : 0;
    }

    /**
     * A Favorites collection folder's cell holder, as it binds the post its adapter just set. The
     * hook sits past the bind's first instruction, which Advanced downloads reads, so it hands
     * over the holder itself and its root view is read here.
     */
    public static void collectionCell(Object holder) {
        HookStatus.bound(FAMILY, "collection cell bind");
        Object view = Reflect.readField(holder, "itemView");
        if (view instanceof View) shape((View) view);
    }

    /** A profile grid cell's root view, as its holder binds the next post. */
    public static void cell(View cell) {
        HookStatus.bound(FAMILY, "grid cell bind");
        shape(cell);
    }

    private static void shape(View cell) {
        if (cell == null) return;
        round(cell);
        space(cell);
    }

    private static void round(View cell) {
        try {
            ViewOutlineProvider current = cell.getOutlineProvider();
            if (enabled()) {
                float radius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                        Math.max(1, Settings.GRID_CORNER_RADIUS.get()), cell.getResources().getDisplayMetrics());
                if (current instanceof Rounded) {
                    Rounded rounded = (Rounded) current;
                    // A new size swaps the outline; setting it redraws the corners.
                    if (rounded.radius != radius) {
                        cell.setOutlineProvider(new Rounded(rounded.previous, rounded.previousClip, radius));
                    }
                    return;
                }
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

    /** Adds half the gap to each of the cell's own margins, or gives them back with no gap set. */
    private static void space(View cell) {
        try {
            if (!(cell.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) cell.getLayoutParams();
            int gap = gapDp();
            int[] own = OWN_MARGINS.get(cell);
            if (gap == 0) {
                if (own == null) return;
                OWN_MARGINS.remove(cell);
                setMargins(cell, params, own, 0);
                return;
            }
            if (own == null) {
                own = new int[]{params.leftMargin, params.topMargin, params.rightMargin, params.bottomMargin};
                OWN_MARGINS.put(cell, own);
            }
            int half = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, gap,
                    cell.getResources().getDisplayMetrics()) / 2f);
            setMargins(cell, params, own, half);
        } catch (RuntimeException failure) {
            HookStatus.threw(FAMILY, "grid cell gap", failure);
            Logger.printException(() -> "Could not space a grid cell", failure);
        }
    }

    private static void setMargins(View cell, ViewGroup.MarginLayoutParams params, int[] own, int extra) {
        int left = own[0] + extra;
        int top = own[1] + extra;
        int right = own[2] + extra;
        int bottom = own[3] + extra;
        if (params.leftMargin == left && params.topMargin == top && params.rightMargin == right
                && params.bottomMargin == bottom) return;
        params.setMargins(left, top, right, bottom);
        cell.requestLayout();
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

/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;

/**
 * The comment box a viewer types in inside a LIVE room, under Dark LIVE panels (#267, #323, #329).
 *
 * <p>The panel's fill follows the room's theme, so the switch makes it dark, but the box's typed
 * text comes from a color every TikTok theme sets to the same dark ink, which no dark theme can
 * flip, and read dark on dark. The patch calls {@link #onPanel} with the panel's root view as
 * TikTok builds it. Once the view has finished its own setup, every text box in it takes a light
 * text color and a softer light hint, on the same answer that darkened the room. A room the switch
 * leaves light keeps TikTok's colors, in dark mode as well.
 *
 * <p>The bar's heart and emoji buttons kept a dark ink on the dark bar, and the emoji picker's
 * boxes behind its send and delete buttons stayed white (#329, a custom AMOLED color). So the
 * panel is watched too: on each layout its opaque white and light gray fills take the panel's own
 * dark fill, which is the AMOLED color when one is picked, and before each frame its dark gray
 * text and single-ink dark icons are turned light, since TikTok tints its icons again in code.
 * Colored buttons, photos, emoji art and anything on a ground of its own keep their look.
 */
public final class LiveCommentBox {
    static final int TEXT = Color.WHITE;
    static final int HINT = Color.argb(0x99, 0xff, 0xff, 0xff);
    /** A fill at least this opaque is a ground; below it the panel shows through. */
    private static final int MIN_FILL_ALPHA = 0x80;
    /** How far apart a white or gray fill's channels may be, so colored buttons keep their color. */
    private static final int MAX_GRAY_SPREAD = 0x30;
    private static final int MAX_DEPTH = 64;
    /** Panels already watched, so one isn't hooked twice. */
    private static final Map<View, Boolean> WATCHED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Views whose light fill was swapped for the panel's, so their icons count as on the panel. */
    private static final Map<View, Boolean> DARKENED = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean frameFailureLogged;

    private LiveCommentBox() {}

    /** Called with the comment panel's root view, first thing in its setup. */
    public static void onPanel(View root) {
        if (root == null) return;
        try {
            root.post(() -> paint(root));
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not watch the LIVE comment box", failure);
        }
    }

    /**
     * Lights the text and the hint of each text box under {@code root} while the panel is dark,
     * and keeps the rest of the panel's fills dark and its ink light from then on.
     */
    static void paint(View root) {
        try {
            if (root == null || !LiveControls.darkLivePanels(root.getContext())) return;
            light(root);
            watch(root);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not color the LIVE comment box", failure);
        }
    }

    private static void light(View view) {
        if (view instanceof EditText) {
            EditText box = (EditText) view;
            box.setTextColor(TEXT);
            box.setHintTextColor(HINT);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) light(group.getChildAt(index));
    }

    /** The picker's pages arrive after the panel, so the fills are looked at again on each layout. */
    private static void watch(View root) {
        if (WATCHED.put(root, Boolean.TRUE) != null) return;
        List<View> ink = new ArrayList<>();
        darken(root, ink);
        ViewTreeObserver observer = root.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(() -> {
            try {
                darken(root, ink);
            } catch (Throwable failure) {
                Logger.printException(() -> "Could not darken the LIVE comment panel", failure);
            }
        });
        observer.addOnPreDrawListener(() -> {
            try {
                for (View view : ink) LivePanelTheme.lightInk(view);
            } catch (Throwable failure) {
                if (!frameFailureLogged) {
                    frameFailureLogged = true;
                    Logger.printException(() -> "Could not keep the LIVE comment panel's icons light", failure);
                }
            }
            return true;
        });
    }

    /** Swaps the panel's light fills for its own dark one and gathers the text and icons to keep light. */
    static void darken(View root, List<View> ink) {
        int fill = panelFill(root);
        ink.clear();
        darken(root, fill, ink, 0, false, false);
        for (View view : ink) LivePanelTheme.lightInk(view);
    }

    /**
     * Inside a list, such as the picker's emoji grid, an image is art: a black heart has to stay
     * black. So there an image is only drawn light on a box this made dark, like a delete button's.
     */
    private static void darken(View view, int fill, List<View> ink, int depth, boolean listed, boolean onDarkened) {
        if (view == null || depth > MAX_DEPTH || view.getVisibility() != View.VISIBLE) return;
        boolean picture = view instanceof ImageView && ((ImageView) view).getDrawable() != null;
        if (!picture && swapFill(view.getBackground(), fill)) {
            view.invalidate();
            DARKENED.put(view, Boolean.TRUE);
        }
        onDarkened |= DARKENED.containsKey(view);
        if (ownGround(view.getBackground()) || view instanceof EditText) return;
        if (view instanceof TextView || view instanceof ImageView) {
            if (!(view instanceof ImageView) || !listed || onDarkened) ink.add(view);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        boolean list = isList(group);
        for (int index = 0; index < group.getChildCount(); index++) {
            darken(group.getChildAt(index), fill, ink, depth + 1, listed || list, onDarkened && !list);
        }
    }

    /** An emoji grid or any other list. TikTok's are RecyclerView subclasses, some renamed. */
    private static boolean isList(ViewGroup group) {
        if (group instanceof AdapterView) return true;
        for (Class<?> type = group.getClass(); type != null && type != ViewGroup.class; type = type.getSuperclass()) {
            if ("RecyclerView".equals(type.getSimpleName())) return true;
        }
        return false;
    }

    /** The first opaque dark fill under the root, nearest it first: the panel's own, in any hue. */
    static int panelFill(View root) {
        List<View> level = Collections.singletonList(root);
        for (int depth = 0; depth <= MAX_DEPTH && !level.isEmpty(); depth++) {
            List<View> next = new ArrayList<>();
            for (View view : level) {
                if (view.getVisibility() != View.VISIBLE) continue;
                Integer color = solidColor(view.getBackground());
                if (color != null && Color.alpha(color) == 0xFF && !LivePanelTheme.isLight(color)) return color;
                if (!(view instanceof ViewGroup)) continue;
                ViewGroup group = (ViewGroup) view;
                for (int index = 0; index < group.getChildCount(); index++) next.add(group.getChildAt(index));
            }
            level = next;
        }
        return LivePanelTheme.DARK_FILL;
    }

    /** A solid or rounded fill's one color, or null for anything else. */
    private static Integer solidColor(Drawable background) {
        if (background instanceof DrawableContainer && background.getCurrent() != background) {
            return solidColor(background.getCurrent());
        }
        if (background instanceof ColorDrawable) return ((ColorDrawable) background).getColor();
        if (background instanceof GradientDrawable && Build.VERSION.SDK_INT >= 24) {
            ColorStateList colors = ((GradientDrawable) background).getColor();
            return colors == null ? null : colors.getDefaultColor();
        }
        return null;
    }

    /**
     * Gives the white and light gray parts of a background the panel's fill, each keeping its own
     * see-through amount: a solid or rounded fill, the stops of a fade, the layers of a layered
     * one. True when something changed.
     */
    static boolean swapFill(Drawable background, int fill) {
        if (background == null) return false;
        if (background instanceof DrawableContainer) {
            // Its states can share their drawables with other views' copies until it's mutated.
            Drawable current = background.mutate().getCurrent();
            return current != background && swapFill(current, fill);
        }
        if (background instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) background;
            boolean changed = false;
            for (int index = 0; index < layers.getNumberOfLayers(); index++) {
                if (layers.getId(index) != android.R.id.mask) changed |= swapFill(layers.getDrawable(index), fill);
            }
            return changed;
        }
        if (background instanceof ColorDrawable) {
            ColorDrawable solid = (ColorDrawable) background;
            if (!whiteFill(solid.getColor())) return false;
            solid.mutate();
            solid.setColor(onFill(solid.getColor(), fill));
            return true;
        }
        if (!(background instanceof GradientDrawable) || Build.VERSION.SDK_INT < 24) return false;
        GradientDrawable shape = (GradientDrawable) background;
        ColorStateList colors = shape.getColor();
        if (colors != null) {
            if (!whiteFill(colors.getDefaultColor())) return false;
            shape.mutate();
            shape.setColor(onFill(colors.getDefaultColor(), fill));
            return true;
        }
        int[] stops = shape.getColors();
        if (stops == null) return false;
        boolean white = false;
        for (int stop : stops) white |= whiteFill(stop);
        if (!white) return false;
        for (int index = 0; index < stops.length; index++) {
            if (gray(stops[index]) && LivePanelTheme.isLight(stops[index])) stops[index] = onFill(stops[index], fill);
        }
        shape.mutate();
        shape.setColors(stops);
        return true;
    }

    /**
     * True for a background that shows a ground of its own under a view's ink: a light fill the
     * swap left alone, a picture, or anything drawn. Clear, dark and see-through fills aren't one,
     * so the bar's icons on their faint pill still count as the panel's.
     */
    static boolean ownGround(Drawable background) {
        if (background == null) return false;
        if (background instanceof DrawableContainer) {
            Drawable current = background.getCurrent();
            return current != background && ownGround(current);
        }
        if (background instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) background;
            for (int index = 0; index < layers.getNumberOfLayers(); index++) {
                if (layers.getId(index) != android.R.id.mask && ownGround(layers.getDrawable(index))) return true;
            }
            return false;
        }
        if (background instanceof GradientDrawable && Build.VERSION.SDK_INT >= 24) {
            GradientDrawable shape = (GradientDrawable) background;
            if (shape.getColor() != null) return lightFill(shape.getColor().getDefaultColor());
            int[] stops = shape.getColors();
            if (stops == null) return false;
            for (int stop : stops) {
                if (lightFill(stop)) return true;
            }
            return false;
        }
        if (background instanceof ColorDrawable) return lightFill(((ColorDrawable) background).getColor());
        return true;
    }

    private static boolean lightFill(int color) {
        return Color.alpha(color) >= MIN_FILL_ALPHA && LivePanelTheme.isLight(color);
    }

    private static boolean whiteFill(int color) {
        return lightFill(color) && gray(color);
    }

    private static boolean gray(int color) {
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) <= MAX_GRAY_SPREAD;
    }

    /** The panel's fill with the old color's see-through amount. */
    private static int onFill(int old, int fill) {
        return (old & 0xFF000000) | (fill & 0x00FFFFFF);
    }
}

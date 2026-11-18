/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.BitmapDrawable;
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

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.theme.AmoledTheme;

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
 * Colored buttons, photos, emoji art and anything on a ground of its own keep their look. The
 * panel behind the picker's delete and send keys is a picture, a white box with a soft glow, so a
 * plain white picture takes the panel's fill too, keeping its glow's see-through edge.
 *
 * <p>The panel's own fill isn't the AMOLED color, though: the bar and the picker are the LIVE
 * module's night grays (#121212, the grid #1b1b1b), which AMOLED dark theme's resources don't
 * reach. So with a picked AMOLED color other than the default black, that color is the fill, and
 * the panel's opaque near-black grays take it as well, so the bar, the picker and its boxes match.
 * The delete key's box isn't a background: TikTok's LiveIconView keeps it as the icon's own box
 * and draws it with the glyph, so that box is looked up on the view and takes the color too. That
 * box is one of TikTok's own drawables, a rounded rect that sets its paint's color on every draw,
 * so it's read where it paints and, when that's a night gray, filtered to the color.
 */
public final class LiveCommentBox {
    static final int TEXT = Color.WHITE;
    static final int HINT = Color.argb(0x99, 0xff, 0xff, 0xff);
    /** A fill at least this opaque is a ground; below it the panel shows through. */
    private static final int MIN_FILL_ALPHA = 0x80;
    /** How far apart a white or gray fill's channels may be, so colored buttons keep their color. */
    private static final int MAX_GRAY_SPREAD = 0x30;
    /**
     * A gray below this in every channel is one of the module's night grounds, the same ceiling
     * AMOLED dark theme rewrites TikTok's own dark grays under.
     */
    private static final int NIGHT_GRAY_CEILING = 0x40;
    private static final int MAX_DEPTH = 64;
    /** Panels already watched, so one isn't hooked twice. */
    private static final Map<View, Boolean> WATCHED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Views whose light fill was swapped for the panel's, so their icons count as on the panel. */
    private static final Map<View, Boolean> DARKENED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Picture backgrounds already drawn in the panel's fill. */
    private static final Map<Drawable, Boolean> TINTED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Each picture's answer to whether it's a plain white ground, so it's read once. */
    private static final Map<Bitmap, Boolean> WHITE_PICTURES = Collections.synchronizedMap(new WeakHashMap<>());
    /** Each LiveIconView class's field holding its icon's box, or null when it has no single one. */
    private static final Map<Class<?>, Field> ICON_BOX_FIELDS = Collections.synchronizedMap(new WeakHashMap<>());
    /** Drawn icon boxes that keep their look (a color, the fill already, see-through), so they're not read again. */
    private static final Map<Drawable, Boolean> KEPT_BOXES = Collections.synchronizedMap(new WeakHashMap<>());
    /** How many points across and down a picture is read at. */
    private static final int SAMPLES = 12;
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
        // Last, so a pass that fails still leaves the panel looked at again on its next layout.
        darken(root, ink);
    }

    /** Swaps the panel's light fills for its own dark one and gathers the text and icons to keep light. */
    static void darken(View root, List<View> ink) {
        darken(root, AmoledTheme.customColor(), ink);
    }

    /**
     * The same, with the picked AMOLED color, or 0 to keep the panel's own fill. A picked color
     * replaces the panel's night grays too.
     */
    static void darken(View root, int custom, List<View> ink) {
        int fill = custom != 0 ? custom : panelFill(root);
        ink.clear();
        darken(root, fill, custom != 0, ink, 0, false, false);
        for (View view : ink) LivePanelTheme.lightInk(view);
    }

    /**
     * Inside a list, such as the picker's emoji grid, an image is art: a black heart has to stay
     * black. So there an image is only drawn light on a box this made dark, like a delete button's.
     */
    private static void darken(View view, int fill, boolean grays, List<View> ink, int depth, boolean listed,
            boolean onDarkened) {
        if (view == null || depth > MAX_DEPTH || view.getVisibility() != View.VISIBLE) return;
        boolean picture = view instanceof ImageView && ((ImageView) view).getDrawable() != null;
        if (!picture) {
            // Only a box that was light counts as made dark: a night gray one's ink was light already.
            boolean light = swapFill(view.getBackground(), fill, false);
            boolean night = grays && swapFill(view.getBackground(), fill, true);
            if (light || night) view.invalidate();
            if (light) DARKENED.put(view, Boolean.TRUE);
        }
        Drawable box = grays ? iconBox(view) : null;
        if (box != null) {
            // Like a background, an icon's light box made dark counts as made dark, so its glyph turns light.
            boolean light = swapBox(box, fill, false);
            boolean night = swapBox(box, fill, true);
            if (light || night) view.invalidate();
            if (light) DARKENED.put(view, Boolean.TRUE);
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
            darken(group.getChildAt(index), fill, grays, ink, depth + 1, listed || list, onDarkened && !list);
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

    /**
     * The box a LIVE icon draws behind its glyph, like the picker's delete key's, or null. TikTok's
     * LiveIconView keeps it in its one Drawable field and paints it inside the icon, so it's no
     * background and the view counts as a picture.
     */
    static Drawable iconBox(View view) {
        if (!(view instanceof ImageView)) return null;
        for (Class<?> type = view.getClass(); type != null && type != ImageView.class; type = type.getSuperclass()) {
            if (!"LiveIconView".equals(type.getSimpleName())) continue;
            Field field;
            synchronized (ICON_BOX_FIELDS) {
                if (ICON_BOX_FIELDS.containsKey(type)) {
                    field = ICON_BOX_FIELDS.get(type);
                } else {
                    field = boxField(type);
                    ICON_BOX_FIELDS.put(type, field);
                }
            }
            if (field == null) return null;
            try {
                return (Drawable) field.get(view);
            } catch (IllegalAccessException | RuntimeException failure) {
                return null;
            }
        }
        return null;
    }

    /**
     * Gives an icon's own box the fill when it's light, or with [grays] a night gray. A fill the swap
     * knows is swapped as usual. TikTok's drawn box (X.0zD9 on 47.9.1) sets its paint's color on
     * every draw, so it's read where it paints, at its middle, and a match there takes the fill as a
     * filter.
     */
    static boolean swapBox(Drawable box, int fill, boolean grays) {
        if (box == null) return false;
        if (box instanceof ColorDrawable || box instanceof GradientDrawable || box instanceof LayerDrawable
                || box instanceof DrawableContainer || box instanceof BitmapDrawable) {
            return swapFill(box, fill, grays);
        }
        if (TINTED.containsKey(box) || KEPT_BOXES.containsKey(box)) return false;
        int painted;
        try {
            painted = paintedAt(box);
        } catch (RuntimeException unreadable) {
            // One that can't be drawn in software, such as a hardware bitmap, keeps its look.
            KEPT_BOXES.put(box, Boolean.TRUE);
            return false;
        }
        // Not drawn yet (no bounds, or no color before its first layout): looked at again on the next.
        if (Color.alpha(painted) == 0) return false;
        if (Color.alpha(painted) != 0xFF || !swappable(painted, fill, grays)) {
            // Only the full look settles it: a night gray box passed over for light ones is still open.
            if (grays) KEPT_BOXES.put(box, Boolean.TRUE);
            return false;
        }
        box.mutate();
        box.setColorFilter(new PorterDuffColorFilter(fill | 0xFF000000, PorterDuff.Mode.SRC_IN));
        TINTED.put(box, Boolean.TRUE);
        return true;
    }

    /** True when an icon's own light box was made dark here, so its glyph is judged on its own. */
    static boolean onBoxMadeDark(View view) {
        return DARKENED.containsKey(view) && iconBox(view) != null;
    }

    /** The color a drawable paints at the middle of its bounds, or 0 when it has none yet. */
    static int paintedAt(Drawable drawable) {
        android.graphics.Rect bounds = drawable.getBounds();
        if (bounds.isEmpty()) return 0;
        Bitmap pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(pixel);
            canvas.translate(-bounds.exactCenterX(), -bounds.exactCenterY());
            drawable.draw(canvas);
            return pixel.getPixel(0, 0);
        } finally {
            pixel.recycle();
        }
    }

    private static Field boxField(Class<?> type) {
        Field found = null;
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() != Drawable.class || Modifier.isStatic(field.getModifiers())) continue;
            if (found != null) return null;
            found = field;
        }
        if (found == null) return null;
        try {
            found.setAccessible(true);
            return found;
        } catch (RuntimeException failure) {
            return null;
        }
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
     * one, a plain white picture. With [grays], the opaque near-black grays take it too. True when
     * something changed.
     */
    static boolean swapFill(Drawable background, int fill, boolean grays) {
        if (background == null) return false;
        if (background instanceof DrawableContainer) {
            // Its states can share their drawables with other views' copies until it's mutated.
            Drawable current = background.mutate().getCurrent();
            return current != background && swapFill(current, fill, grays);
        }
        if (background instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) background;
            boolean changed = false;
            for (int index = 0; index < layers.getNumberOfLayers(); index++) {
                if (layers.getId(index) != android.R.id.mask) changed |= swapFill(layers.getDrawable(index), fill, grays);
            }
            return changed;
        }
        if (background instanceof BitmapDrawable) {
            // The picker's panel behind delete and send is a white picture with a soft glow (#329).
            if (TINTED.containsKey(background) || !whitePicture(((BitmapDrawable) background).getBitmap())) return false;
            background.mutate();
            background.setColorFilter(new PorterDuffColorFilter(fill | 0xFF000000, PorterDuff.Mode.SRC_IN));
            TINTED.put(background, Boolean.TRUE);
            return true;
        }
        if (background instanceof ColorDrawable) {
            ColorDrawable solid = (ColorDrawable) background;
            if (!swappable(solid.getColor(), fill, grays)) return false;
            solid.mutate();
            solid.setColor(onFill(solid.getColor(), fill));
            return true;
        }
        if (!(background instanceof GradientDrawable) || Build.VERSION.SDK_INT < 24) return false;
        GradientDrawable shape = (GradientDrawable) background;
        ColorStateList colors = shape.getColor();
        if (colors != null) {
            if (!swappable(colors.getDefaultColor(), fill, grays)) return false;
            shape.mutate();
            shape.setColor(onFill(colors.getDefaultColor(), fill));
            return true;
        }
        int[] stops = shape.getColors();
        if (stops == null) return false;
        boolean swapped = false;
        for (int stop : stops) swapped |= swappable(stop, fill, grays);
        if (!swapped) return false;
        for (int index = 0; index < stops.length; index++) {
            int stop = stops[index];
            if (gray(stop) && (LivePanelTheme.isLight(stop) || (grays && nightGray(stop)))) stops[index] = onFill(stop, fill);
        }
        shape.mutate();
        shape.setColors(stops);
        return true;
    }

    /**
     * True for a background that shows a ground of its own under a view's ink: a light fill the
     * swap left alone, a picture, or anything drawn. Clear, dark and see-through fills aren't one,
     * so the bar's icons on their faint pill still count as the panel's, and neither is a picture
     * drawn in the panel's fill.
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
        if (background instanceof BitmapDrawable) return !TINTED.containsKey(background);
        return true;
    }

    /**
     * True for a picture that's a plain white or light gray ground: at least a third of it opaque
     * enough to be one, and every such part a light gray. Photos, colored art and thin glyphs
     * aren't.
     */
    static boolean whitePicture(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return false;
        Boolean known = WHITE_PICTURES.get(bitmap);
        if (known != null) return known;
        boolean white = readWhite(bitmap);
        WHITE_PICTURES.put(bitmap, white);
        return white;
    }

    private static boolean readWhite(Bitmap bitmap) {
        // A hardware picture's pixels can't be read.
        if (Build.VERSION.SDK_INT >= 26 && bitmap.getConfig() == Bitmap.Config.HARDWARE) return false;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return false;
        int ground = 0;
        for (int column = 0; column < SAMPLES; column++) {
            for (int row = 0; row < SAMPLES; row++) {
                int color = bitmap.getPixel((2 * column + 1) * width / (2 * SAMPLES), (2 * row + 1) * height / (2 * SAMPLES));
                if (Color.alpha(color) < MIN_FILL_ALPHA) continue;
                if (!whiteFill(color)) return false;
                ground++;
            }
        }
        return ground * 3 >= SAMPLES * SAMPLES;
    }

    private static boolean lightFill(int color) {
        return Color.alpha(color) >= MIN_FILL_ALPHA && LivePanelTheme.isLight(color);
    }

    private static boolean whiteFill(int color) {
        return lightFill(color) && gray(color);
    }

    /** A white or light gray ground, or with [grays] an opaque night gray that isn't the fill already. */
    private static boolean swappable(int color, int fill, boolean grays) {
        return whiteFill(color) || (grays && Color.alpha(color) == 0xFF && gray(color) && nightGray(color)
                && (color & 0x00FFFFFF) != (fill & 0x00FFFFFF));
    }

    private static boolean nightGray(int color) {
        return Math.max(Color.red(color), Math.max(Color.green(color), Color.blue(color))) < NIGHT_GRAY_CEILING;
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

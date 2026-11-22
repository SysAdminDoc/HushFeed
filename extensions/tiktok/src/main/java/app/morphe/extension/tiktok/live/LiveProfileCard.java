/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * The card a tap on a name opens in a LIVE room (#487): its colors and which of its rows show.
 *
 * <p>Rows. The card is a list of cells, each one of TikTok's card modules, and its containers ask
 * each cell to bind and lay out only the ones that answer yes. The patch hands every answer to
 * {@link #keep} with the cell. A cell's module is a constant of TikTok's own module enum, read
 * through {@link #componentOf}, and those constant names (LIVE_EVENT, GROUP_CHAT and the rest) are
 * kept by name, so a hidden row is matched by the name TikTok gave it. A hidden row's view is
 * taken off the card and the container leaves it out of its spacing.
 *
 * <p>Colors. {@link LivePanelTheme#darkLivePanel} hands each LIVE panel over first. For the card,
 * while a color is picked, the card's own surface, the outermost fill as wide as its window, and
 * any layer of the same fill as wide as it, take the background, and the text drawn straight on
 * them takes the text color, white or black when that's left on Automatic, whichever stands out.
 * Anything with a fill of its own (label chips, the event box, badge tiles, the group chat row,
 * buttons) keeps TikTok's fill and its own text color, so it reads the way TikTok drew it on any
 * background. Icons keep their own colors. The card then skips Dark LIVE panels.
 */
public final class LiveProfileCard {
    /** TikTok's profile card sheet, a name it keeps. */
    static final String CARD = "com.bytedance.android.livesdk.profile.ui.LiveProfileSheet";
    /** The card modules a switch can take off, by TikTok's names for them. */
    static final String[] ROWS = {"LIVE_EVENT", "LIVE_PRODUCTS", "ECOM", "SHOP_EXPERIENCE_SCORE",
            "GROUP_CHAT", "BULLETIN_BOARD", "GIFT_GALLERY", "FEST_HONORS"};
    private static final int SETTLE_TRIES = 5;
    private static final long SETTLE_DELAY_MS = 100;
    private static final int MAX_TREE_DEPTH = 64;
    /** A fill at least this opaque is one; below it the view is see-through. */
    private static final int MIN_FILL_ALPHA = 0x10;
    /** How much of the window's width the card's surface spans. */
    private static final float SURFACE_SHARE = 0.9f;
    private static final int NO_FILL = 0;
    private static final int OTHER_FILL = 1;
    private static final int SOLID_FILL = 2;

    /** Card roots already watched, so a card isn't hooked twice. */
    private static final Map<View, Boolean> WATCHED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Each card root's surface and the fill it had before it was colored. */
    private static final Map<View, View> SURFACES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Integer> ORIGINAL_FILLS = Collections.synchronizedMap(new WeakHashMap<>());
    /** The views colored as the card's surface, the outermost and the layers like it. */
    private static final Map<View, Boolean> PAINTED = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean rowFailureLogged;
    private static volatile boolean paintFailureLogged;

    private LiveProfileCard() {
    }

    static boolean liveControls() {
        return SettingsStatus.liveControlsEnabled && !Setting.isPaused();
    }

    /** The switch for a card module, or null for a module no switch takes off. */
    static BooleanSetting rowSetting(String component) {
        if (component == null) return null;
        switch (component) {
            case "LIVE_EVENT": return Settings.HIDE_LIVE_CARD_EVENTS;
            case "LIVE_PRODUCTS": return Settings.HIDE_LIVE_CARD_PRODUCTS;
            case "ECOM": return Settings.HIDE_LIVE_CARD_SHOP;
            case "SHOP_EXPERIENCE_SCORE": return Settings.HIDE_LIVE_CARD_SHOP_RATING;
            case "GROUP_CHAT": return Settings.HIDE_LIVE_CARD_GROUP_CHAT;
            case "BULLETIN_BOARD": return Settings.HIDE_LIVE_CARD_BULLETIN_BOARD;
            case "GIFT_GALLERY": return Settings.HIDE_LIVE_CARD_GIFT_GALLERY;
            case "FEST_HONORS": return Settings.HIDE_LIVE_CARD_FEST_HONORS;
            default: return null;
        }
    }

    /**
     * Called by each of the card's containers right after it asks a cell to bind, with the cell
     * and its answer, non-zero for yes. Answers whether the cell lays out: no for a cell that said
     * no, and no for a module whose switch is on, whose view then goes. Anything that fails keeps
     * TikTok's answer.
     */
    public static boolean keep(Object cell, int shown) {
        if (shown == 0) return false;
        if (!liveControls()) return true;
        try {
            if (!hidden(componentOf(cell))) return true;
            Object view = cellView(cell);
            if (view instanceof View) ((View) view).setVisibility(View.GONE);
            return false;
        } catch (Throwable failure) {
            if (!rowFailureLogged) {
                rowFailureLogged = true;
                Logger.printException(() -> "Could not check a LIVE profile card row", failure);
            }
            return true;
        }
    }

    /** Whether [component], a cell's card module, is one whose switch is on. */
    static boolean hidden(Object component) {
        if (!(component instanceof Enum)) return false;
        BooleanSetting hide = rowSetting(((Enum<?>) component).name());
        return hide != null && hide.get();
    }

    /** The cell's card module, a constant of TikTok's module enum. The patch writes it; unpatched, null. */
    static Object componentOf(Object cell) {
        return null;
    }

    /** The cell's own view on the card. The patch writes it; unpatched, null. */
    static Object cellView(Object cell) {
        return null;
    }

    /** The picked background, or 0 for TikTok's. */
    static int background() {
        switch (Settings.LIVE_CARD_BACKGROUND.get()) {
            case "black": return 0xFF000000;
            case "dark_gray": return 0xFF262626;
            case "light_gray": return 0xFFE6E6E6;
            case "white": return 0xFFFFFFFF;
            default: return 0;
        }
    }

    /** The picked text color, or 0 for automatic. */
    static int text() {
        switch (Settings.LIVE_CARD_TEXT.get()) {
            case "white": return 0xFFFFFFFF;
            case "light_gray": return 0xFFC8C8C8;
            case "black": return 0xFF000000;
            case "yellow": return 0xFFFFD60A;
            default: return 0;
        }
    }

    /** Whether a color is picked for the card. */
    static boolean colorsPicked() {
        return liveControls() && (background() != 0 || text() != 0);
    }

    /** White on a dark fill, black on a light one. */
    static int contrast(int fill) {
        double luminance = (0.299 * Color.red(fill) + 0.587 * Color.green(fill) + 0.114 * Color.blue(fill)) / 255;
        return luminance < 0.5 ? 0xFFFFFFFF : 0xFF000000;
    }

    static boolean isCard(Object panel) {
        for (Class<?> type = panel.getClass(); type != null; type = type.getSuperclass()) {
            if (CARD.equals(type.getName())) return true;
        }
        return false;
    }

    /**
     * Called with each themed LIVE panel as it asks for its inflater. True when it's the profile
     * card and a color is picked: the card is colored here from its first layout on, and Dark
     * LIVE panels leaves it as TikTok draws it.
     */
    public static boolean colorCard(Object panel) {
        try {
            if (panel == null || !isCard(panel) || !colorsPicked()) return false;
            watch(panel, 0);
            return true;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not color a LIVE profile card", failure);
            return false;
        }
    }

    private static void watch(Object panel, int attempt) {
        View root = root(panel);
        if (root == null) {
            if (attempt < SETTLE_TRIES) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> watch(panel, attempt + 1), SETTLE_DELAY_MS);
            }
            return;
        }
        if (WATCHED.put(root, Boolean.TRUE) != null) return;
        paint(root);
        // The cells bind as the card's data comes in, so each layout gets the colors again.
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                paint(root);
            }
        });
    }

    /** The card's window, or its own view before it has one. */
    static View root(Object panel) {
        Object dialog = Reflect.invoke(panel, "getDialog");
        Window window = dialog instanceof Dialog ? ((Dialog) dialog).getWindow() : null;
        if (window != null) return window.getDecorView();
        Object view = Reflect.invoke(panel, "getView");
        return view instanceof View ? (View) view : null;
    }

    /** Colors the card under [root]: its surface, the layers like it and the text on them. */
    static void paint(View root) {
        try {
            if (!colorsPicked()) return;
            View surface = surface(root);
            if (surface == null) return;
            Integer original = ORIGINAL_FILLS.get(surface);
            if (original == null) {
                original = solidColor(surface.getBackground());
                if (original == null) return;
                ORIGINAL_FILLS.put(surface, original);
            }
            int fill = background();
            int card = fill != 0 ? fill : original;
            int ink = text() != 0 ? text() : contrast(card);
            paintTree(surface, surface, original, fill, ink, 0);
        } catch (Throwable failure) {
            if (!paintFailureLogged) {
                paintFailureLogged = true;
                Logger.printException(() -> "Could not color a LIVE profile card", failure);
            }
        }
    }

    private static void paintTree(View view, View surface, int original, int fill, int ink, int depth) {
        if (view == null || depth > MAX_TREE_DEPTH) return;
        if (view == surface || PAINTED.containsKey(view)) {
            paintFill(view, fill);
        } else {
            int kind = fillKind(view.getBackground());
            Integer color = solidColor(view.getBackground());
            if (kind == SOLID_FILL && color != null && color == original
                    && view.getWidth() >= surface.getWidth() - 1) {
                paintFill(view, fill);
            } else if (kind != NO_FILL) {
                // A fill of its own: it and everything on it keep TikTok's colors.
                return;
            }
        }
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getCurrentTextColor() != ink) text.setTextColor(ink);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                paintTree(group.getChildAt(i), surface, original, fill, ink, depth + 1);
            }
        }
    }

    private static void paintFill(View view, int fill) {
        PAINTED.put(view, Boolean.TRUE);
        if (fill == 0) return;
        Drawable background = view.getBackground();
        Integer now = solidColor(background);
        if (now == null || now == fill) return;
        // A shape's state is shared with every view drawn from it, so it's copied first.
        Drawable own = background.mutate();
        if (own instanceof GradientDrawable) {
            ((GradientDrawable) own).setColor(fill);
        } else if (own instanceof ColorDrawable) {
            ((ColorDrawable) own).setColor(fill);
        }
    }

    /**
     * The card's surface: the outermost shown view as wide as most of the window with a solid
     * fill of its own, or the one found before.
     */
    static View surface(View root) {
        View known = SURFACES.get(root);
        if (known != null && known.isAttachedToWindow()) return known;
        if (root.getWidth() <= 0) return null;
        View found = findSurface(root, root.getWidth() * SURFACE_SHARE, 0);
        if (found != null) SURFACES.put(root, found);
        return found;
    }

    private static View findSurface(View view, float width, int depth) {
        if (view.getVisibility() != View.VISIBLE || depth > MAX_TREE_DEPTH) return null;
        if (view.getWidth() >= width && fillKind(view.getBackground()) == SOLID_FILL) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findSurface(group.getChildAt(i), width, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** No fill, a solid one (a color or a colored shape) or some other picture. */
    static int fillKind(Drawable drawable) {
        if (drawable == null) return NO_FILL;
        if (drawable instanceof ColorDrawable) {
            return Color.alpha(((ColorDrawable) drawable).getColor()) < MIN_FILL_ALPHA ? NO_FILL : SOLID_FILL;
        }
        if (drawable instanceof GradientDrawable) {
            Integer color = solidColor(drawable);
            return color == null || Color.alpha(color) < MIN_FILL_ALPHA ? NO_FILL : SOLID_FILL;
        }
        if (drawable instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) drawable;
            // A bare ripple only shows a touch.
            return ripple.getNumberOfLayers() == 0 ? NO_FILL : fillKind(ripple.getDrawable(0));
        }
        if (drawable instanceof DrawableContainer) {
            return fillKind(drawable.getCurrent());
        }
        if (drawable instanceof InsetDrawable) {
            return fillKind(((InsetDrawable) drawable).getDrawable());
        }
        return OTHER_FILL;
    }

    /** A color or colored shape's color, null for anything else. */
    static Integer solidColor(Drawable drawable) {
        if (drawable instanceof ColorDrawable) return ((ColorDrawable) drawable).getColor();
        if (drawable instanceof GradientDrawable && Build.VERSION.SDK_INT >= 24) {
            android.content.res.ColorStateList colors = ((GradientDrawable) drawable).getColor();
            return colors != null ? colors.getDefaultColor() : null;
        }
        return null;
    }

    static void resetForTests() {
        WATCHED.clear();
        SURFACES.clear();
        ORIGINAL_FILLS.clear();
        PAINTED.clear();
        rowFailureLogged = false;
        paintFailureLogged = false;
    }
}

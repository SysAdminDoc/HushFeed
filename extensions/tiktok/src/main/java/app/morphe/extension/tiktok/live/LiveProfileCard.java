/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.app.Dialog;
import android.content.res.ColorStateList;
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
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * while a color is picked, the card's own surface, the outermost fill as wide as its window (a
 * color, a colored shape or a gradient that reads as one color) or else the nearest one drawn
 * behind the card, and any layer of the same fill as wide as it, take the background, and the text drawn straight on
 * them takes the text color, white or black when that's left on Automatic, whichever stands out.
 * Gray color spans in that text (the bio's) take it too, at their own strength. Anything with a
 * fill of its own (label chips, the event box, badge tiles, the group chat row, buttons), and
 * anything drawn over a fill beside it (a chip's label), keeps TikTok's fill and its own text
 * color, so it reads the way TikTok drew it on any background, a button tinted from its view
 * included. A thin fill (the level chip) and a round button that is only a faint tint over the
 * card take the color they showed on TikTok's card. Icons keep their own colors. The card then
 * skips Dark LIVE panels. TikTok keeps a room's card for each time it opens, so once nothing is
 * picked or Hushfeed is paused, the card gets back each fill, text color and span it had.
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
    /** How far a gradient's stops may drift, per channel, and still read as one color. */
    private static final int MAX_GRADIENT_SPREAD = 0x20;
    private static final int NO_FILL = 0;
    private static final int OTHER_FILL = 1;
    private static final int SOLID_FILL = 2;

    /** Card roots already watched, so a card isn't hooked twice. */
    private static final Map<View, Boolean> WATCHED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Each card root's surface and the fill it had before it was colored. */
    private static final Map<View, View> SURFACES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, Integer> ORIGINAL_FILLS = Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * The views colored as the card's surface, the outermost and the layers like it, with their
     * own fill: a gradient's stops, or one color.
     */
    private static final Map<View, int[]> PAINTED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Thin fills given the color they showed on TikTok's card, with their own. */
    private static final Map<View, Integer> THIN_FILLS = Collections.synchronizedMap(new WeakHashMap<>());
    /** Texts given the text color, with their own colors. */
    private static final Map<TextView, ColorStateList> TEXT_COLORS = Collections.synchronizedMap(new WeakHashMap<>());
    /** Texts whose gray spans were given the text color. */
    private static final Map<TextView, Boolean> INKED_TEXTS = Collections.synchronizedMap(new WeakHashMap<>());
    /** The spans put in for TikTok's gray ones, with each gray span's color. */
    private static final Map<ForegroundColorSpan, Integer> SPAN_COLORS = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean rowFailureLogged;
    private static volatile boolean paintFailureLogged;
    private static volatile boolean surfaceMissLogged;

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
            if (!colorsPicked()) {
                restore(root);
                return;
            }
            View surface = surface(root);
            if (surface == null) {
                if (!surfaceMissLogged) {
                    surfaceMissLogged = true;
                    Logger.printDebug(() -> "LIVE profile card: no surface around " + root.getClass().getName());
                }
                return;
            }
            Integer original = ORIGINAL_FILLS.get(surface);
            if (original == null) {
                original = solidColor(surface.getBackground());
                if (original == null) return;
                ORIGINAL_FILLS.put(surface, original);
            }
            int fill = background();
            int card = fill != 0 ? fill : original;
            int ink = text() != 0 ? text() : contrast(card);
            paintTree(scope(surface, root), surface, original, fill, ink, 0);
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
            paintFill(view, fill, original);
        } else {
            int kind = fillKind(view);
            Integer color = solidColor(view.getBackground());
            if (kind == SOLID_FILL && color != null && color == original
                    && view.getWidth() >= surface.getWidth() - 1) {
                paintFill(view, fill, original);
            } else if (kind != NO_FILL) {
                // A fill of its own: it and everything on it keep TikTok's colors. One laid thin
                // over the card takes the color it showed on TikTok's own, or its label would sit
                // on whatever the new card makes of it (the gifter level chip, #487).
                if (fill != 0 && color != null && Color.alpha(color) < 0xFF) thinFill(view, color, original);
                return;
            } else if (fill != 0 && isTintedButton(view, color, surface)) {
                // TikTok's round buttons are a faint black over the card, gray only on white,
                // so each takes the gray it showed there and keeps its icon (#487).
                thinFill(view, color, original);
                return;
            }
        }
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getCurrentTextColor() != ink) {
                TEXT_COLORS.put(text, text.getTextColors());
                text.setTextColor(ink);
            }
            inkSpans(text, ink);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                // Drawn over a fill beside it, a chip's label keeps TikTok's colors like one inside it.
                if (overSiblingFill(group, i)) continue;
                paintTree(group.getChildAt(i), surface, original, fill, ink, depth + 1);
            }
        }
    }

    /**
     * A small view holding others whose fill is a faint tint, too faint to count as a fill of its
     * own: TikTok's round buttons (5% black on 47.9.3).
     */
    private static boolean isTintedButton(View view, Integer color, View surface) {
        return color != null && Color.alpha(color) > 0 && view instanceof ViewGroup
                && ((ViewGroup) view).getChildCount() > 0 && view.getWidth() < surface.getWidth() - 1;
    }

    /** Gives a thin [color] the opaque color it showed over TikTok's [original] card. */
    private static void thinFill(View view, int color, int original) {
        if (!THIN_FILLS.containsKey(view)) THIN_FILLS.put(view, color);
        setFill(view, over(color, original));
    }

    /**
     * Puts back what the card in [root]'s window had before it was colored, once nothing is
     * picked or Hushfeed is paused. TikTok keeps a room's card for each time it opens, so the
     * colors would otherwise stay.
     */
    static void restore(View root) {
        View window = root.getRootView();
        for (View view : keys(PAINTED)) {
            if (view.getRootView() != window) continue;
            int[] own = PAINTED.remove(view);
            if (own != null) putBack(view, own);
        }
        for (View view : keys(THIN_FILLS)) {
            if (view.getRootView() != window) continue;
            Integer own = THIN_FILLS.remove(view);
            if (own != null) setFill(view, own);
        }
        for (TextView text : keys(TEXT_COLORS)) {
            if (text.getRootView() != window) continue;
            ColorStateList own = TEXT_COLORS.remove(text);
            if (own != null) text.setTextColor(own);
        }
        for (TextView text : keys(INKED_TEXTS)) {
            if (text.getRootView() != window) continue;
            INKED_TEXTS.remove(text);
            swapSpans(text, SPAN_COLORS::get);
        }
    }

    private static <K> List<K> keys(Map<K, ?> map) {
        synchronized (map) {
            return new ArrayList<>(map.keySet());
        }
    }

    /** [tint] drawn over the opaque [base], as one opaque color. */
    static int over(int tint, int base) {
        int alpha = Color.alpha(tint);
        return Color.rgb(
                (Color.red(tint) * alpha + Color.red(base) * (255 - alpha)) / 255,
                (Color.green(tint) * alpha + Color.green(base) * (255 - alpha)) / 255,
                (Color.blue(tint) * alpha + Color.blue(base) * (255 - alpha)) / 255);
    }

    /** Whether an earlier child of [group], drawn before child [index], has a fill under its middle. */
    private static boolean overSiblingFill(ViewGroup group, int index) {
        View child = group.getChildAt(index);
        int x = (child.getLeft() + child.getRight()) / 2;
        int y = (child.getTop() + child.getBottom()) / 2;
        for (int i = 0; i < index; i++) {
            View behind = group.getChildAt(i);
            if (behind.getVisibility() != View.VISIBLE || PAINTED.containsKey(behind)) continue;
            if (x < behind.getLeft() || x >= behind.getRight() || y < behind.getTop() || y >= behind.getBottom()) continue;
            if (fillKind(behind) != NO_FILL) return true;
        }
        return false;
    }

    /**
     * The text's own gray color spans take the text color at their own strength, since they beat
     * the view's color: the bio is TikTok's 46% black span (#487). A span of a real color, a link
     * or a mention, keeps it. A text that can't change in place is set once as one that can, and
     * TikTok's later texts for the view then come in that way too.
     */
    static void inkSpans(TextView view, int ink) {
        if (swapSpans(view, span -> inked(span, ink))) INKED_TEXTS.put(view, Boolean.TRUE);
    }

    private interface SpanColor {
        /** The color [span] takes, or null to leave it. */
        Integer of(ForegroundColorSpan span);
    }

    /**
     * Swaps each of [view]'s color spans that [recolor] gives a color for, keeping each first
     * span's color for {@link #restore}. Answers whether any changed.
     */
    private static boolean swapSpans(TextView view, SpanColor recolor) {
        CharSequence text = view.getText();
        if (!(text instanceof Spanned)) return false;
        Spanned spanned = (Spanned) text;
        Spannable target = null;
        for (ForegroundColorSpan span : spanned.getSpans(0, spanned.length(), ForegroundColorSpan.class)) {
            Integer color = recolor.of(span);
            if (color == null) continue;
            if (target == null) target = text instanceof Spannable ? (Spannable) text : new SpannableString(text);
            int start = target.getSpanStart(span);
            if (start < 0) continue;
            int end = target.getSpanEnd(span);
            int flags = target.getSpanFlags(span);
            Integer first = SPAN_COLORS.remove(span);
            ForegroundColorSpan swapped = new ForegroundColorSpan(color);
            target.removeSpan(span);
            target.setSpan(swapped, start, end, flags);
            if (color != (first != null ? first : span.getForegroundColor())) {
                SPAN_COLORS.put(swapped, first != null ? first : span.getForegroundColor());
            }
        }
        if (target == null) return false;
        if (target != text) view.setText(target, TextView.BufferType.SPANNABLE);
        return true;
    }

    /** The color a plain gray span takes, or null for one to leave: a real color, or already inked. */
    private static Integer inked(ForegroundColorSpan span, int ink) {
        if (span.getClass() != ForegroundColorSpan.class) return null;
        int color = span.getForegroundColor();
        int high = Math.max(Color.red(color), Math.max(Color.green(color), Color.blue(color)));
        int low = Math.min(Color.red(color), Math.min(Color.green(color), Color.blue(color)));
        if (high - low > MAX_GRADIENT_SPREAD) return null;
        int target = (ink & 0x00FFFFFF) | (color & 0xFF000000);
        return target == color ? null : target;
    }

    private static void paintFill(View view, int fill, int original) {
        if (!PAINTED.containsKey(view)) PAINTED.put(view, look(view, original));
        if (fill == 0) return;
        setFill(view, fill);
    }

    /** [view]'s fill as it is: its gradient's stops, else [color]. */
    private static int[] look(View view, int color) {
        Drawable background = view.getBackground();
        int[] stops = background instanceof GradientDrawable ? stops((GradientDrawable) background) : null;
        return stops != null ? stops.clone() : new int[] {color};
    }

    private static void putBack(View view, int[] look) {
        if (look.length == 1) {
            setFill(view, look[0]);
            return;
        }
        Drawable background = view.getBackground();
        if (background == null) return;
        Drawable own = background.mutate();
        if (own instanceof GradientDrawable) ((GradientDrawable) own).setColors(look.clone());
    }

    private static void setFill(View view, int fill) {
        Drawable background = view.getBackground();
        Integer now = solidColor(background);
        if (now == null || now == fill) return;
        // A shape's state is shared with every view drawn from it, so it's copied first.
        Drawable own = background.mutate();
        if (own instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) own;
            // A gradient keeps the shader it last drew with through setColor, so it becomes a
            // gradient of one color instead, which builds a new one.
            if (stops(shape) != null) shape.setColors(new int[] {fill, fill});
            else shape.setColor(fill);
        } else if (own instanceof ColorDrawable) {
            ((ColorDrawable) own).setColor(fill);
        }
    }

    /**
     * The card's surface: the outermost shown view under [root] as wide as most of the window
     * with a solid fill of its own, else the nearest such view drawn behind [root], or the one
     * found before. On 47.9.3 the sheet draws the surface beside the card's own view, under the
     * same parent, so the card's view holds none (#487).
     */
    static View surface(View root) {
        View known = SURFACES.get(root);
        if (known != null && known.isAttachedToWindow()) return known;
        if (root.getWidth() <= 0) return null;
        float width = Math.max(root.getWidth(), root.getRootView().getWidth()) * SURFACE_SHARE;
        View found = findSurface(root, width, 0);
        if (found == null) found = findBackdrop(root, width);
        if (found != null) SURFACES.put(root, found);
        return found;
    }

    /**
     * The nearest surface behind [root]: under each parent in turn, an earlier child, drawn
     * before it and level with its top, or else the parent's own fill.
     */
    private static View findBackdrop(View root, float width) {
        View child = root;
        ViewParent parent = root.getParent();
        for (int depth = 0; parent instanceof ViewGroup && depth < MAX_TREE_DEPTH; depth++) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = group.indexOfChild(child) - 1; i >= 0; i--) {
                View behind = group.getChildAt(i);
                if (behind.getTop() > child.getTop() || behind.getBottom() <= child.getTop()) continue;
                View found = findSurface(behind, width, 0);
                if (found != null) return found;
            }
            if (group.getWidth() >= width && fillKind(group.getBackground()) == SOLID_FILL) return group;
            child = group;
            parent = group.getParent();
        }
        return null;
    }

    /**
     * Where painting starts: the surface when the card is drawn in it, else the nearest view
     * holding both, so the text on the card's view takes the colors too. A surface with nothing
     * in it is a backdrop, and what's drawn on it is beside it, under its parent.
     */
    static View scope(View surface, View root) {
        if (within(root, surface)) return surface;
        if (within(surface, root)) {
            boolean holds = surface instanceof ViewGroup && ((ViewGroup) surface).getChildCount() > 0;
            ViewParent parent = surface.getParent();
            return holds || !(parent instanceof View) ? surface : (View) parent;
        }
        for (ViewParent parent = surface.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (within(root, (View) parent)) return (View) parent;
        }
        return surface;
    }

    private static boolean within(View view, View ancestor) {
        if (view == ancestor) return true;
        for (ViewParent parent = view.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (parent == ancestor) return true;
        }
        return false;
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

    /**
     * [view]'s fill, its background tint included: TikTok's Join Fan Club button is a shape with
     * no color of its own that the view tints red (#487).
     */
    static int fillKind(View view) {
        Drawable background = view.getBackground();
        int kind = fillKind(background);
        if (kind != NO_FILL || background == null) return kind;
        ColorStateList tint = view.getBackgroundTintList();
        if (tint == null) return NO_FILL;
        int color = tint.getColorForState(view.getDrawableState(), tint.getDefaultColor());
        return Color.alpha(color) < MIN_FILL_ALPHA ? NO_FILL : OTHER_FILL;
    }

    /** No fill, a solid one (a color or a colored shape) or some other picture. */
    static int fillKind(Drawable drawable) {
        if (drawable == null) return NO_FILL;
        if (drawable instanceof ColorDrawable) {
            return Color.alpha(((ColorDrawable) drawable).getColor()) < MIN_FILL_ALPHA ? NO_FILL : SOLID_FILL;
        }
        if (drawable instanceof GradientDrawable) {
            Integer color = solidColor(drawable);
            if (color != null) return Color.alpha(color) < MIN_FILL_ALPHA ? NO_FILL : SOLID_FILL;
            // A gradient that shows is a picture of its own; a shape with no color draws nothing.
            int[] stops = stops((GradientDrawable) drawable);
            if (stops == null) return NO_FILL;
            for (int stop : stops) {
                if (Color.alpha(stop) >= MIN_FILL_ALPHA) return OTHER_FILL;
            }
            return NO_FILL;
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

    /**
     * A color or colored shape's color, the color of a gradient that reads as one (the sheet's
     * #f8f8f8 to white on 47.9.3), null for anything else.
     */
    static Integer solidColor(Drawable drawable) {
        if (drawable instanceof ColorDrawable) return ((ColorDrawable) drawable).getColor();
        if (drawable instanceof GradientDrawable && Build.VERSION.SDK_INT >= 24) {
            GradientDrawable shape = (GradientDrawable) drawable;
            // A shape draws its gradient over its color when it has both.
            int[] stops = stops(shape);
            if (stops != null) return evenColor(stops);
            android.content.res.ColorStateList colors = shape.getColor();
            return colors != null ? colors.getDefaultColor() : null;
        }
        return null;
    }

    /** A shape's gradient stops, null when it has none or the system can't say. */
    private static int[] stops(GradientDrawable shape) {
        if (Build.VERSION.SDK_INT < 24) return null;
        int[] stops = shape.getColors();
        return stops != null && stops.length > 0 ? stops : null;
    }

    /**
     * The average of [stops] when every channel stays within {@link #MAX_GRADIENT_SPREAD} across
     * them, so the gradient reads as one color; null for one that shows.
     */
    static Integer evenColor(int[] stops) {
        int[] low = {255, 255, 255, 255};
        int[] high = new int[4];
        int[] sum = new int[4];
        for (int stop : stops) {
            int[] channels = {Color.alpha(stop), Color.red(stop), Color.green(stop), Color.blue(stop)};
            for (int c = 0; c < 4; c++) {
                low[c] = Math.min(low[c], channels[c]);
                high[c] = Math.max(high[c], channels[c]);
                sum[c] += channels[c];
            }
        }
        for (int c = 0; c < 4; c++) {
            if (high[c] - low[c] > MAX_GRADIENT_SPREAD) return null;
        }
        int n = stops.length;
        return Color.argb(sum[0] / n, sum[1] / n, sum[2] / n, sum[3] / n);
    }

    static void resetForTests() {
        WATCHED.clear();
        SURFACES.clear();
        ORIGINAL_FILLS.clear();
        PAINTED.clear();
        THIN_FILLS.clear();
        TEXT_COLORS.clear();
        INKED_TEXTS.clear();
        SPAN_COLORS.clear();
        rowFailureLogged = false;
        paintFailureLogged = false;
        surfaceMissLogged = false;
    }
}

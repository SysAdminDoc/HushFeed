/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.ImageView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** The theme LIVE panels inflate with while Dark LIVE panels is on in dark mode (#159). */
@SuppressWarnings("unused")
public final class LivePanelTheme {
    /** The base of every themed LIVE dialog (LiveDialogFragment and the rest). */
    static final String THEMED_DIALOG = "com.bytedance.android.live.design.resource.theme.LiveThemeAwareDialogFragment";
    /** The base of LIVE's sheet pages: the fan club sheets and most newer LIVE panels. */
    static final String SHEET = "com.bytedance.android.live.design.view.sheet.LiveSheetFragment";
    /** The base of every themed LIVE fragment: sheet pages, the room's own and the ranking's pages. */
    static final String THEMED_FRAGMENT = "com.bytedance.android.live.design.resource.theme.LiveThemeAwareFragment";
    /** The daily ranking, whose sheet backgrounds are fixed light drawables it sets itself. */
    static final String RANK_LIST = "com.bytedance.android.live.rank.impl.list.RankListDialog";
    /** The trending ranking's first-time guide over the daily ranking, drawn on a fixed light image. */
    static final String RANK_GUIDE = "com.bytedance.android.live.rank.impl.list.view.PopularityRankingEducationDialog";
    private static final Set<String> STOCK_PANELS = new HashSet<>(Arrays.asList(RANK_LIST, RANK_GUIDE));

    private static final int OTHER = 0;
    private static final int PANEL = 1;
    private static final int STOCK = 2;
    /** A themed LIVE fragment that isn't a panel, which its own inflater hook already sees. */
    private static final int THEMED = 3;
    /** How far up a page's parent fragments the walk looks for a stock panel. */
    private static final int MAX_PARENTS = 8;
    private static final Map<Class<?>, Integer> PANEL_TYPES = new ConcurrentHashMap<>();
    /** Set while a stock panel asks TikTok's own provider answer for its overlay. */
    private static final ThreadLocal<Boolean> STOCK_ANSWER = new ThreadLocal<>();
    /**
     * Set once a stock panel has inflated with the switch on. Until then no fragment can sit under
     * one, so the fragments the rest of the app inflates skip the walk up their parents.
     */
    static volatile boolean stockSeen;
    /** Each darkened room's theme as it was before TikTok's dark app theme went on. */
    private static final Map<Activity, Resources.Theme> LIGHT_ROOMS = Collections.synchronizedMap(new WeakHashMap<>());
    /** Set once a panel has been darkened, so until then no fragment walks up its parents for one. */
    static volatile boolean panelSeen;
    /** Each darkened panel with the dark context its views inflated through. */
    private static final Map<Object, Context> DARK_PANELS = Collections.synchronizedMap(new WeakHashMap<>());
    /** The profile card's label color when the server's is missing or unreadable: color/aj, #0F161823, on 47.4.3. */
    private static final int LABEL_DEFAULT = 0x0F161823;
    /**
     * The event box's fill on the dark card: white at 16%, about the card's own tiles there
     * (#47484E over its #262626, Login2, 2026-10-20).
     */
    private static final int EVENT_BOX = 0x29FFFFFF;
    /** How far into the event box the light text check looks. */
    private static final int MAX_BOX_DEPTH = 3;
    /** The navigation bar under a darkened panel. */
    static final int NAV_BAR = 0xFF161616;
    /** The fill that replaces a light one on a darkened panel, about the dark card's own tiles. */
    static final int DARK_FILL = 0xFF2A2A2E;
    /** What the fan club strip's translucent black unselected tab becomes. */
    static final int DARK_TAB = 0x14FFFFFF;
    /** A fill is only swapped when it's at least this opaque, so the card's translucent tiles stay. */
    private static final int MIN_FILL_ALPHA = 0x80;
    /** Class name pieces of the panels whose own fixed light fills get swapped, the profile card and the fan club. */
    private static final String[] FILL_PANELS = {".fansclub.", ".superfan.", ".livesdk.profile."};
    private static final int LIGHT_NAVIGATION_BAR_FLAG = 0x10;
    private static final int SETTLE_TRIES = 5;
    private static final long SETTLE_DELAY_MS = 100;
    /**
     * How many times the long-press menu is looked for on screen, SETTLE_DELAY_MS apart: the room
     * shows it right away, or after the dialog it's queued behind.
     */
    private static final int MENU_TRIES = 30;
    /** How deep the fill and ink walks go, past any real panel's tree. */
    private static final int MAX_TREE_DEPTH = 64;
    /** How far apart a gray ink's channels may be: TikTok's #161823 text is 13. */
    private static final int MAX_GRAY_SPREAD = 0x30;
    /** A pixel at least this opaque is ink, below it the icon's clear ground. */
    private static final int MIN_INK_ALPHA = 0x10;
    /** How far an icon's pixels may stray from its first one and still be one ink. */
    private static final int MAX_INK_DRIFT = 0x20;
    /** The longest side an icon is drawn at to judge it. */
    private static final float SAMPLE_SIZE = 48f;
    /** How many frames a still blank icon is looked at before it's left as it is. */
    private static final int MAX_EMPTY_SAMPLES = 10;
    /** Draws an icon's ink white, its see-through edges kept. */
    private static final ColorFilter WHITE_INK = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
    /** Views already watched, so a panel isn't hooked twice. */
    private static final Map<View, Boolean> WATCHED = Collections.synchronizedMap(new WeakHashMap<>());
    /** Views, such as the profile card's label strip, that keep their light look on purpose. */
    private static final Map<View, Boolean> KEEP_LIGHT = Collections.synchronizedMap(new WeakHashMap<>());
    /** The profile card's badge tiles, image views whose dark backgrounds the fill pass counts as fills. */
    private static final Map<View, Boolean> BADGE_TILES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ImageView, IconCheck> ICONS = Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean frameFailureLogged;
    private static volatile boolean overlayFailureLogged;
    private static volatile boolean rowFailureLogged;
    private static volatile boolean viewContextFailureLogged;
    private static volatile boolean sparkFailureLogged;
    private static volatile boolean bubbleFailureLogged;

    private LivePanelTheme() {
    }

    /**
     * Called with each layout inflater a themed LIVE dialog or LIVE fragment hands its views,
     * with the dialog or fragment. TikTok gives a LIVE panel its light colors by inflating it
     * through its light LIVE theme, applied over the room's own with force. The dark one it only
     * adds to the room's theme without force, so every color the room's light theme already set
     * wins and the panel stays white, which is what the S25 showed with only the provider
     * answering dark (#159). While Dark LIVE panels is on and the panel's context is in dark
     * mode, the panel's views inflate through its own theme overlay, which the provider hook
     * makes TikTok's dark LIVE theme, applied with force the way TikTok applies the light one. A
     * room's own fragments aren't panels and keep TikTok's inflater, and anything that fails
     * hands TikTok's back.
     * A stock panel, and any fragment under one, inflates through the overlay TikTok's own
     * provider answer gives the stock panel instead, its light LIVE theme in a room, applied with
     * force the way TikTok applies it in a dark app. Left with TikTok's inflater, the provider
     * hook's dark answer kept TikTok from adding any LIVE theme, so the room's dark app theme
     * turned the daily ranking's tab titles white on its fixed white sheet (Login2, 2026-10-18).
     */
    public static LayoutInflater darkLivePanel(Object panel, LayoutInflater inflater) {
        if (panel == null || inflater == null) {
            return inflater;
        }
        try {
            Context context = inflater.getContext();
            if (!LiveControls.darkLivePanels(context)) {
                return inflater;
            }
            Object stock = stockPanel(panel);
            if (stock != null) {
                stockSeen = true;
                return stockInflater(inflater, context, stockOverlay(stock, context));
            }
            if (!isPanel(panel.getClass())) {
                return inflater;
            }
            int overlay = themeOverlay(panel, context);
            if (overlay == 0) {
                return inflater;
            }
            settle(panel);
            Context dark = new ContextThemeWrapper(context, overlay);
            DARK_PANELS.put(panel, dark);
            panelSeen = true;
            return inflater.cloneInContext(dark);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not darken a LIVE panel", failure);
            return inflater;
        }
    }

    /**
     * Darkens the pieces of a dark panel its theme overlay can't reach (#159): the navigation
     * bar under its window, and for the profile card and the fan club the fixed light fills their
     * views paint themselves, with the dark text and icons drawn over those fills turned light.
     * The views don't exist yet when the inflater is asked for, so the fills are swapped on each
     * layout of the panel's window until it goes. TikTok's sheets put the light navigation bar
     * back after the panel inflates, and the fan club recolors its tab labels as the pick
     * changes, so the bar and the ink are checked again before each frame (S22, 2026-10-23).
     */
    static void settle(Object panel) {
        settle(panel, 0);
    }

    private static void settle(Object panel, int attempt) {
        try {
            Object dialog = Reflect.invoke(panel, "getDialog");
            Window window = dialog instanceof android.app.Dialog ? ((android.app.Dialog) dialog).getWindow() : null;
            View root = window != null ? window.getDecorView() : null;
            if (root == null) {
                Object view = Reflect.invoke(panel, "getView");
                root = view instanceof View ? (View) view : null;
            }
            if (root == null) {
                if (attempt < SETTLE_TRIES) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> settle(panel, attempt + 1), SETTLE_DELAY_MS);
                }
                return;
            }
            if (window != null) {
                darkNavigationBar(window);
            }
            boolean fills = fillPanel(panel.getClass());
            if ((window == null && !fills) || WATCHED.put(root, Boolean.TRUE) != null) {
                return;
            }
            View watched = root;
            Window under = window;
            // The text and icons on a swapped fill, found again on each layout.
            List<View> ink = new ArrayList<>();
            ViewTreeObserver observer = watched.getViewTreeObserver();
            if (fills) {
                darkFills(watched, ink);
                observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        try {
                            darkFills(watched, ink);
                        } catch (Throwable failure) {
                            Logger.printException(() -> "Could not darken a LIVE panel's fills", failure);
                        }
                    }
                });
            }
            observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    try {
                        if (under != null) {
                            darkNavigationBar(under);
                        }
                        for (View view : ink) {
                            lightInk(view);
                        }
                    } catch (Throwable failure) {
                        if (!frameFailureLogged) {
                            frameFailureLogged = true;
                            Logger.printException(() -> "Could not keep a LIVE panel dark", failure);
                        }
                    }
                    return true;
                }
            });
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not settle a LIVE panel", failure);
        }
    }

    /**
     * Called with LIVE's long-press menu (share, report and the rest) as TikTok builds it for a
     * room, just before the room shows it. The menu is a plain dialog, the same one a video's
     * long press opens, not one of LIVE's themed panels, so nothing reached its window and its
     * navigation bar stayed light under the dark menu (S22, 2026-10-24). While Dark LIVE panels
     * is on in dark mode the bar is painted dark once the menu shows, and again before each
     * frame, since the menu sets its window up again as it shows. The window's views are left
     * alone until then: building them early would keep the menu from asking for its window
     * features.
     */
    public static void liveMenu(android.app.Dialog dialog) {
        liveMenu(dialog, 0);
    }

    private static void liveMenu(android.app.Dialog dialog, int attempt) {
        try {
            if (dialog == null || !LiveControls.darkLivePanels(dialog.getContext())) {
                return;
            }
            Window window = dialog.getWindow();
            if (window == null) {
                return;
            }
            View root = window.peekDecorView();
            if (root == null || !dialog.isShowing()) {
                if (attempt < MENU_TRIES) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> liveMenu(dialog, attempt + 1),
                            attempt == 0 ? 0 : SETTLE_DELAY_MS);
                }
                return;
            }
            darkNavigationBar(window);
            if (WATCHED.put(root, Boolean.TRUE) != null) {
                return;
            }
            Logger.printDebug(() -> "Darkened the LIVE menu's navigation bar");
            root.getViewTreeObserver().addOnPreDrawListener(() -> {
                try {
                    darkNavigationBar(window);
                } catch (Throwable failure) {
                    if (!frameFailureLogged) {
                        frameFailureLogged = true;
                        Logger.printException(() -> "Could not keep the LIVE menu's navigation bar dark", failure);
                    }
                }
                return true;
            });
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not darken the LIVE menu's navigation bar", failure);
        }
    }

    /** True for the panels whose own fixed light fills get swapped, by their class names. */
    static boolean fillPanel(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            String name = current.getName();
            for (String piece : FILL_PANELS) {
                if (name.contains(piece)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Takes the light navigation bar flag off a panel window and paints the bar dark. TikTok
     * targets a version where the bar's color is ignored and the flag alone picks the icon
     * shade, so a bottom sheet kept the light gray scrim under it (S22, 2026-10-23). It runs
     * before every frame of the panel, so it only writes what TikTok changed back.
     */
    static void darkNavigationBar(Window window) {
        if (window.getNavigationBarColor() != NAV_BAR) {
            window.setNavigationBarColor(NAV_BAR);
        }
        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT >= 26) {
            int flags = decor.getSystemUiVisibility();
            if ((flags & LIGHT_NAVIGATION_BAR_FLAG) != 0) {
                decor.setSystemUiVisibility(flags & ~LIGHT_NAVIGATION_BAR_FLAG);
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null && (controller.getSystemBarsAppearance() & WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS) != 0) {
                controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }
    }

    /**
     * Swaps the light fills under the view for dark ones: solid and gradient backgrounds, a card
     * view's color and the paints of a custom drawn view such as the fan club's tab strip. Images,
     * layered badge tiles and anything marked to stay light are left alone, and so are fills
     * that are mostly see-through. The text and icons drawn over a dark fill, now or from an
     * earlier pass, are gathered into the ink list and turned light.
     */
    static void darkFills(View root, List<View> ink) {
        List<View> filled = new ArrayList<>();
        darkFills(root, filled, 0);
        ink.clear();
        for (View view : filled) {
            gatherInk(view, ink);
        }
        for (View view : ink) {
            lightInk(view);
        }
    }

    private static void darkFills(View view, List<View> filled, int depth) {
        if (view == null || KEEP_LIGHT.containsKey(view) || depth > MAX_TREE_DEPTH) {
            return;
        }
        boolean dark = fillHolder(view) && darkBackground(view);
        dark |= darkCard(view);
        dark |= darkPaints(view);
        if (dark) {
            filled.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                darkFills(group.getChildAt(i), filled, depth + 1);
            }
        }
    }

    /**
     * True for a view whose background is a fill: anything but an image, plus an image view
     * showing no picture (the fan club's FANS card is one, S22 2026-10-24) and a badge tile.
     */
    private static boolean fillHolder(View view) {
        return !(view instanceof ImageView) || ((ImageView) view).getDrawable() == null
                || BADGE_TILES.containsKey(view);
    }

    private static boolean lightFill(int color) {
        return Color.alpha(color) >= MIN_FILL_ALPHA && isLight(color);
    }

    /**
     * Swaps a light solid or gradient background, or such layers of a layered one; true when the
     * view's background is, or holds, the dark fill.
     */
    private static boolean darkBackground(View view) {
        Drawable background = view.getBackground();
        if (background instanceof LayerDrawable && !(background instanceof RippleDrawable)) {
            LayerDrawable layers = (LayerDrawable) background;
            boolean dark = false;
            boolean changed = false;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                Drawable layer = layers.getDrawable(i);
                if (layer instanceof ColorDrawable) {
                    int color = ((ColorDrawable) layer).getColor();
                    if (lightFill(color)) {
                        layer.mutate();
                        ((ColorDrawable) layer).setColor(DARK_FILL);
                        changed = true;
                    }
                    dark |= ((ColorDrawable) layer).getColor() == DARK_FILL;
                } else if (layer instanceof GradientDrawable && Build.VERSION.SDK_INT >= 24) {
                    ColorStateList colors = ((GradientDrawable) layer).getColor();
                    if (colors != null && lightFill(colors.getDefaultColor())) {
                        layer.mutate();
                        ((GradientDrawable) layer).setColor(DARK_FILL);
                        changed = true;
                    }
                    colors = ((GradientDrawable) layer).getColor();
                    dark |= colors != null && colors.getDefaultColor() == DARK_FILL;
                }
            }
            if (changed) {
                view.invalidate();
            }
            return dark;
        }
        if (background instanceof ColorDrawable) {
            int color = ((ColorDrawable) background).getColor();
            if (lightFill(color)) {
                view.setBackground(new ColorDrawable(DARK_FILL));
                return true;
            }
            return color == DARK_FILL;
        }
        if (background instanceof GradientDrawable && Build.VERSION.SDK_INT >= 24) {
            ColorStateList colors = ((GradientDrawable) background).getColor();
            if (colors != null && lightFill(colors.getDefaultColor())) {
                ((GradientDrawable) background).mutate();
                ((GradientDrawable) background).setColor(DARK_FILL);
                return true;
            }
            return colors != null && colors.getDefaultColor() == DARK_FILL;
        }
        return false;
    }

    /**
     * A card view keeps its fill outside the background, so it's read and written by its own
     * methods; true when the card's fill is the dark one.
     */
    private static boolean darkCard(View view) {
        if (!"CardView".equals(view.getClass().getSimpleName()) && !hasSuperName(view.getClass(), "CardView")) {
            return false;
        }
        Object colors = Reflect.invoke(view, "getCardBackgroundColor");
        if (!(colors instanceof ColorStateList)) {
            return false;
        }
        int color = ((ColorStateList) colors).getDefaultColor();
        if (lightFill(color)) {
            try {
                view.getClass().getMethod("setCardBackgroundColor", int.class).invoke(view, DARK_FILL);
                return true;
            } catch (ReflectiveOperationException failure) {
                Logger.printException(() -> "Could not darken a LIVE card view", failure);
                return false;
            }
        }
        return color == DARK_FILL;
    }

    private static boolean hasSuperName(Class<?> type, String simpleName) {
        for (Class<?> current = type.getSuperclass(); current != null; current = current.getSuperclass()) {
            if (simpleName.equals(current.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A view that draws with paints of its own, like the fan club's tab strip, gets its light
     * paints swapped; true when one of its paints is a dark one.
     */
    private static boolean darkPaints(View view) {
        if (view instanceof ViewGroup || view instanceof TextView || view instanceof ImageView) {
            return false;
        }
        Class<?> type = view.getClass();
        if (type.getName().startsWith("android.")) {
            return false;
        }
        boolean changed = false;
        boolean dark = false;
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() != Paint.class || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Paint paint = (Paint) field.get(view);
                if (paint == null) {
                    continue;
                }
                int color = paint.getColor();
                if (lightFill(color)) {
                    paint.setColor(DARK_FILL);
                    changed = true;
                } else if (Color.alpha(color) < MIN_FILL_ALPHA && Color.alpha(color) > 0 && !isLight(color)) {
                    paint.setColor(DARK_TAB);
                    changed = true;
                }
                dark |= paint.getColor() == DARK_FILL || paint.getColor() == DARK_TAB;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                Logger.printException(() -> "Could not darken a LIVE tab strip", failure);
            }
        }
        if (changed) {
            view.invalidate();
        }
        return dark;
    }

    /**
     * Gathers the text and icons drawn over a dark filled view: its own, and those of the views
     * drawn after it that cover it. The fan club's tab labels are one of those, an overlay beside
     * the strip that paints the tabs, so they read black on the dark tab (S22, 2026-10-23).
     */
    private static void gatherInk(View filled, List<View> ink) {
        if (filled instanceof TextView && !ink.contains(filled)) {
            ink.add(filled);
        }
        if (filled instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) filled;
            for (int i = 0; i < group.getChildCount(); i++) {
                gatherInk(group.getChildAt(i), null, ink, 0);
            }
        }
        Rect area = treeRect(filled);
        View current = filled;
        for (int depth = 0; depth <= MAX_TREE_DEPTH && current.getParent() instanceof ViewGroup; depth++) {
            ViewGroup parent = (ViewGroup) current.getParent();
            for (int i = parent.indexOfChild(current) + 1; i < parent.getChildCount(); i++) {
                gatherInk(parent.getChildAt(i), area, ink, 0);
            }
            current = parent;
        }
    }

    /**
     * Gathers the text and icon views under the view, those covering the area when one is given.
     * A view on a backdrop of its own, light or drawn, keeps the ink it was picked for, and so
     * does anything marked to stay light.
     */
    private static void gatherInk(View view, Rect area, List<View> ink, int depth) {
        if (view.getVisibility() != View.VISIBLE || KEEP_LIGHT.containsKey(view) || depth > MAX_TREE_DEPTH) {
            return;
        }
        if ((area != null && !Rect.intersects(area, treeRect(view))) || ownBackdrop(view.getBackground())) {
            return;
        }
        if (view instanceof TextView || view instanceof ImageView) {
            if (!ink.contains(view)) {
                ink.add(view);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                gatherInk(group.getChildAt(i), area, ink, depth + 1);
            }
        }
    }

    /**
     * Where the view sits in its tree, from its parents' positions, scrolls and shifts. The
     * window's own answer is 0,0 for every view until the panel is attached, which would make
     * every view cover every other.
     */
    private static Rect treeRect(View view) {
        float left = 0;
        float top = 0;
        View current = view;
        while (true) {
            left += current.getLeft() + current.getTranslationX();
            top += current.getTop() + current.getTranslationY();
            if (!(current.getParent() instanceof View)) {
                break;
            }
            current = (View) current.getParent();
            left -= current.getScrollX();
            top -= current.getScrollY();
        }
        int x = Math.round(left);
        int y = Math.round(top);
        return new Rect(x, y, x + view.getWidth(), y + view.getHeight());
    }

    /**
     * True for a background that shows something of its own under a view's ink: anything but a
     * clear or dark solid or gradient, a ripple with nothing but its mask, or a state list
     * showing one of those.
     */
    static boolean ownBackdrop(Drawable background) {
        if (background == null) {
            return false;
        }
        if (background instanceof DrawableContainer) {
            Drawable current = background.getCurrent();
            return current != background && ownBackdrop(current);
        }
        if (background instanceof ColorDrawable) {
            return !darkBackdrop(((ColorDrawable) background).getColor());
        }
        if (background instanceof GradientDrawable && Build.VERSION.SDK_INT >= 24) {
            GradientDrawable shape = (GradientDrawable) background;
            ColorStateList color = shape.getColor();
            if (color != null) {
                return !darkBackdrop(color.getDefaultColor());
            }
            int[] stops = shape.getColors();
            if (stops == null) {
                return false;
            }
            for (int stop : stops) {
                if (!darkBackdrop(stop)) {
                    return true;
                }
            }
            return false;
        }
        if (background instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) background;
            for (int i = 0; i < ripple.getNumberOfLayers(); i++) {
                if (ripple.getId(i) != android.R.id.mask) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean darkBackdrop(int color) {
        return Color.alpha(color) == 0 || (Color.alpha(color) >= MIN_FILL_ALPHA && !isLight(color));
    }

    /**
     * Turns a view's dark gray ink light on a dark fill: a text color keeps its see-through
     * amount, and an icon of one dark ink on a clear ground is drawn white. Colored text, photos
     * and colored art keep their look. It runs before every frame, so it only writes what TikTok
     * changed back.
     */
    static void lightInk(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            int color = text.getCurrentTextColor();
            if (darkInk(color)) {
                text.setTextColor(lightened(color));
            }
        } else if (view instanceof ImageView) {
            lightIcon((ImageView) view);
        }
    }

    /** True for a gray ink darker than mid gray, the kind a light panel draws its text and icons in. */
    static boolean darkInk(int color) {
        if (Color.alpha(color) == 0 || isLight(color)) {
            return false;
        }
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) <= MAX_GRAY_SPREAD;
    }

    /** White with the ink's own see-through amount. */
    static int lightened(int color) {
        return (color & 0xFF000000) | 0x00FFFFFF;
    }

    private static void lightIcon(ImageView image) {
        Drawable drawable = image.getDrawable();
        ColorFilter filter = image.getColorFilter();
        IconCheck seen = ICONS.get(image);
        if (seen != null && seen.shows(drawable, filter)) {
            return;
        }
        if (drawable == null) {
            ICONS.put(image, new IconCheck(null, filter, 0));
            return;
        }
        if (filter == WHITE_INK) {
            // A new image under the white ink, judged as TikTok draws it.
            image.clearColorFilter();
        }
        int tries = seen != null && seen.drawable == null ? seen.emptyTries + 1 : 0;
        int[] pixels;
        try {
            pixels = pixelsOf(image);
        } catch (RuntimeException unreadable) {
            // A hardware bitmap can't be drawn in software: it keeps its look and isn't tried again.
            pixels = null;
            tries = MAX_EMPTY_SAMPLES;
        }
        if ((pixels == null || inked(pixels) == 0) && tries < MAX_EMPTY_SAMPLES) {
            // Not drawn yet: looked at again on the next frames, a few times.
            ICONS.put(image, new IconCheck(null, image.getColorFilter(), tries));
            return;
        }
        if (pixels != null && darkMonochrome(pixels)) {
            image.setColorFilter(WHITE_INK);
        }
        ICONS.put(image, new IconCheck(image.getDrawable(), image.getColorFilter(), tries));
    }

    /**
     * What an icon showed when last judged, so an unchanged one isn't drawn again each frame. No
     * drawable means it was still blank and gets looked at again. The drawable is held weakly,
     * since TikTok's can reach back to the view this is kept for.
     */
    private static final class IconCheck {
        final WeakReference<Drawable> drawable;
        final ColorFilter filter;
        final int emptyTries;

        IconCheck(Drawable drawable, ColorFilter filter, int emptyTries) {
            this.drawable = drawable == null ? null : new WeakReference<>(drawable);
            this.filter = filter;
            this.emptyTries = emptyTries;
        }

        boolean shows(Drawable current, ColorFilter currentFilter) {
            return drawable != null && drawable.get() == current && filter == currentFilter;
        }
    }

    /** The view drawn small into a bitmap, its pixels, or null when it has no size yet. */
    private static int[] pixelsOf(View view) {
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        float scale = Math.min(1f, SAMPLE_SIZE / (float) Math.max(width, height));
        int sampleWidth = Math.max(1, Math.round(width * scale));
        int sampleHeight = Math.max(1, Math.round(height * scale));
        Bitmap bitmap = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale);
            view.draw(canvas);
            int[] pixels = new int[sampleWidth * sampleHeight];
            bitmap.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight);
            return pixels;
        } finally {
            bitmap.recycle();
        }
    }

    private static int inked(int[] pixels) {
        int inked = 0;
        for (int pixel : pixels) {
            if (Color.alpha(pixel) >= MIN_INK_ALPHA) {
                inked++;
            }
        }
        return inked;
    }

    /**
     * True for an icon's pixels: one dark gray ink with clear pixels around it, the way a light
     * panel's chevrons and tab labels are drawn. A photo has no clear pixels and colored art has
     * more than one ink, so both stay as they are.
     */
    static boolean darkMonochrome(int[] pixels) {
        int clear = 0;
        int first = 0;
        boolean inked = false;
        for (int pixel : pixels) {
            if (Color.alpha(pixel) < MIN_INK_ALPHA) {
                clear++;
                continue;
            }
            if (!darkInk(pixel)) {
                return false;
            }
            if (!inked) {
                inked = true;
                first = pixel;
            } else if (Math.abs(Color.red(pixel) - Color.red(first)) > MAX_INK_DRIFT
                    || Math.abs(Color.green(pixel) - Color.green(first)) > MAX_INK_DRIFT
                    || Math.abs(Color.blue(pixel) - Color.blue(first)) > MAX_INK_DRIFT) {
                return false;
            }
        }
        return inked && clear > 0;
    }

    /**
     * Called with the layout inflater every fragment takes from its host, with the fragment. A
     * plain fragment takes the room activity's inflater even under a LIVE panel, so the daily
     * ranking's lists took the room's dark app theme and their rows read white on the ranking's
     * white sheet (Login2, 2026-10-19). While Dark LIVE panels is on in dark mode, a plain
     * fragment under a stock panel inflates through the overlay that panel gets. The same goes for
     * a dark panel: the moderator settings' muted and blocked lists are plain fragments under it,
     * and over the room's light LIVE colors their names read dark on the dark sheet (#362), so a
     * plain fragment under a panel darkLivePanel darkened inflates through that panel's dark
     * context. LIVE's themed fragments and dialogs have their own hook, and every other fragment
     * keeps TikTok's inflater. Every fragment in the app comes through here, so nothing is looked
     * up before a stock panel has inflated or a panel was darkened.
     */
    public static LayoutInflater darkLivePage(Object fragment, LayoutInflater inflater) {
        try {
            if ((!stockSeen && !panelSeen) || fragment == null || inflater == null
                    || panelType(fragment.getClass()) != OTHER) {
                return inflater;
            }
            if (stockSeen) {
                LayoutInflater stock = darkLivePanel(fragment, inflater);
                if (stock != inflater) {
                    return stock;
                }
            }
            return panelSeen ? darkPanelPage(fragment, inflater) : inflater;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not check a fragment for a LIVE panel", failure);
            return inflater;
        }
    }

    /**
     * The inflater of a plain fragment under a panel darkLivePanel darkened: one on that panel's
     * dark context. The walk stops at a stock panel, which keeps its light look, and at a panel
     * that wasn't darkened; anything else hands TikTok's inflater back.
     */
    private static LayoutInflater darkPanelPage(Object fragment, LayoutInflater inflater) {
        Object current = Reflect.invoke(fragment, "getParentFragment");
        for (int depth = 0; current != null && depth < MAX_PARENTS; depth++) {
            int kind = panelType(current.getClass());
            if (kind == STOCK) {
                return inflater;
            }
            if (kind == PANEL) {
                Context dark = DARK_PANELS.get(current);
                if (dark == null || !LiveControls.darkLivePanels(inflater.getContext())) {
                    return inflater;
                }
                return inflater.cloneInContext(dark);
            }
            current = Reflect.invoke(current, "getParentFragment");
        }
        return inflater;
    }

    /**
     * Called first in the constructor of the gift guide bubble a LIVE room shows over its gift
     * button ("Support this LIVE"), with the context it inflates its views through. TikTok draws the
     * bubble white, and its text takes the color the room's theme gives text by default, which over
     * a room the room hook darkened is the dark app theme's white, so the text was white on white
     * (#362). While Dark LIVE panels is on in dark mode, the bubble inflates over the room's theme as
     * it was before the dark app theme went on, as TikTok shows it in light mode. A room the hook
     * never darkened, and anything that fails, keeps TikTok's context.
     */
    public static Context lightRoomView(Context context) {
        try {
            if (context == null || !LiveControls.darkLivePanels(context)) {
                return context;
            }
            Resources.Theme light = lightRoom(context);
            if (light == null) {
                return context;
            }
            Resources.Theme theme = context.getResources().newTheme();
            theme.setTo(light);
            return new ContextThemeWrapper(context, theme);
        } catch (Throwable failure) {
            if (!bubbleFailureLogged) {
                bubbleFailureLogged = true;
                Logger.printException(() -> "Could not give a LIVE room's gift guide its light theme", failure);
            }
            return context;
        }
    }

    /**
     * Called by the LIVE room activity's setTheme right after its last room theme. The room
     * ignores the theme it's handed and builds its own out of light styles applied with force,
     * each sitting on TikTok's light app theme, so TikTok's generic sheets over a room (Share, a
     * business room's Contact sheet) open white in a dark app, and the panel overlay never
     * reaches them (#159). While Dark LIVE panels is on and TikTok itself is in dark mode,
     * TikTok's dark app theme goes on over them with force, as TikTok themes its other screens
     * in dark mode. The room's color scheme styles still apply after it.
     * Also called after the room's onConfigurationChanged: the room handles a dark mode flip
     * itself, and AppCompat re-applies the room's last light style with force after its setTheme,
     * so Share opened white in a room left open while the system theme flipped (S22, 2026-10-23).
     */
    public static void darkLiveRoom(Activity room) {
        darkLiveRoom(room, darkAppTheme());
    }

    static void darkLiveRoom(Activity room, int theme) {
        if (room == null) {
            return;
        }
        try {
            if (theme == 0 || !LiveControls.darkLivePanels(room)) {
                return;
            }
            // Only the first pass is light: a later setTheme puts the room's styles back over the
            // dark theme, so a second copy would keep whatever they leave alone dark.
            synchronized (LIGHT_ROOMS) {
                if (!LIGHT_ROOMS.containsKey(room)) {
                    Resources.Theme light = room.getResources().newTheme();
                    light.setTo(room.getTheme());
                    LIGHT_ROOMS.put(room, light);
                }
            }
            room.getTheme().applyStyle(theme, true);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not darken a LIVE room", failure);
        }
    }

    /**
     * The style TikTok gives its screens in dark mode, 0 when unknown. The patch rewrites this to
     * return the style it read from TikTok's own dark mode code.
     */
    static int darkAppTheme() {
        return 0;
    }

    /**
     * Called by the LIVE profile card with the color it picked for a translated bio's "See
     * original" link and the bio's view. The card's theme already gives the dark theme's light
     * link color, but the link is drawn through a replacement span, which skips color spans and
     * paints with the view's own text color, the light theme's dark one, so the link read dark
     * on the dark card (#159). While Dark LIVE panels is on in dark mode, the view's text color
     * becomes the link color. The bio's own text keeps the color its span gives it.
     */
    public static void bioLinkColor(int color, Object bio) {
        try {
            if (bio instanceof TextView && LiveControls.darkLivePanels(((TextView) bio).getContext())) {
                ((TextView) bio).setTextColor(color);
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not color a LIVE bio's link", failure);
        }
    }

    /**
     * Called by the LIVE profile card with its labels and the strip that draws them, before it
     * builds them: the shop rating and sold count chips among them. The server sends each label's
     * colors as they look on the light card, a faint see-through chip under dark text and a dark
     * icon, so on the dark card they read dark on dark (Login2, 2026-10-19). While Dark LIVE
     * panels is on in dark mode, each label drawn on a color gets that color as it shows over the
     * light card, so it stays the light chip its own text and icon were picked for. A label drawn
     * on an image keeps it. Only the card's own labels are touched, by TikTok's class name.
     */
    public static void cardLabels(Object labels, Object strip) {
        try {
            if (!(labels instanceof Iterable) || !(strip instanceof View)
                    || !LiveControls.darkLivePanels(((View) strip).getContext())) {
                return;
            }
            // The chips stay light on purpose, so the fill pass leaves the strip alone.
            KEEP_LIGHT.put((View) strip, Boolean.TRUE);
            for (Object label : (Iterable<?>) labels) {
                if (label != null && "ProfileCardLabel".equals(label.getClass().getSimpleName())) {
                    lightLabel(label);
                }
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not keep a LIVE profile card's labels readable", failure);
        }
    }

    /** Gives the label's background its color as it shows over the light card, a new one when it has none. */
    static void lightLabel(Object label) throws ReflectiveOperationException {
        if (label == null) {
            return;
        }
        // A static field would ignore the label, and a static final one refuses the write once
        // TikTok targets Android 17, so only the label's own fields are written.
        Field field = Reflect.field(label.getClass(), "background");
        if (field == null || Modifier.isStatic(field.getModifiers())) {
            return;
        }
        Object background = field.get(label);
        if (background == null) {
            Constructor<?> make = field.getType().getDeclaredConstructor();
            make.setAccessible(true);
            background = make.newInstance();
            field.set(label, background);
        }
        // TikTok draws backgroundDarkMode's image instead only in a right-to-left layout, which
        // this doesn't check: a label with no image of its own there keeps the color it gets here.
        if (Reflect.readField(background, "image") != null) {
            return;
        }
        Field color = Reflect.field(background.getClass(), "colorCode");
        if (color != null && !Modifier.isStatic(color.getModifiers())) {
            color.set(background, onLightCard((String) color.get(background)));
        }
    }

    /**
     * The label color as it shows over the light card's white, opaque, the way TikTok's color
     * parse reads it. Missing or unreadable, TikTok keeps its own default for the card's labels,
     * color/aj on 47.4.3, which has no night variant.
     */
    static String onLightCard(String code) {
        int color = LABEL_DEFAULT;
        if (code != null && !code.isEmpty()) {
            try {
                color = Color.parseColor(code);
            } catch (IllegalArgumentException unreadable) {
                color = LABEL_DEFAULT;
            }
        }
        int alpha = Color.alpha(color);
        int red = (Color.red(color) * alpha + 255 * (255 - alpha)) / 255;
        int green = (Color.green(color) * alpha + 255 * (255 - alpha)) / 255;
        int blue = (Color.blue(color) * alpha + 255 * (255 - alpha)) / 255;
        return String.format(Locale.ROOT, "#FF%02X%02X%02X", red, green, blue);
    }

    /**
     * Called by the LIVE profile card's upcoming event module with its box as it binds the host's
     * next event. The box is a fixed white rounded shape with no night variant, while its title,
     * time and chevron take their colors from the card's theme, so on the dark card the "LIVE
     * event" line read light on white (Login2, 2026-10-20). While Dark LIVE panels is on in dark
     * mode and the box's own text came out light, its shape gets a see-through light fill like the
     * card's own tiles, corners kept. A box whose text is still dark keeps its white. The group
     * chat row ("Chat di gruppo") calls it too, right after it puts the same kind of white shape
     * on itself.
     */
    public static void eventBox(Object box) {
        try {
            if (!(box instanceof ViewGroup) || !LiveControls.darkLivePanels(((View) box).getContext())) {
                return;
            }
            Drawable background = ((View) box).getBackground();
            if (background instanceof GradientDrawable && hasLightText((ViewGroup) box, 0)) {
                // The shape's state is shared with every other view drawn from it, so it's copied first.
                ((GradientDrawable) background.mutate()).setColor(EVENT_BOX);
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not keep a LIVE profile card's event box readable", failure);
        }
    }

    /**
     * Called by the LIVE profile card's badge tiles (the level tile and the ones beside it) in
     * place of each setBackground TikTok gives a tile. The server draws a tile's art and text for
     * the white card and often see-through: the low levels' "Lv." tile is a pale lavender at
     * about a fifth of full strength with gray text at about a third, so on the dark card both
     * went dark gray and the text sank into the tile (Login2, 2026-10-20). Laid over white, the
     * tiles then showed as light blocks on the dark card (S22, 2026-10-24). While Dark LIVE panels
     * is on in dark mode, a solid light tile takes the dark fill in its own shape, border kept, and
     * a see-through one draws over a dark copy of its shape, so it tints the dark tile the way it
     * tints the white one. The tile is marked so the fill pass lights the gray text over it.
     */
    public static void badgeTile(View tile, Drawable background) {
        Drawable shown = background;
        try {
            if (background instanceof GradientDrawable && LiveControls.darkLivePanels(tile.getContext())) {
                Drawable.ConstantState shape = background.getConstantState();
                if (shape != null) {
                    // Copies with their own state, so the tile's shape keeps its fill and border.
                    GradientDrawable base = (GradientDrawable) shape.newDrawable().mutate();
                    base.setColor(DARK_FILL);
                    if (lightShape((GradientDrawable) background)) {
                        shown = base;
                    } else {
                        base.setStroke(0, Color.TRANSPARENT);
                        LayerDrawable layers = new LayerDrawable(new Drawable[]{base, background});
                        // Both layers carry the shape's padding, which nesting would count twice.
                        layers.setPaddingMode(LayerDrawable.PADDING_MODE_STACK);
                        shown = layers;
                    }
                    BADGE_TILES.put(tile, Boolean.TRUE);
                }
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not darken a LIVE profile card badge", failure);
        }
        tile.setBackground(shown);
    }

    /** True for a shape filled with a light, mostly opaque color or gradient. */
    static boolean lightShape(GradientDrawable shape) {
        if (Build.VERSION.SDK_INT < 24) {
            return false;
        }
        ColorStateList color = shape.getColor();
        if (color != null) {
            return lightFill(color.getDefaultColor());
        }
        int[] stops = shape.getColors();
        if (stops == null || stops.length == 0) {
            return false;
        }
        for (int stop : stops) {
            if (!lightFill(stop)) {
                return false;
            }
        }
        return true;
    }

    /** True when a text in the box, at most a few levels down, is drawn in a light color. */
    static boolean hasLightText(ViewGroup box, int depth) {
        for (int i = 0; i < box.getChildCount(); i++) {
            View child = box.getChildAt(i);
            if (child instanceof TextView && isLight(((TextView) child).getCurrentTextColor())) {
                return true;
            }
            if (child instanceof ViewGroup && depth < MAX_BOX_DEPTH && hasLightText((ViewGroup) child, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** True for a color brighter than mid gray, by its perceived brightness. */
    static boolean isLight(int color) {
        return Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114 > 127_500;
    }

    /**
     * True for a themed LIVE dialog or a LIVE sheet page, by TikTok's own class names. A panel
     * that paints its own fixed light backgrounds isn't one, subclasses included, or the dark
     * overlay's light text lands on white: the daily ranking's title and its guide's text (#159).
     */
    static boolean isPanel(Class<?> type) {
        return panelType(type) == PANEL;
    }

    /**
     * The panel itself when it paints its own fixed light backgrounds, else the one it sits under
     * by its parent fragments, or null. The daily ranking's pages and lists are fragments of their
     * own, which take their inflater from the room, not from the ranking's. The walk stops at an
     * ordinary panel, so a dialog opened from the ranking's pages keeps its dark overlay.
     */
    static Object stockPanel(Object panel) {
        Object current = panel;
        for (int depth = 0; current != null && depth <= MAX_PARENTS; depth++) {
            int kind = panelType(current.getClass());
            if (kind == STOCK) {
                return current;
            }
            if (kind == PANEL) {
                return null;
            }
            current = Reflect.invoke(current, "getParentFragment");
        }
        return null;
    }

    private static int panelType(Class<?> type) {
        Integer known = PANEL_TYPES.get(type);
        if (known != null) {
            return known;
        }
        int kind = OTHER;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getName();
            if (STOCK_PANELS.contains(name)) {
                kind = STOCK;
                break;
            }
            if (THEMED_DIALOG.equals(name) || SHEET.equals(name)) {
                kind = PANEL;
                break;
            }
            if (THEMED_FRAGMENT.equals(name)) {
                kind = THEMED;
                break;
            }
        }
        PANEL_TYPES.put(type, kind);
        return kind;
    }

    /**
     * The overlay TikTok's own provider answer gives a stock panel, asked with the panel's own
     * context the way TikTok asks it, while the provider hook stands down. It's 0 when TikTok's
     * answer is the hook's dark one too, so a ranking opened outside a room keeps TikTok's
     * inflater as before.
     */
    static int stockOverlay(Object stock, Context fallback) {
        Object own = Reflect.invoke(stock, "getContext");
        Context context = own instanceof Context ? (Context) own : fallback;
        int overlay;
        STOCK_ANSWER.set(Boolean.TRUE);
        try {
            overlay = themeOverlay(stock, context);
        } finally {
            STOCK_ANSWER.remove();
        }
        return overlay == themeOverlay(stock, context) ? 0 : overlay;
    }

    /**
     * A stock panel's inflater: its light overlay, applied with force over the room's theme as it
     * was before the room hook put TikTok's dark app theme on. Over the darkened room, whatever the
     * overlay leaves alone kept its dark value, so the daily ranking's gray league pill turned
     * white on white (Login2, 2026-10-19). A room the hook never darkened has no light theme
     * kept, and the overlay goes over its own.
     */
    private static LayoutInflater stockInflater(LayoutInflater inflater, Context context, int overlay) {
        Context themed = stockThemed(context, overlay);
        return themed == null ? inflater : inflater.cloneInContext(themed);
    }

    /** The context under a stock panel's light overlay, the one its inflater takes; null with no overlay. */
    private static Context stockThemed(Context context, int overlay) {
        if (overlay == 0) {
            return null;
        }
        Resources.Theme light = lightRoom(context);
        if (light == null) {
            return new StockContext(context, overlay);
        }
        Resources.Theme theme = context.getResources().newTheme();
        theme.setTo(light);
        theme.applyStyle(overlay, true);
        return new StockContext(context, theme);
    }

    /** The light context a stock panel's views inflate with, which the rows of its lists look for. */
    static final class StockContext extends ContextThemeWrapper {
        StockContext(Context base, int theme) {
            super(base, theme);
        }

        StockContext(Context base, Resources.Theme theme) {
            super(base, theme);
        }
    }

    /** True when the context is a stock panel's light one, or one a view wrapped around it. */
    static boolean isStockContext(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof StockContext) {
                return true;
            }
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) {
                return false;
            }
            current = base;
        }
        return false;
    }

    /**
     * Called by LIVE's list row binders before they make a row, with the list, the row's layout and
     * whether it goes straight into the list. The binders skip the inflater their list hands them
     * and inflate rows through LIVE's shared inflater, whose context is the app or the room, so over
     * the darkened room the daily ranking's trending rows took the room's dark app theme and their
     * names read white on the ranking's white sheet (Login2, 2026-10-19). While Dark LIVE panels is
     * on in dark mode, a row for a list a stock panel's light context inflated comes from that
     * context, and the binder makes its holder from it. Every other row gets null and TikTok's own,
     * one TikTok attaches as it inflates included.
     */
    public static View stockRow(ViewGroup list, int layout, Boolean attach) {
        try {
            if (!stockSeen || list == null || layout == 0 || Boolean.TRUE.equals(attach)) {
                return null;
            }
            Context context = list.getContext();
            if (!isStockContext(context) || !LiveControls.darkLivePanels(context)) {
                return null;
            }
            return LayoutInflater.from(context).inflate(layout, list, false);
        } catch (Throwable failure) {
            if (!rowFailureLogged) {
                rowFailureLogged = true;
                Logger.printException(() -> "Could not give a LIVE ranking's row its light theme", failure);
            }
            return null;
        }
    }

    /**
     * Called by the daily ranking's tab strip with its page and the page's getContext(), which it
     * reads the tabs' fallback colors through. That context is the room activity, and TikTok's LIVE
     * color lookup resolves against its theme, so over the darkened room the unpicked tab's gray
     * pill took the dark theme's white on the ranking's white sheet (Login2, 2026-10-19). While
     * Dark LIVE panels is on in dark mode, a page under a stock panel gets the context its own
     * views inflated with, the light one its inflater hook gave it, or one built the same way
     * before it has a view. Everything else gets its context back untouched, a ranking with no
     * light overlay to put back (outside a room, where TikTok's own answer is already dark)
     * included.
     */
    public static Context stockContext(Object page, Context context) {
        try {
            if (!stockSeen || page == null || context == null || !LiveControls.darkLivePanels(context)) {
                return context;
            }
            Object stock = stockPanel(page);
            if (stock == null) {
                return context;
            }
            int overlay = stockOverlay(stock, context);
            if (overlay == 0) {
                return context;
            }
            Object view = Reflect.invoke(page, "getView");
            if (view instanceof View) {
                return ((View) view).getContext();
            }
            Context themed = stockThemed(context, overlay);
            return themed == null ? context : themed;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not give a LIVE ranking's tabs their light theme", failure);
            return context;
        }
    }

    /**
     * Called by a ranking list's bottom row as it puts the info icon after a host's gap text, with
     * the row's text and the context the list handed it, which it loads and tints the icon through.
     * That context is the room activity, so over the darkened room the icon after the trending
     * tab's "99+" row took the dark theme's white on the ranking's white sheet (Login2, 2026-10-19).
     * While Dark LIVE panels is on in dark mode, a row a stock panel's light context inflated gets
     * that context. Every other row gets its context back untouched, and so does a row text that
     * isn't a View: the patch only proves it's what the row read the text from.
     */
    public static Context stockViewContext(Object text, Context context) {
        try {
            if (!stockSeen || !(text instanceof View) || context == null || !LiveControls.darkLivePanels(context)) {
                return context;
            }
            Context own = ((View) text).getContext();
            return isStockContext(own) ? own : context;
        } catch (Throwable failure) {
            if (!viewContextFailureLogged) {
                viewContextFailureLogged = true;
                Logger.printException(() -> "Could not give a LIVE ranking's info icon its light theme", failure);
            }
            return context;
        }
    }

    /**
     * Called first by Spark's light or dark decision for one page (SparkContext.LJIIZILJ on
     * 47.5.3) with the page's SparkContext and the context it was asked with; true answers light
     * before Spark decides. Spark's pages read dark over a room the room hook darkened, which
     * suits them, except a shop room's viewer ranking with its Top customers tab, whose native tab
     * titles kept their light colors on the dark sheet (S22, 2026-10-20). That page asks for the
     * buyers ranking in its schema (can_show_top_buyers_rank=1; the plain viewer ranking, same
     * Lynx bundle, sends 0, emulator 2026-10-23), and only it stays light, only over a darkened
     * room while Dark LIVE panels is on in dark mode.
     */
    public static boolean sparkKeepsStock(Object page, Context context) {
        try {
            if (page == null || !buyersRanking(page)) {
                return false;
            }
            Activity room = sparkRoom(context);
            return room != null && LiveControls.darkLivePanels(room);
        } catch (Throwable failure) {
            if (!sparkFailureLogged) {
                sparkFailureLogged = true;
                Logger.printException(() -> "Could not keep a LIVE room's Spark page stock", failure);
            }
            return false;
        }
    }

    private static final Pattern BUYERS_RANKING = Pattern.compile("[?&]can_show_top_buyers_rank=1(?:&|$)");

    /** Whether a Spark page's schema asks for the shop ranking's Top customers tab. */
    static boolean buyersRanking(Object page) {
        Object bundle = Reflect.readField(page, "schemaBundle");
        Object queries = Reflect.invoke(bundle, "getAllQueries");
        if (queries instanceof Map && ((Map<?, ?>) queries).containsKey("can_show_top_buyers_rank")) {
            return "1".equals(String.valueOf(((Map<?, ?>) queries).get("can_show_top_buyers_rank")));
        }
        for (Object url : new Object[] {Reflect.readField(page, "fullUrl"), Reflect.invoke(bundle, "getUrl")}) {
            if (url instanceof String && BUYERS_RANKING.matcher((String) url).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The darkened room a Spark page opens over: the one under its context, or with no activity
     * under it (Spark asks with none for the theme name its Lynx page reads), a live one the
     * room hook darkened. Null otherwise, a room the hook never darkened included.
     */
    static Activity sparkRoom(Context context) {
        Activity room = roomOf(context);
        if (room != null) {
            return LIGHT_ROOMS.containsKey(room) ? room : null;
        }
        synchronized (LIGHT_ROOMS) {
            for (Activity kept : LIGHT_ROOMS.keySet()) {
                if (kept != null && !kept.isFinishing() && !kept.isDestroyed()) {
                    return kept;
                }
            }
        }
        return null;
    }

    /** The light theme kept for the room activity under the context, or null. */
    static Resources.Theme lightRoom(Context context) {
        Activity room = roomOf(context);
        return room == null ? null : LIGHT_ROOMS.get(room);
    }

    /** The activity under the context, or null. */
    static Activity roomOf(Context context) {
        Context current = context;
        while (current != null) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            if (!(current instanceof ContextWrapper)) {
                return null;
            }
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) {
                return null;
            }
            current = base;
        }
        return null;
    }

    /** True while a stock panel asks TikTok's own provider answer, which the hook then leaves alone. */
    static boolean askingStock() {
        return STOCK_ANSWER.get() != null;
    }

    /**
     * The panel's own LIVE theme overlay (getThemeOverlay), 0 when it has none. A panel that
     * names its own overlay gets that one again, so only the default one turns dark.
     */
    static int themeOverlay(Object panel, Context context) {
        try {
            Method method = panel.getClass().getMethod("getThemeOverlay", Context.class);
            Object overlay = method.invoke(panel, context);
            return overlay instanceof Integer ? (Integer) overlay : 0;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (!overlayFailureLogged) {
                overlayFailureLogged = true;
                Logger.printException(() -> "A LIVE panel has no theme overlay to darken it with", failure);
            }
            return 0;
        }
    }
}
